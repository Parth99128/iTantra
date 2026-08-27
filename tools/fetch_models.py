#!/usr/bin/env python3
"""Fetch real open-source model packs for the offline Android build."""
from __future__ import annotations

import argparse
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
    with urllib.request.urlopen(req, timeout=180) as response:
        return response.read()


def extract_vosk(data: bytes, destination: Path, prefix: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            parts = Path(name).parts
            if not parts or parts[0] != prefix:
                continue
            relative = Path(*parts[1:])
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def extract_tgz(data: bytes, destination: Path) -> None:
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        members = tar.getmembers()
        root = Path(members[0].name).parts[0]
        for member in members:
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


def fetch_vad() -> None:
    target = ASSETS / "vad" / "silero_vad.onnx"
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(download(f"{SHERPA_ASR_BASE}/silero_vad.onnx"))


def fetch_stt(languages: list[str]) -> None:
    for lang in languages:
        archive = STT_PACKS[lang]
        prefix = archive[:-4]
        # Keep this path aligned with VoskSttEngine: models/vosk/<bcp47>.
        destination = ASSETS / "vosk" / lang
        if (destination / "am").exists() and (destination / "conf").exists():
            continue
        destination.mkdir(parents=True, exist_ok=True)
        extract_vosk(download(f"{VOSK_BASE}/{archive}"), destination, prefix)
        extracted = destination / prefix
        if extracted.exists():
            for item in extracted.iterdir():
                shutil.move(str(item), destination / item.name)
            extracted.rmdir()


def fetch_tts(languages: list[str]) -> None:
    for lang in languages:
        archive = TTS_PACKS[lang]
        destination = ASSETS / "tts" / lang
        if (destination / "model.onnx").exists() and (destination / "tokens.txt").exists():
            continue
        destination.mkdir(parents=True, exist_ok=True)
        extract_tgz(download(f"{SHERPA_TTS_BASE}/{archive}"), destination)
        roots = [p for p in destination.iterdir() if p.is_dir()]
        if len(roots) != 1:
            raise RuntimeError(f"Unexpected archive layout for {archive}: {roots}")
        root = roots[0]
        models = list(root.glob("*.onnx"))
        if not models or not (root / "tokens.txt").exists():
            raise RuntimeError(f"Incomplete TTS pack: {archive}")
        shutil.copy2(models[0], destination / "model.onnx")
        shutil.copy2(root / "tokens.txt", destination / "tokens.txt")
        if (root / "espeak-ng-data").exists():
            shutil.copytree(root / "espeak-ng-data", destination / "espeak-ng-data", dirs_exist_ok=True)
        shutil.rmtree(root)
        (destination / "MODEL_SOURCE.json").write_text(
            json.dumps({"source": archive, "runtime": "sherpa-onnx VITS", "offline_runtime": True}, indent=2),
            encoding="utf-8",
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stt", default="hi,en,gu")
    parser.add_argument("--tts", default="hi,mr,en")
    parser.add_argument("--skip-vad", action="store_true")
    args = parser.parse_args()
    stt = [x.strip() for x in args.stt.split(",") if x.strip()]
    tts = [x.strip() for x in args.tts.split(",") if x.strip()]
    if set(stt) - STT_PACKS.keys() or set(tts) - TTS_PACKS.keys():
        raise SystemExit("Unsupported model pack requested")
    ASSETS.mkdir(parents=True, exist_ok=True)
    if not args.skip_vad:
        fetch_vad()
    fetch_stt(stt)
    fetch_tts(tts)
    print("Real offline model packs are ready under app/src/main/assets/models/")


if __name__ == "__main__":
    main()
