package org.cryptomator.domain.usecases.cloud;

import org.cryptomator.domain.DrimeCloud;
import org.cryptomator.domain.exception.BackendException;
import org.cryptomator.domain.repository.CloudContentRepository;
import org.cryptomator.generator.Parameter;
import org.cryptomator.generator.UseCase;

/**
 * Checks the access token of the cloud and returns the email address of the Drime account it belongs to.
 */
@UseCase
class ConnectToDrime {

	private final CloudContentRepository cloudContentRepository;
	private final DrimeCloud cloud;

	public ConnectToDrime(CloudContentRepository cloudContentRepository, @Parameter DrimeCloud cloud) {
		this.cloudContentRepository = cloudContentRepository;
		this.cloud = cloud;
	}

	public String execute() throws BackendException {
		return cloudContentRepository.checkAuthenticationAndRetrieveCurrentAccount(cloud);
	}
}
