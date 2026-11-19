use rodio::{Decoder, OutputStream, OutputStreamHandle, Sink};
use std::fs::File;
use std::io::BufReader;
use std::path::Path;
use std::time::{Duration, Instant};

pub struct AudioEngine {
    _stream: OutputStream,
    stream_handle: OutputStreamHandle,
    sink: Option<Sink>,
    current_path: Option<String>,
    duration_ms: i64,
    speed: f32,
    volume: f32,
    muted: bool,
    // Suivi précis de la position (indépendant des aléas de get_pos après seek/speed)
    base_position_ms: i64,
    anchor_instant: Option<Instant>,
    paused: bool,
    paused_at: Option<Instant>,
}

impl AudioEngine {
    pub fn new() -> Result<Self, String> {
        let (stream, stream_handle) = OutputStream::try_default()
            .map_err(|e| format!("Impossible d'initialiser la sortie audio : {e}"))?;
        Ok(Self {
            _stream: stream,
            stream_handle,
            sink: None,
            current_path: None,
            duration_ms: 0,
            speed: 1.0,
            volume: 1.0,
            muted: false,
            base_position_ms: 0,
            anchor_instant: None,
            paused: true,
            paused_at: None,
        })
    }

    pub fn play_file(
        &mut self,
        path: &str,
        start_ms: i64,
        duration_ms: i64,
        speed: f32,
        volume: f32,
    ) -> Result<(), String> {
        if !Path::new(path).exists() {
            return Err("Le fichier audio est introuvable sur le disque.".into());
        }
        let file = File::open(path).map_err(|e| format!("Ouverture impossible : {e}"))?;
        let source = Decoder::new(BufReader::new(file))
            .map_err(|e| format!("Format audio non pris en charge : {e}"))?;

        let sink = Sink::try_new(&self.stream_handle)
            .map_err(|e| format!("Erreur de lecture audio : {e}"))?;

        sink.append(source);
        self.speed = speed.clamp(0.5, 3.0);
        self.volume = volume.clamp(0.0, 1.0);
        sink.set_speed(self.speed);
        sink.set_volume(if self.muted { 0.0 } else { self.volume });

        let clamped_start = if duration_ms > 0 {
            start_ms.clamp(0, duration_ms.saturating_sub(500))
        } else {
            start_ms.max(0)
        };

        if clamped_start > 0 {
            let _ = sink.try_seek(Duration::from_millis(clamped_start as u64));
        }

        sink.play();
        if let Some(old) = self.sink.take() {
            old.stop();
        }
        self.sink = Some(sink);
        self.current_path = Some(path.to_string());
        self.duration_ms = duration_ms;
        self.base_position_ms = clamped_start;
        self.anchor_instant = Some(Instant::now());
        self.paused = false;
        self.paused_at = None;
        Ok(())
    }

    pub fn is_playing(&self) -> bool {
        self.sink.is_some() && !self.paused && !self.is_finished()
    }

    pub fn is_loaded(&self) -> bool {
        self.sink.is_some()
    }

    pub fn is_finished(&self) -> bool {
        self.sink.as_ref().map(|s| s.empty()).unwrap_or(false)
    }

    pub fn pause(&mut self) {
        if self.paused {
            return;
        }
        let pos = self.position_ms();
        self.base_position_ms = pos;
        self.anchor_instant = None;
        self.paused = true;
        self.paused_at = Some(Instant::now());
        if let Some(sink) = &self.sink {
            sink.pause();
        }
    }

    /// Reprend la lecture. Retourne la durée de la pause en ms si une pause était active.
    pub fn resume(&mut self) -> Option<i64> {
        if !self.paused || self.sink.is_none() {
            return None;
        }
        let paused_for_ms = self.paused_at.map(|t| t.elapsed().as_millis() as i64);
        if let Some(sink) = &self.sink {
            sink.play();
        }
        self.paused = false;
        self.paused_at = None;
        self.anchor_instant = Some(Instant::now());
        paused_for_ms
    }

    pub fn stop(&mut self) {
        if let Some(sink) = self.sink.take() {
            sink.stop();
        }
        self.current_path = None;
        self.base_position_ms = 0;
        self.anchor_instant = None;
        self.paused = true;
        self.paused_at = None;
    }

    pub fn seek_ms(&mut self, target_ms: i64) {
        let clamped = if self.duration_ms > 0 {
            target_ms.clamp(0, self.duration_ms)
        } else {
            target_ms.max(0)
        };
        if let Some(sink) = &self.sink {
            if sink.try_seek(Duration::from_millis(clamped as u64)).is_ok() {
                self.base_position_ms = clamped;
                if !self.paused {
                    self.anchor_instant = Some(Instant::now());
                }
            }
        }
    }

    pub fn set_speed(&mut self, speed: f32) {
        let clamped = speed.clamp(0.5, 3.0);
        let pos = self.position_ms();
        self.base_position_ms = pos;
        if !self.paused {
            self.anchor_instant = Some(Instant::now());
        }
        self.speed = clamped;
        if let Some(sink) = &self.sink {
            sink.set_speed(clamped);
        }
    }

    pub fn set_volume(&mut self, volume: f32) {
        self.volume = volume.clamp(0.0, 1.0);
        if self.volume > 0.0 {
            self.muted = false;
        }
        if let Some(sink) = &self.sink {
            sink.set_volume(if self.muted { 0.0 } else { self.volume });
        }
    }

    pub fn toggle_mute(&mut self) {
        self.muted = !self.muted;
        if let Some(sink) = &self.sink {
            sink.set_volume(if self.muted { 0.0 } else { self.volume });
        }
    }

    pub fn is_muted(&self) -> bool {
        self.muted
    }

    pub fn volume(&self) -> f32 {
        self.volume
    }

    pub fn speed(&self) -> f32 {
        self.speed
    }

    pub fn position_ms(&self) -> i64 {
        let elapsed = if self.paused {
            0
        } else {
            self.anchor_instant
                .map(|a| (a.elapsed().as_secs_f64() * (self.speed as f64) * 1000.0) as i64)
                .unwrap_or(0)
        };
        let pos = self.base_position_ms + elapsed;
        if self.duration_ms > 0 {
            pos.clamp(0, self.duration_ms)
        } else {
            pos.max(0)
        }
    }
}
