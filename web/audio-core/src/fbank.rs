//! Kaldi-compatible 80-bin log-mel filterbank of 16 kHz audio (25 ms frames, 10 ms hop, Povey window,
//! pre-emphasis 0.97, no dither): the input the CAM++ speaker model was trained on. Port of Fbank.kt.
use std::f64::consts::PI;

pub const BINS: usize = 80;
const FRAME: usize = 400;
const HOP: usize = 160;
const NFFT: usize = 512;
const RATE: f64 = 16_000.0;
const EPS: f64 = 1.1920929e-7;

pub struct Fbank {
    window: Vec<f64>,
    cos: Vec<f64>,
    sin: Vec<f64>,
    starts: Vec<usize>,
    filters: Vec<Vec<f64>>,
}

impl Fbank {
    pub fn new() -> Self {
        let window = (0..FRAME).map(|i| (0.5 - 0.5 * (2.0 * PI * i as f64 / (FRAME - 1) as f64).cos()).powf(0.85)).collect();
        let cos = (0..NFFT / 2).map(|i| (-2.0 * PI * i as f64 / NFFT as f64).cos()).collect();
        let sin = (0..NFFT / 2).map(|i| (-2.0 * PI * i as f64 / NFFT as f64).sin()).collect();
        let mel = |f: f64| 1127.0 * (1.0 + f / 700.0).ln();
        let (lo, hi) = (mel(20.0), mel(RATE / 2.0));
        let step = (hi - lo) / (BINS + 1) as f64;
        let mut starts = Vec::with_capacity(BINS);
        let mut filters = Vec::with_capacity(BINS);
        for b in 0..BINS {
            let left = lo + b as f64 * step;
            let center = left + step;
            let right = center + step;
            let mut w = vec![0.0; NFFT / 2];
            for (k, wk) in w.iter_mut().enumerate() {
                let m = mel(k as f64 * RATE / NFFT as f64);
                if m > left && m < right {
                    *wk = if m <= center { (m - left) / (center - left) } else { (right - m) / (right - center) };
                }
            }
            let first = w.iter().position(|&x| x > 0.0).unwrap_or(0);
            let last = w.iter().rposition(|&x| x > 0.0).unwrap_or(first).max(first);
            starts.push(first);
            filters.push(w[first..=last].to_vec());
        }
        Fbank { window, cos, sin, starts, filters }
    }

    /// One 80-value row per 10 ms frame, flattened.
    pub fn compute(&self, s: &[f32]) -> Vec<f32> {
        if s.len() < FRAME { return Vec::new(); }
        let frames = 1 + (s.len() - FRAME) / HOP;
        let mut out = Vec::with_capacity(frames * BINS);
        let mut re = [0f64; NFFT];
        let mut im = [0f64; NFFT];
        let mut power = [0f64; NFFT / 2 + 1];
        for i in 0..frames {
            let off = i * HOP;
            let mean = s[off..off + FRAME].iter().map(|&x| x as f64).sum::<f64>() / FRAME as f64;
            for k in 0..FRAME {
                let cur = s[off + k] as f64 - mean;
                let prev = if k > 0 { s[off + k - 1] as f64 - mean } else { cur };
                re[k] = (cur - 0.97 * prev) * self.window[k];
            }
            re[FRAME..].fill(0.0);
            im.fill(0.0);
            self.fft(&mut re, &mut im);
            for k in 0..power.len() { power[k] = re[k] * re[k] + im[k] * im[k]; }
            for b in 0..BINS {
                let st = self.starts[b];
                let e: f64 = self.filters[b].iter().enumerate().map(|(j, w)| w * power[st + j]).sum();
                out.push(e.max(EPS).ln() as f32);
            }
        }
        out
    }

    fn fft(&self, re: &mut [f64; NFFT], im: &mut [f64; NFFT]) {
        let mut j = 0usize;
        for i in 1..NFFT {
            let mut bit = NFFT >> 1;
            while j & bit != 0 { j ^= bit; bit >>= 1; }
            j ^= bit;
            if i < j { re.swap(i, j); im.swap(i, j); }
        }
        let mut len = 2;
        while len <= NFFT {
            let half = len / 2;
            let stride = NFFT / len;
            for start in (0..NFFT).step_by(len) {
                for k in 0..half {
                    let (c, sn) = (self.cos[k * stride], self.sin[k * stride]);
                    let (a, b) = (start + k, start + k + half);
                    let tr = re[b] * c - im[b] * sn;
                    let ti = re[b] * sn + im[b] * c;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
            len *= 2;
        }
    }
}

#[cfg(test)]
mod tests {
    /// Same signal and reference values as the Android FbankTest (Python Kaldi-style reference).
    #[test]
    fn matches_python_reference() {
        use std::f64::consts::PI;
        let x: Vec<f32> = (0..8000)
            .map(|n| {
                let n = n as f64;
                (0.3 * (2.0 * PI * 440.0 * n / 16000.0).sin()
                    + 0.2 * (2.0 * PI * 1234.5 * n / 16000.0 + 0.3).sin()
                    + 0.05 * (n * n * 1e-5).sin()) as f32
            })
            .collect();
        let f = super::Fbank::new().compute(&x);
        assert_eq!(f.len(), 48 * 80);
        let at = |t: usize, b: usize| f[t * 80 + b] as f64;
        assert!((at(0, 0) - -6.208714).abs() < 2e-3);
        assert!((at(0, 10) - -5.591959).abs() < 2e-3);
        assert!((at(5, 40) - -12.062954).abs() < 2e-3);
        assert!((at(20, 79) - -15.942385).abs() < 2e-3);
    }
}
