"use client";
/**
 * Listening: mic (or tab audio) → AudioWorklet 16 kHz frames → Silero VAD → Rust segmenter + volume boost →
 * Gemini Live preview + one Gemini request per piece. Every piece is saved for replay. Port of ListenService.kt
 * and PushToTalk.kt (the web has no on-device speech recognition, so push-to-talk goes to Gemini).
 */
import { Listener, loadCore, wav, EV_START, EV_END, EV_SKIP, EV_CHUNK, EV_DURATION, EV_PTT } from "./audio";
import { SileroVad } from "./vad";
import { LiveInterpreter } from "./live";
import { submit } from "./translator";
import { model as speakerModel } from "./people";
import { stop as stopSpeaking } from "./speak";
import { saveAudio } from "./store";
import { app, setApp, newCaption, updateCaption, pairFor, useApp, toast } from "./state";
import { SOURCE_MIC, type WaveBar } from "./types";

interface Run {
  pid: string;
  ctx: AudioContext;
  stream: MediaStream;
  node: AudioWorkletNode;
  listener: Listener;
  vad: SileroVad | null;
  live: LiveInterpreter;
  phone: boolean;
  segmentId: number;
  frames: Float32Array[];
  busy: boolean;
  pttOn: boolean;
  ptt: { id: number; parts: Uint8Array[]; size: number } | null;
  wake: WakeLockSentinel | null;
  frameCount: number;
  lastUi: number;
  unsub: () => void;
  stopped: boolean;
}

let run: Run | null = null;
let starting = false;

/** Starts listening into the project on screen. Phone = another tab's or app's sound (screen share audio). */
export async function startListening(phone = app().source === "PHONE") {
  if (run || starting) return;
  const pid = app().viewId;
  if (!pid) return;
  starting = true;
  try {
    await loadCore();
    let stream: MediaStream;
    if (phone) {
      stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } as MediaTrackConstraints });
      stream.getVideoTracks().forEach((t) => t.stop());
      if (!stream.getAudioTracks().length) { toast("No sound shared · tick “Share audio” and try again"); return; }
    } else {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: false, channelCount: 1 } });
    }
    let ctx: AudioContext;
    try { ctx = new AudioContext({ sampleRate: 16000 }); } catch { ctx = new AudioContext(); }
    await ctx.audioWorklet.addModule("/mic-worklet.js");
    const src = ctx.createMediaStreamSource(stream);
    const node = new AudioWorkletNode(ctx, "mic-frames", { numberOfInputs: 1, numberOfOutputs: 0 });
    src.connect(node);
    await ctx.resume();

    const vad = await SileroVad.load().then((v) => { setApp({ vadStatus: "Running in the browser (ONNX Runtime)" }); return v; })
      .catch((e) => { setApp({ vadStatus: `Failed: ${String(e?.message ?? e).slice(0, 80)} · using volume gate` }); return null; });
    speakerModel().then(() => setApp({ speakerStatus: "Running in the browser (ONNX Runtime)" }))
      .catch((e) => setApp({ speakerStatus: `Failed: ${String(e?.message ?? e).slice(0, 80)}` }));

    setApp({ session: Date.now(), running: true, liveId: pid, runningSource: phone ? "PHONE" : "MIC" });
    const r: Run = {
      pid, ctx, stream, node, listener: new Listener(), vad, live: new LiveInterpreter(pid), phone,
      segmentId: -1, frames: [], busy: false, pttOn: false, ptt: null, wake: null, frameCount: 0, lastUi: 0,
      unsub: () => {}, stopped: false,
    };
    run = r;
    node.port.onmessage = (e) => { r.frames.push(e.data as Float32Array); if (!r.busy) pump(r); };
    stream.getAudioTracks()[0].addEventListener("ended", () => stopListening());
    r.unsub = useApp.subscribe((s, p) => { if (p.speakAloud && !s.speakAloud) stopSpeaking(); });
    keepAwake(r);
  } catch (e) {
    console.warn("start listening failed", e);
    const name = (e as DOMException)?.name;
    if (name === "NotAllowedError") toast(phone ? "Sharing was cancelled" : "Microphone access is blocked for this site");
    else toast(`Couldn't start listening · ${String((e as Error)?.message ?? e).slice(0, 60)}`);
  } finally {
    starting = false;
  }
}

async function keepAwake(r: Run) {
  try {
    r.wake = await navigator.wakeLock?.request("screen");
    const again = () => { if (!document.hidden && run === r && !r.stopped) navigator.wakeLock?.request("screen").then((w) => { r.wake = w; }).catch(() => {}); };
    document.addEventListener("visibilitychange", again);
  } catch { /* not supported */ }
}

/** Stops listening; pieces already recorded still finish translating. */
export function stopListening() {
  const r = run;
  if (!r) return;
  run = null;
  r.stopped = true;
  const now = performance.now();
  if (r.ptt) pttRelease(r, now);
  try { handle(r, r.listener.finish(now), now); } catch (e) { console.warn("final piece failed", e); }
  r.node.port.onmessage = null;
  r.node.disconnect();
  r.stream.getTracks().forEach((t) => t.stop());
  r.ctx.close().catch(() => {});
  r.live.stop();
  r.unsub();
  r.wake?.release().catch(() => {});
  r.listener.free();
  setApp({
    running: false, liveStatus: "Idle", speechActive: false, micLevel: 0, vadProbability: 0, inputGain: 1,
    waveform: [], pushToTalk: false, session: 0, liveId: null,
  });
}

export const isListening = () => run != null;

async function pump(r: Run) {
  r.busy = true;
  try {
    while (r.frames.length && !r.stopped) {
      // Far behind (tab was asleep): drop the oldest so the VAD catches up.
      if (r.frames.length > 300) r.frames.splice(0, r.frames.length - 300);
      const frame = r.frames.shift()!;
      let prob: number;
      if (r.vad) {
        try { prob = await r.vad.probability(frame); } catch { prob = 0; }
      } else {
        let s = 0;
        for (const v of frame) s += v * v;
        prob = Math.sqrt(s / frame.length) > 0.02 ? 1 : 0;
      }
      if (r.stopped) break;
      step(r, frame, prob);
    }
  } finally { r.busy = false; }
}

function step(r: Run, frame: Float32Array, prob: number) {
  const now = performance.now();
  const L = r.listener;
  const wanted = app().pushToTalk && !r.phone;
  if (wanted !== r.pttOn) {
    const ev = L.set_ptt(wanted, now);
    handle(r, ev, now);
    r.pttOn = wanted;
    if (wanted) pttStart(r); else pttRelease(r, now);
  }
  const ev = L.process(frame, prob, now);
  if (ev & EV_PTT) pttFeed(r, L.frame());
  handle(r, ev, now);
  r.frameCount++;
  if (r.frameCount % 2 === 0) {
    const levels = L.wave_levels(), speech = L.wave_speech();
    const wave: WaveBar[] = new Array(levels.length);
    for (let i = 0; i < levels.length; i++) wave[i] = { level: levels[i], speech: speech[i] === 1 };
    setApp({ waveform: wave });
  }
  if (now - r.lastUi > 80) {
    setApp({ micLevel: Math.min(1, Math.max(0, L.rms() * 6)), vadProbability: prob, speechActive: L.speaking(), inputGain: L.gain() });
    r.lastUi = now;
  }
}

/** Order: the chunk belongs to the piece that was open, then it ends, then a new one starts. */
function handle(r: Run, ev: number, now: number) {
  if (!ev) return;
  const L = r.listener;
  const id = r.segmentId;
  if (ev & EV_CHUNK && id >= 0) r.live.send(id, L.chunk());
  if (ev & (EV_END | EV_SKIP) && id >= 0) {
    const pcm = L.take_segment();
    const ms = L.segment_ms();
    const voiced = L.voiced_ms();
    if (ev & EV_END) {
      updateCaption(r.pid, id, (c) => ({ ...c, durationMs: ms }));
      submit(r.pid, pairFor(r.pid), id, pcm, voiced);
    } else {
      updateCaption(r.pid, id, (c) => ({ ...c, durationMs: ms, status: "SKIPPED" }));
    }
    saveAudio(r.pid, `segment_${id}.wav`, wav(pcm)).then((path) => updateCaption(r.pid, id, (c) => ({ ...c, audioFile: path }))).catch(() => {});
    r.segmentId = -1;
  }
  if (ev & EV_START) r.segmentId = newCaption(r.pid);
  if (ev & EV_DURATION && r.segmentId >= 0) {
    const d = L.duration_ms(now);
    const sid = r.segmentId;
    updateCaption(r.pid, sid, (c) => ({ ...c, durationMs: d }));
  }
}

// ---- Push-to-talk ----

function pttStart(r: Run) {
  stopSpeaking();
  const id = newCaption(r.pid, true, SOURCE_MIC);
  r.ptt = { id, parts: [], size: 0 };
}

function pttFeed(r: Run, bytes: Uint8Array) {
  const t = r.ptt;
  if (!t) return;
  t.parts.push(bytes);
  t.size += bytes.length;
  if (t.size % 32_000 < bytes.length) {
    const ms = t.size / 32;
    updateCaption(r.pid, t.id, (c) => ({ ...c, durationMs: ms }));
  }
}

function pttRelease(r: Run, _now: number) {
  const t = r.ptt;
  if (!t) return;
  r.ptt = null;
  const pcm = new Uint8Array(t.size);
  let o = 0;
  for (const p of t.parts) { pcm.set(p, o); o += p.length; }
  updateCaption(r.pid, t.id, (c) => ({ ...c, durationMs: pcm.length / 32, status: "TRANSLATING" }));
  saveAudio(r.pid, `segment_${t.id}.wav`, wav(pcm)).then((path) => updateCaption(r.pid, t.id, (c) => ({ ...c, audioFile: path }))).catch(() => {});
  updateCaption(r.pid, t.id, (c) => ({ ...c, status: "QUEUED" }));
  submit(r.pid, pairFor(r.pid), t.id, pcm);
}

/** Hold-to-speak needs the mic; with tab audio it explains instead. */
export function setPushToTalk(on: boolean) {
  if (on && run?.phone) { toast("Hold to speak works with the mic, not shared audio"); return; }
  setApp({ pushToTalk: on });
}
