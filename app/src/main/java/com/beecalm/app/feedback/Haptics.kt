package com.beecalm.app.feedback

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.beecalm.app.grounding.HapticPattern

/** Gentle, low-amplitude vibrations: physical reassurance without startling anyone. */
class Haptics(context: Context) {
  private val vibrator = requireNotNull(context.getSystemService(VibratorManager::class.java)).defaultVibrator

  fun play(pattern: HapticPattern) {
    if (!vibrator.hasVibrator()) return
    val effect =
        when (pattern) {
          HapticPattern.SOFT -> VibrationEffect.createOneShot(40, 60)
          HapticPattern.CONFIRM ->
              VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35), intArrayOf(0, 90, 0, 90), -1)
          HapticPattern.SUCCESS ->
              // A slow swell up and back down, like one deep breath.
              VibrationEffect.createWaveform(
                  longArrayOf(0, 120, 120, 160, 120, 120),
                  intArrayOf(0, 40, 70, 110, 70, 40),
                  -1,
              )
        }
    vibrator.vibrate(effect)
  }
}
