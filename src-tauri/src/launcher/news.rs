//! Latest Minecraft releases and snapshots from the official launcher's patch notes feed.

use super::http;
use anyhow::Result;
use serde::Serialize;
use serde_json::Value;
use tokio::sync::Mutex;

const FEED: &str = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
const BASE: &str = "https://launchercontent.mojang.com";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct News {
    pub title: String,
    pub version: String,
    /// "release" or "snapshot".
    pub kind: String,
    pub image: Option<String>,
    pub text: String,
    pub date: String,
}

static CACHE: Mutex<Option<Vec<News>>> = Mutex::const_new(None);

/// Parses the feed leniently: entries with missing fields are kept, unknown fields ignored.
fn parse(feed: &Value, limit: usize) -> Vec<News> {
    let str_of = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or_default().to_string();
    let mut items: Vec<News> = feed
        .get("entries")
        .and_then(Value::as_array)
        .map(|a| a.as_slice())
        .unwrap_or_default()
        .iter()
        .map(|e| News {
            title: str_of(e, "title"),
            version: str_of(e, "version"),
            kind: str_of(e, "type"),
            image: e
                .pointer("/image/url")
                .and_then(Value::as_str)
                .map(|u| if u.starts_with("http") { u.to_string() } else { format!("{BASE}{u}") }),
            text: str_of(e, "shortText"),
            date: str_of(e, "date"),
        })
        .filter(|n| !n.title.is_empty())
        .collect();
    // ISO dates sort as strings; newest first.
    items.sort_by(|a, b| b.date.cmp(&a.date));
    items.truncate(limit);
    items
}

pub async fn latest() -> Result<Vec<News>> {
    let mut cache = CACHE.lock().await;
    if let Some(items) = cache.as_ref() {
        return Ok(items.clone());
    }
    let feed: Value = http::get_json(FEED).await?;
    let items = parse(&feed, 6);
    *cache = Some(items.clone());
    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_feed() {
        let feed = json!({ "version": 1, "entries": [
            { "title": "Minecraft 1.21.4", "version": "1.21.4", "type": "release",
              "image": { "url": "/images/a.jpg" }, "shortText": "Pale Garden", "date": "2024-12-03T10:00:00Z" },
            { "title": "Snapshot 25w02a", "version": "25w02a", "type": "snapshot", "date": "2025-01-08T10:00:00Z" },
            { "version": "broken" }
        ]});
        let items = parse(&feed, 5);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].version, "25w02a");
        assert_eq!(items[1].image.as_deref(), Some("https://launchercontent.mojang.com/images/a.jpg"));
        assert_eq!(items[1].text, "Pale Garden");
    }
}
