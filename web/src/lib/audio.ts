"use client";
/** The Rust/WASM audio core (segmenter, volume boost, waveform, fbank, WAV). */
import init, { Listener, speaker_features as features, wav as wavOf } from "@/wasm/audio-core/audio_core";

let ready: Promise<unknown> | null = null;
export const loadCore = () => (ready ??= init({ module_or_path: "/wasm/audio_core_bg.wasm" }));

export const EV_START = 1, EV_END = 2, EV_SKIP = 4, EV_CHUNK = 8, EV_DURATION = 16, EV_PTT = 32;
export { Listener };
export const speaker_features = (pcm: Uint8Array) => features(pcm);
export const wav = (pcm: Uint8Array, rate = 16000) => wavOf(pcm, rate);

export function base64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
export function fromBase64(b64: string): Uint8Array {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
