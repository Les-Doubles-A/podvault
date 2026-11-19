use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Podcast {
    pub id: i64,
    pub title: String,
    pub author: Option<String>,
    pub description: Option<String>,
    pub cover_uri: Option<String>,
    pub folder_uri: String,
    pub category: Option<String>,
    pub group_key: String,
    pub user_title: Option<String>,
    pub user_author: Option<String>,
    pub user_cover_uri: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

impl Podcast {
    pub fn display_title(&self) -> &str {
        self.user_title.as_deref().unwrap_or(&self.title)
    }

    pub fn display_author(&self) -> Option<&str> {
        self.user_author.as_deref().or(self.author.as_deref())
    }

    pub fn display_cover(&self) -> Option<&str> {
        self.user_cover_uri.as_deref().or(self.cover_uri.as_deref())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Episode {
    pub id: i64,
    pub podcast_id: i64,
    pub title: String,
    pub description: Option<String>,
    pub audio_uri: String,
    pub file_name: String,
    pub file_size: i64,
    pub duration_ms: i64,
    pub cover_uri: Option<String>,
    pub publication_date: Option<i64>,
    pub season_number: Option<i32>,
    pub episode_number: Option<i32>,
    pub codec: Option<String>,
    pub bitrate: Option<i32>,
    pub added_at: i64,
    pub modified_at: i64,
    pub last_modified_file: i64,
    pub is_favorite: bool,
    pub is_played: bool,
    pub is_missing: bool,
    pub user_title: Option<String>,
    pub user_author: Option<String>,
    pub user_cover_uri: Option<String>,
}

impl Episode {
    pub fn display_title(&self) -> &str {
        self.user_title.as_deref().unwrap_or(&self.title)
    }

    pub fn display_cover(&self) -> Option<&str> {
        self.user_cover_uri.as_deref().or(self.cover_uri.as_deref())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybackState {
    pub episode_id: i64,
    pub position_ms: i64,
    pub last_played_at: i64,
    pub play_count: i32,
    pub completed: bool,
    pub total_listened_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Bookmark {
    pub id: i64,
    pub episode_id: i64,
    pub position_ms: i64,
    pub title: Option<String>,
    pub note: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ListeningDay {
    pub day: String, // YYYY-MM-DD
    pub listened_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PodcastSignal {
    pub podcast_id: i64,
    pub listened_ms: i64,
    pub completed_count: i32,
    pub last_played_at: i64,
    pub favorite_count: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThemeMode {
    Dark,
    Light,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Preferences {
    pub watched_folders: Vec<String>,
    pub default_speed: f32,
    pub podcast_speeds: HashMap<i64, f32>,
    pub rewind_seconds: i32,
    pub forward_seconds: i32,
    pub smart_rewind: bool,
    pub ambient_mode: bool,
    pub theme: ThemeMode,
    pub volume: f32,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            watched_folders: Vec::new(),
            default_speed: 1.0,
            podcast_speeds: HashMap::new(),
            rewind_seconds: 15,
            forward_seconds: 30,
            smart_rewind: true,
            ambient_mode: false,
            theme: ThemeMode::Dark,
            volume: 1.0,
        }
    }
}

impl Preferences {
    pub fn speed_for_podcast(&self, podcast_id: i64) -> f32 {
        self.podcast_speeds
            .get(&podcast_id)
            .copied()
            .unwrap_or(self.default_speed)
            .clamp(0.5, 3.0)
    }

    pub fn set_speed_for_podcast(&mut self, podcast_id: i64, speed: f32) {
        self.podcast_speeds
            .insert(podcast_id, speed.clamp(0.5, 3.0));
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EpisodeFilter {
    All,
    Unplayed,
    InProgress,
    Favorites,
    Completed,
}

impl EpisodeFilter {
    pub fn label(self) -> &'static str {
        match self {
            Self::All => "Tous",
            Self::Unplayed => "Non lus",
            Self::InProgress => "En cours",
            Self::Favorites => "Favoris",
            Self::Completed => "Terminés",
        }
    }

    pub const ALL: [Self; 5] = [
        Self::All,
        Self::Unplayed,
        Self::InProgress,
        Self::Favorites,
        Self::Completed,
    ];
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EpisodeSort {
    Series,
    NewestFirst,
    OldestFirst,
    TitleAsc,
    DurationDesc,
}

impl EpisodeSort {
    pub fn label(self) -> &'static str {
        match self {
            Self::Series => "Ordre série",
            Self::NewestFirst => "Plus récents",
            Self::OldestFirst => "Plus anciens",
            Self::TitleAsc => "Titre A–Z",
            Self::DurationDesc => "Durée",
        }
    }

    pub const ALL: [Self; 5] = [
        Self::Series,
        Self::NewestFirst,
        Self::OldestFirst,
        Self::TitleAsc,
        Self::DurationDesc,
    ];
}

#[derive(Debug, Clone)]
pub struct ScanProgress {
    pub running: bool,
    pub scanned_files: usize,
    pub total_files: usize,
    pub added_episodes: usize,
    pub updated_episodes: usize,
    pub current_file: String,
    pub completed: bool,
    pub error: Option<String>,
}

impl Default for ScanProgress {
    fn default() -> Self {
        Self {
            running: false,
            scanned_files: 0,
            total_files: 0,
            added_episodes: 0,
            updated_episodes: 0,
            current_file: String::new(),
            completed: false,
            error: None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct DuplicateGroup {
    pub reason: String,
    pub episodes: Vec<Episode>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SleepTimerMode {
    Off,
    EndOfEpisode,
    Countdown(u64), // remaining seconds
}

#[derive(Debug, Clone)]
pub struct ImportSummary {
    pub podcasts: usize,
    pub episodes: usize,
    pub bookmarks: usize,
    pub playback_states: usize,
}
