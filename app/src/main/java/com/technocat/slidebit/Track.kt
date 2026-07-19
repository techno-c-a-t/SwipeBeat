package com.technocat.slidebit

import android.net.Uri

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val uri: Uri
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("id", id)
            put("title", title)
            put("artist", artist)
            put("filePath", filePath)
            put("uri", uri.toString())
        }
    }

    fun getMatchKey(): String {
        val metaKey = "${artist.trim()} - ${title.trim()}".lowercase()
        if (metaKey.replace("-", "").trim().isNotEmpty()) {
            return metaKey
        }
        return filePath.substringAfterLast('/').lowercase().trim()
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): Track {
            return Track(
                id = json.getString("id"),
                title = json.getString("title"),
                artist = json.getString("artist"),
                filePath = json.getString("filePath"),
                uri = Uri.parse(json.getString("uri"))
            )
        }
    }
}
