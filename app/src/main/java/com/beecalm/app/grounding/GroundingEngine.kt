package com.beecalm.app.grounding

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.beecalm.app.detection.Detection
import com.beecalm.app.detection.FrameAnalyzer
import com.beecalm.app.detection.FrameResult
import com.beecalm.app.detection.GroundingLabels
import com.beecalm.app.detection.ObjectDetector
import com.beecalm.app.feedback.Chime
import com.beecalm.app.feedback.Haptics
import com.beecalm.app.feedback.VoiceGuide
import com.beecalm.app.glasses.GlassesLink
import com.beecalm.app.glasses.LensRenderer
import com.meta.wearable.dat.display.views.ActionRole
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.IconName
import com.meta.wearable.dat.display.views.ImageSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the grounding exercise: feeds events into [GroundingStateMachine] and plays the effects it
 * returns (voice on the glasses, chime, haptics, the amber highlight, timers), while pulling camera
 * frames from the glasses through the detector. One instance lives for the whole app; the
 * foreground service keeps it alive while the phone is locked.
 *
 * All state changes happen on the main thread, so the machine never needs locking.
 */
class GroundingEngine(private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val machine = GroundingStateMachine()

  private val _ui = MutableStateFlow(GroundingUiState())
  val ui: StateFlow<GroundingUiState> = _ui.asStateFlow()

  @Volatile private var detector: ObjectDetector? = null
  private val voice by lazy { VoiceGuide(context) }
  private val haptics by lazy { Haptics(context) }

  /** True while the phone screen is showing the preview, so frames are worth decoding. */
  @Volatile var previewVisible = false

  private val analyzer =
      FrameAnalyzer(
          detectorProvider = { detector },
          onResult = { r -> scope.launch { onFrameResult(r) } },
          onSteadyObject = { d, frame -> scope.launch { onSteadyObject(d, frame) } },
      )

  private var lastPreviewAt = 0L

  val glasses =
      GlassesLink(
          scope = scope,
          wantFrame = { _ui.value.isDetecting || previewVisible },
          onFrame = ::onGlassesFrame,
      )

  private var timerJob: Job? = null
  private var lensJob: Job? = null

  init {
    scope.launch { glasses.status.collect { s -> _ui.update { it.copy(glassesStatus = s) } } }
    scope.launch { glasses.lastError.collect { e -> _ui.update { it.copy(glassesError = e) } } }
    scope.launch { glasses.hasDisplay.collect { if (it) refreshLens() } }
    // Loading a 22 MB model takes a moment; do it off the main thread at startup.
    scope.launch(Dispatchers.Default) {
      try {
        val d = ObjectDetector(context)
        detector = d
        _ui.update { it.copy(usingGpu = d.usingGpu) }
      } catch (t: Throwable) {
        Log.e(TAG, "Could not load the detector", t)
      }
    }
  }

  fun connectGlasses() = glasses.start()

  fun disconnectGlasses() = glasses.stop()

  fun start() = send(GroundingEvent.Start)

  fun stop() = send(GroundingEvent.Stop)

  /** The one big button / pinch: starts, confirms an item, or restarts. */
  fun primary() {
    when (_ui.value.state) {
      GroundingState.Idle,
      GroundingState.Complete -> send(GroundingEvent.Start)
      else -> send(GroundingEvent.Confirm)
    }
  }

  fun skip() = send(GroundingEvent.Skip)

  private fun send(event: GroundingEvent) {
    scope.launch {
      val before = machine.state
      val effects = machine.handle(event)
      effects.forEach(::play)
      val after = machine.state
      val (headline, detail, lensLine) = GroundingUiState.copyFor(after)
      _ui.update {
        it.copy(
            state = after,
            progress = machine.progress,
            headline = headline,
            detail = detail,
            lensLine = lensLine,
            primaryAction = GroundingUiState.actionFor(after),
        )
      }
      if (before != after) {
        if (after is GroundingState.Detecting) analyzer.resetSteadiness()
        refreshLens()
      }
    }
  }

  private fun play(effect: GroundingEffect) {
    when (effect) {
      is GroundingEffect.Speak ->
          voice.speak(effect.text) {
            if (effect.thenPromptFinished) send(GroundingEvent.PromptFinished)
          }
      GroundingEffect.Chime -> Chime.play(voice.glassesOutput())
      is GroundingEffect.Haptic -> haptics.play(effect.pattern)
      is GroundingEffect.Highlight -> Unit // the frame and label are set in onSteadyObject
      GroundingEffect.ClearHighlight ->
          _ui.update { it.copy(highlight = null, highlightLabel = null, highlightFrame = null) }
      is GroundingEffect.StartTimer -> {
        timerJob?.cancel()
        timerJob =
            scope.launch {
              delay(effect.millis)
              send(GroundingEvent.Timeout)
            }
      }
      GroundingEffect.CancelTimer -> timerJob?.cancel()
    }
  }

  private fun onGlassesFrame(frame: Bitmap) {
    val now = SystemClock.elapsedRealtime()
    if (previewVisible && now - lastPreviewAt > PREVIEW_INTERVAL_MS) {
      lastPreviewAt = now
      _ui.update { it.copy(previewFrame = frame) }
    }
    if (machine.state is GroundingState.Detecting) analyzer.submit(frame)
  }

  private fun onFrameResult(r: FrameResult) {
    _ui.update {
      it.copy(
          liveDetections = r.detections.filter { d -> GroundingLabels.isGroundingObject(d.label) },
          blurryHint = r.blurry,
          inferenceMs = if (r.blurry) it.inferenceMs else r.inferenceMs,
          previewFrame = if (previewVisible) r.frame else it.previewFrame,
      )
    }
  }

  private fun onSteadyObject(d: Detection, frame: Bitmap) {
    if (machine.state !is GroundingState.Detecting) return
    val spoken = GroundingLabels.spokenName(d.label)
    if (spoken in machine.seen) return
    _ui.update { it.copy(highlight = d, highlightLabel = spoken, highlightFrame = frame) }
    send(GroundingEvent.ObjectSeen(d.copy(label = spoken)))
  }

  /** Redraws the lens card. Rapid changes cancel the previous send so only the latest goes out. */
  private fun refreshLens() {
    lensJob?.cancel()
    lensJob =
        scope.launch {
          val snapshot = _ui.value
          val bmp =
              kotlinx.coroutines.withContext(Dispatchers.Default) {
                LensRenderer.render(snapshot, SystemClock.elapsedRealtime())
              }
          val action = snapshot.primaryAction ?: "Start"
          glasses.showOnLens {
            flexBox(direction = Direction.COLUMN, gap = 8, crossAlignment = Alignment.CENTER) {
              image(bitmap = bmp, sizePreset = ImageSize.FILL)
              buttonGroup {
                button(
                    action,
                    style = ButtonStyle.PRIMARY,
                    iconName = IconName.CHECKMARK,
                    onClick = { primary() },
                    actionRole = ActionRole.PRIMARY,
                )
                if (snapshot.isRunning) {
                  button("Stop", style = ButtonStyle.SECONDARY, iconName = IconName.X, onClick = { stop() })
                }
              }
            }
          }
        }
  }

  fun close() {
    analyzer.close()
    glasses.stop()
    voice.close()
    detector?.close()
  }

  private companion object {
    const val TAG = "BeeCalm:Engine"
    const val PREVIEW_INTERVAL_MS = 150L
  }
}
