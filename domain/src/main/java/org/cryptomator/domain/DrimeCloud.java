package org.cryptomator.domain;

import org.jetbrains.annotations.NotNull;

/**
 * A connection to <a href="https://drime.cloud">Drime</a>, authenticated with a personal API access token.
 * <p>
 * The access token is stored encrypted, see {@code CredentialCryptor}.
 */
public class DrimeCloud implements Cloud {

	private final Long id;
	private final String accessToken;
	private final String username;

	private DrimeCloud(Builder builder) {
		this.id = builder.id;
		this.accessToken = builder.accessToken;
		this.username = builder.username;
	}

	public static Builder aDrimeCloud() {
		return new Builder();
	}

	public static Builder aCopyOf(DrimeCloud drimeCloud) {
		return new Builder() //
				.withId(drimeCloud.id()) //
				.withAccessToken(drimeCloud.accessToken()) //
				.withUsername(drimeCloud.username());
	}

	@Override
	public Long id() {
		return id;
	}

	public String accessToken() {
		return accessToken;
	}

	public String username() {
		return username;
	}

	@Override
	public CloudType type() {
		return CloudType.DRIME;
	}

	@Override
	public boolean configurationMatches(Cloud cloud) {
		return cloud instanceof DrimeCloud && configurationMatches((DrimeCloud) cloud);
	}

	private boolean configurationMatches(DrimeCloud cloud) {
		return username != null && username.equals(cloud.username);
	}

	@Override
	public boolean persistent() {
		return true;
	}

	@Override
	public boolean requiresNetwork() {
		return true;
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@NotNull
	@Override
	public String toString() {
		return "DRIME";
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		if (obj == this) {
			return true;
		}
		return internalEquals((DrimeCloud) obj);
	}

	@Override
	public int hashCode() {
		return id == null ? 0 : id.hashCode();
	}

	private boolean internalEquals(DrimeCloud obj) {
		return id != null && id.equals(obj.id);
	}

	public static class Builder {

		private Long id;
		private String accessToken;
		private String username;

		private Builder() {
		}

		public Builder withId(Long id) {
			this.id = id;
			return this;
		}

		public Builder withAccessToken(String accessToken) {
			this.accessToken = accessToken;
			return this;
		}

		public Builder withUsername(String username) {
			this.username = username;
			return this;
		}

		public DrimeCloud build() {
			return new DrimeCloud(this);
		}

	}

}
