use crate::db::Database;
use crate::model::{Episode, Podcast, ScanProgress};
use chrono::Utc;
use lofty::config::ParseOptions;
use lofty::file::{AudioFile, TaggedFileExt};
use lofty::probe::Probe;
use lofty::tag::Accessor;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use walkdir::WalkDir;

const AUDIO_EXTENSIONS: &[&str] = &["mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav"];

pub fn is_audio_file(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|ext| AUDIO_EXTENSIONS.contains(&ext.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

fn sanitize_text(raw: &str) -> String {
    raw.chars()
        .filter(|c| !c.is_control() || *c == '\n' || *c == '\t')
        .collect::<String>()
        .trim()
        .to_string()
}

fn clean_filename_title(file_name: &str) -> String {
    let stem = Path::new(file_name)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or(file_name);
    let replaced = stem.replace(['_', '.'], " ");
    let collapsed = replaced.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() {
        file_name.to_string()
    } else {
        collapsed
    }
}

fn cache_cover_bytes(covers_dir: &Path, bytes: &[u8]) -> Option<String> {
    let _ = std::fs::create_dir_all(covers_dir);
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let hash = format!("{:x}", hasher.finalize());
    let ext = if bytes.starts_with(&[0x89, 0x50, 0x4E, 0x47]) {
        "png"
    } else if bytes.starts_with(b"RIFF") {
        "webp"
    } else {
        "jpg"
    };
    let target = covers_dir.join(format!("{}.{ext}", &hash[..16]));
    if !target.exists()
        && std::fs::write(&target, bytes).is_err() {
            return None;
        }
    Some(target.to_string_lossy().to_string())
}

fn find_folder_cover(folder: &Path) -> Option<String> {
    const NAMES: &[&str] = &[
        "cover.jpg",
        "cover.png",
        "cover.jpeg",
        "folder.jpg",
        "folder.png",
        "artwork.jpg",
        "artwork.png",
    ];
    for name in NAMES {
        let p = folder.join(name);
        if p.is_file() {
            return Some(p.to_string_lossy().to_string());
        }
    }
    None
}

struct ExtractedMeta {
    title: String,
    album: Option<String>,
    artist: Option<String>,
    description: Option<String>,
    duration_ms: i64,
    codec: Option<String>,
    bitrate: Option<i32>,
    season_number: Option<i32>,
    episode_number: Option<i32>,
    publication_date: Option<i64>,
    cover_uri: Option<String>,
}

fn extract_metadata(path: &Path, file_name: &str, covers_dir: &Path) -> ExtractedMeta {
    let mut meta = ExtractedMeta {
        title: clean_filename_title(file_name),
        album: None,
        artist: None,
        description: None,
        duration_ms: 0,
        codec: path
            .extension()
            .and_then(|e| e.to_str())
            .map(|s| s.to_ascii_uppercase()),
        bitrate: None,
        season_number: None,
        episode_number: None,
        publication_date: None,
        cover_uri: None,
    };

    let probe = match Probe::open(path) {
        Ok(p) => p.options(ParseOptions::new()),
        Err(_) => return meta,
    };

    let Ok(tagged_file) = probe.read() else {
        return meta;
    };

    let props = tagged_file.properties();
    meta.duration_ms = props.duration().as_millis() as i64;
    meta.bitrate = props.audio_bitrate().map(|b| b as i32);

    if let Some(tag) = tagged_file
        .primary_tag()
        .or_else(|| tagged_file.first_tag())
    {
        if let Some(t) = tag.title() {
            let s = sanitize_text(&t);
            if !s.is_empty() {
                meta.title = s;
            }
        }
        if let Some(a) = tag.album() {
            let s = sanitize_text(&a);
            if !s.is_empty() {
                meta.album = Some(s);
            }
        }
        if let Some(ar) = tag.artist() {
            let s = sanitize_text(&ar);
            if !s.is_empty() {
                meta.artist = Some(s);
            }
        }
        if let Some(c) = tag.comment() {
            let s = sanitize_text(&c);
            if !s.is_empty() {
                meta.description = Some(s);
            }
        }
        if let Some(track) = tag.track() {
            if track > 0 {
                meta.episode_number = Some(track as i32);
            }
        }
        if let Some(disk) = tag.disk() {
            if disk > 0 {
                meta.season_number = Some(disk as i32);
            }
        }
        if let Some(year) = tag.year() {
            if (1970..=2100).contains(&year) {
                if let Some(dt) = chrono::NaiveDate::from_ymd_opt(year as i32, 1, 1)
                    .and_then(|d| d.and_hms_opt(0, 0, 0))
                {
                    meta.publication_date = Some(dt.and_utc().timestamp_millis());
                }
            }
        }
        if let Some(pic) = tag.pictures().first() {
            if !pic.data().is_empty() {
                meta.cover_uri = cache_cover_bytes(covers_dir, pic.data());
            }
        }
    }

    meta
}

/// Lance un scan récursif de tous les dossiers surveillés dans un thread d'arrière-plan.
pub fn scan_folders(
    db_path: PathBuf,
    covers_dir: PathBuf,
    folders: Vec<String>,
    progress: Arc<Mutex<ScanProgress>>,
) {
    let update = |f: &dyn Fn(&mut ScanProgress)| {
        if let Ok(mut p) = progress.lock() {
            f(&mut p);
        }
    };

    update(&|p| {
        *p = ScanProgress {
            running: true,
            ..Default::default()
        };
    });

    let db = match Database::open(&db_path) {
        Ok(d) => d,
        Err(e) => {
            update(&|p| {
                p.running = false;
                p.error = Some(format!("Erreur base de données : {e}"));
            });
            return;
        }
    };

    let mut files: Vec<PathBuf> = Vec::new();
    let mut accessible_roots = 0usize;

    for folder in &folders {
        let root = PathBuf::from(folder);
        if !root.exists() || !root.is_dir() {
            continue;
        }
        accessible_roots += 1;
        for entry in WalkDir::new(&root).follow_links(false).into_iter().flatten() {
            if entry.file_type().is_file() && is_audio_file(entry.path()) {
                files.push(entry.into_path());
            }
        }
    }

    let total = files.len();
    update(&|p| {
        p.total_files = total;
    });

    let now = Utc::now().timestamp_millis();
    let mut seen_uris: HashSet<String> = HashSet::with_capacity(total);
    let mut added = 0usize;
    let mut updated = 0usize;

    for (idx, path) in files.iter().enumerate() {
        let file_name = path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("audio")
            .to_string();
        let audio_uri = path.to_string_lossy().to_string();
        seen_uris.insert(audio_uri.clone());

        update(&|p| {
            p.scanned_files = idx + 1;
            p.current_file = file_name.clone();
        });

        let Ok(file_meta) = std::fs::metadata(path) else {
            continue;
        };
        let file_size = file_meta.len() as i64;
        let last_modified_file = file_meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        let existing = db.get_episode_by_uri(&audio_uri).ok().flatten();
        if let Some(mut ep) = existing {
            if ep.last_modified_file == last_modified_file && ep.file_size == file_size {
                if ep.is_missing {
                    let _ = db.set_episode_missing(ep.id, false);
                }
                continue;
            }
            let extracted = extract_metadata(path, &file_name, &covers_dir);
            ep.title = extracted.title;
            ep.description = extracted.description;
            ep.file_name = file_name;
            ep.file_size = file_size;
            ep.duration_ms = extracted.duration_ms;
            ep.cover_uri = extracted.cover_uri.or(ep.cover_uri);
            ep.publication_date = extracted.publication_date.or(ep.publication_date);
            ep.season_number = extracted.season_number.or(ep.season_number);
            ep.episode_number = extracted.episode_number.or(ep.episode_number);
            ep.codec = extracted.codec;
            ep.bitrate = extracted.bitrate;
            ep.modified_at = now;
            ep.last_modified_file = last_modified_file;
            ep.is_missing = false;
            if db.update_episode(&ep).is_ok() {
                updated += 1;
            }
            continue;
        }

        let extracted = extract_metadata(path, &file_name, &covers_dir);
        let parent_dir = path.parent().unwrap_or_else(|| Path::new(""));
        let folder_uri = parent_dir.to_string_lossy().to_string();
        let folder_name = parent_dir
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("Podcasts")
            .to_string();

        let (group_key, show_title) = if let Some(album) = &extracted.album {
            (format!("album:{}", album.to_lowercase()), album.clone())
        } else {
            (format!("folder:{}", folder_uri.to_lowercase()), folder_name)
        };

        let folder_cover = find_folder_cover(parent_dir);
        let podcast_cover = extracted.cover_uri.clone().or(folder_cover);

        let podcast_id = match db.get_podcast_by_group_key(&group_key).ok().flatten() {
            Some(mut p) => {
                if p.cover_uri.is_none() && podcast_cover.is_some() {
                    p.cover_uri = podcast_cover.clone();
                    p.updated_at = now;
                    let _ = db.update_podcast(&p);
                }
                p.id
            }
            None => {
                let new_podcast = Podcast {
                    id: 0,
                    title: show_title,
                    author: extracted.artist.clone(),
                    description: None,
                    cover_uri: podcast_cover.clone(),
                    folder_uri: folder_uri.clone(),
                    category: None,
                    group_key,
                    user_title: None,
                    user_author: None,
                    user_cover_uri: None,
                    created_at: now,
                    updated_at: now,
                };
                match db.insert_podcast(&new_podcast) {
                    Ok(id) => id,
                    Err(_) => continue,
                }
            }
        };

        let new_episode = Episode {
            id: 0,
            podcast_id,
            title: extracted.title,
            description: extracted.description,
            audio_uri,
            file_name,
            file_size,
            duration_ms: extracted.duration_ms,
            cover_uri: extracted.cover_uri.or(podcast_cover),
            publication_date: extracted.publication_date,
            season_number: extracted.season_number,
            episode_number: extracted.episode_number,
            codec: extracted.codec,
            bitrate: extracted.bitrate,
            added_at: now,
            modified_at: now,
            last_modified_file,
            is_favorite: false,
            is_played: false,
            is_missing: false,
            user_title: None,
            user_author: None,
            user_cover_uri: None,
        };
        if db.insert_episode(&new_episode).is_ok() {
            added += 1;
        }
    }

    // Marque les épisodes absents uniquement si tous les dossiers configurés étaient accessibles
    if accessible_roots == folders.len() && !folders.is_empty() {
        if let Ok(all_eps) = db.get_all_episodes() {
            for ep in all_eps {
                let under_watched = folders
                    .iter()
                    .any(|f| ep.audio_uri.to_lowercase().starts_with(&f.to_lowercase()));
                if under_watched {
                    let missing = !seen_uris.contains(&ep.audio_uri);
                    if ep.is_missing != missing {
                        let _ = db.set_episode_missing(ep.id, missing);
                    }
                }
            }
        }
    }

    update(&|p| {
        p.running = false;
        p.completed = true;
        p.added_episodes = added;
        p.updated_episodes = updated;
        p.current_file.clear();
    });
}
