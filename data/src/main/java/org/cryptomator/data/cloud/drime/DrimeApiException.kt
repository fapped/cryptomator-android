package org.cryptomator.data.cloud.drime

import org.cryptomator.domain.exception.FatalBackendException

internal class DrimeApiException(val statusCode: Int, message: String?) : FatalBackendException("Drime API responded with $statusCode: $message") {

	val isAuthenticationError: Boolean
		get() = statusCode == 401

	val isForbidden: Boolean
		get() = statusCode == 403

	/**
	 * Drime answers 422 "entry ids is invalid" for entries that don't exist (anymore).
	 */
	val isNotFound: Boolean
		get() = statusCode == 404 || statusCode == 422 && message?.contains("invalid", ignoreCase = true) == true
}
