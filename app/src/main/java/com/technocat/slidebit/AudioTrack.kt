package com.technocat.slidebit

import android.net.Uri

// data class в Kotlin автоматически генерирует методы equals(), hashCode() и toString()
data class AudioTrack(
    val id: Long,          // Уникальный ID файла в базе данных MediaStore
    val title: String,     // Название трека
    val artist: String,    // Исполнитель
    val duration: Long,    // Длительность в миллисекундах
    val contentUri: Uri,   // Контентный URI файла (нужен для плеера)
    val path: String       // Физический путь (пригодится для генерации M3U)
)