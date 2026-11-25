package org.cryptomator.data.cloud.drime

import android.util.LruCache

/**
 * Drime addresses entries by id only, so the ids of the paths already seen are cached to avoid listing every folder along a path again.
 */
internal class DrimeIdCache {

	private val cache: LruCache<String, NodeInfo> = LruCache(1000)

	operator fun get(path: String): NodeInfo? {
		return cache[path]
	}

	/**
	 * Drops the cached descendants as well if the path now belongs to another entry, as theirs belong to the replaced one.
	 */
	fun add(path: String, info: NodeInfo) {
		if (cache[path]?.id?.equals(info.id) == false) {
			removeChildren(path)
		}
		cache.put(path, info)
	}

	fun remove(path: String) {
		removeChildren(path)
		cache.remove(path)
	}

	fun childPaths(path: String): List<String> {
		val prefix = "$path/"
		return cache.snapshot().keys.filter { it.startsWith(prefix) && it.indexOf('/', prefix.length) == -1 }
	}

	private fun removeChildren(path: String) {
		val prefix = "$path/"
		for (key in cache.snapshot().keys) {
			if (key.startsWith(prefix)) {
				cache.remove(key)
			}
		}
	}

	/**
	 * @param id the id of the entry, `null` for the root folder
	 */
	internal class NodeInfo(val id: String?, val isFolder: Boolean, val hash: String? = null, val url: String? = null) {

		constructor(item: DrimeItem) : this(item.id ?: throw IllegalArgumentException("Drime entry without id"), item.isFolder, item.hash, item.url)
	}
}
