package com.beecalm.app.grounding

import com.beecalm.app.detection.Detection

/** The five senses of the 5-4-3-2-1 exercise, in the order they are walked through. */
enum class Sense(val count: Int, val noun: String, val verb: String) {
  SEE(5, "thing you can see", "see"),
  TOUCH(4, "thing you can feel", "feel"),
  HEAR(3, "sound you can hear", "hear"),
  SMELL(2, "thing you can smell", "smell"),
  TASTE(1, "thing you can taste", "taste");

  val next: Sense?
    get() = entries.getOrNull(ordinal + 1)

  /** How many items come before this sense, so progress can be shown as one honeycomb of 15. */
  val offset: Int
    get() = entries.take(ordinal).sumOf { it.count }

  companion object {
    val TOTAL = entries.sumOf { it.count }
  }
}

/**
 * Where the exercise is. The names follow the flow the user described:
 * IDLE -> PROMPT_SEE -> DETECTING -> ... -> TOUCH_PHASE -> ... -> COMPLETE.
 */
sealed interface GroundingState {
  data object Idle : GroundingState

  /** A "find something you can see" prompt is being spoken; detection starts when it ends. */
  data class PromptSee(val item: Int) : GroundingState

  /** The camera is looking for the [item]th thing. [nudges] counts the gentle timeouts so far. */
  data class Detecting(val item: Int, val nudges: Int = 0) : GroundingState

  /** Touch, hear, smell and taste: the user confirms each item with a pinch or a big button. */
  data class SensePhase(val sense: Sense, val item: Int, val nudges: Int = 0) : GroundingState

  data object Complete : GroundingState
}

sealed interface GroundingEvent {
  data object Start : GroundingEvent

  /** The current spoken prompt finished playing. */
  data object PromptFinished : GroundingEvent

  /** The detector has confirmed a steady object in view. */
  data class ObjectSeen(val detection: Detection) : GroundingEvent

  /** Pinch on the glasses, or the big "Got it" button on the phone. */
  data object Confirm : GroundingEvent

  /** The user asked to move on without this item. */
  data object Skip : GroundingEvent

  /** The fallback timer ran out. */
  data object Timeout : GroundingEvent

  data object Stop : GroundingEvent
}

enum class HapticPattern {
  /** One soft tap: "I'm here". */
  SOFT,
  /** Two short taps: "that counted". */
  CONFIRM,
  /** A slow swell: "you finished". */
  SUCCESS,
}

sealed interface GroundingEffect {
  /** Say [text]. If [thenPromptFinished] the engine reports [GroundingEvent.PromptFinished] after. */
  data class Speak(val text: String, val thenPromptFinished: Boolean = false) : GroundingEffect

  data object Chime : GroundingEffect

  data class Haptic(val pattern: HapticPattern) : GroundingEffect

  data class Highlight(val detection: Detection) : GroundingEffect

  data object ClearHighlight : GroundingEffect

  data class StartTimer(val millis: Long) : GroundingEffect

  data object CancelTimer : GroundingEffect
}

/**
 * The whole grounding exercise as a pure state machine: every input is an event, every output is
 * an effect, and nothing here touches Android. The engine around it plays the effects.
 */
class GroundingStateMachine(
    private val detectTimeoutMs: Long = DETECT_TIMEOUT_MS,
    private val senseTimeoutMs: Long = SENSE_TIMEOUT_MS,
) {
  var state: GroundingState = GroundingState.Idle
    private set

  /** What the camera has already counted this round, so the same cup doesn't count five times. */
  private val _seen = mutableListOf<String>()
  val seen: List<String>
    get() = _seen

  /** Items finished so far, out of [Sense.TOTAL]. */
  val progress: Int
    get() =
        when (val s = state) {
          GroundingState.Idle -> 0
          is GroundingState.PromptSee -> s.item - 1
          is GroundingState.Detecting -> s.item - 1
          is GroundingState.SensePhase -> s.sense.offset + s.item - 1
          GroundingState.Complete -> Sense.TOTAL
        }

  fun handle(event: GroundingEvent): List<GroundingEffect> {
    if (event == GroundingEvent.Stop) return stop()
    return when (val s = state) {
      GroundingState.Idle,
      GroundingState.Complete -> if (event == GroundingEvent.Start) begin() else emptyList()
      is GroundingState.PromptSee -> onPromptSee(s, event)
      is GroundingState.Detecting -> onDetecting(s, event)
      is GroundingState.SensePhase -> onSensePhase(s, event)
    }
  }

  private fun begin(): List<GroundingEffect> {
    _seen.clear()
    state = GroundingState.PromptSee(1)
    return listOf(
        GroundingEffect.ClearHighlight,
        GroundingEffect.Haptic(HapticPattern.SOFT),
        GroundingEffect.Speak(
            "I'm here with you. Let's breathe and look around together. " +
                "Find five things you can see. Just look at one thing near you.",
            thenPromptFinished = true,
        ),
    )
  }

  private fun stop(): List<GroundingEffect> {
    val wasRunning = state != GroundingState.Idle && state != GroundingState.Complete
    state = GroundingState.Idle
    _seen.clear()
    val effects = mutableListOf(GroundingEffect.CancelTimer, GroundingEffect.ClearHighlight)
    if (wasRunning) effects += GroundingEffect.Speak("Okay, we can stop here. You did well.")
    return effects
  }

  private fun onPromptSee(s: GroundingState.PromptSee, event: GroundingEvent) =
      when (event) {
        GroundingEvent.PromptFinished -> {
          state = GroundingState.Detecting(s.item)
          listOf(GroundingEffect.StartTimer(detectTimeoutMs))
        }
        // Someone pinching while the prompt plays is ready now: count it like the camera would.
        GroundingEvent.Confirm,
        GroundingEvent.Skip -> countSeen(s.item, label = null)
        else -> emptyList()
      }

  private fun onDetecting(s: GroundingState.Detecting, event: GroundingEvent) =
      when (event) {
        is GroundingEvent.ObjectSeen -> {
          val label = event.detection.label
          if (label in _seen) emptyList() else countSeen(s.item, label, event.detection)
        }
        GroundingEvent.Confirm,
        GroundingEvent.Skip -> countSeen(s.item, label = null)
        GroundingEvent.Timeout ->
            if (s.nudges == 0) {
              state = s.copy(nudges = 1)
              listOf(
                  GroundingEffect.Haptic(HapticPattern.SOFT),
                  GroundingEffect.Speak(
                      "Take your time. Hold your head still, and rest your eyes on one thing in front of you."),
                  GroundingEffect.StartTimer(detectTimeoutMs),
              )
            } else {
              // Second timeout: never leave someone stuck. Count it and keep going.
              countSeen(s.item, label = null, bypassed = true)
            }
        else -> emptyList()
      }

  private fun countSeen(
      item: Int,
      label: String?,
      detection: Detection? = null,
      bypassed: Boolean = false,
  ): List<GroundingEffect> {
    label?.let { _seen += it }
    val effects =
        mutableListOf<GroundingEffect>(
            GroundingEffect.CancelTimer,
            GroundingEffect.Chime,
            GroundingEffect.Haptic(HapticPattern.CONFIRM),
        )
    effects += detection?.let { GroundingEffect.Highlight(it) } ?: GroundingEffect.ClearHighlight
    val heard =
        when {
          label != null -> "${article(label)} $label. "
          bypassed -> "That's okay. Just notice anything you can see, and we'll count it. "
          else -> "Good. "
        }
    val remaining = Sense.SEE.count - item
    if (remaining > 0) {
      state = GroundingState.PromptSee(item + 1)
      val more = if (remaining == 1) "one more thing" else "$remaining more things"
      effects += GroundingEffect.Speak("${heard}Now find $more you can see.", thenPromptFinished = true)
    } else {
      effects += enterSense(Sense.TOUCH, intro = heard + "That's five. ")
    }
    return effects
  }

  private fun onSensePhase(s: GroundingState.SensePhase, event: GroundingEvent) =
      when (event) {
        GroundingEvent.Confirm,
        GroundingEvent.Skip -> {
          val effects =
              mutableListOf<GroundingEffect>(
                  GroundingEffect.CancelTimer,
                  GroundingEffect.Chime,
                  GroundingEffect.Haptic(HapticPattern.CONFIRM),
              )
          val remaining = s.sense.count - s.item
          val next = s.sense.next
          when {
            remaining > 0 -> {
              state = GroundingState.SensePhase(s.sense, s.item + 1)
              val more = if (remaining == 1) "one more" else "$remaining more"
              effects += GroundingEffect.Speak("Good. $more.")
              effects += GroundingEffect.StartTimer(senseTimeoutMs)
            }
            next != null -> effects += enterSense(next, intro = "Good. ")
            else -> effects += complete()
          }
          effects
        }
        GroundingEvent.Timeout -> {
          state = s.copy(nudges = s.nudges + 1)
          listOf(
              GroundingEffect.Speak(
                  "No rush. When you notice a ${s.sense.noun}, pinch, or tap the big button."),
              GroundingEffect.StartTimer(senseTimeoutMs),
          )
        }
        else -> emptyList()
      }

  private fun enterSense(sense: Sense, intro: String): List<GroundingEffect> {
    state = GroundingState.SensePhase(sense, 1)
    val ask =
        when (sense) {
          Sense.TOUCH ->
              "Now notice four things you can feel. Your feet on the floor, your hands, your clothes. " +
                  "Pinch each time you feel one."
          Sense.HEAR -> "Now listen for three sounds. Near or far. Pinch for each one."
          Sense.SMELL -> "Now two things you can smell. Even the air counts. Pinch for each."
          Sense.TASTE -> "Last one. Notice one thing you can taste. Pinch when you have it."
          Sense.SEE -> "Find five things you can see."
        }
    return listOf(
        GroundingEffect.ClearHighlight,
        GroundingEffect.Speak(intro + ask),
        GroundingEffect.StartTimer(senseTimeoutMs),
    )
  }

  private fun complete(): List<GroundingEffect> {
    state = GroundingState.Complete
    return listOf(
        GroundingEffect.CancelTimer,
        GroundingEffect.Haptic(HapticPattern.SUCCESS),
        GroundingEffect.Speak(
            "Grounding complete. You're here, right now. Take one more slow breath. You did that."),
    )
  }

  private fun article(label: String): String =
      if (label.firstOrNull()?.lowercaseChar() in VOWELS) "An" else "A"

  companion object {
    const val DETECT_TIMEOUT_MS = 10_000L
    const val SENSE_TIMEOUT_MS = 25_000L
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
  }
}
