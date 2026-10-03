package com.beecalm.app.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.beecalm.app.grounding.GroundingUiState
import com.beecalm.app.ui.BeeArt
import com.beecalm.app.ui.BeeArt.Mood

/**
 * Draws one Bee Calm card for the glasses lens. Black is see-through on the waveguide, so the
 * background stays black, exactly as in bee-quest.html.
 *
 * The lens is not true AR, so a box drawn over the real world would drift as the head moves.
 * Instead the lens shows a freeze-frame of the object the camera just found, outlined with the
 * amber dashed box, with the bee hovering over it.
 */
object LensRenderer {
  const val WIDTH = 600
  const val HEIGHT = 440

  fun render(state: GroundingUiState, tMs: Long): Bitmap {
    val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.BLACK)
    val cx = WIDTH / 2f

    val snapshot = state.highlightFrame
    val box = state.highlight
    if (state.isComplete) {
      BeeArt.drawCompleteBadge(c, cx, 170f, 120f)
      BeeArt.drawBee(c, cx, 40f, 0.8f, tMs, Mood.HAPPY)
    } else if (snapshot != null && box != null) {
      // Crop around the object with some margin and fit it into the top of the card.
      val fw = snapshot.width.toFloat()
      val fh = snapshot.height.toFloat()
      val margin = 0.35f
      val bw = (box.right - box.left) * fw
      val bh = (box.bottom - box.top) * fh
      val l = ((box.left * fw) - bw * margin).coerceAtLeast(0f)
      val t = ((box.top * fh) - bh * margin).coerceAtLeast(0f)
      val r = ((box.right * fw) + bw * margin).coerceAtMost(fw)
      val b = ((box.bottom * fh) + bh * margin).coerceAtMost(fh)
      val src = Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
      val maxW = 360f
      val maxH = 230f
      val s = minOf(maxW / src.width(), maxH / src.height())
      val dw = src.width() * s
      val dh = src.height() * s
      val dst = RectF(cx - dw / 2, 70f, cx + dw / 2, 70f + dh)
      c.drawBitmap(snapshot, src, dst, null)
      val obj =
          RectF(
              dst.left + (box.left * fw - l) * s,
              dst.top + (box.top * fh - t) * s,
              dst.left + (box.right * fw - l) * s,
              dst.top + (box.bottom * fh - t) * s,
          )
      BeeArt.drawObjectBox(c, obj, state.highlightLabel ?: "", tMs, 22f)
      BeeArt.drawBee(c, obj.centerX(), (obj.top - 30f).coerceAtLeast(36f), 0.7f, tMs, Mood.HAPPY)
    } else {
      val mood = if (state.blurryHint) Mood.UNSURE else Mood.CALM
      BeeArt.drawBee(c, cx, 120f, 1.4f, tMs, mood)
    }

    if (!state.isComplete) {
      BeeArt.drawWrapped(c, state.lensLine, cx, 340f, 540f, 30f, Color.WHITE, 38f)
    } else {
      BeeArt.drawText(c, "Take one more slow breath", cx, 340f, 28f, Color.WHITE)
    }
    BeeArt.drawHoneycomb(c, cx, 412f, 9f, state.progress, state.total, listOf(15))
    return bmp
  }
}
