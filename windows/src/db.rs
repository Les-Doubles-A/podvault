use crate::model::{
    Bookmark, DuplicateGroup, Episode, ListeningDay, PlaybackState, Podcast, PodcastSignal,
};
use rusqlite::{params, Connection, OptionalExtension, Result};
use std::collections::HashMap;
use std::path::Path;

pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA foreign_keys = ON;",
        )?;
        let db = Self { conn };
        db.migrate()?;
        Ok(db)
    }

    fn migrate(&self) -> Result<()> {
        self.conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS podcasts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                author TEXT,
                description TEXT,
                cover_uri TEXT,
                folder_uri TEXT NOT NULL,
                category TEXT,
                group_key TEXT NOT NULL DEFAULT '',
                user_title TEXT,
                user_author TEXT,
                user_cover_uri TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_podcasts_folder_uri ON podcasts(folder_uri);
            CREATE INDEX IF NOT EXISTS idx_podcasts_group_key ON podcasts(group_key);
            CREATE INDEX IF NOT EXISTS idx_podcasts_title ON podcasts(title);

            CREATE TABLE IF NOT EXISTS episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                podcast_id INTEGER NOT NULL REFERENCES podcasts(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                description TEXT,
                audio_uri TEXT NOT NULL UNIQUE,
                file_name TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                cover_uri TEXT,
                publication_date INTEGER,
                season_number INTEGER,
                episode_number INTEGER,
                codec TEXT,
                bitrate INTEGER,
                added_at INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                last_modified_file INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_played INTEGER NOT NULL DEFAULT 0,
                is_missing INTEGER NOT NULL DEFAULT 0,
                user_title TEXT,
                user_author TEXT,
                user_cover_uri TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_episodes_podcast_id ON episodes(podcast_id);
            CREATE INDEX IF NOT EXISTS idx_episodes_title ON episodes(title);
            CREATE INDEX IF NOT EXISTS idx_episodes_fav ON episodes(is_favorite);
            CREATE INDEX IF NOT EXISTS idx_episodes_played ON episodes(is_played);

            CREATE TABLE IF NOT EXISTS playback_states (
                episode_id INTEGER PRIMARY KEY REFERENCES episodes(id) ON DELETE CASCADE,
                position_ms INTEGER NOT NULL,
                last_played_at INTEGER NOT NULL,
                play_count INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                total_listened_ms INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id INTEGER NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
                position_ms INTEGER NOT NULL,
                title TEXT,
                note TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_bookmarks_episode_id ON bookmarks(episode_id);

            CREATE TABLE IF NOT EXISTS listening_days (
                day TEXT PRIMARY KEY,
                listened_ms INTEGER NOT NULL
            );
            ",
        )?;
        Ok(())
    }

    // --- Podcasts ---

    pub fn get_all_podcasts(&self) -> Result<Vec<Podcast>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, author, description, cover_uri, folder_uri, category, group_key,
                    user_title, user_author, user_cover_uri, created_at, updated_at
             FROM podcasts ORDER BY COALESCE(user_title, title) COLLATE NOCASE ASC",
        )?;
        let rows = stmt.query_map([], Self::map_podcast)?;
        rows.collect()
    }

    pub fn get_podcast_by_group_key(&self, group_key: &str) -> Result<Option<Podcast>> {
        self.conn
            .query_row(
                "SELECT id, title, author, description, cover_uri, folder_uri, category, group_key,
                        user_title, user_author, user_cover_uri, created_at, updated_at
                 FROM podcasts WHERE group_key = ?1 LIMIT 1",
                params![group_key],
                Self::map_podcast,
            )
            .optional()
    }

    pub fn get_podcast_by_folder_uri(&self, folder_uri: &str) -> Result<Option<Podcast>> {
        self.conn
            .query_row(
                "SELECT id, title, author, description, cover_uri, folder_uri, category, group_key,
                        user_title, user_author, user_cover_uri, created_at, updated_at
                 FROM podcasts WHERE folder_uri = ?1 LIMIT 1",
                params![folder_uri],
                Self::map_podcast,
            )
            .optional()
    }

    pub fn insert_podcast(&self, p: &Podcast) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO podcasts (title, author, description, cover_uri, folder_uri, category,
                                   group_key, user_title, user_author, user_cover_uri, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                p.title,
                p.author,
                p.description,
                p.cover_uri,
                p.folder_uri,
                p.category,
                p.group_key,
                p.user_title,
                p.user_author,
                p.user_cover_uri,
                p.created_at,
                p.updated_at
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn update_podcast(&self, p: &Podcast) -> Result<()> {
        self.conn.execute(
            "UPDATE podcasts SET title=?1, author=?2, description=?3, cover_uri=?4, folder_uri=?5,
                                 category=?6, group_key=?7, user_title=?8, user_author=?9,
                                 user_cover_uri=?10, updated_at=?11
             WHERE id=?12",
            params![
                p.title,
                p.author,
                p.description,
                p.cover_uri,
                p.folder_uri,
                p.category,
                p.group_key,
                p.user_title,
                p.user_author,
                p.user_cover_uri,
                p.updated_at,
                p.id
            ],
        )?;
        Ok(())
    }

    pub fn update_podcast_user_meta(
        &self,
        id: i64,
        user_title: Option<&str>,
        user_author: Option<&str>,
        user_cover_uri: Option<&str>,
        now: i64,
    ) -> Result<()> {
        self.conn.execute(
            "UPDATE podcasts SET user_title=?1, user_author=?2, user_cover_uri=?3, updated_at=?4 WHERE id=?5",
            params![user_title, user_author, user_cover_uri, now, id],
        )?;
        Ok(())
    }

    // --- Episodes ---

    pub fn get_all_episodes(&self) -> Result<Vec<Episode>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, podcast_id, title, description, audio_uri, file_name, file_size, duration_ms,
                    cover_uri, publication_date, season_number, episode_number, codec, bitrate,
                    added_at, modified_at, last_modified_file, is_favorite, is_played, is_missing,
                    user_title, user_author, user_cover_uri
             FROM episodes",
        )?;
        let rows = stmt.query_map([], Self::map_episode)?;
        rows.collect()
    }

    pub fn get_episode_by_uri(&self, audio_uri: &str) -> Result<Option<Episode>> {
        self.conn
            .query_row(
                "SELECT id, podcast_id, title, description, audio_uri, file_name, file_size, duration_ms,
                        cover_uri, publication_date, season_number, episode_number, codec, bitrate,
                        added_at, modified_at, last_modified_file, is_favorite, is_played, is_missing,
                        user_title, user_author, user_cover_uri
                 FROM episodes WHERE audio_uri = ?1",
                params![audio_uri],
                Self::map_episode,
            )
            .optional()
    }

    pub fn insert_episode(&self, e: &Episode) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO episodes (podcast_id, title, description, audio_uri, file_name, file_size,
                                   duration_ms, cover_uri, publication_date, season_number, episode_number,
                                   codec, bitrate, added_at, modified_at, last_modified_file,
                                   is_favorite, is_played, is_missing, user_title, user_author, user_cover_uri)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22)",
            params![
                e.podcast_id,
                e.title,
                e.description,
                e.audio_uri,
                e.file_name,
                e.file_size,
                e.duration_ms,
                e.cover_uri,
                e.publication_date,
                e.season_number,
                e.episode_number,
                e.codec,
                e.bitrate,
                e.added_at,
                e.modified_at,
                e.last_modified_file,
                e.is_favorite as i32,
                e.is_played as i32,
                e.is_missing as i32,
                e.user_title,
                e.user_author,
                e.user_cover_uri
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn update_episode(&self, e: &Episode) -> Result<()> {
        self.conn.execute(
            "UPDATE episodes SET podcast_id=?1, title=?2, description=?3, file_name=?4, file_size=?5,
                                 duration_ms=?6, cover_uri=?7, publication_date=?8, season_number=?9,
                                 episode_number=?10, codec=?11, bitrate=?12, modified_at=?13,
                                 last_modified_file=?14, is_favorite=?15, is_played=?16, is_missing=?17,
                                 user_title=?18, user_author=?19, user_cover_uri=?20
             WHERE id=?21",
            params![
                e.podcast_id,
                e.title,
                e.description,
                e.file_name,
                e.file_size,
                e.duration_ms,
                e.cover_uri,
                e.publication_date,
                e.season_number,
                e.episode_number,
                e.codec,
                e.bitrate,
                e.modified_at,
                e.last_modified_file,
                e.is_favorite as i32,
                e.is_played as i32,
                e.is_missing as i32,
                e.user_title,
                e.user_author,
                e.user_cover_uri,
                e.id
            ],
        )?;
        Ok(())
    }

    pub fn set_episode_favorite(&self, id: i64, favorite: bool) -> Result<()> {
        self.conn.execute(
            "UPDATE episodes SET is_favorite = ?1 WHERE id = ?2",
            params![favorite as i32, id],
        )?;
        Ok(())
    }

    pub fn set_episode_played(&self, id: i64, played: bool) -> Result<()> {
        self.conn.execute(
            "UPDATE episodes SET is_played = ?1 WHERE id = ?2",
            params![played as i32, id],
        )?;
        Ok(())
    }

    pub fn set_episode_missing(&self, id: i64, missing: bool) -> Result<()> {
        self.conn.execute(
            "UPDATE episodes SET is_missing = ?1 WHERE id = ?2",
            params![missing as i32, id],
        )?;
        Ok(())
    }

    pub fn update_episode_user_meta(
        &self,
        id: i64,
        user_title: Option<&str>,
        user_author: Option<&str>,
        user_cover_uri: Option<&str>,
        now: i64,
    ) -> Result<()> {
        self.conn.execute(
            "UPDATE episodes SET user_title=?1, user_author=?2, user_cover_uri=?3, modified_at=?4 WHERE id=?5",
            params![user_title, user_author, user_cover_uri, now, id],
        )?;
        Ok(())
    }

    // --- Playback states ---

    pub fn get_all_playback_states(&self) -> Result<Vec<PlaybackState>> {
        let mut stmt = self.conn.prepare(
            "SELECT episode_id, position_ms, last_played_at, play_count, completed, total_listened_ms
             FROM playback_states",
        )?;
        let rows = stmt.query_map([], Self::map_playback_state)?;
        rows.collect()
    }

    pub fn get_playback_state(&self, episode_id: i64) -> Result<Option<PlaybackState>> {
        self.conn
            .query_row(
                "SELECT episode_id, position_ms, last_played_at, play_count, completed, total_listened_ms
                 FROM playback_states WHERE episode_id = ?1",
                params![episode_id],
                Self::map_playback_state,
            )
            .optional()
    }

    pub fn upsert_playback_state(&self, s: &PlaybackState) -> Result<()> {
        self.conn.execute(
            "INSERT INTO playback_states (episode_id, position_ms, last_played_at, play_count, completed, total_listened_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(episode_id) DO UPDATE SET
                 position_ms = excluded.position_ms,
                 last_played_at = excluded.last_played_at,
                 play_count = excluded.play_count,
                 completed = excluded.completed,
                 total_listened_ms = excluded.total_listened_ms",
            params![
                s.episode_id,
                s.position_ms,
                s.last_played_at,
                s.play_count,
                s.completed as i32,
                s.total_listened_ms
            ],
        )?;
        Ok(())
    }

    // --- Bookmarks ---

    pub fn get_all_bookmarks(&self) -> Result<Vec<Bookmark>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, episode_id, position_ms, title, note, created_at, updated_at
             FROM bookmarks ORDER BY created_at DESC",
        )?;
        let rows = stmt.query_map([], Self::map_bookmark)?;
        rows.collect()
    }

    pub fn insert_bookmark(&self, b: &Bookmark) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO bookmarks (episode_id, position_ms, title, note, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                b.episode_id,
                b.position_ms,
                b.title,
                b.note,
                b.created_at,
                b.updated_at
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn delete_bookmark(&self, id: i64) -> Result<()> {
        self.conn
            .execute("DELETE FROM bookmarks WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn bookmark_exists(
        &self,
        episode_id: i64,
        position_ms: i64,
        title: Option<&str>,
        note: Option<&str>,
    ) -> Result<bool> {
        let count: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM bookmarks
             WHERE episode_id = ?1 AND position_ms = ?2
               AND ((title IS NULL AND ?3 IS NULL) OR title = ?3)
               AND ((note IS NULL AND ?4 IS NULL) OR note = ?4)",
            params![episode_id, position_ms, title, note],
            |r| r.get(0),
        )?;
        Ok(count > 0)
    }

    // --- Listening days ---

    pub fn get_all_listening_days(&self) -> Result<Vec<ListeningDay>> {
        let mut stmt = self.conn.prepare(
            "SELECT day, listened_ms FROM listening_days ORDER BY day ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(ListeningDay {
                day: r.get(0)?,
                listened_ms: r.get(1)?,
            })
        })?;
        rows.collect()
    }

    pub fn add_listening_time(&self, day: &str, delta_ms: i64) -> Result<()> {
        if delta_ms <= 0 {
            return Ok(());
        }
        self.conn.execute(
            "INSERT INTO listening_days (day, listened_ms) VALUES (?1, ?2)
             ON CONFLICT(day) DO UPDATE SET listened_ms = listening_days.listened_ms + excluded.listened_ms",
            params![day, delta_ms],
        )?;
        Ok(())
    }

    pub fn upsert_listening_day_max(&self, day: &str, listened_ms: i64) -> Result<()> {
        self.conn.execute(
            "INSERT INTO listening_days (day, listened_ms) VALUES (?1, ?2)
             ON CONFLICT(day) DO UPDATE SET listened_ms = MAX(listening_days.listened_ms, excluded.listened_ms)",
            params![day, listened_ms],
        )?;
        Ok(())
    }

    // --- Helpers ---

    pub fn compute_podcast_signals(
        episodes: &[Episode],
        states: &HashMap<i64, PlaybackState>,
    ) -> HashMap<i64, PodcastSignal> {
        let mut map: HashMap<i64, PodcastSignal> = HashMap::new();
        for ep in episodes {
            let entry = map.entry(ep.podcast_id).or_insert(PodcastSignal {
                podcast_id: ep.podcast_id,
                listened_ms: 0,
                completed_count: 0,
                last_played_at: 0,
                favorite_count: 0,
            });
            if ep.is_favorite {
                entry.favorite_count += 1;
            }
            if ep.is_played {
                entry.completed_count += 1;
            }
            if let Some(st) = states.get(&ep.id) {
                entry.listened_ms += st.total_listened_ms;
                if st.last_played_at > entry.last_played_at {
                    entry.last_played_at = st.last_played_at;
                }
            }
        }
        map
    }

    pub fn detect_duplicates(episodes: &[Episode]) -> Vec<DuplicateGroup> {
        let mut groups: Vec<DuplicateGroup> = Vec::new();
        let mut by_title: HashMap<String, Vec<Episode>> = HashMap::new();
        for ep in episodes.iter().filter(|e| !e.is_missing) {
            let key = ep.display_title().trim().to_lowercase();
            if !key.is_empty() {
                by_title.entry(key).or_default().push(ep.clone());
            }
        }
        for (_, list) in by_title {
            if list.len() > 1 {
                groups.push(DuplicateGroup {
                    reason: format!("Même titre (« {} »)", list[0].display_title()),
                    episodes: list,
                });
            }
        }

        let mut by_size_dur: HashMap<(i64, i64), Vec<Episode>> = HashMap::new();
        for ep in episodes.iter().filter(|e| !e.is_missing && e.file_size > 0) {
            let dur_bucket = ep.duration_ms / 2_000;
            by_size_dur
                .entry((ep.file_size, dur_bucket))
                .or_default()
                .push(ep.clone());
        }
        for ((size, _), list) in by_size_dur {
            if list.len() > 1 {
                let ids: Vec<i64> = list.iter().map(|e| e.id).collect();
                let already = groups.iter().any(|g| {
                    g.episodes.len() == ids.len() && g.episodes.iter().all(|e| ids.contains(&e.id))
                });
                if !already {
                    groups.push(DuplicateGroup {
                        reason: format!("Même taille ({:.1} Mo) et même durée", size as f64 / 1_048_576.0),
                        episodes: list,
                    });
                }
            }
        }
        groups
    }

    fn map_podcast(r: &rusqlite::Row<'_>) -> Result<Podcast> {
        Ok(Podcast {
            id: r.get(0)?,
            title: r.get(1)?,
            author: r.get(2)?,
            description: r.get(3)?,
            cover_uri: r.get(4)?,
            folder_uri: r.get(5)?,
            category: r.get(6)?,
            group_key: r.get(7)?,
            user_title: r.get(8)?,
            user_author: r.get(9)?,
            user_cover_uri: r.get(10)?,
            created_at: r.get(11)?,
            updated_at: r.get(12)?,
        })
    }

    fn map_episode(r: &rusqlite::Row<'_>) -> Result<Episode> {
        Ok(Episode {
            id: r.get(0)?,
            podcast_id: r.get(1)?,
            title: r.get(2)?,
            description: r.get(3)?,
            audio_uri: r.get(4)?,
            file_name: r.get(5)?,
            file_size: r.get(6)?,
            duration_ms: r.get(7)?,
            cover_uri: r.get(8)?,
            publication_date: r.get(9)?,
            season_number: r.get(10)?,
            episode_number: r.get(11)?,
            codec: r.get(12)?,
            bitrate: r.get(13)?,
            added_at: r.get(14)?,
            modified_at: r.get(15)?,
            last_modified_file: r.get(16)?,
            is_favorite: r.get::<_, i32>(17)? != 0,
            is_played: r.get::<_, i32>(18)? != 0,
            is_missing: r.get::<_, i32>(19)? != 0,
            user_title: r.get(20)?,
            user_author: r.get(21)?,
            user_cover_uri: r.get(22)?,
        })
    }

    fn map_playback_state(r: &rusqlite::Row<'_>) -> Result<PlaybackState> {
        Ok(PlaybackState {
            episode_id: r.get(0)?,
            position_ms: r.get(1)?,
            last_played_at: r.get(2)?,
            play_count: r.get(3)?,
            completed: r.get::<_, i32>(4)? != 0,
            total_listened_ms: r.get(5)?,
        })
    }

    fn map_bookmark(r: &rusqlite::Row<'_>) -> Result<Bookmark> {
        Ok(Bookmark {
            id: r.get(0)?,
            episode_id: r.get(1)?,
            position_ms: r.get(2)?,
            title: r.get(3)?,
            note: r.get(4)?,
            created_at: r.get(5)?,
            updated_at: r.get(6)?,
        })
    }
}
