# iTantra — SIH 26173

Offline, on-device multilingual STT/TTS walkie-talkie for low-bitrate links.

## Current implementation

```text
Mic → VAD → streaming STT → sentence finalization → text/phoneme payload
    → Bluetooth → receiver → offline neural TTS → speaker
```

The ML runtime is now real: Vosk is used for lightweight offline STT packs, Silero VAD is loaded as a local ONNX model, and Piper/VITS TTS packs are executed locally through sherpa-onnx. There is no cloud STT/TTS call in the runtime pipeline.

The default hackathon build intentionally bundles a small live-demo set rather than pretending that ten large models can all be loaded efficiently on an ₹8,000 phone at once.

### Default live demo

- STT: Hindi + Indian English + Gujarati
- TTS: Hindi + Marathi + English
- VAD: Silero
- Transport: Bluetooth Classic

Optional packs are provisioned by the same script. Kannada, Tamil and Odia remain explicit plug-in slots until a compatible open-source mobile model is integrated and benchmarked.

See [`docs/MODEL_MATRIX.md`](docs/MODEL_MATRIX.md) for the exact model matrix.

## Model provisioning

Large model binaries are intentionally **not committed to Git**. The repository contains a reproducible provisioning script and CI workflow that download the open-source artifacts, verify their structure, place them into Android assets, and build an APK containing the models. Runtime operation after installation is offline.

From the repository root:

```bash
python3 tools/fetch_models.py --stt hi,en,gu --tts hi,mr,en
./gradlew :app:assembleDebug
```

The resulting assets are under:

```text
app/src/main/assets/models/
├── vad/silero_vad.onnx
├── vosk/hi/...
├── vosk/en/...
├── vosk/gu/...
├── tts/hi/model.onnx + tokens.txt + espeak-ng-data/
├── tts/mr/model.onnx + tokens.txt + espeak-ng-data/
└── tts/en/model.onnx + tokens.txt + espeak-ng-data/
```

GitHub Actions performs the same provisioning and uploads a debug APK as an artifact from the `sih-26173-offline-ml` branch.

## Offline guarantee

Model provisioning requires internet because the model archives have to be obtained before packaging. **The Android runtime does not.** STT, VAD, TTS and Bluetooth transport operate from packaged local assets. The app should be tested in airplane mode before the SIH demo.

## Evaluation work still required

The code now contains the real inference path, but performance claims must come from hardware measurements. Before demo day we need:

1. WER/CER on a fixed 20–30 sentence test set per demo language.
2. TTS intelligibility/naturalness ratings from independent listeners.
3. STT latency and TTS latency on the target low/mid-range phone.
4. End-to-end speech-to-playback latency.
5. RTF = inference time / audio duration.
6. APK/model footprint, peak RAM and CPU usage.
7. Airplane-mode test proving no runtime network dependency.

Do not copy model-card WER or speed numbers into the SIH presentation as if they were iTantra measurements.

## Build architecture

- Kotlin + Jetpack Compose
- Vosk Android for the lightweight demo STT packs
- sherpa-onnx + ONNX Runtime for local Piper/VITS TTS
- Silero VAD ONNX
- Bluetooth Classic RFCOMM for the low-bit-rate text link
- Coroutines for asynchronous inference and audio processing
- API 24 minimum target

## Demo strategy

The live demonstration should prioritize a reliable Hindi/English/Marathi speech loop. The architecture remains language-pack based so additional models can be added without changing the transport protocol.

The core innovation remains unchanged: **raw audio never crosses the low-bitrate link; only compact text data does.**
