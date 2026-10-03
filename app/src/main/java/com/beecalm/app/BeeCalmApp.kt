package com.beecalm.app

import android.app.Application
import com.beecalm.app.grounding.GroundingEngine

class BeeCalmApp : Application() {
  /** One engine for the whole app, shared by the screen and the background service. */
  val engine: GroundingEngine by lazy { GroundingEngine(this) }
}
