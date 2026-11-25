package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFile
import java.util.Date

internal class DrimeFile(override val parent: DrimeFolder, override val name: String, override val path: String, override val size: Long?, override val modified: Date?) : CloudFile, DrimeNode {

	override val isFolder: Boolean = false

	override val cloud: Cloud?
		get() = parent.cloud
}
