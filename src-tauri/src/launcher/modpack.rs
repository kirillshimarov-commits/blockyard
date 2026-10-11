//! Installing ready-made modpacks from Modrinth (.mrpack) and CurseForge (manifest.json zip).
//!
//! An instance is created right away from the pack's metadata so the UI can show it;
//! `finish` then downloads everything and fills in the loader and mod list.

use super::curseforge::{self, Envelope};
use super::fabric::{self, Flavor};
use super::forge::{self, Kind};
use super::http::{self, Download};
use super::instance::{self, InstalledMod, Instance, Loader, ModpackRef};
use super::mods::{PendingDownload, Source};
use super::{modrinth, report, Reporter};
use anyhow::{anyhow, bail, Context, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

/// What `prepare` found out; passed to `finish`.
pub struct Plan {
    source: Source,
    project_id: String,
    version_id: String,
    title: String,
    url: String,
    sha1: Option<String>,
}

/// Looks up the newest version of the pack and creates a placeholder instance for it.
pub async fn prepare(source: Source, project_id: &str, memory_mb: u32) -> Result<(Instance, Plan)> {
    let (plan, game_version, icon) = match source {
        Source::Modrinth => {
            let project = modrinth::project(project_id).await?;
            let version = modrinth::latest_version(&project.id, None, None)
                .await?
                .ok_or_else(|| anyhow!("У модпака нет версий"))?;
            let file = version
                .files
                .iter()
                .find(|f| f.primary)
                .or_else(|| version.files.first())
                .ok_or_else(|| anyhow!("У модпака нет файлов"))?;
            let game = version.game_versions.first().cloned().unwrap_or_default();
            let plan = Plan {
                source,
                project_id: project.id.clone(),
                version_id: version.id.clone(),
                title: project.title.clone(),
                url: file.url.clone(),
                sha1: file.hashes.sha1.clone(),
            };
            (plan, game, project.icon_url)
        }
        Source::Curseforge => {
            let mod_id: u64 = project_id.parse().context("неверный id CurseForge")?;
            let info: curseforge::Mod = curseforge::get::<Envelope<curseforge::Mod>>(&format!("/mods/{mod_id}")).await?.data;
            let file = curseforge::latest_compatible(mod_id, None, None)
                .await?
                .ok_or_else(|| anyhow!("У модпака нет файлов"))?;
            let url = file.download_url.clone().ok_or_else(|| {
                anyhow!("Автор модпака запретил скачивание через лаунчеры. Скачайте его на сайте CurseForge.")
            })?;
            let game = file
                .game_versions
                .iter()
                .find(|v| v.chars().next().is_some_and(|c| c.is_ascii_digit()))
                .cloned()
                .unwrap_or_default();
            let plan = Plan {
                source,
                project_id: mod_id.to_string(),
                version_id: file.id.to_string(),
                title: info.name.clone(),
                url,
                sha1: file.hashes.iter().find(|h| h.algo == curseforge::HASH_SHA1).map(|h| h.value.clone()),
            };
            (plan, game, info.icon())
        }
    };

    let mut inst = instance::create(&plan.title, &game_version, Loader::Vanilla, memory_mb)?;
    inst.icon_url = icon;
    inst.modpack = Some(ModpackRef {
        source,
        project_id: plan.project_id.clone(),
        version_id: plan.version_id.clone(),
        title: plan.title.clone(),
    });
    inst.save()?;
    Ok((inst, plan))
}

/// Downloads the pack and everything in it into the instance.
pub async fn finish(instance_id: &str, plan: Plan, reporter: &Reporter) -> Result<Instance> {
    let mut inst = instance::get(instance_id)?;
    report(reporter, "Скачивание модпака", 0, 1);
    let archive = inst.dir().join(match plan.source {
        Source::Modrinth => "pack.mrpack",
        Source::Curseforge => "pack.zip",
    });
    http::download(&Download { url: plan.url.clone(), path: archive.clone(), sha1: plan.sha1.clone() }).await?;

    match plan.source {
        Source::Modrinth => install_mrpack(&mut inst, &archive, reporter).await?,
        Source::Curseforge => install_curseforge(&mut inst, &archive, reporter).await?,
    }
    let _ = std::fs::remove_file(&archive);
    inst.save()?;
    Ok(inst)
}

// ---------- Shared helpers ----------

/// A relative path from a pack that stays inside the game folder.
fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = Path::new(path);
    (!path.is_empty() && p.components().all(|c| matches!(c, Component::Normal(_)))).then(|| p.to_path_buf())
}

/// Copies `<prefix>/...` entries of the archive into the game folder.
fn extract_overrides(archive: &Path, prefix: &str, game_dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(name) = entry.enclosed_name() else { continue };
        let Ok(rel) = name.strip_prefix(prefix) else { continue };
        if entry.is_dir() || rel.as_os_str().is_empty() {
            continue;
        }
        let dest = game_dir.join(rel);
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf)?;
        std::fs::write(dest, buf)?;
    }
    Ok(())
}

fn read_json(archive: &Path, name: &str) -> Result<Value> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?).context("архив модпака повреждён")?;
    let mut entry = zip.by_name(name).with_context(|| format!("в модпаке нет {name}"))?;
    let mut text = String::new();
    entry.read_to_string(&mut text)?;
    Ok(serde_json::from_str(&text)?)
}

/// Turns "forge 47.2.0" for Minecraft 1.20.1 into the maven version "1.20.1-47.2.0".
async fn forge_version(kind: Kind, game: &str, short: &str) -> Result<String> {
    let versions = forge::loader_versions(kind, game).await?;
    let candidates = [short.to_string(), format!("{game}-{short}")];
    versions
        .into_iter()
        .map(|v| v.version)
        .find(|v| candidates.iter().any(|c| v == c || v.starts_with(&format!("{c}-"))))
        .ok_or_else(|| anyhow!("не найдена версия {} {short} для Minecraft {game}", if kind == Kind::Forge { "Forge" } else { "NeoForge" }))
}

fn file_name_of(path: &str) -> String {
    Path::new(path).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

// ---------- Modrinth ----------

#[derive(Deserialize)]
struct MrIndex {
    files: Vec<MrFile>,
    dependencies: HashMap<String, String>,
}

#[derive(Deserialize)]
struct MrFile {
    path: String,
    hashes: HashMap<String, String>,
    #[serde(default)]
    env: Option<HashMap<String, String>>,
    downloads: Vec<String>,
}

#[derive(Deserialize)]
struct MrVersionRef {
    id: String,
    project_id: String,
}

#[derive(Deserialize)]
struct MrProject {
    id: String,
    title: String,
    #[serde(default)]
    icon_url: Option<String>,
}

async fn install_mrpack(inst: &mut Instance, archive: &Path, reporter: &Reporter) -> Result<()> {
    let index: MrIndex = serde_json::from_value(read_json(archive, "modrinth.index.json")?)?;
    let deps = &index.dependencies;
    let game = deps.get("minecraft").ok_or_else(|| anyhow!("в модпаке не указана версия Minecraft"))?.clone();
    inst.game_version = game.clone();
    inst.loader = if let Some(v) = deps.get("fabric-loader") {
        Loader::Fabric { version: v.clone() }
    } else if let Some(v) = deps.get("quilt-loader") {
        Loader::Quilt { version: v.clone() }
    } else if let Some(v) = deps.get("neoforge") {
        Loader::NeoForge { version: forge_version(Kind::NeoForge, &game, v).await? }
    } else if let Some(v) = deps.get("forge") {
        Loader::Forge { version: forge_version(Kind::Forge, &game, v).await? }
    } else {
        Loader::Vanilla
    };
    inst.save()?;

    let game_dir = inst.game_dir();
    let mut downloads = Vec::new();
    let mut by_hash: HashMap<String, String> = HashMap::new(); // sha1 -> relative path
    for f in &index.files {
        if f.env.as_ref().and_then(|e| e.get("client")).is_some_and(|c| c == "unsupported") {
            continue;
        }
        let Some(rel) = safe_relative(&f.path) else { bail!("недопустимый путь в модпаке: {}", f.path) };
        let Some(url) = f.downloads.first() else { continue };
        let sha1 = f.hashes.get("sha1").cloned();
        if let Some(h) = &sha1 {
            by_hash.insert(h.clone(), f.path.clone());
        }
        downloads.push(Download { url: url.clone(), path: game_dir.join(rel), sha1 });
    }
    http::download_all(downloads, "Скачивание модов", reporter).await?;

    report(reporter, "Распаковка настроек", 0, 1);
    let (a, g) = (archive.to_path_buf(), game_dir.clone());
    tokio::task::spawn_blocking(move || {
        extract_overrides(&a, "overrides", &g)?;
        extract_overrides(&a, "client-overrides", &g)
    })
    .await??;

    // Look up project names so the mod list shows titles instead of file names.
    report(reporter, "Получение списка модов", 0, 1);
    let hashes: Vec<&String> = by_hash.keys().collect();
    // Names are cosmetic: if the lookup fails, file names are shown instead.
    let versions: HashMap<String, MrVersionRef> = async {
        let resp = http::client()
            .post("https://api.modrinth.com/v2/version_files")
            .json(&json!({ "hashes": hashes, "algorithm": "sha1" }))
            .send()
            .await
            .ok()?
            .error_for_status()
            .ok()?;
        resp.json().await.ok()
    }
    .await
    .unwrap_or_default();
    let ids: Vec<&String> = versions.values().map(|v| &v.project_id).collect();
    let projects: HashMap<String, MrProject> = if ids.is_empty() {
        HashMap::new()
    } else {
        let url = format!(
            "https://api.modrinth.com/v2/projects?ids={}",
            urlencoding::encode(&serde_json::to_string(&ids)?)
        );
        http::get_json::<Vec<MrProject>>(&url)
            .await
            .unwrap_or_default()
            .into_iter()
            .map(|p| (p.id.clone(), p))
            .collect()
    };

    for (hash, path) in &by_hash {
        let version = versions.get(hash);
        let project = version.and_then(|v| projects.get(&v.project_id));
        let file_name = file_name_of(path);
        let entry = InstalledMod {
            source: Source::Modrinth,
            project_id: version.map(|v| v.project_id.clone()).unwrap_or_else(|| file_name.clone()),
            version_id: version.map(|v| v.id.clone()).unwrap_or_default(),
            title: project.map(|p| p.title.clone()).unwrap_or_else(|| file_name.trim_end_matches(".jar").to_string()),
            file_name,
            icon_url: project.and_then(|p| p.icon_url.clone()),
            disabled: false,
        };
        let folder = path.split('/').next().unwrap_or_default();
        if let Some(list) = inst.list_mut(folder) {
            list.push(entry);
        }
    }
    inst.mods.sort_by_key(|m| m.title.to_lowercase());
    Ok(())
}

// ---------- CurseForge ----------

#[derive(Deserialize)]
struct CfManifest {
    minecraft: CfMinecraft,
    files: Vec<CfFileRef>,
    #[serde(default)]
    overrides: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfMinecraft {
    version: String,
    #[serde(default)]
    mod_loaders: Vec<CfLoader>,
}

#[derive(Deserialize)]
struct CfLoader {
    id: String,
    #[serde(default)]
    primary: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfFileRef {
    #[serde(rename = "projectID")]
    project_id: u64,
    #[serde(rename = "fileID")]
    file_id: u64,
    #[serde(default = "yes")]
    required: bool,
}

fn yes() -> bool {
    true
}

async fn install_curseforge(inst: &mut Instance, archive: &Path, reporter: &Reporter) -> Result<()> {
    let manifest: CfManifest = serde_json::from_value(read_json(archive, "manifest.json")?)?;
    let game = manifest.minecraft.version.clone();
    inst.game_version = game.clone();
    let loader_id = manifest
        .minecraft
        .mod_loaders
        .iter()
        .find(|l| l.primary)
        .or_else(|| manifest.minecraft.mod_loaders.first())
        .map(|l| l.id.clone())
        .unwrap_or_default();
    let (name, version) = loader_id.split_once('-').unwrap_or((&loader_id, ""));
    inst.loader = match name {
        "forge" => Loader::Forge { version: forge_version(Kind::Forge, &game, version).await? },
        "neoforge" => Loader::NeoForge { version: forge_version(Kind::NeoForge, &game, version).await? },
        "fabric" => Loader::Fabric { version: version.to_string() },
        "quilt" => Loader::Quilt { version: version.to_string() },
        "" => Loader::Vanilla,
        other => bail!("Загрузчик {other} пока не поддерживается"),
    };
    // Make sure Fabric/Quilt versions resolve (and surface errors before the big download).
    if let Loader::Fabric { version } | Loader::Quilt { version } = &inst.loader {
        let flavor = if matches!(inst.loader, Loader::Fabric { .. }) { Flavor::Fabric } else { Flavor::Quilt };
        fabric::load(flavor, &game, version, false).await?;
    }
    inst.save()?;

    let refs: Vec<&CfFileRef> = manifest.files.iter().filter(|f| f.required).collect();
    report(reporter, "Получение списка модов", 0, 1);
    let mut files: Vec<curseforge::File> = Vec::new();
    let mut mods: HashMap<u64, curseforge::Mod> = HashMap::new();
    for chunk in refs.chunks(200) {
        let ids: Vec<u64> = chunk.iter().map(|f| f.file_id).collect();
        files.extend(curseforge::post::<Envelope<Vec<curseforge::File>>>("/mods/files", &json!({ "fileIds": ids })).await?.data);
        let mod_ids: Vec<u64> = chunk.iter().map(|f| f.project_id).collect();
        for m in curseforge::post::<Envelope<Vec<curseforge::Mod>>>("/mods", &json!({ "modIds": mod_ids })).await?.data {
            mods.insert(m.id, m);
        }
    }

    let game_dir = inst.game_dir();
    let mut downloads = Vec::new();
    for file in &files {
        let Some(info) = mods.get(&file.mod_id) else { continue };
        if super::mods::check_file_name(&file.file_name).is_err() {
            continue;
        }
        let folder = match info.class_id {
            Some(curseforge::RESOURCE_PACKS_CLASS_ID) => "resourcepacks",
            Some(curseforge::SHADERS_CLASS_ID) => "shaderpacks",
            _ => "mods",
        };
        let sha1 = file.hashes.iter().find(|h| h.algo == curseforge::HASH_SHA1).map(|h| h.value.clone());
        let entry = InstalledMod {
            source: Source::Curseforge,
            project_id: info.id.to_string(),
            version_id: file.id.to_string(),
            title: info.name.clone(),
            file_name: file.file_name.clone(),
            icon_url: info.icon(),
            disabled: false,
        };
        match &file.download_url {
            Some(url) => {
                downloads.push(Download { url: url.clone(), path: game_dir.join(folder).join(&file.file_name), sha1 });
                if let Some(list) = inst.list_mut(folder) {
                    list.push(entry);
                }
            }
            None => inst.pending.push(PendingDownload {
                source: Source::Curseforge,
                project_id: info.id.to_string(),
                version_id: file.id.to_string(),
                title: info.name.clone(),
                file_name: file.file_name.clone(),
                sha1,
                page_url: format!("{}/download/{}", info.page_url(), file.id),
                icon_url: info.icon(),
                folder: folder.into(),
            }),
        }
    }
    http::download_all(downloads, "Скачивание модов", reporter).await?;

    report(reporter, "Распаковка настроек", 0, 1);
    let overrides = manifest.overrides.clone().unwrap_or_else(|| "overrides".into());
    let (a, g) = (archive.to_path_buf(), game_dir.clone());
    tokio::task::spawn_blocking(move || extract_overrides(&a, &overrides, &g)).await??;
    inst.mods.sort_by_key(|m| m.title.to_lowercase());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_escaping_paths() {
        assert!(safe_relative("mods/sodium.jar").is_some());
        assert!(safe_relative("../evil.jar").is_none());
        assert!(safe_relative("/etc/passwd").is_none());
        assert!(safe_relative("mods/../../x").is_none());
        assert!(safe_relative("").is_none());
    }
}

// ---------- Import from a file ----------

#[derive(Debug, Clone, Copy)]
pub enum ArchiveKind {
    Mrpack,
    Curseforge,
}

/// Reads the pack's name and format and creates a placeholder instance for it.
pub fn prepare_import(path: &Path, memory_mb: u32) -> Result<(Instance, ArchiveKind)> {
    let (kind, name) = if let Ok(index) = read_json(path, "modrinth.index.json") {
        (ArchiveKind::Mrpack, index["name"].as_str().unwrap_or("Импортированная сборка").to_string())
    } else if let Ok(manifest) = read_json(path, "manifest.json") {
        (ArchiveKind::Curseforge, manifest["name"].as_str().unwrap_or("Импортированная сборка").to_string())
    } else {
        bail!("Это не модпак: в архиве нет modrinth.index.json или manifest.json");
    };
    let inst = instance::create(&name, "", Loader::Vanilla, memory_mb)?;
    Ok((inst, kind))
}

pub async fn finish_import(instance_id: &str, path: &Path, kind: ArchiveKind, reporter: &Reporter) -> Result<Instance> {
    let mut inst = instance::get(instance_id)?;
    match kind {
        ArchiveKind::Mrpack => install_mrpack(&mut inst, path, reporter).await?,
        ArchiveKind::Curseforge => install_curseforge(&mut inst, path, reporter).await?,
    }
    inst.save()?;
    Ok(inst)
}

// ---------- Export to .mrpack ----------

/// Folders with settings worth sharing along with the mods.
const EXPORTED_CONFIG_DIRS: &[&str] = &["config", "defaultconfigs", "kubejs", "scripts"];

#[derive(Deserialize)]
struct MrVersionFull {
    files: Vec<MrVersionFile>,
}

#[derive(Deserialize)]
struct MrVersionFile {
    url: String,
    hashes: HashMap<String, String>,
}

/// Writes the instance as a Modrinth modpack. Files known to Modrinth are referenced by URL;
/// everything else (CurseForge mods, configs) is packed into overrides/.
pub async fn export_mrpack(instance_id: &str, dest: &Path) -> Result<()> {
    use sha1::{Digest, Sha1};
    use sha2::Sha512;

    let inst = instance::get(instance_id)?;
    let game_dir = inst.game_dir();

    let mut dependencies = serde_json::Map::new();
    dependencies.insert("minecraft".into(), json!(inst.game_version));
    match &inst.loader {
        Loader::Vanilla => {}
        Loader::Fabric { version } => drop(dependencies.insert("fabric-loader".into(), json!(version))),
        Loader::Quilt { version } => drop(dependencies.insert("quilt-loader".into(), json!(version))),
        Loader::NeoForge { version } => drop(dependencies.insert("neoforge".into(), json!(version))),
        Loader::Forge { version } => {
            let short = version.strip_prefix(&format!("{}-", inst.game_version)).unwrap_or(version);
            dependencies.insert("forge".into(), json!(short));
        }
    }

    // Every enabled mod, shader and resource pack with its hashes.
    struct Entry {
        rel: String,
        sha1: String,
        sha512: String,
        size: u64,
    }
    let mut entries = Vec::new();
    let lists = [("mods", &inst.mods), ("shaderpacks", &inst.shaders), ("resourcepacks", &inst.resourcepacks)];
    for (folder, list) in lists {
        for m in list.iter().filter(|m| !m.disabled) {
            let Ok(bytes) = std::fs::read(game_dir.join(folder).join(&m.file_name)) else { continue };
            entries.push(Entry {
                rel: format!("{folder}/{}", m.file_name),
                sha1: hex::encode(Sha1::digest(&bytes)),
                sha512: hex::encode(Sha512::digest(&bytes)),
                size: bytes.len() as u64,
            });
        }
    }

    // Ask Modrinth which of these files it hosts.
    let hashes: Vec<&String> = entries.iter().map(|e| &e.sha1).collect();
    let known: HashMap<String, MrVersionFull> = if hashes.is_empty() {
        HashMap::new()
    } else {
        http::client()
            .post("https://api.modrinth.com/v2/version_files")
            .json(&json!({ "hashes": hashes, "algorithm": "sha1" }))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?
    };

    let mut files = Vec::new();
    let mut overrides: Vec<(String, PathBuf)> = Vec::new();
    for e in &entries {
        let url = known.get(&e.sha1).and_then(|v| {
            v.files.iter().find(|f| f.hashes.get("sha1") == Some(&e.sha1)).map(|f| f.url.clone())
        });
        match url {
            Some(url) => files.push(json!({
                "path": e.rel,
                "hashes": { "sha1": e.sha1, "sha512": e.sha512 },
                "env": { "client": "required", "server": "required" },
                "downloads": [url],
                "fileSize": e.size,
            })),
            None => overrides.push((format!("overrides/{}", e.rel), game_dir.join(&e.rel))),
        }
    }
    for dir in EXPORTED_CONFIG_DIRS {
        collect_files(&game_dir.join(dir), &format!("overrides/{dir}"), &mut overrides);
    }

    let index = json!({
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": "1.0.0",
        "name": inst.name,
        "summary": "Сборка из Blockyard",
        "files": files,
        "dependencies": dependencies,
    });

    let dest = dest.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<()> {
        use std::io::Write;
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&dest)?);
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("modrinth.index.json", options)?;
        zip.write_all(serde_json::to_string_pretty(&index)?.as_bytes())?;
        for (name, path) in overrides {
            let Ok(bytes) = std::fs::read(&path) else { continue };
            zip.start_file(name.replace('\\', "/"), options)?;
            zip.write_all(&bytes)?;
        }
        zip.finish()?;
        Ok(())
    })
    .await??;
    Ok(())
}

/// Recursively lists files under `dir` as (archive path, disk path).
fn collect_files(dir: &Path, prefix: &str, out: &mut Vec<(String, PathBuf)>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = format!("{prefix}/{}", entry.file_name().to_string_lossy());
        if path.is_dir() {
            collect_files(&path, &name, out);
        } else {
            out.push((name, path));
        }
    }
}
