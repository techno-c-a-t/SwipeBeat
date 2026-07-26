package com.technocat.slidebit

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.DialogFragment

class FastTutorialDialogFragment : DialogFragment() {

    var onDismissListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val dpToPx = { dp: Int -> (dp * requireContext().resources.displayMetrics.density).toInt() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
            background = requireContext().getDrawable(R.drawable.bg_button_secondary)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        val webView = WebView(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            loadUrl("file:///android_asset/tutorial/fast_tutorial.html")
        }

        val btnClose = AppCompatButton(requireContext()).apply {
            text = "Понятно, к работе!"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = requireContext().getDrawable(R.drawable.bg_button_primary)
            setOnClickListener {
                dismiss()
                onDismissListener?.invoke()
            }
        }

        val webParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(400)
        ).apply {
            setMargins(0, 0, 0, dpToPx(16))
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(50)
        )

        root.addView(webView, webParams)
        root.addView(btnClose, btnParams)

        return root
    }

    companion object {
        const val TAG = "FastTutorialDialog"
    }
}
