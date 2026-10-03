package com.beecalm.app.grounding

import com.beecalm.app.detection.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingStateMachineTest {
  private fun seen(label: String) =
      GroundingEvent.ObjectSeen(Detection(label, 0.9f, 0.3f, 0.3f, 0.6f, 0.6f))

  private fun GroundingStateMachine.startDetecting() {
    handle(GroundingEvent.Start)
    handle(GroundingEvent.PromptFinished)
  }

  @Test
  fun startPromptsThenDetectsWithTenSecondTimer() {
    val m = GroundingStateMachine()
    val start = m.handle(GroundingEvent.Start)
    assertEquals(GroundingState.PromptSee(1), m.state)
    assertTrue(start.any { it is GroundingEffect.Speak && it.thenPromptFinished })

    val detect = m.handle(GroundingEvent.PromptFinished)
    assertEquals(GroundingState.Detecting(1), m.state)
    assertTrue(detect.contains(GroundingEffect.StartTimer(10_000L)))
  }

  @Test
  fun detectedObjectChimesAndMovesToNextPrompt() {
    val m = GroundingStateMachine()
    m.startDetecting()
    val effects = m.handle(seen("cup"))
    assertEquals(GroundingState.PromptSee(2), m.state)
    assertTrue(effects.contains(GroundingEffect.Chime))
    assertTrue(effects.contains(GroundingEffect.Haptic(HapticPattern.CONFIRM)))
    assertTrue(effects.any { it is GroundingEffect.Speak && it.text.startsWith("A cup.") })
    assertEquals(1, m.progress)
  }

  @Test
  fun sameObjectDoesNotCountTwice() {
    val m = GroundingStateMachine()
    m.startDetecting()
    m.handle(seen("cup"))
    m.handle(GroundingEvent.PromptFinished)
    val again = m.handle(seen("cup"))
    assertTrue(again.isEmpty())
    assertEquals(GroundingState.Detecting(2), m.state)
  }

  @Test
  fun firstTimeoutAsksToHoldStillSecondBypasses() {
    val m = GroundingStateMachine()
    m.startDetecting()
    val first = m.handle(GroundingEvent.Timeout)
    assertEquals(GroundingState.Detecting(1, nudges = 1), m.state)
    assertTrue(first.any { it is GroundingEffect.Speak && it.text.contains("Hold your head still") })

    m.handle(GroundingEvent.Timeout)
    assertEquals(GroundingState.PromptSee(2), m.state)
  }

  @Test
  fun fullExerciseReachesComplete() {
    val m = GroundingStateMachine()
    m.startDetecting()
    listOf("cup", "chair", "laptop", "plant", "book").forEachIndexed { i, label ->
      m.handle(seen(label))
      if (i < 4) m.handle(GroundingEvent.PromptFinished)
    }
    assertEquals(GroundingState.SensePhase(Sense.TOUCH, 1), m.state)
    assertEquals(5, m.progress)

    repeat(4 + 3 + 2 + 1) { m.handle(GroundingEvent.Confirm) }
    assertEquals(GroundingState.Complete, m.state)
    assertEquals(Sense.TOTAL, m.progress)
  }

  @Test
  fun stopReturnsToIdleAndCancelsTimer() {
    val m = GroundingStateMachine()
    m.startDetecting()
    val effects = m.handle(GroundingEvent.Stop)
    assertEquals(GroundingState.Idle, m.state)
    assertTrue(effects.contains(GroundingEffect.CancelTimer))
  }

  @Test
  fun articleUsesAnBeforeVowels() {
    val m = GroundingStateMachine()
    m.startDetecting()
    val effects = m.handle(seen("orange"))
    assertTrue(effects.any { it is GroundingEffect.Speak && it.text.startsWith("An orange.") })
  }
}
