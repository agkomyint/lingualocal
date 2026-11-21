/* tslint:disable */
/* eslint-disable */

export class Listener {
    free(): void;
    [Symbol.dispose](): void;
    /**
     * Audio for the live preview from the last call (belongs to the piece that was open before any START).
     */
    chunk(): Uint8Array;
    duration_ms(now: number): number;
    /**
     * Stop tapped mid-sentence: what was said so far is a real piece.
     */
    finish(now: number): number;
    /**
     * The last frame after the volume boost, 16-bit PCM.
     */
    frame(): Uint8Array;
    gain(): number;
    constructor();
    prob(): number;
    /**
     * One frame of mic audio (-1..1) with its voice probability. `now` is in ms.
     */
    process(frame: Float32Array | number[], prob: number, now: number): number;
    rms(): number;
    segment_ms(): number;
    /**
     * Hold-to-speak pressed or released. Pressing ends a piece that was being recorded.
     */
    set_ptt(on: boolean, now: number): number;
    speaking(): boolean;
    /**
     * The ended piece's PCM (moved out).
     */
    take_segment(): Uint8Array;
    voiced_ms(): number;
    wave_levels(): Float32Array;
    wave_speech(): Uint8Array;
}

/**
 * CAM++ input from 16-bit PCM: fbank rows with each bin's mean removed (also cancels the volume boost).
 * Returns T*80 values; empty if there is too little audio.
 */
export function speaker_features(pcm: Uint8Array | number[]): Float32Array;

/**
 * Mono 16-bit WAV file of PCM at `rate` Hz.
 */
export function wav(pcm: Uint8Array | number[], rate: number): Uint8Array;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_listener_free: (a: number, b: number) => void;
    readonly listener_chunk: (a: number) => [number, number];
    readonly listener_duration_ms: (a: number, b: number) => number;
    readonly listener_finish: (a: number, b: number) => number;
    readonly listener_frame: (a: number) => [number, number];
    readonly listener_gain: (a: number) => number;
    readonly listener_new: () => number;
    readonly listener_prob: (a: number) => number;
    readonly listener_process: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly listener_rms: (a: number) => number;
    readonly listener_segment_ms: (a: number) => number;
    readonly listener_set_ptt: (a: number, b: number, c: number) => number;
    readonly listener_speaking: (a: number) => number;
    readonly listener_take_segment: (a: number) => [number, number];
    readonly listener_voiced_ms: (a: number) => number;
    readonly listener_wave_levels: (a: number) => [number, number];
    readonly listener_wave_speech: (a: number) => [number, number];
    readonly speaker_features: (a: number, b: number) => [number, number];
    readonly wav: (a: number, b: number, c: number) => [number, number];
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
