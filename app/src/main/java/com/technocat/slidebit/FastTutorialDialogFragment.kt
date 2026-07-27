package com.technocat.slidebit

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.DialogFragment

class FastTutorialDialogFragment : DialogFragment() {

    var onDismissListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#09090B")))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val dpToPx = { dp: Int -> (dp * requireContext().resources.displayMetrics.density).toInt() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val scrollView = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = true
        }

        val htmlContent = """
            <h2 style="color:#F4F4F5;">⚡ Быстрый гайд по SwipeBeat</h2>
            <br>
            <p><b>1. Источники музыки:</b><br>
            Нажмите <b>«Выбрать источники»</b> на главном экране. Вы можете сканировать папки, вызывать системный выбор или загружать текстовые плейлисты.</p>
            <br>
            <p><b>2. Свайпы и сортировка:</b><br>
            • <b>⬆️ Свайп ВВЕРХ:</b> Одобрить трек. Трек попадает в итоговую подборку.<br>
            • <b>⬇️ Свайп ВНИЗ:</b> Пропустить трек.<br>
            • <b>👆 Тап по карточке:</b> Пауза / Плей.<br>
            • <b>⏱️ Верхняя панель:</b> Открывает прогресс проигрывания, регулировку громкости и скорости.</p>
            <br>
            <p><b>3. Умный старт (Оффсет):</b><br>
            В ⚙️ <b>Настройках</b> под секцией «Умный старт» вы можете задать пропуск интро (в секундах или процентах). Плеер сразу начнет играть сочной части трека!</p>
            <br>
            <p><b>4. Шторка истории:</b><br>
            Кнопка <b>«📜 История»</b> на экране сортировки показывает списки прослушанных треков.<br>
            • <b>Свайп ВЛЕВО:</b> Переносит трек между списками.<br>
            • <b>Свайп ВПРАВО:</b> Возвращает трек обратно в очередь.</p>
        """.trimIndent()

        val tvContent = TextView(requireContext()).apply {
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 15f
            text = Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY)
            setLineSpacing(8f, 1.1f)
        }

        scrollView.addView(tvContent)

        val btnClose = AppCompatButton(requireContext()).apply {
            text = "Понятно, к работе!"
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = requireContext().getDrawable(R.drawable.bg_button_secondary)
            setOnClickListener {
                dismiss()
                onDismissListener?.invoke()
            }
        }

        val scrollParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ).apply {
            setMargins(0, 0, 0, dpToPx(16))
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(50)
        )

        root.addView(scrollView, scrollParams)
        root.addView(btnClose, btnParams)

        return root
    }

    companion object {
        const val TAG = "FastTutorialDialog"
    }
}
