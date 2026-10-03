package com.beecalm.app.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Bee Quest look, ported from bee-quest.html so the phone and the lens draw the same bee,
 * the same honeycomb and the same moving amber dashed box.
 */
object BeeArt {
  // Colors straight from bee-quest.html's COLORS table.
  const val AMBER = 0xFFFFB800.toInt()
  const val HONEY = 0xFFFFD45C.toInt()
  const val BEE = 0xFFFFC93C.toInt()
  const val STRIPE = 0xFF3A2A10.toInt()
  const val DARK_TEXT = 0xFF2A1A00.toInt()
  const val MUTED = 0xFF9A9A9A.toInt()
  const val WING = 0xBFC8E6FF.toInt()
  const val HEX_DIM = 0xFF4A3A10.toInt()

  enum class Mood { CALM, HAPPY, UNSURE }

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val text =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }

  /** The bee from bee-quest.html's drawBee(), at [scale] (1.0 = 68 px wide body). */
  fun drawBee(c: Canvas, x: Float, y: Float, scale: Float, tMs: Long, mood: Mood = Mood.CALM, flip: Boolean = false) {
    c.save()
    c.translate(x, y)
    c.scale(if (flip) -scale else scale, scale)
    val flap = (sin(tMs * 0.05) * 0.5 + 0.5).toFloat()
    fill.color = WING
    drawEllipse(c, -10f, -26f, 14f, 22f * (0.5f + flap * 0.5f), -0.4f)
    drawEllipse(c, 10f, -26f, 14f, 22f * (0.5f + flap * 0.5f), 0.4f)
    fill.color = BEE
    val body = RectF(-34f, -24f, 34f, 24f)
    c.drawOval(body, fill)
    c.save()
    val clip = Path().apply { addOval(body, Path.Direction.CW) }
    c.clipPath(clip)
    fill.color = STRIPE
    c.drawRect(-14f, -30f, -6f, 30f, fill)
    c.drawRect(2f, -30f, 10f, 30f, fill)
    c.restore()
    fill.color = STRIPE
    c.drawPath(Path().apply { moveTo(-33f, -5f); lineTo(-46f, 0f); lineTo(-33f, 5f); close() }, fill)
    fill.color = 0xFFFFFFFF.toInt()
    c.drawCircle(20f, -6f, 7f, fill)
    fill.color = 0xFF111111.toInt()
    c.drawCircle(22f, -6f, 3.5f, fill)
    stroke.color = STRIPE
    stroke.strokeWidth = 2.5f
    stroke.pathEffect = null
    when (mood) {
      Mood.HAPPY -> c.drawArc(RectF(14f, -3f, 28f, 11f), 18f, 144f, false, stroke)
      Mood.UNSURE -> c.drawLine(16f, 10f, 27f, 9f, stroke)
      Mood.CALM -> c.drawArc(RectF(17f, 1f, 27f, 11f), 27f, 126f, false, stroke)
    }
    c.restore()
  }

  private fun drawEllipse(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rot: Float) {
    c.save()
    c.translate(cx, cy)
    c.rotate((rot * 180 / PI).toFloat())
    c.drawOval(RectF(-rx, -ry, rx, ry), fill)
    c.restore()
  }

  fun hexPath(cx: Float, cy: Float, r: Float): Path =
      Path().apply {
        for (i in 0 until 6) {
          val a = PI / 180 * (60 * i - 30)
          val x = cx + r * cos(a).toFloat()
          val y = cy + r * sin(a).toFloat()
          if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
      }

  /**
   * A honeycomb of [total] cells with [filled] lit in amber: 5+4+3+2+1 = 15 cells, one per item of
   * the exercise, so progress reads like the honeycomb timer in Bee Quest.
   */
  fun drawHoneycomb(c: Canvas, cx: Float, cy: Float, r: Float, filled: Int, total: Int, rows: List<Int>) {
    val dx = kotlin.math.sqrt(3f) * r + 4f
    val dy = 1.5f * r + 4f
    var index = 0
    val startY = cy - (rows.size - 1) * dy / 2
    rows.forEachIndexed { row, count ->
      val y = startY + row * dy
      val startX = cx - (count - 1) * dx / 2
      for (k in 0 until count) {
        if (index >= total) return
        val p = hexPath(startX + k * dx, y, r)
        if (index < filled) {
          fill.color = AMBER
          c.drawPath(p, fill)
        }
        stroke.pathEffect = null
        stroke.color = if (index < filled) HONEY else HEX_DIM
        stroke.strokeWidth = 3f
        c.drawPath(p, stroke)
        index++
      }
    }
  }

  /** The moving amber dashed box with the object's name in its top-left corner. */
  fun drawObjectBox(c: Canvas, box: RectF, label: String, tMs: Long, textSize: Float = 16f) {
    stroke.color = AMBER
    stroke.strokeWidth = 4f
    stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 8f), -(tMs / 30f) % 22f)
    c.drawRect(box, stroke)
    stroke.pathEffect = null
    text.color = AMBER
    text.textSize = textSize
    text.textAlign = Paint.Align.LEFT
    c.drawText(label, box.left + 6f, box.top + 6f + textSize, text)
  }

  fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true) {
    text.color = color
    text.textSize = size
    text.textAlign = Paint.Align.CENTER
    text.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    c.drawText(s, x, y, text)
  }

  /** Word-wraps [s] centered on [x]; returns the number of lines drawn. */
  fun drawWrapped(c: Canvas, s: String, x: Float, y: Float, maxWidth: Float, size: Float, color: Int, lineHeight: Float): Int {
    text.color = color
    text.textSize = size
    text.textAlign = Paint.Align.CENTER
    text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    val lines = mutableListOf<String>()
    var line = ""
    for (w in s.split(" ")) {
      val test = if (line.isEmpty()) w else "$line $w"
      if (text.measureText(test) > maxWidth && line.isNotEmpty()) {
        lines += line
        line = w
      } else line = test
    }
    if (line.isNotEmpty()) lines += line
    lines.forEachIndexed { i, l -> c.drawText(l, x, y + i * lineHeight, text) }
    return lines.size
  }

  /** The "Challenge complete!" hexagon badge from Bee Quest, reworded for grounding. */
  fun drawCompleteBadge(c: Canvas, cx: Float, cy: Float, r: Float) {
    val p = hexPath(cx, cy, r)
    fill.color = AMBER
    c.drawPath(p, fill)
    stroke.color = HONEY
    stroke.strokeWidth = 6f
    stroke.pathEffect = null
    c.drawPath(p, stroke)
    drawText(c, "Grounding", cx, cy - 6f, r * 0.28f, DARK_TEXT)
    drawText(c, "complete!", cx, cy + r * 0.3f, r * 0.28f, DARK_TEXT)
  }
}
