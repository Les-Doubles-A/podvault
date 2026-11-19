use crate::model::{
    Bookmark, Episode, ListeningDay, PlaybackState, Podcast, Preferences, ScanProgress, ThemeMode,
};
use crate::theme::Palette;
use crate::ui::widgets::{
    accent_button, card_frame, format_duration_ms, format_human_duration_ms, pill_badge,
};
use crate::ui::{AppAction, Screen};
use chrono::{Duration, Local};
use egui::{CornerRadius, Rect, RichText, ScrollArea, Sense, Ui, Vec2};
use std::collections::HashMap;

pub fn render_bookmarks(
    ui: &mut Ui,
    p: Palette,
    bookmarks: &[Bookmark],
    episodes: &[Episode],
    podcasts: &[Podcast],
    actions: &mut Vec<AppAction>,
) {
    let ep_map: HashMap<i64, &Episode> = episodes.iter().map(|e| (e.id, e)).collect();
    let pod_map: HashMap<i64, &Podcast> = podcasts.iter().map(|pod| (pod.id, pod)).collect();

    ui.heading(RichText::new("Points mémoire").size(26.0).strong());
    ui.add_space(8.0);

    if bookmarks.is_empty() {
        card_frame(p).show(ui, |ui| {
            ui.label(
                RichText::new(
                    "Aucun point mémoire enregistré. Depuis le lecteur, cliquez sur « Créer un point mémoire » pour mémoriser un passage.",
                )
                .color(p.on_surface_variant),
            );
        });
        return;
    }

    ScrollArea::vertical().show(ui, |ui| {
        for bm in bookmarks {
            let ep = ep_map.get(&bm.episode_id).copied();
            let pod = ep.and_then(|e| pod_map.get(&e.podcast_id).copied());
            card_frame(p).show(ui, |ui| {
                ui.horizontal(|ui| {
                    if accent_button(ui, p, &format!("▶ {}", format_duration_ms(bm.position_ms)))
                        .clicked()
                    {
                        actions.push(AppAction::PlayEpisode {
                            episode_id: bm.episode_id,
                            position_ms: Some(bm.position_ms),
                        });
                    }
                    ui.add_space(8.0);
                    ui.vertical(|ui| {
                        let title = bm.title.as_deref().unwrap_or("Point mémoire");
                        ui.label(RichText::new(title).strong().size(15.0));
                        if let Some(ep) = ep {
                            let sub = match pod {
                                Some(pod) => format!("{} — {}", pod.display_title(), ep.display_title()),
                                None => ep.display_title().to_string(),
                            };
                            ui.label(RichText::new(sub).color(p.accent).size(12.5));
                        }
                        if let Some(note) = &bm.note {
                            if !note.trim().is_empty() {
                                ui.label(RichText::new(note).color(p.on_surface_variant));
                            }
                        }
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("🗑").on_hover_text("Supprimer").clicked() {
                            actions.push(AppAction::DeleteBookmark(bm.id));
                        }
                    });
                });
            });
            ui.add_space(6.0);
        }
    });
}

pub fn render_search(
    ui: &mut Ui,
    p: Palette,
    query: &mut String,
    podcasts: &[Podcast],
    episodes: &[Episode],
    bookmarks: &[Bookmark],
    actions: &mut Vec<AppAction>,
) {
    ui.heading(RichText::new("Recherche").size(26.0).strong());
    ui.add_space(8.0);

    ui.horizontal(|ui| {
        ui.add(
            egui::TextEdit::singleline(query)
                .hint_text("Rechercher un podcast, un épisode ou un point mémoire...")
                .desired_width(420.0),
        );
        if !query.is_empty() && ui.button("Effacer").clicked() {
            query.clear();
        }
    });

    ui.add_space(10.0);
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        ui.label(
            RichText::new("Tapez un mot-clé pour filtrer instantanément votre bibliothèque locale.")
                .color(p.on_surface_variant),
        );
        return;
    }

    let pod_map: HashMap<i64, &Podcast> = podcasts.iter().map(|pod| (pod.id, pod)).collect();
    let matching_podcasts: Vec<&Podcast> = podcasts
        .iter()
        .filter(|pod| {
            pod.display_title().to_lowercase().contains(&q)
                || pod
                    .display_author()
                    .map(|a| a.to_lowercase().contains(&q))
                    .unwrap_or(false)
        })
        .collect();

    let matching_episodes: Vec<&Episode> = episodes
        .iter()
        .filter(|ep| {
            ep.display_title().to_lowercase().contains(&q)
                || ep.file_name.to_lowercase().contains(&q)
                || ep
                    .description
                    .as_deref()
                    .map(|d| d.to_lowercase().contains(&q))
                    .unwrap_or(false)
        })
        .take(40)
        .collect();

    let matching_bookmarks: Vec<&Bookmark> = bookmarks
        .iter()
        .filter(|bm| {
            bm.title
                .as_deref()
                .map(|t| t.to_lowercase().contains(&q))
                .unwrap_or(false)
                || bm
                    .note
                    .as_deref()
                    .map(|n| n.to_lowercase().contains(&q))
                    .unwrap_or(false)
        })
        .take(20)
        .collect();

    ScrollArea::vertical().show(ui, |ui| {
        if !matching_podcasts.is_empty() {
            ui.label(
                RichText::new(format!("Podcasts ({})", matching_podcasts.len()))
                    .size(16.0)
                    .strong(),
            );
            ui.add_space(4.0);
            for pod in matching_podcasts {
                card_frame(p).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(pod.display_title()).strong());
                        if let Some(a) = pod.display_author() {
                            ui.label(RichText::new(format!("· {a}")).color(p.on_surface_variant));
                        }
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Ouvrir").clicked() {
                                actions.push(AppAction::Navigate(Screen::PodcastDetail(pod.id)));
                            }
                        });
                    });
                });
                ui.add_space(4.0);
            }
            ui.add_space(10.0);
        }

        if !matching_episodes.is_empty() {
            ui.label(
                RichText::new(format!("Épisodes ({})", matching_episodes.len()))
                    .size(16.0)
                    .strong(),
            );
            ui.add_space(4.0);
            for ep in matching_episodes {
                let pod_name = pod_map
                    .get(&ep.podcast_id)
                    .map(|pod| pod.display_title())
                    .unwrap_or("");
                card_frame(p).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(RichText::new(ep.display_title()).strong());
                            ui.label(
                                RichText::new(format!(
                                    "{} · {}",
                                    pod_name,
                                    format_duration_ms(ep.duration_ms)
                                ))
                                .color(p.on_surface_variant)
                                .size(12.0),
                            );
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("▶ Lire").clicked() {
                                actions.push(AppAction::PlayEpisode {
                                    episode_id: ep.id,
                                    position_ms: None,
                                });
                            }
                            if ui.button("+ File").clicked() {
                                actions.push(AppAction::QueueAddEnd(ep.id));
                            }
                        });
                    });
                });
                ui.add_space(4.0);
            }
            ui.add_space(10.0);
        }

        if !matching_bookmarks.is_empty() {
            ui.label(
                RichText::new(format!("Points mémoire ({})", matching_bookmarks.len()))
                    .size(16.0)
                    .strong(),
            );
            ui.add_space(4.0);
            for bm in matching_bookmarks {
                card_frame(p).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new(bm.title.as_deref().unwrap_or("Point mémoire")).strong(),
                        );
                        if let Some(note) = &bm.note {
                            ui.label(RichText::new(note).color(p.on_surface_variant));
                        }
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui
                                .button(format!("▶ {}", format_duration_ms(bm.position_ms)))
                                .clicked()
                            {
                                actions.push(AppAction::PlayEpisode {
                                    episode_id: bm.episode_id,
                                    position_ms: Some(bm.position_ms),
                                });
                            }
                        });
                    });
                });
                ui.add_space(4.0);
            }
        }
    });
}

pub fn render_statistics(
    ui: &mut Ui,
    p: Palette,
    podcasts: &[Podcast],
    episodes: &[Episode],
    states: &HashMap<i64, PlaybackState>,
    bookmarks: &[Bookmark],
    listening_days: &[ListeningDay],
) {
    ui.heading(RichText::new("Statistiques d'écoute").size(26.0).strong());
    ui.add_space(10.0);

    let total_listened_ms: i64 = listening_days.iter().map(|d| d.listened_ms).sum();
    let completed_count = episodes.iter().filter(|e| e.is_played).count();
    let favorites_count = episodes.iter().filter(|e| e.is_favorite).count();

    // Calcul de la série de jours consécutifs
    let day_map: HashMap<&str, i64> = listening_days
        .iter()
        .map(|d| (d.day.as_str(), d.listened_ms))
        .collect();
    let today = Local::now().date_naive();
    let mut streak = 0usize;
    for offset in 0..365 {
        let d = today - Duration::days(offset);
        let key = d.format("%Y-%m-%d").to_string();
        if day_map.get(key.as_str()).copied().unwrap_or(0) > 0 {
            streak += 1;
        } else if offset > 0 {
            break;
        }
    }

    ScrollArea::vertical().show(ui, |ui| {
        ui.horizontal_wrapped(|ui| {
            stat_tile(ui, p, "Temps total écouté", &format_human_duration_ms(total_listened_ms));
            stat_tile(ui, p, "Épisodes terminés", &format!("{completed_count} / {}", episodes.len()));
            stat_tile(ui, p, "Podcasts", &podcasts.len().to_string());
            stat_tile(ui, p, "Favoris", &favorites_count.to_string());
            stat_tile(ui, p, "Points mémoire", &bookmarks.len().to_string());
            stat_tile(ui, p, "Série en cours", &format!("{streak} jour(s)"));
        });

        ui.add_space(14.0);

        // Graphique des 14 derniers jours
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("Activité des 14 derniers jours").size(16.0).strong());
            ui.add_space(10.0);

            let mut bars: Vec<(String, i64)> = Vec::with_capacity(14);
            for offset in (0..14).rev() {
                let d = today - Duration::days(offset);
                let key = d.format("%Y-%m-%d").to_string();
                let label = d.format("%d/%m").to_string();
                let ms = day_map.get(key.as_str()).copied().unwrap_or(0);
                bars.push((label, ms));
            }
            let max_ms = bars.iter().map(|(_, m)| *m).max().unwrap_or(1).max(60_000);

            let chart_h = 120.0;
            let bar_w = 28.0;
            let gap = 10.0;
            let total_w = (bars.len() as f32) * (bar_w + gap);
            let (rect, _) = ui.allocate_exact_size(Vec2::new(total_w, chart_h + 24.0), Sense::hover());

            for (i, (lbl, ms)) in bars.iter().enumerate() {
                let x = rect.left() + (i as f32) * (bar_w + gap);
                let frac = (*ms as f32 / max_ms as f32).clamp(0.0, 1.0);
                let h = (frac * chart_h).max(if *ms > 0 { 4.0 } else { 2.0 });
                let bar_rect = Rect::from_min_max(
                    egui::pos2(x, rect.top() + chart_h - h),
                    egui::pos2(x + bar_w, rect.top() + chart_h),
                );
                let color = if *ms > 0 {
                    p.accent
                } else {
                    p.surface_variant
                };
                ui.painter()
                    .rect_filled(bar_rect, CornerRadius::same(4), color);
                ui.painter().text(
                    egui::pos2(x + bar_w / 2.0, rect.top() + chart_h + 12.0),
                    egui::Align2::CENTER_CENTER,
                    lbl,
                    egui::FontId::proportional(10.0),
                    p.on_surface_variant,
                );
            }
        });

        ui.add_space(14.0);

        // Top podcasts par temps d'écoute
        let mut by_pod: HashMap<i64, i64> = HashMap::new();
        for ep in episodes {
            if let Some(st) = states.get(&ep.id) {
                *by_pod.entry(ep.podcast_id).or_insert(0) += st.total_listened_ms;
            }
        }
        let mut top: Vec<(&Podcast, i64)> = podcasts
            .iter()
            .filter_map(|pod| {
                let ms = by_pod.get(&pod.id).copied().unwrap_or(0);
                if ms > 0 {
                    Some((pod, ms))
                } else {
                    None
                }
            })
            .collect();
        top.sort_by(|a, b| b.1.cmp(&a.1));
        top.truncate(8);

        if !top.is_empty() {
            card_frame(p).show(ui, |ui| {
                ui.label(RichText::new("Podcasts les plus écoutés").size(16.0).strong());
                ui.add_space(8.0);
                for (pod, ms) in top {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(pod.display_title()).strong());
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            pill_badge(
                                ui,
                                p.primary_container,
                                p.on_primary_container,
                                &format_human_duration_ms(ms),
                            );
                        });
                    });
                    ui.add_space(4.0);
                }
            });
        }
    });
}

fn stat_tile(ui: &mut Ui, p: Palette, label: &str, value: &str) {
    card_frame(p).show(ui, |ui| {
        ui.set_min_width(145.0);
        ui.label(RichText::new(label).color(p.on_surface_variant).size(12.0));
        ui.label(RichText::new(value).color(p.accent).size(20.0).strong());
    });
}

pub fn render_settings(
    ui: &mut Ui,
    p: Palette,
    prefs: &Preferences,
    scan: &ScanProgress,
    actions: &mut Vec<AppAction>,
) {
    ui.heading(RichText::new("Paramètres").size(26.0).strong());
    ui.add_space(10.0);

    ScrollArea::vertical().show(ui, |ui| {
        // Dossiers surveillés
        card_frame(p).show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(RichText::new("Dossiers surveillés").size(16.0).strong());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if accent_button(ui, p, "📁 Ajouter un dossier").clicked() {
                        actions.push(AppAction::AddWatchedFolder);
                    }
                    if ui
                        .add_enabled(!scan.running, egui::Button::new("↻ Scanner maintenant"))
                        .clicked()
                    {
                        actions.push(AppAction::StartScan);
                    }
                });
            });
            ui.add_space(6.0);
            if prefs.watched_folders.is_empty() {
                ui.label(
                    RichText::new("Aucun dossier configuré.").color(p.on_surface_variant),
                );
            } else {
                for folder in &prefs.watched_folders {
                    ui.horizontal(|ui| {
                        ui.label(folder);
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Retirer").clicked() {
                                actions.push(AppAction::RemoveWatchedFolder(folder.clone()));
                            }
                        });
                    });
                }
            }
        });

        ui.add_space(10.0);

        // Lecture
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("Lecture").size(16.0).strong());
            ui.add_space(6.0);

            ui.horizontal_wrapped(|ui| {
                ui.label("Vitesse par défaut :");
                for sp in [0.75_f32, 1.0, 1.25, 1.5, 1.75, 2.0] {
                    if ui
                        .selectable_label((prefs.default_speed - sp).abs() < 0.01, format!("{sp}×"))
                        .clicked()
                    {
                        actions.push(AppAction::SetDefaultSpeed(sp));
                    }
                }
            });

            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label("Retour rapide :");
                for s in [5, 10, 15, 30] {
                    if ui
                        .selectable_label(prefs.rewind_seconds == s, format!("-{s} s"))
                        .clicked()
                    {
                        actions.push(AppAction::SetSkipIntervals(s, prefs.forward_seconds));
                    }
                }
            });

            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label("Avance rapide :");
                for s in [10, 15, 30, 45, 60] {
                    if ui
                        .selectable_label(prefs.forward_seconds == s, format!("+{s} s"))
                        .clicked()
                    {
                        actions.push(AppAction::SetSkipIntervals(prefs.rewind_seconds, s));
                    }
                }
            });

            ui.add_space(6.0);
            let mut smart = prefs.smart_rewind;
            if ui
                .checkbox(
                    &mut smart,
                    "Retour intelligent à la reprise (recule légèrement selon la durée de la pause)",
                )
                .changed()
            {
                actions.push(AppAction::SetSmartRewind(smart));
            }
        });

        ui.add_space(10.0);

        // Apparence
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("Apparence").size(16.0).strong());
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                ui.label("Thème :");
                if ui
                    .selectable_label(prefs.theme == ThemeMode::Dark, "🌙 Sombre")
                    .clicked()
                {
                    actions.push(AppAction::SetTheme(ThemeMode::Dark));
                }
                if ui
                    .selectable_label(prefs.theme == ThemeMode::Light, "☀ Clair")
                    .clicked()
                {
                    actions.push(AppAction::SetTheme(ThemeMode::Light));
                }
            });
            ui.add_space(4.0);
            let mut ambient = prefs.ambient_mode;
            if ui
                .checkbox(
                    &mut ambient,
                    "Mode ambiance du lecteur (teinte inspirée de la pochette)",
                )
                .changed()
            {
                actions.push(AppAction::SetAmbientMode(ambient));
            }
        });

        ui.add_space(10.0);

        // Sauvegarde JSON
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("Sauvegarde et restauration").size(16.0).strong());
            ui.label(
                RichText::new(
                    "Exportez ou importez votre bibliothèque, positions de lecture, favoris, points mémoire et statistiques au format JSON compatible avec PodVault Android.",
                )
                .color(p.on_surface_variant)
                .size(12.5),
            );
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if accent_button(ui, p, "💾 Exporter la sauvegarde (.json)").clicked() {
                    actions.push(AppAction::ExportBackup);
                }
                if ui.button("📂 Importer une sauvegarde (.json)").clicked() {
                    actions.push(AppAction::ImportBackup);
                }
            });
        });

        ui.add_space(10.0);

        // À propos
        card_frame(p).show(ui, |ui| {
            ui.label(RichText::new("À propos de PodVault").size(16.0).strong());
            ui.label(
                RichText::new(format!(
                    "PodVault Windows v{} — 100 % local, hors-ligne, écrit en Rust.\n\
                     Vos fichiers audio ne sont jamais déplacés, modifiés ni supprimés.",
                    env!("CARGO_PKG_VERSION")
                ))
                .color(p.on_surface_variant),
            );
            ui.hyperlink_to(
                "Dépôt GitHub : github.com/Les-Doubles-A/podvault",
                "https://github.com/Les-Doubles-A/podvault",
            );
        });
        ui.add_space(16.0);
    });
}
