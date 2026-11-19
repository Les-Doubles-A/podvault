use crate::audio::AudioEngine;
use crate::backup;
use crate::db::Database;
use crate::dictionary::{self, DictionaryLookupResult};
use crate::model::{
    Bookmark, Episode, EpisodeFilter, EpisodeSort, ListeningDay, PlaybackState, Podcast,
    Preferences, ScanProgress, SleepTimerMode,
};
use crate::rules::PlaybackRules;
use crate::scanner;
use crate::theme::{apply_theme, Palette};
use crate::ui::widgets::{accent_button, format_duration_ms, CoverCache};
use crate::ui::{extra, home, library, player, AppAction, Screen};
use chrono::{Local, Utc};
use egui::{Color32, CornerRadius, Margin, RichText, Stroke};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

struct Toast {
    message: String,
    is_error: bool,
    expires_at: Instant,
}

struct AddBookmarkModal {
    episode_id: i64,
    position_ms: i64,
    title: String,
    note: String,
}

struct EditPodcastModal {
    podcast_id: i64,
    title: String,
    author: String,
    cover_uri: Option<String>,
}

struct EditEpisodeModal {
    episode_id: i64,
    title: String,
    author: String,
    cover_uri: Option<String>,
}

pub struct PodVaultApp {
    db_path: PathBuf,
    covers_dir: PathBuf,
    prefs_path: PathBuf,

    db: Database,
    prefs: Preferences,
    audio: Option<AudioEngine>,

    podcasts: Vec<Podcast>,
    episodes: Vec<Episode>,
    states: HashMap<i64, PlaybackState>,
    bookmarks: Vec<Bookmark>,
    listening_days: Vec<ListeningDay>,

    screen: Screen,
    current_episode_id: Option<i64>,
    queue: Vec<i64>,
    sleep_timer: SleepTimerMode,

    episode_filter: EpisodeFilter,
    episode_sort: EpisodeSort,
    search_query: String,

    dict_query: String,
    dict_last_suggested_query: String,
    dict_last_edit: Instant,
    dict_show_suggestions: bool,
    dict_suggestions: Vec<String>,
    dict_suggest_rx: Option<std::sync::mpsc::Receiver<(String, Vec<String>)>>,
    dict_loading: bool,
    dict_result: Option<Result<DictionaryLookupResult, String>>,
    dict_recent: Vec<String>,
    dict_rx: Option<std::sync::mpsc::Receiver<Result<DictionaryLookupResult, String>>>,

    scan_progress: Arc<Mutex<ScanProgress>>,
    scan_was_running: bool,

    covers: CoverCache,
    toast: Option<Toast>,
    add_bookmark_modal: Option<AddBookmarkModal>,
    edit_podcast_modal: Option<EditPodcastModal>,
    edit_episode_modal: Option<EditEpisodeModal>,

    last_tick: Instant,
    last_persist: Instant,
    theme_applied: bool,
}

impl PodVaultApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let data_dir = directories::ProjectDirs::from("com", "podvault", "PodVault")
            .map(|d| d.data_local_dir().to_path_buf())
            .unwrap_or_else(|| PathBuf::from("podvault_data"));
        let _ = std::fs::create_dir_all(&data_dir);

        let db_path = data_dir.join("podvault.db");
        let covers_dir = data_dir.join("covers");
        let prefs_path = data_dir.join("preferences.json");

        let prefs: Preferences = std::fs::read_to_string(&prefs_path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();

        apply_theme(&cc.egui_ctx, prefs.theme);

        let db = Database::open(&db_path).expect("Impossible d'ouvrir la base SQLite PodVault");
        let audio = AudioEngine::new().ok();

        let mut app = Self {
            db_path,
            covers_dir,
            prefs_path,
            db,
            prefs,
            audio,
            podcasts: Vec::new(),
            episodes: Vec::new(),
            states: HashMap::new(),
            bookmarks: Vec::new(),
            listening_days: Vec::new(),
            screen: Screen::Home,
            current_episode_id: None,
            queue: Vec::new(),
            sleep_timer: SleepTimerMode::Off,
            episode_filter: EpisodeFilter::All,
            episode_sort: EpisodeSort::Series,
            search_query: String::new(),
            dict_query: String::new(),
            dict_last_suggested_query: String::new(),
            dict_last_edit: Instant::now(),
            dict_show_suggestions: false,
            dict_suggestions: Vec::new(),
            dict_suggest_rx: None,
            dict_loading: false,
            dict_result: None,
            dict_recent: Vec::new(),
            dict_rx: None,
            scan_progress: Arc::new(Mutex::new(ScanProgress::default())),
            scan_was_running: false,
            covers: CoverCache::default(),
            toast: None,
            add_bookmark_modal: None,
            edit_podcast_modal: None,
            edit_episode_modal: None,
            last_tick: Instant::now(),
            last_persist: Instant::now(),
            theme_applied: true,
        };

        if let Some(engine) = &mut app.audio {
            engine.set_volume(app.prefs.volume);
        }
        app.reload_data();
        app
    }

    fn reload_data(&mut self) {
        self.podcasts = self.db.get_all_podcasts().unwrap_or_default();
        self.episodes = self.db.get_all_episodes().unwrap_or_default();
        self.states = self
            .db
            .get_all_playback_states()
            .unwrap_or_default()
            .into_iter()
            .map(|s| (s.episode_id, s))
            .collect();
        self.bookmarks = self.db.get_all_bookmarks().unwrap_or_default();
        self.listening_days = self.db.get_all_listening_days().unwrap_or_default();
    }

    fn save_prefs(&self) {
        if let Ok(json) = serde_json::to_string_pretty(&self.prefs) {
            let _ = std::fs::write(&self.prefs_path, json);
        }
    }

    fn show_toast(&mut self, msg: impl Into<String>, is_error: bool) {
        self.toast = Some(Toast {
            message: msg.into(),
            is_error,
            expires_at: Instant::now() + Duration::from_secs(4),
        });
    }

    fn current_episode(&self) -> Option<&Episode> {
        let id = self.current_episode_id?;
        self.episodes.iter().find(|e| e.id == id)
    }

    fn current_podcast(&self) -> Option<&Podcast> {
        let ep = self.current_episode()?;
        self.podcasts.iter().find(|p| p.id == ep.podcast_id)
    }

    fn play_episode(&mut self, episode_id: i64, explicit_position_ms: Option<i64>) {
        // Persiste la position de l'épisode précédent avant de changer
        self.persist_current_playback(0);

        let Some(ep) = self.episodes.iter().find(|e| e.id == episode_id).cloned() else {
            return;
        };
        let saved = self.states.get(&episode_id);
        let start_ms = explicit_position_ms.unwrap_or_else(|| {
            PlaybackRules::resume_position(saved, ep.duration_ms, self.prefs.smart_rewind)
        });
        let speed = self.prefs.speed_for_podcast(ep.podcast_id);
        let volume = self.prefs.volume;

        let Some(engine) = &mut self.audio else {
            self.show_toast("Sortie audio indisponible sur ce système.", true);
            return;
        };

        match engine.play_file(&ep.audio_uri, start_ms, ep.duration_ms, speed, volume) {
            Ok(()) => {
                self.current_episode_id = Some(episode_id);
                let now = Utc::now().timestamp_millis();
                let mut state = self.states.get(&episode_id).cloned().unwrap_or(PlaybackState {
                    episode_id,
                    position_ms: start_ms,
                    last_played_at: now,
                    play_count: 0,
                    completed: false,
                    total_listened_ms: 0,
                });
                state.position_ms = start_ms;
                state.last_played_at = now;
                state.play_count += 1;
                if state.completed && start_ms < ep.duration_ms.saturating_sub(5_000) {
                    state.completed = false;
                }
                let _ = self.db.upsert_playback_state(&state);
                self.states.insert(episode_id, state);
            }
            Err(err) => {
                self.show_toast(err, true);
            }
        }
    }

    fn persist_current_playback(&mut self, delta_listened_ms: i64) {
        let Some(ep_id) = self.current_episode_id else {
            return;
        };
        let Some(ep) = self.episodes.iter().find(|e| e.id == ep_id).cloned() else {
            return;
        };
        let Some(engine) = &self.audio else {
            return;
        };
        if !engine.is_loaded() {
            return;
        }
        let pos_ms = engine.position_ms();
        let now = Utc::now().timestamp_millis();
        let completed = PlaybackRules::is_completed(pos_ms, ep.duration_ms);

        let mut state = self.states.get(&ep_id).cloned().unwrap_or(PlaybackState {
            episode_id: ep_id,
            position_ms: pos_ms,
            last_played_at: now,
            play_count: 1,
            completed: false,
            total_listened_ms: 0,
        });
        state.position_ms = pos_ms;
        state.last_played_at = now;
        state.total_listened_ms += delta_listened_ms.max(0);
        if completed {
            state.completed = true;
        }
        let _ = self.db.upsert_playback_state(&state);
        self.states.insert(ep_id, state);

        if completed && !ep.is_played {
            let _ = self.db.set_episode_played(ep_id, true);
            if let Some(e) = self.episodes.iter_mut().find(|e| e.id == ep_id) {
                e.is_played = true;
            }
        }

        if delta_listened_ms > 0 {
            let day = Local::now().format("%Y-%m-%d").to_string();
            let _ = self.db.add_listening_time(&day, delta_listened_ms);
            if let Some(existing) = self.listening_days.iter_mut().find(|d| d.day == day) {
                existing.listened_ms += delta_listened_ms;
            } else {
                self.listening_days.push(ListeningDay {
                    day,
                    listened_ms: delta_listened_ms,
                });
            }
        }
    }

    fn start_scan(&mut self) {
        if self.prefs.watched_folders.is_empty() {
            self.show_toast("Ajoutez d'abord un dossier à scanner.", true);
            return;
        }
        if let Ok(p) = self.scan_progress.lock() {
            if p.running {
                return;
            }
        }
        let db_path = self.db_path.clone();
        let covers_dir = self.covers_dir.clone();
        let folders = self.prefs.watched_folders.clone();
        let progress = Arc::clone(&self.scan_progress);
        self.scan_was_running = true;

        std::thread::spawn(move || {
            scanner::scan_folders(db_path, covers_dir, folders, progress);
        });
    }

    fn tick(&mut self) {
        let now = Instant::now();
        let dt = now.duration_since(self.last_tick);
        self.last_tick = now;

        // Expiration du toast
        if let Some(t) = &self.toast {
            if now >= t.expires_at {
                self.toast = None;
            }
        }

        // Réception du résultat du dictionnaire
        if let Some(rx) = &self.dict_rx {
            if let Ok(res) = rx.try_recv() {
                if let Ok(lookup) = &res {
                    if let Some(entry) = &lookup.entry {
                        let w = entry.word.clone();
                        self.dict_recent.retain(|x| !x.eq_ignore_ascii_case(&w));
                        self.dict_recent.insert(0, w);
                        self.dict_recent.truncate(6);
                    }
                }
                self.dict_result = Some(res);
                self.dict_loading = false;
                self.dict_rx = None;
            }
        }

        // Réception des suggestions en direct du dictionnaire
        if let Some(rx) = &self.dict_suggest_rx {
            if let Ok((q, list)) = rx.try_recv() {
                if q == self.dict_query.trim() {
                    self.dict_suggestions = list;
                }
                self.dict_suggest_rx = None;
            }
        }

        // Déclenchement debouncé (220 ms) des suggestions pendant la saisie
        let trimmed_q = self.dict_query.trim().to_string();
        if self.dict_show_suggestions
            && trimmed_q.chars().count() >= 2
            && trimmed_q != self.dict_last_suggested_query
            && self.dict_last_edit.elapsed() >= Duration::from_millis(220)
        {
            self.dict_last_suggested_query = trimmed_q.clone();
            let (tx, rx) = std::sync::mpsc::channel();
            self.dict_suggest_rx = Some(rx);
            std::thread::spawn(move || {
                let list = dictionary::suggest_words(&trimmed_q);
                let _ = tx.send((trimmed_q, list));
            });
        } else if !self.dict_show_suggestions || trimmed_q.chars().count() < 2 {
            self.dict_suggestions.clear();
            if trimmed_q.chars().count() < 2 {
                self.dict_last_suggested_query.clear();
            }
        }

        // Vérifie la fin du scan en arrière-plan
        let scan_snapshot = self.scan_progress.lock().map(|p| p.clone()).unwrap_or_default();
        if self.scan_was_running && !scan_snapshot.running {
            self.scan_was_running = false;
            self.reload_data();
            if let Some(err) = scan_snapshot.error {
                self.show_toast(err, true);
            } else if scan_snapshot.completed {
                self.show_toast(
                    format!(
                        "Scan terminé : {} ajouté(s), {} mis à jour.",
                        scan_snapshot.added_episodes, scan_snapshot.updated_episodes
                    ),
                    false,
                );
            }
        }

        let is_playing = self.audio.as_ref().map(|a| a.is_playing()).unwrap_or(false);
        let is_finished = self.audio.as_ref().map(|a| a.is_finished()).unwrap_or(false);

        // Minuterie d'arrêt
        if is_playing {
            if let SleepTimerMode::Countdown(secs) = self.sleep_timer {
                if self.last_persist.elapsed() >= Duration::from_secs(1) {
                    if secs <= 1 {
                        self.sleep_timer = SleepTimerMode::Off;
                        if let Some(engine) = &mut self.audio {
                            engine.pause();
                        }
                        self.persist_current_playback(1000);
                        self.show_toast("Minuterie d'arrêt écoulée — lecture mise en pause.", false);
                        self.last_persist = now;
                        return;
                    } else {
                        self.sleep_timer = SleepTimerMode::Countdown(secs - 1);
                    }
                }
            }
        }

        // Persistance toutes les secondes pendant la lecture
        if is_playing && self.last_persist.elapsed() >= Duration::from_secs(1) {
            let delta = self.last_persist.elapsed().as_millis().min(2000) as i64;
            self.persist_current_playback(delta);
            self.last_persist = now;
        }

        // Fin d'épisode -> marquer terminé + enchaîner sur la file d'attente
        if is_finished && self.current_episode_id.is_some() {
            if let Some(ep_id) = self.current_episode_id {
                let _ = self.db.set_episode_played(ep_id, true);
                if let Some(ep) = self.episodes.iter_mut().find(|e| e.id == ep_id) {
                    ep.is_played = true;
                    let dur = ep.duration_ms;
                    let mut st = self.states.get(&ep_id).cloned().unwrap_or(PlaybackState {
                        episode_id: ep_id,
                        position_ms: dur,
                        last_played_at: Utc::now().timestamp_millis(),
                        play_count: 1,
                        completed: true,
                        total_listened_ms: 0,
                    });
                    st.position_ms = dur;
                    st.completed = true;
                    let _ = self.db.upsert_playback_state(&st);
                    self.states.insert(ep_id, st);
                }
            }
            if let Some(engine) = &mut self.audio {
                engine.stop();
            }
            if self.sleep_timer == SleepTimerMode::EndOfEpisode {
                self.sleep_timer = SleepTimerMode::Off;
                self.show_toast("Fin de l'épisode — lecture arrêtée par la minuterie.", false);
            } else if !self.queue.is_empty() {
                let next_id = self.queue.remove(0);
                self.play_episode(next_id, None);
            }
        }
        let _ = dt;
    }

    fn handle_actions(&mut self, ctx: &egui::Context, actions: Vec<AppAction>) {
        for action in actions {
            match action {
                AppAction::Navigate(s) => self.screen = s,
                AppAction::PlayEpisode {
                    episode_id,
                    position_ms,
                } => {
                    self.play_episode(episode_id, position_ms);
                }
                AppAction::TogglePlayPause => {
                    if let Some(engine) = &mut self.audio {
                        if engine.is_playing() {
                            engine.pause();
                            self.persist_current_playback(0);
                        } else if engine.is_loaded() {
                            let paused_for = engine.resume().unwrap_or(0);
                            if self.prefs.smart_rewind {
                                let rewind = PlaybackRules::smart_rewind_ms(paused_for);
                                if rewind > 0 {
                                    let target = (engine.position_ms() - rewind).max(0);
                                    engine.seek_ms(target);
                                }
                            }
                            self.last_persist = Instant::now();
                        }
                    }
                }
                AppAction::SkipBack => {
                    if let Some(engine) = &mut self.audio {
                        let target = (engine.position_ms() - (self.prefs.rewind_seconds as i64) * 1000).max(0);
                        engine.seek_ms(target);
                    }
                }
                AppAction::SkipForward => {
                    if let Some(engine) = &mut self.audio {
                        let target = engine.position_ms() + (self.prefs.forward_seconds as i64) * 1000;
                        engine.seek_ms(target);
                    }
                }
                AppAction::SeekMs(ms) => {
                    if let Some(engine) = &mut self.audio {
                        engine.seek_ms(ms);
                    }
                }
                AppAction::SetVolume(vol) => {
                    self.prefs.volume = vol.clamp(0.0, 1.0);
                    if let Some(engine) = &mut self.audio {
                        engine.set_volume(self.prefs.volume);
                    }
                    self.save_prefs();
                }
                AppAction::ToggleMute => {
                    if let Some(engine) = &mut self.audio {
                        engine.toggle_mute();
                    }
                }
                AppAction::SetSpeed(sp) => {
                    if let Some(ep) = self.current_episode().cloned() {
                        self.prefs.set_speed_for_podcast(ep.podcast_id, sp);
                        self.save_prefs();
                    }
                    if let Some(engine) = &mut self.audio {
                        engine.set_speed(sp);
                    }
                }
                AppAction::SetPodcastSpeed(pid, sp) => {
                    self.prefs.set_speed_for_podcast(pid, sp);
                    self.save_prefs();
                    if self.current_episode().map(|e| e.podcast_id) == Some(pid) {
                        if let Some(engine) = &mut self.audio {
                            engine.set_speed(sp);
                        }
                    }
                }
                AppAction::ToggleFavorite(ep_id) => {
                    if let Some(ep) = self.episodes.iter_mut().find(|e| e.id == ep_id) {
                        ep.is_favorite = !ep.is_favorite;
                        let _ = self.db.set_episode_favorite(ep_id, ep.is_favorite);
                    }
                }
                AppAction::TogglePlayed(ep_id) => {
                    if let Some(ep) = self.episodes.iter_mut().find(|e| e.id == ep_id) {
                        ep.is_played = !ep.is_played;
                        let _ = self.db.set_episode_played(ep_id, ep.is_played);
                        if let Some(st) = self.states.get_mut(&ep_id) {
                            st.completed = ep.is_played;
                            if !ep.is_played {
                                st.position_ms = 0;
                            }
                            let _ = self.db.upsert_playback_state(st);
                        }
                    }
                }
                AppAction::QueuePlayNext(ep_id) => {
                    self.queue.retain(|&id| id != ep_id);
                    self.queue.insert(0, ep_id);
                    self.show_toast("Ajouté en tête de file (« Lire ensuite »).", false);
                }
                AppAction::QueueAddEnd(ep_id) => {
                    if !self.queue.contains(&ep_id) {
                        self.queue.push(ep_id);
                    }
                    self.show_toast("Ajouté à la file d'attente.", false);
                }
                AppAction::QueueRemoveAt(idx) => {
                    if idx < self.queue.len() {
                        self.queue.remove(idx);
                    }
                }
                AppAction::QueueMoveUp(idx) => {
                    if idx > 0 && idx < self.queue.len() {
                        self.queue.swap(idx, idx - 1);
                    }
                }
                AppAction::QueueMoveDown(idx) => {
                    if idx + 1 < self.queue.len() {
                        self.queue.swap(idx, idx + 1);
                    }
                }
                AppAction::QueueClear => {
                    self.queue.clear();
                    self.show_toast("File d'attente vidée.", false);
                }
                AppAction::PlayNextInQueue => {
                    if !self.queue.is_empty() {
                        let next_id = self.queue.remove(0);
                        self.play_episode(next_id, None);
                    }
                }
                AppAction::SetSleepTimer(mode) => {
                    self.sleep_timer = mode;
                }
                AppAction::OpenAddBookmark(episode_id, position_ms) => {
                    self.add_bookmark_modal = Some(AddBookmarkModal {
                        episode_id,
                        position_ms,
                        title: String::new(),
                        note: String::new(),
                    });
                }
                AppAction::DeleteBookmark(id) => {
                    let _ = self.db.delete_bookmark(id);
                    self.bookmarks.retain(|b| b.id != id);
                    self.show_toast("Point mémoire supprimé.", false);
                }
                AppAction::OpenEditPodcast(pid) => {
                    if let Some(pod) = self.podcasts.iter().find(|p| p.id == pid) {
                        self.edit_podcast_modal = Some(EditPodcastModal {
                            podcast_id: pid,
                            title: pod.display_title().to_string(),
                            author: pod.display_author().unwrap_or("").to_string(),
                            cover_uri: pod.user_cover_uri.clone(),
                        });
                    }
                }
                AppAction::OpenEditEpisode(eid) => {
                    if let Some(ep) = self.episodes.iter().find(|e| e.id == eid) {
                        self.edit_episode_modal = Some(EditEpisodeModal {
                            episode_id: eid,
                            title: ep.display_title().to_string(),
                            author: ep.user_author.clone().unwrap_or_default(),
                            cover_uri: ep.user_cover_uri.clone(),
                        });
                    }
                }
                AppAction::AddWatchedFolder => {
                    if let Some(folder) = rfd::FileDialog::new()
                        .set_title("Choisir un dossier de podcasts")
                        .pick_folder()
                    {
                        let s = folder.to_string_lossy().to_string();
                        if !self.prefs.watched_folders.contains(&s) {
                            self.prefs.watched_folders.push(s);
                            self.save_prefs();
                        }
                        self.start_scan();
                    }
                }
                AppAction::RemoveWatchedFolder(folder) => {
                    self.prefs.watched_folders.retain(|f| f != &folder);
                    self.save_prefs();
                }
                AppAction::StartScan => self.start_scan(),
                AppAction::ExportBackup => {
                    let default_name = format!(
                        "podvault-backup-{}.json",
                        Local::now().format("%Y-%m-%d")
                    );
                    if let Some(path) = rfd::FileDialog::new()
                        .set_title("Exporter la sauvegarde PodVault")
                        .set_file_name(&default_name)
                        .add_filter("JSON", &["json"])
                        .save_file()
                    {
                        match backup::export_backup(&self.db, &path) {
                            Ok(()) => self.show_toast("Sauvegarde exportée avec succès.", false),
                            Err(e) => self.show_toast(e, true),
                        }
                    }
                }
                AppAction::ImportBackup => {
                    if let Some(path) = rfd::FileDialog::new()
                        .set_title("Importer une sauvegarde PodVault")
                        .add_filter("JSON", &["json"])
                        .pick_file()
                    {
                        match backup::import_backup(&self.db, &path) {
                            Ok(summary) => {
                                self.reload_data();
                                self.show_toast(
                                    format!(
                                        "Import réussi : {} podcast(s), {} épisode(s), {} point(s) mémoire, {} position(s).",
                                        summary.podcasts, summary.episodes, summary.bookmarks, summary.playback_states
                                    ),
                                    false,
                                );
                            }
                            Err(e) => self.show_toast(e, true),
                        }
                    }
                }
                AppAction::SetSkipIntervals(rew, fwd) => {
                    self.prefs.rewind_seconds = rew;
                    self.prefs.forward_seconds = fwd;
                    self.save_prefs();
                }
                AppAction::SetDefaultSpeed(sp) => {
                    self.prefs.default_speed = sp;
                    self.save_prefs();
                }
                AppAction::SetSmartRewind(v) => {
                    self.prefs.smart_rewind = v;
                    self.save_prefs();
                }
                AppAction::SetAmbientMode(v) => {
                    self.prefs.ambient_mode = v;
                    self.save_prefs();
                }
                AppAction::SetTheme(mode) => {
                    self.prefs.theme = mode;
                    self.save_prefs();
                    apply_theme(ctx, mode);
                }
                AppAction::SetEpisodeFilter(f) => self.episode_filter = f,
                AppAction::SetEpisodeSort(s) => self.episode_sort = s,
                AppAction::LookupWord(word) => {
                    self.dict_loading = true;
                    self.dict_result = None;
                    self.dict_show_suggestions = false;
                    self.dict_suggestions.clear();
                    let (tx, rx) = std::sync::mpsc::channel();
                    self.dict_rx = Some(rx);
                    std::thread::spawn(move || {
                        let _ = tx.send(dictionary::lookup_word(&word));
                    });
                }
            }
        }
    }

    fn render_modals(&mut self, ctx: &egui::Context, p: Palette) {
        // Modal : Nouveau point mémoire
        if let Some(mut modal) = self.add_bookmark_modal.take() {
            let mut keep_open = true;
            let mut saved = false;
            egui::Window::new("Nouveau point mémoire")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new(format!("Position : {}", format_duration_ms(modal.position_ms)))
                            .color(p.accent)
                            .strong(),
                    );
                    ui.add_space(6.0);
                    ui.label("Titre (optionnel) :");
                    ui.text_edit_singleline(&mut modal.title);
                    ui.add_space(4.0);
                    ui.label("Note (optionnelle) :");
                    ui.text_edit_multiline(&mut modal.note);
                    ui.add_space(10.0);
                    ui.horizontal(|ui| {
                        if accent_button(ui, p, "Enregistrer").clicked() {
                            saved = true;
                            keep_open = false;
                        }
                        if ui.button("Annuler").clicked() {
                            keep_open = false;
                        }
                    });
                });
            if saved {
                let now = Utc::now().timestamp_millis();
                let bm = Bookmark {
                    id: 0,
                    episode_id: modal.episode_id,
                    position_ms: modal.position_ms,
                    title: (!modal.title.trim().is_empty()).then(|| modal.title.trim().to_string()),
                    note: (!modal.note.trim().is_empty()).then(|| modal.note.trim().to_string()),
                    created_at: now,
                    updated_at: now,
                };
                if self.db.insert_bookmark(&bm).is_ok() {
                    self.bookmarks = self.db.get_all_bookmarks().unwrap_or_default();
                    self.show_toast("Point mémoire enregistré.", false);
                }
            } else if keep_open {
                self.add_bookmark_modal = Some(modal);
            }
        }

        // Modal : Modifier un podcast
        if let Some(mut modal) = self.edit_podcast_modal.take() {
            let mut keep_open = true;
            let mut save = false;
            let mut reset = false;
            egui::Window::new("Modifier le podcast")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label(
                        RichText::new("Les modifications sont enregistrées localement sans modifier vos fichiers audio.")
                            .color(p.on_surface_variant)
                            .size(11.5),
                    );
                    ui.add_space(6.0);
                    ui.label("Titre :");
                    ui.text_edit_singleline(&mut modal.title);
                    ui.label("Auteur :");
                    ui.text_edit_singleline(&mut modal.author);
                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        if ui.button("🖼 Choisir une pochette...").clicked() {
                            if let Some(img) = rfd::FileDialog::new()
                                .add_filter("Images", &["png", "jpg", "jpeg", "webp"])
                                .pick_file()
                            {
                                modal.cover_uri = Some(img.to_string_lossy().to_string());
                            }
                        }
                        if modal.cover_uri.is_some() && ui.button("Retirer").clicked() {
                            modal.cover_uri = None;
                        }
                    });
                    ui.add_space(10.0);
                    ui.horizontal(|ui| {
                        if accent_button(ui, p, "Enregistrer").clicked() {
                            save = true;
                            keep_open = false;
                        }
                        if ui.button("Réinitialiser").clicked() {
                            reset = true;
                            keep_open = false;
                        }
                        if ui.button("Annuler").clicked() {
                            keep_open = false;
                        }
                    });
                });

            let now = Utc::now().timestamp_millis();
            if save {
                let ut = (!modal.title.trim().is_empty()).then(|| modal.title.trim());
                let ua = (!modal.author.trim().is_empty()).then(|| modal.author.trim());
                let _ = self.db.update_podcast_user_meta(
                    modal.podcast_id,
                    ut,
                    ua,
                    modal.cover_uri.as_deref(),
                    now,
                );
                self.reload_data();
                self.show_toast("Podcast mis à jour.", false);
            } else if reset {
                let _ = self
                    .db
                    .update_podcast_user_meta(modal.podcast_id, None, None, None, now);
                self.reload_data();
                self.show_toast("Métadonnées du podcast réinitialisées.", false);
            } else if keep_open {
                self.edit_podcast_modal = Some(modal);
            }
        }

        // Modal : Modifier un épisode
        if let Some(mut modal) = self.edit_episode_modal.take() {
            let mut keep_open = true;
            let mut save = false;
            let mut reset = false;
            egui::Window::new("Modifier l'épisode")
                .collapsible(false)
                .resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                .show(ctx, |ui| {
                    ui.label("Titre :");
                    ui.text_edit_singleline(&mut modal.title);
                    ui.label("Auteur (optionnel) :");
                    ui.text_edit_singleline(&mut modal.author);
                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        if ui.button("🖼 Choisir une pochette...").clicked() {
                            if let Some(img) = rfd::FileDialog::new()
                                .add_filter("Images", &["png", "jpg", "jpeg", "webp"])
                                .pick_file()
                            {
                                modal.cover_uri = Some(img.to_string_lossy().to_string());
                            }
                        }
                        if modal.cover_uri.is_some() && ui.button("Retirer").clicked() {
                            modal.cover_uri = None;
                        }
                    });
                    ui.add_space(10.0);
                    ui.horizontal(|ui| {
                        if accent_button(ui, p, "Enregistrer").clicked() {
                            save = true;
                            keep_open = false;
                        }
                        if ui.button("Réinitialiser").clicked() {
                            reset = true;
                            keep_open = false;
                        }
                        if ui.button("Annuler").clicked() {
                            keep_open = false;
                        }
                    });
                });

            let now = Utc::now().timestamp_millis();
            if save {
                let ut = (!modal.title.trim().is_empty()).then(|| modal.title.trim());
                let ua = (!modal.author.trim().is_empty()).then(|| modal.author.trim());
                let _ = self.db.update_episode_user_meta(
                    modal.episode_id,
                    ut,
                    ua,
                    modal.cover_uri.as_deref(),
                    now,
                );
                self.reload_data();
                self.show_toast("Épisode mis à jour.", false);
            } else if reset {
                let _ = self
                    .db
                    .update_episode_user_meta(modal.episode_id, None, None, None, now);
                self.reload_data();
                self.show_toast("Métadonnées de l'épisode réinitialisées.", false);
            } else if keep_open {
                self.edit_episode_modal = Some(modal);
            }
        }
    }
}

impl eframe::App for PodVaultApp {
    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.persist_current_playback(0);
        self.save_prefs();
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if !self.theme_applied {
            apply_theme(ctx, self.prefs.theme);
            self.theme_applied = true;
        }

        self.tick();
        self.covers.begin_frame();
        let p = Palette::for_mode(self.prefs.theme);
        let mut actions: Vec<AppAction> = Vec::new();

        // Raccourcis clavier globaux (hors saisie texte)
        if !ctx.wants_keyboard_input() {
            ctx.input(|i| {
                if i.key_pressed(egui::Key::Space) {
                    actions.push(AppAction::TogglePlayPause);
                }
                if i.key_pressed(egui::Key::ArrowLeft) {
                    actions.push(AppAction::SkipBack);
                }
                if i.key_pressed(egui::Key::ArrowRight) {
                    actions.push(AppAction::SkipForward);
                }
                if i.key_pressed(egui::Key::M) {
                    actions.push(AppAction::ToggleMute);
                }
            });
        }

        let is_playing = self.audio.as_ref().map(|a| a.is_playing()).unwrap_or(false);
        let position_ms = self.audio.as_ref().map(|a| a.position_ms()).unwrap_or(0);
        let speed = self.audio.as_ref().map(|a| a.speed()).unwrap_or(self.prefs.default_speed);
        let volume = self.audio.as_ref().map(|a| a.volume()).unwrap_or(self.prefs.volume);
        let muted = self.audio.as_ref().map(|a| a.is_muted()).unwrap_or(false);
        let scan_snapshot = self.scan_progress.lock().map(|s| s.clone()).unwrap_or_default();

        if is_playing
            || scan_snapshot.running
            || self.toast.is_some()
            || self.dict_loading
            || self.dict_suggest_rx.is_some()
            || (self.dict_show_suggestions && self.dict_query.trim() != self.dict_last_suggested_query)
        {
            ctx.request_repaint_after(Duration::from_millis(120));
        }

        // Barre latérale de navigation
        egui::SidePanel::left("nav_sidebar")
            .resizable(false)
            .exact_width(210.0)
            .frame(
                egui::Frame::new()
                    .fill(p.surface)
                    .stroke(Stroke::new(1.0_f32, p.outline.linear_multiply(0.45)))
                    .inner_margin(Margin::same(14)),
            )
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new("PodVault")
                            .size(22.0)
                            .strong()
                            .color(p.accent),
                    );
                });
                ui.label(
                    RichText::new("Podcasts 100 % locaux")
                        .size(11.5)
                        .color(p.on_surface_variant),
                );
                ui.add_space(16.0);

                let nav_items: [(&str, Screen); 9] = [
                    ("🏠  Accueil", Screen::Home),
                    ("📚  Bibliothèque", Screen::Library),
                    ("▶  Lecteur", Screen::Player),
                    ("☰  File d'attente", Screen::Queue),
                    ("🔖  Points mémoire", Screen::Bookmarks),
                    ("🔍  Recherche", Screen::Search),
                    ("📖  Dictionnaire", Screen::Dictionary),
                    ("📊  Statistiques", Screen::Statistics),
                    ("⚙  Paramètres", Screen::Settings),
                ];

                for (label, target) in nav_items {
                    let selected = match (&self.screen, &target) {
                        (Screen::PodcastDetail(_), Screen::Library)
                        | (Screen::Duplicates, Screen::Library) => true,
                        (a, b) => a == b,
                    };
                    let btn_text = if target == Screen::Queue && !self.queue.is_empty() {
                        format!("{label} ({})", self.queue.len())
                    } else {
                        label.to_string()
                    };
                    let fill = if selected {
                        p.primary_container
                    } else {
                        Color32::TRANSPARENT
                    };
                    let txt_color = if selected {
                        p.on_primary_container
                    } else {
                        p.on_surface
                    };
                    let btn = egui::Button::new(RichText::new(btn_text).color(txt_color).size(14.0))
                        .fill(fill)
                        .stroke(Stroke::NONE)
                        .corner_radius(CornerRadius::same(8))
                        .min_size(egui::vec2(ui.available_width(), 34.0));
                    if ui.add(btn).clicked() {
                        actions.push(AppAction::Navigate(target));
                    }
                    ui.add_space(3.0);
                }

                ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
                    ui.label(
                        RichText::new(format!("v{}", env!("CARGO_PKG_VERSION")))
                            .size(11.0)
                            .color(p.on_surface_variant),
                    );
                    if scan_snapshot.running {
                        ui.label(
                            RichText::new(format!(
                                "Scan {}/{}...",
                                scan_snapshot.scanned_files, scan_snapshot.total_files
                            ))
                            .size(11.5)
                            .color(p.accent),
                        );
                    }
                });
            });

        // Mini-lecteur persistant en bas (sauf sur l'écran Lecteur complet)
        if self.screen != Screen::Player {
            if let Some(ep) = self.current_episode().cloned() {
                let pod = self.current_podcast().cloned();
                egui::TopBottomPanel::bottom("mini_player")
                    .frame(egui::Frame::NONE)
                    .show(ctx, |ui| {
                        player::render_mini_player(
                            ui,
                            p,
                            &mut self.covers,
                            &ep,
                            pod.as_ref(),
                            position_ms,
                            is_playing,
                            volume,
                            muted,
                            self.sleep_timer,
                            self.queue.len(),
                            &self.prefs,
                            &mut actions,
                        );
                    });
            }
        }

        // Contenu principal
        egui::CentralPanel::default()
            .frame(
                egui::Frame::new()
                    .fill(p.background)
                    .inner_margin(Margin::same(18)),
            )
            .show(ctx, |ui| {
                // Notification toast
                if let Some(toast) = &self.toast {
                    let (bg, fg) = if toast.is_error {
                        (Color32::from_rgb(0x5A, 0x1F, 0x1F), p.error)
                    } else {
                        (p.primary_container, p.on_primary_container)
                    };
                    egui::Frame::new()
                        .fill(bg)
                        .corner_radius(CornerRadius::same(8))
                        .inner_margin(Margin::symmetric(12, 8))
                        .show(ui, |ui| {
                            ui.label(RichText::new(&toast.message).color(fg).strong());
                        });
                    ui.add_space(8.0);
                }

                let current_podcast_id = self.current_episode().map(|e| e.podcast_id);
                match self.screen.clone() {
                    Screen::Home => home::render_home(
                        ui,
                        p,
                        &mut self.covers,
                        &self.podcasts,
                        &self.episodes,
                        &self.states,
                        !self.prefs.watched_folders.is_empty(),
                        self.current_episode_id,
                        is_playing,
                        &mut actions,
                    ),
                    Screen::Library => library::render_library(
                        ui,
                        p,
                        &mut self.covers,
                        &self.podcasts,
                        &self.episodes,
                        &scan_snapshot,
                        current_podcast_id,
                        is_playing,
                        &mut actions,
                    ),
                    Screen::PodcastDetail(pid) => {
                        if let Some(pod) = self.podcasts.iter().find(|x| x.id == pid) {
                            library::render_podcast_detail(
                                ui,
                                p,
                                &mut self.covers,
                                pod,
                                &self.episodes,
                                &self.states,
                                &self.prefs,
                                self.episode_filter,
                                self.episode_sort,
                                self.current_episode_id,
                                is_playing,
                                &mut actions,
                            );
                        } else {
                            self.screen = Screen::Library;
                        }
                    }
                    Screen::Duplicates => library::render_duplicates(
                        ui,
                        p,
                        &self.podcasts,
                        &self.episodes,
                        &mut actions,
                    ),
                    Screen::Player => {
                        let ep = self.current_episode().cloned();
                        let pod = self.current_podcast().cloned();
                        player::render_player_screen(
                            ui,
                            p,
                            ep.as_ref(),
                            pod.as_ref(),
                            &self.bookmarks,
                            position_ms,
                            is_playing,
                            speed,
                            volume,
                            muted,
                            self.sleep_timer,
                            self.queue.len(),
                            &self.prefs,
                            &mut self.covers,
                            &mut actions,
                        );
                    }
                    Screen::Queue => player::render_queue_screen(
                        ui,
                        p,
                        &self.queue,
                        &self.episodes,
                        &self.podcasts,
                        &mut actions,
                    ),
                    Screen::Bookmarks => extra::render_bookmarks(
                        ui,
                        p,
                        &self.bookmarks,
                        &self.episodes,
                        &self.podcasts,
                        &mut actions,
                    ),
                    Screen::Search => extra::render_search(
                        ui,
                        p,
                        &mut self.search_query,
                        &self.podcasts,
                        &self.episodes,
                        &self.bookmarks,
                        &mut actions,
                    ),
                    Screen::Dictionary => {
                        let prev_q = self.dict_query.clone();
                        extra::render_dictionary(
                            ui,
                            p,
                            &mut self.dict_query,
                            self.dict_loading,
                            self.dict_result.as_ref(),
                            &self.dict_suggestions,
                            &mut self.dict_show_suggestions,
                            &self.dict_recent,
                            &mut actions,
                        );
                        if self.dict_query != prev_q {
                            self.dict_last_edit = Instant::now();
                            if self.dict_query.trim().is_empty() {
                                self.dict_result = None;
                            }
                        }
                    }
                    Screen::Statistics => extra::render_statistics(
                        ui,
                        p,
                        &self.podcasts,
                        &self.episodes,
                        &self.states,
                        &self.bookmarks,
                        &self.listening_days,
                    ),
                    Screen::Settings => extra::render_settings(
                        ui,
                        p,
                        &self.prefs,
                        &scan_snapshot,
                        &mut actions,
                    ),
                }
            });

        self.render_modals(ctx, p);
        self.handle_actions(ctx, actions);
    }
}
