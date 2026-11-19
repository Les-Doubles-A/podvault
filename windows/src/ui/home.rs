use crate::db::Database;
use crate::model::{Episode, PlaybackState, Podcast};
use crate::rules::{HomeRecommender, PlaybackRules};
use crate::theme::Palette;
use crate::ui::widgets::{
    accent_button, card_frame, cover_art, format_duration_ms, format_human_duration_ms,
    now_playing_bars, CoverCache,
};
use crate::ui::{AppAction, Screen};
use chrono::Utc;
use egui::{ProgressBar, RichText, ScrollArea, Ui};
use std::collections::{HashMap, HashSet};

#[allow(clippy::too_many_arguments)]
pub fn render_home(
    ui: &mut Ui,
    p: Palette,
    covers: &mut CoverCache,
    podcasts: &[Podcast],
    episodes: &[Episode],
    states: &HashMap<i64, PlaybackState>,
    has_watched_folders: bool,
    current_episode_id: Option<i64>,
    is_playing: bool,
    actions: &mut Vec<AppAction>,
) {
    let podcast_map: HashMap<i64, &Podcast> = podcasts.iter().map(|pod| (pod.id, pod)).collect();

    ScrollArea::vertical().show(ui, |ui| {
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            ui.heading(RichText::new("Accueil").size(26.0).strong());
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("↻ Scanner").clicked() {
                    actions.push(AppAction::StartScan);
                }
                if ui.button("📊 Statistiques").clicked() {
                    actions.push(AppAction::Navigate(Screen::Statistics));
                }
            });
        });
        ui.add_space(8.0);

        if !has_watched_folders || episodes.is_empty() {
            card_frame(p).show(ui, |ui| {
                ui.vertical_centered(|ui| {
                    ui.add_space(16.0);
                    ui.label(
                        RichText::new("Bienvenue dans PodVault")
                            .size(22.0)
                            .strong()
                            .color(p.accent),
                    );
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(
                            "Lecteur et gestionnaire de podcasts 100 % local pour Windows.\n\
                             Aucun compte, aucun réseau, vos fichiers audio ne sont jamais modifiés ni déplacés.",
                        )
                        .color(p.on_surface_variant),
                    );
                    ui.add_space(16.0);
                    if accent_button(ui, p, "📁 Choisir un dossier de podcasts").clicked() {
                        actions.push(AppAction::AddWatchedFolder);
                    }
                    if has_watched_folders {
                        ui.add_space(8.0);
                        if ui.button("Lancer un scan maintenant").clicked() {
                            actions.push(AppAction::StartScan);
                        }
                    }
                    ui.add_space(16.0);
                });
            });
            return;
        }

        // 1. Continuer l'écoute
        let mut in_progress: Vec<(&Episode, &PlaybackState)> = episodes
            .iter()
            .filter(|e| !e.is_missing && !e.is_played)
            .filter_map(|e| {
                let st = states.get(&e.id)?;
                if !st.completed
                    && st.position_ms >= 5_000
                    && !PlaybackRules::is_completed(st.position_ms, e.duration_ms)
                {
                    Some((e, st))
                } else {
                    None
                }
            })
            .collect();
        in_progress.sort_by(|a, b| b.1.last_played_at.cmp(&a.1.last_played_at));
        in_progress.truncate(6);

        let mut exclude_ids: HashSet<i64> = HashSet::new();

        if !in_progress.is_empty() {
            ui.label(RichText::new("Continuer l'écoute").size(18.0).strong());
            ui.add_space(6.0);

            for (ep, st) in &in_progress {
                exclude_ids.insert(ep.id);
                let pod = podcast_map.get(&ep.podcast_id).copied();
                let cover = ep.display_cover().or_else(|| pod.and_then(|x| x.display_cover()));
                let is_curr = current_episode_id == Some(ep.id);

                card_frame(p).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        cover_art(ui, covers, cover, ep.display_title(), 60.0, 8);
                        ui.add_space(8.0);
                        ui.vertical(|ui| {
                            ui.horizontal(|ui| {
                                if is_curr {
                                    now_playing_bars(ui, p, is_playing);
                                }
                                ui.label(RichText::new(ep.display_title()).strong().size(15.0));
                            });
                            if let Some(pod) = pod {
                                ui.label(
                                    RichText::new(pod.display_title())
                                        .color(p.on_surface_variant)
                                        .size(12.5),
                                );
                            }
                            let frac = if ep.duration_ms > 0 {
                                (st.position_ms as f32 / ep.duration_ms as f32).clamp(0.0, 1.0)
                            } else {
                                0.0
                            };
                            let remaining = (ep.duration_ms - st.position_ms).max(0);
                            ui.add_space(4.0);
                            ui.add(
                                ProgressBar::new(frac)
                                    .fill(p.accent)
                                    .desired_height(5.0)
                                    .desired_width(280.0),
                            );
                            ui.label(
                                RichText::new(format!(
                                    "{} restant · {} / {}",
                                    format_human_duration_ms(remaining),
                                    format_duration_ms(st.position_ms),
                                    format_duration_ms(ep.duration_ms)
                                ))
                                .size(11.5)
                                .color(p.on_surface_variant),
                            );
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let label = if is_curr && is_playing {
                                "⏸ Pause"
                            } else {
                                "▶ Reprendre"
                            };
                            if accent_button(ui, p, label).clicked() {
                                if is_curr {
                                    actions.push(AppAction::TogglePlayPause);
                                } else {
                                    actions.push(AppAction::PlayEpisode {
                                        episode_id: ep.id,
                                        position_ms: None,
                                    });
                                }
                            }
                        });
                    });
                });
                ui.add_space(6.0);
            }
            ui.add_space(12.0);
        }

        // 2. À suivre (prochain épisode de la série après le dernier terminé ou en cours)
        let mut by_podcast: HashMap<i64, Vec<Episode>> = HashMap::new();
        for ep in episodes.iter().filter(|e| !e.is_missing) {
            by_podcast.entry(ep.podcast_id).or_default().push(ep.clone());
        }

        let mut up_next: Vec<(Episode, i64)> = Vec::new();
        for (pid, eps) in &by_podcast {
            let last_played = eps
                .iter()
                .filter_map(|e| states.get(&e.id).map(|st| (e, st.last_played_at)))
                .max_by_key(|(_, ts)| *ts);
            if let Some((last_ep, ts)) = last_played {
                let unplayed: Vec<Episode> = eps
                    .iter()
                    .filter(|e| {
                        !e.is_played
                            && !exclude_ids.contains(&e.id)
                            && states
                                .get(&e.id)
                                .map(|st| !st.completed && st.position_ms < 5_000)
                                .unwrap_or(true)
                    })
                    .cloned()
                    .collect();
                if let Some(next_ep) = HomeRecommender::next_in_series(last_ep, &unplayed) {
                    up_next.push((next_ep.clone(), ts));
                }
            }
            let _ = pid;
        }
        up_next.sort_by(|a, b| b.1.cmp(&a.1));
        up_next.truncate(5);

        if !up_next.is_empty() {
            ui.label(RichText::new("À suivre").size(18.0).strong());
            ui.add_space(6.0);
            for (ep, _) in &up_next {
                exclude_ids.insert(ep.id);
                render_compact_episode_card(
                    ui,
                    p,
                    covers,
                    ep,
                    podcast_map.get(&ep.podcast_id).copied(),
                    current_episode_id == Some(ep.id),
                    is_playing,
                    actions,
                );
                ui.add_space(6.0);
            }
            ui.add_space(12.0);
        }

        // 3. Pour vous (HomeRecommender)
        let signals = Database::compute_podcast_signals(episodes, states);
        let unstarted: Vec<Episode> = episodes
            .iter()
            .filter(|e| {
                !e.is_missing
                    && !e.is_played
                    && states
                        .get(&e.id)
                        .map(|st| !st.completed && st.position_ms < 5_000)
                        .unwrap_or(true)
            })
            .cloned()
            .collect();
        let now = Utc::now().timestamp_millis();
        let recommended = HomeRecommender::recommend(&unstarted, &signals, &exclude_ids, now, 8, 2);

        if !recommended.is_empty() {
            ui.horizontal(|ui| {
                ui.label(RichText::new("Pour vous").size(18.0).strong());
                ui.label(
                    RichText::new("· sélection locale renouvelée chaque jour")
                        .color(p.on_surface_variant)
                        .size(12.0),
                );
            });
            ui.add_space(6.0);
            for ep in &recommended {
                render_compact_episode_card(
                    ui,
                    p,
                    covers,
                    ep,
                    podcast_map.get(&ep.podcast_id).copied(),
                    current_episode_id == Some(ep.id),
                    is_playing,
                    actions,
                );
                ui.add_space(6.0);
            }
        }
        ui.add_space(16.0);
    });
}

#[allow(clippy::too_many_arguments)]
fn render_compact_episode_card(
    ui: &mut Ui,
    p: Palette,
    covers: &mut CoverCache,
    ep: &Episode,
    pod: Option<&Podcast>,
    is_curr: bool,
    is_playing: bool,
    actions: &mut Vec<AppAction>,
) {
    let cover = ep.display_cover().or_else(|| pod.and_then(|x| x.display_cover()));
    card_frame(p).show(ui, |ui| {
        ui.horizontal(|ui| {
            cover_art(ui, covers, cover, ep.display_title(), 50.0, 8);
            ui.add_space(8.0);
            ui.vertical(|ui| {
                ui.horizontal(|ui| {
                    if is_curr {
                        now_playing_bars(ui, p, is_playing);
                    }
                    ui.label(RichText::new(ep.display_title()).strong().size(14.5));
                });
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
                if ui
                    .button(if is_curr && is_playing { "⏸" } else { "▶" })
                    .on_hover_text("Lire")
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
                if ui
                    .button("+ File")
                    .on_hover_text("Ajouter à la file d'attente")
                    .clicked()
                {
                    actions.push(AppAction::QueueAddEnd(ep.id));
                }
                if let Some(pod) = pod {
                    if ui
                        .button("Podcast")
                        .on_hover_text("Ouvrir la fiche du podcast")
                        .clicked()
                    {
                        actions.push(AppAction::Navigate(Screen::PodcastDetail(pod.id)));
                    }
                }
            });
        });
    });
}
