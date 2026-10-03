package com.beecalm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.beecalm.app.BeeCalmApp
import com.beecalm.app.R
import com.beecalm.app.ui.MainActivity

/**
 * Keeps the glasses session, the camera stream and the detector running while the phone is locked
 * or in a pocket, which is exactly where it is during a panic attack. The heavy lifting itself
 * lives in [com.beecalm.app.grounding.GroundingEngine]; this service just holds it in the
 * foreground with a quiet notification and a partial wake lock.
 */
class GroundingService : LifecycleService() {

  private var wakeLock: PowerManager.WakeLock? = null
  private val engine
    get() = (application as BeeCalmApp).engine

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    // Always enter the foreground first so the startForegroundService() promise is kept.
    try {
      startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } catch (t: Throwable) {
      Log.e(TAG, "Could not enter the foreground", t)
      stopSelf()
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_STOP -> {
        engine.stop()
        engine.disconnectGlasses()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        acquireWakeLock()
        engine.connectGlasses()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createChannel() {
    val channel =
        NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun notification(): Notification {
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val stop =
        PendingIntent.getService(
            this,
            1,
            Intent(this, GroundingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_body))
        .setSmallIcon(R.drawable.ic_bee)
        .setOngoing(true)
        .setContentIntent(open)
        .addAction(0, getString(R.string.stop), stop)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    wakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeeCalm::Grounding")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  companion object {
    private const val TAG = "BeeCalm:Service"
    private const val CHANNEL_ID = "grounding"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_STOP = "com.beecalm.app.STOP"
    // Safety net against a leaked lock; a grounding round takes a few minutes.
    private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

    fun start(context: Context) {
      context.startForegroundService(Intent(context, GroundingService::class.java))
    }

    fun stop(context: Context) {
      context.startForegroundService(Intent(context, GroundingService::class.java).setAction(ACTION_STOP))
    }
  }
}
