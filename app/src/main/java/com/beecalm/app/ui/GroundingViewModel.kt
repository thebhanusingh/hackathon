package com.beecalm.app.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.beecalm.app.BeeCalmApp
import com.beecalm.app.grounding.GroundingUiState
import com.beecalm.app.service.GroundingService
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin layer between the screen and the engine: the screen never touches the SDK or services. */
class GroundingViewModel(application: Application) : AndroidViewModel(application) {
  private val engine = (application as BeeCalmApp).engine

  val ui: StateFlow<GroundingUiState> = engine.ui

  private val _sdkReady = MutableStateFlow(false)
  val sdkReady: StateFlow<Boolean> = _sdkReady.asStateFlow()

  /** Registration only exists once the SDK has been initialized. */
  fun registration(): StateFlow<RegistrationState>? = if (_sdkReady.value) Wearables.registrationState else null

  fun onSdkInitialized() {
    _sdkReady.value = true
  }

  fun connectGlasses(activity: Activity) = Wearables.startRegistration(activity)

  /** Starts the background service (glasses + detector) and the first prompt. */
  fun start() {
    GroundingService.start(getApplication())
    engine.start()
  }

  fun primary() {
    if (!ui.value.isRunning) GroundingService.start(getApplication())
    engine.primary()
  }

  fun skip() = engine.skip()

  fun stop() {
    engine.stop()
    GroundingService.stop(getApplication())
  }

  fun setPreviewVisible(visible: Boolean) {
    engine.previewVisible = visible
  }
}
