use super::paths;
use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// Offline account nickname.
    pub username: String,
    /// Default RAM for new instances, in MB.
    pub memory_mb: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Self { username: "Player".into(), memory_mb: 4096 }
    }
}

pub fn load() -> Settings {
    std::fs::read_to_string(paths::settings_file())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn save(settings: &Settings) -> Result<()> {
    std::fs::create_dir_all(paths::data_dir())?;
    std::fs::write(paths::settings_file(), serde_json::to_string_pretty(settings)?)?;
    Ok(())
}

/// Minecraft usernames: 3-16 characters, latin letters, digits and underscore.
pub fn is_valid_username(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}
