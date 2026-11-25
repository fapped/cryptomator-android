package org.cryptomator.data.cloud.drime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.cryptomator.data.cloud.okhttplogging.HttpLoggingInterceptor
import org.cryptomator.data.util.NetworkTimeout
import org.cryptomator.data.util.UserAgentInterceptor
import org.cryptomator.domain.exception.FatalBackendException
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import timber.log.Timber

internal object DrimeHttpClient {

	val gson: Gson = Gson()

	/**
	 * Writes `null` values, as Drime expects `null` to address the root folder.
	 */
	val requestGson: Gson = GsonBuilder().serializeNulls().create()

	fun create(context: Context): OkHttpClient {
		return OkHttpClient() //
			.newBuilder() //
			.connectTimeout(NetworkTimeout.CONNECTION.timeout, NetworkTimeout.CONNECTION.unit) //
			.readTimeout(NetworkTimeout.READ.timeout, NetworkTimeout.READ.unit) //
			.writeTimeout(NetworkTimeout.WRITE.timeout, NetworkTimeout.WRITE.unit) //
			.addInterceptor(httpLoggingInterceptor(context)) //
			.addInterceptor(UserAgentInterceptor()) //
			.build()
	}

	/**
	 * Returns the parsed body of a successful response and throws a [DrimeApiException] for any other, closing the response in both cases.
	 */
	@Throws(IOException::class)
	fun <T> parse(response: Response, type: Class<T>): T {
		response.use {
			val body = it.body?.string() ?: ""
			if (!it.isSuccessful) {
				throw apiException(it.code, body)
			}
			return try {
				gson.fromJson(body, type)
			} catch (e: JsonSyntaxException) {
				throw FatalBackendException("Drime responded with an unexpected body", e)
			} ?: throw FatalBackendException("Drime responded with an empty body")
		}
	}

	@Throws(IOException::class)
	fun requireSuccess(response: Response) {
		response.use {
			if (!it.isSuccessful) {
				throw apiException(it.code, it.body?.string() ?: "")
			}
		}
	}

	fun apiException(statusCode: Int, body: String): DrimeApiException {
		val message = try {
			gson.fromJson(body, DrimeError::class.java)?.message
		} catch (e: JsonSyntaxException) {
			null
		} catch (e: IllegalStateException) {
			null
		}
		return DrimeApiException(statusCode, message ?: body.take(MAX_ERROR_MESSAGE_LENGTH))
	}

	private fun httpLoggingInterceptor(context: Context): Interceptor {
		val logger = object : HttpLoggingInterceptor.Logger {
			override fun log(message: String) {
				Timber.tag("OkHttp").d(message)
			}
		}
		return HttpLoggingInterceptor(logger, context)
	}

	private const val MAX_ERROR_MESSAGE_LENGTH = 500
}
