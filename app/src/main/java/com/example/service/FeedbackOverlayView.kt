package com.example.service

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class FeedbackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var gestureResult: GestureResult? = null

    // Paints
    private val boxPaint = Paint().apply {
        color = Color.parseColor("#3498db") // Cyber Blue
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
        isAntiAlias = true
    }

    private val centroidPaint = Paint().apply {
        color = Color.parseColor("#e74c3c") // Electric Red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val crosshairPaint = Paint().apply {
        color = Color.parseColor("#e74c3c")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val fingerPaint = Paint().apply {
        color = Color.parseColor("#f1c40f") // Glowing Yellow
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val backgroundTextPaint = Paint().apply {
        color = Color.parseColor("#80000000") // Translucent Black
        style = Paint.Style.FILL
    }

    fun updateResult(result: GestureResult?) {
        this.gestureResult = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = gestureResult ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw the debug skin segmented bitmap if available for amazing AR feedback
        result.debugBitmap?.let { bmp ->
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bmp, src, dst, Paint().apply { isFilterBitmap = false })
        }

        // 2. Draw Hand Bounding Box (Cyber styled)
        result.boundingBox?.let { box ->
            val left = box.left * w
            val top = box.top * h
            val right = box.right * w
            val bottom = box.bottom * h
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, boxPaint)
        }

        // 3. Draw Centroid & Crosshair
        result.centroid?.let { pt ->
            val cx = pt.x * w
            val cy = pt.y * h
            // Crosshairs
            canvas.drawLine(cx - 30, cy, cx + 30, cy, crosshairPaint)
            canvas.drawLine(cx, cy - 30, cx, cy + 30, crosshairPaint)
            // Center circle
            canvas.drawCircle(cx, cy, 12f, centroidPaint)
        }

        // 4. Draw Finger Peaks (Yellow dots with pulse effect)
        for (peak in result.peaks) {
            val px = peak.x * w
            val py = peak.y * h
            canvas.drawCircle(px, py, 14f, fingerPaint)
            // Little outer ring
            canvas.drawCircle(px, py, 22f, Paint().apply {
                color = Color.parseColor("#80f1c40f")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            })
        }

        // 5. Draw detected gesture or status message on a semi-transparent card
        val statusText = when (result.detectedGesture) {
            "SWIPE_LEFT" -> "SOLA SEÇİM ➔"
            "SWIPE_RIGHT" -> "➔ SAĞA SEÇİM"
            "SWIPE_UP" -> "▲ YUKARI SEÇİM"
            "SWIPE_DOWN" -> "▼ AŞAĞI SEÇİM"
            "FIST" -> "✊ YUMRUK ALGILANDI"
            "PEACE" -> "✌ ZAFER İŞARETİ"
            else -> if (result.skinRatio > 0.025f) "EL ALGILANDI (Sistem Hazır)" else "El Bekleniyor..."
        }

        // Draw HUD Status Bar
        canvas.drawRect(0f, h - 60f, w, h, backgroundTextPaint)
        canvas.drawText(statusText, 20f, h - 18f, textPaint)
    }
}
