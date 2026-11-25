package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.exception.CancellationException
import org.cryptomator.domain.exception.FatalBackendException
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client of the Drime REST API, see https://docs.drime.cloud
 *
 * All requests address the personal workspace of the account the access token belongs to. A folder id of `null` addresses the root folder.
 */
internal class DrimeApi(private val httpClient: OkHttpClient, private val accessToken: String) {

	@Throws(IOException::class)
	fun currentUser(): DrimeUser {
		return DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/users/me")), DrimeUserResponse::class.java).user //
			?: throw FatalBackendException("Drime returned no user")
	}

	@Throws(IOException::class)
	fun folderItems(folderId: String?, page: Int): DrimeListing {
		val url = "$API_URL/drive/file-entries".toHttpUrl().newBuilder() //
			.addQueryParameter("perPage", PAGE_SIZE.toString()) //
			.addQueryParameter("page", page.toString()) //
			.apply { folderId?.let { addQueryParameter("folderId", it) } } //
			.build()
		return DrimeHttpClient.parse(execute(Request.Builder().url(url)), DrimeListing::class.java)
	}

	@Throws(IOException::class)
	fun createFolder(parentId: String?, name: String): DrimeItem {
		val body = json(mapOf("name" to name, "parentId" to idValue(parentId)))
		return DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/folders").post(body)), DrimeFolderResponse::class.java).folder //
			?: throw FatalBackendException("Drime returned no folder")
	}

	/**
	 * Drime's origin answers a literal PUT to this endpoint with a malformed response, so it is sent as POST with Laravel's method spoofing.
	 */
	@Throws(IOException::class)
	fun rename(id: String, name: String): DrimeItem {
		val request = Request.Builder() //
			.url("$API_URL/file-entries/$id") //
			.header("X-HTTP-Method-Override", "PUT") //
			.post(json(mapOf("name" to name)))
		return requireEntry(DrimeHttpClient.parse(execute(request), DrimeFileEntryResponse::class.java).fileEntry)
	}

	@Throws(IOException::class)
	fun move(id: String, destinationId: String?) {
		val body = json(mapOf("entryIds" to listOf(id), "destinationId" to destinationId))
		DrimeHttpClient.requireSuccess(execute(Request.Builder().url("$API_URL/file-entries/move").post(body)))
	}

	/**
	 * Moves the entry into the trash, from where it can still be restored using the Drime web app.
	 */
	@Throws(IOException::class)
	fun delete(id: String) {
		val body = json(mapOf("entryIds" to listOf(id), "deleteForever" to false))
		DrimeHttpClient.requireSuccess(execute(Request.Builder().url("$API_URL/file-entries/delete").post(body)))
	}

	/**
	 * The caller has to close the returned response.
	 */
	@Throws(IOException::class)
	fun download(info: DrimeIdCache.NodeInfo): Response {
		val url = when {
			info.url.isNullOrEmpty() -> "$API_URL/file-entries/download/${info.hash ?: throw FatalBackendException("Drime file without url and hash")}"
			info.url.startsWith("http://") || info.url.startsWith("https://") -> info.url
			else -> BASE_URL + info.url.removePrefix("/")
		}
		val response = execute(Request.Builder().url(url))
		if (!response.isSuccessful) {
			DrimeHttpClient.requireSuccess(response)
		}
		return response
	}

	@Throws(IOException::class)
	fun upload(parentId: String?, name: String, content: RequestBody): DrimeItem {
		val body = MultipartBody.Builder() //
			.setType(MultipartBody.FORM) //
			.apply { parentId?.let { addFormDataPart("parentId", it) } } //
			.addFormDataPart("relativePath", name) //
			.addFormDataPart("file", name, content) //
			.build()
		return requireEntry(DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/uploads").post(body)), DrimeFileEntryResponse::class.java).fileEntry)
	}

	@Throws(IOException::class)
	fun createMultipartUpload(parentId: String?, name: String, size: Long): DrimeMultipartUpload {
		val body = json(
			mapOf(
				"filename" to name, //
				"mime" to MIME_TYPE, //
				"size" to size, //
				"extension" to extensionOf(name), //
				"parentId" to idValue(parentId), //
				"relativePath" to name
			)
		)
		return DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/s3/multipart/create").post(body)), DrimeMultipartUpload::class.java)
	}

	@Throws(IOException::class)
	fun signPartUrl(upload: DrimeMultipartUpload, partNumber: Int): String {
		val body = json(mapOf("uploadId" to upload.uploadId, "key" to upload.key, "partNumbers" to listOf(partNumber)))
		val urls = DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/s3/multipart/batch-sign-part-urls").post(body)), DrimeSignedPartUrls::class.java).urls
		return urls?.firstOrNull { it.partNumber == partNumber }?.url ?: urls?.singleOrNull()?.url //
		?: throw FatalBackendException("Drime returned no upload url for part $partNumber")
	}

	/**
	 * Uploads the part directly to the storage behind Drime, which must not receive Drime's access token. Returns the ETag of the part.
	 */
	@Throws(IOException::class)
	fun uploadPart(partUrl: String, part: ByteArray, length: Int): String {
		val request = Request.Builder().url(partUrl).put(part.toRequestBody(OCTET_STREAM, 0, length))
		val response = executeRetrying(request) { httpClient.newCall(it.build()).execute() }
		response.use {
			if (!it.isSuccessful) {
				throw DrimeApiException(it.code, "Uploading a part failed: " + (it.body?.string()?.take(500) ?: ""))
			}
			return it.header("ETag") ?: throw FatalBackendException("Storage returned no ETag for the uploaded part")
		}
	}

	@Throws(IOException::class)
	fun completeMultipartUpload(upload: DrimeMultipartUpload, parts: List<DrimeCompletedPart>) {
		val body = json(mapOf("uploadId" to upload.uploadId, "key" to upload.key, "parts" to parts.sortedBy { it.partNumber }))
		DrimeHttpClient.requireSuccess(execute(Request.Builder().url("$API_URL/s3/multipart/complete").post(body)))
	}

	@Throws(IOException::class)
	fun abortMultipartUpload(upload: DrimeMultipartUpload) {
		val body = json(mapOf("uploadId" to upload.uploadId, "key" to upload.key))
		DrimeHttpClient.requireSuccess(execute(Request.Builder().url("$API_URL/s3/multipart/abort").post(body)))
	}

	/**
	 * Creates the file entry for a completed multipart upload. Drime expects the last segment of the storage key as `filename` and the actual name as `clientName`.
	 */
	@Throws(IOException::class)
	fun createEntryForMultipartUpload(upload: DrimeMultipartUpload, parentId: String?, name: String, size: Long): DrimeItem {
		val body = json(
			mapOf(
				"clientMime" to MIME_TYPE, //
				"clientName" to name, //
				"filename" to upload.key.substringAfterLast('/'), //
				"size" to size, //
				"clientExtension" to extensionOf(name), //
				"parentId" to idValue(parentId), //
				"relativePath" to name
			)
		)
		return requireEntry(DrimeHttpClient.parse(execute(Request.Builder().url("$API_URL/s3/entries").post(body)), DrimeFileEntryResponse::class.java).fileEntry)
	}

	/**
	 * Drime's ids are numbers, which the JSON bodies carry as such.
	 */
	private fun idValue(id: String?): Any? {
		return id?.let { it.toLongOrNull() ?: it }
	}

	private fun requireEntry(item: DrimeItem?): DrimeItem {
		return item?.takeIf { it.id != null } ?: throw FatalBackendException("Drime returned no file entry")
	}

	/**
	 * Drime requires an extension for multipart uploads, which the encrypted names of Cryptomator mostly lack.
	 */
	private fun extensionOf(name: String): String {
		return name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH } ?: "bin"
	}

	private fun json(value: Any): RequestBody {
		return DrimeHttpClient.requestGson.toJson(value).toRequestBody(JSON)
	}

	@Throws(IOException::class)
	private fun execute(request: Request.Builder): Response {
		val authorized = request.header("Authorization", "Bearer $accessToken").header("Accept", "application/json")
		return executeRetrying(authorized) { httpClient.newCall(it.build()).execute() }
	}

	/**
	 * Repeats requests Drime rate limited or its proxy failed to answer. Returns the answer of the last attempt as it is, so callers get Drime's error.
	 */
	@Throws(IOException::class)
	private fun executeRetrying(request: Request.Builder, call: (Request.Builder) -> Response): Response {
		var attempt = 1
		while (true) {
			val response = call(request)
			if (attempt >= MAX_RETRY_ATTEMPTS || response.code !in RETRY_STATUS_CODES) {
				return response
			}
			val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
			response.close()
			try {
				Thread.sleep(retryAfterSeconds?.times(1000) ?: (attempt * attempt * RETRY_DELAY_STEP_MS))
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				throw CancellationException(e)
			}
			attempt++
		}
	}

	companion object {

		private const val BASE_URL = "https://app.drime.cloud/"
		private const val API_URL = BASE_URL + "api/v1"
		private const val PAGE_SIZE = 1000
		private const val MAX_RETRY_ATTEMPTS = 6
		private const val RETRY_DELAY_STEP_MS = 500L
		private const val MAX_EXTENSION_LENGTH = 10
		private const val MIME_TYPE = "application/octet-stream"
		private val RETRY_STATUS_CODES = setOf(429, 500, 502, 503, 504, 509, 520, 521, 522, 523, 524)
		private val JSON = "application/json; charset=utf-8".toMediaType()
		private val OCTET_STREAM = MIME_TYPE.toMediaType()
	}
}
