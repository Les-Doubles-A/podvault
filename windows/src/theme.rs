use crate::model::ThemeMode;
use egui::{Color32, CornerRadius, Stroke, Style, Visuals};

#[derive(Debug, Clone, Copy)]
pub struct Palette {
    pub accent: Color32,
    pub background: Color32,
    pub surface: Color32,
    pub surface_variant: Color32,
    pub surface_container: Color32,
    pub surface_container_high: Color32,
    pub on_surface: Color32,
    pub on_surface_variant: Color32,
    pub primary_container: Color32,
    pub on_primary_container: Color32,
    pub outline: Color32,
    pub error: Color32,
}

impl Palette {
    pub fn for_mode(mode: ThemeMode) -> Self {
        match mode {
            ThemeMode::Dark => Self {
                accent: Color32::from_rgb(0x3E, 0xD9, 0xC5),
                background: Color32::from_rgb(0x0E, 0x10, 0x13),
                surface: Color32::from_rgb(0x17, 0x1A, 0x1F),
                surface_variant: Color32::from_rgb(0x20, 0x24, 0x2B),
                surface_container: Color32::from_rgb(0x1B, 0x1F, 0x25),
                surface_container_high: Color32::from_rgb(0x23, 0x28, 0x30),
                on_surface: Color32::from_rgb(0xED, 0xEF, 0xF2),
                on_surface_variant: Color32::from_rgb(0x9A, 0xA1, 0xAC),
                primary_container: Color32::from_rgb(0x0F, 0x4F, 0x47),
                on_primary_container: Color32::from_rgb(0xB8, 0xF5, 0xEC),
                outline: Color32::from_rgb(0x3A, 0x41, 0x4B),
                error: Color32::from_rgb(0xFF, 0x8A, 0x80),
            },
            ThemeMode::Light => Self {
                accent: Color32::from_rgb(0x1F, 0x9C, 0x8C),
                background: Color32::from_rgb(0xFA, 0xFA, 0xFA),
                surface: Color32::from_rgb(0xFF, 0xFF, 0xFF),
                surface_variant: Color32::from_rgb(0xF0, 0xF1, 0xF3),
                surface_container: Color32::from_rgb(0xF3, 0xF5, 0xF6),
                surface_container_high: Color32::from_rgb(0xEB, 0xEE, 0xEF),
                on_surface: Color32::from_rgb(0x14, 0x16, 0x1A),
                on_surface_variant: Color32::from_rgb(0x5B, 0x61, 0x6B),
                primary_container: Color32::from_rgb(0xC4, 0xF3, 0xEC),
                on_primary_container: Color32::from_rgb(0x00, 0x20, 0x1C),
                outline: Color32::from_rgb(0xC3, 0xC8, 0xCE),
                error: Color32::from_rgb(0xB3, 0x26, 0x1E),
            },
        }
    }
}

pub fn apply_theme(ctx: &egui::Context, mode: ThemeMode) {
    let p = Palette::for_mode(mode);
    let mut visuals = match mode {
        ThemeMode::Dark => Visuals::dark(),
        ThemeMode::Light => Visuals::light(),
    };

    visuals.panel_fill = p.background;
    visuals.window_fill = p.surface;
    visuals.extreme_bg_color = p.surface_container;
    visuals.faint_bg_color = p.surface_variant;
    visuals.selection.bg_fill = p.primary_container;
    visuals.selection.stroke = Stroke::new(1.0_f32, p.accent);
    visuals.hyperlink_color = p.accent;
    visuals.override_text_color = Some(p.on_surface);

    visuals.widgets.noninteractive.bg_fill = p.surface;
    visuals.widgets.noninteractive.weak_bg_fill = p.surface_container;
    visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, p.outline.linear_multiply(0.5));
    visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, p.on_surface);
    visuals.widgets.noninteractive.corner_radius = CornerRadius::same(8);

    visuals.widgets.inactive.bg_fill = p.surface_variant;
    visuals.widgets.inactive.weak_bg_fill = p.surface_variant;
    visuals.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, p.outline.linear_multiply(0.45));
    visuals.widgets.inactive.fg_stroke = Stroke::new(1.0_f32, p.on_surface);
    visuals.widgets.inactive.corner_radius = CornerRadius::same(8);

    visuals.widgets.hovered.bg_fill = p.surface_container_high;
    visuals.widgets.hovered.weak_bg_fill = p.surface_container_high;
    visuals.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, p.accent);
    visuals.widgets.hovered.fg_stroke = Stroke::new(1.0_f32, p.on_surface);
    visuals.widgets.hovered.corner_radius = CornerRadius::same(8);

    visuals.widgets.active.bg_fill = p.primary_container;
    visuals.widgets.active.weak_bg_fill = p.primary_container;
    visuals.widgets.active.bg_stroke = Stroke::new(1.5_f32, p.accent);
    visuals.widgets.active.fg_stroke = Stroke::new(1.0_f32, p.on_primary_container);
    visuals.widgets.active.corner_radius = CornerRadius::same(8);

    let mut style = Style::default();
    style.visuals = visuals;
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    style.spacing.button_padding = egui::vec2(12.0, 6.0);

    // egui suit par défaut le thème de Windows et garde un style par thème : `set_style` seul
    // ne remplaçait que l'un des deux. Avec Windows en clair et PodVault en sombre, les textes
    // et boutons restaient en style clair (texte noir sur fond sombre). On fixe donc le thème
    // choisi dans PodVault et on applique la palette aux deux styles.
    let theme = match mode {
        ThemeMode::Dark => egui::Theme::Dark,
        ThemeMode::Light => egui::Theme::Light,
    };
    ctx.set_theme(theme);
    ctx.set_style_of(egui::Theme::Dark, style.clone());
    ctx.set_style_of(egui::Theme::Light, style);
}
