package org.cryptomator.presentation.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import org.cryptomator.generator.Fragment
import org.cryptomator.presentation.databinding.FragmentSetupDrimeBinding
import org.cryptomator.presentation.model.DrimeCloudModel
import org.cryptomator.presentation.presenter.DrimeAddOrChangePresenter
import org.cryptomator.util.crypto.CredentialCryptor
import org.cryptomator.util.crypto.FatalCryptoException
import javax.inject.Inject
import timber.log.Timber

@Fragment
class DrimeAddOrChangeFragment : BaseFragment<FragmentSetupDrimeBinding>(FragmentSetupDrimeBinding::inflate) {

	@Inject
	lateinit var drimeAddOrChangePresenter: DrimeAddOrChangePresenter

	private var cloudId: Long? = null

	private val drimeCloudModel: DrimeCloudModel?
		get() = arguments?.getSerializable(ARG_DRIME_CLOUD) as? DrimeCloudModel

	override fun setupView() {
		binding.createCloudButton.setOnClickListener { createCloud() }
		binding.accessTokenEditText.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				createCloud()
			}
			false
		}
		binding.openDeveloperSettingsButton.setOnClickListener {
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_SETTINGS_URL)))
		}

		drimeCloudModel?.let {
			cloudId = it.id()
			binding.accessTokenEditText.setText(decrypt(it.accessToken()))
		}
	}

	private fun decrypt(text: String?): String {
		return if (text != null) {
			try {
				CredentialCryptor //
					.getInstance(activity?.applicationContext) //
					.decrypt(text)
			} catch (e: FatalCryptoException) {
				Timber.tag("DrimeAddOrChangeFragment").e(e, "Unable to decrypt access token, clearing it")
				""
			}
		} else ""
	}

	private fun createCloud() {
		drimeAddOrChangePresenter.checkUserInput(binding.accessTokenEditText.text.toString().trim(), cloudId)
	}

	fun hideKeyboard() {
		hideKeyboard(binding.accessTokenEditText)
	}

	companion object {

		private const val ARG_DRIME_CLOUD = "DRIME_CLOUD"
		private const val DEVELOPER_SETTINGS_URL = "https://app.drime.cloud/account-settings#developers"

		fun newInstance(cloudModel: DrimeCloudModel?): DrimeAddOrChangeFragment {
			val result = DrimeAddOrChangeFragment()
			val args = Bundle()
			args.putSerializable(ARG_DRIME_CLOUD, cloudModel)
			result.arguments = args
			return result
		}
	}

}
