package com.technocat.slidebit

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CachedMetadata(
    val title: String,
    val artist: String,
    val lastModified: Long,
    val size: Long
)

class MetadataCache(private val context: Context) {
    private val cacheFile = File(context.cacheDir, "metadata_cache.json")
    private val cache = ConcurrentHashMap<String, CachedMetadata>()

    init {
        loadFromDisk()
    }

    fun get(uri: String): CachedMetadata? {
        return cache[uri]
    }

    fun put(uri: String, title: String, artist: String, lastModified: Long, size: Long) {
        cache[uri] = CachedMetadata(title, artist, lastModified, size)
    }

    fun clear() {
        cache.clear()
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
    }

    fun loadFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val jsonStr = cacheFile.readText()
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.getJSONObject(key)
                cache[key] = CachedMetadata(
                    title = obj.optString("title", ""),
                    artist = obj.optString("artist", ""),
                    lastModified = obj.optLong("lastModified", 0L),
                    size = obj.optLong("size", 0L)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveToDisk() {
        try {
            val json = JSONObject()
            for ((key, value) in cache) {
                val obj = JSONObject().apply {
                    put("title", value.title)
                    put("artist", value.artist)
                    put("lastModified", value.lastModified)
                    put("size", value.size)
                }
                json.put(key, obj)
            }
            cacheFile.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
