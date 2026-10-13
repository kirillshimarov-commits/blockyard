//! A project's page inside the launcher: description, screenshots, versions and dependencies,
//! from Modrinth or CurseForge, in one source-independent shape.

use super::curseforge::{self, Envelope};
use super::http;
use super::mods::Source;
use anyhow::Result;
use serde::{Deserialize, Serialize};

const MODRINTH: &str = "https://api.modrinth.com/v2";
/// Versions listed on the page; older ones stay on the website.
const MAX_VERSIONS: usize = 40;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModPage {
    pub source: Source,
    pub project_id: String,
    pub title: String,
    pub summary: String,
    pub author: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub followers: Option<u64>,
    pub categories: Vec<String>,
    pub page_url: String,
    /// The long description: Markdown from Modrinth (it may contain HTML), HTML from CurseForge.
    pub body: String,
    pub body_html: bool,
    pub gallery: Vec<GalleryImage>,
    pub links: Vec<Link>,
    pub license: Option<String>,
    /// ISO 8601 date of the last update.
    pub updated: Option<String>,
    /// Newest first.
    pub versions: Vec<VersionInfo>,
    /// Dependencies of the version that would be installed.
    pub dependencies: Vec<DependencyInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GalleryImage {
    pub url: String,
    pub thumbnail: String,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Link {
    /// "website" | "source" | "issues" | "wiki" | "discord"
    pub kind: &'static str,
    pub url: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionInfo {
    pub name: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    /// "release" | "beta" | "alpha"
    pub release_type: String,
    pub date: String,
    /// Fits the game version and loader the page was opened for.
    pub compatible: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DependencyInfo {
    pub project_id: String,
    pub title: String,
    pub icon_url: Option<String>,
    /// "required" | "optional" | "incompatible" | "embedded"
    pub kind: String,
}

/// Loads the page; `game_version` and `loader` mark which versions fit the target instance.
pub async fn load(source: Source, project_id: &str, game_version: Option<&str>, loader: Option<&str>) -> Result<ModPage> {
    match source {
        Source::Modrinth => modrinth_page(project_id, game_version, loader).await,
        Source::Curseforge => curseforge_page(project_id, game_version, loader).await,
    }
}

fn https(url: Option<String>) -> Option<String> {
    url.filter(|u| u.starts_with("https://"))
}

// ---------- Modrinth ----------

#[derive(Deserialize)]
struct MrProject {
    id: String,
    slug: String,
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    followers: u64,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    project_type: String,
    #[serde(default)]
    gallery: Vec<MrImage>,
    #[serde(default)]
    source_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
    #[serde(default)]
    wiki_url: Option<String>,
    #[serde(default)]
    discord_url: Option<String>,
    #[serde(default)]
    license: Option<MrLicense>,
    #[serde(default)]
    updated: Option<String>,
    #[serde(default)]
    team: String,
}

#[derive(Deserialize)]
struct MrImage {
    url: String,
    #[serde(default)]
    raw_url: Option<String>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    featured: bool,
}

#[derive(Deserialize)]
struct MrLicense {
    #[serde(default)]
    id: String,
    #[serde(default)]
    name: String,
}

#[derive(Deserialize)]
struct MrVersion {
    #[serde(default)]
    name: String,
    #[serde(default)]
    version_number: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    version_type: String,
    #[serde(default)]
    date_published: String,
    #[serde(default)]
    dependencies: Vec<MrDependency>,
}

#[derive(Deserialize)]
struct MrDependency {
    #[serde(default)]
    project_id: Option<String>,
    dependency_type: String,
}

#[derive(Deserialize)]
struct MrMember {
    user: MrUser,
    #[serde(default)]
    role: String,
}

#[derive(Deserialize)]
struct MrUser {
    username: String,
}

#[derive(Deserialize)]
struct MrBrief {
    id: String,
    title: String,
    #[serde(default)]
    icon_url: Option<String>,
}

fn enc(s: &str) -> String {
    urlencoding::encode(s).into_owned()
}

async fn modrinth_page(project_id: &str, game_version: Option<&str>, loader: Option<&str>) -> Result<ModPage> {
    let id = enc(project_id);
    let project: MrProject = http::get_json(&format!("{MODRINTH}/project/{id}")).await?;
    let (versions, members) = tokio::join!(
        http::get_json::<Vec<MrVersion>>(&format!("{MODRINTH}/project/{id}/version")),
        http::get_json::<Vec<MrMember>>(&format!("{MODRINTH}/team/{}/members", enc(&project.team))),
    );
    let versions = versions.unwrap_or_default();
    let author = members
        .ok()
        .and_then(|m| {
            let owner = m.iter().position(|x| x.role.eq_ignore_ascii_case("owner")).unwrap_or(0);
            m.into_iter().nth(owner).map(|x| x.user.username)
        })
        .unwrap_or_default();

    let loaders: Vec<&str> = loader.map(super::modrinth::compatible_loaders).unwrap_or_default();
    let fits = |v: &MrVersion| {
        game_version.is_some_and(|g| v.game_versions.iter().any(|x| x == g))
            && (loaders.is_empty() || v.loaders.iter().any(|l| loaders.contains(&l.as_str())))
    };

    // Dependencies of the version the launcher would install: the newest that fits, else the newest.
    let chosen = versions.iter().find(|v| fits(v)).or(versions.first());
    let mut dependencies = Vec::new();
    if let Some(v) = chosen {
        let deps: Vec<(&str, &str)> = v
            .dependencies
            .iter()
            .filter_map(|d| d.project_id.as_deref().map(|p| (p, d.dependency_type.as_str())))
            .collect();
        if !deps.is_empty() {
            let ids: Vec<&str> = deps.iter().map(|(p, _)| *p).collect();
            let url = format!("{MODRINTH}/projects?ids={}", enc(&serde_json::to_string(&ids)?));
            let briefs: Vec<MrBrief> = http::get_json(&url).await.unwrap_or_default();
            for (pid, kind) in deps {
                let brief = briefs.iter().find(|b| b.id == pid);
                dependencies.push(DependencyInfo {
                    project_id: pid.to_string(),
                    title: brief.map(|b| b.title.clone()).unwrap_or_else(|| pid.to_string()),
                    icon_url: brief.and_then(|b| b.icon_url.clone()),
                    kind: kind.to_string(),
                });
            }
        }
    }

    let mut gallery: Vec<&MrImage> = project.gallery.iter().collect();
    gallery.sort_by_key(|g| !g.featured);
    let mut links = Vec::new();
    for (kind, url) in [
        ("source", &project.source_url),
        ("issues", &project.issues_url),
        ("wiki", &project.wiki_url),
        ("discord", &project.discord_url),
    ] {
        if let Some(url) = https(url.clone()) {
            links.push(Link { kind, url });
        }
    }
    let path = if project.project_type.is_empty() { "mod" } else { project.project_type.as_str() };

    Ok(ModPage {
        source: Source::Modrinth,
        page_url: format!("https://modrinth.com/{path}/{}", project.slug),
        project_id: project.id,
        title: project.title,
        summary: project.description,
        author,
        icon_url: project.icon_url,
        downloads: project.downloads,
        followers: Some(project.followers),
        categories: project.categories,
        body: project.body,
        body_html: false,
        gallery: gallery
            .into_iter()
            .map(|g| GalleryImage {
                url: g.raw_url.clone().unwrap_or_else(|| g.url.clone()),
                thumbnail: g.url.clone(),
                title: g.title.clone().filter(|t| !t.is_empty()),
            })
            .collect(),
        links,
        license: project.license.map(|l| if l.name.is_empty() { l.id } else { l.name }).filter(|l| !l.is_empty()),
        updated: project.updated,
        versions: versions
            .iter()
            .take(MAX_VERSIONS)
            .map(|v| VersionInfo {
                name: if v.name.is_empty() { v.version_number.clone() } else { v.name.clone() },
                game_versions: v.game_versions.clone(),
                loaders: v.loaders.clone(),
                release_type: v.version_type.clone(),
                date: v.date_published.clone(),
                compatible: fits(v),
            })
            .collect(),
        dependencies,
    })
}

// ---------- CurseForge ----------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfMod {
    id: u64,
    name: String,
    slug: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    download_count: f64,
    #[serde(default)]
    thumbs_up_count: Option<u64>,
    #[serde(default)]
    logo: Option<CfImage>,
    #[serde(default)]
    authors: Vec<CfAuthor>,
    #[serde(default)]
    categories: Vec<CfCategory>,
    #[serde(default)]
    screenshots: Vec<CfImage>,
    #[serde(default)]
    links: Option<CfLinks>,
    #[serde(default)]
    date_modified: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfImage {
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    thumbnail_url: Option<String>,
}

#[derive(Deserialize)]
struct CfAuthor {
    name: String,
}

#[derive(Deserialize)]
struct CfCategory {
    name: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfLinks {
    #[serde(default)]
    website_url: Option<String>,
    #[serde(default)]
    wiki_url: Option<String>,
    #[serde(default)]
    issues_url: Option<String>,
    #[serde(default)]
    source_url: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfFile {
    #[serde(default)]
    display_name: String,
    #[serde(default)]
    file_name: String,
    #[serde(default)]
    release_type: u32,
    #[serde(default)]
    file_date: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    dependencies: Vec<CfDependency>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfDependency {
    mod_id: u64,
    relation_type: u32,
}

const CF_LOADERS: &[&str] = &["Forge", "Fabric", "Quilt", "NeoForge"];

async fn curseforge_page(project_id: &str, game_version: Option<&str>, loader: Option<&str>) -> Result<ModPage> {
    let id: u64 = project_id.parse()?;
    let (info, description, files) = tokio::join!(
        curseforge::get::<Envelope<CfMod>>(&format!("/mods/{id}")),
        curseforge::get::<Envelope<String>>(&format!("/mods/{id}/description")),
        curseforge::get::<Envelope<Vec<CfFile>>>(&format!("/mods/{id}/files?pageSize=50")),
    );
    let info = info?.data;
    let body = description.map(|d| d.data).unwrap_or_default();
    let mut files = files.map(|f| f.data).unwrap_or_default();
    files.sort_by(|a, b| b.file_date.cmp(&a.file_date));

    // CurseForge lists loaders among the game versions ("1.20.1", "Forge", "Client"…).
    let wanted_loaders: Vec<String> = match loader {
        Some("quilt") => vec!["quilt".into(), "fabric".into()],
        Some(l) => vec![l.to_string()],
        None => Vec::new(),
    };
    let split = |f: &CfFile| -> (Vec<String>, Vec<String>) {
        let loaders = f.game_versions.iter().filter(|v| CF_LOADERS.contains(&v.as_str())).cloned().collect();
        let games = f
            .game_versions
            .iter()
            .filter(|v| v.starts_with(|c: char| c.is_ascii_digit()) && v.contains('.'))
            .cloned()
            .collect();
        (loaders, games)
    };
    let fits = |f: &CfFile| {
        let (loaders, games) = split(f);
        game_version.is_some_and(|g| games.iter().any(|x| x == g))
            && (wanted_loaders.is_empty() || loaders.iter().any(|l| wanted_loaders.contains(&l.to_lowercase())))
    };

    let chosen = files.iter().find(|f| fits(f)).or(files.first());
    let mut dependencies = Vec::new();
    if let Some(f) = chosen {
        let ids: Vec<u64> = f.dependencies.iter().map(|d| d.mod_id).collect();
        if !ids.is_empty() {
            let briefs: Vec<CfMod> = curseforge::post::<Envelope<Vec<CfMod>>>("/mods", &serde_json::json!({ "modIds": ids }))
                .await
                .map(|e| e.data)
                .unwrap_or_default();
            for d in &f.dependencies {
                // RelationType: 1 embedded, 2 optional, 3 required, 4 tool, 5 incompatible, 6 include.
                let kind = match d.relation_type {
                    1 | 6 => "embedded",
                    3 => "required",
                    5 => "incompatible",
                    _ => "optional",
                };
                let brief = briefs.iter().find(|b| b.id == d.mod_id);
                dependencies.push(DependencyInfo {
                    project_id: d.mod_id.to_string(),
                    title: brief.map(|b| b.name.clone()).unwrap_or_else(|| d.mod_id.to_string()),
                    icon_url: brief.and_then(|b| b.logo.as_ref()).and_then(|l| l.thumbnail_url.clone()),
                    kind: kind.into(),
                });
            }
        }
    }

    let mut links = Vec::new();
    let mut page_url = format!("https://www.curseforge.com/minecraft/mc-mods/{}", info.slug);
    if let Some(l) = &info.links {
        if let Some(site) = https(l.website_url.clone()) {
            page_url = site;
        }
        for (kind, url) in [("source", &l.source_url), ("issues", &l.issues_url), ("wiki", &l.wiki_url)] {
            if let Some(url) = https(url.clone()) {
                links.push(Link { kind, url });
            }
        }
    }

    Ok(ModPage {
        source: Source::Curseforge,
        project_id: info.id.to_string(),
        title: info.name,
        summary: info.summary,
        author: info.authors.first().map(|a| a.name.clone()).unwrap_or_default(),
        icon_url: info.logo.and_then(|l| l.thumbnail_url),
        downloads: info.download_count as u64,
        followers: info.thumbs_up_count,
        categories: info.categories.into_iter().map(|c| c.name).collect(),
        page_url,
        body,
        body_html: true,
        gallery: info
            .screenshots
            .into_iter()
            .filter_map(|s| {
                let url = https(s.url)?;
                Some(GalleryImage { thumbnail: https(s.thumbnail_url).unwrap_or_else(|| url.clone()), url, title: s.title })
            })
            .collect(),
        links,
        license: None,
        updated: info.date_modified,
        versions: files
            .iter()
            .take(MAX_VERSIONS)
            .map(|f| {
                let (loaders, games) = split(f);
                VersionInfo {
                    name: if f.display_name.is_empty() { f.file_name.clone() } else { f.display_name.clone() },
                    game_versions: games,
                    loaders: loaders.into_iter().map(|l| l.to_lowercase()).collect(),
                    release_type: match f.release_type {
                        2 => "beta",
                        3 => "alpha",
                        _ => "release",
                    }
                    .into(),
                    date: f.file_date.clone(),
                    compatible: fits(f),
                }
            })
            .collect(),
        dependencies,
    })
}
