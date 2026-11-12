package io.github.myshihi.pleosproject26.e10seatdjparty

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/** Original vector-like cover illustration drawn with native Canvas. */
class RecordView(context: Context, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), accent, Color.rgb(68, 54, 97), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 24f, 24f, paint)
        paint.shader = null
        val size = minOf(width, height).toFloat()
        val x = width / 2f
        val y = height / 2f
        paint.color = Color.rgb(28, 25, 39)
        canvas.drawCircle(x, y, size * .4f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = Color.rgb(66, 58, 79)
        repeat(12) { canvas.drawCircle(x, y, size * (.18f + it * .017f), paint) }
        paint.style = Paint.Style.FILL
        paint.color = accent
        canvas.drawCircle(x, y, size * .15f, paint)
        paint.color = Color.rgb(28, 25, 39)
        canvas.drawCircle(x, y, size * .025f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * .018f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(239, 233, 225)
        canvas.drawLine(width * .84f, height * .17f, width * .84f, height * .51f, paint)
        canvas.drawLine(width * .84f, height * .51f, width * .72f, height * .66f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(209, 250, 136)
        canvas.drawCircle(width * .84f, height * .17f, size * .025f, paint)
        paint.color = Color.rgb(242, 236, 232)
        paint.textSize = size * .041f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText("모 두 의   D J   /   O R I G I N A L S", size * .07f, height - size * .06f, paint)
    }
}
