package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.CloudNode

interface DrimeNode : CloudNode {

	val isFolder: Boolean
	override val name: String
	override val path: String
	override val parent: DrimeFolder?

}
