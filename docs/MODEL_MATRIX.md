# iTantra offline model matrix

## Live demo packs

| Layer | Language | Model | Runtime | Approx. model footprint | Status |
|---|---|---|---|---:|---|
| STT | Hindi | Vosk `vosk-model-small-hi-0.22` | Vosk Android | ~42 MB | Live pack |
| STT | English | Vosk `vosk-model-small-en-in-0.4` | Vosk Android | ~36 MB | Live pack |
| STT | Gujarati | Vosk `vosk-model-small-gu-0.42` | Vosk Android | ~100 MB | Live pack |
| STT | Telugu | Vosk `vosk-model-small-te-0.42` | Vosk Android | ~58 MB | Optional pack |
| VAD | All | Silero VAD ONNX | ONNX Runtime / sherpa-onnx | ~2 MB | Live pack |
| TTS | Hindi | Piper `hi_IN-priyamvada-medium` | sherpa-onnx VITS | ~64 MB | Live pack |
| TTS | Marathi | Piper `mr_IN-google-medium` | sherpa-onnx VITS | ~77 MB | Live pack |
| TTS | English | Piper `en_US-amy-medium` | sherpa-onnx VITS | ~63 MB | Live pack |
| TTS | Bengali | Piper `bn_BD-google-medium` | sherpa-onnx VITS | ~77 MB | Optional pack |
| TTS | Malayalam | Piper `ml_IN-arjun-medium` | sherpa-onnx VITS | ~63 MB | Optional pack |
| TTS | Telugu | Piper `te_IN-maya-medium` | sherpa-onnx VITS | ~63 MB | Optional pack |

The default build deliberately bundles only the live-demo packs so a low/mid-range phone is not forced to load every language simultaneously.

## Remaining languages

Kannada, Tamil and Odia are kept as language-pack slots in `SupportedLanguage`. They are not falsely marked as production-ready until a compatible open-source on-device model has been integrated and benchmarked for Android.

## Why Vosk for the demo STT?

Vosk publishes small mobile-oriented models and explicitly supports offline Android deployment. Its current model catalogue lists small Hindi, Gujarati and Telugu models as well as Indian English. Model sizes and published WER figures are recorded in the source catalogue; iTantra's own benchmark numbers must be measured on the target phones rather than copied from another dataset.

## Why sherpa-onnx for TTS?

The Android TTS engine receives Piper/VITS model packs and performs phonemization plus ONNX inference locally. This avoids the previous placeholder character-level VITS implementation and keeps the runtime offline.

## Runtime rule

No network operation is permitted from STT, VAD, TTS, sentence formation or transport code. Network access during development is limited to provisioning the model artifacts before the APK is built.
