package org.cryptomator.data.cloud.drime

import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class DrimeApiTest {

	private val responses = CannedResponses()
	private val inTest = DrimeApi(OkHttpClient.Builder().addInterceptor(responses).build(), "token")

	@Test
	fun testRequestsAreAuthorizedWithTheAccessToken() {
		responses.add(200, """{"user":{"id":1,"email":"user@example.com"},"status":"success"}""")

		val user = inTest.currentUser()

		assertThat(user.email, `is`("user@example.com"))
		assertThat(responses.requests[0].header("Authorization"), `is`("Bearer token"))
		assertThat(responses.requests[0].url.toString(), `is`("https://app.drime.cloud/api/v1/users/me"))
	}

	@Test
	fun testRateLimitIsRetried() {
		responses.add(429, """{"message":"Too Many Attempts."}""")
		responses.add(200, """{"data":[],"current_page":1,"last_page":1}""")

		inTest.folderItems(null, 1)

		assertThat(responses.requests.size, `is`(2))
	}

	@Test
	fun testRateLimitKeepsDrimesErrorWhenRetriesRunOut() {
		repeat(6) { responses.add(429, """{"message":"Too Many Attempts."}""") }

		val exception = assertThrows(DrimeApiException::class.java) { inTest.delete("1") }

		assertThat(exception.statusCode, `is`(429))
		assertThat(responses.requests.size, `is`(6))
	}

	@Test
	fun testUnauthenticatedIsAnAuthenticationError() {
		responses.add(401, """{"message":"Unauthenticated."}""")

		val exception = assertThrows(DrimeApiException::class.java) { inTest.currentUser() }

		assertThat(exception.isAuthenticationError, `is`(true))
		assertThat(responses.requests.size, `is`(1))
	}

	@Test
	fun testListingTheRootOmitsTheFolderId() {
		responses.add(200, """{"data":[{"id":5,"name":"a","type":"folder"}],"current_page":1,"last_page":1}""")

		val listing = inTest.folderItems(null, 1)

		assertThat(responses.requests[0].url.queryParameter("folderId"), `is`(nullValue()))
		assertThat(listing.data!![0].id, `is`("5"))
		assertThat(listing.data!![0].isFolder, `is`(true))
	}

	@Test
	fun testListingAFolderPassesItsId() {
		responses.add(200, """{"data":[],"current_page":2,"last_page":2}""")

		inTest.folderItems("42", 2)

		assertThat(responses.requests[0].url.queryParameter("folderId"), `is`("42"))
		assertThat(responses.requests[0].url.queryParameter("page"), `is`("2"))
	}

	@Test
	fun testCreatingAFolderInTheRootSendsNullAsParent() {
		responses.add(200, """{"status":"success","folder":{"id":7,"name":"folder","type":"folder"}}""")

		inTest.createFolder(null, "folder")

		assertThat(bodyOf(responses.requests[0]), `is`("""{"name":"folder","parentId":null}"""))
	}

	@Test
	fun testCreatingAFolderSendsTheParentIdAsNumber() {
		responses.add(200, """{"status":"success","folder":{"id":7,"name":"folder","type":"folder"}}""")

		inTest.createFolder("3", "folder")

		assertThat(bodyOf(responses.requests[0]), `is`("""{"name":"folder","parentId":3}"""))
	}

	@Test
	fun testRenameUsesMethodSpoofing() {
		responses.add(200, """{"status":"success","fileEntry":{"id":7,"name":"new","type":"file"}}""")

		inTest.rename("7", "new")

		assertThat(responses.requests[0].method, `is`("POST"))
		assertThat(responses.requests[0].header("X-HTTP-Method-Override"), `is`("PUT"))
		assertThat(responses.requests[0].url.toString(), `is`("https://app.drime.cloud/api/v1/file-entries/7"))
	}

	@Test
	fun testUploadedPartsDontGetTheAccessToken() {
		responses.add(200, "", eTag = "\"abc\"")

		val eTag = inTest.uploadPart("https://storage.example.com/part?signature=1", ByteArray(4), 4)

		assertThat(eTag, `is`("\"abc\""))
		assertThat(responses.requests[0].header("Authorization"), `is`(nullValue()))
	}

	@Test
	fun testDownloadUsesTheRelativeUrlOfTheEntry() {
		responses.add(200, "content")

		inTest.download(DrimeIdCache.NodeInfo("1", false, "hash", "api/v1/file-entries/1?download=1")).close()

		assertThat(responses.requests[0].url.toString(), `is`("https://app.drime.cloud/api/v1/file-entries/1?download=1"))
	}

	@Test
	fun testDownloadFallsBackToTheHash() {
		responses.add(200, "content")

		inTest.download(DrimeIdCache.NodeInfo("1", false, "hash", null)).close()

		assertThat(responses.requests[0].url.toString(), `is`("https://app.drime.cloud/api/v1/file-entries/download/hash"))
	}

	@Test
	fun testMultipartEntryUsesTheStorageKeyAsFilename() {
		responses.add(200, """{"fileEntry":{"id":9,"name":"file","type":"file"}}""")

		inTest.createEntryForMultipartUpload(DrimeMultipartUpload("uploadId", "uploads/abc-123"), "3", "file", 10L)

		assertThat(
			bodyOf(responses.requests[0]), //
			`is`("""{"clientMime":"application/octet-stream","clientName":"file","filename":"abc-123","size":10,"clientExtension":"bin","parentId":3,"relativePath":"file"}""")
		)
	}

	private fun bodyOf(request: Request): String {
		val buffer = Buffer()
		request.body!!.writeTo(buffer)
		return buffer.readUtf8()
	}

	private class CannedResponses : Interceptor {

		private val pending = ArrayDeque<Triple<Int, String, String?>>()
		val requests = ArrayList<Request>()

		fun add(code: Int, body: String, eTag: String? = null) {
			pending.addLast(Triple(code, body, eTag))
		}

		override fun intercept(chain: Interceptor.Chain): Response {
			requests.add(chain.request())
			val (code, body, eTag) = pending.removeFirst()
			return Response.Builder() //
				.request(chain.request()) //
				.protocol(Protocol.HTTP_1_1) //
				.code(code) //
				.message("") //
				.header("Retry-After", "0") //
				.apply { eTag?.let { header("ETag", it) } } //
				.body(body.toResponseBody("application/json".toMediaType())) //
				.build()
		}
	}
}
