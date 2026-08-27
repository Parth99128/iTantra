# iTantra — SIH26173

Offline, on-device multilingual STT/TTS walkie-talkie for low-bitrate alert links.

## What's actually in this repo

A complete Android Studio project (Kotlin + Jetpack Compose) implementing the full
pipeline architecture:

```
Mic → AudioRecorder (16kHz PCM) → SileroVad (pause detection)
    → VoskSttEngine (speech→text) → BluetoothTransceiver (send text)

BluetoothTransceiver (receive text) → OnnxVitsTtsEngine (text→speech)
    → AudioPlayer (voice-note OR non-interruptible max-volume alert)
```

**What this repo does NOT include** (and cannot, without an Android build
environment and large binary downloads): the actual `.tflite`/`.onnx` model files,
a compiled/tested APK, or on-device latency numbers. You need to complete the
"Model Setup" steps below before this builds and runs. Budget real time for this —
it is the highest-risk part of the whole project.

---

## 1. Open the project

Open the `iTantra/` folder in Android Studio (Iguana or later). Let Gradle sync —
it will pull dependencies from Google's Maven, Maven Central, alphacephei's Maven
(Vosk), and JitPack as configured in `settings.gradle.kts`.

## 2. Model setup (do this first, before writing any more code)

### STT — Vosk models
1. Go to https://alphacephei.com/vosk/models and download the **small** model for
   each language you're demoing (Hindi and English have the most mature small
   models; check current availability for the others before committing to all 10).
2. Unzip each into: `app/src/main/assets/models/vosk/<lang_code>/`
   e.g. `app/src/main/assets/models/vosk/hi/` should directly contain the model's
   `am/`, `conf/`, `graph/` folders etc.
3. Verify the exact Vosk-Android API (`Model`, `Recognizer`, `StorageService`)
   against the current version at https://github.com/alphacep/vosk-android-demo —
   library APIs shift between versions, and `VoskSttEngine.kt` is written against
   the API shape as of writing; double check before your first build.

### VAD — Silero VAD
1. Download `silero_vad.onnx` from the official Silero VAD repository.
2. Place at `app/src/main/assets/models/vad/silero_vad.onnx`.

### TTS — this is your hardest engineering decision, read carefully
`OnnxVitsTtsEngine.kt` is deliberately built for **character-level, native-script
VITS models** (the AI4Bharat Indic-TTS style) rather than Piper's default
espeak-ng-phonemized pipeline, because:
- Piper needs a compiled espeak-ng native library on Android (real added
  complexity) and its phoneme coverage for several target Indian languages is
  inconsistent.
- Character-level models trained directly on native script (Devanagari, Bengali
  script, etc.) skip the phonemizer step entirely.

Steps:
1. Source an AI4Bharat Indic-TTS (or a Coqui VITS) checkpoint for your demo
   languages.
2. Export it to a single ONNX graph: `text_ids -> waveform`.
3. Quantize to int8 (this is what your efficiency score is measured on).
4. Place at `app/src/main/assets/models/tts/<lang_code>/model.onnx`.
5. Build the matching character→ID vocabulary as JSON at
   `app/src/main/assets/models/tts/<lang_code>/vocab.json`.

**If this proves too time-consuming during the hackathon:** fall back to Piper for
just Hindi + English (where espeak-ng phoneme support is solid) as your guaranteed
working demo path, and present the character-level VITS approach as your
architecture slide for full 10-language scale. Don't let TTS integration eat your
entire hackathon — decide on a fallback deadline (e.g. "if not working by hour 20,
switch to Piper for 2 languages") before you start.

## 3. Build

Standard Android Studio build/run once models are in place. Test on an actual
low/mid-range device early — emulator audio behavior (mic input especially) is not
representative of real hardware, and the PS explicitly grades on low/mid-range
device performance.

## 4. Measure your metrics before demo day

Run `tools/measure_wer.py` (`pip install jiwer --break-system-packages`) against
real recordings to know your actual WER — don't guess this number when a judge
asks. Time your STT/TTS/end-to-end latency using the numbers already surfaced in
the app's UI (`lastSttLatencyMs`, `lastTtsLatencyMs`, `lastEndToEndMs` in
`UiState`). Check app size and RAM usage in Android Studio's profiler.

## 5. Demo script (two phones)

1. Phone A taps "Host," Phone B pairs via system Bluetooth settings first, then
   taps "Join" and selects Phone A from the picker.
2. Hold the push-to-talk button on Phone A, speak a sentence, release (or let VAD
   auto-detect the pause).
3. Watch the transcript appear, get sent, and Phone B speaks it back.
4. Say a sentence containing an alert keyword (e.g. "madad") — Phone B should play
   it at max volume, non-interruptibly, demonstrating the alert-path logic in
   `WalkieTalkieViewModel.handleIncomingText`.
5. Flip the mode switch to "Normal Phone" and show the walkie-talke features are
   disabled — this proves you built the full spec, not just the flashy part.
6. Switch languages live via the dropdown to show multilingual capability.

## 6. Known gaps to be upfront about with judges

- Only your chosen demo languages will have models loaded; be ready to explain the
  AI4Bharat/MMS-based path to full 10-language coverage as a roadmap item, not a
  gap you're hiding.
- WiFi Direct is not implemented (Bluetooth Classic only) — mention this as a
  next-phase transport addition using the same `BluetoothTransceiver`-shaped
  interface, if asked.
- Alert detection here uses simple keyword matching for demo purposes — a
  production version would need a more robust distress-classification approach.
