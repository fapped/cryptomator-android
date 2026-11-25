package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.DrimeCloud

internal class RootDrimeFolder(override val cloud: DrimeCloud) : DrimeFolder(null, "", "") {

	override fun withCloud(cloud: Cloud?): RootDrimeFolder {
		return RootDrimeFolder(cloud as DrimeCloud)
	}
}
