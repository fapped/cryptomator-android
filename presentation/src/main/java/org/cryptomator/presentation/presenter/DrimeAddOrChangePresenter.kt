package org.cryptomator.presentation.presenter

import android.widget.Toast
import org.cryptomator.domain.Cloud
import org.cryptomator.domain.DrimeCloud
import org.cryptomator.domain.di.PerView
import org.cryptomator.domain.usecases.cloud.AddOrChangeCloudConnectionUseCase
import org.cryptomator.domain.usecases.cloud.ConnectToDrimeUseCase
import org.cryptomator.presentation.R
import org.cryptomator.presentation.exception.ExceptionHandlers
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.model.ProgressStateModel
import org.cryptomator.presentation.ui.activity.view.DrimeAddOrChangeView
import org.cryptomator.util.crypto.CredentialCryptor
import javax.inject.Inject

@PerView
class DrimeAddOrChangePresenter @Inject internal constructor( //
	private val addOrChangeCloudConnectionUseCase: AddOrChangeCloudConnectionUseCase,  //
	private val connectToDrimeUseCase: ConnectToDrimeUseCase,  //
	exceptionMappings: ExceptionHandlers
) : Presenter<DrimeAddOrChangeView>(exceptionMappings) {

	fun checkUserInput(accessToken: String, cloudId: Long?) {
		if (accessToken.isEmpty()) {
			Toast.makeText(context(), getString(R.string.screen_drime_settings_msg_access_token_not_empty), Toast.LENGTH_SHORT).show()
		} else {
			view?.onCheckUserInputSucceeded(encrypt(accessToken), cloudId)
		}
	}

	private fun encrypt(text: String): String {
		return CredentialCryptor //
			.getInstance(context()) //
			.encrypt(text)
	}

	/**
	 * @param accessToken the encrypted access token
	 */
	fun authenticate(accessToken: String, cloudId: Long?) {
		var builder = DrimeCloud.aDrimeCloud().withAccessToken(accessToken)
		cloudId?.let { builder = builder.withId(it) }
		authenticate(builder.build())
	}

	private fun authenticate(cloud: DrimeCloud) {
		view?.showProgress(ProgressModel(ProgressStateModel.AUTHENTICATION))
		connectToDrimeUseCase //
			.withCloud(cloud) //
			.run(object : DefaultResultHandler<String>() {
				override fun onSuccess(username: String) {
					onCloudAuthenticated(DrimeCloud.aCopyOf(cloud).withUsername(username).build())
				}

				override fun onError(e: Throwable) {
					view?.showProgress(ProgressModel.COMPLETED)
					super.onError(e)
				}
			})
	}

	private fun onCloudAuthenticated(cloud: Cloud) {
		addOrChangeCloudConnectionUseCase //
			.withCloud(cloud) //
			.run(object : DefaultResultHandler<Void?>() {
				override fun onSuccess(void: Void?) {
					finishWithResult(CloudConnectionListPresenter.SELECTED_CLOUD, cloud)
				}

				override fun onError(e: Throwable) {
					view?.showProgress(ProgressModel.COMPLETED)
					super.onError(e)
				}
			})
	}

	init {
		unsubscribeOnDestroy(addOrChangeCloudConnectionUseCase, connectToDrimeUseCase)
	}
}
