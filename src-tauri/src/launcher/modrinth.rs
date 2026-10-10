//! Modrinth API v2: search and install mods with their required dependencies.

use super::http::{self, Download};
use super::instance::{self, InstalledMod};
use super::mods::{self, ContentKind, InstallResult, ModHit, SearchResult, Source};
use anyhow::{anyhow, bail, Result};
use serde::Deserialize;
use std::collections::HashSet;

const API_URL: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Deserialize)]
struct SearchHit {
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

#[derive(Debug, Clone, Deserialize)]
struct SearchResponse {
    hits: Vec<SearchHit>,
    total_hits: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Project {
    pub(crate) id: String,
    pub(crate) title: String,
    #[serde(default)]
    pub(crate) icon_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Version {
    pub(crate) id: String,
    pub(crate) project_id: String,
    pub(crate) files: Vec<VersionFile>,
    #[serde(default)]
    pub(crate) game_versions: Vec<String>,
    #[serde(default)]
    pub(crate) dependencies: Vec<Dependency>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct VersionFile {
    pub(crate) url: String,
    pub(crate) filename: String,
    pub(crate) primary: bool,
    pub(crate) hashes: FileHashes,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct FileHashes {
    #[serde(default)]
    pub(crate) sha1: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct Dependency {
    #[serde(default)]
    pub(crate) version_id: Option<String>,
    #[serde(default)]
    pub(crate) project_id: Option<String>,
    pub(crate) dependency_type: String,
}

fn enc(s: &str) -> String {
    urlencoding::encode(s).into_owned()
}

/// Modrinth loader names accepted for a given loader (Quilt runs Fabric mods too).
fn compatible_loaders(loader: &str) -> Vec<&str> {
    match loader {
        "quilt" => vec!["quilt", "fabric"],
        other => vec![other],
    }
}

/// Searches mods, shaders or modpacks, optionally limited to a Minecraft version and loader.
pub async fn search(
    kind: ContentKind,
    query: &str,
    game_version: Option<&str>,
    loader: Option<&str>,
    sort: &str,
    offset: u64,
) -> Result<SearchResult> {
    let (project_type, path) = match kind {
        ContentKind::Mod => ("mod", "mod"),
        ContentKind::Shader => ("shader", "shader"),
        ContentKind::Modpack => ("modpack", "modpack"),
    };
    let mut facets = vec![vec![format!("project_type:{project_type}")]];
    if kind == ContentKind::Shader {
        // Packs that Iris/Oculus can load.
        facets.push(vec!["categories:iris".into(), "categories:optifine".into()]);
    } else {
        if let Some(v) = game_version {
            facets.push(vec![format!("versions:{v}")]);
        }
        if let Some(l) = loader {
            facets.push(compatible_loaders(l).into_iter().map(|l| format!("categories:{l}")).collect());
        }
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
    let resp: SearchResponse = http::get_json(&url).await?;
    Ok(SearchResult {
        total_hits: resp.total_hits,
        hits: resp
            .hits
            .into_iter()
            .map(|h| ModHit {
                source: Source::Modrinth,
                page_url: format!("https://modrinth.com/{path}/{}", h.slug),
                project_id: h.project_id,
                title: h.title,
                description: h.description,
                author: h.author,
                icon_url: h.icon_url,
                downloads: h.downloads,
                categories: h.categories,
            })
            .collect(),
    })
}

/// Newest version of a project, optionally filtered by game version and loaders.
pub(crate) async fn latest_version(
    project_id: &str,
    game_version: Option<&str>,
    loaders: Option<&[&str]>,
) -> Result<Option<Version>> {
    let mut url = format!("{API_URL}/project/{}/version?", enc(project_id));
    if let Some(l) = loaders {
        url.push_str(&format!("loaders={}&", enc(&serde_json::to_string(l)?)));
    }
    if let Some(v) = game_version {
        url.push_str(&format!("game_versions={}", enc(&format!("[\"{v}\"]"))));
    }
    let versions: Vec<Version> = http::get_json(&url).await?;
    // Modrinth returns versions newest first.
    Ok(versions.into_iter().next())
}

pub(crate) async fn project(id_or_slug: &str) -> Result<Project> {
    http::get_json(&format!("{API_URL}/project/{}", enc(id_or_slug))).await
}

fn primary_file(version: &Version) -> Result<&VersionFile> {
    let file = version
        .files
        .iter()
        .find(|f| f.primary)
        .or_else(|| version.files.first())
        .ok_or_else(|| anyhow!("у версии нет файлов"))?;
    mods::check_file_name(&file.filename)?;
    Ok(file)
}

/// Installs a mod and its required dependencies into the instance.
pub async fn install(instance_id: &str, project_id: &str) -> Result<InstallResult> {
    let mut inst = instance::get(instance_id)?;
    let loader = inst
        .loader
        .mod_loader()
        .ok_or_else(|| anyhow!("Моды можно ставить только в сборку с загрузчиком модов"))?;
    let loaders = compatible_loaders(loader);
    let game_version = inst.game_version.clone();

    let mut installed_titles = Vec::new();
    let mut visited: HashSet<String> = HashSet::new();
    // (project id, exact version id if a dependency pins one)
    let mut queue: Vec<(Option<String>, Option<String>)> = vec![(Some(project_id.to_string()), None)];
    let mut root = true;

    while let Some((pid, vid)) = queue.pop() {
        let version = match (&vid, &pid) {
            (Some(vid), _) => Some(http::get_json::<Version>(&format!("{API_URL}/version/{}", enc(vid))).await?),
            (None, Some(pid)) => latest_version(pid, Some(&game_version), Some(&loaders)).await?,
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
        let project = project(&version.project_id).await?;
        if mods::already_installed(&inst, Source::Modrinth, &project.id, &project.title) {
            continue;
        }

        let file = primary_file(&version)?;
        http::download(&Download {
            url: file.url.clone(),
            path: inst.mods_dir().join(&file.filename),
            sha1: file.hashes.sha1.clone(),
        })
        .await?;

        installed_titles.push(project.title.clone());
        inst.mods.push(InstalledMod {
            source: Source::Modrinth,
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
    Ok(InstallResult { installed: installed_titles, ..InstallResult::new(inst) })
}

/// Installs a shader pack, plus Iris/Oculus if the instance does not have it yet.
pub async fn install_shader(instance_id: &str, project_id: &str) -> Result<InstallResult> {
    let inst = instance::get(instance_id)?;
    let shader_mod = inst
        .loader
        .shader_mod()
        .ok_or_else(|| anyhow!("Для шейдеров нужна сборка с загрузчиком модов (Fabric, Forge…)"))?;

    // The shader loader mod first, so a failure there does not leave a half-done install.
    let mut result = match install(instance_id, shader_mod).await {
        Ok(r) => r,
        Err(e) => {
            let mut r = InstallResult::new(inst);
            r.warnings.push(format!("Не удалось поставить {shader_mod}: {e:#}. Без него шейдеры не заработают."));
            r
        }
    };
    let mut inst = instance::get(instance_id)?;

    let project = project(project_id).await?;
    if inst.shaders.iter().any(|s| s.project_id == project.id) {
        result.instance = inst;
        return Ok(result);
    }
    let version = latest_version(&project.id, None, Some(&["iris", "optifine"]))
        .await?
        .ok_or_else(|| anyhow!("У этого шейдера нет файлов для Iris/OptiFine"))?;
    let file = primary_file(&version)?;
    http::download(&Download {
        url: file.url.clone(),
        path: inst.shaderpacks_dir().join(&file.filename),
        sha1: file.hashes.sha1.clone(),
    })
    .await?;
    inst.shaders.push(InstalledMod {
        source: Source::Modrinth,
        project_id: project.id,
        version_id: version.id.clone(),
        title: project.title.clone(),
        file_name: file.filename.clone(),
        icon_url: project.icon_url,
    });
    inst.save()?;
    result.installed.push(project.title);
    result.instance = inst;
    Ok(result)
}
