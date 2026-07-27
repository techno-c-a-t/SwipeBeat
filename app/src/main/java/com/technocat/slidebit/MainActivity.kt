package com.technocat.slidebit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.technocat.slidebit.databinding.ActivityMainBinding
import com.technocat.slidebit.databinding.LayoutSourcesSheetBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerEngine: AudioPlayerEngine
    private lateinit var tutorialManager: TutorialManager

    private val viewModel: SortingViewModel by lazy {
        ViewModelProvider(this)[SortingViewModel::class.java]
    }

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideHUDRunnable = Runnable {
        binding.layoutTopHUD.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.layoutTopHUD.visibility = View.GONE
                binding.tvTopTriggerHint.visibility = View.VISIBLE
                binding.tvTopTriggerHint.alpha = 0f
                binding.tvTopTriggerHint.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (viewModel.isPlaying.value == true) {
                val current = playerEngine.getCurrentPosition()
                val duration = playerEngine.getDuration()
                if (duration > 0) {
                    binding.sbTrackProgress.max = duration.toInt()
                    binding.sbTrackProgress.progress = current.toInt()
                    binding.tvTrackCurrentTime.text = formatTime(current)
                    binding.tvTrackDuration.text = formatTime(duration)
                }
            }
            progressHandler.postDelayed(this, 250)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(java.util.Locale.US, "%d:%02d", mins, secs)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readAudioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        val readStorageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false

        if (readAudioGranted || readStorageGranted) {
            binding.tvLogs.text = "Разрешение получено. Готово к работе."
        } else {
            binding.tvLogs.text = "Ошибка: Для работы приложения необходим доступ к файлам."
        }
    }

    private val selectM3u8Launcher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = getFileName(uri) ?: uri.toString()
            val isM3u = fileName.endsWith(".m3u", ignoreCase = true) || fileName.endsWith(".m3u8", ignoreCase = true)
            if (!isM3u && !viewModel.settings.parseAllTextFilesAsPlaylists) {
                Toast.makeText(this, "Пожалуйста, выберите файл M3U/M3U8. Вы можете включить поддержку любых текстовых файлов в Расширенных настройках.", Toast.LENGTH_LONG).show()
            } else {
                importPlaylistAsync(uri)
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scanCustomFolder(uri)
        }
    }

    private val selectBlacklistFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val path = uri.toString()
            if (!viewModel.settings.blacklistedFolders.contains(path)) {
                viewModel.settings.blacklistedFolders.add(path)
                saveSettingsToPrefs()
                updateBlacklistSettingsList()
                viewModel.combineSelectedSources()
                updatePrepScreenStatus()
            }
        }
    }

    private var isSavingPreviousSession = false

    private val createPlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        if (uri != null) {
            writePlaylistToUri(uri)
            if (isSavingPreviousSession) {
                clearSavedSession()
                isSavingPreviousSession = false
                updateResumeButtonState()
                updatePrepScreenStatus()
            }
        }
    }

    private fun clearSavedSession() {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            remove("SessionQueue")
            remove("SessionApproved")
            remove("SessionRejected")
            remove("SessionHistory")
            remove("SessionIndex")
            remove("SessionSourceStatus")
            apply()
        }
    }

    private fun savePreviousSessionDirectly() {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val approvedStr = prefs.getString("SessionApproved", null)
        if (!approvedStr.isNullOrEmpty() && approvedStr != "[]") {
            isSavingPreviousSession = true
            try {
                val approvedJson = org.json.JSONArray(approvedStr)
                val approved = mutableListOf<Track>()
                for (i in 0 until approvedJson.length()) {
                    approved.add(Track.fromJson(approvedJson.getJSONObject(i)))
                }
                viewModel.approvedTracks.clear()
                viewModel.approvedTracks.addAll(approved)
                
                val ext = if (approved.isNotEmpty()) {
                    getDefaultPlaylistExtension(approved)
                } else {
                    "m3u8"
                }
                val playlistName = prefs.getString("SettingPlaylistName", "playlist") ?: "playlist"
                createPlaylistLauncher.launch("$playlistName.$ext")
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Не удалось загрузить сохраненную сессию!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Нет сохраненной сессии или она пуста!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#09090B")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerEngine = AudioPlayerEngine(this)
        tutorialManager = TutorialManager(this, this, binding.tutorialOverlayView, viewModel)

        loadSettingsFromPrefs()
        setupListeners()
        setupObservers()
        setupGestures()
        setupLogicControl()
        setupSettingsControls()
        setupBackPressed()
        updatePrepScreenStatus()
        updateResumeButtonState()

        if (tutorialManager.isFirstLaunch()) {
            showWelcomeTutorialDialog()
        }
    }

    private fun setupListeners() {
        binding.cardSelectSource.setOnClickListener {
            showSourcesSheet()
        }

        binding.cardOutputPlaylist.setOnClickListener {
            showRenamePlaylistDialog()
        }

        binding.btnSettingsPrep.setOnClickListener {
            binding.screenPrep.visibility = View.GONE
            binding.screenSettings.visibility = View.VISIBLE
        }

        binding.btnBackFromSettings.setOnClickListener {
            binding.screenSettings.visibility = View.GONE
            binding.screenPrep.visibility = View.VISIBLE
            updatePrepScreenStatus()
            updateResumeButtonState()
        }

        binding.btnStartSort.setOnClickListener {
            if (viewModel.trackQueue.isNotEmpty()) {
                startSortingSession(viewModel.trackQueue.toList())
            } else {
                Toast.makeText(this, "Сначала выберите источник музыки!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnScanStop.setOnClickListener {
            val state = viewModel.scanProgress.value
            if (state != null && state.isPaused) {
                viewModel.resumeScanning(this)
            } else {
                viewModel.stopScanningAndKeep()
            }
        }

        binding.btnScanCancel.setOnClickListener {
            viewModel.cancelScanning()
        }

        binding.btnResumeSort.setOnClickListener {
            if (loadSessionFromPrefs()) {
                binding.screenPrep.visibility = View.GONE
                binding.screenSort.visibility = View.VISIBLE
                binding.layoutTopHUD.visibility = View.GONE
                binding.tvTopTriggerHint.visibility = View.VISIBLE
                binding.tvTopTriggerHint.alpha = 1.0f
            } else {
                Toast.makeText(this, "Нет сохраненной сессии", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSavePrevSession.setOnClickListener {
            savePreviousSessionDirectly()
        }

        binding.btnExitSort.setOnClickListener {
            exitSortingDirectly()
        }

        binding.btnOpenHistory.setOnClickListener {
            val historySheet = HistoryBottomSheetFragment()
            historySheet.show(supportFragmentManager, "history")
        }

        binding.viewTopTrigger.setOnClickListener {
            showHUD()
        }

        // Set initial SeekBars progresses from settings
        binding.sbVolume.progress = (viewModel.settings.volume * 100).toInt()
        binding.sbSpeed.progress = ((viewModel.settings.speed - 0.5f) / 0.1f).toInt()
        binding.tvSpeedLabel.text = String.format("⚡ %.1fx", viewModel.settings.speed)

        binding.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val vol = progress / 100f
                playerEngine.setVolume(vol)
                viewModel.settings.volume = vol
                saveSettingsToPrefs()
                if (fromUser) {
                    showHUD()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speedVal = 0.5f + (progress * 0.1f) // 0 -> 0.5, 5 -> 1.0, 15 -> 2.0
                binding.tvSpeedLabel.text = String.format("⚡ %.1fx", speedVal)
                playerEngine.setPlaybackSpeed(speedVal)
                viewModel.settings.speed = speedVal
                saveSettingsToPrefs()
                if (fromUser) {
                    showHUD()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnResetHUDControls.setOnClickListener {
            binding.sbVolume.progress = 80
            binding.sbSpeed.progress = 5
            binding.tvSpeedLabel.text = "⚡ 1.0x"
            playerEngine.setVolume(0.8f)
            playerEngine.setPlaybackSpeed(1.0f)
            viewModel.settings.volume = 0.8f
            viewModel.settings.speed = 1.0f
            saveSettingsToPrefs()
            showHUD()
        }

        binding.btnBlacklistAddManual.setOnClickListener {
            val path = binding.etBlacklistPath.text.toString().trim()
            if (path.isNotEmpty()) {
                if (!viewModel.settings.blacklistedFolders.contains(path)) {
                    viewModel.settings.blacklistedFolders.add(path)
                    saveSettingsToPrefs()
                    updateBlacklistSettingsList()
                    viewModel.combineSelectedSources()
                    updatePrepScreenStatus()
                }
                binding.etBlacklistPath.setText("")
            }
        }

        binding.btnBlacklistPickFolder.setOnClickListener {
            selectBlacklistFolderLauncher.launch(null)
        }

        updateBlacklistSettingsList()

        binding.sbTrackProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    playerEngine.seekTo(progress.toLong())
                    binding.tvTrackCurrentTime.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupObservers() {
        viewModel.currentTrack.observe(this) { track ->
            if (track != null) {
                resetCardAnimation()
                binding.tvTrackTitle.text = track.title
                binding.tvTrackArtist.text = track.artist
                loadAlbumArt(track)

                // Apply volume and speed
                playerEngine.setVolume(viewModel.settings.volume)
                playerEngine.setPlaybackSpeed(viewModel.settings.speed)

                val startMs = if (viewModel.settings.isSmartJumpEnabled) {
                    val secMs = viewModel.settings.smartJumpSeconds * 1000L
                    var pctMs = 0L
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(this, track.uri)
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val durationMs = durationStr?.toLongOrNull() ?: 0L
                        retriever.release()
                        if (durationMs > 0) {
                            pctMs = (durationMs * viewModel.settings.smartJumpPct) / 100L
                        }
                    } catch (e: Exception) {
                        // ignore fallback
                    }
                    maxOf(secMs, pctMs)
                } else {
                    0L
                }
                playerEngine.play(track, startMs)
            } else {
                if ((viewModel.currentIndex.value ?: 0) > 0) {
                    askSavePlaylist()
                    binding.screenPrep.visibility = View.VISIBLE
                    binding.screenSort.visibility = View.GONE
                    updatePrepScreenStatus()
                    updateResumeButtonState()
                }
            }
        }

        viewModel.currentIndex.observe(this) {
            updateCounterText()
            viewModel.preloadMetadataAroundCurrent(this)
        }

        viewModel.totalTracks.observe(this) {
            updateCounterText()
        }

        viewModel.isPlaying.observe(this) { isPlaying ->
            if (isPlaying) {
                binding.tvPauseIndicator.visibility = View.GONE
                playerEngine.resume()
            } else {
                binding.tvPauseIndicator.visibility = View.VISIBLE
                playerEngine.pause()
            }
        }

        viewModel.scanProgress.observe(this) { state ->
            if (state.isScanning) {
                binding.cardScanProgress.visibility = View.VISIBLE
                binding.btnStartSort.isEnabled = false
                binding.btnStartSort.alpha = 0.5f
                binding.btnScanStop.text = "СТОП"
                binding.btnScanStop.setTextColor(android.graphics.Color.parseColor("#E4E4E7"))

                val sourceProgressStr = if (state.totalSourcesToScan > 1) {
                    " (${state.totalScannedSources + 1} из ${state.totalSourcesToScan})"
                } else {
                    ""
                }
                binding.tvScanStatusTitle.text = "Сканирование: ${state.currentSource}$sourceProgressStr"
                binding.tvScanDetails.text = "Найдено файлов: ${state.filesFound}"
            } else if (state.isPaused) {
                binding.cardScanProgress.visibility = View.VISIBLE
                val hasTracks = viewModel.trackQueue.isNotEmpty()
                binding.btnStartSort.isEnabled = hasTracks
                binding.btnStartSort.alpha = if (hasTracks) 1.0f else 0.5f
                binding.btnScanStop.text = "ПРОДОЛЖИТЬ"
                binding.btnScanStop.setTextColor(android.graphics.Color.parseColor("#10B981"))

                binding.tvScanStatusTitle.text = "Сканирование приостановлено"
                binding.tvScanDetails.text = "Найдено файлов: ${state.filesFound}"
                viewModel.combineSelectedSources()
                updatePrepScreenStatus()
            } else {
                binding.cardScanProgress.visibility = View.GONE
                viewModel.combineSelectedSources()
                updatePrepScreenStatus()
                val hasTracks = viewModel.trackQueue.isNotEmpty()
                binding.btnStartSort.isEnabled = hasTracks
                binding.btnStartSort.alpha = if (hasTracks) 1.0f else 0.5f
            }
        }
    }

    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                viewModel.togglePlayPause()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffY) > Math.abs(diffX)) {
                    if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY < 0) {
                            onTrackSwipedUp()
                        } else {
                            onTrackSwipedDown()
                        }
                        return true
                    }
                } else {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        onTrackSwipedSide()
                        return true
                    }
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                binding.layoutInfoOverlay.visibility = View.VISIBLE
                viewModel.currentTrack.value?.let { track ->
                    binding.tvInfoPath.text = "Путь: ${track.filePath}"
                }
            }
        })

        binding.cardTrack.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                binding.layoutInfoOverlay.visibility = View.GONE
            }
            true
        }
    }

    private fun setupLogicControl() {
        binding.sbSettingsLogic.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mode = when (progress) {
                        0 -> LogicMode.UNION
                        1 -> LogicMode.DUP
                        else -> LogicMode.UNIQUE
                    }
                    syncLogicModeUI(mode)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        syncLogicModeUI(viewModel.selectedLogicMode)
    }

    private fun syncLogicModeUI(mode: LogicMode) {
        viewModel.selectedLogicMode = mode
        binding.sbSettingsLogic.progress = when (mode) {
            LogicMode.UNION -> 0
            LogicMode.DUP -> 1
            LogicMode.UNIQUE -> 2
        }
        updateSettingsLogicLabel(mode)
        viewModel.combineSelectedSources()
        updatePrepScreenStatus()
    }

    private fun updateSettingsLogicLabel(mode: LogicMode) {
        binding.tvSettingsLogicLabel.text = when (mode) {
            LogicMode.UNION -> "Логика сессии: Объединить"
            LogicMode.DUP -> "Логика сессии: Дубликаты"
            LogicMode.UNIQUE -> "Логика сессии: Уникальные"
        }
    }

    private fun setupSettingsControls() {
        // Initialize controls from loaded settings
        binding.switchSmartStart.isChecked = viewModel.settings.isSmartJumpEnabled
        binding.sbSmartStartSec.progress = viewModel.settings.smartJumpSeconds
        binding.tvSmartStartSecLabel.text = "Смещение: ${viewModel.settings.smartJumpSeconds} сек"

        binding.sbSmartStartPct.progress = viewModel.settings.smartJumpPct
        binding.tvSmartStartPctLabel.text = "Смещение по процентам: ${viewModel.settings.smartJumpPct}%"

        binding.sbVibrationStrength.progress = viewModel.settings.vibrationStrength
        binding.tvVibeLabel.text = "Сила вибрации: ${viewModel.settings.vibrationStrength} ms"

        // Card scale progress calculation: percentage / 5 = progress (e.g. 100% -> progress 20)
        binding.sbCardScale.progress = (viewModel.settings.cardScale * 20).toInt()
        binding.tvScaleLabel.text = "Масштаб карточки: ${(viewModel.settings.cardScale * 100).toInt()}%"
        binding.cardTrack.scaleX = viewModel.settings.cardScale
        binding.cardTrack.scaleY = viewModel.settings.cardScale

        binding.switchAutoplay.isChecked = viewModel.settings.isAutoplayEnabled
        binding.sbAutosaveInterval.progress = viewModel.settings.autosaveInterval
        if (viewModel.settings.autosaveInterval == 0) {
            binding.tvAutosaveLabel.text = "Автосохранение сессии: при каждом действии"
        } else {
            binding.tvAutosaveLabel.text = "Автосохранение сессии: каждые ${viewModel.settings.autosaveInterval} треков"
        }

        // Smart start controls listeners
        binding.switchSmartStart.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.isSmartJumpEnabled = isChecked
            saveSettingsToPrefs()
        }

        binding.sbSmartStartSec.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.settings.smartJumpSeconds = progress
                binding.tvSmartStartSecLabel.text = "Смещение: $progress сек"
                saveSettingsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbSmartStartPct.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.settings.smartJumpPct = progress
                binding.tvSmartStartPctLabel.text = "Смещение по процентам: $progress%"
                saveSettingsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Vibration strength controls listeners
        binding.sbVibrationStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.settings.vibrationStrength = progress
                binding.tvVibeLabel.text = "Сила вибрации: $progress ms"
                saveSettingsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Card scale controls listeners
        binding.sbCardScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percentage = progress * 5
                viewModel.settings.cardScale = percentage / 100f
                binding.tvScaleLabel.text = "Масштаб карточки: $percentage%"
                
                binding.cardTrack.scaleX = viewModel.settings.cardScale
                binding.cardTrack.scaleY = viewModel.settings.cardScale
                saveSettingsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Autoplay switch listener
        binding.switchAutoplay.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.isAutoplayEnabled = isChecked
            saveSettingsToPrefs()
        }

        // Autosave Interval listener
        binding.sbAutosaveInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.settings.autosaveInterval = progress
                if (progress == 0) {
                    binding.tvAutosaveLabel.text = "Автосохранение сессии: при каждом действии"
                } else {
                    binding.tvAutosaveLabel.text = "Автосохранение сессии: каждые $progress треков"
                }
                saveSettingsToPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Theme selector listener
        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeAmoled -> "amoled"
                else -> "dark"
            }
            applyTheme(theme)
            saveSettingsToPrefs()
        }

        // Load default theme and check default checked item
        applyTheme(viewModel.settings.currentTheme)
        when (viewModel.settings.currentTheme) {
            "light" -> binding.rbThemeLight.isChecked = true
            "amoled" -> binding.rbThemeAmoled.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        // Initialize detailed settings controls
        binding.switchDetailedSettings.isChecked = viewModel.settings.isDetailedSettingsEnabled
        binding.layoutDetailedSettingsSection.visibility = if (viewModel.settings.isDetailedSettingsEnabled) View.VISIBLE else View.GONE
        binding.switchAdvancedSources.isChecked = viewModel.settings.isAdvancedSourcesModeEnabled
        binding.switchParseAllTextPlaylists.isChecked = viewModel.settings.parseAllTextFilesAsPlaylists
        binding.switchMetadataCache.isChecked = viewModel.settings.isMetadataCacheEnabled

        binding.switchDetailedSettings.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.isDetailedSettingsEnabled = isChecked
            binding.layoutDetailedSettingsSection.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                viewModel.settings.vibrationStrength = 80
                viewModel.settings.autosaveInterval = 5
                viewModel.settings.isAdvancedSourcesModeEnabled = false
                viewModel.settings.parseAllTextFilesAsPlaylists = false
                viewModel.settings.isMetadataCacheEnabled = true

                binding.sbVibrationStrength.progress = 80
                binding.tvVibeLabel.text = "Сила вибрации: 80 ms"
                binding.sbAutosaveInterval.progress = 5
                binding.tvAutosaveLabel.text = "Автосохранение сессии: каждые 5 треков"
                binding.switchAdvancedSources.isChecked = false
                binding.switchParseAllTextPlaylists.isChecked = false
                binding.switchMetadataCache.isChecked = true
            }
            saveSettingsToPrefs()
        }

        binding.switchAdvancedSources.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.isAdvancedSourcesModeEnabled = isChecked
            saveSettingsToPrefs()
        }

        binding.switchParseAllTextPlaylists.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.parseAllTextFilesAsPlaylists = isChecked
            saveSettingsToPrefs()
        }

        binding.switchMetadataCache.setOnCheckedChangeListener { _, isChecked ->
            viewModel.settings.isMetadataCacheEnabled = isChecked
            saveSettingsToPrefs()
        }

        binding.btnClearMetadataCache.setOnClickListener {
            LocalDirectoryScanner.getCache(this).clear()
            Toast.makeText(this, "Кэш метаданных очищен", Toast.LENGTH_SHORT).show()
        }

        binding.btnRestartTutorial.setOnClickListener {
            binding.screenSettings.visibility = View.GONE
            binding.screenPrep.visibility = View.VISIBLE
            showWelcomeTutorialDialog()
        }

        val hintPopupWindow = SettingsHintPopupWindow(this)

        binding.btnHintSmartStart.setOnClickListener { view ->
            hintPopupWindow.show(view, "Умный старт: Автоматически пропускает интро и перемотает трек на указанные секунды или проценты при открытии.")
        }

        binding.btnHintAutoplay.setOnClickListener { view ->
            hintPopupWindow.show(view, "Автостарт: Автоматически запускает воспроизведение следующего трека сразу после свайпа.")
        }

        binding.btnHintAdvancedSources.setOnClickListener { view ->
            hintPopupWindow.show(view, "Расширенный режим источников: Добавляет кнопки «+» и «−» возле каждого источника в списке, позволяя вручную включать или исключать его треки из сортировки.")
        }

        binding.btnHintParseTextPlaylists.setOnClickListener { view ->
            hintPopupWindow.show(view, "Любой текст как плейлист: Разрешает импортировать плейлисты из любых файлов (например .txt), содержащих пути к трекам.")
        }

        binding.btnHintAutosave.setOnClickListener { view ->
            hintPopupWindow.show(view, "Автосохранение сессии: Сохраняет промежуточный прогресс сортировки в памяти каждые N треков.")
        }

        binding.btnHintMetadataCache.setOnClickListener { view ->
            hintPopupWindow.show(view, "Кэшировать метаданные: Сохраняет теги и обложки треков для мгновенной повторной загрузки.")
        }

        binding.btnHintBlacklist.setOnClickListener { view ->
            hintPopupWindow.show(view, "Черный список папок: Автоматически скрывает треки из указанных папок (например, системные звуки или диктофонные записи) из очереди сортировки.")
        }

        binding.btnHintSessionLogic.setOnClickListener { view ->
            hintPopupWindow.show(view, "Логика сессии: Определяет правило объединения треков при выборе 2 и более источников (0 — объединить все, 1 — только дубликаты, 2 — только уникальные).")
        }

        binding.btnAboutApp.setOnClickListener {
            showLinkConfirmationDialog("https://swipebeat.technocat.cc?source=swipebeat&type=app")
        }

        binding.btnAboutAuthor.setOnClickListener {
            showLinkConfirmationDialog("https://technocat.cc?source=swipebeat&type=app")
        }

        binding.btnRestoreDefaults.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Сбросить настройки?")
                .setMessage("Вы уверены, что хотите восстановить настройки по умолчанию?")
                .setPositiveButton("Да") { _, _ ->
                    viewModel.settings.isSmartJumpEnabled = false
                    viewModel.settings.smartJumpSeconds = 30
                    viewModel.settings.vibrationStrength = 80
                    viewModel.settings.cardScale = 1.0f
                    viewModel.settings.isAutoplayEnabled = true
                    viewModel.settings.autosaveInterval = 5
                    viewModel.settings.currentTheme = "dark"
                    viewModel.settings.volume = 1.0f
                    viewModel.settings.speed = 1.0f
                    viewModel.settings.playlistName = "Sorted_Slidebox"
                    viewModel.settings.isDetailedSettingsEnabled = false
                    viewModel.settings.isAdvancedSourcesModeEnabled = false
                    viewModel.settings.isMetadataCacheEnabled = true
                    viewModel.settings.parseAllTextFilesAsPlaylists = false
                    
                    saveSettingsToPrefs()
                    applyTheme("dark")
                    
                    // Update UI controls on Settings screen
                    binding.switchSmartStart.isChecked = false
                    binding.sbSmartStartSec.progress = 30
                    binding.sbVibrationStrength.progress = 80
                    binding.sbCardScale.progress = 20
                    binding.switchAutoplay.isChecked = true
                    binding.sbAutosaveInterval.progress = 5
                    binding.rbThemeDark.isChecked = true
                    binding.switchDetailedSettings.isChecked = false
                    binding.layoutDetailedSettingsSection.visibility = View.GONE
                    binding.switchAdvancedSources.isChecked = false
                    binding.switchParseAllTextPlaylists.isChecked = false
                    binding.switchMetadataCache.isChecked = true
                    
                    // Also update UI controls on sorting screen HUD
                    binding.sbVolume.progress = 100
                    binding.sbSpeed.progress = 5
                    binding.tvSpeedLabel.text = "⚡ 1.0x"
                    playerEngine.setVolume(1.0f)
                    playerEngine.setPlaybackSpeed(1.0f)
                    
                    // And update playlist name label
                    updateOutputPlaylistLabel()
                    
                    Toast.makeText(this, "Настройки сброшены!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.screenSort.visibility == View.VISIBLE) {
                    exitSortingDirectly()
                } else if (binding.screenSettings.visibility == View.VISIBLE) {
                    binding.screenSettings.visibility = View.GONE
                    binding.screenPrep.visibility = View.VISIBLE
                    updatePrepScreenStatus()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun showSourcesSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = LayoutSourcesSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.btnSheetDeviceLibrary.setOnClickListener {
            if (checkPermissions()) {
                dialog.dismiss()
                viewModel.queueDeviceMediaScan(this)
            } else {
                requestPermissions()
            }
        }

        sheetBinding.btnSheetAddFolder.setOnClickListener {
            dialog.dismiss()
            selectFolderLauncher.launch(null)
        }

        sheetBinding.btnSheetImportM3u8.setOnClickListener {
            if (checkPermissions()) {
                dialog.dismiss()
                val mimeTypes = if (viewModel.settings.parseAllTextFilesAsPlaylists) {
                    arrayOf(
                        "audio/x-mpegurl",
                        "audio/mpegurl",
                        "application/x-mpegurl",
                        "application/vnd.apple.mpegurl",
                        "text/*"
                    )
                } else {
                    arrayOf(
                        "audio/x-mpegurl",
                        "audio/mpegurl",
                        "application/x-mpegurl",
                        "application/vnd.apple.mpegurl"
                    )
                }
                selectM3u8Launcher.launch(mimeTypes)
            } else {
                requestPermissions()
            }
        }

        populateCreatedPlaylists(sheetBinding.createdPlaylistsContainer, dialog)
        populateActiveSources(sheetBinding.activeSourcesContainer, sheetBinding.tvActiveSourcesHeader)
        populateRecentFolders(sheetBinding.recentFoldersContainer, dialog)

        dialog.show()
    }

    private fun populateActiveSources(container: LinearLayout, header: TextView) {
        container.removeAllViews()
        val sources = viewModel.selectedSources
        if (sources.isEmpty()) {
            container.visibility = View.GONE
            header.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        header.visibility = View.VISIBLE

        for (source in sources) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
                setBackgroundResource(R.drawable.bg_history_item)
                setPadding(24, 24, 24, 24)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${source.displayName} (${source.tracks.size} тр.)"
                setTextColor(android.graphics.Color.parseColor("#E4E4E7"))
                textSize = 13f
            }

            if (viewModel.settings.isAdvancedSourcesModeEnabled) {
                val tvToggleSign = TextView(this).apply {
                    val isNeg = source.isExcluded
                    text = if (isNeg) "−" else "+"
                    setTextColor(android.graphics.Color.parseColor(if (isNeg) "#EF4444" else "#10B981"))
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(24, 8, 24, 8)
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_tab_selected)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setOnClickListener {
                        source.isExcluded = !source.isExcluded
                        viewModel.combineSelectedSources()
                        updatePrepScreenStatus()
                        updateResumeButtonState()
                        populateActiveSources(container, header)
                    }
                }
                row.addView(tvName)
                row.addView(tvToggleSign)
            } else {
                row.addView(tvName)
            }

            val tvDelete = TextView(this).apply {
                text = "✕"
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                textSize = 14f
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    viewModel.selectedSources.remove(source)
                    viewModel.combineSelectedSources()
                    updatePrepScreenStatus()
                    updateResumeButtonState()
                    populateActiveSources(container, header)
                }
            }

            row.addView(tvDelete)
            container.addView(row)
        }
    }

    private fun startSortingSession(tracks: List<Track>) {
        binding.screenPrep.visibility = View.GONE
        binding.screenSort.visibility = View.VISIBLE
        binding.layoutTopHUD.visibility = View.GONE
        binding.tvTopTriggerHint.visibility = View.VISIBLE
        binding.tvTopTriggerHint.alpha = 1.0f
        viewModel.initSession(tracks)
    }

    private fun exitSortingDirectly() {
        playerEngine.stop()
        viewModel.setPlaying(false)
        saveSessionToPrefs()
        binding.screenPrep.visibility = View.VISIBLE
        binding.screenSort.visibility = View.GONE
        updatePrepScreenStatus()
        updateResumeButtonState()
    }

    private fun updatePrepScreenStatus() {
        val sourcesCount = viewModel.selectedSources.size
        if (sourcesCount > 0) {
            val totalRawTracks = viewModel.selectedSources.sumOf { it.tracks.size }
            val combinedTracks = viewModel.trackQueue.size
            
            val sourcesStr = when {
                sourcesCount == 1 -> "Источник: ${viewModel.selectedSources[0].displayName} (${totalRawTracks} треков)"
                else -> "Выбрано источников: $sourcesCount (${totalRawTracks} треков исходно)"
            }
            binding.tvSourceStatus.text = sourcesStr
            
            val logicStr = when (viewModel.selectedLogicMode) {
                LogicMode.UNION -> "Объединение без дубликатов"
                LogicMode.DUP -> "Только дубликаты"
                LogicMode.UNIQUE -> "Только уникальные"
            }
            
            if (sourcesCount > 1) {
                binding.tvLogs.text = "Логика: $logicStr. Готово к сортировке. Треков в очереди: $combinedTracks."
            } else {
                binding.tvLogs.text = "Готово к сортировке. Треков в очереди: $combinedTracks."
            }
        } else {
            binding.tvSourceStatus.text = "Не выбрано (Нажмите для выбора)"
            binding.tvLogs.text = "Выберите источник и нажмите «Начать новую сортировку»"
        }
    }

    private fun showHUD() {
        hudHandler.removeCallbacks(hideHUDRunnable)
        if (binding.layoutTopHUD.visibility != View.VISIBLE) {
            binding.layoutTopHUD.visibility = View.VISIBLE
            binding.layoutTopHUD.alpha = 0f
            binding.layoutTopHUD.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
                
            binding.tvTopTriggerHint.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    binding.tvTopTriggerHint.visibility = View.GONE
                }
                .start()
        }
        hudHandler.postDelayed(hideHUDRunnable, 4000)
    }

    private fun onTrackSwipedUp() {
        binding.viewApproveOverlay.visibility = View.VISIBLE
        vibrate("single")
        binding.cardTrack.animate()
            .translationY(-400f)
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                viewModel.onSwipeUp()
                checkAutosave()
            }
            .start()
    }

    private fun onTrackSwipedDown() {
        binding.viewRejectOverlay.visibility = View.VISIBLE
        vibrate("double")
        binding.cardTrack.animate()
            .translationY(400f)
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                viewModel.onSwipeDown()
                checkAutosave()
            }
            .start()
    }

    private fun onTrackSwipedSide() {
        if (viewModel.swipeHistory.isEmpty()) {
            Toast.makeText(this, "Нет действий для отмены", Toast.LENGTH_SHORT).show()
            return
        }
        vibrate("single")
        binding.cardTrack.animate()
            .translationX(600f)
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                viewModel.undo()
                checkAutosave()
                binding.cardTrack.translationX = -600f
                binding.cardTrack.alpha = 0f
                binding.cardTrack.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .start()
            }
            .start()
    }

    private fun resetCardAnimation() {
        binding.cardTrack.animate().cancel()
        binding.cardTrack.translationY = 0f
        binding.cardTrack.alpha = 1f
        binding.viewApproveOverlay.visibility = View.GONE
        binding.viewRejectOverlay.visibility = View.GONE
    }

    private fun updateCounterText() {
        val current = (viewModel.currentIndex.value ?: 0) + 1
        val total = viewModel.totalTracks.value ?: 0
        if (total > 0 && current <= total) {
            binding.tvCounter.text = "$current / $total"
            binding.hudSeekBar.max = total
            binding.hudSeekBar.progress = current
        } else {
            binding.tvCounter.text = "0 / 0"
            binding.hudSeekBar.progress = 0
        }
    }

    private var loadingDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showLoadingDialog(message: String) {
        runOnUiThread {
            if (loadingDialog == null) {
                val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                val dialogView = layoutInflater.inflate(R.layout.layout_loading_dialog, null)
                val tvMessage = dialogView.findViewById<TextView>(R.id.tvLoadingMessage)
                tvMessage.text = message
                builder.setView(dialogView)
                builder.setCancelable(false)
                loadingDialog = builder.create()
                loadingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            } else {
                val tvMessage = loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)
                tvMessage?.text = message
            }
            loadingDialog?.show()
        }
    }

    private fun hideLoadingDialog() {
        runOnUiThread {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }

    private fun importPlaylistAsync(uri: Uri) {
        showLoadingDialog("Импорт плейлиста...")
        Thread {
            try {
                val parser = PlaylistParser(this)
                val tracks = parser.parseM3U8(uri, viewModel.settings.parseAllTextFilesAsPlaylists)
                runOnUiThread {
                    hideLoadingDialog()
                    if (tracks.isNotEmpty()) {
                        val displayName = uri.lastPathSegment ?: uri.toString()
                        viewModel.selectedSources.removeAll { it.uri == uri }
                        viewModel.selectedSources.add(SelectedSource(uri, displayName, tracks))
                        
                        viewModel.combineSelectedSources()
                        updatePrepScreenStatus()
                        updateResumeButtonState()
                        Toast.makeText(this, "Плейлист добавлен: $displayName (${tracks.size} треков)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Файл плейлиста пуст или не содержит корректных путей!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    hideLoadingDialog()
                    Toast.makeText(this, "Ошибка при импорте плейлиста: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun scanCustomFolder(uri: Uri) {
        saveFolderToRecent(uri)
        viewModel.queueScan(this, uri)
    }

    private fun saveFolderToRecent(uri: Uri) {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val recentJson = prefs.getString("RecentFolders", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(recentJson)
            val uriStr = uri.toString()
            var exists = false
            for (i in 0 until array.length()) {
                if (array.getString(i) == uriStr) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                array.put(uriStr)
                prefs.edit().putString("RecentFolders", array.toString()).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun populateRecentFolders(container: LinearLayout, dialog: BottomSheetDialog) {
        container.removeAllViews()
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val recentJson = prefs.getString("RecentFolders", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(recentJson)
            if (array.length() == 0) {
                val tvEmpty = TextView(this).apply {
                    text = "Нет недавних папок"
                    setTextColor(android.graphics.Color.parseColor("#71717A"))
                    textSize = 13f
                    setPadding(0, 16, 0, 16)
                }
                container.addView(tvEmpty)
                return
            }

            for (i in 0 until array.length()) {
                val uriStr = array.getString(i)
                val uri = Uri.parse(uriStr)
                val displayName = uri.lastPathSegment ?: uriStr

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8)
                    }
                    setBackgroundResource(R.drawable.bg_history_item)
                    setPadding(24, 24, 24, 24)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = displayName
                    setTextColor(android.graphics.Color.parseColor("#E4E4E7"))
                    textSize = 13f
                }

                row.setOnClickListener {
                    dialog.dismiss()
                    scanCustomFolder(uri)
                }

                val tvDelete = TextView(this).apply {
                    text = "✕"
                    setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    setPadding(16, 8, 16, 8)
                }

                tvDelete.setOnClickListener {
                    removeRecentFolder(uriStr)
                    populateRecentFolders(container, dialog)
                }

                row.addView(tvName)
                row.addView(tvDelete)
                container.addView(row)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCreatedPlaylist(name: String, uri: Uri) {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val createdJson = prefs.getString("CreatedPlaylists", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(createdJson)
            val uriStr = uri.toString()
            var exists = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("uri") == uriStr) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                val newObj = org.json.JSONObject().apply {
                    put("name", name)
                    put("uri", uriStr)
                }
                array.put(newObj)
                prefs.edit().putString("CreatedPlaylists", array.toString()).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun populateCreatedPlaylists(container: LinearLayout, dialog: BottomSheetDialog) {
        container.removeAllViews()
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val createdJson = prefs.getString("CreatedPlaylists", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(createdJson)
            if (array.length() == 0) {
                val tvEmpty = TextView(this).apply {
                    text = "Нет созданных плейлистов"
                    setTextColor(android.graphics.Color.parseColor("#71717A"))
                    textSize = 13f
                    setPadding(0, 16, 0, 16)
                }
                container.addView(tvEmpty)
                return
            }

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val uriStr = obj.getString("uri")
                val uri = Uri.parse(uriStr)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8)
                    }
                    setBackgroundResource(R.drawable.bg_history_item)
                    setPadding(24, 24, 24, 24)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val tvName = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = name
                    setTextColor(android.graphics.Color.parseColor("#E4E4E7"))
                    textSize = 13f
                }

                row.setOnClickListener {
                    dialog.dismiss()
                    importPlaylistAsync(uri)
                }

                val tvDelete = TextView(this).apply {
                    text = "✕"
                    setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    setPadding(16, 8, 16, 8)
                }

                tvDelete.setOnClickListener {
                    try {
                        val newArray = org.json.JSONArray()
                        for (j in 0 until array.length()) {
                            if (j != i) {
                                newArray.put(array.get(j))
                            }
                        }
                        prefs.edit().putString("CreatedPlaylists", newArray.toString()).apply()
                        populateCreatedPlaylists(container, dialog)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                row.addView(tvName)
                row.addView(tvDelete)
                container.addView(row)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeRecentFolder(uriStr: String) {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val recentJson = prefs.getString("RecentFolders", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(recentJson)
            val newArray = org.json.JSONArray()
            for (i in 0 until array.length()) {
                if (array.getString(i) != uriStr) {
                    newArray.put(array.getString(i))
                }
            }
            prefs.edit().putString("RecentFolders", newArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAutosave() {
        val totalProcessed = viewModel.approvedTracks.size + viewModel.rejectedTracks.size
        val interval = viewModel.settings.autosaveInterval
        if (interval > 0 && totalProcessed % interval == 0) {
            saveSessionToPrefs()
        }
    }

    private fun saveSessionToPrefs() {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        
        val queueArray = org.json.JSONArray()
        for (t in viewModel.trackQueue) {
            queueArray.put(t.toJson())
        }
        val approvedArray = org.json.JSONArray()
        for (t in viewModel.approvedTracks) {
            approvedArray.put(t.toJson())
        }
        val rejectedArray = org.json.JSONArray()
        for (t in viewModel.rejectedTracks) {
            rejectedArray.put(t.toJson())
        }

        val historyArray = org.json.JSONArray()
        for (a in viewModel.swipeHistory) {
            val actJson = org.json.JSONObject().apply {
                put("track", a.track.toJson())
                put("isApproved", a.isApproved)
                put("queueIndex", a.queueIndex)
            }
            historyArray.put(actJson)
        }
        
        editor.putString("SessionQueue", queueArray.toString())
        editor.putString("SessionApproved", approvedArray.toString())
        editor.putString("SessionRejected", rejectedArray.toString())
        editor.putString("SessionHistory", historyArray.toString())
        editor.putInt("SessionIndex", viewModel.currentIndex.value ?: 0)
        editor.putString("SessionSourceStatus", binding.tvSourceStatus.text.toString())
        editor.apply()
    }

    private fun loadSessionFromPrefs(): Boolean {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val queueStr = prefs.getString("SessionQueue", null) ?: return false
        val approvedStr = prefs.getString("SessionApproved", "[]") ?: "[]"
        val rejectedStr = prefs.getString("SessionRejected", "[]") ?: "[]"
        val historyStr = prefs.getString("SessionHistory", "[]") ?: "[]"
        val index = prefs.getInt("SessionIndex", 0)
        val statusText = prefs.getString("SessionSourceStatus", "Не выбрано") ?: "Не выбрано"

        try {
            val queueJson = org.json.JSONArray(queueStr)
            val approvedJson = org.json.JSONArray(approvedStr)
            val rejectedJson = org.json.JSONArray(rejectedStr)
            val historyJson = org.json.JSONArray(historyStr)

            val queue = mutableListOf<Track>()
            for (i in 0 until queueJson.length()) {
                queue.add(Track.fromJson(queueJson.getJSONObject(i)))
            }
            val approved = mutableListOf<Track>()
            for (i in 0 until approvedJson.length()) {
                approved.add(Track.fromJson(approvedJson.getJSONObject(i)))
            }
            val rejected = mutableListOf<Track>()
            for (i in 0 until rejectedJson.length()) {
                rejected.add(Track.fromJson(rejectedJson.getJSONObject(i)))
            }

            val history = mutableListOf<SwipeAction>()
            for (i in 0 until historyJson.length()) {
                val obj = historyJson.getJSONObject(i)
                val track = Track.fromJson(obj.getJSONObject("track"))
                val isApproved = obj.getBoolean("isApproved")
                val queueIndex = obj.getInt("queueIndex")
                history.add(SwipeAction(track, isApproved, queueIndex))
            }

            if (queue.isEmpty()) return false

            viewModel.resumeSession(queue, approved, rejected, index)
            viewModel.swipeHistory.clear()
            viewModel.swipeHistory.addAll(history)
            binding.tvSourceStatus.text = statusText

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun checkSavedSessionExists(): Boolean {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val queueStr = prefs.getString("SessionQueue", null)
        if (queueStr.isNullOrEmpty() || queueStr.trim() == "[]") return false
        return true
    }

    private fun updateResumeButtonState() {
        if (checkSavedSessionExists()) {
            binding.layoutResumeArea.visibility = View.VISIBLE
            binding.btnResumeSort.isEnabled = true
            binding.btnResumeSort.alpha = 1.0f
        } else {
            binding.layoutResumeArea.visibility = View.GONE
            binding.btnResumeSort.isEnabled = false
        }
    }

    private fun updateOutputPlaylistLabel() {
        val ext = if (viewModel.approvedTracks.isNotEmpty()) {
            getDefaultPlaylistExtension(viewModel.approvedTracks)
        } else {
            "m3u8"
        }
        binding.tvOutputPlaylistName.text = "${viewModel.settings.playlistName}.$ext"
    }

    private fun showRenamePlaylistDialog() {
        val input = android.widget.EditText(this).apply {
            setText(viewModel.settings.playlistName)
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 48
                rightMargin = 48
                topMargin = 16
                bottomMargin = 16
            }
            addView(input, params)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Название плейлиста")
            .setMessage("Введите имя файла для сохранения (без расширения):")
            .setView(container)
            .setPositiveButton("ОК") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewModel.settings.playlistName = newName
                    saveSettingsToPrefs()
                    updateOutputPlaylistLabel()
                } else {
                    Toast.makeText(this, "Имя не может быть пустым!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askSavePlaylist() {
        if (viewModel.approvedTracks.isEmpty()) {
            Toast.makeText(this, "Нет одобренных треков для сохранения плейлиста!", Toast.LENGTH_LONG).show()
            return
        }
        val ext = getDefaultPlaylistExtension(viewModel.approvedTracks)
        val defaultName = "${viewModel.settings.playlistName}.$ext"
        createPlaylistLauncher.launch(defaultName)
    }

    private fun getDefaultPlaylistExtension(tracks: List<Track>): String {
        for (track in tracks) {
            val combo = "${track.title} ${track.artist} ${track.filePath}"
            if (combo.any { it.code > 127 }) {
                return "m3u8"
            }
        }
        return "m3u"
    }

    private fun writePlaylistToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"))
                writer.write("#EXTM3U\n")
                for (track in viewModel.approvedTracks) {
                    writer.write("#EXTINF:-1,${track.artist} - ${track.title}\n")
                    writer.write("${track.filePath}\n")
                }
                writer.flush()
            }
            
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val filename = uri.path?.substringAfterLast('/') ?: uri.toString()
            Toast.makeText(this, "Плейлист успешно сохранен!", Toast.LENGTH_SHORT).show()
            savePlaylistToHistory(filename, viewModel.approvedTracks.size)
            saveCreatedPlaylist(filename, uri)
            binding.tvLogs.text = "Сохранен плейлист: $filename\n(Количество треков: ${viewModel.approvedTracks.size})"
            clearSavedSession()
            updateResumeButtonState()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка сохранения: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun savePlaylistToHistory(name: String, trackCount: Int) {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        val historyJson = prefs.getString("PlaylistHistory", "[]") ?: "[]"
        try {
            val array = org.json.JSONArray(historyJson)
            val obj = org.json.JSONObject().apply {
                put("name", name)
                put("date", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
                put("count", trackCount)
            }
            array.put(obj)
            prefs.edit().putString("PlaylistHistory", array.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAlbumArt(track: Track) {
        try {
            if (track.uri.scheme == "content" || track.uri.scheme == "file") {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(this, track.uri)
                val artBytes = retriever.embeddedPicture
                retriever.release()
                if (artBytes != null) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    binding.ivAlbumArt.setImageBitmap(bitmap)
                    binding.ivAlbumArt.visibility = View.VISIBLE
                } else {
                    binding.ivAlbumArt.visibility = View.GONE
                }
            } else {
                binding.ivAlbumArt.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.ivAlbumArt.visibility = View.GONE
        }
    }

    private fun applyTheme(themeName: String) {
        viewModel.settings.currentTheme = themeName
        val bgColor: Int
        val cardColor: Int
        val textColor: Int
        val subTextColor: Int

        when (themeName) {
            "light" -> {
                bgColor = android.graphics.Color.parseColor("#FFFFFF")
                cardColor = android.graphics.Color.parseColor("#F4F4F5")
                textColor = android.graphics.Color.parseColor("#09090B")
                subTextColor = android.graphics.Color.parseColor("#71717A")
            }
            "amoled" -> {
                bgColor = android.graphics.Color.parseColor("#000000")
                cardColor = android.graphics.Color.parseColor("#09090B")
                textColor = android.graphics.Color.parseColor("#FFFFFF")
                subTextColor = android.graphics.Color.parseColor("#A1A1AA")
            }
            else -> {
                bgColor = android.graphics.Color.parseColor("#09090B")
                cardColor = android.graphics.Color.parseColor("#18181B")
                textColor = android.graphics.Color.parseColor("#F4F4F5")
                subTextColor = android.graphics.Color.parseColor("#71717A")
            }
        }

        binding.screenPrep.setBackgroundColor(bgColor)
        binding.screenSort.setBackgroundColor(bgColor)
        binding.screenSettings.setBackgroundColor(bgColor)

        binding.cardSelectSource.setCardBackgroundColor(cardColor)
        binding.cardOutputPlaylist.setCardBackgroundColor(cardColor)

        binding.cardTrack.setCardBackgroundColor(cardColor)

        binding.tvLogs.setTextColor(subTextColor)
        binding.tvSourceStatus.setTextColor(textColor)
         
        binding.tvTrackTitle.setTextColor(textColor)
        binding.tvTrackArtist.setTextColor(subTextColor)

        binding.rbThemeLight.setTextColor(if (themeName == "light") textColor else subTextColor)
        binding.rbThemeDark.setTextColor(if (themeName == "dark") textColor else subTextColor)
        binding.rbThemeAmoled.setTextColor(if (themeName == "amoled") textColor else subTextColor)
    }

    private fun updateBlacklistSettingsList() {
        binding.layoutBlacklistPaths.removeAllViews()
        val inflater = android.view.LayoutInflater.from(this)
        for (path in viewModel.settings.blacklistedFolders) {
            val row = android.widget.LinearLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val tvPath = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = path
                setTextColor(0xFFE4E4E7.toInt())
                textSize = 12f
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                isSingleLine = true
            }

            val btnDelete = android.widget.TextView(this).apply {
                text = "❌"
                textSize = 12f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    viewModel.settings.blacklistedFolders.remove(path)
                    saveSettingsToPrefs()
                    updateBlacklistSettingsList()
                    viewModel.combineSelectedSources()
                    updatePrepScreenStatus()
                }
            }

            row.addView(tvPath)
            row.addView(btnDelete)
            binding.layoutBlacklistPaths.addView(row)
        }
    }

    private fun checkPermissions(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun vibrate(pattern: String) {
        val strength = viewModel.settings.vibrationStrength.toLong()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (pattern == "single") {
                        vibrator.vibrate(VibrationEffect.createOneShot(strength, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else if (pattern == "double") {
                        val timings = longArrayOf(0, strength, 80, strength)
                        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (pattern == "single") {
                        vibrator.vibrate(strength)
                    } else if (pattern == "double") {
                        vibrator.vibrate(longArrayOf(0, strength, 80, strength), -1)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveSettingsToPrefs() {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("SettingSmartJump", viewModel.settings.isSmartJumpEnabled)
            putInt("SettingSmartJumpSec", viewModel.settings.smartJumpSeconds)
            putInt("SettingSmartJumpPct", viewModel.settings.smartJumpPct)
            putInt("SettingVibeStrength", viewModel.settings.vibrationStrength)
            putFloat("SettingCardScale", viewModel.settings.cardScale)
            putBoolean("SettingAutoplay", viewModel.settings.isAutoplayEnabled)
            putInt("SettingAutosaveInterval", viewModel.settings.autosaveInterval)
            putString("SettingTheme", viewModel.settings.currentTheme)
            putFloat("SettingVolume", viewModel.settings.volume)
            putFloat("SettingSpeed", viewModel.settings.speed)
            putString("SettingPlaylistName", viewModel.settings.playlistName)
            putBoolean("SettingDetailedSettings", viewModel.settings.isDetailedSettingsEnabled)
            putBoolean("SettingAdvancedSourcesMode", viewModel.settings.isAdvancedSourcesModeEnabled)
            putBoolean("SettingMetadataCache", viewModel.settings.isMetadataCacheEnabled)
            putBoolean("SettingParseAllTextPlaylists", viewModel.settings.parseAllTextFilesAsPlaylists)
            
            val blacklistArray = org.json.JSONArray()
            for (f in viewModel.settings.blacklistedFolders) {
                blacklistArray.put(f)
            }
            putString("SettingBlacklistedFolders", blacklistArray.toString())
            apply()
        }
    }

    private fun loadSettingsFromPrefs() {
        val prefs = getSharedPreferences("SlideboxPrefs", MODE_PRIVATE)
        viewModel.settings.isSmartJumpEnabled = prefs.getBoolean("SettingSmartJump", true)
        viewModel.settings.smartJumpSeconds = prefs.getInt("SettingSmartJumpSec", 30)
        viewModel.settings.smartJumpPct = prefs.getInt("SettingSmartJumpPct", 10)
        viewModel.settings.vibrationStrength = prefs.getInt("SettingVibeStrength", 80)
        viewModel.settings.cardScale = prefs.getFloat("SettingCardScale", 1.0f)
        viewModel.settings.isAutoplayEnabled = prefs.getBoolean("SettingAutoplay", true)
        viewModel.settings.autosaveInterval = prefs.getInt("SettingAutosaveInterval", 5)
        viewModel.settings.currentTheme = prefs.getString("SettingTheme", "dark") ?: "dark"
        viewModel.settings.volume = prefs.getFloat("SettingVolume", 1.0f)
        viewModel.settings.speed = prefs.getFloat("SettingSpeed", 1.0f)
        viewModel.settings.playlistName = prefs.getString("SettingPlaylistName", "Sorted_Slidebox") ?: "Sorted_Slidebox"
        viewModel.settings.isDetailedSettingsEnabled = prefs.getBoolean("SettingDetailedSettings", false)
        viewModel.settings.isAdvancedSourcesModeEnabled = prefs.getBoolean("SettingAdvancedSourcesMode", false)
        viewModel.settings.isMetadataCacheEnabled = prefs.getBoolean("SettingMetadataCache", true)
        viewModel.settings.parseAllTextFilesAsPlaylists = prefs.getBoolean("SettingParseAllTextPlaylists", false)
        
        val blacklistStr = prefs.getString("SettingBlacklistedFolders", null)
        if (blacklistStr != null) {
            try {
                val array = org.json.JSONArray(blacklistStr)
                viewModel.settings.blacklistedFolders.clear()
                for (i in 0 until array.length()) {
                    viewModel.settings.blacklistedFolders.add(array.getString(i))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        updateOutputPlaylistLabel()
    }

    override fun onResume() {
        super.onResume()
        progressHandler.post(progressRunnable)
    }

    override fun onPause() {
        super.onPause()
        progressHandler.removeCallbacks(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        playerEngine.stop()
        viewModel.setPlaying(false)
        saveSessionToPrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
    }

    private fun showWelcomeTutorialDialog() {
        val dialog = WelcomeTutorialDialogFragment()
        dialog.onInteractiveSelected = {
            tutorialManager.startInteractiveTutorial(binding)
        }
        dialog.onFastSelected = {
            val fastDialog = FastTutorialDialogFragment()
            fastDialog.onDismissListener = {
                tutorialManager.markTutorialCompleted()
            }
            fastDialog.show(supportFragmentManager, FastTutorialDialogFragment.TAG)
        }
        dialog.onSkipSelected = {
            tutorialManager.markTutorialCompleted()
        }
        dialog.show(supportFragmentManager, WelcomeTutorialDialogFragment.TAG)
    }

    fun showPrepScreen() {
        binding.screenPrep.visibility = View.VISIBLE
        binding.screenSort.visibility = View.GONE
        binding.screenSettings.visibility = View.GONE
    }

    fun showSettingsScreen() {
        binding.screenPrep.visibility = View.GONE
        binding.screenSort.visibility = View.GONE
        binding.screenSettings.visibility = View.VISIBLE
    }

    fun openSourcesSheetForTutorial(onAdded: () -> Unit) {
        showSourcesSheet()
        onAdded()
    }

    fun startDemoSortingSession() {
        binding.screenPrep.visibility = View.GONE
        binding.screenSettings.visibility = View.GONE
        binding.screenSort.visibility = View.VISIBLE
        binding.layoutTopHUD.visibility = View.GONE
        binding.tvTopTriggerHint.visibility = View.VISIBLE
        binding.tvTopTriggerHint.alpha = 1.0f
        viewModel.initSession(viewModel.trackQueue.toList())
    }

    private fun showLinkConfirmationDialog(url: String) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val dpToPx = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_button_secondary)
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
        }

        val tvTitle = TextView(this).apply {
            text = "Перейти по ссылке?"
            setTextColor(android.graphics.Color.parseColor("#F4F4F5"))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val tvUrl = TextView(this).apply {
            text = url
            setTextColor(android.graphics.Color.parseColor("#818CF8"))
            textSize = 13f
            setPadding(0, dpToPx(10), 0, dpToPx(20))
            setTextIsSelectable(true)
        }

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val btnCancel = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "Нет"
            setTextColor(android.graphics.Color.parseColor("#A1A1AA"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.bg_button_secondary)
            setOnClickListener { dialog.dismiss() }
        }

        val btnConfirm = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "Перейти"
            setTextColor(android.graphics.Color.parseColor("#F4F4F5"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.bg_button_secondary)
            setOnClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }

        val btnParams = LinearLayout.LayoutParams(dpToPx(100), dpToPx(44)).apply {
            setMargins(dpToPx(8), 0, 0, 0)
        }

        btnLayout.addView(btnCancel, LinearLayout.LayoutParams(dpToPx(80), dpToPx(44)))
        btnLayout.addView(btnConfirm, btnParams)

        root.addView(tvTitle)
        root.addView(tvUrl)
        root.addView(btnLayout)

        dialog.setContentView(root, android.view.ViewGroup.LayoutParams(dpToPx(320), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
}