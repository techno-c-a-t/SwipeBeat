package com.technocat.slidebit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton

class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E609090B") // 90% opacity dark background
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#6366F1") // Accent Indigo border
        strokeWidth = 6f
    }

    private val targetRect = RectF()
    private var isHighlighting = false

    private var tvStepCounter: TextView? = null
    private var tvTitle: TextView? = null
    private var tvDescription: TextView? = null
    private var btnNext: AppCompatButton? = null
    private var btnSkip: TextView? = null
    private var cardTooltip: LinearLayout? = null

    private var onNextClickListener: (() -> Unit)? = null
    private var onSkipClickListener: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // Build Tooltip Card programmatically
        val container = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            clipToOutline = true
            background = context.getDrawable(R.drawable.bg_button_secondary) // uses rounded bg shape
        }
        cardTooltip = container

        tvStepCounter = TextView(context).apply {
            setTextColor(Color.parseColor("#818CF8"))
            textSize = 12f
            setPadding(0, 0, 0, dpToPx(4))
        }

        tvTitle = TextView(context).apply {
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(6))
        }

        tvDescription = TextView(context).apply {
            setTextColor(Color.parseColor("#D4D4D8"))
            textSize = 14f
            setPadding(0, 0, 0, dpToPx(14))
        }

        val btnLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        btnSkip = TextView(context).apply {
            text = "Пропустить"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 13f
            setPadding(dpToPx(8), dpToPx(8), dpToPx(16), dpToPx(8))
            setOnClickListener { onSkipClickListener?.invoke() }
        }

        btnNext = AppCompatButton(context).apply {
            text = "Далее"
            setTextColor(Color.parseColor("#F4F4F5"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = context.getDrawable(R.drawable.bg_button_secondary)
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            setOnClickListener { onNextClickListener?.invoke() }
        }

        btnLayout.addView(btnSkip)
        btnLayout.addView(btnNext, LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(40)))

        container.addView(tvStepCounter)
        container.addView(tvTitle)
        container.addView(tvDescription)
        container.addView(btnLayout)

        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            gravity = Gravity.BOTTOM
        }
        addView(container, cardParams)

        // Any tap anywhere on overlay view advances to next tutorial step!
        setOnClickListener {
            onNextClickListener?.invoke()
        }
    }

    fun setOnNextClickListener(listener: () -> Unit) {
        onNextClickListener = listener
    }

    fun setOnSkipClickListener(listener: () -> Unit) {
        onSkipClickListener = listener
    }

    fun highlightView(target: View?, title: String, description: String, stepText: String, isLastStep: Boolean = false) {
        tvStepCounter?.text = stepText
        tvTitle?.text = title
        tvDescription?.text = description
        btnNext?.text = if (isLastStep) "Завершить 🎉" else "Далее"

        if (target != null && target.visibility == View.VISIBLE && target.width > 0) {
            val location = IntArray(2)
            target.getLocationOnScreen(location)

            val overlayLocation = IntArray(2)
            this.getLocationOnScreen(overlayLocation)

            val left = (location[0] - overlayLocation[0] - dpToPx(8)).toFloat()
            val top = (location[1] - overlayLocation[1] - dpToPx(8)).toFloat()
            val right = left + target.width + dpToPx(16)
            val bottom = top + target.height + dpToPx(16)

            targetRect.set(left, top, right, bottom)
            isHighlighting = true

            // Position tooltip card above or below target Rect
            adjustTooltipPosition(top, bottom)
        } else {
            isHighlighting = false
            targetRect.set(0f, 0f, 0f, 0f)
            // Position tooltip safely at top of viewport if no target view
            val cardParams = cardTooltip?.layoutParams as? LayoutParams
            cardParams?.gravity = Gravity.TOP
            cardParams?.setMargins(dpToPx(24), dpToPx(80), dpToPx(24), dpToPx(24))
            cardTooltip?.layoutParams = cardParams
        }

        visibility = View.VISIBLE
        invalidate()
    }

    private fun adjustTooltipPosition(targetTop: Float, targetBottom: Float) {
        val screenHeight = height.toFloat()
        val cardParams = cardTooltip?.layoutParams as? LayoutParams ?: return

        if (targetBottom > screenHeight / 2f) {
            // Target is in bottom half -> place tooltip at top safely
            cardParams.gravity = Gravity.TOP
            val topMargin = (targetTop - dpToPx(160)).coerceAtLeast(dpToPx(48).toFloat()).toInt()
            cardParams.setMargins(dpToPx(24), topMargin, dpToPx(24), dpToPx(24))
        } else {
            // Target is in top half -> place tooltip below target, clamped to screen height
            cardParams.gravity = Gravity.TOP
            val topMargin = (targetBottom + dpToPx(16)).coerceAtMost(screenHeight - dpToPx(220)).coerceAtLeast(dpToPx(48).toFloat()).toInt()
            cardParams.setMargins(dpToPx(24), topMargin, dpToPx(24), dpToPx(24))
        }
        cardTooltip?.layoutParams = cardParams
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != View.VISIBLE) return

        // Draw dark mask
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // Clear hole around target view
        if (isHighlighting && !targetRect.isEmpty) {
            val cornerRadius = dpToPx(12).toFloat()
            canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, clearPaint)
            canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, strokePaint)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
