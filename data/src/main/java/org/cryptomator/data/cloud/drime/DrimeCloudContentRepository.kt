package org.cryptomator.data.cloud.drime

import android.content.Context
import org.cryptomator.data.cloud.InterceptingCloudContentRepository
import org.cryptomator.domain.DrimeCloud
import org.cryptomator.domain.exception.BackendException
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.domain.exception.ForbiddenException
import org.cryptomator.domain.exception.NetworkConnectionException
import org.cryptomator.domain.exception.NoSuchCloudFileException
import org.cryptomator.domain.exception.authentication.WrongCredentialsException
import org.cryptomator.domain.repository.CloudContentRepository
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.DataSource
import org.cryptomator.domain.usecases.cloud.DownloadState
import org.cryptomator.domain.usecases.cloud.UploadState
import org.cryptomator.util.ExceptionUtil
import java.io.IOException
import java.io.OutputStream

internal class DrimeCloudContentRepository(private val cloud: DrimeCloud, api: DrimeApi, context: Context) :
	InterceptingCloudContentRepository<DrimeCloud, DrimeNode, DrimeFolder, DrimeFile>(Intercepted(cloud, api, context)) {

	@Throws(BackendException::class)
	override fun throwWrappedIfRequired(e: Exception) {
		throwApiExceptionIfRequired(e)
		throwNetworkConnectionExceptionIfRequired(e)
	}

	@Throws(BackendException::class)
	private fun throwApiExceptionIfRequired(e: Exception) {
		val apiException = ExceptionUtil.extract(e, DrimeApiException::class.java)
		if (apiException.isPresent) {
			when {
				apiException.get().isAuthenticationError -> throw WrongCredentialsException(cloud)
				apiException.get().isNotFound -> throw NoSuchCloudFileException()
				apiException.get().isForbidden -> throw ForbiddenException()
			}
		}
	}

	@Throws(NetworkConnectionException::class)
	private fun throwNetworkConnectionExceptionIfRequired(e: Exception) {
		if (ExceptionUtil.contains(e, IOException::class.java)) {
			throw NetworkConnectionException(e)
		}
	}

	private class Intercepted(cloud: DrimeCloud, api: DrimeApi, context: Context) : CloudContentRepository<DrimeCloud, DrimeNode, DrimeFolder, DrimeFile> {

		private val impl: DrimeImpl = DrimeImpl(cloud, api, DrimeIdCache(), context)

		override fun root(cloud: DrimeCloud): DrimeFolder {
			return impl.root()
		}

		override fun resolve(cloud: DrimeCloud, path: String): DrimeFolder {
			return impl.resolve(path)
		}

		override fun file(parent: DrimeFolder, name: String): DrimeFile {
			return impl.file(parent, name, null)
		}

		override fun file(parent: DrimeFolder, name: String, size: Long?): DrimeFile {
			return impl.file(parent, name, size)
		}

		override fun folder(parent: DrimeFolder, name: String): DrimeFolder {
			return impl.folder(parent, name)
		}

		@Throws(BackendException::class)
		override fun exists(node: DrimeNode): Boolean {
			return try {
				impl.exists(node)
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun metadata(file: DrimeFile): DrimeFile? {
			return try {
				impl.metadata(file)
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun list(folder: DrimeFolder): List<DrimeNode> {
			return try {
				impl.list(folder)
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun create(folder: DrimeFolder): DrimeFolder {
			return wrapped { impl.create(folder) }
		}

		@Throws(BackendException::class)
		override fun move(source: DrimeFolder, target: DrimeFolder): DrimeFolder {
			return wrapped { impl.move(source, target) as DrimeFolder }
		}

		@Throws(BackendException::class)
		override fun move(source: DrimeFile, target: DrimeFile): DrimeFile {
			return wrapped { impl.move(source, target) as DrimeFile }
		}

		@Throws(BackendException::class)
		override fun write(file: DrimeFile, data: DataSource, progressAware: ProgressAware<UploadState>, replace: Boolean, size: Long): DrimeFile {
			return wrapped { impl.write(file, data, progressAware, replace, size) }
		}

		@Throws(BackendException::class)
		private fun <T> wrapped(action: () -> T): T {
			return try {
				action()
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun read(file: DrimeFile, data: OutputStream, progressAware: ProgressAware<DownloadState>) {
			try {
				impl.read(file, data, progressAware)
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun delete(node: DrimeNode) {
			try {
				impl.delete(node)
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		@Throws(BackendException::class)
		override fun checkAuthenticationAndRetrieveCurrentAccount(cloud: DrimeCloud): String {
			return try {
				impl.currentAccount()
			} catch (e: IOException) {
				throw FatalBackendException(e)
			}
		}

		override fun logout(cloud: DrimeCloud) {
			impl.logout()
		}
	}
}
