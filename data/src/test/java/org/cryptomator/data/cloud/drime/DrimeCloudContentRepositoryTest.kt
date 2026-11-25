package org.cryptomator.data.cloud.drime

import android.content.Context
import android.util.LruCache
import org.cryptomator.domain.DrimeCloud
import org.cryptomator.domain.exception.CloudNodeAlreadyExistsException
import org.cryptomator.domain.exception.authentication.WrongCredentialsException
import org.cryptomator.domain.usecases.ProgressAware
import org.cryptomator.domain.usecases.cloud.ByteArrayDataSource
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DrimeCloudContentRepositoryTest {

	private val cloud = DrimeCloud.aDrimeCloud().withId(1L).withAccessToken("token").withUsername("user@example.com").build()
	private val api = mock<DrimeApi>()

	private lateinit var lruCaches: MockedConstruction<LruCache<*, *>>
	private lateinit var inTest: DrimeCloudContentRepository
	private lateinit var root: DrimeFolder

	@BeforeEach
	fun setup() {
		lruCaches = mapBackedLruCaches()
		inTest = DrimeCloudContentRepository(cloud, api, mock<Context>())
		root = inTest.root(cloud)
	}

	@AfterEach
	fun tearDown() {
		lruCaches.close()
	}

	@Test
	fun testListReadsAllPages() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(folder("1", "a")), 1, 2))
		whenever(api.folderItems(null, 2)).thenReturn(DrimeListing(listOf(file("2", "b"), trashedFile("3", "c")), 2, 2))

		val nodes = inTest.list(root)

		assertThat(nodes.map { it.path }, `is`(listOf("/a", "/b")))
		assertThat(nodes[0] is DrimeFolder, `is`(true))
		assertThat((nodes[1] as DrimeFile).size, `is`(10L))
	}

	@Test
	fun testNestedFoldersAreResolvedById() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(folder("1", "a")), 1, 1))
		whenever(api.folderItems("1", 1)).thenReturn(DrimeListing(listOf(file("2", "b")), 1, 1))

		val nodes = inTest.list(inTest.resolve(cloud, "/a"))

		assertThat(nodes.map { it.path }, `is`(listOf("/a/b")))
	}

	@Test
	fun testCreatingAnExistingFolderFails() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(folder("1", "folder")), 1, 1))

		val exception = assertThrows(CloudNodeAlreadyExistsException::class.java) { inTest.create(inTest.folder(root, "folder")) }

		assertThat(exception.message, `is`("folder"))
		verify(api, never()).createFolder(anyOrNull(), any())
	}

	@Test
	fun testCreateFolderInRoot() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(emptyList(), 1, 1))
		whenever(api.createFolder(null, "folder")).thenReturn(folder("5", "folder"))

		val created = inTest.create(inTest.folder(root, "folder"))

		assertThat(created.path, `is`("/folder"))
	}

	@Test
	fun testWritingAnExistingFileWithoutReplaceFails() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(file("1", "file")), 1, 1))

		assertThrows(CloudNodeAlreadyExistsException::class.java) { write("file", replace = false) }

		verify(api, never()).upload(anyOrNull(), any(), any())
	}

	@Test
	fun testReplacingAFileUploadsItAgainAndRemovesTheOldOne() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(file("1", "file")), 1, 1))
		whenever(api.upload(eq(null), eq("file"), any())).thenReturn(file("2", "file (1)"))
		whenever(api.rename("2", "file")).thenReturn(file("2", "file"))

		val written = write("file", replace = true)

		assertThat(written.path, `is`("/file"))
		val order = inOrder(api)
		order.verify(api).upload(eq(null), eq("file"), any())
		order.verify(api).delete("1")
		order.verify(api).rename("2", "file")
	}

	@Test
	fun testWritingANewFileKeepsItsName() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(emptyList(), 1, 1))
		whenever(api.upload(eq(null), eq("file"), any())).thenReturn(file("2", "file"))

		write("file", replace = false)

		verify(api, never()).rename(any(), any())
		verify(api, never()).delete(any())
	}

	@Test
	fun testMoveToAnotherFolderMovesAndRenames() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(folder("1", "a"), folder("2", "b")), 1, 1))
		whenever(api.folderItems("1", 1)).thenReturn(DrimeListing(listOf(file("3", "old")), 1, 1))
		whenever(api.folderItems("2", 1)).thenReturn(DrimeListing(emptyList(), 1, 1))
		whenever(api.rename("3", "new")).thenReturn(file("3", "new"))
		val a = inTest.folder(root, "a")
		val b = inTest.folder(root, "b")

		val moved = inTest.move(inTest.file(a, "old"), inTest.file(b, "new"))

		assertThat(moved.path, `is`("/b/new"))
		val order = inOrder(api)
		order.verify(api).move("3", "2")
		order.verify(api).rename("3", "new")
	}

	@Test
	fun testRenameWithinAFolderDoesNotMove() {
		whenever(api.folderItems(null, 1)).thenReturn(DrimeListing(listOf(file("3", "old")), 1, 1))
		whenever(api.rename("3", "new")).thenReturn(file("3", "new"))

		inTest.move(inTest.file(root, "old"), inTest.file(root, "new"))

		verify(api, never()).move(any(), anyOrNull())
	}

	@Test
	fun testRejectedTokenIsReportedAsWrongCredentials() {
		whenever(api.folderItems(null, 1)).thenThrow(DrimeApiException(401, "Unauthenticated."))

		assertThrows(WrongCredentialsException::class.java) { inTest.list(root) }
	}

	private fun write(name: String, replace: Boolean): DrimeFile {
		val data = ByteArrayDataSource.from(ByteArray(10))
		return inTest.write(inTest.file(root, name, 10L), data, ProgressAware.NO_OP_PROGRESS_AWARE_UPLOAD, replace, 10L)
	}

	/**
	 * android.util.LruCache is only a stub in JVM tests, so the id cache gets one backed by a map.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun mapBackedLruCaches(): MockedConstruction<LruCache<*, *>> {
		return Mockito.mockConstruction(LruCache::class.java as Class<LruCache<*, *>>) { cache, _ ->
			val entries = LinkedHashMap<Any?, Any?>()
			cache as LruCache<Any?, Any?>
			doAnswer { entries[it.arguments[0]] }.whenever(cache).get(any())
			doAnswer { entries.put(it.arguments[0], it.arguments[1]) }.whenever(cache).put(any(), any())
			doAnswer { entries.remove(it.arguments[0]) }.whenever(cache).remove(any())
			doAnswer { LinkedHashMap(entries) }.whenever(cache).snapshot()
		}
	}

	private fun folder(id: String, name: String): DrimeItem {
		return DrimeItem(id, name, "folder", null, null, null, null, null, null, null)
	}

	private fun file(id: String, name: String): DrimeItem {
		return DrimeItem(id, name, "file", 10L, null, null, "2024-01-01T00:00:00.000000Z", "hash$id", null, null)
	}

	private fun trashedFile(id: String, name: String): DrimeItem {
		return DrimeItem(id, name, "file", 10L, null, null, null, null, null, "2024-01-01T00:00:00.000000Z")
	}
}
