package com.technocat.swipebeat

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.DialogFragment

class WelcomeTutorialDialogFragment : DialogFragment() {

    var onInteractiveSelected: (() -> Unit)? = null
    var onFastSelected: (() -> Unit)? = null
    var onSkipSelected: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        isCancelable = false

        val view = LinearLayoutBuilder.buildWelcomeLayout(requireContext(),
            onInteractive = {
                dismiss()
                onInteractiveSelected?.invoke()
            },
            onFast = {
                dismiss()
                onFastSelected?.invoke()
            },
            onSkip = {
                dismiss()
                onSkipSelected?.invoke()
            }
        )
        return view
    }

    private object LinearLayoutBuilder {
        fun buildWelcomeLayout(
            context: android.content.Context,
            onInteractive: () -> Unit,
            onFast: () -> Unit,
            onSkip: () -> Unit
        ): View {
            val dpToPx = { dp: Int -> (dp * context.resources.displayMetrics.density).toInt() }

            val root = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#18181B"))
                background = context.getDrawable(R.drawable.bg_button_secondary)
                setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            }

            val title = TextView(context).apply {
                text = "Привет! 👋"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val subtitle = TextView(context).apply {
                text = "Приятно видеть тебя в SwipeBeat. Желаешь пройти короткое знакомство?"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 14f
                setPadding(0, dpToPx(8), 0, dpToPx(24))
            }

            val btnInteractive = AppCompatButton(context).apply {
                text = "🚀 Погнали (Интерактивно)"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = context.getDrawable(R.drawable.bg_button_secondary)
                setOnClickListener { onInteractive() }
            }

            val btnFast = AppCompatButton(context).apply {
                text = "⚡ Фастом (Краткая справка)"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = context.getDrawable(R.drawable.bg_button_secondary)
                setOnClickListener { onFast() }
            }

            val btnSkip = AppCompatButton(context).apply {
                text = "🤙 Я сам (Пропустить)"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = context.getDrawable(R.drawable.bg_button_secondary)
                setOnClickListener { onSkip() }
            }

            root.addView(title)
            root.addView(subtitle)
            root.addView(btnInteractive, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)
            ).apply { setMargins(0, 0, 0, dpToPx(10)) })
            root.addView(btnFast, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)
            ).apply { setMargins(0, 0, 0, dpToPx(10)) })
            root.addView(btnSkip, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)
            ))

            return root
        }
    }

    companion object {
        const val TAG = "WelcomeTutorialDialog"
    }
}
