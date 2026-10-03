package com.beecalm.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * On-device YOLOv8 detector trained on Open Images V7 (600 everyday object classes), run with
 * TensorFlow Lite. Nothing leaves the phone and no network is needed.
 *
 * Input: one 640x640 RGB frame, letterboxed so the picture isn't stretched. Output: boxes in
 * fractions of the original frame, after confidence filtering and non-max suppression.
 */
class ObjectDetector(context: Context) : AutoCloseable {

  val labels: List<String> =
      context.assets.open(LABELS_FILE).bufferedReader().readLines().map { it.trim() }.filter {
        it.isNotEmpty()
      }

  private var gpuDelegate: GpuDelegate? = null
  private val interpreter: Interpreter
  val usingGpu: Boolean

  private val inputSize: Int
  private val input: ByteBuffer
  private val output: Array<Array<FloatArray>>
  private val outputChannelsFirst: Boolean
  private val numBoxes: Int
  private val numClasses: Int

  private val letterbox: Bitmap
  private val letterboxCanvas: Canvas
  private val pixels: IntArray
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

  init {
    val model = loadModel(context)
    val compat = CompatibilityList()
    var gpu = false
    val interp =
        try {
          if (compat.isDelegateSupportedOnThisDevice) {
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            gpuDelegate = delegate
            gpu = true
            Interpreter(model, Interpreter.Options().addDelegate(delegate))
          } else {
            cpuInterpreter(model)
          }
        } catch (t: Throwable) {
          Log.w(TAG, "GPU delegate failed, using CPU", t)
          gpuDelegate?.close()
          gpuDelegate = null
          gpu = false
          cpuInterpreter(model)
        }
    interpreter = interp
    usingGpu = gpu

    val inShape = interpreter.getInputTensor(0).shape() // [1, 640, 640, 3]
    inputSize = inShape[1]
    input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())

    // Ultralytics exports [1, 4 + classes, boxes]; some converters give [1, boxes, 4 + classes].
    val outShape = interpreter.getOutputTensor(0).shape()
    outputChannelsFirst = outShape[1] < outShape[2]
    numBoxes = if (outputChannelsFirst) outShape[2] else outShape[1]
    val channels = if (outputChannelsFirst) outShape[1] else outShape[2]
    numClasses = channels - 4
    output =
        if (outputChannelsFirst) Array(1) { Array(channels) { FloatArray(numBoxes) } }
        else Array(1) { Array(numBoxes) { FloatArray(channels) } }

    letterbox = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    letterboxCanvas = Canvas(letterbox)
    pixels = IntArray(inputSize * inputSize)
    Log.i(TAG, "Loaded detector: $numClasses classes, $numBoxes anchors, gpu=$usingGpu")
  }

  private fun cpuInterpreter(model: MappedByteBuffer) =
      Interpreter(model, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))

  /** Runs one frame. Thread-confined: call from a single background thread. */
  fun detect(frame: Bitmap, minScore: Float = MIN_SCORE): Pair<List<Detection>, Long> {
    val scale = inputSize.toFloat() / maxOf(frame.width, frame.height)
    val w = (frame.width * scale).toInt()
    val h = (frame.height * scale).toInt()
    val padX = (inputSize - w) / 2
    val padY = (inputSize - h) / 2

    letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
    letterboxCanvas.drawBitmap(frame, null, Rect(padX, padY, padX + w, padY + h), paint)
    letterbox.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    input.rewind()
    for (p in pixels) {
      input.putFloat(((p shr 16) and 0xFF) / 255f)
      input.putFloat(((p shr 8) and 0xFF) / 255f)
      input.putFloat((p and 0xFF) / 255f)
    }
    input.rewind()

    val start = SystemClock.elapsedRealtime()
    interpreter.run(input, output)
    val elapsed = SystemClock.elapsedRealtime() - start

    val candidates = ArrayList<Detection>()
    val out = output[0]
    var maxCoord = 0f
    for (i in 0 until numBoxes) maxCoord = maxOf(maxCoord, value(out, 0, i), value(out, 1, i))
    // Ultralytics TFLite boxes come out as fractions of the input; other exports use pixels.
    val coordScale = if (maxCoord > 2f) inputSize.toFloat() else 1f

    for (i in 0 until numBoxes) {
      var best = -1
      var bestScore = minScore
      for (c in 0 until numClasses) {
        val s = value(out, 4 + c, i)
        if (s > bestScore) {
          bestScore = s
          best = c
        }
      }
      if (best < 0) continue
      val cx = value(out, 0, i) / coordScale * inputSize
      val cy = value(out, 1, i) / coordScale * inputSize
      val bw = value(out, 2, i) / coordScale * inputSize
      val bh = value(out, 3, i) / coordScale * inputSize
      // Undo the letterbox: input pixels -> original frame fractions.
      val l = ((cx - bw / 2 - padX) / w).coerceIn(0f, 1f)
      val t = ((cy - bh / 2 - padY) / h).coerceIn(0f, 1f)
      val r = ((cx + bw / 2 - padX) / w).coerceIn(0f, 1f)
      val b = ((cy + bh / 2 - padY) / h).coerceIn(0f, 1f)
      if (r - l < 0.01f || b - t < 0.01f) continue
      candidates += Detection(labels.getOrElse(best) { "object" }, bestScore, l, t, r, b)
    }
    return nms(candidates) to elapsed
  }

  private fun value(out: Array<FloatArray>, channel: Int, box: Int): Float =
      if (outputChannelsFirst) out[channel][box] else out[box][channel]

  override fun close() {
    interpreter.close()
    gpuDelegate?.close()
  }

  companion object {
    private const val TAG = "BeeCalm:Detector"
    const val MODEL_FILE = "bee_detector.tflite"
    const val LABELS_FILE = "bee_labels.txt"
    const val MIN_SCORE = 0.35f
    private const val IOU_THRESHOLD = 0.5f
    private const val MAX_RESULTS = 20

    private fun loadModel(context: Context): MappedByteBuffer {
      context.assets.openFd(MODEL_FILE).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
          return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
      }
    }

    /** Class-aware non-max suppression, highest score first. */
    fun nms(candidates: List<Detection>): List<Detection> {
      val sorted = candidates.sortedByDescending { it.score }
      val kept = ArrayList<Detection>()
      for (d in sorted) {
        if (kept.size >= MAX_RESULTS) break
        if (kept.none { it.label == d.label && iou(it, d) > IOU_THRESHOLD }) kept += d
      }
      return kept
    }

    fun iou(a: Detection, b: Detection): Float {
      val l = maxOf(a.left, b.left)
      val t = maxOf(a.top, b.top)
      val r = minOf(a.right, b.right)
      val btm = minOf(a.bottom, b.bottom)
      val inter = (r - l).coerceAtLeast(0f) * (btm - t).coerceAtLeast(0f)
      val union = a.area + b.area - inter
      return if (union <= 0f) 0f else inter / union
    }
  }
}
