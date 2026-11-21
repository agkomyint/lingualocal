"use client";
/**
 * Live preview on the Gemini Live API: streams speech as it is recorded and shows Gemini's running
 * transcript on the piece being recorded. The final words come from the translator. Port of LiveInterpreter.kt.
 */
import { getLiveGenerativeModel, ResponseModality, type LiveSession, type LiveGenerativeModel } from "firebase/ai";
import { fbNow } from "./firebase";
import { LIVE_MODEL, PREVIEW_PROMPT } from "./config";
import { EN, TH, MY, type LangPair } from "./lang";
import { caption, pairFor, setApp, updateCaption } from "./state";
import { base64 } from "./audio";
import { isDone } from "./types";

function scriptOf(ch: string): string | null {
  const c = ch.codePointAt(0)!;
  if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122)) return EN;
  if (c >= 0x0e00 && c <= 0x0e7f) return TH;
  if ((c >= 0x1000 && c <= 0x109f) || (c >= 0xa9e0 && c <= 0xa9ff) || (c >= 0xaa60 && c <= 0xaa7f)) return MY;
  if (/\p{L}/u.test(ch)) return "other";
  return null;
}

/** Drops a chunk mostly in a third language's script, and stray letters from other scripts. */
export function keepPairScripts(text: string, pair: LangPair): string | null {
  const allowed = new Set([pair.a, pair.b]);
  const chars = [...text];
  const letters = chars.map(scriptOf).filter((s): s is string => s != null);
  if (!letters.length) return text;
  if (letters.filter((s) => allowed.has(s)).length * 2 < letters.length) return null;
  return chars.filter((ch) => { const s = scriptOf(ch); return s == null || allowed.has(s); }).join("");
}

export class LiveInterpreter {
  private session: LiveSession | null = null;
  private lastSegment = -1;
  private stopped = false;
  private model: LiveGenerativeModel;
  private wake: (() => void) | null = null;

  constructor(private pid: string) {
    this.model = getLiveGenerativeModel(fbNow().ai, {
      model: LIVE_MODEL,
      generationConfig: { responseModalities: [ResponseModality.AUDIO], inputAudioTranscription: {} },
      systemInstruction: PREVIEW_PROMPT,
    });
    this.loop();
  }

  /** Streams a piece of audio for the piece segmentId. Dropped while disconnected. */
  send(segmentId: number, bytes: Uint8Array) {
    const s = this.session;
    if (!s) return;
    s.sendAudioRealtime({ mimeType: "audio/pcm;rate=16000", data: base64(bytes) })
      .then(() => { this.lastSegment = segmentId; })
      .catch(() => {});
  }

  stop() {
    this.stopped = true;
    this.session?.close().catch(() => {});
    this.session = null;
    this.wake?.();
  }

  private sleep(ms: number) {
    return new Promise<void>((r) => { const t = setTimeout(r, ms); this.wake = () => { clearTimeout(t); r(); }; });
  }

  private async loop() {
    let handle: string | undefined;
    let backoff = 1000;
    while (!this.stopped) {
      let s: LiveSession | null = null;
      let refresh = false;
      try {
        setApp({ liveStatus: "Connecting…" });
        s = await this.model.connect(handle ? { handle } : {});
        if (this.stopped) { await s.close(); break; }
        this.session = s;
        setApp({ liveStatus: "Live · listening" });
        backoff = 1000;
        for await (const msg of s.receive()) {
          if (this.stopped) break;
          if (msg.type === "serverContent") this.onContent(msg.inputTranscription?.text);
          else if (msg.type === "sessionResumptionUpdate") { if (msg.resumable && msg.newHandle) handle = msg.newHandle; }
          else if (msg.type === "goingAwayNotice") { refresh = true; break; }
        }
        refresh = true;
      } catch (e) {
        const m = (e as Error)?.message ?? String(e);
        if (!this.stopped) setApp({ liveStatus: `Offline · ${m.slice(0, 80)} · retrying` });
      } finally {
        this.session = null;
        s?.close().catch(() => {});
      }
      if (this.stopped) break;
      if (!refresh) { await this.sleep(backoff); backoff = Math.min(backoff * 2, 15_000); }
    }
    setApp({ liveStatus: "Idle" });
  }

  private onContent(raw?: string) {
    if (!raw?.trim()) return;
    const t = keepPairScripts(raw, pairFor(this.pid));
    if (t == null) return;
    const target = caption(this.pid, this.lastSegment);
    if (!target || target.mine || isDone(target)) return;
    updateCaption(this.pid, target.id, (c) => (isDone(c) ? c : { ...c, preview: c.preview + t }));
  }
}
