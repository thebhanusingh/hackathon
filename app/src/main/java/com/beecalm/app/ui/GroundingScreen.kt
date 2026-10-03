package com.beecalm.app.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beecalm.app.glasses.GlassesStatus
import com.beecalm.app.grounding.GroundingUiState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState

/**
 * One screen, very few words, huge targets. Everything is also spoken and shown on the lens, so
 * this screen is for manual overrides and for whoever is helping.
 */
@Composable
fun GroundingScreen(
    viewModel: GroundingViewModel,
    onConnectGlasses: () -> Unit,
    onStart: () -> Unit,
) {
  val ui by viewModel.ui.collectAsState()
  val sdkReady by viewModel.sdkReady.collectAsState()
  val registration =
      if (sdkReady) Wearables.registrationState.collectAsState().value else RegistrationState.UNAVAILABLE

  Box(
      Modifier.fillMaxSize()
          .background(Brush.verticalGradient(listOf(BeeColors.DeepSea, BeeColors.Lagoon)))
          .safeDrawingPadding()
          .padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          StatusPill(ui, registration)
          Text(
              ui.headline,
              color = if (ui.isComplete) BeeColors.Amber else BeeColors.Text,
              fontSize = 38.sp,
              fontWeight = FontWeight.Bold,
              lineHeight = 42.sp,
          )
          Text(ui.detail, color = BeeColors.Muted, fontSize = 22.sp, lineHeight = 30.sp)
          CameraCard(ui, Modifier.fillMaxWidth().weight(1f))
          ProgressHoneycomb(ui)
          if (sdkReady && registration != RegistrationState.REGISTERED && !ui.isRunning) {
            BigOutlined("Connect my glasses", onConnectGlasses, Modifier.fillMaxWidth())
          }
          ui.primaryAction?.let { label ->
            BigButton(
                label,
                onClick = { if (ui.isRunning) viewModel.primary() else onStart() },
                Modifier.fillMaxWidth(),
            )
          }
          if (ui.isRunning) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              BigOutlined("Skip", viewModel::skip, Modifier.weight(1f))
              BigOutlined("Stop", viewModel::stop, Modifier.weight(1f))
            }
          }
        }
      }
}

@Composable
private fun StatusPill(ui: GroundingUiState, registration: RegistrationState) {
  val text =
      when {
        registration != RegistrationState.REGISTERED -> "Glasses not connected yet"
        ui.glassesStatus == GlassesStatus.STREAMING ->
            if (ui.inferenceMs > 0) "Glasses connected · ${ui.inferenceMs} ms" else "Glasses connected"
        else -> ui.glassesStatus.label
      }
  val live = ui.glassesStatus == GlassesStatus.STREAMING
  Row(
      Modifier.clip(RoundedCornerShape(50))
          .background(BeeColors.Card)
          .border(1.dp, if (live) BeeColors.Amber else BeeColors.CardEdge, RoundedCornerShape(50))
          .padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text, color = if (live) BeeColors.Honey else BeeColors.Muted, fontSize = 15.sp)
  }
}

/** Live view from the glasses with the amber dashed box and the bee, drawn the Bee Quest way. */
@Composable
private fun CameraCard(ui: GroundingUiState, modifier: Modifier) {
  val time = remember { mutableLongStateOf(0L) }
  LaunchedEffect(Unit) { while (true) withFrameMillis { time.longValue = it } }

  Box(
      modifier
          .clip(RoundedCornerShape(24.dp))
          .background(BeeColors.Card)
          .border(2.dp, BeeColors.CardEdge, RoundedCornerShape(24.dp))
          .semantics { contentDescription = ui.highlightLabel?.let { "Found $it" } ?: "Camera view" }) {
        Canvas(Modifier.fillMaxSize()) {
          val t = time.longValue
          drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            val frame = ui.highlightFrame ?: ui.previewFrame
            val w = size.width
            val h = size.height
            var dst = RectF(0f, 0f, w, h)
            if (frame != null) {
              // Fit the frame inside the card, centered, keeping its shape.
              val s = minOf(w / frame.width, h / frame.height)
              val fw = frame.width * s
              val fh = frame.height * s
              dst = RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
              c.drawBitmap(frame, null, dst, null)
            }
            fun toScreen(l: Float, tp: Float, r: Float, b: Float) =
                RectF(dst.left + l * dst.width(), dst.top + tp * dst.height(), dst.left + r * dst.width(), dst.top + b * dst.height())

            val hl = ui.highlight
            if (hl != null && ui.highlightFrame != null) {
              val box = toScreen(hl.left, hl.top, hl.right, hl.bottom)
              BeeArt.drawObjectBox(c, box, ui.highlightLabel ?: hl.label, t, 40f)
              BeeArt.drawBee(c, box.centerX() + kotlin.math.cos(t / 700.0).toFloat() * minOf(40f, box.width() / 3), (box.top - 60f).coerceAtLeast(70f), 1.3f, t, BeeArt.Mood.HAPPY)
            } else if (ui.isDetecting && frame != null) {
              // While looking, show what the camera can see so the user knows it's working.
              for (d in ui.liveDetections.take(4)) {
                val box = toScreen(d.left, d.top, d.right, d.bottom)
                val p = android.graphics.Paint().apply {
                  style = android.graphics.Paint.Style.STROKE
                  strokeWidth = 3f
                  color = 0x8CFFFFFF.toInt()
                }
                c.drawRect(box, p)
              }
              val a = t / 500.0
              BeeArt.drawBee(c, w / 2 + kotlin.math.cos(a).toFloat() * w * 0.28f, h * 0.3f + kotlin.math.sin(a * 1.3).toFloat() * 40f, 1.3f, t, if (ui.blurryHint) BeeArt.Mood.UNSURE else BeeArt.Mood.CALM)
            } else if (ui.isComplete) {
              BeeArt.drawCompleteBadge(c, w / 2, h / 2 + 30f, minOf(w, h) * 0.3f)
              BeeArt.drawBee(c, w / 2, h / 2 - minOf(w, h) * 0.3f - 20f - kotlin.math.abs(kotlin.math.sin(t / 250.0)).toFloat() * 30f, 1.4f, t, BeeArt.Mood.HAPPY)
            } else {
              BeeArt.drawBee(c, w / 2, h / 2 + kotlin.math.sin(t / 500.0).toFloat() * 14f, 2.2f, t)
            }
          }
        }
      }
}

/** 5 + 4 + 3 + 2 + 1 honeycomb cells, filled in amber as each item is done. */
@Composable
private fun ProgressHoneycomb(ui: GroundingUiState) {
  Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "${ui.progress} of ${ui.total} done" }) {
    drawIntoCanvas {
      val r = minOf(size.height / 11f, size.width / 20f)
      BeeArt.drawHoneycomb(it.nativeCanvas, size.width / 2, size.height / 2, r, ui.progress, ui.total, listOf(5, 4, 3, 2, 1))
    }
  }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier) {
  Button(
      onClick = onClick,
      modifier = modifier.height(96.dp),
      shape = RoundedCornerShape(28.dp),
      colors = ButtonDefaults.buttonColors(containerColor = BeeColors.Amber, contentColor = BeeColors.DarkText),
  ) {
    Text(label, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
  }
}

@Composable
private fun BigOutlined(label: String, onClick: () -> Unit, modifier: Modifier) {
  OutlinedButton(
      onClick = onClick,
      modifier = modifier.height(76.dp),
      shape = RoundedCornerShape(24.dp),
      border = androidx.compose.foundation.BorderStroke(2.dp, BeeColors.Honey),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = BeeColors.Honey),
  ) {
    Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
  }
}
