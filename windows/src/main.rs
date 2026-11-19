#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod audio;
mod backup;
mod db;
mod dictionary;
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

fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let log_dir = directories::ProjectDirs::from("com", "podvault", "PodVault")
            .map(|d| d.data_local_dir().to_path_buf())
            .unwrap_or_else(|| std::path::PathBuf::from("."));
        let _ = std::fs::create_dir_all(&log_dir);
        let log_path = log_dir.join("crash.log");
        let ts = chrono::Local::now().format("%Y-%m-%d %H:%M:%S");
        let msg = format!("[{ts}] PANIC: {info}\n");
        let _ = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_path)
            .and_then(|mut f| std::io::Write::write_all(&mut f, msg.as_bytes()));
    }));
}

fn main() -> eframe::Result<()> {
    install_panic_hook();

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
