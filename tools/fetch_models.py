#!/usr/bin/env python3
"""Fetch compact real offline model packs for iTantra."""
from __future__ import annotations
import argparse, io, json, shutil, tarfile, urllib.request, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; ASSETS=ROOT/"app/src/main/assets/models"
VOSK_BASE="https://alphacephei.com/vosk/models"; SHERPA_ASR_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"; SHERPA_TTS_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"; PIPER_HF_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
STT_PACKS={"hi":"vosk-model-small-hi-0.22.zip","en":"vosk-model-small-en-in-0.4.zip","gu":"vosk-model-small-gu-0.42.zip","te":"vosk-model-small-te-0.42.zip"}
TTS_PACKS={"hi":"vits-piper-hi_IN-priyamvada-medium.tar.bz2","en":"vits-piper-en_US-amy-medium.tar.bz2","bn":"vits-piper-bn_BD-google-medium.tar.bz2","ml":"vits-piper-ml_IN-arjun-medium.tar.bz2","te":"vits-piper-te_IN-maya-medium.tar.bz2"}
PIPER_TTS_PACKS={"mr":{"onnx":"mr/mr_IN/google/medium/mr_IN-google-medium.onnx","config":"mr/mr_IN/google/medium/mr_IN-google-medium.onnx.json"}}
def download(url):
 req=urllib.request.Request(url,headers={"User-Agent":"iTantra-model-fetcher/3.0"}); last=None
 for attempt in range(1,6):
  try:
   print(f"GET {url}")
   with urllib.request.urlopen(req,timeout=180) as r: data=r.read()
   if not data: raise RuntimeError("empty response")
   print(f"downloaded {len(data):,} bytes"); return data
  except Exception as e:
   last=e; print(f"attempt {attempt}/5 failed: {e}")
   if attempt<5:
    import time; time.sleep(attempt*2)
 raise RuntimeError(f"download failed: {url}") from last
def extract_vosk(data,destination,prefix):
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  for name in z.namelist():
   parts=Path(name).parts
   if not parts or parts[0]!=prefix or name.endswith("/"): continue
   target=destination/Path(*parts[1:]); target.parent.mkdir(parents=True,exist_ok=True)
   with z.open(name) as src,target.open("wb") as dst: shutil.copyfileobj(src,dst)
def extract_tgz(data,destination,strip_root=True):
 with tarfile.open(fileobj=io.BytesIO(data),mode="r:bz2") as tar:
  members=tar.getmembers(); roots={Path(m.name).parts[0] for m in members if Path(m.name).parts}; root=next(iter(roots)) if strip_root and len(roots)==1 else None
  if strip_root and root is None: raise RuntimeError(f"Unexpected archive roots: {sorted(roots)}")
  for member in members:
   parts=Path(member.name).parts
   if not parts or (strip_root and parts[0]!=root): continue
   relative=Path(*parts[1:]) if strip_root else Path(*parts)
   if not relative.parts: continue
   target=destination/relative
   if member.isdir(): target.mkdir(parents=True,exist_ok=True)
   elif member.isfile():
    target.parent.mkdir(parents=True,exist_ok=True)
    with tar.extractfile(member) as src,target.open("wb") as dst:
     if src is None: raise RuntimeError(member.name)
     shutil.copyfileobj(src,dst)
def ensure_shared_espeak_data():
 target=ASSETS/"tts/espeak-ng-data"
 if target.is_dir() and all((target/name).exists() for name in ("phontab","phonindex","phondata","intonations")): return
 tmp=ASSETS/"tts/.espeak-tmp"; shutil.rmtree(tmp,ignore_errors=True); tmp.mkdir(parents=True,exist_ok=True)
 extract_tgz(download(f"{SHERPA_TTS_BASE}/espeak-ng-data.tar.bz2"),tmp,False)
 candidate=tmp/"espeak-ng-data"; dirs=[p for p in tmp.iterdir() if p.is_dir()]
 if not candidate.is_dir(): candidate=dirs[0] if len(dirs)==1 else None
 if candidate is None: raise RuntimeError("Unexpected espeak-ng-data archive layout")
 shutil.rmtree(target,ignore_errors=True); target.parent.mkdir(parents=True,exist_ok=True); shutil.move(str(candidate),str(target)); shutil.rmtree(tmp,ignore_errors=True)
 keep_files={"version","phondata","phonindex","phontab","intonations","phondata-manifest","en_dict","hi_dict","mr_dict"}
 keep_roots={"lang","voices"}
 for child in list(target.iterdir()):
  if child.name in keep_files or child.name in keep_roots: continue
  if child.is_dir(): shutil.rmtree(child)
  else: child.unlink()
 lang_dir=target/"lang"
 if lang_dir.is_dir():
  for child in list(lang_dir.iterdir()):
   if child.name not in {"gmw","inc"}:
    if child.is_dir(): shutil.rmtree(child)
    else: child.unlink()
def fetch_vad():
 target=ASSETS/"vad/silero_vad.onnx"
 if not target.exists(): target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(download(f"{SHERPA_ASR_BASE}/silero_vad.onnx"))
def fetch_stt(languages):
 for lang in languages:
  if lang=="none": continue
  archive=STT_PACKS[lang]; destination=ASSETS/"vosk"/lang
  if (destination/"am").exists() and (destination/"conf").exists(): continue
  destination.mkdir(parents=True,exist_ok=True); extract_vosk(download(f"{VOSK_BASE}/{archive}"),destination,archive[:-4])
def write_source(destination,source,kind):
 (destination/"MODEL_SOURCE.json").write_text(json.dumps({"source":source,"runtime":"sherpa-onnx VITS" if kind=="tts" else "Vosk","offline_runtime":True,"pack_kind":kind},indent=2),encoding="utf-8")
def remove_embedded_espeak(destination):
 embedded=destination/"espeak-ng-data"
 if embedded.exists(): shutil.rmtree(embedded) if embedded.is_dir() else embedded.unlink()
def fetch_sherpa_tts(lang,archive):
 destination=ASSETS/"tts"/lang
 destination.mkdir(parents=True,exist_ok=True); extract_tgz(download(f"{SHERPA_TTS_BASE}/{archive}"),destination)
 models=sorted(destination.glob("*.onnx")); tokens=destination/"tokens.txt"
 if not models or not tokens.exists(): raise RuntimeError(f"Incomplete TTS pack: {archive}")
 if models[0].name!="model.onnx": models[0].rename(destination/"model.onnx")
 remove_embedded_espeak(destination); write_source(destination,archive,"tts")
def fetch_piper_tts(lang,pack):
 destination=ASSETS/"tts"/lang; destination.mkdir(parents=True,exist_ok=True)
 source_model=destination/".source.onnx"; source_config=destination/".source.onnx.json"
 source_model.write_bytes(download(f"{PIPER_HF_BASE}/{pack['onnx']}")); source_config.write_bytes(download(f"{PIPER_HF_BASE}/{pack['config']}"))
 import onnx
 config=json.loads(source_config.read_text(encoding="utf-8")); ids=config.get("phoneme_id_map")
 if not isinstance(ids,dict): raise RuntimeError("Piper config missing phoneme_id_map")
 with (destination/"tokens.txt").open("w",encoding="utf-8") as out:
  for symbol,values in ids.items(): out.write(f"{symbol} {values[0]}\n")
 model=onnx.load(str(source_model))
 for key,value in {"model_type":"vits","comment":"piper","language":config["language"]["name_english"],"voice":config["espeak"]["voice"],"has_espeak":1,"n_speakers":config["num_speakers"],"sample_rate":config["audio"]["sample_rate"]}.items():
  prop=model.metadata_props.add(); prop.key=key; prop.value=str(value)
 onnx.save(model,str(destination/"model.onnx")); write_source(destination,f"rhasspy/piper-voices/{pack['onnx']}","tts"); source_model.unlink(missing_ok=True); source_config.unlink(missing_ok=True)
def fetch_tts(languages):
 ensure_shared_espeak_data()
 for lang in languages:
  if lang in PIPER_TTS_PACKS: fetch_piper_tts(lang,PIPER_TTS_PACKS[lang])
  elif lang in TTS_PACKS: fetch_sherpa_tts(lang,TTS_PACKS[lang])
  else: raise SystemExit(f"Unsupported TTS language: {lang}")
def main():
 p=argparse.ArgumentParser(); p.add_argument("--stt",default="hi"); p.add_argument("--tts",default="hi"); p.add_argument("--skip-vad",action="store_true"); a=p.parse_args()
 stt=[x.strip() for x in a.stt.split(",") if x.strip()]; tts=[x.strip() for x in a.tts.split(",") if x.strip()]
 if any(x not in STT_PACKS and x!="none" for x in stt): raise SystemExit("Unsupported STT model pack requested")
 if any(x not in TTS_PACKS and x not in PIPER_TTS_PACKS for x in tts): raise SystemExit("Unsupported TTS model pack requested")
 if not stt and not tts: raise SystemExit("At least one STT or TTS model must be requested")
 ASSETS.mkdir(parents=True,exist_ok=True)
 if not a.skip_vad: fetch_vad()
 fetch_stt(stt); fetch_tts(tts)
 print("Single-language real offline model pack is ready")
if __name__=="__main__": main()
