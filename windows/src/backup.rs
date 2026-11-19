use crate::db::Database;
use crate::model::{Bookmark, Episode, ImportSummary, PlaybackState, Podcast};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;

const FORMAT_VERSION: i32 = 1;
const MAX_IMPORT_BYTES: u64 = 32 * 1024 * 1024;
const MAX_RECORDS: usize = 100_000;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupRoot {
    format_version: i32,
    podcasts: Vec<BackupPodcast>,
    episodes: Vec<BackupEpisode>,
    bookmarks: Vec<BackupBookmark>,
    playback_states: Vec<BackupPlaybackState>,
    #[serde(default)]
    listening_days: Vec<BackupListeningDay>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupPodcast {
    id: i64,
    title: String,
    author: Option<String>,
    description: Option<String>,
    cover_uri: Option<String>,
    folder_uri: String,
    category: Option<String>,
    #[serde(default)]
    group_key: String,
    user_title: Option<String>,
    user_author: Option<String>,
    user_cover_uri: Option<String>,
    #[serde(default)]
    created_at: i64,
    #[serde(default)]
    updated_at: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupEpisode {
    id: i64,
    podcast_id: i64,
    title: String,
    description: Option<String>,
    audio_uri: String,
    file_name: String,
    file_size: i64,
    duration_ms: i64,
    cover_uri: Option<String>,
    publication_date: Option<i64>,
    season_number: Option<i32>,
    episode_number: Option<i32>,
    codec: Option<String>,
    bitrate: Option<i32>,
    #[serde(default)]
    added_at: i64,
    #[serde(default)]
    modified_at: i64,
    #[serde(default)]
    last_modified_file: i64,
    #[serde(default)]
    is_favorite: bool,
    #[serde(default)]
    is_played: bool,
    #[serde(default)]
    is_missing: bool,
    user_title: Option<String>,
    user_author: Option<String>,
    user_cover_uri: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupBookmark {
    id: i64,
    episode_id: i64,
    position_ms: i64,
    title: Option<String>,
    note: Option<String>,
    #[serde(default)]
    created_at: i64,
    #[serde(default)]
    updated_at: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupPlaybackState {
    episode_id: i64,
    position_ms: i64,
    last_played_at: i64,
    #[serde(default)]
    play_count: i32,
    #[serde(default)]
    completed: bool,
    #[serde(default)]
    total_listened_ms: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BackupListeningDay {
    day: String,
    listened_ms: i64,
}

/// Export JSON 100 % compatible avec le format Android `formatVersion = 1`.
pub fn export_backup(db: &Database, path: &Path) -> Result<(), String> {
    let podcasts = db.get_all_podcasts().map_err(|e| e.to_string())?;
    let episodes = db.get_all_episodes().map_err(|e| e.to_string())?;
    let bookmarks = db.get_all_bookmarks().map_err(|e| e.to_string())?;
    let playback_states = db.get_all_playback_states().map_err(|e| e.to_string())?;
    let listening_days = db.get_all_listening_days().map_err(|e| e.to_string())?;

    let root = BackupRoot {
        format_version: FORMAT_VERSION,
        podcasts: podcasts
            .into_iter()
            .map(|p| BackupPodcast {
                id: p.id,
                title: p.title,
                author: p.author,
                description: p.description,
                cover_uri: p.cover_uri,
                folder_uri: p.folder_uri,
                category: p.category,
                group_key: p.group_key,
                user_title: p.user_title,
                user_author: p.user_author,
                user_cover_uri: p.user_cover_uri,
                created_at: p.created_at,
                updated_at: p.updated_at,
            })
            .collect(),
        episodes: episodes
            .into_iter()
            .map(|e| BackupEpisode {
                id: e.id,
                podcast_id: e.podcast_id,
                title: e.title,
                description: e.description,
                audio_uri: e.audio_uri,
                file_name: e.file_name,
                file_size: e.file_size,
                duration_ms: e.duration_ms,
                cover_uri: e.cover_uri,
                publication_date: e.publication_date,
                season_number: e.season_number,
                episode_number: e.episode_number,
                codec: e.codec,
                bitrate: e.bitrate,
                added_at: e.added_at,
                modified_at: e.modified_at,
                last_modified_file: e.last_modified_file,
                is_favorite: e.is_favorite,
                is_played: e.is_played,
                is_missing: e.is_missing,
                user_title: e.user_title,
                user_author: e.user_author,
                user_cover_uri: e.user_cover_uri,
            })
            .collect(),
        bookmarks: bookmarks
            .into_iter()
            .map(|b| BackupBookmark {
                id: b.id,
                episode_id: b.episode_id,
                position_ms: b.position_ms,
                title: b.title,
                note: b.note,
                created_at: b.created_at,
                updated_at: b.updated_at,
            })
            .collect(),
        playback_states: playback_states
            .into_iter()
            .map(|s| BackupPlaybackState {
                episode_id: s.episode_id,
                position_ms: s.position_ms,
                last_played_at: s.last_played_at,
                play_count: s.play_count,
                completed: s.completed,
                total_listened_ms: s.total_listened_ms,
            })
            .collect(),
        listening_days: listening_days
            .into_iter()
            .map(|d| BackupListeningDay {
                day: d.day,
                listened_ms: d.listened_ms,
            })
            .collect(),
    };

    let json = serde_json::to_string_pretty(&root).map_err(|e| e.to_string())?;
    std::fs::write(path, json).map_err(|e| format!("Impossible d'écrire la sauvegarde : {e}"))?;
    Ok(())
}

/// Import et fusion dans la bibliothèque existante.
pub fn import_backup(db: &Database, path: &Path) -> Result<ImportSummary, String> {
    let meta = std::fs::metadata(path).map_err(|e| format!("Fichier inaccessible : {e}"))?;
    if meta.len() > MAX_IMPORT_BYTES {
        return Err("Le fichier d'import dépasse la taille maximale autorisée (32 Mo).".into());
    }
    let text = std::fs::read_to_string(path).map_err(|e| format!("Lecture impossible : {e}"))?;
    let root: BackupRoot =
        serde_json::from_str(&text).map_err(|e| format!("Format JSON invalide : {e}"))?;

    if root.format_version != FORMAT_VERSION {
        return Err("Version de sauvegarde non prise en charge.".into());
    }
    if root.podcasts.len() > MAX_RECORDS
        || root.episodes.len() > MAX_RECORDS
        || root.bookmarks.len() > MAX_RECORDS
        || root.playback_states.len() > MAX_RECORDS
        || root.listening_days.len() > MAX_RECORDS
    {
        return Err("La sauvegarde contient trop d'éléments.".into());
    }

    let mut podcast_ids: HashMap<i64, i64> = HashMap::new();
    for p in &root.podcasts {
        let existing = if !p.group_key.trim().is_empty() {
            db.get_podcast_by_group_key(&p.group_key).map_err(|e| e.to_string())?
        } else {
            None
        }
        .or_else(|| {
            db.get_podcast_by_folder_uri(&p.folder_uri)
                .ok()
                .flatten()
                .filter(|ex| ex.title == p.title)
        });

        let target_id = if let Some(mut ex) = existing {
            if p.user_title.is_some() || p.user_author.is_some() || p.user_cover_uri.is_some() {
                ex.user_title = p.user_title.clone().or(ex.user_title);
                ex.user_author = p.user_author.clone().or(ex.user_author);
                ex.user_cover_uri = p.user_cover_uri.clone().or(ex.user_cover_uri);
                db.update_podcast(&ex).map_err(|e| e.to_string())?;
            }
            ex.id
        } else {
            let new_p = Podcast {
                id: 0,
                title: p.title.clone(),
                author: p.author.clone(),
                description: p.description.clone(),
                cover_uri: p.cover_uri.clone(),
                folder_uri: p.folder_uri.clone(),
                category: p.category.clone(),
                group_key: p.group_key.clone(),
                user_title: p.user_title.clone(),
                user_author: p.user_author.clone(),
                user_cover_uri: p.user_cover_uri.clone(),
                created_at: p.created_at,
                updated_at: p.updated_at,
            };
            db.insert_podcast(&new_p).map_err(|e| e.to_string())?
        };
        podcast_ids.insert(p.id, target_id);
    }

    let mut episode_ids: HashMap<i64, i64> = HashMap::new();
    for e in &root.episodes {
        let Some(&podcast_id) = podcast_ids.get(&e.podcast_id) else {
            return Err("Un épisode référence un podcast absent de la sauvegarde.".into());
        };
        let existing = db.get_episode_by_uri(&e.audio_uri).map_err(|err| err.to_string())?;
        let target_id = if let Some(mut ex) = existing {
            ex.user_title = e.user_title.clone().or(ex.user_title);
            ex.user_author = e.user_author.clone().or(ex.user_author);
            ex.user_cover_uri = e.user_cover_uri.clone().or(ex.user_cover_uri);
            ex.is_favorite = ex.is_favorite || e.is_favorite;
            ex.is_played = ex.is_played || e.is_played;
            db.update_episode(&ex).map_err(|err| err.to_string())?;
            ex.id
        } else {
            let new_ep = Episode {
                id: 0,
                podcast_id,
                title: e.title.clone(),
                description: e.description.clone(),
                audio_uri: e.audio_uri.clone(),
                file_name: e.file_name.clone(),
                file_size: e.file_size,
                duration_ms: e.duration_ms,
                cover_uri: e.cover_uri.clone(),
                publication_date: e.publication_date,
                season_number: e.season_number,
                episode_number: e.episode_number,
                codec: e.codec.clone(),
                bitrate: e.bitrate,
                added_at: e.added_at,
                modified_at: e.modified_at,
                last_modified_file: e.last_modified_file,
                is_favorite: e.is_favorite,
                is_played: e.is_played,
                is_missing: e.is_missing,
                user_title: e.user_title.clone(),
                user_author: e.user_author.clone(),
                user_cover_uri: e.user_cover_uri.clone(),
            };
            db.insert_episode(&new_ep).map_err(|err| err.to_string())?
        };
        episode_ids.insert(e.id, target_id);
    }

    for b in &root.bookmarks {
        let Some(&episode_id) = episode_ids.get(&b.episode_id) else {
            continue;
        };
        let exists = db
            .bookmark_exists(
                episode_id,
                b.position_ms,
                b.title.as_deref(),
                b.note.as_deref(),
            )
            .map_err(|e| e.to_string())?;
        if !exists {
            let nb = Bookmark {
                id: 0,
                episode_id,
                position_ms: b.position_ms,
                title: b.title.clone(),
                note: b.note.clone(),
                created_at: b.created_at,
                updated_at: b.updated_at,
            };
            db.insert_bookmark(&nb).map_err(|e| e.to_string())?;
        }
    }

    for s in &root.playback_states {
        let Some(&episode_id) = episode_ids.get(&s.episode_id) else {
            continue;
        };
        let current = db.get_playback_state(episode_id).map_err(|e| e.to_string())?;
        if current.is_none() || s.last_played_at > current.unwrap().last_played_at {
            let ns = PlaybackState {
                episode_id,
                position_ms: s.position_ms,
                last_played_at: s.last_played_at,
                play_count: s.play_count.max(0),
                completed: s.completed,
                total_listened_ms: s.total_listened_ms.max(0),
            };
            db.upsert_playback_state(&ns).map_err(|e| e.to_string())?;
        }
    }

    for d in &root.listening_days {
        if d.day.len() == 10 && d.listened_ms >= 0 {
            let _ = db.upsert_listening_day_max(&d.day, d.listened_ms);
        }
    }

    Ok(ImportSummary {
        podcasts: root.podcasts.len(),
        episodes: root.episodes.len(),
        bookmarks: root.bookmarks.len(),
        playback_states: root.playback_states.len(),
    })
}
