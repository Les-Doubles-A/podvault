pub mod extra;
pub mod home;
pub mod library;
pub mod player;
pub mod widgets;

use crate::model::{EpisodeFilter, EpisodeSort, SleepTimerMode, ThemeMode};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Screen {
    Home,
    Library,
    PodcastDetail(i64),
    Player,
    Bookmarks,
    Search,
    Queue,
    Statistics,
    Duplicates,
    Settings,
}

#[derive(Debug, Clone)]
pub enum AppAction {
    Navigate(Screen),
    PlayEpisode {
        episode_id: i64,
        position_ms: Option<i64>,
    },
    TogglePlayPause,
    SkipBack,
    SkipForward,
    SeekMs(i64),
    SetVolume(f32),
    ToggleMute,
    SetSpeed(f32),
    SetPodcastSpeed(i64, f32),
    ToggleFavorite(i64),
    TogglePlayed(i64),
    QueuePlayNext(i64),
    QueueAddEnd(i64),
    QueueRemoveAt(usize),
    QueueMoveUp(usize),
    QueueMoveDown(usize),
    QueueClear,
    PlayNextInQueue,
    SetSleepTimer(SleepTimerMode),
    OpenAddBookmark(i64, i64),
    DeleteBookmark(i64),
    OpenEditPodcast(i64),
    OpenEditEpisode(i64),
    AddWatchedFolder,
    RemoveWatchedFolder(String),
    StartScan,
    ExportBackup,
    ImportBackup,
    SetSkipIntervals(i32, i32),
    SetDefaultSpeed(f32),
    SetSmartRewind(bool),
    SetAmbientMode(bool),
    SetTheme(ThemeMode),
    SetEpisodeFilter(EpisodeFilter),
    SetEpisodeSort(EpisodeSort),
}
