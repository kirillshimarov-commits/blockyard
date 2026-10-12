//! Instances: separate game folders, each with its own version, loader and mods.

use super::mods::{PendingDownload, Source};
use super::{now, paths};
use anyhow::{anyhow, bail, Result};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Loader {
    Vanilla,
    Fabric { version: String },
    Quilt { version: String },
    Forge { version: String },
    NeoForge { version: String },
}

impl Loader {
    /// Loader name used by mod sites, if mods are supported.
    pub fn mod_loader(&self) -> Option<&'static str> {
        match self {
            Loader::Vanilla => None,
            Loader::Fabric { .. } => Some("fabric"),
            Loader::Quilt { .. } => Some("quilt"),
            Loader::Forge { .. } => Some("forge"),
            Loader::NeoForge { .. } => Some("neoforge"),
        }
    }

    /// Modrinth slug of the mod that loads shader packs on this loader.
    pub fn shader_mod(&self) -> Option<&'static str> {
        match self {
            Loader::Vanilla => None,
            Loader::Forge { .. } => Some("oculus"),
            _ => Some("iris"),
        }
    }
}

/// The modpack an instance was created from.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackRef {
    pub source: Source,
    pub project_id: String,
    pub version_id: String,
    pub title: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledMod {
    #[serde(default)]
    pub source: Source,
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub file_name: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    /// Disabled mods stay on disk as "<file>.disabled" so the loader skips them.
    #[serde(default)]
    pub disabled: bool,
}

impl InstalledMod {
    /// File name as it is on disk right now.
    pub fn disk_name(&self) -> String {
        if self.disabled {
            format!("{}.disabled", self.file_name)
        } else {
            self.file_name.clone()
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: Loader,
    pub memory_mb: u32,
    pub created_at: u64,
    #[serde(default)]
    pub last_played: Option<u64>,
    #[serde(default)]
    pub mods: Vec<InstalledMod>,
    /// Mods waiting for the user to download them in the browser.
    #[serde(default)]
    pub pending: Vec<PendingDownload>,
    #[serde(default)]
    pub shaders: Vec<InstalledMod>,
    #[serde(default)]
    pub resourcepacks: Vec<InstalledMod>,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub modpack: Option<ModpackRef>,
    #[serde(default)]
    pub settings: InstanceSettings,
}

/// Per-instance options from the settings dialog. Empty strings mean "not set".
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct InstanceSettings {
    /// Library section the instance is shown in.
    pub group: String,
    /// Which mod releases count as updates: "release", "beta" or "alpha".
    pub update_channel: String,
    /// Java executable to use instead of the automatically downloaded one.
    pub java_path: String,
    /// Extra JVM arguments, e.g. "-XX:+UseG1GC".
    pub jvm_args: String,
    /// Environment variables, one KEY=VALUE per line.
    pub env_vars: String,
    pub fullscreen: bool,
    pub width: Option<u32>,
    pub height: Option<u32>,
    /// Shell command run before the game starts; a failure cancels the launch.
    pub pre_launch: String,
    /// Program the game is started through, e.g. "prime-run".
    pub wrapper: String,
    /// Shell command run after the game exits.
    pub post_exit: String,
    /// In-game menus: "" follows the launcher setting (and steps aside for other menu mods),
    /// "on" always uses the Blockyard menus, "off" keeps the standard ones.
    pub game_ui: String,
}

impl InstanceSettings {
    /// Release types allowed by the update channel, most stable first.
    pub fn allowed_release_types(&self) -> &'static [&'static str] {
        match self.update_channel.as_str() {
            "alpha" => &["release", "beta", "alpha"],
            "beta" => &["release", "beta"],
            _ => &["release"],
        }
    }
}

impl Instance {
    pub fn dir(&self) -> PathBuf {
        paths::instances_dir().join(&self.id)
    }

    /// The .minecraft-style folder with saves, mods and configs.
    pub fn game_dir(&self) -> PathBuf {
        self.dir().join("minecraft")
    }

    pub fn mods_dir(&self) -> PathBuf {
        self.game_dir().join("mods")
    }

    /// The tracked list for a game folder: "mods", "shaderpacks" or "resourcepacks".
    pub fn list_mut(&mut self, folder: &str) -> Option<&mut Vec<InstalledMod>> {
        match folder {
            "mods" => Some(&mut self.mods),
            "shaderpacks" => Some(&mut self.shaders),
            "resourcepacks" => Some(&mut self.resourcepacks),
            _ => None,
        }
    }

    pub fn natives_dir(&self) -> PathBuf {
        self.dir().join("natives")
    }

    pub fn save(&self) -> Result<()> {
        std::fs::create_dir_all(self.game_dir())?;
        std::fs::write(self.dir().join("instance.json"), serde_json::to_string_pretty(self)?)?;
        Ok(())
    }
}

/// Instance ids are generated by us; reject anything that could escape the instances folder.
fn check_id(id: &str) -> Result<()> {
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        bail!("invalid instance id");
    }
    Ok(())
}

pub fn list() -> Vec<Instance> {
    let Ok(entries) = std::fs::read_dir(paths::instances_dir()) else { return Vec::new() };
    let mut instances: Vec<Instance> = entries
        .flatten()
        .filter_map(|e| std::fs::read_to_string(e.path().join("instance.json")).ok())
        .filter_map(|text| serde_json::from_str(&text).ok())
        .collect();
    instances.sort_by_key(|i| std::cmp::Reverse(i.last_played.unwrap_or(i.created_at)));
    instances
}

pub fn get(id: &str) -> Result<Instance> {
    check_id(id)?;
    let path = paths::instances_dir().join(id).join("instance.json");
    let text = std::fs::read_to_string(path).map_err(|_| anyhow!("сборка не найдена"))?;
    Ok(serde_json::from_str(&text)?)
}

pub fn create(name: &str, game_version: &str, loader: Loader, memory_mb: u32) -> Result<Instance> {
    let name = name.trim();
    if name.is_empty() {
        bail!("укажите название сборки");
    }
    let instance = Instance {
        id: uuid::Uuid::new_v4().to_string(),
        name: name.to_string(),
        game_version: game_version.to_string(),
        loader,
        memory_mb,
        created_at: now(),
        last_played: None,
        mods: Vec::new(),
        pending: Vec::new(),
        shaders: Vec::new(),
        resourcepacks: Vec::new(),
        icon_url: None,
        modpack: None,
        settings: InstanceSettings::default(),
    };
    instance.save()?;
    Ok(instance)
}

pub fn delete(id: &str) -> Result<()> {
    check_id(id)?;
    let dir = paths::instances_dir().join(id);
    if dir.is_dir() {
        std::fs::remove_dir_all(dir)?;
    }
    Ok(())
}

/// Removes a mod, shader or resource pack (by folder) and its file.
pub fn remove_content(id: &str, folder: &str, project_id: &str) -> Result<Instance> {
    let mut instance = get(id)?;
    let dir = instance.game_dir().join(folder);
    if let Some(list) = instance.list_mut(folder) {
        if let Some(pos) = list.iter().position(|m| m.project_id == project_id) {
            let removed = list.remove(pos);
            let _ = std::fs::remove_file(dir.join(removed.disk_name()));
        }
    }
    instance.pending.retain(|p| p.project_id != project_id);
    instance.save()?;
    Ok(instance)
}

/// Turns a mod on or off by renaming its file to/from "<file>.disabled".
pub fn set_mod_enabled(id: &str, project_id: &str, enabled: bool) -> Result<Instance> {
    let mut instance = get(id)?;
    let dir = instance.mods_dir();
    let m = instance
        .mods
        .iter_mut()
        .find(|m| m.project_id == project_id)
        .ok_or_else(|| anyhow!("мод не найден"))?;
    if m.disabled == !enabled {
        return Ok(instance);
    }
    let from = dir.join(m.disk_name());
    m.disabled = !enabled;
    let to = dir.join(m.disk_name());
    if from.is_file() {
        std::fs::rename(&from, &to)?;
    }
    instance.save()?;
    Ok(instance)
}

fn copy_dir(from: &std::path::Path, to: &std::path::Path) -> Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let dest = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &dest)?;
        } else {
            std::fs::copy(entry.path(), dest)?;
        }
    }
    Ok(())
}

/// Copies an instance with its worlds, mods and settings under a new id.
pub fn duplicate(id: &str) -> Result<Instance> {
    let source = get(id)?;
    let mut copy = source.clone();
    copy.id = uuid::Uuid::new_v4().to_string();
    copy.name = format!("{} (копия)", source.name);
    copy.created_at = now();
    copy.last_played = None;
    copy_dir(&source.game_dir(), &copy.game_dir())?;
    copy.save()?;
    Ok(copy)
}

/// Fields the settings dialog can change.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstancePatch {
    pub name: String,
    pub icon_url: Option<String>,
    pub memory_mb: u32,
    pub game_version: String,
    pub loader: Loader,
    pub settings: InstanceSettings,
}

pub fn apply_patch(id: &str, patch: InstancePatch) -> Result<Instance> {
    let mut inst = get(id)?;
    let name = patch.name.trim();
    if name.is_empty() {
        bail!("укажите название сборки");
    }
    if let Some(icon) = &patch.icon_url {
        if !(icon.starts_with("https://") || icon.starts_with("data:image/")) {
            bail!("недопустимая иконка");
        }
    }
    inst.name = name.to_string();
    inst.icon_url = patch.icon_url;
    inst.memory_mb = patch.memory_mb.clamp(512, 65536);
    inst.game_version = patch.game_version;
    inst.loader = patch.loader;
    inst.settings = patch.settings;
    inst.save()?;
    Ok(inst)
}
