//! The Blockyard UI mod: the game's main menu restyled like the launcher.
//!
//! The jar is embedded into the launcher at build time and copied into the mods folder of
//! instances it supports; it is removed again when the feature is off or the instance changes.

use super::appearance;
use super::http::{self, Download};
use super::instance::{Instance, Loader};
use super::modrinth;
use anyhow::Result;
use std::path::Path;

mod jars {
    include!(concat!(env!("OUT_DIR"), "/gameui_jars.rs"));
}
const FILE_NAME: &str = "blockyard-ui.jar";
/// Fabric API loads the mod's fonts and textures; installed alongside when the user has none.
const FABRIC_API: &str = "P7dR8mSH";
const FABRIC_API_FILE: &str = "blockyard-fabric-api.jar";

/// Whether this launcher build carries the mod for any game version.
pub fn available() -> bool {
    !jars::BUILDS.is_empty()
}

/// The build of the mod for the instance's loader and game version (Quilt loads Fabric mods too).
fn jar_for(instance: &Instance) -> Option<&'static [u8]> {
    let loader = match instance.loader {
        Loader::Fabric { .. } | Loader::Quilt { .. } => "fabric",
        Loader::Forge { .. } => "forge",
        Loader::NeoForge { .. } => "neoforge",
        Loader::Vanilla => return None,
    };
    jars::BUILDS
        .iter()
        .find(|b| b.loader == loader && b.versions.contains(&instance.game_version.as_str()))
        .map(|b| b.jar)
}

/// Whether the mod is built for this instance's loader and game version.
pub fn supports(instance: &Instance) -> bool {
    jar_for(instance).is_some()
}

/// Mods that replace the menus themselves; with one of them the automatic mode stays out of the way.
const MENU_MODS: &[&str] = &["fancymenu", "drippyloadingscreen", "drippy-loading-screen", "custommainmenu", "custom-main-menu"];

/// Whether the instance should get the mod: the instance's own choice ("on"/"off"), otherwise
/// the launcher setting, unless another menu mod is installed.
pub fn wanted(choice: &str, global: bool, game_dir: &Path) -> bool {
    match choice {
        "on" => true,
        "off" => false,
        _ => global && other_menu_mod(game_dir).is_none(),
    }
}

/// File name of an installed mod that changes the menus too, if any.
pub fn other_menu_mod(game_dir: &Path) -> Option<String> {
    let entries = std::fs::read_dir(game_dir.join("mods")).ok()?;
    entries.flatten().map(|e| e.file_name().to_string_lossy().to_string()).find(|name| {
        let lower = name.to_lowercase().replace(['_', ' '], "-");
        lower.ends_with(".jar") && MENU_MODS.iter().any(|m| lower.starts_with(m) || lower.contains(&format!("-{m}")))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_menu_mods() {
        let dir = std::env::temp_dir().join(format!("by-gameui-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("mods")).unwrap();
        assert!(wanted("", true, &dir));
        std::fs::write(dir.join("mods").join("fancymenu_fabric_3.2.3_MC_1.21.1.jar"), b"").unwrap();
        assert!(!wanted("", true, &dir));
        assert!(wanted("on", true, &dir));
        assert!(!wanted("off", true, &dir));
        std::fs::remove_dir_all(&dir).ok();
    }
}

/// Installs or removes the mod in `game_dir` and writes its settings (the accent color).
pub async fn sync(game_dir: &Path, instance: &Instance, enabled: bool) -> Result<()> {
    let mods = game_dir.join("mods");
    let jar = mods.join(FILE_NAME);
    let api = mods.join(FABRIC_API_FILE);
    let Some(bytes) = jar_for(instance).filter(|_| enabled) else {
        for file in [&jar, &api] {
            if file.exists() {
                std::fs::remove_file(file)?;
            }
        }
        return Ok(());
    };
    std::fs::create_dir_all(&mods)?;
    if std::fs::read(&jar).ok().as_deref() != Some(bytes) {
        std::fs::write(&jar, bytes)?;
    }
    // Only the Fabric build needs Fabric API; Forge and NeoForge load the mod's assets themselves.
    if matches!(instance.loader, Loader::Fabric { .. } | Loader::Quilt { .. }) {
        ensure_fabric_api(&mods, instance).await?;
    } else if api.exists() {
        std::fs::remove_file(&api)?;
    }

    let saved = appearance::load();
    let accent = appearance::field(&saved, "accent", "#8b5cff");
    std::fs::create_dir_all(game_dir.join("config"))?;
    std::fs::write(
        game_dir.join("config").join("blockyard-ui.json"),
        serde_json::json!({ "accent": accent }).to_string(),
    )?;
    Ok(())
}

/// Keeps exactly one Fabric API: the user's own if they installed it, otherwise ours.
async fn ensure_fabric_api(mods: &Path, instance: &Instance) -> Result<()> {
    let ours = mods.join(FABRIC_API_FILE);
    let user_has_it = instance.mods.iter().any(|m| m.project_id == FABRIC_API && !m.disabled)
        || std::fs::read_dir(mods)?.flatten().any(|e| {
            let name = e.file_name().to_string_lossy().to_lowercase();
            name.starts_with("fabric-api") && name.ends_with(".jar")
        });
    if user_has_it {
        if ours.exists() {
            std::fs::remove_file(&ours)?;
        }
        return Ok(());
    }
    if ours.exists() {
        return Ok(());
    }
    let loaders: &[&str] = &["fabric"];
    let Some(version) = modrinth::latest_version(FABRIC_API, Some(&instance.game_version), Some(loaders)).await? else {
        return Ok(());
    };
    let Some(file) = version.files.iter().find(|f| f.primary).or(version.files.first()) else {
        return Ok(());
    };
    http::download(&Download { url: file.url.clone(), path: ours, sha1: file.hashes.sha1.clone() }).await
}
