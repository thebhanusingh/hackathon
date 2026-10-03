package com.beecalm.app.feedback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A soft two-note chime (E5 then A5, like a small bell) generated in code, so there's no audio file
 * to ship. It plays on the glasses when they're the given output.
 */
object Chime {
  private const val RATE = 44_100

  private val samples: ShortArray by lazy {
    val notes = listOf(659.25 to 0.0, 880.0 to 0.12)
    val length = (RATE * 0.75).toInt()
    ShortArray(length) { i ->
      val t = i / RATE.toDouble()
      var v = 0.0
      for ((freq, start) in notes) {
        if (t >= start) {
          val local = t - start
          v += sin(2 * PI * freq * local) * exp(-local * 6.0) * 0.28
        }
      }
      (v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
  }

  fun play(output: AudioDeviceInfo?) {
    thread(name = "BeeCalm-Chime") {
      val track =
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                      .build())
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setSampleRate(RATE)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setTransferMode(AudioTrack.MODE_STATIC)
              .setBufferSizeInBytes(samples.size * 2)
              .build()
      try {
        output?.let { track.setPreferredDevice(it) }
        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep(800)
      } catch (_: Throwable) {
      } finally {
        track.release()
      }
    }
  }
}
