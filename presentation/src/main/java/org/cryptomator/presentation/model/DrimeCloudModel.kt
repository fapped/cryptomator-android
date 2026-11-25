package org.cryptomator.presentation.model

import org.cryptomator.domain.Cloud
import org.cryptomator.domain.DrimeCloud
import org.cryptomator.presentation.R

class DrimeCloudModel(cloud: Cloud) : CloudModel(cloud) {

	override fun name(): Int {
		return R.string.cloud_names_drime
	}

	override fun username(): String? {
		return cloud().username()
	}

	override fun cloudType(): CloudTypeModel {
		return CloudTypeModel.DRIME
	}

	fun id(): Long? {
		return cloud().id()
	}

	fun accessToken(): String? {
		return cloud().accessToken()
	}

	private fun cloud(): DrimeCloud {
		return toCloud() as DrimeCloud
	}
}
