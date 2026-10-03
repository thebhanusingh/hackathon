# Bee Calm

A hands-free 5-4-3-2-1 grounding app for Meta Ray-Ban Display glasses and an Android phone (built
and tuned for a Galaxy S25). The glasses camera finds real objects around you, the bee lands on
them, and a calm voice in the glasses' speakers walks you through the exercise.

The look comes from our Bee Quest web app: the same bee, the amber `#FFB800` moving dashed box,
the honeycomb and the "complete!" badge, on a soothing dark blue-green phone screen.

## How a round goes

1. **See (5):** "Find five things you can see." The camera looks for one steady object near the
   middle of your view. When it's sure, you hear a soft chime, feel two gentle taps, and the lens
   shows that object outlined in amber with the bee on it. If nothing is found in 10 seconds, it
   asks you to hold still; after another 10 seconds it counts it anyway so you never get stuck.
2. **Feel (4), Hear (3), Smell (2), Taste (1):** pinch the button on the lens (or tap the big
   button on the phone) for each one.
3. **Complete:** a slow haptic swell and the "Grounding complete!" badge.

Skip and Stop are always one tap away, on the phone and on the lens.

## Architecture

```
glasses camera ──HEVC──▶ GlassesLink ─▶ HevcFrameDecoder ─▶ FrameAnalyzer ─▶ ObjectDetector (TFLite)
                                                             (blur check,       601 Open Images classes,
                                                              steady focus)     640x640, GPU delegate
                                                                    │
                                                                    ▼
 VoiceGuide ◀──┐                                         GroundingEngine ◀── pinch / big buttons
 Chime      ◀──┼── effects ── GroundingStateMachine ◀──── events
 Haptics    ◀──┘              (pure Kotlin, unit tested)        │
                                                                ▼
                                    GroundingUiState ─▶ GroundingViewModel ─▶ Compose screen
                                                     └▶ LensRenderer ─▶ glasses display
```

- `grounding/GroundingStateMachine.kt` holds the whole exercise: IDLE, PROMPT_SEE, DETECTING,
  the sense phases (TOUCH, HEAR, SMELL, TASTE) and COMPLETE, with the 10-second fallback.
- `service/GroundingService.kt` is a foreground service so the glasses session and detector keep
  running with the phone locked. The camera stream is requested compressed because the SDK keeps a
  compressed stream flowing in the background.
- `detection/ObjectDetector.kt` runs YOLOv8s trained on Open Images V7 (601 classes) fully on the
  phone. People and body parts are never used as anchors (`GroundingLabels.kt`).
- `feedback/VoiceGuide.kt` routes speech to the glasses when they're connected as a Bluetooth audio
  device, and falls back to the phone speaker otherwise.

## Running it

1. On the phone: install the Meta AI app, pair the glasses, and turn on **Developer Mode** for the
   glasses in Meta AI.
2. Download the APK from the latest **Build APK** run in the Actions tab (artifact
   `bee-calm-debug-apk`), or build it yourself with `./gradlew assembleDebug` (JDK 17 or 21).
3. Open Bee Calm, allow Bluetooth and notifications, tap **Connect my glasses**, then **Start**.

Without glasses connected, the exercise still runs with voice and the big buttons.

## Model

`app/src/main/assets/bee_detector.tflite` is Ultralytics YOLOv8s-oiv7 exported to TFLite (float16
weights, 640x640 input). `bee_labels.txt` lists its 601 classes in output order. The model is
AGPL-3.0 licensed by Ultralytics.
