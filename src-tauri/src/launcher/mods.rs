//! Source-independent mod types shared by Modrinth and CurseForge.

use super::instance::{self, InstalledMod, Instance};
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    #[default]
    Modrinth,
    Curseforge,
}

/// What a catalog tab lists.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentKind {
    Mod,
    Shader,
    Modpack,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModHit {
    pub source: Source,
    pub project_id: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub categories: Vec<String>,
    pub page_url: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub hits: Vec<ModHit>,
    pub total_hits: u64,
}

/// A mod whose author forbids third-party downloads: the user downloads it in the browser.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingDownload {
    pub source: Source,
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub file_name: String,
    pub sha1: Option<String>,
    pub page_url: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    /// Folder inside the game directory: "mods", "shaderpacks" or "resourcepacks".
    #[serde(default = "default_folder")]
    pub folder: String,
}

fn default_folder() -> String {
    "mods".into()
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallResult {
    pub instance: Instance,
    /// Titles of mods that were downloaded.
    pub installed: Vec<String>,
    /// Mods the user still has to download manually.
    pub pending: Vec<PendingDownload>,
    /// Non-fatal problems worth showing to the user.
    #[serde(default)]
    pub warnings: Vec<String>,
}

impl InstallResult {
    pub fn new(instance: Instance) -> Self {
        Self { instance, installed: Vec::new(), pending: Vec::new(), warnings: Vec::new() }
    }
}

/// Lowercase letters and digits only: "Fabric API" and "fabric-api" compare equal.
pub fn normalize_title(title: &str) -> String {
    title.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

/// True if the instance already has this mod, from either source.
pub fn already_installed(instance: &Instance, source: Source, project_id: &str, title: &str) -> bool {
    let norm = normalize_title(title);
    instance
        .mods
        .iter()
        .any(|m| (m.source == source && m.project_id == project_id) || normalize_title(&m.title) == norm)
}

pub fn check_file_name(name: &str) -> Result<()> {
    if name.is_empty() || name.contains(['/', '\\']) || name.starts_with('.') {
        bail!("недопустимое имя файла мода");
    }
    Ok(())
}

/// Looks in the Downloads folder for pending manual downloads and moves them into the instance.
pub fn import_pending(instance_id: &str) -> Result<Instance> {
    let mut inst = instance::get(instance_id)?;
    let Some(downloads) = dirs::download_dir() else { return Ok(inst) };
    std::fs::create_dir_all(inst.mods_dir())?;

    let mut still_pending = Vec::new();
    for p in std::mem::take(&mut inst.pending) {
        let src = downloads.join(&p.file_name);
        let valid = src.is_file()
            && p.sha1.as_ref().is_none_or(|expected| {
                std::fs::read(&src)
                    .map(|bytes| hex::encode(Sha1::digest(&bytes)).eq_ignore_ascii_case(expected))
                    .unwrap_or(false)
            });
        if !valid {
            still_pending.push(p);
            continue;
        }
        let folder = match p.folder.as_str() {
            "shaderpacks" | "resourcepacks" => p.folder.as_str(),
            _ => "mods",
        };
        let dest_dir = inst.game_dir().join(folder);
        std::fs::create_dir_all(&dest_dir)?;
        let dest = dest_dir.join(&p.file_name);
        if std::fs::rename(&src, &dest).is_err() {
            // Downloads may be on another drive.
            std::fs::copy(&src, &dest)?;
            let _ = std::fs::remove_file(&src);
        }
        let entry = InstalledMod {
            source: p.source,
            project_id: p.project_id,
            version_id: p.version_id,
            title: p.title,
            file_name: p.file_name,
            icon_url: p.icon_url,
        };
        match folder {
            "shaderpacks" => inst.shaders.push(entry),
            "resourcepacks" => {}
            _ => inst.mods.push(entry),
        }
    }
    inst.pending = still_pending;
    inst.save()?;
    Ok(inst)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn titles_normalize() {
        assert_eq!(normalize_title("Fabric API"), normalize_title("fabric-api"));
        assert_ne!(normalize_title("Sodium"), normalize_title("Sodium Extra"));
    }
}
