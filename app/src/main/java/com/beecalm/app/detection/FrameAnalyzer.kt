package com.beecalm.app.detection

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Takes frames from the glasses and runs the detector on them, newest frame only.
 *
 * Battery: frames arriving while a detection is still running are dropped rather than queued, and
 * the caller only submits frames while the exercise is actually looking for something.
 *
 * Motion blur: a quick sharpness check (variance of a Laplacian on a small grayscale copy) skips
 * frames taken mid head-turn, which would otherwise give flickering, low-confidence boxes.
 *
 * Steadiness: an object only counts once it is the focus in [STEADY_FRAMES] frames in a row, so a
 * one-frame misfire never triggers a chime.
 */
class FrameAnalyzer(
    private val detectorProvider: () -> ObjectDetector?,
    private val onResult: (FrameResult) -> Unit,
    private val onSteadyObject: (Detection, Bitmap) -> Unit,
) : AutoCloseable {

  private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "BeeCalm-Detector") }
  private val busy = AtomicBoolean(false)

  private var lastFocusLabel: String? = null
  private var focusStreak = 0

  /** Returns false if the frame was dropped because the detector was still busy. */
  fun submit(frame: Bitmap): Boolean {
    if (!busy.compareAndSet(false, true)) return false
    executor.execute {
      try {
        analyze(frame)
      } catch (t: Throwable) {
        Log.e(TAG, "Frame analysis failed", t)
      } finally {
        busy.set(false)
      }
    }
    return true
  }

  fun resetSteadiness() {
    executor.execute {
      lastFocusLabel = null
      focusStreak = 0
    }
  }

  private fun analyze(frame: Bitmap) {
    val detector = detectorProvider() ?: return
    if (isBlurry(frame)) {
      focusStreak = 0
      onResult(FrameResult(frame, emptyList(), null, 0, blurry = true))
      return
    }
    val (detections, ms) = detector.detect(frame)
    val focus = pickFocus(detections)
    onResult(FrameResult(frame, detections, focus, ms, blurry = false))

    if (focus == null) {
      focusStreak = 0
      lastFocusLabel = null
      return
    }
    focusStreak = if (focus.label == lastFocusLabel) focusStreak + 1 else 1
    lastFocusLabel = focus.label
    if (focusStreak >= STEADY_FRAMES) {
      focusStreak = 0
      onSteadyObject(focus, frame)
    }
  }

  override fun close() {
    executor.shutdownNow()
  }

  companion object {
    private const val TAG = "BeeCalm:Analyzer"
    const val STEADY_FRAMES = 2
    private const val FOCUS_MIN_SCORE = 0.45f
    private const val BLUR_SAMPLE_WIDTH = 160
    /** Below this Laplacian variance a frame is treated as motion-blurred. Tuned on 360p frames. */
    private const val BLUR_THRESHOLD = 60.0

    /**
     * The object the wearer is most likely looking at: confident, a good grounding anchor, and
     * close to the middle of the view (the glasses camera points where the head points).
     */
    fun pickFocus(detections: List<Detection>): Detection? =
        detections
            .filter { it.score >= FOCUS_MIN_SCORE && GroundingLabels.isGroundingObject(it.label) }
            .maxByOrNull { d ->
              val off = hypot(d.centerX - 0.5f, d.centerY - 0.5f)
              val sizeBonus = if (d.area > 0.6f) 0.7f else 1f // a wall-filling box is rarely "the thing"
              d.score * (1f - minOf(0.6f, off)) * sizeBonus
            }

    fun isBlurry(frame: Bitmap): Boolean {
      val w = BLUR_SAMPLE_WIDTH
      val h = (frame.height * w / frame.width.toFloat()).toInt().coerceAtLeast(3)
      val small = Bitmap.createScaledBitmap(frame, w, h, true)
      val px = IntArray(w * h)
      small.getPixels(px, 0, w, 0, 0, w, h)
      if (small !== frame) small.recycle()
      val gray = FloatArray(px.size) { i ->
        val p = px[i]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
      }
      return laplacianVariance(gray, w, h) < BLUR_THRESHOLD
    }

    fun laplacianVariance(gray: FloatArray, w: Int, h: Int): Double {
      var sum = 0.0
      var sumSq = 0.0
      var n = 0
      for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
          val i = y * w + x
          val lap = gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4 * gray[i]
          sum += lap
          sumSq += lap * lap
          n++
        }
      }
      if (n == 0) return 0.0
      val mean = sum / n
      return sumSq / n - mean * mean
    }
  }
}
