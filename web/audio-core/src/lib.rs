//! LinguaLocal audio core: the on-device part of listening, ported 1:1 from the Android ListenService.
//! The page feeds it 32 ms frames (512 samples at 16 kHz) plus the Silero VAD probability for each frame;
//! it boosts quiet speech, cuts speech into pieces, keeps the waveform, and makes speaker features.
use std::collections::VecDeque;
use wasm_bindgen::prelude::*;

mod fbank;

const SPEECH_THRESHOLD: f32 = 0.5;
const BREATH_THRESHOLD: f32 = 0.3;
const HANGOVER_FRAMES: u32 = 25; // 0.8 s of silence ends a piece
const PRE_ROLL_FRAMES: usize = 10; // 0.32 s kept from before speech starts
const BATCH_BYTES: usize = 3200; // 100 ms chunks to the live preview
const SOFT_CUT_MS: f64 = 8_000.0; // after this, cut at the next breath
const MAX_SEGMENT_MS: f64 = 15_000.0; // hard cut if there is no breath
const MIN_VOICED_FRAMES: u32 = 10; // 0.32 s of clear voice before anything is sent
const WAVE_BARS: usize = 150; // 4.8 s of waveform
const TARGET_RMS: f32 = 0.08;
const MAX_GAIN: f32 = 8.0;
const KNEE: f32 = 0.8;
const MAX_SPEAKER_SAMPLES: usize = 16_000 * 15;

/// Events of one call, as bits. Handle them in this order: CHUNK, END/SKIP, START.
pub const EV_START: u32 = 1;
pub const EV_END: u32 = 2;
pub const EV_SKIP: u32 = 4;
pub const EV_CHUNK: u32 = 8;
pub const EV_DURATION: u32 = 16;
pub const EV_PTT: u32 = 32;

/// Automatic gain for quiet or distant speech, with a soft limiter so loud peaks don't clip.
struct Agc {
    gain: f32,
    speech_rms: f32,
}

impl Agc {
    fn track(&mut self, rms: f32) {
        self.speech_rms = if self.speech_rms == 0.0 { rms } else { self.speech_rms * 0.95 + rms * 0.05 };
        let target = (TARGET_RMS / self.speech_rms.max(1e-4)).clamp(1.0, MAX_GAIN);
        // Turn down quickly when it gets loud, turn up slowly.
        self.gain += (target - self.gain) * if target < self.gain { 0.3 } else { 0.03 };
    }
    fn apply(&self, frame: &[f32], out: &mut Vec<u8>) {
        out.clear();
        for &s in frame {
            let mut x = s * self.gain;
            let a = x.abs();
            if a > KNEE {
                x = x.signum() * (KNEE + (1.0 - KNEE) * ((a - KNEE) / (1.0 - KNEE)).tanh());
            }
            let v = (x * 32767.0) as i32;
            out.extend_from_slice(&(v.clamp(-32768, 32767) as i16).to_le_bytes());
        }
    }
}

#[wasm_bindgen]
pub struct Listener {
    agc: Agc,
    speaking: bool,
    hangover: u32,
    pre_roll: VecDeque<Vec<u8>>,
    batch: Vec<u8>,
    segment: Vec<u8>,
    voiced: u32,
    confirmed: bool,
    seg_start: f64,
    last_duration: f64,
    ptt: bool,
    frame_bytes: Vec<u8>,
    wave_level: VecDeque<f32>,
    wave_speech: VecDeque<u8>,
    // Results of the last call.
    out_chunk: Vec<u8>,
    out_segment: Vec<u8>,
    out_segment_ms: f64,
    out_voiced_ms: f64,
    rms: f32,
    prob: f32,
}

impl Default for Listener {
    fn default() -> Self {
        Self::new()
    }
}

#[wasm_bindgen]
impl Listener {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Listener {
        Listener {
            agc: Agc { gain: 1.0, speech_rms: 0.0 },
            speaking: false,
            hangover: 0,
            pre_roll: VecDeque::new(),
            batch: Vec::new(),
            segment: Vec::new(),
            voiced: 0,
            confirmed: false,
            seg_start: 0.0,
            last_duration: 0.0,
            ptt: false,
            frame_bytes: Vec::with_capacity(1024),
            wave_level: VecDeque::new(),
            wave_speech: VecDeque::new(),
            out_chunk: Vec::new(),
            out_segment: Vec::new(),
            out_segment_ms: 0.0,
            out_voiced_ms: 0.0,
            rms: 0.0,
            prob: 0.0,
        }
    }

    fn end_segment(&mut self, now: f64) -> u32 {
        let mut ev = 0;
        self.out_segment = std::mem::take(&mut self.segment);
        self.out_segment_ms = now - self.seg_start;
        self.out_voiced_ms = self.voiced as f64 * 32.0;
        if self.confirmed {
            if !self.batch.is_empty() {
                self.out_chunk.extend_from_slice(&self.batch);
                ev |= EV_CHUNK;
            }
            ev |= EV_END;
        } else {
            // Too little voice (a click, a cough, background noise): never sent to Gemini.
            ev |= EV_SKIP;
        }
        self.batch.clear();
        ev
    }

    fn start_segment(&mut self, now: f64) -> u32 {
        self.seg_start = now;
        self.last_duration = now;
        self.voiced = 0;
        self.confirmed = false;
        for f in self.pre_roll.drain(..) {
            self.batch.extend_from_slice(&f);
            self.segment.extend_from_slice(&f);
        }
        EV_START
    }

    /// One frame of mic audio (-1..1) with its voice probability. `now` is in ms.
    pub fn process(&mut self, frame: &[f32], prob: f32, now: f64) -> u32 {
        let mut ev = 0;
        self.out_chunk.clear();
        let rms = (frame.iter().map(|&v| (v * v) as f64).sum::<f64>() / frame.len().max(1) as f64).sqrt() as f32;
        self.rms = rms;
        self.prob = prob;
        let voice = prob > SPEECH_THRESHOLD;
        if voice {
            self.hangover = HANGOVER_FRAMES
        } else if self.hangover > 0 {
            self.hangover -= 1
        }
        let now_speaking = voice || (self.speaking && self.hangover > 0);
        if voice || (self.ptt && prob > SPEECH_THRESHOLD) {
            self.agc.track(rms);
        }
        let mut bytes = std::mem::take(&mut self.frame_bytes);
        self.agc.apply(frame, &mut bytes);
        if self.ptt {
            ev |= EV_PTT; // the page sends frame() to push-to-talk
        } else if now_speaking {
            if !self.speaking {
                ev |= self.start_segment(now);
            }
            if voice {
                self.voiced += 1;
            }
            self.batch.extend_from_slice(&bytes);
            self.segment.extend_from_slice(&bytes);
            if !self.confirmed && self.voiced >= MIN_VOICED_FRAMES {
                self.confirmed = true; // real speech: start streaming it, including what was held back
            }
            if self.confirmed && self.batch.len() >= BATCH_BYTES {
                self.out_chunk.extend_from_slice(&self.batch);
                self.batch.clear();
                ev |= EV_CHUNK;
            }
            let length = now - self.seg_start;
            if length >= MAX_SEGMENT_MS || (length >= SOFT_CUT_MS && prob < BREATH_THRESHOLD) {
                // Non-stop speech (a talk, a video) is cut at a breath so translations keep flowing.
                ev |= self.end_segment(now);
                ev |= self.start_segment(now);
            } else if now - self.last_duration > 250.0 {
                ev |= EV_DURATION;
                self.last_duration = now;
            }
        } else {
            if self.speaking {
                ev |= self.end_segment(now);
            }
            self.pre_roll.push_back(bytes.clone());
            if self.pre_roll.len() > PRE_ROLL_FRAMES {
                self.pre_roll.pop_front();
            }
        }
        if !self.ptt {
            self.speaking = now_speaking;
        }
        self.frame_bytes = bytes;
        self.wave_level.push_back((rms * 6.0).clamp(0.0, 1.0));
        self.wave_speech.push_back((self.speaking || self.ptt) as u8);
        if self.wave_level.len() > WAVE_BARS {
            self.wave_level.pop_front();
            self.wave_speech.pop_front();
        }
        ev
    }

    /// Hold-to-speak pressed or released. Pressing ends a piece that was being recorded.
    pub fn set_ptt(&mut self, on: bool, now: f64) -> u32 {
        let mut ev = 0;
        if on == self.ptt {
            return 0;
        }
        self.out_chunk.clear();
        if on {
            if self.speaking {
                ev |= self.end_segment(now);
            }
            self.speaking = false;
            self.hangover = 0;
        }
        self.ptt = on;
        ev
    }

    /// Stop tapped mid-sentence: what was said so far is a real piece.
    pub fn finish(&mut self, now: f64) -> u32 {
        self.out_chunk.clear();
        if self.speaking && !self.ptt {
            self.speaking = false;
            return self.end_segment(now);
        }
        0
    }

    /// The last frame after the volume boost, 16-bit PCM.
    pub fn frame(&self) -> Vec<u8> {
        self.frame_bytes.clone()
    }
    /// Audio for the live preview from the last call (belongs to the piece that was open before any START).
    pub fn chunk(&self) -> Vec<u8> {
        self.out_chunk.clone()
    }
    /// The ended piece's PCM (moved out).
    pub fn take_segment(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.out_segment)
    }
    pub fn segment_ms(&self) -> f64 {
        self.out_segment_ms
    }
    pub fn voiced_ms(&self) -> f64 {
        self.out_voiced_ms
    }
    pub fn duration_ms(&self, now: f64) -> f64 {
        now - self.seg_start
    }
    pub fn speaking(&self) -> bool {
        self.speaking
    }
    pub fn rms(&self) -> f32 {
        self.rms
    }
    pub fn prob(&self) -> f32 {
        self.prob
    }
    pub fn gain(&self) -> f32 {
        self.agc.gain
    }
    pub fn wave_levels(&self) -> Vec<f32> {
        self.wave_level.iter().copied().collect()
    }
    pub fn wave_speech(&self) -> Vec<u8> {
        self.wave_speech.iter().copied().collect()
    }
}

thread_local! { static FBANK: fbank::Fbank = fbank::Fbank::new(); }

/// CAM++ input from 16-bit PCM: fbank rows with each bin's mean removed (also cancels the volume boost).
/// Returns T*80 values; empty if there is too little audio.
#[wasm_bindgen]
pub fn speaker_features(pcm: &[u8]) -> Vec<f32> {
    let n = (pcm.len() / 2).min(MAX_SPEAKER_SAMPLES);
    let samples: Vec<f32> = (0..n)
        .map(|i| i16::from_le_bytes([pcm[2 * i], pcm[2 * i + 1]]) as f32 / 32768.0)
        .collect();
    let mut f = FBANK.with(|fb| fb.compute(&samples));
    let frames = f.len() / fbank::BINS;
    if frames == 0 {
        return f;
    }
    let mut mean = [0f32; fbank::BINS];
    for t in 0..frames {
        for b in 0..fbank::BINS {
            mean[b] += f[t * fbank::BINS + b];
        }
    }
    for m in mean.iter_mut() {
        *m /= frames as f32;
    }
    for t in 0..frames {
        for b in 0..fbank::BINS {
            f[t * fbank::BINS + b] -= mean[b];
        }
    }
    f
}

/// Mono 16-bit WAV file of PCM at `rate` Hz.
#[wasm_bindgen]
pub fn wav(pcm: &[u8], rate: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(44 + pcm.len());
    let len = pcm.len() as u32;
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + len).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(pcm);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(n: usize, amp: f32) -> Vec<f32> {
        (0..n).map(|i| amp * (i as f32 * 0.1).sin()).collect()
    }

    #[test]
    fn short_noise_is_skipped_real_speech_is_sent() {
        let mut l = Listener::new();
        let f = tone(512, 0.05);
        let mut t = 0.0;
        // 5 voiced frames then silence: unconfirmed -> skipped.
        for _ in 0..5 {
            l.process(&f, 0.9, t);
            t += 32.0;
        }
        let mut ev = 0;
        for _ in 0..30 {
            ev |= l.process(&f, 0.0, t);
            t += 32.0;
        }
        assert!(ev & EV_SKIP != 0 && ev & EV_END == 0);
        // 40 voiced frames: confirmed -> end with audio, chunks streamed.
        let mut ev = 0;
        for _ in 0..40 {
            ev |= l.process(&f, 0.9, t);
            t += 32.0;
        }
        assert!(ev & EV_START != 0 && ev & EV_CHUNK != 0);
        let mut ev = 0;
        for _ in 0..30 {
            ev |= l.process(&f, 0.0, t);
            t += 32.0;
            if ev & EV_END != 0 {
                break;
            }
        }
        assert!(ev & EV_END != 0);
        let seg = l.take_segment();
        assert!(seg.len() >= 40 * 1024);
    }

    #[test]
    fn long_speech_is_cut() {
        let mut l = Listener::new();
        let f = tone(512, 0.05);
        let mut ends = 0;
        for i in 0..1000 {
            if l.process(&f, 0.9, i as f64 * 32.0) & EV_END != 0 {
                ends += 1;
            }
        }
        assert_eq!(ends, 2); // 32 s of non-stop speech: hard cut every 15 s
    }

    #[test]
    fn quiet_speech_is_boosted() {
        let mut l = Listener::new();
        let f = tone(512, 0.01);
        for i in 0..300 {
            l.process(&f, 0.9, i as f64 * 32.0);
        }
        assert!(l.gain() > 4.0);
    }

    #[test]
    fn fbank_shape() {
        let pcm: Vec<u8> = tone(16_000, 0.3)
            .iter()
            .flat_map(|&x| ((x * 32767.0) as i16).to_le_bytes())
            .collect();
        let f = speaker_features(&pcm);
        assert_eq!(f.len(), 98 * 80);
        assert!(f.iter().all(|x| x.is_finite()));
    }
}
