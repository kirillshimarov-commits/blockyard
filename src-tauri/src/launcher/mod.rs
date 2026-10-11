//! Launcher core: everything that does not depend on Tauri.

pub mod appearance;
pub mod auth;
pub mod crash;
pub mod curseforge;
pub mod fabric;
pub mod forge;
pub mod http;
pub mod instance;
pub mod java;
pub mod launch;
pub mod modrinth;
pub mod modpack;
pub mod mods;
pub mod mojang;
pub mod news;
pub mod tunnel;
pub mod updater;
pub mod updates;
pub mod yggdrasil;
pub mod paths;
pub mod settings;

use serde::Serialize;
use std::sync::Arc;

/// Progress update sent to the UI while installing or downloading.
#[derive(Debug, Clone, Serialize)]
pub struct Progress {
    pub stage: String,
    pub current: u64,
    pub total: u64,
}

pub type Reporter = Arc<dyn Fn(Progress) + Send + Sync>;

pub fn report(reporter: &Reporter, stage: &str, current: u64, total: u64) {
    reporter(Progress {
        stage: stage.to_string(),
        current,
        total,
    });
}

/// Seconds since the Unix epoch.
pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
