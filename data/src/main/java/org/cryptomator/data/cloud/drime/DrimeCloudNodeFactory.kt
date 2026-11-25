package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.exception.FatalBackendException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Date

internal object DrimeCloudNodeFactory {

	fun from(parent: DrimeFolder, item: DrimeItem): DrimeNode {
		return if (item.isFolder) {
			folder(parent, item)
		} else {
			file(parent, item)
		}
	}

	fun file(parent: DrimeFolder, item: DrimeItem): DrimeFile {
		val name = requireName(item)
		return DrimeFile(parent, name, getNodePath(parent, name), item.fileSize, lastModified(item))
	}

	fun file(parent: DrimeFolder, name: String, size: Long?): DrimeFile {
		return DrimeFile(parent, name, getNodePath(parent, name), size, null)
	}

	fun folder(parent: DrimeFolder, item: DrimeItem): DrimeFolder {
		val name = requireName(item)
		return DrimeFolder(parent, name, getNodePath(parent, name))
	}

	fun folder(parent: DrimeFolder, name: String): DrimeFolder {
		return DrimeFolder(parent, name, getNodePath(parent, name))
	}

	private fun requireName(item: DrimeItem): String {
		return item.name ?: throw FatalBackendException("Item name shouldn't be null")
	}

	private fun getNodePath(parent: DrimeFolder, name: String): String {
		return parent.path + "/" + name
	}

	private fun lastModified(item: DrimeItem): Date? {
		val timestamp = item.updatedAt ?: item.createdAt ?: return null
		return try {
			Date.from(OffsetDateTime.parse(timestamp).toInstant())
		} catch (e: DateTimeParseException) {
			null
		}
	}
}
