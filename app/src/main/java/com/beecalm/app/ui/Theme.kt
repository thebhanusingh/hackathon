package com.beecalm.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Soothing dark blue-green, with Bee Quest's amber for anything you can act on. */
object BeeColors {
  val DeepSea = Color(0xFF0A1C26)
  val Lagoon = Color(0xFF0E2F2E)
  val Card = Color(0xFF13343A)
  val CardEdge = Color(0xFF1F4A50)
  val Amber = Color(0xFFFFB800)
  val Honey = Color(0xFFFFD45C)
  val DarkText = Color(0xFF2A1A00)
  val Text = Color(0xFFF2F7F5)
  val Muted = Color(0xFFA9C2BE)
}

@Composable
fun BeeCalmTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          darkColorScheme(
              primary = BeeColors.Amber,
              onPrimary = BeeColors.DarkText,
              background = BeeColors.DeepSea,
              onBackground = BeeColors.Text,
              surface = BeeColors.Card,
              onSurface = BeeColors.Text,
          ),
      content = content,
  )
}
