//! CurseForge API: search and install mods with their required dependencies.
//!
//! The API key is embedded at build time from the CURSEFORGE_API_KEY environment variable
//! (a GitHub Actions secret), so it never appears in the source code.

use super::http::{self, Download};
use super::instance::{self, InstalledMod};
use super::mods::{self, InstallResult, ModHit, PendingDownload, SearchResult, Source};
use anyhow::{anyhow, bail, Context, Result};
use serde::de::DeserializeOwned;
use serde::Deserialize;
use std::collections::HashSet;

const API_URL: &str = "https://api.curseforge.com/v1";
const MINECRAFT_GAME_ID: u32 = 432;
const MODS_CLASS_ID: u32 = 6;
const RELATION_REQUIRED: u32 = 3;
const HASH_SHA1: u32 = 1;
const RELEASE_TYPE_RELEASE: u32 = 1;

fn api_key() -> Option<&'static str> {
    option_env!("CURSEFORGE_API_KEY").filter(|k| !k.trim().is_empty())
}

pub fn is_available() -> bool {
    api_key().is_some()
}

/// CurseForge's ModLoaderType enum.
fn loader_type(loader: &str) -> Option<u32> {
    match loader {
        "forge" => Some(1),
        "fabric" => Some(4),
        "quilt" => Some(5),
        "neoforge" => Some(6),
        _ => None,
    }
}

async fn get<T: DeserializeOwned>(path_and_query: &str) -> Result<T> {
    let key = api_key().ok_or_else(|| anyhow!("CurseForge не подключён: в этой сборке нет API-ключа"))?;
    let url = format!("{API_URL}{path_and_query}");
    let resp = http::client()
        .get(&url)
        .header("x-api-key", key)
        .send()
        .await
        .with_context(|| format!("request to {url} failed"))?
        .error_for_status()?;
    Ok(resp.json().await?)
}

#[derive(Debug, Deserialize)]
struct Envelope<T> {
    data: T,
    #[serde(default)]
    pagination: Option<Pagination>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    total_count: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Mod {
    id: u64,
    name: String,
    slug: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    download_count: f64,
    #[serde(default)]
    logo: Option<Logo>,
    #[serde(default)]
    authors: Vec<Author>,
    #[serde(default)]
    categories: Vec<Category>,
    #[serde(default)]
    links: Option<Links>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Logo {
    #[serde(default)]
    thumbnail_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Author {
    name: String,
}

#[derive(Debug, Clone, Deserialize)]
struct Category {
    name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Links {
    #[serde(default)]
    website_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct File {
    id: u64,
    file_name: String,
    /// Null when the author has disabled third-party downloads.
    #[serde(default)]
    download_url: Option<String>,
    #[serde(default)]
    hashes: Vec<FileHash>,
    #[serde(default)]
    dependencies: Vec<FileDependency>,
    #[serde(default)]
    release_type: u32,
    #[serde(default)]
    file_date: String,
}

#[derive(Debug, Clone, Deserialize)]
struct FileHash {
    value: String,
    algo: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FileDependency {
    mod_id: u64,
    relation_type: u32,
}

impl Mod {
    fn page_url(&self) -> String {
        self.links
            .as_ref()
            .and_then(|l| l.website_url.clone())
            .unwrap_or_else(|| format!("https://www.curseforge.com/minecraft/mc-mods/{}", self.slug))
    }

    fn icon(&self) -> Option<String> {
        self.logo.as_ref().and_then(|l| l.thumbnail_url.clone())
    }
}

pub async fn search(
    query: &str,
    game_version: Option<&str>,
    loader: Option<&str>,
    sort: &str,
    offset: u64,
) -> Result<SearchResult> {
    // ModsSearchSortField: 2 popularity, 3 last updated, 6 total downloads, 11 released date.
    let sort_field = match sort {
        "downloads" => 6,
        "updated" => 3,
        "newest" => 11,
        _ => 2,
    };
    let mut url = format!(
        "/mods/search?gameId={MINECRAFT_GAME_ID}&classId={MODS_CLASS_ID}&searchFilter={}&sortField={sort_field}&sortOrder=desc&index={offset}&pageSize=20",
        urlencoding::encode(query),
    );
    if let Some(v) = game_version {
        url.push_str(&format!("&gameVersion={}", urlencoding::encode(v)));
    }
    if let Some(t) = loader.and_then(loader_type) {
        url.push_str(&format!("&modLoaderType={t}"));
    }

    let resp: Envelope<Vec<Mod>> = get(&url).await?;
    // The API refuses index + pageSize above 10 000.
    let total = resp.pagination.map_or(0, |p| p.total_count).min(10_000);
    Ok(SearchResult {
        total_hits: total,
        hits: resp
            .data
            .into_iter()
            .map(|m| ModHit {
                source: Source::Curseforge,
                project_id: m.id.to_string(),
                page_url: m.page_url(),
                icon_url: m.icon(),
                description: m.summary.clone(),
                author: m.authors.first().map(|a| a.name.clone()).unwrap_or_default(),
                downloads: m.download_count as u64,
                categories: m.categories.iter().map(|c| c.name.clone()).collect(),
                title: m.name,
            })
            .collect(),
    })
}

/// Newest compatible file, preferring releases over betas and alphas.
async fn latest_compatible(mod_id: u64, game_version: &str, loader_type: u32) -> Result<Option<File>> {
    let url = format!(
        "/mods/{mod_id}/files?gameVersion={}&modLoaderType={loader_type}&pageSize=50",
        urlencoding::encode(game_version),
    );
    let mut files: Vec<File> = get::<Envelope<Vec<File>>>(&url).await?.data;
    // ISO 8601 dates sort correctly as strings.
    files.sort_by(|a, b| b.file_date.cmp(&a.file_date));
    let release = files.iter().position(|f| f.release_type == RELEASE_TYPE_RELEASE);
    Ok(match release {
        Some(i) => Some(files.swap_remove(i)),
        None => files.into_iter().next(),
    })
}

pub async fn install(instance_id: &str, project_id: &str) -> Result<InstallResult> {
    let mut inst = instance::get(instance_id)?;
    let loader = inst
        .loader
        .mod_loader()
        .ok_or_else(|| anyhow!("Моды можно ставить только в сборку с загрузчиком модов"))?;
    let loader_id = loader_type(loader).ok_or_else(|| anyhow!("CurseForge не поддерживает загрузчик {loader}"))?;
    let game_version = inst.game_version.clone();
    let root_id: u64 = project_id.parse().context("неверный id мода CurseForge")?;

    let mut installed = Vec::new();
    let mut pending = Vec::new();
    let mut visited = HashSet::new();
    let mut queue = vec![root_id];
    let mut root = true;

    while let Some(mod_id) = queue.pop() {
        if !visited.insert(mod_id) {
            continue;
        }
        let info: Mod = get::<Envelope<Mod>>(&format!("/mods/{mod_id}")).await?.data;
        let is_root = std::mem::replace(&mut root, false);
        if mods::already_installed(&inst, Source::Curseforge, &info.id.to_string(), &info.name)
            || inst.pending.iter().any(|p| p.project_id == info.id.to_string())
        {
            continue;
        }
        let Some(file) = latest_compatible(mod_id, &game_version, loader_id).await? else {
            if is_root {
                bail!("У этого мода нет версии для Minecraft {game_version} ({loader})");
            }
            continue; // Dependency without a compatible build: skip rather than fail.
        };
        mods::check_file_name(&file.file_name)?;
        let sha1 = file.hashes.iter().find(|h| h.algo == HASH_SHA1).map(|h| h.value.clone());

        match &file.download_url {
            Some(url) => {
                http::download(&Download { url: url.clone(), path: inst.mods_dir().join(&file.file_name), sha1 })
                    .await?;
                installed.push(info.name.clone());
                inst.mods.push(InstalledMod {
                    source: Source::Curseforge,
                    project_id: info.id.to_string(),
                    version_id: file.id.to_string(),
                    title: info.name.clone(),
                    file_name: file.file_name.clone(),
                    icon_url: info.icon(),
                });
            }
            None => {
                let p = PendingDownload {
                    source: Source::Curseforge,
                    project_id: info.id.to_string(),
                    version_id: file.id.to_string(),
                    title: info.name.clone(),
                    file_name: file.file_name.clone(),
                    sha1,
                    page_url: format!("https://www.curseforge.com/minecraft/mc-mods/{}/download/{}", info.slug, file.id),
                    icon_url: info.icon(),
                };
                pending.push(p.clone());
                inst.pending.push(p);
            }
        }
        inst.save()?;

        queue.extend(
            file.dependencies
                .iter()
                .filter(|d| d.relation_type == RELATION_REQUIRED)
                .map(|d| d.mod_id),
        );
    }
    Ok(InstallResult { instance: inst, installed, pending })
}
