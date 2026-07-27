package com.technocat.slidebit

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import com.technocat.slidebit.databinding.ActivityMainBinding

class TutorialManager(
    private val context: Context,
    private val mainActivity: MainActivity,
    private val overlayView: TutorialOverlayView,
    private val viewModel: SortingViewModel
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("swipebeat_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
    }

    enum class Step {
        IDLE,
        MAIN_HEADER,
        MAIN_SOURCES,
        MAIN_SETTINGS,
        MAIN_OUTPUT,
        MAIN_START_BTN,
        SOURCES_SHEET,
        SETTINGS_OFFSET,
        SETTINGS_ADVANCED,
        SORT_CARD_GESTURES,
        SORT_HUD_TRIGGER,
        SORT_HISTORY_BTN,
        HISTORY_SHEET,
        FINISHED
    }

    private var currentStep = Step.IDLE
    private var isInteractiveActive = false

    fun isFirstLaunch(): Boolean {
        return !prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false)
    }

    fun markTutorialCompleted() {
        prefs.edit().putBoolean(KEY_TUTORIAL_COMPLETED, true).apply()
    }

    fun startInteractiveTutorial(binding: ActivityMainBinding) {
        isInteractiveActive = true
        overlayView.setOnNextClickListener { nextStep(binding) }
        overlayView.setOnSkipClickListener { skipTutorial() }
        currentStep = Step.MAIN_HEADER
        showStep(binding)
    }

    fun nextStep(binding: ActivityMainBinding) {
        if (!isInteractiveActive) return
        val steps = Step.values()
        val nextIdx = currentStep.ordinal + 1
        if (nextIdx < steps.size && steps[nextIdx] != Step.FINISHED) {
            currentStep = steps[nextIdx]
            showStep(binding)
        } else {
            finishTutorial(binding)
        }
    }

    fun skipTutorial() {
        isInteractiveActive = false
        overlayView.visibility = View.GONE
        markTutorialCompleted()
    }

    private fun showStep(binding: ActivityMainBinding) {
        when (currentStep) {
            Step.MAIN_HEADER -> {
                mainActivity.showPrepScreen()
                overlayView.highlightView(
                    binding.tvAppName,
                    "Добро пожаловать в SwipeBeat!",
                    "SwipeBeat — приложение для молниеносной сортировки вашей музыки с помощью простых свайпов.",
                    "Шаг 1 из 11"
                )
            }
            Step.MAIN_SOURCES -> {
                overlayView.highlightView(
                    binding.cardSelectSource,
                    "1. Выбор источников",
                    "Нажмите здесь, чтобы выбрать папки с аудиофайлами или импортировать плейлисты M3U/M3U8.",
                    "Шаг 2 из 11"
                )
            }
            Step.MAIN_SETTINGS -> {
                overlayView.highlightView(
                    binding.btnSettingsPrep,
                    "2. Быстрый переход в Настройки",
                    "Здесь настраивается умный запуск (оффсет), темы оформления и сила тактильной вибрации.",
                    "Шаг 3 из 11"
                )
            }
            Step.MAIN_OUTPUT -> {
                overlayView.highlightView(
                    binding.cardOutputPlaylist,
                    "3. Итоговый плейлист",
                    "Сюда сохранится ваш результат свайпов в формате плейлиста .m3u8, готового для сторонних плееров.",
                    "Шаг 4 из 11"
                )
            }
            Step.MAIN_START_BTN -> {
                overlayView.highlightView(
                    binding.btnStartSort,
                    "4. Кнопка старта",
                    "Крупная кнопка для запуска сессии сортировки после выбора источников.",
                    "Шаг 5 из 11"
                )
            }
            Step.SOURCES_SHEET -> {
                // Open sources bottom sheet & highlight
                mainActivity.openSourcesSheetForTutorial {
                    // Inject demo tracks if empty
                    injectDemoTracksIfEmpty()
                }
                overlayView.highlightView(
                    null,
                    "Менеджер источников",
                    "Здесь можно сканировать папки, добавлять плейлисты или быстрые директории. Мы уже подготовили пару демо-треков для интерактивного знакомства!",
                    "Шаг 6 из 11"
                )
            }
            Step.SETTINGS_OFFSET -> {
                mainActivity.showSettingsScreen()
                val targetView = binding.screenSettings.findViewById<View>(R.id.switchSmartStart) ?: binding.screenSettings
                overlayView.highlightView(
                    targetView,
                    "Умный старт (Оффсет)",
                    "С помощью ползунков секунд и процентов плеер автоматически проматывает интро и запускает треки с самого интересного момента!",
                    "Шаг 7 из 11"
                )
            }
            Step.SETTINGS_ADVANCED -> {
                val targetView = binding.screenSettings.findViewById<View>(R.id.switchAdvancedSources) ?: binding.screenSettings
                overlayView.highlightView(
                    targetView,
                    "Продвинутые настройки",
                    "Более глубокие параметры (парсинг любых текстовых файлов, кодировки и отладка) находятся в этой секции.",
                    "Шаг 8 из 11"
                )
            }
            Step.SORT_CARD_GESTURES -> {
                // Start sorting demo
                injectDemoTracksIfEmpty()
                mainActivity.startDemoSortingSession()
                overlayView.highlightView(
                    binding.cardTrack,
                    "Экран сортировки и жесты",
                    "• Свайп ВВЕРХ — Одобрить трек 👍\n• Свайп ВНИЗ — Пропустить 👎\n• Тап по карточке — Пауза/Плей\n• Долгий тап — Инфо о треке",
                    "Шаг 9 из 11"
                )
            }
            Step.SORT_HUD_TRIGGER -> {
                overlayView.highlightView(
                    binding.viewTopTrigger,
                    "Скрытый плеер и прогресс",
                    "Нажмите на верхний край экрана, чтобы показать полосу прогресса трека, регуляторы громкости и скорости.",
                    "Шаг 10 из 11"
                )
            }
            Step.SORT_HISTORY_BTN -> {
                overlayView.highlightView(
                    binding.btnOpenHistory,
                    "Шторка истории (Два списка)",
                    "Открывает список ваших решений. В ней: Свайп ВЛЕВО меняет решение, Свайп ВПРАВО возвращает трек в очередь!",
                    "Шаг 11 из 11",
                    isLastStep = true
                )
            }
            else -> {}
        }
    }

    private fun injectDemoTracksIfEmpty() {
        val tracks = listOf(
            Track(
                id = "demo_1",
                title = "Demo Track 1 (Electronic)",
                artist = "SwipeBeat Audio",
                filePath = "/demo/track1.mp3",
                uri = Uri.parse("asset:///demo_sounds/demo_track_1.wav"),
                metadataLoaded = true
            ),
            Track(
                id = "demo_2",
                title = "Demo Track 2 (Chill Acoustic)",
                artist = "SwipeBeat Audio",
                filePath = "/demo/track2.mp3",
                uri = Uri.parse("asset:///demo_sounds/demo_track_2.wav"),
                metadataLoaded = true
            )
        )
        viewModel.setTrackQueue(tracks)
    }

    private fun finishTutorial(binding: ActivityMainBinding) {
        isInteractiveActive = false
        overlayView.visibility = View.GONE
        markTutorialCompleted()
        mainActivity.showPrepScreen()
    }
}
