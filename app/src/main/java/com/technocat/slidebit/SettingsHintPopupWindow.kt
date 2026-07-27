package com.technocat.slidebit

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

class SettingsHintPopupWindow(private val context: Context) {

    private var popupWindow: PopupWindow? = null

    fun show(anchorView: View, messageText: String) {
        dismiss()

        val dpToPx = { dp: Int -> (dp * context.resources.displayMetrics.density).toInt() }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#27272A"))
            setStroke(dpToPx(1), Color.parseColor("#3F3F46"))
            cornerRadius = dpToPx(10).toFloat()
        }

        val textView = TextView(context).apply {
            text = messageText
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 13f
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            background = bgDrawable
            maxWidth = dpToPx(260)
        }

        val window = PopupWindow(
            textView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = dpToPx(8).toFloat()
        }

        popupWindow = window
        window.showAsDropDown(anchorView, -dpToPx(120), dpToPx(4))
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}
