use crate::model::{Bookmark, Episode, Podcast, Preferences, SleepTimerMode};
use crate::theme::Palette;
use crate::ui::widgets::{
    accent_button, card_frame, cover_art, format_duration_ms, now_playing_bars, pill_badge,
    CoverCache,
};
use crate::ui::{AppAction, Screen};
use egui::{Color32, CornerRadius, Margin, RichText, ScrollArea, Slider, Stroke, Ui};
use std::collections::HashMap;

#[allow(clippy::too_many_arguments)]
pub fn render_player_screen(
    ui: &mut Ui,
    p: Palette,
    episode: Option<&Episode>,
    podcast: Option<&Podcast>,
    bookmarks: &[Bookmark],
    position_ms: i64,
    is_playing: bool,
    speed: f32,
    volume: f32,
    muted: bool,
    sleep_timer: SleepTimerMode,
    queue_len: usize,
    prefs: &Preferences,
    covers: &mut CoverCache,
    resume_candidate: Option<&Episode>,
    actions: &mut Vec<AppAction>,
) {
    let Some(ep) = episode else {
        ui.vertical_centered(|ui| {
            ui.add_space(60.0);
            ui.heading("Aucun épisode en cours de lecture");
            ui.add_space(4.0);
            if let Some(last) = resume_candidate {
                // Proposer directement la reprise plutôt qu'un écran vide.
                ui.label(
                    RichText::new("Reprenez là où vous vous étiez arrêté :")
                        .color(p.on_surface_variant),
                );
                ui.add_space(14.0);
                cover_art(ui, covers, last.display_cover(), last.display_title(), 160.0, 14);
                ui.add_space(10.0);
                ui.label(RichText::new(last.display_title()).size(16.0).strong());
                ui.add_space(12.0);
                if accent_button(ui, p, "▶  Reprendre la lecture").clicked() {
                    actions.push(AppAction::PlayEpisode {
                        episode_id: last.id,
                        position_ms: None,
                    });
                }
                ui.add_space(6.0);
                if ui.button("Parcourir la bibliothèque").clicked() {
                    actions.push(AppAction::Navigate(Screen::Library));
                }
            } else {
                ui.label(
                    RichText::new("Choisissez un épisode depuis l'Accueil ou la Bibliothèque.")
                        .color(p.on_surface_variant),
                );
                ui.add_space(12.0);
                if accent_button(ui, p, "Aller à la bibliothèque").clicked() {
                    actions.push(AppAction::Navigate(Screen::Library));
                }
            }
        });
        return;
    };

    let cover = ep
        .display_cover()
        .or_else(|| podcast.and_then(|pod| pod.display_cover()));

    // Mode ambiance : halo teinté par la couleur dominante de la pochette
    if prefs.ambient_mode {
        let dom = covers.color_for(ui.ctx(), cover, p.accent);
        let rect = ui.available_rect_before_wrap();
        let tint = Color32::from_rgba_unmultiplied(dom.r(), dom.g(), dom.b(), 34);
        ui.painter().rect_filled(rect, CornerRadius::same(16), tint);
    }

    ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
        ui.horizontal(|ui| {
            if let Some(pod) = podcast {
                if ui.button(format!("← {}", pod.display_title())).clicked() {
                    actions.push(AppAction::Navigate(Screen::PodcastDetail(pod.id)));
                }
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .button(format!("☰ File d'attente ({queue_len})"))
                    .clicked()
                {
                    actions.push(AppAction::Navigate(Screen::Queue));
                }
                if ui
                    .button("📖 Dictionnaire")
                    .on_hover_text("Rechercher la définition d'un mot")
                    .clicked()
                {
                    actions.push(AppAction::Navigate(Screen::Dictionary));
                }
                let fav_label = if ep.is_favorite {
                    "♥ Favori"
                } else {
                    "♡ Ajouter aux favoris"
                };
                if ui.button(fav_label).clicked() {
                    actions.push(AppAction::ToggleFavorite(ep.id));
                }
            });
        });

        ui.add_space(14.0);

        ui.vertical_centered(|ui| {
            cover_art(ui, covers, cover, ep.display_title(), 230.0, 16);
            ui.add_space(14.0);

            ui.heading(RichText::new(ep.display_title()).size(22.0).strong());
            if let Some(pod) = podcast {
                ui.label(
                    RichText::new(pod.display_title())
                        .color(p.accent)
                        .size(15.0),
                );
            }
            ui.add_space(12.0);

            // Barre de progression interactive
            let dur = ep.duration_ms.max(1);
            let mut pos_secs = (position_ms as f64) / 1000.0;
            let dur_secs = (dur as f64) / 1000.0;

            ui.scope(|ui| {
                ui.spacing_mut().slider_width = (ui.available_width() - 80.0).clamp(240.0, 620.0);
                let slider = Slider::new(&mut pos_secs, 0.0..=dur_secs)
                    .show_value(false)
                    .trailing_fill(true);
                let resp = ui.add(slider);
                if resp.changed() || resp.drag_stopped() {
                    actions.push(AppAction::SeekMs((pos_secs * 1000.0) as i64));
                }
            });

            ui.horizontal(|ui| {
                let remaining = (dur - position_ms).max(0);
                ui.label(
                    RichText::new(format_duration_ms(position_ms))
                        .color(p.on_surface_variant)
                        .size(12.5),
                );
                ui.add_space(20.0);
                ui.label(
                    RichText::new(format!("-{}", format_duration_ms(remaining)))
                        .color(p.on_surface_variant)
                        .size(12.5),
                );
            });

            ui.add_space(12.0);

            // Contrôles de transport
            ui.horizontal(|ui| {
                ui.add_space((ui.available_width() - 360.0).max(0.0) / 2.0);
                if ui
                    .button(format!("⏪ -{}s", prefs.rewind_seconds))
                    .clicked()
                {
                    actions.push(AppAction::SkipBack);
                }
                ui.add_space(8.0);
                let play_label = if is_playing {
                    "  ⏸ Pause  "
                } else {
                    "  ▶ Lecture  "
                };
                if accent_button(ui, p, play_label).clicked() {
                    actions.push(AppAction::TogglePlayPause);
                }
                ui.add_space(8.0);
                if ui
                    .button(format!("+{}s ⏩", prefs.forward_seconds))
                    .clicked()
                {
                    actions.push(AppAction::SkipForward);
                }
                if queue_len > 0 {
                    ui.add_space(8.0);
                    if ui.button("⏭ Suivant").clicked() {
                        actions.push(AppAction::PlayNextInQueue);
                    }
                }
            });

            ui.add_space(16.0);
        });

        // Vitesse, Volume, Minuterie et Point mémoire
        card_frame(p).show(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new("Vitesse :").strong());
                for sp in [0.75_f32, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5] {
                    let selected = (speed - sp).abs() < 0.01;
                    let txt = format!("{sp:.2}×")
                        .trim_end_matches('0')
                        .trim_end_matches('.')
                        .to_string();
                    if ui.selectable_label(selected, txt).clicked() {
                        actions.push(AppAction::SetSpeed(sp));
                    }
                }
            });

            ui.add_space(8.0);
            ui.horizontal(|ui| {
                let vol_icon = if muted || volume <= 0.01 { "🔇" } else { "🔊" };
                if ui.button(vol_icon).clicked() {
                    actions.push(AppAction::ToggleMute);
                }
                let mut vol_pct = (volume * 100.0).round();
                if ui
                    .add(Slider::new(&mut vol_pct, 0.0..=100.0).suffix(" %"))
                    .changed()
                {
                    actions.push(AppAction::SetVolume(vol_pct / 100.0));
                }
            });

            ui.add_space(8.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new("Minuterie d'arrêt :").strong());
                let presets = [
                    ("Désactivée", SleepTimerMode::Off),
                    ("5 min", SleepTimerMode::Countdown(5 * 60)),
                    ("15 min", SleepTimerMode::Countdown(15 * 60)),
                    ("30 min", SleepTimerMode::Countdown(30 * 60)),
                    ("45 min", SleepTimerMode::Countdown(45 * 60)),
                    ("60 min", SleepTimerMode::Countdown(60 * 60)),
                    ("Fin de l'épisode", SleepTimerMode::EndOfEpisode),
                ];
                for (lbl, mode) in presets {
                    let active = match (sleep_timer, mode) {
                        (SleepTimerMode::Off, SleepTimerMode::Off) => true,
                        (SleepTimerMode::EndOfEpisode, SleepTimerMode::EndOfEpisode) => true,
                        _ => false,
                    };
                    if ui.selectable_label(active, lbl).clicked() {
                        actions.push(AppAction::SetSleepTimer(mode));
                    }
                }
                match sleep_timer {
                    SleepTimerMode::Countdown(secs) => {
                        pill_badge(
                            ui,
                            p.primary_container,
                            p.on_primary_container,
                            &format!("⏱ {}:{:02}", secs / 60, secs % 60),
                        );
                    }
                    SleepTimerMode::EndOfEpisode => {
                        pill_badge(
                            ui,
                            p.primary_container,
                            p.on_primary_container,
                            "⏱ Arrêt en fin d'épisode",
                        );
                    }
                    SleepTimerMode::Off => {}
                }
            });

            ui.add_space(10.0);
            ui.horizontal(|ui| {
                if accent_button(
                    ui,
                    p,
                    &format!("🔖 Créer un point mémoire à {}", format_duration_ms(position_ms)),
                )
                .clicked()
                {
                    actions.push(AppAction::OpenAddBookmark(ep.id, position_ms));
                }
            });
        });

        // Points mémoire de cet épisode
        let ep_bookmarks: Vec<&Bookmark> = bookmarks
            .iter()
            .filter(|b| b.episode_id == ep.id)
            .collect();
        if !ep_bookmarks.is_empty() {
            ui.add_space(12.0);
            ui.label(
                RichText::new("Points mémoire de cet épisode")
                    .size(16.0)
                    .strong(),
            );
            ui.add_space(4.0);
            for bm in ep_bookmarks {
                card_frame(p).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        if ui
                            .button(format!("▶ {}", format_duration_ms(bm.position_ms)))
                            .clicked()
                        {
                            actions.push(AppAction::SeekMs(bm.position_ms));
                        }
                        ui.vertical(|ui| {
                            if let Some(t) = &bm.title {
                                ui.label(RichText::new(t).strong());
                            }
                            if let Some(n) = &bm.note {
                                ui.label(RichText::new(n).color(p.on_surface_variant).size(12.5));
                            }
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("🗑").clicked() {
                                actions.push(AppAction::DeleteBookmark(bm.id));
                            }
                        });
                    });
                });
                ui.add_space(4.0);
            }
        }

        if let Some(desc) = &ep.description {
            if !desc.trim().is_empty() {
                ui.add_space(12.0);
                card_frame(p).show(ui, |ui| {
                    ui.label(RichText::new("Notes de l'épisode").strong());
                    ui.add_space(4.0);
                    ui.label(RichText::new(desc).color(p.on_surface_variant));
                });
            }
        }
        ui.add_space(16.0);
    });
}

#[allow(clippy::too_many_arguments)]
pub fn render_mini_player(
    ui: &mut Ui,
    p: Palette,
    covers: &mut CoverCache,
    episode: &Episode,
    podcast: Option<&Podcast>,
    position_ms: i64,
    is_playing: bool,
    volume: f32,
    muted: bool,
    sleep_timer: SleepTimerMode,
    queue_len: usize,
    prefs: &Preferences,
    actions: &mut Vec<AppAction>,
) {
    let cover = episode
        .display_cover()
        .or_else(|| podcast.and_then(|pod| pod.display_cover()));

    egui::Frame::new()
        .fill(p.surface_container)
        .stroke(Stroke::new(1.0_f32, p.outline.linear_multiply(0.5)))
        .inner_margin(Margin::symmetric(14, 10))
        .show(ui, |ui| {
            // Fine barre de progression en haut
            let frac = if episode.duration_ms > 0 {
                (position_ms as f32 / episode.duration_ms as f32).clamp(0.0, 1.0)
            } else {
                0.0
            };
            ui.add(
                egui::ProgressBar::new(frac)
                    .fill(p.accent)
                    .desired_height(3.5),
            );
            ui.add_space(6.0);

            ui.horizontal(|ui| {
                let cov = cover_art(ui, covers, cover, episode.display_title(), 44.0, 6);
                if cov.clicked() {
                    actions.push(AppAction::Navigate(Screen::Player));
                }
                ui.add_space(8.0);
                ui.vertical(|ui| {
                    ui.horizontal(|ui| {
                        now_playing_bars(ui, p, is_playing);
                        let lbl = ui.add(
                            egui::Label::new(
                                RichText::new(episode.display_title()).strong().size(13.5),
                            )
                            .truncate()
                            .sense(egui::Sense::click()),
                        );
                        if lbl.clicked() {
                            actions.push(AppAction::Navigate(Screen::Player));
                        }
                    });
                    let sub = match podcast {
                        Some(pod) => format!(
                            "{} · {} / {}",
                            pod.display_title(),
                            format_duration_ms(position_ms),
                            format_duration_ms(episode.duration_ms)
                        ),
                        None => format!(
                            "{} / {}",
                            format_duration_ms(position_ms),
                            format_duration_ms(episode.duration_ms)
                        ),
                    };
                    ui.label(RichText::new(sub).color(p.on_surface_variant).size(11.5));
                });

                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .button(format!("☰ ({queue_len})"))
                        .on_hover_text("File d'attente")
                        .clicked()
                    {
                        actions.push(AppAction::Navigate(Screen::Queue));
                    }
                    if ui
                        .button("⤢")
                        .on_hover_text("Ouvrir le lecteur complet")
                        .clicked()
                    {
                        actions.push(AppAction::Navigate(Screen::Player));
                    }

                    let mut vol = volume;
                    ui.scope(|ui| {
                        ui.spacing_mut().slider_width = 75.0;
                        if ui
                            .add(Slider::new(&mut vol, 0.0..=1.0).show_value(false))
                            .changed()
                        {
                            actions.push(AppAction::SetVolume(vol));
                        }
                    });
                    if ui
                        .button(if muted || volume <= 0.01 { "🔇" } else { "🔊" })
                        .clicked()
                    {
                        actions.push(AppAction::ToggleMute);
                    }

                    if let SleepTimerMode::Countdown(secs) = sleep_timer {
                        pill_badge(
                            ui,
                            p.primary_container,
                            p.on_primary_container,
                            &format!("⏱ {}:{:02}", secs / 60, secs % 60),
                        );
                    }

                    if ui
                        .button(format!("+{}s", prefs.forward_seconds))
                        .clicked()
                    {
                        actions.push(AppAction::SkipForward);
                    }
                    if accent_button(ui, p, if is_playing { "⏸" } else { "▶" }).clicked() {
                        actions.push(AppAction::TogglePlayPause);
                    }
                    if ui
                        .button(format!("-{}s", prefs.rewind_seconds))
                        .clicked()
                    {
                        actions.push(AppAction::SkipBack);
                    }
                });
            });
        });
}

pub fn render_queue_screen(
    ui: &mut Ui,
    p: Palette,
    queue: &[i64],
    episodes: &[Episode],
    podcasts: &[Podcast],
    actions: &mut Vec<AppAction>,
) {
    let ep_map: HashMap<i64, &Episode> = episodes.iter().map(|e| (e.id, e)).collect();
    let pod_map: HashMap<i64, &Podcast> = podcasts.iter().map(|pod| (pod.id, pod)).collect();

    ui.horizontal(|ui| {
        ui.heading(RichText::new("File d'attente").size(26.0).strong());
        ui.label(
            RichText::new(format!("· {} épisode(s)", queue.len())).color(p.on_surface_variant),
        );
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if !queue.is_empty() && ui.button("Vider la file").clicked() {
                actions.push(AppAction::QueueClear);
            }
        });
    });
    ui.add_space(10.0);

    if queue.is_empty() {
        card_frame(p).show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(
                RichText::new("La file d'attente est vide. Ajoutez des épisodes avec « Lire ensuite » ou « + File ».")
                    .color(p.on_surface_variant),
            );
        });
        return;
    }

    ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
        for (idx, &ep_id) in queue.iter().enumerate() {
            let Some(ep) = ep_map.get(&ep_id) else {
                continue;
            };
            let pod = pod_map.get(&ep.podcast_id).copied();
            card_frame(p).show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(format!("{}.", idx + 1))
                            .color(p.accent)
                            .strong(),
                    );
                    ui.vertical(|ui| {
                        ui.label(RichText::new(ep.display_title()).strong());
                        let sub = match pod {
                            Some(pod) => format!(
                                "{} · {}",
                                pod.display_title(),
                                format_duration_ms(ep.duration_ms)
                            ),
                            None => format_duration_ms(ep.duration_ms),
                        };
                        ui.label(RichText::new(sub).color(p.on_surface_variant).size(12.0));
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("✕").on_hover_text("Retirer").clicked() {
                            actions.push(AppAction::QueueRemoveAt(idx));
                        }
                        if idx + 1 < queue.len() && ui.button("↓").clicked() {
                            actions.push(AppAction::QueueMoveDown(idx));
                        }
                        if idx > 0 && ui.button("↑").clicked() {
                            actions.push(AppAction::QueueMoveUp(idx));
                        }
                        if ui.button("▶").on_hover_text("Lire maintenant").clicked() {
                            actions.push(AppAction::QueueRemoveAt(idx));
                            actions.push(AppAction::PlayEpisode {
                                episode_id: ep.id,
                                position_ms: None,
                            });
                        }
                    });
                });
            });
            ui.add_space(6.0);
        }
    });
}
