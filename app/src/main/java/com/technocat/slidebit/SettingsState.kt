package com.technocat.slidebit

import android.net.Uri

enum class LogicMode {
    UNION,
    DUP,
    UNIQUE
}

data class SelectedSource(
    val uri: Uri,
    val displayName: String,
    val tracks: List<Track>,
    var isExcluded: Boolean = false
)

data class SettingsState(
    var isSmartJumpEnabled: Boolean = true,
    var smartJumpSeconds: Int = 30,
    var smartJumpPct: Int = 10,
    var vibrationStrength: Int = 80, // in milliseconds
    var cardScale: Float = 1.0f,
    var isAutoplayEnabled: Boolean = true,
    var autosaveInterval: Int = 5,
    var currentTheme: String = "dark", // "light", "dark", "amoled"
    var volume: Float = 1.0f,
    var speed: Float = 1.0f,
    var playlistName: String = "Sorted_Slidebox",
    var isDetailedSettingsEnabled: Boolean = false,
    var isAdvancedSourcesModeEnabled: Boolean = false,
    var isMetadataCacheEnabled: Boolean = true,
    var parseAllTextFilesAsPlaylists: Boolean = false,
    var blacklistedFolders: MutableList<String> = mutableListOf(
        "/storage/emulated/0/Music/SystemAlerts",
        "/storage/emulated/0/WhatsApp/Media/VoiceNotes"
    )
)
