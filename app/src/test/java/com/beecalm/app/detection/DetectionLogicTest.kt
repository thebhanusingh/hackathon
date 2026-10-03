package com.beecalm.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionLogicTest {
  private fun d(label: String, score: Float, l: Float, t: Float, r: Float, b: Float) =
      Detection(label, score, l, t, r, b)

  @Test
  fun nmsDropsOverlappingSameLabelBoxes() {
    val kept =
        ObjectDetector.nms(
            listOf(
                d("Mug", 0.9f, 0.1f, 0.1f, 0.4f, 0.4f),
                d("Mug", 0.6f, 0.12f, 0.12f, 0.42f, 0.42f),
                d("Chair", 0.7f, 0.12f, 0.12f, 0.42f, 0.42f),
            ))
    assertEquals(listOf("Mug", "Chair"), kept.map { it.label })
  }

  @Test
  fun focusPrefersCenteredGroundingObjectsAndSkipsPeople() {
    val focus =
        FrameAnalyzer.pickFocus(
            listOf(
                d("Person", 0.95f, 0.4f, 0.3f, 0.6f, 0.9f),
                d("Mug", 0.7f, 0.45f, 0.45f, 0.55f, 0.55f),
                d("Chair", 0.75f, 0.0f, 0.0f, 0.15f, 0.2f),
            ))
    assertEquals("Mug", focus?.label)
  }

  @Test
  fun focusIgnoresLowConfidence() {
    assertNull(FrameAnalyzer.pickFocus(listOf(d("Mug", 0.3f, 0.4f, 0.4f, 0.6f, 0.6f))))
  }

  @Test
  fun labelsFilterAndSpeakNaturally() {
    assertFalse(GroundingLabels.isGroundingObject("Human hand"))
    assertFalse(GroundingLabels.isGroundingObject("Woman"))
    assertTrue(GroundingLabels.isGroundingObject("Houseplant"))
    assertEquals("plant", GroundingLabels.spokenName("Houseplant"))
    assertEquals("phone", GroundingLabels.spokenName("Mobile phone"))
    assertEquals("laptop", GroundingLabels.spokenName("Laptop"))
  }

  @Test
  fun sharpImageHasHigherLaplacianVarianceThanFlat() {
    val w = 20
    val h = 20
    val flat = FloatArray(w * h) { 128f }
    val checker = FloatArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) 0f else 255f }
    assertTrue(FrameAnalyzer.laplacianVariance(checker, w, h) > FrameAnalyzer.laplacianVariance(flat, w, h))
  }
}
