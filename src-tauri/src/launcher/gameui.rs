//! The Blockyard UI mod: the game's main menu restyled like the launcher.
//!
//! The jar is embedded into the launcher at build time and copied into the mods folder of
//! instances it supports; it is removed again when the feature is off or the instance changes.

use super::appearance;
use super::instance::{Instance, Loader};
use anyhow::Result;
use std::path::Path;

static JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/blockyard-ui.jar"));
const FILE_NAME: &str = "blockyard-ui.jar";

/// Whether this launcher build carries the mod.
pub fn available() -> bool {
    !JAR.is_empty()
}

/// Game versions and loaders the mod is built for (Quilt loads Fabric mods too).
pub fn supports(instance: &Instance) -> bool {
    matches!(instance.loader, Loader::Fabric { .. } | Loader::Quilt { .. }) && instance.game_version == "1.21.1"
}

/// Installs or removes the mod in `game_dir` and writes its settings (the accent color).
pub fn sync(game_dir: &Path, instance: &Instance, enabled: bool) -> Result<()> {
    let jar = game_dir.join("mods").join(FILE_NAME);
    if !(enabled && available() && supports(instance)) {
        if jar.exists() {
            std::fs::remove_file(&jar)?;
        }
        return Ok(());
    }
    std::fs::create_dir_all(game_dir.join("mods"))?;
    if std::fs::read(&jar).ok().as_deref() != Some(JAR) {
        std::fs::write(&jar, JAR)?;
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
