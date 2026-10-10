//! Checking installed mods for newer compatible versions and updating them.

use super::curseforge;
use super::http::{self, Download};
use super::instance::{self, Instance};
use super::mods::{self, Source};
use super::modrinth::{self, Version};
use anyhow::{anyhow, Result};
use futures::StreamExt;
use serde::Serialize;
use serde_json::json;
use sha1::{Digest, Sha1};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModUpdate {
    pub source: Source,
    pub project_id: String,
    pub title: String,
    pub current_file: String,
    pub new_file: String,
    #[serde(skip)]
    new_version_id: String,
    #[serde(skip)]
    url: String,
    #[serde(skip)]
    sha1: Option<String>,
}

fn file_sha1(path: &std::path::Path) -> Option<String> {
    std::fs::read(path).ok().map(|bytes| hex::encode(Sha1::digest(&bytes)))
}

/// Lists mods that have a newer version for this instance's game version and loader.
pub async fn check(instance_id: &str) -> Result<Vec<ModUpdate>> {
    let inst = instance::get(instance_id)?;
    let loader = inst
        .loader
        .mod_loader()
        .ok_or_else(|| anyhow!("В ванильной сборке нет модов"))?;
    let mut updates = Vec::new();

    // Modrinth: one request for all mods, keyed by the hash of the file on disk.
    let mut by_hash: HashMap<String, usize> = HashMap::new();
    for (i, m) in inst.mods.iter().enumerate() {
        if m.source == Source::Modrinth && !m.version_id.is_empty() {
            if let Some(h) = file_sha1(&inst.mods_dir().join(&m.file_name)) {
                by_hash.insert(h, i);
            }
        }
    }
    if !by_hash.is_empty() {
        let body = json!({
            "hashes": by_hash.keys().collect::<Vec<_>>(),
            "algorithm": "sha1",
            "loaders": modrinth::compatible_loaders(loader),
            "game_versions": [inst.game_version],
        });
        let latest: HashMap<String, Version> = http::client()
            .post("https://api.modrinth.com/v2/version_files/update")
            .json(&body)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        for (hash, version) in latest {
            let Some(&i) = by_hash.get(&hash) else { continue };
            let m = &inst.mods[i];
            if version.id == m.version_id {
                continue;
            }
            let Ok(file) = modrinth::primary_file(&version) else { continue };
            updates.push(ModUpdate {
                source: Source::Modrinth,
                project_id: m.project_id.clone(),
                title: m.title.clone(),
                current_file: m.file_name.clone(),
                new_file: file.filename.clone(),
                new_version_id: version.id.clone(),
                url: file.url.clone(),
                sha1: file.hashes.sha1.clone(),
            });
        }
    }

    // CurseForge: one request per mod, a few at a time.
    if curseforge::is_available() {
        let loader_type = curseforge::loader_type(loader);
        let cf_mods: Vec<_> = inst.mods.iter().filter(|m| m.source == Source::Curseforge).cloned().collect();
        let game = inst.game_version.clone();
        let found: Vec<Option<ModUpdate>> = futures::stream::iter(cf_mods)
            .map(|m| {
                let game = game.clone();
                async move {
                    let id: u64 = m.project_id.parse().ok()?;
                    let file = curseforge::latest_compatible(id, Some(&game), loader_type).await.ok()??;
                    let url = file.download_url.clone()?;
                    (file.id.to_string() != m.version_id && mods::check_file_name(&file.file_name).is_ok()).then(|| {
                        ModUpdate {
                            source: Source::Curseforge,
                            project_id: m.project_id.clone(),
                            title: m.title.clone(),
                            current_file: m.file_name.clone(),
                            new_file: file.file_name.clone(),
                            new_version_id: file.id.to_string(),
                            url,
                            sha1: file.hashes.iter().find(|h| h.algo == curseforge::HASH_SHA1).map(|h| h.value.clone()),
                        }
                    })
                }
            })
            .buffer_unordered(8)
            .collect()
            .await;
        updates.extend(found.into_iter().flatten());
    }

    updates.sort_by_key(|u| u.title.to_lowercase());
    Ok(updates)
}

/// Updates the given mods (all available updates when `project_ids` is empty).
pub async fn apply(instance_id: &str, project_ids: &[String]) -> Result<Instance> {
    let updates: Vec<ModUpdate> = check(instance_id)
        .await?
        .into_iter()
        .filter(|u| project_ids.is_empty() || project_ids.contains(&u.project_id))
        .collect();
    let mut inst = instance::get(instance_id)?;
    for u in updates {
        mods::check_file_name(&u.new_file)?;
        http::download(&Download { url: u.url.clone(), path: inst.mods_dir().join(&u.new_file), sha1: u.sha1.clone() })
            .await?;
        if u.current_file != u.new_file {
            let _ = std::fs::remove_file(inst.mods_dir().join(&u.current_file));
        }
        if let Some(m) = inst.mods.iter_mut().find(|m| m.project_id == u.project_id && m.source == u.source) {
            m.file_name = u.new_file;
            m.version_id = u.new_version_id;
        }
        inst.save()?;
    }
    Ok(inst)
}

