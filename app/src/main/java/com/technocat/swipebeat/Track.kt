package com.technocat.swipebeat

import android.net.Uri

data class Track(
    val id: String,
    var title: String,
    var artist: String,
    val filePath: String,
    val uri: Uri,
    var metadataLoaded: Boolean = false
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("filePath", filePath)
            put("uri", uri.toString())
            put("metadataLoaded", metadataLoaded)
        }
    }

    fun getMatchKey(): String {
        return filePath.substringAfterLast('/').lowercase().trim()
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): Track {
            return Track(
                id = json.getString("id"),
                title = json.getString("title"),
                artist = json.getString("artist"),
                filePath = json.getString("filePath"),
                uri = Uri.parse(json.getString("uri")),
                metadataLoaded = json.optBoolean("metadataLoaded", false)
            )
        }
    }
}
