package com.beecalm.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spoken instructions, sent to the glasses' open-ear speakers.
 *
 * Android's TextToSpeech can't be pointed at a specific output, so when the glasses are connected
 * as a Bluetooth audio device each line is synthesized to a small file and played with a
 * MediaPlayer whose preferred device is the glasses. With no glasses audio found, it speaks
 * normally through whatever the phone is using.
 */
class VoiceGuide(private val context: Context) : AutoCloseable {
  private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
  private val ids = AtomicInteger()
  private val pending = mutableMapOf<String, () -> Unit>()
  private var player: MediaPlayer? = null
  private val pendingFiles = ConcurrentHashMap<String, Pair<File, AudioDeviceInfo>>()
  @Volatile private var ready = false
  private val queuedBeforeReady = mutableListOf<Pair<String, (() -> Unit)?>>()

  private val attributes =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()

  private val tts: TextToSpeech =
      TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
          configure()
          ready = true
          synchronized(queuedBeforeReady) {
            queuedBeforeReady.forEach { (t, done) -> speak(t, done) }
            queuedBeforeReady.clear()
          }
        } else {
          Log.e(TAG, "Text to speech is not available ($status)")
        }
      }

  private fun configure() {
    tts.language = Locale.getDefault()
    tts.setSpeechRate(0.88f) // a little slower than default: calmer, easier to follow
    tts.setPitch(0.95f)
    tts.setAudioAttributes(attributes)
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String) {}

          override fun onDone(utteranceId: String) = onSynthesized(utteranceId)

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String) = finish(utteranceId)

          override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
  }

  /** The glasses, if they're connected as a Bluetooth audio output. */
  fun glassesOutput(): AudioDeviceInfo? =
      audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type in BLUETOOTH_OUTPUTS
      }

  /** Speaks [text], interrupting anything still playing. [onDone] runs when it has finished. */
  fun speak(text: String, onDone: (() -> Unit)? = null) {
    if (!ready) {
      synchronized(queuedBeforeReady) { queuedBeforeReady += text to onDone }
      return
    }
    stopPlayback()
    val id = "line-${ids.incrementAndGet()}"
    synchronized(pending) {
      pending.clear()
      onDone?.let { pending[id] = it }
    }
    val glasses = glassesOutput()
    if (glasses != null) {
      val file = File(context.cacheDir, "$id.wav")
      pendingFiles[id] = file to glasses
      tts.synthesizeToFile(text, Bundle(), file, id)
    } else {
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }
  }

  private fun onSynthesized(id: String) {
    val entry = pendingFiles.remove(id)
    if (entry == null) {
      finish(id) // spoken directly by TTS
      return
    }
    val (file, device) = entry
    try {
      val mp = MediaPlayer()
      mp.setAudioAttributes(attributes)
      mp.setDataSource(file.absolutePath)
      mp.setPreferredDevice(device)
      mp.setOnCompletionListener {
        it.release()
        if (player === it) player = null
        file.delete()
        finish(id)
      }
      mp.setOnErrorListener { p, _, _ ->
        p.release()
        file.delete()
        finish(id)
        true
      }
      mp.prepare()
      player = mp
      mp.start()
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't play on glasses, falling back to phone speaker", t)
      file.delete()
      finish(id)
    }
  }

  private fun finish(id: String) {
    val cb = synchronized(pending) { pending.remove(id) }
    cb?.invoke()
  }

  private fun stopPlayback() {
    tts.stop()
    player?.let {
      try {
        it.stop()
      } catch (_: Throwable) {}
      it.release()
    }
    player = null
  }

  override fun close() {
    stopPlayback()
    tts.shutdown()
  }

  companion object {
    private const val TAG = "BeeCalm:Voice"
    val BLUETOOTH_OUTPUTS =
        setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
  }
}
