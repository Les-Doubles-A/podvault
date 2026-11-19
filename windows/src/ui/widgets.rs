use crate::theme::Palette;
use egui::{Color32, CornerRadius, Margin, Rect, Response, RichText, Sense, Stroke, Ui, Vec2};
use std::collections::HashMap;
use std::path::Path;

pub fn format_duration_ms(ms: i64) -> String {
    let total_secs = (ms.max(0) / 1000) as u64;
    let h = total_secs / 3600;
    let m = (total_secs % 3600) / 60;
    let s = total_secs % 60;
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

pub fn format_human_duration_ms(ms: i64) -> String {
    let total_mins = (ms.max(0) / 60_000) as u64;
    let h = total_mins / 60;
    let m = total_mins % 60;
    if h > 0 {
        format!("{h} h {m:02} min")
    } else if m > 0 {
        format!("{m} min")
    } else {
        let s = (ms.max(0) / 1000) as u64;
        format!("{s} s")
    }
}

pub fn format_file_size(bytes: i64) -> String {
    let b = bytes.max(0) as f64;
    if b >= 1_073_741_824.0 {
        format!("{:.2} Go", b / 1_073_741_824.0)
    } else if b >= 1_048_576.0 {
        format!("{:.1} Mo", b / 1_048_576.0)
    } else if b >= 1024.0 {
        format!("{:.0} Ko", b / 1024.0)
    } else {
        format!("{bytes} o")
    }
}

pub fn card_frame(p: Palette) -> egui::Frame {
    egui::Frame::new()
        .fill(p.surface)
        .stroke(Stroke::new(1.0_f32, p.outline.linear_multiply(0.45)))
        .corner_radius(CornerRadius::same(12))
        .inner_margin(Margin::same(14))
}

pub fn accent_button(ui: &mut Ui, p: Palette, text: &str) -> Response {
    let btn = egui::Button::new(
        RichText::new(text)
            .color(Color32::from_rgb(0x05, 0x1E, 0x1B))
            .strong(),
    )
    .fill(p.accent)
    .corner_radius(CornerRadius::same(8));
    ui.add(btn)
}

pub fn pill_badge(ui: &mut Ui, bg: Color32, fg: Color32, text: &str) {
    egui::Frame::new()
        .fill(bg)
        .corner_radius(CornerRadius::same(6))
        .inner_margin(Margin::symmetric(8, 3))
        .show(ui, |ui| {
            ui.label(RichText::new(text).color(fg).size(11.5).strong());
        });
}

/// Indicateur animé (3 barres verticales) pour l'épisode ou le podcast en cours de lecture.
pub fn now_playing_bars(ui: &mut Ui, p: Palette, is_playing: bool) {
    let desired = Vec2::new(18.0, 16.0);
    let (rect, _) = ui.allocate_exact_size(desired, Sense::hover());
    let painter = ui.painter();
    let t = ui.input(|i| i.time);
    let bar_w = 4.0;
    let gap = 2.5;
    for i in 0..3 {
        let phase = (t * 6.5 + (i as f64) * 1.7).sin() * 0.5 + 0.5;
        let h = if is_playing {
            4.0 + (phase as f32) * (rect.height() - 4.0)
        } else {
            4.0
        };
        let x = rect.left() + (i as f32) * (bar_w + gap);
        let bar_rect = Rect::from_min_max(
            egui::pos2(x, rect.bottom() - h),
            egui::pos2(x + bar_w, rect.bottom()),
        );
        painter.rect_filled(bar_rect, CornerRadius::same(2), p.accent);
    }
    if is_playing {
        ui.ctx().request_repaint();
    }
}

/// Cache des couleurs dominantes des pochettes (pour le mode ambiance).
pub struct CoverAccentCache {
    colors: HashMap<String, Color32>,
}

impl Default for CoverAccentCache {
    fn default() -> Self {
        Self {
            colors: HashMap::new(),
        }
    }
}

impl CoverAccentCache {
    pub fn color_for(&mut self, cover_path: Option<&str>, fallback: Color32) -> Color32 {
        let Some(path) = cover_path else {
            return fallback;
        };
        if let Some(&c) = self.colors.get(path) {
            return c;
        }
        let computed = extract_dominant_color(Path::new(path)).unwrap_or(fallback);
        self.colors.insert(path.to_string(), computed);
        computed
    }
}

fn extract_dominant_color(path: &Path) -> Option<Color32> {
    let img = image::open(path).ok()?.thumbnail(32, 32).to_rgb8();
    let (mut r_sum, mut g_sum, mut b_sum, mut count) = (0u64, 0u64, 0u64, 0u64);
    for px in img.pixels() {
        let [r, g, b] = px.0;
        let max = r.max(g).max(b) as i32;
        let min = r.min(g).min(b) as i32;
        // Favorise les pixels un peu saturés
        let weight = if max - min > 25 { 3 } else { 1 };
        r_sum += (r as u64) * weight;
        g_sum += (g as u64) * weight;
        b_sum += (b as u64) * weight;
        count += weight;
    }
    if count == 0 {
        return None;
    }
    Some(Color32::from_rgb(
        (r_sum / count) as u8,
        (g_sum / count) as u8,
        (b_sum / count) as u8,
    ))
}

fn cover_fallback_color(title: &str) -> Color32 {
    let mut hash: u32 = 2166136261;
    for b in title.bytes() {
        hash ^= b as u32;
        hash = hash.wrapping_mul(16777619);
    }
    const PALETTE: &[(u8, u8, u8)] = &[
        (0x16, 0x69, 0x62),
        (0x1E, 0x4E, 0x79),
        (0x4F, 0x3A, 0x73),
        (0x7A, 0x3B, 0x4E),
        (0x7C, 0x52, 0x26),
        (0x28, 0x62, 0x4A),
    ];
    let (r, g, b) = PALETTE[(hash as usize) % PALETTE.len()];
    Color32::from_rgb(r, g, b)
}

fn initials(title: &str) -> String {
    let words: Vec<&str> = title.split_whitespace().collect();
    if words.is_empty() {
        return "PV".into();
    }
    if words.len() == 1 {
        return words[0].chars().take(2).collect::<String>().to_uppercase();
    }
    let a = words[0].chars().next().unwrap_or('P');
    let b = words[1].chars().next().unwrap_or('V');
    format!("{a}{b}").to_uppercase()
}

/// Affiche une pochette carrée arrondie (image locale si disponible, sinon tuile typographique).
pub fn cover_art(
    ui: &mut Ui,
    cover_path: Option<&str>,
    title: &str,
    size: f32,
    rounding: u8,
) -> Response {
    let desired = Vec2::splat(size);
    if let Some(path) = cover_path {
        if Path::new(path).is_file() {
            let uri = format!("file://{}", path.replace('\\', "/"));
            return ui.add(
                egui::Image::new(uri)
                    .fit_to_exact_size(desired)
                    .corner_radius(CornerRadius::same(rounding)),
            );
        }
    }

    let (rect, resp) = ui.allocate_exact_size(desired, Sense::click());
    let bg = cover_fallback_color(title);
    ui.painter()
        .rect_filled(rect, CornerRadius::same(rounding), bg);
    ui.painter().text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        initials(title),
        egui::FontId::proportional((size * 0.32).clamp(13.0, 48.0)),
        Color32::from_rgba_unmultiplied(255, 255, 255, 225),
    );
    resp
}
