package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.CloudFolder

open class DrimeFolder(override val parent: DrimeFolder?, override val name: String, override val path: String) : CloudFolder, DrimeNode {

	override val isFolder: Boolean = true

	override val cloud: Cloud?
		get() = parent?.cloud

	override fun withCloud(cloud: Cloud?): DrimeFolder? {
		return DrimeFolder(parent?.withCloud(cloud), name, path)
	}
}
