"""
Export everything LinguaLocal recorded into a fine-tuning dataset, split by translation direction.

    python tools/export_dataset.py [out_dir]          (default out_dir: dataset)
    python tools/export_dataset.py --from <folder>    (use an already-copied "projects" folder instead of adb)

Pulls files/projects from the connected phone (debug build, via `adb exec-out run-as`) and writes:

    <out>/th-en.jsonl   Thai speech  -> English translation
    <out>/en-th.jsonl   English speech -> Thai translation
    <out>/audio/<project>/<file>.m4a   the recordings the rows point to (16 kHz mono AAC)

One JSON object per line:

    id            "<project>/<piece id>[#<turn>]"
    project       project name
    time          when the speech started (ISO 8601, UTC)
    source_lang   "th" | "en"
    target_lang   "en" | "th"
    source        what was said (transcript, in source_lang)
    target        its translation (in target_lang)
    audio         path of the recording relative to <out>, or null
    audio_ms      length of the recording
    engine        what produced source/target: "cloud:<model>" (Gemini) or "device:mlkit" (on-device);
                  cloud rows are the stronger labels; device rows are better used for evaluation
    speaker       voice number within the project (on-device speaker ID), or null
    mine          true for the user's own push-to-talk speech
    explanation   the cultural/context explanation shown on the card, if any
    turn          for pieces where several people spoke: index of this person's part. These rows share
                  one recording with the other turns, so "audio" is null for them (text pairs only).

Only finished pieces with words on both sides are exported (no noise, echo, or untranslated pieces).
"""
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from datetime import datetime, timezone

PACKAGE = "com.example.lingualocal"


def thai(text):
    return any("฀" <= ch <= "๿" for ch in text)


def lang_of(text):
    return "" if not text.strip() else ("th" if thai(text) else "en")


def other(lang):
    return {"th": "en", "en": "th"}.get(lang, "")


def pull(dest):
    adb = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.isfile(adb):
        adb = "adb"
    tar_path = os.path.join(dest, "projects.tar")
    with open(tar_path, "wb") as f:
        subprocess.run([adb, "exec-out", "run-as", PACKAGE, "tar", "cf", "-", "files/projects"], stdout=f, check=True)
    with tarfile.open(tar_path) as t:
        t.extractall(dest, filter="data")
    return os.path.join(dest, "files", "projects")


def rows(projects_dir, out_dir):
    for pid in sorted(os.listdir(projects_dir)):
        pdir = os.path.join(projects_dir, pid)
        try:
            meta = json.load(open(os.path.join(pdir, "project.json"), encoding="utf-8"))
            pieces = json.load(open(os.path.join(pdir, "captions.json"), encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for c in pieces:
            if c.get("status") != "DONE":
                continue
            base = {
                "project": meta.get("name", pid),
                "time": datetime.fromtimestamp(c["time"] / 1000, timezone.utc).isoformat(),
                "engine": c.get("engine") or ("device:mlkit" if c.get("offline") else "unknown"),
                "speaker": c.get("speaker"),
                "mine": bool(c.get("mine")),
                "explanation": c.get("explanation"),
            }
            turns = c.get("turns") or []
            if turns:
                for i, t in enumerate(turns):
                    src, tgt = t.get("heard", "").strip(), t.get("translation", "").strip()
                    lang = t.get("lang") or lang_of(src)
                    if src and tgt and lang:
                        yield dict(base, id=f"{pid}/{c['id']}#{i}", source_lang=lang, target_lang=other(lang),
                                   source=src, target=tgt, audio=None, audio_ms=None, turn=i,
                                   speaker=t.get("speaker"))
                continue
            src, tgt = c.get("heard", "").strip(), c.get("translation", "").strip()
            lang = c.get("lang") or lang_of(src)
            if not (src and tgt and lang):
                continue
            audio = None
            if c.get("audio"):
                f = os.path.join(pdir, "audio", c["audio"])
                if os.path.isfile(f):
                    audio = f"audio/{pid}/{c['audio']}"
                    os.makedirs(os.path.join(out_dir, "audio", pid), exist_ok=True)
                    shutil.copy2(f, os.path.join(out_dir, audio))
            yield dict(base, id=f"{pid}/{c['id']}", source_lang=lang, target_lang=other(lang),
                       source=src, target=tgt, audio=audio, audio_ms=c.get("durationMs"), turn=None)


def main():
    args = sys.argv[1:]
    source = None
    if "--from" in args:
        i = args.index("--from")
        source = args[i + 1]
        del args[i:i + 2]
    out_dir = args[0] if args else "dataset"
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        projects_dir = source or pull(tmp)
        files = {d: open(os.path.join(out_dir, f"{d}.jsonl"), "w", encoding="utf-8") for d in ("th-en", "en-th")}
        counts = {d: 0 for d in files}
        for r in rows(projects_dir, out_dir):
            d = f"{r['source_lang']}-{r['target_lang']}"
            if d in files:
                files[d].write(json.dumps(r, ensure_ascii=False) + "\n")
                counts[d] += 1
        for f in files.values():
            f.close()
    print(f"th-en: {counts['th-en']} pairs, en-th: {counts['en-th']} pairs -> {os.path.abspath(out_dir)}")


if __name__ == "__main__":
    main()
