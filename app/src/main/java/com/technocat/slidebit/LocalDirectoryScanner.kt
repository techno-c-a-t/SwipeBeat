package com.technocat.slidebit

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

class LocalDirectoryScanner(private val context: Context) {

    fun scanSlideboxFolder(): List<Track> {
        val tracks = mutableListOf<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // Select the columns we want to retrieve
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )

        // Add RELATIVE_PATH for API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        // We want to filter for files in the Slidebox directory inside Download(s)
        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // RELATIVE_PATH matches "Download/Slidebox/" or "Downloads/Slidebox/"
            selection = "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?)"
            selectionArgs = arrayOf("Download/Slidebox%", "Downloads/Slidebox%")
        } else {
            // On older versions we filter by _data (physical path)
            selection = "(${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?)"
            selectionArgs = arrayOf("%/Download/Slidebox/%", "%/Downloads/Slidebox/%")
        }

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Unknown Track"
                    val artist = c.getString(artistCol) ?: "Unknown Artist"
                    val path = c.getString(dataCol) ?: ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    // Verify that the file has a valid audio extension
                    val lowerPath = path.lowercase()
                    if (lowerPath.endsWith(".mp3") || lowerPath.endsWith(".wav") || 
                        lowerPath.endsWith(".ogg") || lowerPath.endsWith(".flac") || 
                        lowerPath.endsWith(".m4a") || lowerPath.endsWith(".opus")) {
                        
                        tracks.add(
                            Track(
                                id = id.toString(),
                                title = title,
                                artist = artist,
                                filePath = path,
                                uri = contentUri
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return tracks
    }

    fun scanCustomFolderUri(treeUri: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc != null && rootDoc.isDirectory) {
                scanDirRecursive(rootDoc, tracks)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tracks
    }

    private fun getMetadata(file: androidx.documentfile.provider.DocumentFile): Pair<String, String> {
        val retriever = android.media.MediaMetadataRetriever()
        var title = file.name?.substringBeforeLast(".") ?: "Unknown Track"
        var artist = "Unknown Artist"
        try {
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val metaTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                if (!metaTitle.isNullOrEmpty()) {
                    title = metaTitle
                }
                if (!metaArtist.isNullOrEmpty()) {
                    artist = metaArtist
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
        return Pair(title, artist)
    }

    private fun scanDirRecursive(dir: androidx.documentfile.provider.DocumentFile, tracks: MutableList<Track>) {
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                scanDirRecursive(file, tracks)
            } else if (file.isFile) {
                val name = file.name ?: ""
                val lowerName = name.lowercase()
                if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || 
                    lowerName.endsWith(".ogg") || lowerName.endsWith(".flac") || 
                    lowerName.endsWith(".m4a") || lowerName.endsWith(".opus")) {
                    
                    val meta = getMetadata(file)
                    tracks.add(
                        Track(
                            id = file.uri.toString(),
                            title = meta.first,
                            artist = meta.second,
                            filePath = file.uri.toString(),
                            uri = file.uri
                        )
                    )
                }
            }
        }
    }
}
