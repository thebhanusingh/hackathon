package com.beecalm.app.grounding

import android.graphics.Bitmap
import com.beecalm.app.detection.Detection
import com.beecalm.app.glasses.GlassesStatus

/** Everything the phone screen and the lens need to draw, in one immutable snapshot. */
data class GroundingUiState(
    val state: GroundingState = GroundingState.Idle,
    val progress: Int = 0,
    val total: Int = Sense.TOTAL,
    /** Big words on the phone. */
    val headline: String = "Bee Calm",
    val detail: String = "When you're ready, start a grounding round. I'll guide you through it.",
    /** Short line under the bee on the lens. */
    val lensLine: String = "Pinch Start when you're ready",
    /** Label for the one big confirm button. Null hides it. */
    val primaryAction: String? = "Start",
    val highlight: Detection? = null,
    val highlightLabel: String? = null,
    val highlightFrame: Bitmap? = null,
    val previewFrame: Bitmap? = null,
    val liveDetections: List<Detection> = emptyList(),
    val blurryHint: Boolean = false,
    val inferenceMs: Long = 0,
    val usingGpu: Boolean = false,
    val glassesStatus: GlassesStatus = GlassesStatus.NO_GLASSES,
    val glassesError: String? = null,
) {
  val isRunning: Boolean
    get() = state != GroundingState.Idle && state != GroundingState.Complete

  val isComplete: Boolean
    get() = state == GroundingState.Complete

  val isDetecting: Boolean
    get() = state is GroundingState.Detecting || state is GroundingState.PromptSee

  companion object {
    /** The words for each state, kept short and plain for low cognitive load. */
    fun copyFor(state: GroundingState): Triple<String, String, String> =
        when (state) {
          GroundingState.Idle ->
              Triple("Bee Calm", "When you're ready, start a grounding round. I'll guide you through it.", "Pinch Start when you're ready")
          is GroundingState.PromptSee -> {
            val left = Sense.SEE.count - state.item + 1
            Triple("Look around", "Find $left ${plural(left, "thing")} you can see. Rest your eyes on one.", "Find $left ${plural(left, "thing")} you can see")
          }
          is GroundingState.Detecting -> {
            val left = Sense.SEE.count - state.item + 1
            Triple("Looking with you", "Hold your head still and look at one thing. $left to go.", "Look at one thing. $left to go")
          }
          is GroundingState.SensePhase -> {
            val left = state.sense.count - state.item + 1
            val noun =
                when (state.sense) {
                  Sense.TOUCH -> "thing you can feel"
                  Sense.HEAR -> "sound you can hear"
                  Sense.SMELL -> "thing you can smell"
                  Sense.TASTE -> "thing you can taste"
                  Sense.SEE -> "thing you can see"
                }
            val title =
                when (state.sense) {
                  Sense.TOUCH -> "Feel"
                  Sense.HEAR -> "Listen"
                  Sense.SMELL -> "Smell"
                  Sense.TASTE -> "Taste"
                  Sense.SEE -> "Look"
                }
            Triple(title, "Notice $left ${pluralNoun(left, noun)}. Pinch or tap the big button for each one.", "$left ${pluralNoun(left, noun)}")
          }
          GroundingState.Complete ->
              Triple("Grounding complete!", "You're here, right now. Take one more slow breath.", "Grounding complete!")
        }

    fun actionFor(state: GroundingState): String? =
        when (state) {
          GroundingState.Idle -> "Start"
          is GroundingState.PromptSee,
          is GroundingState.Detecting -> "I see one"
          is GroundingState.SensePhase -> "Got it"
          GroundingState.Complete -> "Start again"
        }

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    private fun pluralNoun(n: Int, noun: String): String {
      if (n == 1) return noun
      val first = noun.substringBefore(" ")
      return first + "s" + noun.removePrefix(first)
    }
  }
}
