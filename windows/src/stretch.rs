//! Changement de vitesse sans changement de hauteur (WSOLA).
//!
//! `rodio::Sink::set_speed` rééchantillonne le signal : à 1,5x les voix montent d'une quinte.
//! Ce filtre découpe l'audio en fenêtres de 30 ms (Hann, recouvrement 50 %) et avance dans
//! la source plus ou moins vite que dans la sortie ; chaque fenêtre est recalée de quelques
//! millisecondes sur la meilleure corrélation pour éviter les artefacts de phase.
//! À vitesse 1,0 la reconstruction est exacte (somme des fenêtres de Hann = 1).

use rodio::Source;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// Vitesse partagée entre le moteur audio (écriture) et le filtre (lecture).
#[derive(Clone)]
pub struct SpeedControl(Arc<AtomicU32>);

impl SpeedControl {
    pub fn new(speed: f32) -> Self {
        Self(Arc::new(AtomicU32::new(speed.to_bits())))
    }

    pub fn set(&self, speed: f32) {
        self.0.store(speed.to_bits(), Ordering::Relaxed);
    }

    fn get(&self) -> f32 {
        f32::from_bits(self.0.load(Ordering::Relaxed)).clamp(0.5, 3.0)
    }
}

pub struct TimeStretch<S: Source<Item = f32>> {
    inner: S,
    speed: SpeedControl,
    channels: usize,
    sample_rate: u32,
    win: usize,
    hop: usize,
    tolerance: i64,
    window: Vec<f32>,
    /// Échantillons entrelacés ; `base` est l'index absolu (en trames) de la première trame.
    input: Vec<f32>,
    base: i64,
    inner_done: bool,
    analysis_pos: f64,
    prev_start: Option<i64>,
    tail: Vec<f32>,
    out: Vec<f32>,
    out_pos: usize,
    finished: bool,
}

impl<S: Source<Item = f32>> TimeStretch<S> {
    pub fn new(inner: S, speed: SpeedControl) -> Self {
        let channels = (inner.channels() as usize).max(1);
        let sample_rate = inner.sample_rate().max(8_000);
        let win = ((sample_rate as usize * 30 / 1000) & !1).max(64);
        let hop = win / 2;
        let tolerance = (sample_rate as i64 * 8 / 1000).max(8);
        let window = (0..win)
            .map(|n| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * n as f32 / win as f32).cos())
            .collect();
        Self {
            inner,
            speed,
            channels,
            sample_rate,
            win,
            hop,
            tolerance,
            window,
            input: Vec::new(),
            base: 0,
            inner_done: false,
            analysis_pos: 0.0,
            prev_start: None,
            tail: vec![0.0; hop * channels],
            out: Vec::new(),
            out_pos: 0,
            finished: false,
        }
    }

    fn reset(&mut self) {
        self.input.clear();
        self.base = 0;
        self.inner_done = false;
        self.analysis_pos = 0.0;
        self.prev_start = None;
        self.tail.iter_mut().for_each(|s| *s = 0.0);
        self.out.clear();
        self.out_pos = 0;
        self.finished = false;
    }

    fn frames_end(&self) -> i64 {
        self.base + (self.input.len() / self.channels) as i64
    }

    /// Lit la source jusqu'à disposer de la trame `end` (exclue). Faux si la source est épuisée avant.
    fn fill_until(&mut self, end: i64) -> bool {
        while self.frames_end() < end && !self.inner_done {
            let missing = (end - self.frames_end()) as usize * self.channels;
            self.input.reserve(missing);
            for _ in 0..missing {
                match self.inner.next() {
                    Some(s) => self.input.push(s),
                    None => {
                        self.inner_done = true;
                        break;
                    }
                }
            }
        }
        let whole = self.input.len() / self.channels * self.channels;
        self.input.truncate(whole);
        self.frames_end() >= end
    }

    #[inline]
    fn sample(&self, frame: i64, channel: usize) -> f32 {
        self.input[(frame - self.base) as usize * self.channels + channel]
    }

    #[inline]
    fn mono(&self, frame: i64) -> f32 {
        let start = (frame - self.base) as usize * self.channels;
        self.input[start..start + self.channels].iter().sum()
    }

    /// Corrélation normalisée entre le segment candidat et la continuité naturelle du précédent.
    fn similarity(&self, candidate: i64, reference: i64, step: usize) -> f32 {
        let mut dot = 0.0f32;
        let mut energy = 0.0f32;
        let mut j = 0;
        while j < self.hop {
            let c = self.mono(candidate + j as i64);
            dot += c * self.mono(reference + j as i64);
            energy += c * c;
            j += step;
        }
        dot / (energy.sqrt() + 1e-6)
    }

    fn step(&mut self) {
        let speed = self.speed.get();
        let target = self.analysis_pos.round() as i64;
        let mut tolerance = if (speed - 1.0).abs() < 0.01 || self.prev_start.is_none() {
            0
        } else {
            self.tolerance
        };
        if !self.fill_until(target + tolerance + self.win as i64) {
            tolerance = 0;
            if !self.fill_until(target + self.win as i64) {
                // Fin de la source : on vide la dernière demi-fenêtre puis on s'arrête.
                self.out.clear();
                self.out.extend_from_slice(&self.tail);
                self.tail.iter_mut().for_each(|s| *s = 0.0);
                self.finished = true;
                return;
            }
        }

        let low = (target - tolerance).max(self.base);
        let high = target + tolerance;
        let segment = match self.prev_start {
            Some(prev) if tolerance > 0 => {
                let reference = prev + self.hop as i64;
                let mut best = target;
                let mut best_score = f32::MIN;
                let mut k = low;
                while k <= high {
                    let score = self.similarity(k, reference, 4);
                    if score > best_score {
                        best_score = score;
                        best = k;
                    }
                    k += 4;
                }
                let coarse = best;
                for k in (coarse - 3).max(low)..=(coarse + 3).min(high) {
                    let score = self.similarity(k, reference, 2);
                    if score > best_score {
                        best_score = score;
                        best = k;
                    }
                }
                best
            }
            _ => target.max(self.base),
        };

        let ch = self.channels;
        self.out.clear();
        for i in 0..self.hop {
            let w = self.window[i];
            for c in 0..ch {
                let v = self.tail[i * ch + c] + w * self.sample(segment + i as i64, c);
                self.out.push(v);
            }
        }
        for i in 0..self.hop {
            let w = self.window[self.hop + i];
            for c in 0..ch {
                self.tail[i * ch + c] = w * self.sample(segment + (self.hop + i) as i64, c);
            }
        }

        self.prev_start = Some(segment);
        self.analysis_pos += self.hop as f64 * speed as f64;

        // Oublie les trames qui ne serviront plus (ni comme candidat ni comme référence).
        let next_low = self.analysis_pos.round() as i64 - self.tolerance;
        let keep_from = (segment + self.hop as i64).min(next_low).max(self.base);
        let drop = (keep_from - self.base) as usize * ch;
        if drop > 0 {
            self.input.drain(..drop.min(self.input.len()));
            self.base = keep_from;
        }
    }
}

impl<S: Source<Item = f32>> Iterator for TimeStretch<S> {
    type Item = f32;

    fn next(&mut self) -> Option<f32> {
        while self.out_pos >= self.out.len() {
            if self.finished {
                return None;
            }
            self.step();
            self.out_pos = 0;
            if self.out.is_empty() && self.finished {
                return None;
            }
        }
        let s = self.out[self.out_pos];
        self.out_pos += 1;
        Some(s)
    }
}

impl<S: Source<Item = f32>> Source for TimeStretch<S> {
    fn current_frame_len(&self) -> Option<usize> {
        None
    }

    fn channels(&self) -> u16 {
        self.channels as u16
    }

    fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    fn total_duration(&self) -> Option<Duration> {
        None
    }

    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        self.inner.try_seek(pos)?;
        self.reset();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rodio::buffer::SamplesBuffer;

    fn sine(len: usize, rate: u32) -> Vec<f32> {
        (0..len)
            .map(|i| (2.0 * std::f32::consts::PI * 440.0 * i as f32 / rate as f32).sin() * 0.5)
            .collect()
    }

    #[test]
    fn unity_speed_reconstructs_signal() {
        let rate = 16_000;
        let data = sine(rate as usize, rate);
        let src = SamplesBuffer::new(1, rate, data.clone());
        let out: Vec<f32> = TimeStretch::new(src, SpeedControl::new(1.0)).collect();
        let stretch = TimeStretch::new(SamplesBuffer::new(1, rate, vec![0.0f32; 1]), SpeedControl::new(1.0));
        let hop = stretch.hop;
        // Après la montée initiale (une demi-fenêtre), la sortie suit l'entrée à l'identique.
        for i in hop..out.len().min(data.len()) - hop {
            assert!((out[i] - data[i]).abs() < 1e-4, "écart à l'échantillon {i}");
        }
    }

    #[test]
    fn faster_speed_shortens_output() {
        let rate = 16_000;
        let data = sine(rate as usize * 2, rate);
        let out: Vec<f32> = TimeStretch::new(SamplesBuffer::new(1, rate, data.clone()), SpeedControl::new(2.0)).collect();
        let ratio = out.len() as f32 / data.len() as f32;
        assert!((0.45..0.56).contains(&ratio), "ratio {ratio}");
        // La hauteur est conservée : toujours ~440 Hz (880 passages par zéro par seconde).
        let crossings = out.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count();
        let per_second = crossings as f32 * rate as f32 / out.len() as f32;
        assert!((840.0..920.0).contains(&per_second), "passages par zéro/s {per_second}");
    }
}
