"use client";
/** Silero VAD v5 (16 kHz, 512-sample frames with 64 samples of context). Port of SileroVad.kt. */
import { onnx, type InferenceSession, type Tensor } from "./onnx";

const CONTEXT = 64;
const FRAME = 512;

export class SileroVad {
  private constructor(private session: InferenceSession) {}
  private state = new Float32Array(2 * 128);
  private context = new Float32Array(CONTEXT);
  private input = new Float32Array(CONTEXT + FRAME);
  private sr: Tensor | null = null;

  static async load(): Promise<SileroVad> {
    const ort = onnx();
    const s = await ort.InferenceSession.create("/models/silero_vad.onnx", { executionProviders: ["wasm"] });
    return new SileroVad(s);
  }

  reset() { this.state.fill(0); this.context.fill(0); }

  /** Probability (0..1) that the frame has speech. */
  async probability(frame: Float32Array): Promise<number> {
    const ort = onnx();
    this.input.set(this.context, 0);
    this.input.set(frame.subarray(0, FRAME), CONTEXT);
    this.sr ??= new ort.Tensor("int64", BigInt64Array.from([16000n]), []);
    const out = await this.session.run({
      input: new ort.Tensor("float32", this.input.slice(), [1, CONTEXT + FRAME]),
      state: new ort.Tensor("float32", this.state, [2, 1, 128]),
      sr: this.sr,
    });
    const names = this.session.outputNames;
    const prob = (out[names[0]].data as Float32Array)[0];
    this.state = new Float32Array(out[names[1]].data as Float32Array);
    this.context.set(frame.subarray(FRAME - CONTEXT, FRAME));
    return prob;
  }
}
