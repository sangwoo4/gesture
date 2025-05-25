package com.square.aircommand.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class FragmentRender @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var mBitmap: Bitmap? = null
    private val mTargetRect = Rect()
    private var fps: Float = 0f
    private var inferTime: Long = 0
    private var preprocessTime: Long = 0
    private var postprocessTime: Long = 0

    private val textPaint = Paint().apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = 50f
        isAntiAlias = true
    }

    fun render(
        image: Bitmap,
        fps: Float,
        inferTime: Long,
        preprocessTime: Long,
        postprocessTime: Long
    ) {
        synchronized(this) {
            mBitmap?.recycle()
            mBitmap = image.copy(Bitmap.Config.ARGB_8888, false)
            this.fps = fps
            this.inferTime = inferTime
            this.preprocessTime = preprocessTime
            this.postprocessTime = postprocessTime
        }
        postInvalidate()
    }

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(this) {
            mBitmap?.let { bitmap ->
                val canvasRatio = width.toFloat() / height.toFloat()
                val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                val (targetWidth, targetHeight) = if (canvasRatio > bitmapRatio) {
                    val h = height
                    val w = (h * bitmapRatio).toInt()
                    w to h
                } else {
                    val w = width
                    val h = (w / bitmapRatio).toInt()
                    w to h
                }

                val left = (width - targetWidth) / 2
                val top = (height - targetHeight) / 2
                mTargetRect.set(left, top, left + targetWidth, top + targetHeight)

                canvas.drawBitmap(bitmap, null, mTargetRect, null)

                // 한글로 표시
                canvas.drawText("FPS: ${"%.0f".format(fps)}", 30f, 60f, textPaint)
                canvas.drawText("전처리: ${"%.1f".format(preprocessTime / 1_000_000f)}ms", 30f, 130f, textPaint)
                canvas.drawText("추론: ${"%.1f".format(inferTime / 1_000_000f)}ms", 30f, 190f, textPaint)
                canvas.drawText("후처리: ${"%.1f".format(postprocessTime / 1_000_000f)}ms", 30f, 250f, textPaint)
            }
        }
    }
}