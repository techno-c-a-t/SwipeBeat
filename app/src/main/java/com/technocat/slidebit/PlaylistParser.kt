package com.technocat.slidebit

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.util.UUID

class PlaylistParser(private val context: Context) {

    fun parseM3U8(fileUri: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    var currentTitle = ""
                    var currentArtist = ""

                    while (line != null) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty()) {
                            if (trimmedLine.startsWith("#EXTINF:")) {
                                // Parse #EXTINF:<duration>,<artist> - <title> or <title>
                                val commaIndex = trimmedLine.indexOf(',')
                                if (commaIndex != -1 && commaIndex < trimmedLine.length - 1) {
                                    val meta = trimmedLine.substring(commaIndex + 1).trim()
                                    val dashIndex = meta.indexOf(" - ")
                                    if (dashIndex != -1) {
                                        currentArtist = meta.substring(0, dashIndex).trim()
                                        currentTitle = meta.substring(dashIndex + 3).trim()
                                    } else {
                                        currentArtist = "Unknown Artist"
                                        currentTitle = meta
                                    }
                                }
                            } else if (!trimmedLine.startsWith("#")) {
                                // This is the file path/uri
                                val trackUri = if (trimmedLine.startsWith("content://") || trimmedLine.startsWith("file://")) {
                                    Uri.parse(trimmedLine)
                                } else {
                                    Uri.fromFile(File(trimmedLine))
                                }

                                if (currentTitle.isEmpty()) {
                                    // Fallback: use filename without extension
                                    val lastSlash = trimmedLine.lastIndexOf('/')
                                    val filename = if (lastSlash != -1) trimmedLine.substring(lastSlash + 1) else trimmedLine
                                    val dotIndex = filename.lastIndexOf('.')
                                    currentTitle = if (dotIndex != -1) filename.substring(0, dotIndex) else filename
                                    currentArtist = "Unknown Artist"
                                }

                                tracks.add(
                                    Track(
                                        id = UUID.randomUUID().toString(),
                                        title = currentTitle,
                                        artist = currentArtist,
                                        filePath = trimmedLine,
                                        uri = trackUri
                                    )
                                )

                                // Reset temp variables for next track
                                currentTitle = ""
                                currentArtist = ""
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tracks
    }
}
