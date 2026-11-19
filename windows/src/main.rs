#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod audio;
mod backup;
mod db;
mod model;
mod rules;
mod scanner;
mod theme;
mod ui;

use app::PodVaultApp;

fn load_window_icon() -> Option<egui::IconData> {
    const ICON_PNG: &[u8] = include_bytes!("../../app/src/main/res/drawable/ic_podvault.png");
    let img = image::load_from_memory(ICON_PNG).ok()?.to_rgba8();
    let (width, height) = img.dimensions();
    Some(egui::IconData {
        rgba: img.into_raw(),
        width,
        height,
    })
}

fn main() -> eframe::Result<()> {
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("PodVault")
        .with_inner_size([1160.0, 740.0])
        .with_min_inner_size([860.0, 560.0]);

    if let Some(icon) = load_window_icon() {
        viewport = viewport.with_icon(std::sync::Arc::new(icon));
    }

    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    eframe::run_native(
        "PodVault",
        options,
        Box::new(|cc| Ok(Box::new(PodVaultApp::new(cc)))),
    )
}
