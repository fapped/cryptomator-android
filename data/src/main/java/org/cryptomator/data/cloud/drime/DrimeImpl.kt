package org.cryptomator.data.cloud.drime

import android.content.Context
import org.cryptomator.data.cloud.webdav.network.DataSourceBasedRequestBody
import org.cryptomator.data.util.CopyStream
import org.cryptomator.data.util.TransferredBytesAwareInputStream
import org.cryptomator.data.util.TransferredBytesAwareOutputStream
import org.cryptomator.domain.DrimeCloud
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.CloudNodeAlreadyExistsException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.exception.ParentFolderIsNullException
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.cryptomator.domain.usecases.cloud.DownloadState
import org.cryptomator.domain.usecases.cloud.Progress
import org.cryptomator.domain.usecases.cloud.UploadState
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import timber.log.Timber

internal class DrimeImpl(private val cloud: DrimeCloud, private val api: DrimeApi, private val idCache: DrimeIdCache, private val context: Context) {

	fun root(): DrimeFolder {
		return RootDrimeFolder(cloud)
	}

	fun resolve(path: String): DrimeFolder {
		val names = path.removePrefix("/").split("/").toTypedArray()
		var folder = root()
		for (name in names) {
			folder = folder(folder, name)
		}
		return folder
	}

	fun file(parent: DrimeFolder, name: String, size: Long?): DrimeFile {
		return DrimeCloudNodeFactory.file(parent, name, size)
	}

	fun folder(parent: DrimeFolder, name: String): DrimeFolder {
		return DrimeCloudNodeFactory.folder(parent, name)
	}

	@Throws(IOException::class)
	fun exists(node: DrimeNode): Boolean {
		return currentNodeInfo(node) != null
	}

	@Throws(IOException::class)
	fun metadata(file: DrimeFile): DrimeFile? {
		val item = currentItem(file) ?: return null
		return DrimeCloudNodeFactory.file(file.parent, item)
	}

	@Throws(IOException::class, BackendException::class)
	fun list(folder: DrimeFolder): List<DrimeNode> {
		val folderInfo = requireNodeInfo(folder)
		val vanishedChildren = idCache.childPaths(folder.path).toMutableSet()
		val result: MutableList<DrimeNode> = ArrayList()
		forEachChild(folderInfo.id) { item ->
			val node = cacheNodeInfo(DrimeCloudNodeFactory.from(folder, item), item)
			vanishedChildren.remove(node.path)
			result.add(node)
			false
		}
		vanishedChildren.forEach { idCache.remove(it) }
		return result
	}

	/**
	 * Drime gives a new folder another name instead of failing if the name is taken, so the name is checked first.
	 */
	@Throws(IOException::class, BackendException::class)
	fun create(folder: DrimeFolder): DrimeFolder {
		val parent = folder.parent ?: throw ParentFolderIsNullException(folder.name)
		val parentId = existingOrCreatedFolderInfo(parent).id
		if (currentNodeInfo(folder, matchingType = false) != null) {
			throw CloudNodeAlreadyExistsException(folder.name)
		}
		val createdFolder = api.createFolder(parentId, folder.name)
		return cacheNodeInfo(DrimeCloudNodeFactory.folder(parent, createdFolder), createdFolder)
	}

	@Throws(IOException::class, BackendException::class)
	private fun existingOrCreatedFolderInfo(folder: DrimeFolder): DrimeIdCache.NodeInfo {
		return nodeInfo(folder) ?: requireNodeInfo(create(folder))
	}

	/**
	 * Moves the entry if the parent changes and renames it afterwards, which also restores the name if Drime gave it another one while moving.
	 */
	@Throws(IOException::class, BackendException::class)
	fun move(source: DrimeNode, target: DrimeNode): DrimeNode {
		val targetParent = target.parent ?: throw ParentFolderIsNullException(target.name)
		if (currentNodeInfo(target, matchingType = false) != null) {
			throw CloudNodeAlreadyExistsException(target.name)
		}
		val id = requireNodeInfo(source).id ?: throw FatalBackendException("The root folder can't be moved")
		if (source.parent?.path != targetParent.path) {
			api.move(id, requireNodeInfo(targetParent).id)
		}
		val movedItem = api.rename(id, target.name)
		idCache.remove(source.path)
		return cacheNodeInfo(DrimeCloudNodeFactory.from(targetParent, movedItem), movedItem)
	}

	/**
	 * Drime can't replace the content of a file, so the new content is uploaded as another file and the existing one is moved to the trash afterwards.
	 */
	@Throws(IOException::class, BackendException::class)
	fun write(file: DrimeFile, data: DataSource, progressAware: ProgressAware<UploadState>, replace: Boolean, size: Long): DrimeFile {
		val existingFile = currentNodeInfo(file, matchingType = false)
		if (existingFile != null && (!replace || existingFile.isFolder)) {
			throw CloudNodeAlreadyExistsException(file.name)
		}
		val parentId = requireNodeInfo(file.parent).id
		progressAware.onProgress(Progress.started(UploadState.upload(file)))
		var uploadedFile = if (size <= SINGLE_UPLOAD_LIMIT) {
			uploadFile(file, parentId, data, progressAware, size)
		} else {
			multipartUploadFile(file, parentId, data, progressAware, size)
		}
		existingFile?.id?.let { deleteIgnoringMissing(it) }
		if (uploadedFile.name != file.name) {
			uploadedFile = api.rename(uploadedFile.id ?: throw FatalBackendException("Drime returned no file id"), file.name)
		}
		progressAware.onProgress(Progress.completed(UploadState.upload(file)))
		return cacheNodeInfo(DrimeCloudNodeFactory.file(file.parent, uploadedFile), uploadedFile)
	}

	private fun deleteIgnoringMissing(id: String) {
		try {
			api.delete(id)
		} catch (e: DrimeApiException) {
			if (!e.isNotFound) {
				throw e
			}
		}
	}

	private fun uploadFile(file: DrimeFile, parentId: String?, data: DataSource, progressAware: ProgressAware<UploadState>, size: Long): DrimeItem {
		val content = DataSourceBasedRequestBody.from(context, data, size) { inputStream: InputStream ->
			object : TransferredBytesAwareInputStream(inputStream) {
				override fun bytesTransferred(transferred: Long) {
					progressAware.onProgress(Progress.progress(UploadState.upload(file)).between(0).and(size).withValue(transferred))
				}
			}
		}
		return api.upload(parentId, file.name, content)
	}

	/**
	 * Larger files are uploaded in parts directly to the storage behind Drime, as a single request would exceed the size its proxy accepts.
	 */
	private fun multipartUploadFile(file: DrimeFile, parentId: String?, data: DataSource, progressAware: ProgressAware<UploadState>, size: Long): DrimeItem {
		val upload = api.createMultipartUpload(parentId, file.name, size)
		try {
			val parts: MutableList<DrimeCompletedPart> = ArrayList()
			val buffer = ByteArray(PART_SIZE)
			data.open(context)?.use { inputStream ->
				var offset = 0L
				var partNumber = 1
				while (offset < size) {
					val length = readPart(inputStream, buffer, minOf(PART_SIZE.toLong(), size - offset).toInt())
					val eTag = api.uploadPart(api.signPartUrl(upload, partNumber), buffer, length)
					parts.add(DrimeCompletedPart(partNumber, eTag))
					offset += length
					partNumber++
					progressAware.onProgress(Progress.progress(UploadState.upload(file)).between(0).and(size).withValue(offset))
				}
			} ?: throw FatalBackendException("InputStream shouldn't be null")
			api.completeMultipartUpload(upload, parts)
		} catch (e: Exception) {
			abortQuietly(upload)
			throw e
		}
		return api.createEntryForMultipartUpload(upload, parentId, file.name, size)
	}

	private fun abortQuietly(upload: DrimeMultipartUpload) {
		try {
			api.abortMultipartUpload(upload)
		} catch (e: Exception) {
			Timber.tag("DrimeImpl").w(e, "Failed to abort multipart upload")
		}
	}

	private fun readPart(inputStream: InputStream, buffer: ByteArray, length: Int): Int {
		var read = 0
		while (read < length) {
			val count = inputStream.read(buffer, read, length - read)
			if (count == -1) {
				throw FatalBackendException("Data ended before reaching the announced size")
			}
			read += count
		}
		return read
	}

	@Throws(IOException::class, BackendException::class)
	fun read(file: DrimeFile, data: OutputStream, progressAware: ProgressAware<DownloadState>) {
		progressAware.onProgress(Progress.started(DownloadState.download(file)))
		val nodeInfo = requireNodeInfo(file)
		api.download(nodeInfo).use { response ->
			val inputStream = response.body?.byteStream() ?: throw FatalBackendException("InputStream shouldn't be null")
			object : TransferredBytesAwareOutputStream(data) {
				override fun bytesTransferred(transferred: Long) {
					progressAware.onProgress(Progress.progress(DownloadState.download(file)).between(0).and(file.size ?: Long.MAX_VALUE).withValue(transferred))
				}
			}.use { out -> CopyStream.copyStreamToStream(inputStream, out) }
		}
		progressAware.onProgress(Progress.completed(DownloadState.download(file)))
	}

	@Throws(IOException::class, BackendException::class)
	fun delete(node: DrimeNode) {
		val id = requireNodeInfo(node).id ?: throw FatalBackendException("The root folder can't be deleted")
		api.delete(id)
		idCache.remove(node.path)
	}

	@Throws(IOException::class)
	fun currentAccount(): String {
		val user = api.currentUser()
		return user.email ?: user.displayName ?: throw FatalBackendException("Drime account has no email address")
	}

	fun logout() {
		// connections are removed instead of being logged out, the access token can be revoked in the Drime web app
	}

	@Throws(IOException::class, NoSuchCloudFileException::class)
	private fun requireNodeInfo(node: DrimeNode): DrimeIdCache.NodeInfo {
		return nodeInfo(node) ?: throw NoSuchCloudFileException(node.path)
	}

	@Throws(IOException::class)
	private fun nodeInfo(node: DrimeNode): DrimeIdCache.NodeInfo? {
		val info = idCache[node.path] ?: loadNodeInfo(node) ?: return null
		return if (info.isFolder == node.isFolder) info else null
	}

	/**
	 * Like [nodeInfo] but asks Drime instead of trusting the cache, which might be outdated.
	 *
	 * @param matchingType `false` to also return the info of an entry of the other type using the name
	 */
	@Throws(IOException::class)
	private fun currentNodeInfo(node: DrimeNode, matchingType: Boolean = true): DrimeIdCache.NodeInfo? {
		if (node.parent == null) {
			return ROOT_NODE_INFO
		}
		val info = currentItem(node)?.let { DrimeIdCache.NodeInfo(it) }
		return info?.takeIf { !matchingType || it.isFolder == node.isFolder }
	}

	/**
	 * Looks the node up in its parent folder, resolving the parent again if its cached id belongs to a folder that doesn't exist anymore.
	 */
	@Throws(IOException::class)
	private fun currentItem(node: DrimeNode): DrimeItem? {
		val parent = node.parent ?: return null
		val item = try {
			findInParent(node, parent)
		} catch (e: DrimeApiException) {
			if (!e.isNotFound) {
				throw e
			}
			idCache.remove(parent.path)
			findInParent(node, parent)
		}
		if (item == null) {
			idCache.remove(node.path)
		}
		return item
	}

	@Throws(IOException::class)
	private fun findInParent(node: DrimeNode, parent: DrimeFolder): DrimeItem? {
		val parentInfo = nodeInfo(parent) ?: return null
		return findChild(parent, parentInfo, node.name)
	}

	@Throws(IOException::class)
	private fun loadNodeInfo(node: DrimeNode): DrimeIdCache.NodeInfo? {
		val parent = node.parent ?: return ROOT_NODE_INFO
		return findInParent(node, parent)?.let { DrimeIdCache.NodeInfo(it) }
	}

	/**
	 * Looks through the folder for the entry with the name, caching the siblings passed on the way.
	 */
	@Throws(IOException::class)
	private fun findChild(folder: DrimeFolder, folderInfo: DrimeIdCache.NodeInfo, name: String): DrimeItem? {
		var result: DrimeItem? = null
		forEachChild(folderInfo.id) { item ->
			val itemName = item.name
			if (itemName != null) {
				idCache.add(folder.path + "/" + itemName, DrimeIdCache.NodeInfo(item))
				if (itemName == name) {
					result = item
				}
			}
			result != null
		}
		return result
	}

	/**
	 * Iterates over the pages of the folder's entries until [action] returns `true`. Skips entries in the trash and those without id or name.
	 */
	@Throws(IOException::class)
	private fun forEachChild(folderId: String?, action: (DrimeItem) -> Boolean) {
		var page = 1
		while (true) {
			val listing = api.folderItems(folderId, page)
			if (listing.currentPage != null && listing.currentPage != page) {
				throw FatalBackendException("Folder has more entries than Drime lists")
			}
			listing.data?.forEach { item ->
				if (item.id != null && item.name != null && item.deletedAt == null && action(item)) {
					return
				}
			}
			val lastPage = listing.lastPage ?: return
			if (page >= lastPage) {
				return
			}
			page++
		}
	}

	private fun <T : DrimeNode> cacheNodeInfo(node: T, item: DrimeItem): T {
		idCache.add(node.path, DrimeIdCache.NodeInfo(item))
		return node
	}

	companion object {

		private const val SINGLE_UPLOAD_LIMIT = 5L shl 20
		private const val PART_SIZE = 8 shl 20
		private val ROOT_NODE_INFO = DrimeIdCache.NodeInfo(null, true)
	}
}
