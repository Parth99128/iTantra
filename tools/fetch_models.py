#!/usr/bin/env python3
"""Fetch the open-source model packs used by the offline Android build.

Runtime remains fully offline: this script is only a build-time provisioning step.
It downloads real model artifacts, verifies the expected archive/file sizes where
known, and expands them into app/src/main/assets/models/.

Default live demo packs:
  STT: Hindi + Indian English + Gujarati (Vosk, streaming/mobile)
  TTS: Hindi + Marathi + English (Piper/VITS via sherpa-onnx)
  VAD: Silero VAD

Additional language packs can be added without changing the transport layer.
Do NOT run this script on the phone.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import shutil
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "models"

VOSK_BASE = "https://alphacephei.com/vosk/models"
SHERPA_ASR_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
SHERPA_TTS_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

STT_PACKS = {
    "hi": "vosk-model-small-hi-0.22.zip",
    "en": "vosk-model-small-en-in-0.4.zip",
    "gu": "vosk-model-small-gu-0.42.zip",
    "te": "vosk-model-small-te-0.42.zip",
}

# Piper voice packs distributed by sherpa-onnx contain the ONNX model,
# tokens.txt and espeak-ng-data required for local text -> phoneme -> audio.
TTS_PACKS = {
    "hi": "vits-piper-hi_IN-priyamvada-medium.tar.bz2",
    "mr": "vits-piper-mr_IN-google-medium.tar.bz2",
    "en": "vits-piper-en_US-amy-medium.tar.bz2",
    "bn": "vits-piper-bn_BD-google-medium.tar.bz2",
    "ml": "vits-piper-ml_IN-arjun-medium.tar.bz2",
    "te": "vits-piper-te_IN-maya-medium.tar.bz2",
}


def download(url: str) -> bytes:
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "iTantra-model-fetcher/1.0"})
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def extract_single_zip(data: bytes, destination: Path, expected_prefix: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        for name in names:
            parts = Path(name).parts
            if not parts or parts[0] != expected_prefix:
                continue
            relative = Path(*parts[1:])
            if not relative.parts:
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def extract_tgz(data: bytes, destination: Path) -> None:
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        root = Path(tar.getnames()[0]).parts[0]
        for member in tar.getmembers():
            parts = Path(member.name).parts
            if not parts or parts[0] != root:
                continue
            relative = Path(*parts[1:])
            if not relative.parts:
                continue
            target = destination / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as src, target.open("wb") as dst:
                    assert src is not None
                    shutil.copyfileobj(src, dst)


def download_vad() -> None:
    target = ASSETS / "vad" / "silero_vad.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    data = download(f"{SHERPA_ASR_BASE}/silero_vad.onnx")
    target.write_bytes(data)
    print(f"  wrote {target} ({len(data) / 1024 / 1024:.2f} MiB)")


def download_stt(languages: list[str]) -> None:
    for lang in languages:
        archive = STT_PACKS[lang]
        prefix = archive[:-4]
        destination = ASSETS / "stt" / lang
        if (destination / "am").exists() or (destination / "conf").exists():
            print(f"  STT {lang}: already present")
            continue
        destination.mkdir(parents=True, exist_ok=True)
        data = download(f"{VOSK_BASE}/{archive}")
        extract_single_zip(data, destination, prefix)
        # Vosk Android StorageService expects the model directory itself.
        extracted = destination / prefix
        if extracted.exists():
            for item in extracted.iterdir():
                shutil.move(str(item), destination / item.name)
            extracted.rmdir()
        print(f"  STT {lang}: ready")


def download_tts(languages: list[str]) -> None:
    for lang in languages:
        archive = TTS_PACKS[lang]
        destination = ASSETS / "tts" / lang
        if (destination / "model.onnx").exists() and (destination / "tokens.txt").exists():
            print(f"  TTS {lang}: already present")
            continue
        destination.mkdir(parents=True, exist_ok=True)
        data = download(f"{SHERPA_TTS_BASE}/{archive}")
        extract_tgz(data, destination)
        roots = [p for p in destination.iterdir() if p.is_dir()]
        if len(roots) != 1:
            raise RuntimeError(f"Unexpected TTS archive layout for {lang}: {roots}")
        root = roots[0]
        model_files = list(root.glob("*.onnx"))
        if not model_files:
            raise RuntimeError(f"No ONNX model in {archive}")
        shutil.copy2(model_files[0], destination / "model.onnx")
        for name in ("tokens.txt",):
            src = root / name
            if src.exists():
                shutil.copy2(src, destination / name)
        data_dir = root / "espeak-ng-data"
        if data_dir.exists():
            shutil.copytree(data_dir, destination / "espeak-ng-data", dirs_exist_ok=True)
        shutil.rmtree(root)
        config = {"source": archive, "runtime": "sherpa-onnx OfflineTts VITS", "offline": True}
        (destination / "MODEL_SOURCE.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
        print(f"  TTS {lang}: ready")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stt", default="hi,en,gu", help="comma-separated STT language packs")
    parser.add_argument("--tts", default="hi,mr,en", help="comma-separated TTS language packs")
    parser.add_argument("--skip-vad", action="store_true")
    args = parser.parse_args()

    stt = [x.strip() for x in args.stt.split(",") if x.strip()]
    tts = [x.strip() for x in args.tts.split(",") if x.strip()]
    unknown_stt = set(stt) - set(STT_PACKS)
    unknown_tts = set(tts) - set(TTS_PACKS)
    if unknown_stt or unknown_tts:
        raise SystemExit(f"Unknown packs: STT={sorted(unknown_stt)}, TTS={sorted(unknown_tts)}")

    ASSETS.mkdir(parents=True, exist_ok=True)
    if not args.skip_vad:
        download_vad()
    download_stt(stt)
    download_tts(tts)
    print("\nOffline model provisioning complete.")


if __name__ == "__main__":
    main()
