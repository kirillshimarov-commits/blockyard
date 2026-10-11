//! Fabric and Quilt: both publish ready-made launcher profiles through a meta API.

use super::http::{self, Download};
use super::mojang::{self, VersionJson};
use super::paths;
use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy)]
pub enum Flavor {
    Fabric,
    Quilt,
}

impl Flavor {
    fn meta_url(self) -> &'static str {
        match self {
            Flavor::Fabric => "https://meta.fabricmc.net/v2",
            Flavor::Quilt => "https://meta.quiltmc.org/v3",
        }
    }

    fn id_prefix(self) -> &'static str {
        match self {
            Flavor::Fabric => "fabric-loader",
            Flavor::Quilt => "quilt-loader",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoaderVersion {
    pub version: String,
    #[serde(default)]
    pub stable: bool,
    /// Suggested default (Forge "recommended" build).
    #[serde(default)]
    pub recommended: bool,
}

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: LoaderVersion,
}

/// Loader versions that support the given Minecraft version, newest first.
pub async fn loader_versions(flavor: Flavor, game_version: &str) -> Result<Vec<LoaderVersion>> {
    let url = format!("{}/versions/loader/{game_version}", flavor.meta_url());
    let entries: Vec<LoaderEntry> = http::get_json(&url).await?;
    Ok(entries
        .into_iter()
        .map(|e| {
            let mut v = e.loader;
            // Quilt has no "stable" flag; treat non-beta builds as stable.
            if matches!(flavor, Flavor::Quilt) {
                v.stable = !v.version.contains("beta") && !v.version.contains("pre");
            }
            v
        })
        .collect())
}

/// Downloads the loader profile and merges it with vanilla.
pub async fn load(flavor: Flavor, game_version: &str, loader_version: &str, refresh: bool) -> Result<VersionJson> {
    let id = format!("{}-{loader_version}-{game_version}", flavor.id_prefix());
    let path = paths::versions_dir().join(&id).join(format!("{id}.json"));
    if refresh {
        let _ = std::fs::remove_file(&path);
    }
    if !path.is_file() {
        let url = format!(
            "{}/versions/loader/{game_version}/{loader_version}/profile/json",
            flavor.meta_url()
        );
        http::download(&Download { url, path: path.clone(), sha1: None }).await?;
    }
    let profile = mojang::read_version_json(&path)?;
    let vanilla = mojang::load_vanilla(game_version).await?;
    Ok(mojang::merge(vanilla, profile))
}
