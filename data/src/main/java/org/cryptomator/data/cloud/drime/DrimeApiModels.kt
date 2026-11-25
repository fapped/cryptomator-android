package org.cryptomator.data.cloud.drime

import com.google.gson.annotations.SerializedName

/**
 * A file entry of Drime, which is either a folder or a file of any other type.
 */
internal class DrimeItem(
	@SerializedName("id") val id: String?,
	@SerializedName("name") val name: String?,
	@SerializedName("type") val type: String?,
	@SerializedName("file_size") val fileSize: Long?,
	@SerializedName("parent_id") val parentId: String?,
	@SerializedName("created_at") val createdAt: String?,
	@SerializedName("updated_at") val updatedAt: String?,
	@SerializedName("hash") val hash: String?,
	@SerializedName("url") val url: String?,
	@SerializedName("deleted_at") val deletedAt: String?
) {

	val isFolder: Boolean
		get() = type == "folder"
}

internal class DrimeListing(
	@SerializedName("data") val data: List<DrimeItem>?,
	@SerializedName("current_page") val currentPage: Int?,
	@SerializedName("last_page") val lastPage: Int?
)

internal class DrimeFileEntryResponse(
	@SerializedName("fileEntry") val fileEntry: DrimeItem?
)

internal class DrimeFolderResponse(
	@SerializedName("folder") val folder: DrimeItem?
)

internal class DrimeUserResponse(
	@SerializedName("user") val user: DrimeUser?
)

internal class DrimeUser(
	@SerializedName("id") val id: String?,
	@SerializedName("email") val email: String?,
	@SerializedName("display_name") val displayName: String?
)

internal class DrimeError(
	@SerializedName("message") val message: String?
)

internal class DrimeMultipartUpload(
	@SerializedName("uploadId") val uploadId: String,
	@SerializedName("key") val key: String
)

internal class DrimeSignedPartUrls(
	@SerializedName("urls") val urls: List<DrimeSignedPartUrl>?
)

internal class DrimeSignedPartUrl(
	@SerializedName("url") val url: String,
	@SerializedName("partNumber") val partNumber: Int
)

internal class DrimeCompletedPart(
	@SerializedName("PartNumber") val partNumber: Int,
	@SerializedName("ETag") val eTag: String
)
