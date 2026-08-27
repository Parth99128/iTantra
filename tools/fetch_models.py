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
PIPER_HF_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

STT_PACKS = {"hi": "vosk-model-small-hi-0.22.zip", "en": "vosk-model-small-en-in-0.4.zip", "gu": "vosk-model-small-gu-0.42.zip", "te": "vosk-model-small-te-0.42.zip"}
TTS_PACKS = {"hi": "vits-piper-hi_IN-priyamvada-medium.tar.bz2", "en": "vits-piper-en_US-amy-medium.tar.bz2", "bn": "vits-piper-bn_BD-google-medium.tar.bz2", "ml": "vits-piper-ml_IN-arjun-medium.tar.bz2", "te": "vits-piper-te_IN-maya-medium.tar.bz2"}
PIPER_TTS_PACKS = {"mr": {"onnx": "mr/mr_IN/google/medium/mr_IN-google-medium.onnx", "config": "mr/mr_IN/google/medium/mr_IN-google-medium.onnx.json"}}


def download(url: str) -> bytes:
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "iTantra-model-fetcher/1.3"})
    last_error = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                data = response.read()
                if not data:
                    raise RuntimeError("Empty HTTP response")
                print(f"  downloaded {len(data):,} bytes")
                return data
        except Exception as exc:
            last_error = exc
            print(f"  download attempt {attempt}/5 failed: {exc}")
            if attempt < 5:
                import time
                time.sleep(attempt * 2)
    raise RuntimeError(f"Download failed after 5 attempts: {url}") from last_error


def extract_vosk(data: bytes, destination: Path, prefix: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            if name.endswith("/"):
                continue
            parts = Path(name).parts
            if not parts or parts[0] != prefix:
                continue
            target = destination / Path(*parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def extract_tgz(data: bytes, destination: Path, strip_root: bool = True) -> None:
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        members = tar.getmembers()
        roots = {Path(m.name).parts[0] for m in members if Path(m.name).parts}
        if strip_root and len(roots) != 1:
            raise RuntimeError(f"Unexpected archive roots: {sorted(roots)}")
        root = next(iter(roots)) if strip_root else None
        for member in members:
            parts = Path(member.name).parts
            if not parts:
                continue
            if strip_root and parts[0] != root:
                continue
            relative = Path(*parts[1:]) if strip_root else Path(*parts)
            if not relative.parts:
                continue
            target = destination / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as src, target.open("wb") as dst:
                    if src is None:
                        raise RuntimeError(f"Unable to extract {member.name}")
                    shutil.copyfileobj(src, dst)


def ensure_espeak_data(destination: Path) -> None:
    target = destination / "espeak-ng-data"
    if target.is_dir():
        return
    # The sherpa archive contains the required directory. Do not flatten it.
    tmp = destination / ".espeak-tmp"
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)
    extract_tgz(download(f"{SHERPA_TTS_BASE}/espeak-ng-data.tar.bz2"), tmp, strip_root=False)
    candidate = tmp / "espeak-ng-data"
    if not candidate.is_dir():
        dirs = [p for p in tmp.iterdir() if p.is_dir()]
        if len(dirs) == 1:
            candidate = dirs[0]
        else:
            raise RuntimeError("Unexpected espeak-ng-data archive layout")
    shutil.move(str(candidate), str(target))
    shutil.rmtree(tmp, ignore_errors=True)


def fetch_vad() -> None:
    target = ASSETS / "vad" / "silero_vad.onnx"
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(download(f"{SHERPA_ASR_BASE}/silero_vad.onnx"))


def fetch_stt(languages: list[str]) -> None:
    for lang in languages:
        archive = STT_PACKS[lang]
        prefix = archive[:-4]
        destination = ASSETS / "vosk" / lang
        if (destination / "am").exists() and (destination / "conf").exists():
            continue
        destination.mkdir(parents=True, exist_ok=True)
        extract_vosk(download(f"{VOSK_BASE}/{archive}"), destination, prefix)


def write_source(destination: Path, source: str) -> None:
    (destination / "MODEL_SOURCE.json").write_text(json.dumps({"source": source, "runtime": "sherpa-onnx VITS", "offline_runtime": True}, indent=2), encoding="utf-8")


def fetch_sherpa_tts(lang: str, archive: str) -> None:
    destination = ASSETS / "tts" / lang
    if (destination / "model.onnx").exists() and (destination / "tokens.txt").exists():
        return
    destination.mkdir(parents=True, exist_ok=True)
    extract_tgz(download(f"{SHERPA_TTS_BASE}/{archive}"), destination)
    models = sorted(destination.glob("*.onnx"))
    tokens = destination / "tokens.txt"
    if not models or not tokens.exists():
        raise RuntimeError(f"Incomplete TTS pack: {archive}")
    if models[0].name != "model.onnx":
        models[0].rename(destination / "model.onnx")
    ensure_espeak_data(destination)
    write_source(destination, archive)


def fetch_piper_tts(lang: str, pack: dict[str, str]) -> None:
    destination = ASSETS / "tts" / lang
    if (destination / "model.onnx").exists() and (destination / "tokens.txt").exists():
        return
    destination.mkdir(parents=True, exist_ok=True)
    source_model = destination / ".source.onnx"
    source_config = destination / ".source.onnx.json"
    source_model.write_bytes(download(f"{PIPER_HF_BASE}/{pack['onnx']}"))
    source_config.write_bytes(download(f"{PIPER_HF_BASE}/{pack['config']}"))
    try:
        import onnx
    except ImportError as exc:
        raise RuntimeError("Marathi Piper conversion requires onnx==1.17.0") from exc
    config = json.loads(source_config.read_text(encoding="utf-8"))
    phoneme_id_map = config.get("phoneme_id_map")
    if not isinstance(phoneme_id_map, dict):
        raise RuntimeError("Piper config does not contain phoneme_id_map")
    with (destination / "tokens.txt").open("w", encoding="utf-8") as out:
        for symbol, ids in phoneme_id_map.items():
            if not isinstance(ids, list) or not ids:
                raise RuntimeError(f"Invalid token id for {symbol!r}")
            out.write(f"{symbol} {ids[0]}\n")
    model = onnx.load(str(source_model))
    metadata = {"model_type": "vits", "comment": "piper", "language": config["language"]["name_english"], "voice": config["espeak"]["voice"], "has_espeak": 1, "n_speakers": config["num_speakers"], "sample_rate": config["audio"]["sample_rate"]}
    for key, value in metadata.items():
        prop = model.metadata_props.add(); prop.key = key; prop.value = str(value)
    onnx.save(model, str(destination / "model.onnx"))
    ensure_espeak_data(destination)
    write_source(destination, f"rhasspy/piper-voices/{pack['onnx']}")
    source_model.unlink(missing_ok=True); source_config.unlink(missing_ok=True)


def fetch_tts(languages: list[str]) -> None:
    for lang in languages:
        if lang in PIPER_TTS_PACKS:
            fetch_piper_tts(lang, PIPER_TTS_PACKS[lang])
        elif lang in TTS_PACKS:
            fetch_sherpa_tts(lang, TTS_PACKS[lang])
        else:
            raise SystemExit(f"Unsupported TTS language: {lang}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stt", default="hi,en,gu")
    parser.add_argument("--tts", default="hi,mr,en")
    parser.add_argument("--skip-vad", action="store_true")
    args = parser.parse_args()
    stt = [x.strip() for x in args.stt.split(",") if x.strip()]
    tts = [x.strip() for x in args.tts.split(",") if x.strip()]
    supported_tts = set(TTS_PACKS) | set(PIPER_TTS_PACKS)
    if set(stt) - set(STT_PACKS) or set(tts) - supported_tts:
        raise SystemExit("Unsupported model pack requested")
    ASSETS.mkdir(parents=True, exist_ok=True)
    if not args.skip_vad: fetch_vad()
    fetch_stt(stt)
    fetch_tts(tts)
    print("Real offline model packs are ready under app/src/main/assets/models/")


if __name__ == "__main__":
    main()
