//! Modrinth API v2: search and install mods with their required dependencies.

use super::http::{self, Download};
use super::instance::{self, InstalledMod, Instance};
use anyhow::{anyhow, bail, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

const API_URL: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    pub downloads: u64,
    #[serde(default)]
    pub categories: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub hits: Vec<SearchHit>,
    pub total_hits: u64,
    pub offset: u64,
}

#[derive(Debug, Clone, Deserialize)]
struct Project {
    id: String,
    title: String,
    #[serde(default)]
    icon_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Version {
    id: String,
    project_id: String,
    files: Vec<VersionFile>,
    #[serde(default)]
    dependencies: Vec<Dependency>,
}

#[derive(Debug, Clone, Deserialize)]
struct VersionFile {
    url: String,
    filename: String,
    primary: bool,
    hashes: FileHashes,
}

#[derive(Debug, Clone, Deserialize)]
struct FileHashes {
    #[serde(default)]
    sha1: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct Dependency {
    #[serde(default)]
    version_id: Option<String>,
    #[serde(default)]
    project_id: Option<String>,
    dependency_type: String,
}

fn enc(s: &str) -> String {
    urlencoding::encode(s).into_owned()
}

/// Searches mods, optionally limited to a Minecraft version and loader.
pub async fn search(
    query: &str,
    game_version: Option<&str>,
    loader: Option<&str>,
    sort: &str,
    offset: u64,
) -> Result<SearchResult> {
    let mut facets = vec![vec!["project_type:mod".to_string()]];
    if let Some(v) = game_version {
        facets.push(vec![format!("versions:{v}")]);
    }
    if let Some(l) = loader {
        facets.push(vec![format!("categories:{l}")]);
    }
    let index = match sort {
        "downloads" | "follows" | "newest" | "updated" => sort,
        _ => "relevance",
    };
    let url = format!(
        "{API_URL}/search?query={}&facets={}&index={index}&offset={offset}&limit=20",
        enc(query),
        enc(&serde_json::to_string(&facets)?),
    );
    http::get_json(&url).await
}

async fn latest_compatible(project_id: &str, game_version: &str, loader: &str) -> Result<Option<Version>> {
    let url = format!(
        "{API_URL}/project/{}/version?loaders={}&game_versions={}",
        enc(project_id),
        enc(&format!("[\"{loader}\"]")),
        enc(&format!("[\"{game_version}\"]")),
    );
    let versions: Vec<Version> = http::get_json(&url).await?;
    // Modrinth returns versions newest first.
    Ok(versions.into_iter().next())
}

/// Installs a mod and its required dependencies into the instance.
/// Returns the updated instance and the titles of newly installed mods.
pub async fn install(instance_id: &str, project_id: &str) -> Result<(Instance, Vec<String>)> {
    let mut inst = instance::get(instance_id)?;
    let loader = inst
        .loader
        .modrinth_name()
        .ok_or_else(|| anyhow!("Моды можно ставить только в сборку с Fabric"))?;
    let game_version = inst.game_version.clone();

    let mut installed_titles = Vec::new();
    let mut visited: HashSet<String> = inst.mods.iter().map(|m| m.project_id.clone()).collect();
    // (project id, exact version id if a dependency pins one)
    let mut queue: Vec<(Option<String>, Option<String>)> = vec![(Some(project_id.to_string()), None)];
    let mut root = true;

    while let Some((pid, vid)) = queue.pop() {
        let version = match (&vid, &pid) {
            (Some(vid), _) => Some(http::get_json::<Version>(&format!("{API_URL}/version/{}", enc(vid))).await?),
            (None, Some(pid)) => latest_compatible(pid, &game_version, loader).await?,
            (None, None) => None,
        };
        let Some(version) = version else {
            if root {
                bail!("У этого мода нет версии для Minecraft {game_version} ({loader})");
            }
            continue; // Dependency without a compatible build: skip rather than fail.
        };
        root = false;
        if !visited.insert(version.project_id.clone()) {
            continue;
        }

        let file = version
            .files
            .iter()
            .find(|f| f.primary)
            .or_else(|| version.files.first())
            .ok_or_else(|| anyhow!("у версии мода нет файлов"))?;
        if file.filename.contains(['/', '\\']) || file.filename.starts_with('.') {
            bail!("недопустимое имя файла мода");
        }
        http::download(&Download {
            url: file.url.clone(),
            path: inst.mods_dir().join(&file.filename),
            sha1: file.hashes.sha1.clone(),
        })
        .await?;

        let project: Project = http::get_json(&format!("{API_URL}/project/{}", enc(&version.project_id))).await?;
        installed_titles.push(project.title.clone());
        inst.mods.push(InstalledMod {
            project_id: project.id,
            version_id: version.id.clone(),
            title: project.title,
            file_name: file.filename.clone(),
            icon_url: project.icon_url,
        });
        inst.save()?;

        for dep in version.dependencies.iter().filter(|d| d.dependency_type == "required") {
            if dep.project_id.as_ref().is_some_and(|p| visited.contains(p)) {
                continue;
            }
            queue.push((dep.project_id.clone(), dep.version_id.clone()));
        }
    }
    Ok((inst, installed_titles))
}
