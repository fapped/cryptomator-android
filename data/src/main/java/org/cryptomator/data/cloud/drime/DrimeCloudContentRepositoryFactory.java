package org.cryptomator.data.cloud.drime;

import android.content.Context;

import org.cryptomator.data.repository.CloudContentRepositoryFactory;
import org.cryptomator.domain.Cloud;
import org.cryptomator.domain.DrimeCloud;
import org.cryptomator.domain.exception.authentication.NoAuthenticationProvidedException;
import org.cryptomator.domain.repository.CloudContentRepository;
import org.cryptomator.util.crypto.CredentialCryptor;

import javax.inject.Inject;
import javax.inject.Singleton;

import static org.cryptomator.domain.CloudType.DRIME;

@Singleton
public class DrimeCloudContentRepositoryFactory implements CloudContentRepositoryFactory {

	private final Context context;

	@Inject
	public DrimeCloudContentRepositoryFactory(Context context) {
		this.context = context;
	}

	@Override
	public boolean supports(Cloud cloud) {
		return cloud.type() == DRIME;
	}

	@Override
	public CloudContentRepository<DrimeCloud, DrimeNode, DrimeFolder, DrimeFile> cloudContentRepositoryFor(Cloud cloud) {
		DrimeCloud drimeCloud = (DrimeCloud) cloud;
		if (drimeCloud.accessToken() == null) {
			throw new NoAuthenticationProvidedException(drimeCloud);
		}
		String accessToken = CredentialCryptor.getInstance(context).decrypt(drimeCloud.accessToken());
		return new DrimeCloudContentRepository(drimeCloud, new DrimeApi(DrimeHttpClient.INSTANCE.create(context), accessToken), context);
	}

}
