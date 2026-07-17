package com.technocat.slidebit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.technocat.slidebit.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFolderUri: Uri? = null
    private val scannedTracks = mutableListOf<AudioTrack>()

    // Название ключа для сохранения пути в настройках
    private val PREFS_KEY_FOLDER_URI = "saved_folder_uri"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) scanAudioFiles() else binding.tvLogs.text = "Ошибка: Доступ к файлам отклонен."
    }

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            selectedFolderUri = uri

            // СОХРАНЯЕМ URI НА ДИСК (в SharedPreferences)
            saveFolderUriToPrefs(uri)

            binding.tvLogs.text = "Папка успешно выбрана!\nURI папки: $uri"
        } else {
            binding.tvLogs.text = "Папка не была выбрана."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ВОССТАНАВЛИВАЕМ СОХРАНЕННЫЙ URI ПРИ СТАРТЕ
        restoreFolderUriFromPrefs()

        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }
        binding.btnSelectFolder.setOnClickListener { selectFolderLauncher.launch(null) }

        binding.btnCreatePlaylist.setOnClickListener {
            val uri = selectedFolderUri
            if (uri != null) writeM3UFile(uri) else binding.tvLogs.text = "Сначала выберите папку!"
        }
    }

    // Сохранение URI в SharedPreferences
    private fun saveFolderUriToPrefs(uri: Uri) {
        val sharedPrefs = getPreferences(Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(PREFS_KEY_FOLDER_URI, uri.toString()).apply()
    }

    // Чтение URI из SharedPreferences при запуске
    private fun restoreFolderUriFromPrefs() {
        val sharedPrefs = getPreferences(Context.MODE_PRIVATE)
        val savedUriString = sharedPrefs.getString(PREFS_KEY_FOLDER_URI, null)
        if (savedUriString != null) {
            selectedFolderUri = Uri.parse(savedUriString)
            binding.tvLogs.text = "Папка восстановлена из памяти:\n$selectedFolderUri"
        }
    }

    private fun writeM3UFile(folderUri: Uri) {
        if (scannedTracks.isEmpty()) {
            binding.tvLogs.text = "Ошибка: Сначала отсканируйте музыку, список пуст!"
            return
        }

        try {
            val documentId = DocumentsContract.getTreeDocumentId(folderUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)

            val newFileUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocumentUri,
                "application/octet-stream",
                "real_playlist.m3u"
            )

            if (newFileUri != null) {
                contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                    // Начинаем собирать текст плейлиста в формате M3U
                    val m3uBuilder = StringBuilder()
                    m3uBuilder.append("#EXTM3U\n") // Заголовок формата

                    for (track in scannedTracks) {
                        // Переводим длительность из миллисекунд в секунды для формата M3U
                        val durationInSeconds = track.duration / 1000

                        // Строка описания: #EXTINF:секунды,Исполнитель - Название
                        m3uBuilder.append("#EXTINF:$durationInSeconds,${track.artist} - ${track.title}\n")

                        // Строка пути к файлу
                        m3uBuilder.append("${track.path}\n")
                    }

                    outputStream.write(m3uBuilder.toString().toByteArray())
                }
                binding.tvLogs.text = "Плейлист 'real_playlist.m3u' успешно создан!\nВ него записано треков: ${scannedTracks.size}"
            } else {
                binding.tvLogs.text = "Не удалось создать файл."
            }
        } catch (e: Exception) {
            binding.tvLogs.text = "Ошибка при записи файла: ${e.localizedMessage}"
        }
    }

    // (Остальной код scanAudioFiles и checkPermissionsAndScan оставляем без изменений)
    private fun checkPermissionsAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanAudioFiles()
        } else {
            binding.tvLogs.text = "Запрос разрешения..."
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun scanAudioFiles() {
        binding.tvLogs.text = "Сканирование...\n"

        // Очищаем список перед новым сканированием
        scannedTracks.clear()

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        // Запрашиваем важные метаданные, включая физический путь (_data) и длительность
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA // Физический путь на диске
        )

        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(idColumn)
                val title = c.getString(titleColumn) ?: "Неизвестный трек"
                val artist = c.getString(artistColumn) ?: "Неизвестный исполнитель"
                val duration = c.getLong(durationColumn)
                val path = c.getString(dataColumn) ?: ""

                // Создаем контентный URI для плеера на основе ID
                val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())

                // Добавляем песню в наш список объектов
                scannedTracks.add(AudioTrack(id, title, artist, duration, contentUri, path))
            }
        }

        // Выводим результат на экран
        if (scannedTracks.isEmpty()) {
            binding.tvLogs.text = "Аудиофайлы не найдены."
        } else {
            val resultText = StringBuilder()
            resultText.append("Найдено реальных треков: ${scannedTracks.size}\n\n")
            scannedTracks.forEach { track ->
                resultText.append("${track.artist} — ${track.title}\n")
            }
            binding.tvLogs.text = resultText.toString()
        }
    }
}