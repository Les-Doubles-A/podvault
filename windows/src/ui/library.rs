use crate::db::Database;
use crate::model::{Episode, EpisodeFilter, EpisodeSort, PlaybackState, Podcast, Preferences, ScanProgress};
use crate::rules::HomeRecommender;
use crate::theme::Palette;
use crate::ui::widgets::{
    accent_button, card_frame, cover_art, format_duration_ms, format_file_size,
    format_human_duration_ms, now_playing_bars, pill_badge, CoverCache,
};
use crate::ui::{AppAction, Screen};
use egui::{Color32, ProgressBar, RichText, ScrollArea, Ui};
use std::collections::HashMap;

#[allow(clippy::too_many_arguments)]
pub fn render_library(
    ui: &mut Ui,
    p: Palette,
    covers: &mut CoverCache,
    podcasts: &[Podcast],
    episodes: &[Episode],
    scan: &ScanProgress,
    current_podcast_id: Option<i64>,
    is_playing: bool,
    actions: &mut Vec<AppAction>,
) {
    ui.add_space(4.0);
    ui.horizontal(|ui| {
        ui.heading(RichText::new("Bibliothèque").size(26.0).strong());
        ui.label(
            RichText::new(format!(
                "· {} podcast(s) · {} épisode(s)",
                podcasts.len(),
                episodes.len()
            ))
            .color(p.on_surface_variant),
        );
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.add_enabled(!scan.running, egui::Button::new("↻ Scanner")).clicked() {
                actions.push(AppAction::StartScan);
            }
            if ui.button("🔍 Doublons").clicked() {
                actions.push(AppAction::Navigate(Screen::Duplicates));
            }
            if ui.button("📁 + Dossier").clicked() {
                actions.push(AppAction::AddWatchedFolder);
            }
        });
    });

    if scan.running {
        ui.add_space(6.0);
        let frac = if scan.total_files > 0 {
            scan.scanned_files as f32 / scan.total_files as f32
        } else {
            0.0
        };
        ui.add(
            ProgressBar::new(frac)
                .fill(p.accent)
                .text(format!(
                    "Scan en cours ({}/{}) : {}",
                    scan.scanned_files, scan.total_files, scan.current_file
                )),
        );
    }

    ui.add_space(10.0);

    if podcasts.is_empty() {
        card_frame(p).show(ui, |ui| {
            ui.vertical_centered(|ui| {
                ui.add_space(20.0);
                ui.label(RichText::new("Aucun podcast dans la bibliothèque").size(17.0).strong());
                ui.label(
                    RichText::new("Ajoutez un dossier contenant vos fichiers MP3, M4A, OGG, Opus, FLAC ou WAV.")
                        .color(p.on_surface_variant),
                );
                ui.add_space(12.0);
                if accent_button(ui, p, "📁 Ajouter un dossier").clicked() {
                    actions.push(AppAction::AddWatchedFolder);
                }
                ui.add_space(20.0);
            });
        });
        return;
    }

    let mut counts: HashMap<i64, (usize, usize)> = HashMap::new();
    for ep in episodes {
        let entry = counts.entry(ep.podcast_id).or_insert((0, 0));
        entry.0 += 1;
        if !ep.is_played && !ep.is_missing {
            entry.1 += 1;
        }
    }

    let avail_w = ui.available_width().max(220.0);
    let card_w = 195.0;
    let cols = ((avail_w / (card_w + 12.0)).floor() as usize).max(1);

    ScrollArea::vertical().show(ui, |ui| {
        for row in podcasts.chunks(cols) {
            ui.horizontal(|ui| {
                for pod in row {
                    let (total, unplayed) = counts.get(&pod.id).copied().unwrap_or((0, 0));
                    let is_curr_pod = current_podcast_id == Some(pod.id);
                    ui.allocate_ui_with_layout(
                        egui::vec2(card_w, 260.0),
                        egui::Layout::top_down(egui::Align::LEFT),
                        |ui| {
                            card_frame(p).show(ui, |ui| {
                                ui.set_width(card_w - 28.0);
                                let cover_resp = cover_art(
                                    ui,
                                    covers,
                                    pod.display_cover(),
                                    pod.display_title(),
                                    card_w - 28.0,
                                    10,
                                );
                                ui.add_space(8.0);
                                ui.horizontal(|ui| {
                                    if is_curr_pod {
                                        now_playing_bars(ui, p, is_playing);
                                    }
                                    let title_label = ui.add(
                                        egui::Label::new(
                                            RichText::new(pod.display_title()).strong().size(14.0),
                                        )
                                        .truncate()
                                        .sense(egui::Sense::click()),
                                    );
                                    if title_label.clicked() || cover_resp.clicked() {
                                        actions.push(AppAction::Navigate(Screen::PodcastDetail(pod.id)));
                                    }
                                });
                                if let Some(author) = pod.display_author() {
                                    ui.add(
                                        egui::Label::new(
                                            RichText::new(author)
                                                .color(p.on_surface_variant)
                                                .size(12.0),
                                        )
                                        .truncate(),
                                    );
                                }
                                ui.add_space(4.0);
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(format!("{total} ép. · {unplayed} non lu(s)"))
                                            .size(11.5)
                                            .color(p.on_surface_variant),
                                    );
                                });
                            });
                        },
                    );
                }
            });
            ui.add_space(10.0);
        }
    });
}

#[allow(clippy::too_many_arguments)]
pub fn render_podcast_detail(
    ui: &mut Ui,
    p: Palette,
    covers: &mut CoverCache,
    podcast: &Podcast,
    episodes: &[Episode],
    states: &HashMap<i64, PlaybackState>,
    prefs: &Preferences,
    filter: EpisodeFilter,
    sort: EpisodeSort,
    current_episode_id: Option<i64>,
    is_playing: bool,
    actions: &mut Vec<AppAction>,
) {
    ui.horizontal(|ui| {
        if ui.button("← Bibliothèque").clicked() {
            actions.push(AppAction::Navigate(Screen::Library));
        }
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.button("✏ Modifier les infos").clicked() {
                actions.push(AppAction::OpenEditPodcast(podcast.id));
            }
        });
    });
    ui.add_space(8.0);

    let pod_episodes: Vec<&Episode> = episodes
        .iter()
        .filter(|e| e.podcast_id == podcast.id)
        .collect();
    let total_dur: i64 = pod_episodes.iter().map(|e| e.duration_ms).sum();
    let pod_speed = prefs.speed_for_podcast(podcast.id);

    card_frame(p).show(ui, |ui| {
        ui.horizontal(|ui| {
            cover_art(ui, covers, podcast.display_cover(), podcast.display_title(), 110.0, 12);
            ui.add_space(14.0);
            ui.vertical(|ui| {
                ui.heading(RichText::new(podcast.display_title()).size(22.0).strong());
                if let Some(author) = podcast.display_author() {
                    ui.label(RichText::new(author).color(p.accent).size(14.0));
                }
                ui.add_space(4.0);
                ui.label(
                    RichText::new(format!(
                        "{} épisode(s) · Durée totale : {}",
                        pod_episodes.len(),
                        format_human_duration_ms(total_dur)
                    ))
                    .color(p.on_surface_variant)
                    .size(12.5),
                );
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new("Vitesse du podcast :").color(p.on_surface_variant));
                    for sp in [0.75_f32, 1.0, 1.25, 1.5, 1.75, 2.0] {
                        let selected = (pod_speed - sp).abs() < 0.01;
                        if ui
                            .selectable_label(selected, format!("{sp:.2}×").trim_end_matches('0').trim_end_matches('.'))
                            .clicked()
                        {
                            actions.push(AppAction::SetPodcastSpeed(podcast.id, sp));
                        }
                    }
                });
            });
        });
    });

    ui.add_space(10.0);

    // Filtres et tri
    ui.horizontal_wrapped(|ui| {
        for f in EpisodeFilter::ALL {
            if ui.selectable_label(filter == f, f.label()).clicked() {
                actions.push(AppAction::SetEpisodeFilter(f));
            }
        }
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            egui::ComboBox::from_id_salt("podcast_sort")
                .selected_text(format!("Tri : {}", sort.label()))
                .show_ui(ui, |ui| {
                    for s in EpisodeSort::ALL {
                        if ui.selectable_label(sort == s, s.label()).clicked() {
                            actions.push(AppAction::SetEpisodeSort(s));
                        }
                    }
                });
        });
    });

    ui.add_space(8.0);

    let mut filtered: Vec<&Episode> = pod_episodes
        .into_iter()
        .filter(|e| match filter {
            EpisodeFilter::All => true,
            EpisodeFilter::Unplayed => !e.is_played,
            EpisodeFilter::InProgress => states
                .get(&e.id)
                .map(|st| !st.completed && st.position_ms >= 5_000)
                .unwrap_or(false),
            EpisodeFilter::Favorites => e.is_favorite,
            EpisodeFilter::Completed => e.is_played,
        })
        .collect();

    match sort {
        EpisodeSort::Series => filtered.sort_by(|a, b| HomeRecommender::series_compare(a, b)),
        EpisodeSort::NewestFirst => filtered.sort_by(|a, b| {
            b.publication_date
                .unwrap_or(b.added_at)
                .cmp(&a.publication_date.unwrap_or(a.added_at))
        }),
        EpisodeSort::OldestFirst => filtered.sort_by(|a, b| {
            a.publication_date
                .unwrap_or(a.added_at)
                .cmp(&b.publication_date.unwrap_or(b.added_at))
        }),
        EpisodeSort::TitleAsc => filtered.sort_by(|a, b| {
            a.display_title()
                .to_lowercase()
                .cmp(&b.display_title().to_lowercase())
        }),
        EpisodeSort::DurationDesc => filtered.sort_by(|a, b| b.duration_ms.cmp(&a.duration_ms)),
    }

    ScrollArea::vertical().show(ui, |ui| {
        if filtered.is_empty() {
            ui.label(
                RichText::new("Aucun épisode ne correspond à ce filtre.")
                    .color(p.on_surface_variant),
            );
        }
        for ep in filtered {
            let st = states.get(&ep.id);
            let is_curr = current_episode_id == Some(ep.id);
            card_frame(p).show(ui, |ui| {
                ui.horizontal(|ui| {
                    let play_btn = if is_curr && is_playing { "⏸" } else { "▶" };
                    if ui
                        .add_enabled(!ep.is_missing, egui::Button::new(play_btn))
                        .clicked()
                    {
                        if is_curr {
                            actions.push(AppAction::TogglePlayPause);
                        } else {
                            actions.push(AppAction::PlayEpisode {
                                episode_id: ep.id,
                                position_ms: None,
                            });
                        }
                    }

                    ui.add_space(6.0);
                    ui.vertical(|ui| {
                        ui.horizontal(|ui| {
                            if is_curr {
                                now_playing_bars(ui, p, is_playing);
                            }
                            let title_color = if ep.is_played {
                                p.on_surface_variant
                            } else {
                                p.on_surface
                            };
                            ui.label(
                                RichText::new(ep.display_title())
                                    .strong()
                                    .size(14.5)
                                    .color(title_color),
                            );
                            if ep.is_played {
                                pill_badge(ui, p.primary_container, p.on_primary_container, "Terminé");
                            }
                            if ep.is_missing {
                                pill_badge(
                                    ui,
                                    Color32::from_rgb(0x5A, 0x1F, 0x1F),
                                    p.error,
                                    "Fichier absent",
                                );
                            }
                        });

                        let mut meta_parts = vec![format_duration_ms(ep.duration_ms)];
                        if let Some(st) = st {
                            if !st.completed && st.position_ms >= 5_000 {
                                meta_parts.push(format!("à {}", format_duration_ms(st.position_ms)));
                            }
                        }
                        if let Some(codec) = &ep.codec {
                            meta_parts.push(codec.clone());
                        }
                        meta_parts.push(format_file_size(ep.file_size));
                        ui.label(
                            RichText::new(meta_parts.join(" · "))
                                .size(12.0)
                                .color(p.on_surface_variant),
                        );
                    });

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("✏").on_hover_text("Modifier").clicked() {
                            actions.push(AppAction::OpenEditEpisode(ep.id));
                        }
                        if ui
                            .button(if ep.is_played { "↺" } else { "✓" })
                            .on_hover_text(if ep.is_played {
                                "Marquer non lu"
                            } else {
                                "Marquer terminé"
                            })
                            .clicked()
                        {
                            actions.push(AppAction::TogglePlayed(ep.id));
                        }
                        let fav_text = if ep.is_favorite { "♥" } else { "♡" };
                        let fav_color = if ep.is_favorite {
                            p.accent
                        } else {
                            p.on_surface_variant
                        };
                        if ui
                            .button(RichText::new(fav_text).color(fav_color))
                            .on_hover_text("Favori")
                            .clicked()
                        {
                            actions.push(AppAction::ToggleFavorite(ep.id));
                        }
                        if ui
                            .add_enabled(!ep.is_missing, egui::Button::new("+ File"))
                            .on_hover_text("Ajouter en fin de file")
                            .clicked()
                        {
                            actions.push(AppAction::QueueAddEnd(ep.id));
                        }
                        if ui
                            .add_enabled(!ep.is_missing, egui::Button::new("⏭ Ensuite"))
                            .on_hover_text("Lire juste après l'épisode en cours")
                            .clicked()
                        {
                            actions.push(AppAction::QueuePlayNext(ep.id));
                        }
                    });
                });
            });
            ui.add_space(6.0);
        }
    });
}

pub fn render_duplicates(
    ui: &mut Ui,
    p: Palette,
    podcasts: &[Podcast],
    episodes: &[Episode],
    actions: &mut Vec<AppAction>,
) {
    ui.horizontal(|ui| {
        if ui.button("← Bibliothèque").clicked() {
            actions.push(AppAction::Navigate(Screen::Library));
        }
        ui.heading(RichText::new("Doublons potentiels").size(22.0).strong());
    });
    ui.label(
        RichText::new("PodVault ne supprime jamais vos fichiers audio. Cet écran permet uniquement de repérer les doublons.")
            .color(p.on_surface_variant)
            .size(12.5),
    );
    ui.add_space(10.0);

    let pod_map: HashMap<i64, &Podcast> = podcasts.iter().map(|pod| (pod.id, pod)).collect();
    let groups = Database::detect_duplicates(episodes);

    if groups.is_empty() {
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("Aucun doublon détecté dans votre bibliothèque.").color(p.accent));
        });
        return;
    }

    ScrollArea::vertical().show(ui, |ui| {
        for group in groups {
            card_frame(p).show(ui, |ui| {
                ui.label(RichText::new(&group.reason).strong().color(p.accent));
                ui.add_space(6.0);
                for ep in &group.episodes {
                    let pod_name = pod_map
                        .get(&ep.podcast_id)
                        .map(|x| x.display_title())
                        .unwrap_or("Podcast");
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(ep.display_title()).strong());
                        ui.label(
                            RichText::new(format!(
                                "· {} · {} · {}",
                                pod_name,
                                format_duration_ms(ep.duration_ms),
                                format_file_size(ep.file_size)
                            ))
                            .color(p.on_surface_variant)
                            .size(12.0),
                        );
                    });
                    ui.label(
                        RichText::new(&ep.audio_uri)
                            .size(11.0)
                            .color(p.on_surface_variant),
                    );
                    ui.add_space(4.0);
                }
            });
            ui.add_space(8.0);
        }
    });
}
