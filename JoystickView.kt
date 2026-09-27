package com.example.esp32rover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Simple virtual joystick. Reports normalized X/Y in -255..255 via the
 * listener - the same range the ESP32 firmware expects, so this can share
 * one protocol with physical gamepad input (see MainActivity).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMoveListener: ((x: Int, y: Int) -> Unit)? = null

    private val outerPaint = Paint().apply {
        color = Color.parseColor("#333333")
        isAntiAlias = true
    }
    private val knobPaint = Paint().apply {
        color = Color.parseColor("#007bff")
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        outerRadius = min(w, h) / 2f * 0.9f
        knobRadius = outerRadius * 0.4f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, outerRadius, outerPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val angle = atan2(dy.toDouble(), dx.toDouble())

                val clamped = min(distance, outerRadius)
                knobX = centerX + (clamped * cos(angle)).toFloat()
                knobY = centerY + (clamped * sin(angle)).toFloat()
                invalidate()

                // normalize to -255..255; Y inverted so pushing up = forward (positive)
                val normX = ((knobX - centerX) / outerRadius * 255).toInt()
                val normY = (-(knobY - centerY) / outerRadius * 255).toInt()
                onMoveListener?.invoke(normX.coerceIn(-255, 255), normY.coerceIn(-255, 255))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX
                knobY = centerY
                invalidate()
                onMoveListener?.invoke(0, 0)
            }
        }
        return true
    }
}
