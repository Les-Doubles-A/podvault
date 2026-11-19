use serde_json::Value;

#[derive(Debug, Clone)]
pub struct DictionarySection {
    pub part_of_speech: String,
    pub definitions: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct DictionaryEntry {
    pub word: String,
    pub sections: Vec<DictionarySection>,
}

#[derive(Debug, Clone)]
pub struct DictionaryLookupResult {
    pub entry: Option<DictionaryEntry>,
    pub corrected_from: Option<String>,
    pub related_suggestions: Vec<String>,
}

const SKIP_HEADINGS: &[&str] = &[
    "étymologie",
    "prononciation",
    "voir aussi",
    "références",
    "anagrammes",
    "traductions",
    "synonymes",
    "antonymes",
    "dérivés",
    "apparentés étymologiques",
    "vocabulaire apparenté",
    "hyperonymes",
    "hyponymes",
    "homophones",
    "paronymes",
    "notes",
];

pub fn suggest_words(raw_query: &str) -> Vec<String> {
    let cleaned = raw_query.trim();
    if cleaned.chars().count() < 2 {
        return Vec::new();
    }
    fetch_suggestions(cleaned)
}

pub fn lookup_word(raw_word: &str) -> Result<DictionaryLookupResult, String> {
    let cleaned = raw_word.trim();
    if cleaned.is_empty() {
        return Err("Veuillez saisir un mot.".into());
    }

    if let Some(entry) = fetch_page(cleaned)? {
        return Ok(DictionaryLookupResult {
            entry: Some(entry),
            corrected_from: None,
            related_suggestions: Vec::new(),
        });
    }

    let lower = cleaned.to_lowercase();
    if lower != cleaned {
        if let Some(entry) = fetch_page(&lower)? {
            return Ok(DictionaryLookupResult {
                entry: Some(entry),
                corrected_from: None,
                related_suggestions: Vec::new(),
            });
        }
    }

    // Aucun match exact : recherche des résultats les plus probables (faute de frappe, accent manquant...)
    let candidates = fetch_suggestions(cleaned);
    for candidate in candidates.iter().take(3) {
        if let Ok(Some(found)) = fetch_page(candidate) {
            let others: Vec<String> = candidates
                .iter()
                .filter(|c| !c.eq_ignore_ascii_case(&found.word))
                .take(5)
                .cloned()
                .collect();
            return Ok(DictionaryLookupResult {
                entry: Some(found),
                corrected_from: Some(cleaned.to_string()),
                related_suggestions: others,
            });
        }
    }

    Ok(DictionaryLookupResult {
        entry: None,
        corrected_from: None,
        related_suggestions: candidates.into_iter().take(6).collect(),
    })
}

fn http_get_json(url: &str) -> Result<Value, String> {
    let resp = ureq::get(url)
        .set(
            "User-Agent",
            "PodVault/0.4.1 (https://github.com/Les-Doubles-A/podvault)",
        )
        .timeout(std::time::Duration::from_secs(8))
        .call()
        .map_err(|_| "Connexion impossible. Vérifiez votre accès internet.".to_string())?;

    let body = resp
        .into_string()
        .map_err(|_| "Réponse illisible du dictionnaire.".to_string())?;
    serde_json::from_str(&body).map_err(|_| "Réponse JSON invalide.".to_string())
}

fn fetch_suggestions(query: &str) -> Vec<String> {
    let mut results: Vec<String> = Vec::new();
    let enc = percent_encode(query);

    let push_unique = |list: &mut Vec<String>, s: String| {
        let trimmed = s.trim().to_string();
        if !trimmed.is_empty() && !list.iter().any(|x| x.eq_ignore_ascii_case(&trimmed)) {
            list.push(trimmed);
        }
    };

    // 1. Autocomplétion rapide par préfixe (opensearch)
    let opensearch_url = format!(
        "https://fr.wiktionary.org/w/api.php?action=opensearch&search={enc}&limit=6&namespace=0&format=json"
    );
    if let Ok(val) = http_get_json(&opensearch_url) {
        if let Some(arr) = val.get(1).and_then(|v| v.as_array()) {
            for item in arr {
                if let Some(s) = item.as_str() {
                    push_unique(&mut results, s.to_string());
                }
            }
        }
    }

    // 2. Si peu de résultats (faute d'orthographe / accent oublié), recherche floue MediaWiki
    if results.len() < 4 {
        let search_url = format!(
            "https://fr.wiktionary.org/w/api.php?action=query&list=search&srsearch={enc}&srlimit=6&srnamespace=0&srinfo=suggestion&format=json"
        );
        if let Ok(val) = http_get_json(&search_url) {
            if let Some(q) = val.get("query") {
                if let Some(suggestion) = q
                    .get("searchinfo")
                    .and_then(|s| s.get("suggestion"))
                    .and_then(|v| v.as_str())
                {
                    let trimmed = suggestion.trim().to_string();
                    if !trimmed.is_empty() {
                        results.retain(|x| !x.eq_ignore_ascii_case(&trimmed));
                        results.insert(0, trimmed);
                    }
                }
                if let Some(arr) = q.get("search").and_then(|v| v.as_array()) {
                    for item in arr {
                        if let Some(t) = item.get("title").and_then(|v| v.as_str()) {
                            push_unique(&mut results, t.to_string());
                        }
                    }
                }
            }
        }
    }

    results.truncate(6);
    results
}

fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() * 2);
    for b in input.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char);
            }
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn fetch_page(word: &str) -> Result<Option<DictionaryEntry>, String> {
    let encoded = percent_encode(word);
    let url = format!(
        "https://fr.wiktionary.org/w/api.php?action=parse&prop=text&format=json&redirects=1&disableeditsection=1&page={encoded}"
    );
    let root = http_get_json(&url)?;

    if root.get("error").is_some() {
        return Ok(None);
    }
    let Some(parse) = root.get("parse") else {
        return Ok(None);
    };
    let title = parse
        .get("title")
        .and_then(|v| v.as_str())
        .unwrap_or(word)
        .to_string();
    let Some(html) = parse
        .get("text")
        .and_then(|t| t.get("*"))
        .and_then(|v| v.as_str())
    else {
        return Ok(None);
    };

    let sections = parse_wiktionary_html(html);
    if sections.is_empty() {
        Ok(None)
    } else {
        Ok(Some(DictionaryEntry {
            word: title,
            sections,
        }))
    }
}

fn find_tag_blocks(html: &str, tag: &str) -> Vec<(usize, usize, String)> {
    let lower = html.to_lowercase();
    let open_pat = format!("<{tag}");
    let close_pat = format!("</{tag}>");
    let mut blocks = Vec::new();
    let mut pos = 0;

    while let Some(rel) = lower[pos..].find(&open_pat) {
        let start = pos + rel;
        let after_tag = start + open_pat.len();
        let valid_boundary = html
            .as_bytes()
            .get(after_tag)
            .map(|&b| b == b'>' || b.is_ascii_whitespace())
            .unwrap_or(false);
        if !valid_boundary {
            pos = after_tag;
            continue;
        }
        let Some(gt_rel) = html[after_tag..].find('>') else {
            break;
        };
        let inner_start = after_tag + gt_rel + 1;
        let Some(close_rel) = lower[inner_start..].find(&close_pat) else {
            break;
        };
        let inner_end = inner_start + close_rel;
        let end = inner_end + close_pat.len();
        blocks.push((start, end, html[inner_start..inner_end].to_string()));
        pos = end;
    }
    blocks
}

fn parse_wiktionary_html(html: &str) -> Vec<DictionarySection> {
    let h2s = find_tag_blocks(html, "h2");
    let lang_chunk = if h2s.is_empty() {
        html
    } else {
        let idx = h2s
            .iter()
            .position(|(_, _, inner)| strip_html(inner).to_lowercase().contains("français"))
            .unwrap_or(0);
        let start = h2s[idx].1;
        let end = if idx + 1 < h2s.len() {
            h2s[idx + 1].0
        } else {
            html.len()
        };
        &html[start..end]
    };

    let h3s = find_tag_blocks(lang_chunk, "h3");
    let mut results = Vec::new();

    for i in 0..h3s.len() {
        let heading = strip_html(&h3s[i].2).trim().to_string();
        let lower_h = heading.to_lowercase();
        if heading.is_empty() || SKIP_HEADINGS.iter().any(|s| lower_h.starts_with(s)) {
            continue;
        }
        let start = h3s[i].1;
        let end = if i + 1 < h3s.len() {
            h3s[i + 1].0
        } else {
            lang_chunk.len()
        };
        let block = &lang_chunk[start..end];

        let ols = find_tag_blocks(block, "ol");
        let Some((_, _, ol_inner)) = ols.first() else {
            continue;
        };

        let defs: Vec<String> = extract_top_level_li(ol_inner)
            .into_iter()
            .map(|item| {
                let cleaned = strip_tag_block(&strip_tag_block(&strip_tag_block(&item, "ul"), "dl"), "ol");
                collapse_whitespace(&strip_html(&cleaned))
            })
            .filter(|s| s.len() > 1)
            .take(8)
            .collect();

        if !defs.is_empty() {
            results.push(DictionarySection {
                part_of_speech: heading,
                definitions: defs,
            });
        }
    }
    results
}

fn extract_top_level_li(ol_inner: &str) -> Vec<String> {
    let lower = ol_inner.to_lowercase();
    let bytes = ol_inner.as_bytes();
    let mut items = Vec::new();
    let mut depth = 0usize;
    let mut item_start: Option<usize> = None;
    let mut i = 0;

    while i < bytes.len() {
        if lower[i..].starts_with("<li") {
            let next = bytes.get(i + 3).copied();
            if next == Some(b'>') || next.map(|b| b.is_ascii_whitespace()).unwrap_or(false) {
                if let Some(gt) = ol_inner[i..].find('>') {
                    let after = i + gt + 1;
                    if depth == 0 {
                        item_start = Some(after);
                    }
                    depth += 1;
                    i = after;
                    continue;
                }
            }
        } else if lower[i..].starts_with("</li>") {
            if depth > 0 {
                depth -= 1;
                if depth == 0 {
                    if let Some(st) = item_start.take() {
                        items.push(ol_inner[st..i].to_string());
                    }
                }
            }
            i += 5;
            continue;
        }
        // Avance d'un caractère UTF-8 valide
        i += ol_inner[i..].chars().next().map(|c| c.len_utf8()).unwrap_or(1);
    }
    items
}

fn strip_tag_block(input: &str, tag: &str) -> String {
    let blocks = find_tag_blocks(input, tag);
    if blocks.is_empty() {
        return input.to_string();
    }
    let mut out = String::with_capacity(input.len());
    let mut last = 0;
    for (start, end, _) in blocks {
        if start >= last {
            out.push_str(&input[last..start]);
            last = end;
        }
    }
    out.push_str(&input[last..]);
    out
}

fn strip_html(input: &str) -> String {
    let mut no_tags = String::with_capacity(input.len());
    let mut in_tag = false;
    for ch in input.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => no_tags.push(ch),
            _ => {}
        }
    }
    decode_entities(&no_tags)
}

fn decode_entities(s: &str) -> String {
    s.replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#8217;", "’")
        .replace("&rsquo;", "’")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
}

fn collapse_whitespace(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_wiktionary_html_definitions() {
        let sample = r#"
            <h2>Français</h2>
            <h3>Étymologie</h3><p>Du latin...</p>
            <h3>Nom commun</h3>
            <ol>
                <li><i>(Audiovisuel)</i> Émission audio diffusée sur Internet.<ul><li>Exemple à ignorer.</li></ul></li>
                <li>Seconde définition claire.</li>
            </ol>
        "#;
        let sections = parse_wiktionary_html(sample);
        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].part_of_speech, "Nom commun");
        assert_eq!(sections[0].definitions.len(), 2);
        assert!(sections[0].definitions[0].contains("Émission audio"));
        assert!(!sections[0].definitions[0].contains("Exemple"));
    }
}
