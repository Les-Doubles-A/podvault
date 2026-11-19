use rodio::{OutputStream, OutputStreamHandle, Sink, Source};
use std::fs::File;
use std::path::Path;
use std::time::{Duration, Instant};
use symphonia::core::audio::{AudioBufferRef, SampleBuffer};
use symphonia::core::codecs::{Decoder, DecoderOptions};
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::Time;

/// Décodeur audio Symphonia personnalisé implémentant `rodio::Source`.
/// Utilise directement `std::fs::File` (seekable + taille connue), évitant les panics de rodio 0.20 sur M4A/MP4.
struct SymphoniaSource {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn Decoder>,
    track_id: u32,
    channels: u16,
    sample_rate: u32,
    total_duration: Option<Duration>,
    buffer: Vec<f32>,
    buffer_pos: usize,
}

impl SymphoniaSource {
    fn open(path: &Path) -> Result<Self, String> {
        let file = File::open(path).map_err(|e| format!("Ouverture impossible : {e}"))?;
        let mss = MediaSourceStream::new(Box::new(file), Default::default());

        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            hint.with_extension(ext);
        }

        let probed = symphonia::default::get_probe()
            .format(
                &hint,
                mss,
                &FormatOptions {
                    enable_gapless: true,
                    ..Default::default()
                },
                &MetadataOptions::default(),
            )
            .map_err(|e| format!("Format audio non reconnu : {e}"))?;

        let format = probed.format;
        let track = format
            .default_track()
            .or_else(|| {
                format
                    .tracks()
                    .iter()
                    .find(|t| t.codec_params.codec != symphonia::core::codecs::CODEC_TYPE_NULL)
            })
            .ok_or_else(|| "Aucune piste audio trouvée dans ce fichier.".to_string())?;

        let track_id = track.id;
        let params = track.codec_params.clone();
        let sample_rate = params.sample_rate.unwrap_or(44_100);
        let channels = params
            .channels
            .map(|c| c.count() as u16)
            .unwrap_or(2)
            .max(1);

        let total_duration = params.n_frames.and_then(|frames| {
            params.time_base.map(|tb| {
                let t = tb.calc_time(frames);
                Duration::from_secs(t.seconds) + Duration::from_secs_f64(t.frac)
            })
        });

        let decoder = symphonia::default::get_codecs()
            .make(&params, &DecoderOptions::default())
            .map_err(|e| format!("Codec audio non pris en charge : {e}"))?;

        let mut src = Self {
            format,
            decoder,
            track_id,
            channels,
            sample_rate,
            total_duration,
            buffer: Vec::new(),
            buffer_pos: 0,
        };
        // Décode le premier paquet pour obtenir les vraies infos (canaux / sample_rate) si absentes des headers
        let _ = src.decode_next_packet();
        Ok(src)
    }

    fn decode_next_packet(&mut self) -> bool {
        self.buffer.clear();
        self.buffer_pos = 0;

        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(SymphoniaError::ResetRequired) => {
                    self.decoder.reset();
                    continue;
                }
                Err(_) => return false,
            };

            if packet.track_id() != self.track_id {
                continue;
            }

            match self.decoder.decode(&packet) {
                Ok(decoded) => {
                    let spec = *decoded.spec();
                    self.sample_rate = spec.rate;
                    self.channels = (spec.channels.count() as u16).max(1);

                    let frames = decoded.frames();
                    if frames == 0 {
                        continue;
                    }
                    let mut sample_buf = SampleBuffer::<f32>::new(frames as u64, spec);
                    copy_interleaved(decoded, &mut sample_buf);
                    self.buffer.extend_from_slice(sample_buf.samples());
                    return true;
                }
                Err(SymphoniaError::DecodeError(_)) => {
                    // Ignore un paquet corrompu au lieu de couper la lecture
                    continue;
                }
                Err(SymphoniaError::IoError(_)) => return false,
                Err(_) => return false,
            }
        }
    }
}

fn copy_interleaved(decoded: AudioBufferRef<'_>, buf: &mut SampleBuffer<f32>) {
    buf.copy_interleaved_ref(decoded);
}

impl Iterator for SymphoniaSource {
    type Item = f32;

    fn next(&mut self) -> Option<Self::Item> {
        if self.buffer_pos >= self.buffer.len() && !self.decode_next_packet() {
            return None;
        }
        let sample = self.buffer[self.buffer_pos];
        self.buffer_pos += 1;
        Some(sample)
    }
}

impl Source for SymphoniaSource {
    fn current_frame_len(&self) -> Option<usize> {
        let rem = self.buffer.len().saturating_sub(self.buffer_pos);
        if rem > 0 {
            Some(rem)
        } else {
            None
        }
    }

    fn channels(&self) -> u16 {
        self.channels
    }

    fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    fn total_duration(&self) -> Option<Duration> {
        self.total_duration
    }

    fn try_seek(&mut self, pos: Duration) -> Result<(), rodio::source::SeekError> {
        let time = Time::new(pos.as_secs(), pos.subsec_nanos() as f64 / 1_000_000_000.0);
        match self.format.seek(
            SeekMode::Coarse,
            SeekTo::Time {
                time,
                track_id: Some(self.track_id),
            },
        ) {
            Ok(_) => {
                self.decoder.reset();
                self.buffer.clear();
                self.buffer_pos = 0;
                Ok(())
            }
            Err(_) => Err(rodio::source::SeekError::NotSupported {
                underlying_source: "SymphoniaSource",
            }),
        }
    }
}

pub struct AudioEngine {
    _stream: OutputStream,
    stream_handle: OutputStreamHandle,
    sink: Option<Sink>,
    current_path: Option<String>,
    duration_ms: i64,
    speed: f32,
    volume: f32,
    muted: bool,
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
        let p = Path::new(path);
        if !p.exists() {
            return Err("Le fichier audio est introuvable sur le disque.".into());
        }

        let open_result = std::panic::catch_unwind(|| SymphoniaSource::open(p));
        let mut source = match open_result {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => return Err(e),
            Err(_) => return Err("Erreur interne lors du décodage de ce fichier audio.".into()),
        };

        let clamped_start = if duration_ms > 0 {
            start_ms.clamp(0, duration_ms.saturating_sub(500))
        } else {
            start_ms.max(0)
        };

        if clamped_start > 0 {
            let _ = source.try_seek(Duration::from_millis(clamped_start as u64));
        }

        let sink = Sink::try_new(&self.stream_handle)
            .map_err(|e| format!("Erreur de lecture audio : {e}"))?;

        self.speed = speed.clamp(0.5, 3.0);
        self.volume = volume.clamp(0.0, 1.0);
        sink.set_speed(self.speed);
        sink.set_volume(if self.muted { 0.0 } else { self.volume });
        sink.append(source);
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
