// Mic (or tab) audio to 16 kHz mono frames of 512 samples, posted to the page.
class MicFrames extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.pos = 0;
    this.buf = new Float32Array(512);
    this.n = 0;
    this.last = 0;
  }
  process(inputs) {
    const ch = inputs[0];
    if (!ch || !ch.length) return true;
    const a = ch[0], b = ch[1];
    const len = a.length;
    for (;;) {
      const i = Math.floor(this.pos);
      if (i >= len) break;
      const f = this.pos - i;
      const s0 = i < 0 ? this.last : (b ? (a[i] + b[i]) / 2 : a[i]);
      const j = i + 1 < len ? i + 1 : i;
      const s1 = b ? (a[j] + b[j]) / 2 : a[j];
      this.buf[this.n++] = s0 + (s1 - s0) * f;
      if (this.n === 512) {
        this.port.postMessage(this.buf, [this.buf.buffer]);
        this.buf = new Float32Array(512);
        this.n = 0;
      }
      this.pos += this.ratio;
    }
    this.last = b ? (a[len - 1] + b[len - 1]) / 2 : a[len - 1];
    this.pos -= len;
    return true;
  }
}
registerProcessor("mic-frames", MicFrames);
