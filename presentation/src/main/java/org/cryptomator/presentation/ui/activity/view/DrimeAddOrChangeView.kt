package org.cryptomator.presentation.ui.activity.view

interface DrimeAddOrChangeView : View {

	fun onCheckUserInputSucceeded(accessToken: String, cloudId: Long?)

}
