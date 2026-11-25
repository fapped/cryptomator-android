package org.cryptomator.presentation.intent;

import org.cryptomator.generator.Intent;
import org.cryptomator.generator.Optional;
import org.cryptomator.presentation.model.DrimeCloudModel;
import org.cryptomator.presentation.ui.activity.DrimeAddOrChangeActivity;

@Intent(DrimeAddOrChangeActivity.class)
public interface DrimeAddOrChangeIntent {

	@Optional
	DrimeCloudModel drimeCloud();
}
