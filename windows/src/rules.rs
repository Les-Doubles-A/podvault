use crate::model::{Episode, PlaybackState, PodcastSignal};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

/// Règles de lecture pures (port exact de PlaybackRules.kt).
pub struct PlaybackRules;

impl PlaybackRules {
    const MIN_RESUME_MS: i64 = 5_000;
    const COLD_RESUME_REWIND_MS: i64 = 3_000;

    /// Marge de fin : 3 % de la durée, bornée entre 5 et 30 secondes.
    pub fn completion_threshold_ms(duration_ms: i64) -> i64 {
        ((duration_ms as f64) * 0.03).clamp(5_000.0, 30_000.0) as i64
    }

    pub fn is_completed(position_ms: i64, duration_ms: i64) -> bool {
        duration_ms > 0 && position_ms >= duration_ms - Self::completion_threshold_ms(duration_ms)
    }

    /// Retour en arrière après une pause selon sa durée.
    pub fn smart_rewind_ms(paused_for_ms: i64) -> i64 {
        if paused_for_ms < 10_000 {
            0
        } else if paused_for_ms < 60_000 {
            2_000
        } else if paused_for_ms < 5 * 60_000 {
            5_000
        } else if paused_for_ms < 30 * 60_000 {
            10_000
        } else {
            15_000
        }
    }

    /// Position de reprise d'un épisode à partir de son état sauvegardé.
    pub fn resume_position(
        saved: Option<&PlaybackState>,
        duration_ms: i64,
        smart_rewind: bool,
    ) -> i64 {
        let Some(state) = saved else {
            return 0;
        };
        if state.completed {
            return 0;
        }
        if state.position_ms < Self::MIN_RESUME_MS
            || Self::is_completed(state.position_ms, duration_ms)
        {
            return 0;
        }
        let rewind = if smart_rewind {
            Self::COLD_RESUME_REWIND_MS
        } else {
            0
        };
        (state.position_ms - rewind).max(0)
    }
}

/// Algorithme de recommandation local et déterministe (port exact de HomeRecommender.kt).
pub struct HomeRecommender;

impl HomeRecommender {
    const DAY_MS: f64 = 86_400_000.0;
    const EXPLORATION_AFFINITY: f64 = 0.30;
    const AFFINITY_HALF_LIFE_DAYS: f64 = 14.0;
    const FRESHNESS_HALF_LIFE_DAYS: f64 = 10.0;
    const DIVERSITY_PENALTY: f64 = 0.65;

    pub fn affinity(signal: Option<&PodcastSignal>, max_listened_ms: i64, now: i64) -> f64 {
        let Some(s) = signal else {
            return Self::EXPLORATION_AFFINITY;
        };
        if s.listened_ms <= 0 && s.last_played_at <= 0 && s.favorite_count == 0 {
            return Self::EXPLORATION_AFFINITY;
        }
        let listened = if max_listened_ms > 0 {
            (1.0 + (s.listened_ms as f64) / 60_000.0).ln()
                / (1.0 + (max_listened_ms as f64) / 60_000.0).ln()
        } else {
            0.0
        };
        let recency = if s.last_played_at > 0 {
            Self::half_life(now - s.last_played_at, Self::AFFINITY_HALF_LIFE_DAYS)
        } else {
            0.0
        };
        let completion = (s.completed_count as f64) / ((s.completed_count as f64) + 2.0);
        let favorite = ((s.favorite_count as f64) / 3.0).min(1.0);
        (0.40 * listened + 0.30 * recency + 0.15 * completion + 0.15 * favorite).clamp(0.0, 1.0)
    }

    pub fn score(episode: &Episode, affinity: f64, now: i64) -> f64 {
        let freshness = Self::half_life(now - episode.added_at, Self::FRESHNESS_HALF_LIFE_DAYS);
        let favorite = if episode.is_favorite { 1.0 } else { 0.0 };
        0.50 * affinity + 0.30 * freshness + 0.12 * favorite + 0.08 * Self::daily_jitter(episode.id, now)
    }

    pub fn recommend(
        candidates: &[Episode],
        signals: &HashMap<i64, PodcastSignal>,
        exclude_ids: &HashSet<i64>,
        now: i64,
        limit: usize,
        max_per_podcast: usize,
    ) -> Vec<Episode> {
        let max_listened = signals
            .values()
            .map(|s| s.listened_ms)
            .max()
            .unwrap_or(0);
        let mut affinity_cache: HashMap<i64, f64> = HashMap::new();
        let mut scored: Vec<(&Episode, f64)> = candidates
            .iter()
            .filter(|e| !exclude_ids.contains(&e.id) && !e.is_missing)
            .map(|ep| {
                let aff = *affinity_cache
                    .entry(ep.podcast_id)
                    .or_insert_with(|| Self::affinity(signals.get(&ep.podcast_id), max_listened, now));
                (ep, Self::score(ep, aff, now))
            })
            .collect();

        let mut picked: Vec<Episode> = Vec::with_capacity(limit);
        let mut per_podcast: HashMap<i64, usize> = HashMap::new();

        while picked.len() < limit && !scored.is_empty() {
            let mut best_index: Option<usize> = None;
            let mut best_value = f64::NEG_INFINITY;
            for (i, (ep, base)) in scored.iter().enumerate() {
                let count = *per_podcast.get(&ep.podcast_id).unwrap_or(&0);
                if count >= max_per_podcast {
                    continue;
                }
                let value = base * Self::DIVERSITY_PENALTY.powi(count as i32);
                if value > best_value {
                    best_value = value;
                    best_index = Some(i);
                }
            }
            let Some(idx) = best_index else {
                break;
            };
            let (chosen, _) = scored.remove(idx);
            *per_podcast.entry(chosen.podcast_id).or_insert(0) += 1;
            picked.push(chosen.clone());
        }
        picked
    }

    /// Ordre "série" : saison + numéro, sinon date de publication, sinon nom de fichier naturel, sinon id.
    pub fn series_compare(a: &Episode, b: &Episode) -> Ordering {
        if let (Some(na), Some(nb)) = (a.episode_number, b.episode_number) {
            let season = a.season_number.unwrap_or(0).cmp(&b.season_number.unwrap_or(0));
            if season != Ordering::Equal {
                return season;
            }
            let num = na.cmp(&nb);
            if num != Ordering::Equal {
                return num;
            }
        }
        if let (Some(da), Some(db)) = (a.publication_date, b.publication_date) {
            let date = da.cmp(&db);
            if date != Ordering::Equal {
                return date;
            }
        }
        let name = Self::natural_key(&a.file_name).cmp(&Self::natural_key(&b.file_name));
        if name != Ordering::Equal {
            return name;
        }
        a.id.cmp(&b.id)
    }

    pub fn next_in_series<'a>(last: &Episode, unplayed: &'a [Episode]) -> Option<&'a Episode> {
        unplayed
            .iter()
            .filter(|e| e.id != last.id && !e.is_missing && Self::series_compare(e, last) == Ordering::Greater)
            .min_by(|a, b| Self::series_compare(a, b))
    }

    pub fn natural_key(name: &str) -> String {
        let lower = name.to_lowercase();
        let mut out = String::with_capacity(lower.len() + 16);
        let mut digits = String::new();
        for ch in lower.chars() {
            if ch.is_ascii_digit() {
                digits.push(ch);
            } else {
                if !digits.is_empty() {
                    if digits.len() < 9 {
                        for _ in 0..(9 - digits.len()) {
                            out.push('0');
                        }
                    }
                    out.push_str(&digits);
                    digits.clear();
                }
                out.push(ch);
            }
        }
        if !digits.is_empty() {
            if digits.len() < 9 {
                for _ in 0..(9 - digits.len()) {
                    out.push('0');
                }
            }
            out.push_str(&digits);
        }
        out
    }

    fn half_life(age_ms: i64, half_life_days: f64) -> f64 {
        0.5_f64.powf((age_ms.max(0) as f64) / Self::DAY_MS / half_life_days)
    }

    fn daily_jitter(episode_id: i64, now: i64) -> f64 {
        let day = now / 86_400_000_i64;
        let mut h = episode_id
            .wrapping_mul(-7046029254386353131_i64)
            .wrapping_add(day.wrapping_mul(1442695040888963407_i64)) as u64;
        h ^= h >> 33;
        h = h.wrapping_mul(-49064778989728563_i64 as u64);
        h ^= h >> 29;
        ((h & 0xFFFF) as f64) / 65536.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completion_threshold_clamps_between_5s_and_30s() {
        assert_eq!(PlaybackRules::completion_threshold_ms(60_000), 5_000);
        assert_eq!(PlaybackRules::completion_threshold_ms(600_000), 18_000);
        assert_eq!(PlaybackRules::completion_threshold_ms(3_600_000), 30_000);
    }

    #[test]
    fn smart_rewind_scales_with_pause_duration() {
        assert_eq!(PlaybackRules::smart_rewind_ms(5_000), 0);
        assert_eq!(PlaybackRules::smart_rewind_ms(30_000), 2_000);
        assert_eq!(PlaybackRules::smart_rewind_ms(120_000), 5_000);
        assert_eq!(PlaybackRules::smart_rewind_ms(600_000), 10_000);
        assert_eq!(PlaybackRules::smart_rewind_ms(3_600_000), 15_000);
    }

    #[test]
    fn natural_key_orders_ep2_before_ep10() {
        assert!(HomeRecommender::natural_key("ep2.mp3") < HomeRecommender::natural_key("ep10.mp3"));
    }
}
