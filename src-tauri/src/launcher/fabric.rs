//! Fabric mod loader support via meta.fabricmc.net.

use super::http::{self, Download};
use super::mojang::{self, VersionJson};
use super::paths;
use anyhow::Result;
use serde::{Deserialize, Serialize};

const META_URL: &str = "https://meta.fabricmc.net/v2";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoaderVersion {
    pub version: String,
    pub stable: bool,
}

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: LoaderVersion,
}

/// Fabric loader versions that support the given Minecraft version, newest first.
pub async fn loader_versions(game_version: &str) -> Result<Vec<LoaderVersion>> {
    let entries: Vec<LoaderEntry> = http::get_json(&format!("{META_URL}/versions/loader/{game_version}")).await?;
    Ok(entries.into_iter().map(|e| e.loader).collect())
}

/// Downloads the Fabric profile and merges it with vanilla.
pub async fn load(game_version: &str, loader_version: &str) -> Result<VersionJson> {
    let id = format!("fabric-loader-{loader_version}-{game_version}");
    let path = paths::versions_dir().join(&id).join(format!("{id}.json"));
    if !path.is_file() {
        let url = format!("{META_URL}/versions/loader/{game_version}/{loader_version}/profile/json");
        http::download(&Download { url, path: path.clone(), sha1: None }).await?;
    }
    let profile = mojang::read_version_json(&path)?;
    let vanilla = mojang::load_vanilla(game_version).await?;
    Ok(mojang::merge(vanilla, profile))
}
