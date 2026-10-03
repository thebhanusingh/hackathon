package com.beecalm.app.glasses

import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class GlassesStatus(val label: String) {
  NOT_REGISTERED("Connect your glasses in the Meta AI app"),
  NO_GLASSES("Looking for your glasses"),
  CONNECTING("Connecting to your glasses"),
  STREAMING("Glasses connected"),
  PAUSED("Glasses paused"),
  ERROR("Glasses disconnected"),
}

/**
 * Everything that talks to the Meta glasses through the Wearables Device Access Toolkit: one
 * session, with the camera stream (frames in) and the lens display (Bee Calm cards out).
 */
class GlassesLink(
    private val scope: CoroutineScope,
    private val wantFrame: () -> Boolean,
    private val onFrame: (Bitmap) -> Unit,
) {
  private val _status = MutableStateFlow(GlassesStatus.NO_GLASSES)
  val status: StateFlow<GlassesStatus> = _status.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  private val _hasDisplay = MutableStateFlow(false)
  val hasDisplay: StateFlow<Boolean> = _hasDisplay.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var display: Display? = null
  private val jobs = mutableListOf<Job>()
  private val decoder = HevcFrameDecoder(wantFrame, onFrame)

  fun start() {
    if (session != null) return
    if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
      _status.value = GlassesStatus.NOT_REGISTERED
      return
    }
    _status.value = GlassesStatus.CONNECTING
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { s ->
              session = s
              jobs += scope.launch { s.errors.collect { e -> fail(e.description) } }
              jobs +=
                  scope.launch {
                    s.state.collect { state ->
                      when (state) {
                        DeviceSessionState.STARTED -> onSessionStarted(s)
                        DeviceSessionState.PAUSED -> _status.value = GlassesStatus.PAUSED
                        DeviceSessionState.STOPPED -> {
                          if (session === s) teardown(GlassesStatus.NO_GLASSES)
                        }
                        else -> Unit
                      }
                    }
                  }
              // Fire-and-forget: failures arrive through s.errors.
              s.start()
            },
            onFailure = { error, _ -> fail(error.description) },
        )
  }

  fun stop() = teardown(GlassesStatus.NO_GLASSES)

  private fun onSessionStarted(s: DeviceSession) {
    if (camera == null) attachCamera(s)
    if (display == null) attachDisplay(s)
  }

  private fun attachCamera(s: DeviceSession) {
    s.addCamera(
            StreamConfiguration(
                audioCodec = null,
                videoQuality = VideoQuality.LOW, // 360x640 is plenty for a 640x640 detector
                frameRate = 15,
                compressVideo = true, // keeps flowing with the screen off; see HevcFrameDecoder
            ),
        )
        .fold(
            onSuccess = { cam ->
              camera = cam
              jobs += scope.launch(Dispatchers.Default) { cam.stream.videoStream.collect(::onVideo) }
              jobs +=
                  scope.launch {
                    cam.stream.state.collect { st ->
                      when (st) {
                        StreamState.STREAMING -> _status.value = GlassesStatus.STREAMING
                        StreamState.PAUSED -> _status.value = GlassesStatus.PAUSED
                        else -> Unit
                      }
                    }
                  }
              jobs += scope.launch { cam.stream.errorStream.collect { e -> _lastError.value = e.description } }
              cam.stream.start().onFailure { error, _ -> fail(error.description) }
            },
            onFailure = { error, _ -> fail(error.description) },
        )
  }

  private fun attachDisplay(s: DeviceSession) {
    val capable = s.deviceInfo.value.isDisplayCapable()
    if (!capable) {
      Log.i(TAG, "These glasses have no display; using voice only")
      return
    }
    s.addDisplay()
        .fold(
            onSuccess = { d ->
              display = d
              jobs += scope.launch { d.state.collect { _hasDisplay.value = it == DisplayState.STARTED } }
            },
            onFailure = { error, _ -> Log.w(TAG, "Display would not attach: ${error.description}") },
        )
  }

  private fun onVideo(frame: VideoFrame) {
    if (!frame.isCompressed) return
    val buf = frame.buffer.duplicate()
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    decoder.offer(bytes, frame.presentationTimeUs, frame.isCodecConfig, frame.width, frame.height)
  }

  /** Replaces whatever is on the lens. Silently does nothing if there's no display. */
  suspend fun showOnLens(content: ContentScope.() -> Unit) {
    val d = display ?: return
    val ready = withTimeoutOrNull(3_000) { d.state.first { it == DisplayState.STARTED } }
    if (ready == null) return
    withContext(Dispatchers.IO) {
      d.sendContent(content).onFailure { error, _ -> Log.w(TAG, "Lens send failed: ${error.description}") }
    }
  }

  private fun fail(message: String) {
    Log.w(TAG, "Glasses error: $message")
    _lastError.value = message
    _status.value = GlassesStatus.ERROR
  }

  private fun teardown(status: GlassesStatus) {
    jobs.forEach { it.cancel() }
    jobs.clear()
    decoder.stop()
    try {
      camera?.stop()
    } catch (_: Throwable) {}
    try {
      display?.stop()
    } catch (_: Throwable) {}
    try {
      session?.stop()
    } catch (_: Throwable) {}
    camera = null
    display = null
    session = null
    _hasDisplay.value = false
    _status.value = status
  }

  private companion object {
    const val TAG = "BeeCalm:Glasses"
  }
}
