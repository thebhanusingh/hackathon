package com.beecalm.app.detection

/**
 * One object found in a frame. [left], [top], [right] and [bottom] are fractions (0..1) of the
 * original frame, so the box can be drawn on any preview size.
 */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
  val centerX: Float
    get() = (left + right) / 2f

  val centerY: Float
    get() = (top + bottom) / 2f

  val area: Float
    get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** What one pass of the analyzer saw. */
data class FrameResult(
    val frame: android.graphics.Bitmap,
    val detections: List<Detection>,
    /** The object nearest where the wearer is looking, if any qualifies for grounding. */
    val focus: Detection?,
    val inferenceMs: Long,
    val blurry: Boolean,
)
