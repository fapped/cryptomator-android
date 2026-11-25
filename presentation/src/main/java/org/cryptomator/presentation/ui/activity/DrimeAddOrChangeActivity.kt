package org.cryptomator.presentation.ui.activity

import androidx.fragment.app.Fragment
import org.cryptomator.generator.Activity
import org.cryptomator.generator.InjectIntent
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.ActivityLayoutBinding
import org.cryptomator.presentation.intent.DrimeAddOrChangeIntent
import org.cryptomator.presentation.presenter.DrimeAddOrChangePresenter
import org.cryptomator.presentation.ui.activity.view.DrimeAddOrChangeView
import org.cryptomator.presentation.ui.fragment.DrimeAddOrChangeFragment
import javax.inject.Inject

@Activity
class DrimeAddOrChangeActivity : BaseActivity<ActivityLayoutBinding>(ActivityLayoutBinding::inflate), DrimeAddOrChangeView {

	@Inject
	lateinit var drimeAddOrChangePresenter: DrimeAddOrChangePresenter

	@InjectIntent
	lateinit var drimeAddOrChangeIntent: DrimeAddOrChangeIntent

	override fun setupView() {
		binding.mtToolbar.toolbar.setTitle(R.string.screen_drime_settings_title)
		setSupportActionBar(binding.mtToolbar.toolbar)
	}

	override fun createFragment(): Fragment = DrimeAddOrChangeFragment.newInstance(drimeAddOrChangeIntent.drimeCloud())

	override fun onCheckUserInputSucceeded(accessToken: String, cloudId: Long?) {
		drimeAddOrChangeFragment().hideKeyboard()
		drimeAddOrChangePresenter.authenticate(accessToken, cloudId)
	}

	private fun drimeAddOrChangeFragment(): DrimeAddOrChangeFragment = getCurrentFragment(R.id.fragment_container) as DrimeAddOrChangeFragment

}
