package com.beecalm.app.glasses

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the glasses' compressed (HEVC) camera stream into Bitmaps on the phone.
 *
 * The stream is requested compressed on purpose: the SDK pauses a raw stream when the app leaves
 * the foreground, but keeps a compressed one flowing, and this app is meant to keep working with
 * the phone locked in a pocket.
 *
 * Every frame has to be decoded (later frames depend on earlier ones), but turning a decoded frame
 * into a Bitmap is the costly part, so that only happens when [wantFrame] says someone needs one,
 * and at most once per [minFrameIntervalMs].
 */
class HevcFrameDecoder(
    private val wantFrame: () -> Boolean,
    private val onFrame: (Bitmap) -> Unit,
    private val minFrameIntervalMs: Long = 120,
) {
  private class AccessUnit(val data: ByteArray, val ptsUs: Long, val isConfig: Boolean)

  private val queue = LinkedBlockingQueue<AccessUnit>(QUEUE_CAPACITY)
  @Volatile private var running = false
  private var thread: Thread? = null
  private var codec: MediaCodec? = null
  private var width = 0
  private var height = 0
  private var lastFrameAt = 0L
  private var sawConfig = false

  fun offer(data: ByteArray, ptsUs: Long, isConfig: Boolean, frameWidth: Int, frameHeight: Int) {
    if (isConfig) sawConfig = true
    if (!sawConfig) return // can't decode anything until the parameter sets arrive
    if (!running) start(frameWidth, frameHeight)
    if (!queue.offer(AccessUnit(data, ptsUs, isConfig))) {
      // Falling behind: drop the backlog and wait for the next keyframe to recover cleanly.
      Log.w(TAG, "Decoder backlog full, resetting")
      queue.clear()
    }
  }

  @Synchronized
  private fun start(w: Int, h: Int) {
    if (running) return
    width = w
    height = h
    running = true
    thread = Thread(::loop, "BeeCalm-HevcDecoder").apply { start() }
  }

  @Synchronized
  fun stop() {
    running = false
    thread?.interrupt()
    thread = null
    queue.clear()
    sawConfig = false
  }

  private fun loop() {
    val c =
        try {
          MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            it.configure(format, null, null, 0)
            it.start()
          }
        } catch (t: Throwable) {
          Log.e(TAG, "Could not start HEVC decoder", t)
          running = false
          return
        }
    codec = c
    val info = MediaCodec.BufferInfo()
    try {
      while (running) {
        val unit = queue.poll(30, TimeUnit.MILLISECONDS)
        if (unit != null) {
          val idx = c.dequeueInputBuffer(20_000)
          if (idx >= 0) {
            val buf = c.getInputBuffer(idx)
            if (buf != null && buf.capacity() >= unit.data.size) {
              buf.clear()
              buf.put(unit.data)
              val flags = if (unit.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
              c.queueInputBuffer(idx, 0, unit.data.size, unit.ptsUs, flags)
            } else {
              c.queueInputBuffer(idx, 0, 0, unit.ptsUs, 0)
            }
          }
        }
        drain(c, info)
      }
    } catch (_: InterruptedException) {
      // stopping
    } catch (t: Throwable) {
      Log.e(TAG, "Decoder loop failed", t)
    } finally {
      try {
        c.stop()
      } catch (_: Throwable) {}
      c.release()
      codec = null
      running = false
    }
  }

  private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
    while (true) {
      val idx = c.dequeueOutputBuffer(info, 0)
      if (idx < 0) return
      val now = SystemClock.elapsedRealtime()
      if (info.size > 0 && now - lastFrameAt >= minFrameIntervalMs && wantFrame()) {
        lastFrameAt = now
        val image = c.getOutputImage(idx)
        if (image != null) {
          val bitmap =
              try {
                yuvToBitmap(image)
              } finally {
                image.close()
              }
          c.releaseOutputBuffer(idx, false)
          onFrame(bitmap)
          continue
        }
      }
      c.releaseOutputBuffer(idx, false)
    }
  }

  companion object {
    private const val TAG = "BeeCalm:Decoder"
    private const val QUEUE_CAPACITY = 60

    /** YUV_420_888 -> ARGB_8888, honoring row/pixel strides and the crop rectangle. */
    fun yuvToBitmap(image: Image): Bitmap {
      val crop = image.cropRect
      val w = crop.width()
      val h = crop.height()
      val y = image.planes[0]
      val u = image.planes[1]
      val v = image.planes[2]
      val yBuf = y.buffer
      val uBuf = u.buffer
      val vBuf = v.buffer
      val out = IntArray(w * h)
      for (row in 0 until h) {
        val yRow = (row + crop.top) * y.rowStride
        val uvRow = ((row + crop.top) / 2) * u.rowStride
        val vRow = ((row + crop.top) / 2) * v.rowStride
        for (col in 0 until w) {
          val cx = col + crop.left
          val yy = (yBuf.get(yRow + cx * y.pixelStride).toInt() and 0xFF) - 16
          val uu = (uBuf.get(uvRow + (cx / 2) * u.pixelStride).toInt() and 0xFF) - 128
          val vv = (vBuf.get(vRow + (cx / 2) * v.pixelStride).toInt() and 0xFF) - 128
          val y1 = 1192 * maxOf(yy, 0)
          val r = ((y1 + 1634 * vv) shr 10).coerceIn(0, 255)
          val g = ((y1 - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
          val b = ((y1 + 2066 * uu) shr 10).coerceIn(0, 255)
          out[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
      }
      return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
  }
}
