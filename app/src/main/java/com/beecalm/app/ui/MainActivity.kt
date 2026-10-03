package com.beecalm.app.ui

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: GroundingViewModel by viewModels()

  private val androidPermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.BLUETOOTH_CONNECT] != false) initWearables()
      }

  // Asks Meta AI for permission to use the glasses camera.
  private val glassesCameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val granted = result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted
        if (granted) viewModel.start()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      BeeCalmTheme {
        GroundingScreen(
            viewModel = viewModel,
            onConnectGlasses = { viewModel.connectGlasses(this) },
            onStart = ::startWithGlassesCamera,
        )
      }
    }
  }

  override fun onStart() {
    super.onStart()
    androidPermissions.launch(
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        ))
  }

  override fun onResume() {
    super.onResume()
    viewModel.setPreviewVisible(true)
  }

  override fun onPause() {
    viewModel.setPreviewVisible(false)
    super.onPause()
  }

  private fun initWearables() {
    if (viewModel.sdkReady.value) return
    Wearables.initialize(this)
        .onSuccess { viewModel.onSdkInitialized() }
        .onFailure { error, _ -> Log.e(TAG, "Couldn't start the glasses toolkit: ${error.description}") }
  }

  /**
   * Starting needs the glasses camera permission. If it's already granted (or the glasses aren't
   * set up yet, in which case the exercise still runs with voice and the big buttons), go now.
   */
  private fun startWithGlassesCamera() {
    if (!viewModel.sdkReady.value) {
      viewModel.start()
      return
    }
    lifecycleScope.launch {
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            if (status == PermissionStatus.Granted) viewModel.start()
            else glassesCameraPermission.launch(Permission.CAMERA)
          }
          .onFailure { _, _ -> viewModel.start() } // no glasses connected: run voice + buttons only
    }
  }

  private companion object {
    const val TAG = "BeeCalm:Main"
  }
}
