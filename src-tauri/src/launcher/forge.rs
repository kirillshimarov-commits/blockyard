//! Forge and NeoForge.
//!
//! Both ship an installer jar containing an install profile, a version JSON and a list of
//! "processors" (small Java tools that patch the game jar). Instead of running the installer
//! with its GUI, we replay its client-side steps: download libraries, extract bundled files,
//! run the processors, then use the version JSON like any other loader profile.
//! Pre-1.13 Forge has no processors; its installer just carries the profile and the jar.

use super::fabric::LoaderVersion;
use super::http::{self, Download};
use super::mojang::{self, Library, VersionJson};
use super::{java, paths, report, Reporter};
use anyhow::{anyhow, bail, Context, Result};
use serde_json::Value;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Forge,
    NeoForge,
}

impl Kind {
    fn maven(self) -> &'static str {
        match self {
            Kind::Forge => "https://maven.minecraftforge.net/net/minecraftforge/forge",
            Kind::NeoForge => "https://maven.neoforged.net/releases/net/neoforged/neoforge",
        }
    }

    fn artifact(self) -> &'static str {
        match self {
            Kind::Forge => "forge",
            Kind::NeoForge => "neoforge",
        }
    }

    fn label(self) -> &'static str {
        match self {
            Kind::Forge => "Forge",
            Kind::NeoForge => "NeoForge",
        }
    }
}

// ---------- Version lists ----------

async fn get_text(url: &str) -> Result<String> {
    Ok(http::client().get(url).send().await?.error_for_status()?.text().await?)
}

fn maven_versions(xml: &str) -> Vec<String> {
    xml.split("<version>")
        .skip(1)
        .filter_map(|s| s.split("</version>").next())
        .map(|s| s.trim().to_string())
        .collect()
}

/// NeoForge numbers versions after Minecraft: 21.1.x is for 1.21.1, 21.0.x for 1.21,
/// and from Minecraft 26.1 onwards the game version itself is the prefix.
fn neoforge_prefix(game_version: &str) -> String {
    match game_version.strip_prefix("1.") {
        Some(rest) => {
            let mut parts = rest.split('.');
            let major = parts.next().unwrap_or("0");
            let minor = parts.next().unwrap_or("0");
            format!("{major}.{minor}.")
        }
        None => format!("{game_version}."),
    }
}

pub async fn loader_versions(kind: Kind, game_version: &str) -> Result<Vec<LoaderVersion>> {
    let xml = get_text(&format!("{}/maven-metadata.xml", kind.maven())).await?;
    let all = maven_versions(&xml);

    let mut versions: Vec<LoaderVersion> = match kind {
        Kind::Forge => {
            let prefix = format!("{game_version}-");
            #[derive(serde::Deserialize)]
            struct Promotions {
                promos: HashMap<String, String>,
            }
            let promos = http::get_json::<Promotions>(
                "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json",
            )
            .await
            .map(|p| p.promos)
            .unwrap_or_default();
            let recommended = promos
                .get(&format!("{game_version}-recommended"))
                .or_else(|| promos.get(&format!("{game_version}-latest")))
                .map(|short| format!("{game_version}-{short}"));
            all.into_iter()
                .filter(|v| v.starts_with(&prefix))
                .map(|v| LoaderVersion { recommended: recommended.as_deref() == Some(v.as_str()), stable: true, version: v })
                .collect()
        }
        Kind::NeoForge => {
            let prefix = neoforge_prefix(game_version);
            all.into_iter()
                .filter(|v| v.starts_with(&prefix))
                .map(|v| LoaderVersion { stable: !v.contains("beta") && !v.contains("alpha"), recommended: false, version: v })
                .collect()
        }
    };
    // Maven metadata lists oldest first.
    versions.reverse();
    if kind == Kind::NeoForge {
        if let Some(first_stable) = versions.iter_mut().find(|v| v.stable) {
            first_stable.recommended = true;
        }
    }
    Ok(versions)
}

// ---------- Installer helpers ----------

fn read_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Result<Option<Vec<u8>>> {
    let mut entry = match zip.by_name(name) {
        Ok(e) => e,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf)?;
    Ok(Some(buf))
}

fn extract_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str, dest: &Path) -> Result<()> {
    let bytes = read_entry(zip, name)?.ok_or_else(|| anyhow!("в установщике нет файла {name}"))?;
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(dest, bytes)?;
    Ok(())
}

/// Copies the installer's bundled maven/ folder (the loader's own jars) into the libraries dir.
fn extract_bundled_maven(zip: &mut zip::ZipArchive<std::fs::File>, libs_dir: &Path) -> Result<()> {
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let Some(name) = entry.enclosed_name() else { continue };
        let Ok(rel) = name.strip_prefix("maven") else { continue };
        if entry.is_dir() || rel.as_os_str().is_empty() {
            continue;
        }
        let dest = libs_dir.join(rel);
        if dest.is_file() {
            continue;
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf)?;
        std::fs::write(dest, buf)?;
    }
    Ok(())
}

fn library_path(coord: &str) -> Result<PathBuf> {
    let rel = mojang::maven_path(coord).ok_or_else(|| anyhow!("неверная библиотека {coord}"))?;
    Ok(paths::libraries_dir().join(rel))
}

/// Main-Class from a jar manifest (lines may be folded with a leading space).
fn main_class(jar: &Path) -> Result<String> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
    let bytes = read_entry(&mut zip, "META-INF/MANIFEST.MF")?.ok_or_else(|| anyhow!("нет MANIFEST.MF"))?;
    let text = String::from_utf8_lossy(&bytes).replace("\r\n", "\n").replace("\n ", "");
    text.lines()
        .find_map(|l| l.strip_prefix("Main-Class:"))
        .map(|s| s.trim().to_string())
        .ok_or_else(|| anyhow!("в {} не указан Main-Class", jar.display()))
}

fn installed_marker(id: &str) -> PathBuf {
    paths::versions_dir().join(id).join(".installed")
}

fn write_version(id: &str, json: &str) -> Result<PathBuf> {
    let path = paths::versions_dir().join(id).join(format!("{id}.json"));
    std::fs::create_dir_all(path.parent().unwrap())?;
    std::fs::write(&path, json)?;
    Ok(path)
}

// ---------- Install ----------

/// Installs (if needed) and returns the merged version for this loader.
pub async fn load(kind: Kind, game_version: &str, loader_version: &str, reporter: &Reporter) -> Result<VersionJson> {
    let label = kind.label();
    let installer = paths::data_dir()
        .join("meta")
        .join("installers")
        .join(format!("{}-{loader_version}-installer.jar", kind.artifact()));
    if !installer.is_file() {
        report(reporter, &format!("Скачивание {label}"), 0, 1);
        let url = format!(
            "{}/{loader_version}/{}-{loader_version}-installer.jar",
            kind.maven(),
            kind.artifact()
        );
        http::download(&Download { url, path: installer.clone(), sha1: None })
            .await
            .with_context(|| format!("не удалось скачать установщик {label} {loader_version}"))?;
    }

    let mut zip = zip::ZipArchive::new(std::fs::File::open(&installer)?)
        .with_context(|| format!("повреждён установщик {label}; удалите {}", installer.display()))?;
    let profile: Value = serde_json::from_slice(
        &read_entry(&mut zip, "install_profile.json")?.ok_or_else(|| anyhow!("в установщике нет install_profile.json"))?,
    )?;

    let vanilla = mojang::load_vanilla(game_version).await?;

    // Legacy (pre-1.13) installers embed the version JSON and the universal jar.
    if let Some(version_info) = profile.get("versionInfo") {
        let id = version_info["id"].as_str().ok_or_else(|| anyhow!("нет id версии"))?.to_string();
        let path = paths::versions_dir().join(&id).join(format!("{id}.json"));
        if !installed_marker(&id).is_file() {
            report(reporter, &format!("Установка {label}"), 0, 1);
            let install = &profile["install"];
            if let (Some(file), Some(coord)) = (install["filePath"].as_str(), install["path"].as_str()) {
                extract_entry(&mut zip, file, &library_path(coord)?)?;
            }
            let mut info = version_info.clone();
            if info.get("inheritsFrom").is_none() {
                info["inheritsFrom"] = Value::String(game_version.to_string());
            }
            let json = serde_json::to_string(&info)?
                .replace("http://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/")
                .replace("https://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/");
            write_version(&id, &json)?;
            std::fs::write(installed_marker(&id), "")?;
        }
        return Ok(mojang::merge(vanilla, mojang::read_version_json(&path)?));
    }

    // Modern installers.
    let version_text = String::from_utf8(
        read_entry(&mut zip, "version.json")?.ok_or_else(|| anyhow!("в установщике нет version.json"))?,
    )?;
    let version_value: Value = serde_json::from_str(&version_text)?;
    let id = version_value["id"].as_str().ok_or_else(|| anyhow!("нет id версии"))?.to_string();
    let version_path = paths::versions_dir().join(&id).join(format!("{id}.json"));
    if installed_marker(&id).is_file() && version_path.is_file() {
        return Ok(mojang::merge(vanilla, mojang::read_version_json(&version_path)?));
    }

    // The processors patch the vanilla jar, so it has to be present first.
    let client_jar = mojang::client_jar_path(&vanilla.client_jar_id);
    if let Some(dl) = &vanilla.downloads {
        report(reporter, "Скачивание игры", 0, 1);
        http::download(&Download { url: dl.client.url.clone(), path: client_jar.clone(), sha1: dl.client.sha1.clone() })
            .await?;
    }

    let libs_dir = paths::libraries_dir();
    extract_bundled_maven(&mut zip, &libs_dir)?;

    let mut libraries: Vec<Library> = serde_json::from_value(profile["libraries"].clone()).unwrap_or_default();
    libraries.extend(serde_json::from_value::<Vec<Library>>(version_value["libraries"].clone()).unwrap_or_default());
    let downloads = mojang::resolve_library_list(&libraries)
        .into_iter()
        .filter_map(|l| l.download)
        .collect();
    http::download_all(downloads, &format!("Библиотеки {label}"), reporter).await?;

    // Data values referenced by processor arguments.
    let work_dir = installer.with_extension("work");
    let mut data: HashMap<String, String> = HashMap::from([
        ("SIDE".into(), "client".into()),
        ("MINECRAFT_JAR".into(), client_jar.to_string_lossy().into_owned()),
        ("MINECRAFT_VERSION".into(), game_version.to_string()),
        ("ROOT".into(), paths::data_dir().join("meta").to_string_lossy().into_owned()),
        ("INSTALLER".into(), installer.to_string_lossy().into_owned()),
        ("LIBRARY_DIR".into(), libs_dir.to_string_lossy().into_owned()),
    ]);
    if let Some(entries) = profile["data"].as_object() {
        for (key, sides) in entries {
            let Some(value) = sides["client"].as_str() else { continue };
            let resolved = if let Some(coord) = value.strip_prefix('[').and_then(|v| v.strip_suffix(']')) {
                library_path(coord)?.to_string_lossy().into_owned()
            } else if let Some(literal) = value.strip_prefix('\'').and_then(|v| v.strip_suffix('\'')) {
                literal.to_string()
            } else if let Some(inner) = value.strip_prefix('/') {
                let dest = work_dir.join(inner);
                extract_entry(&mut zip, inner, &dest)?;
                dest.to_string_lossy().into_owned()
            } else {
                value.to_string()
            };
            data.insert(key.clone(), resolved);
        }
    }

    let resolve_arg = |arg: &str| -> Result<String> {
        if let Some(coord) = arg.strip_prefix('[').and_then(|v| v.strip_suffix(']')) {
            return Ok(library_path(coord)?.to_string_lossy().into_owned());
        }
        let mut out = arg.to_string();
        for (key, value) in &data {
            out = out.replace(&format!("{{{key}}}"), value);
        }
        Ok(out)
    };

    let processors: Vec<&Value> = profile["processors"]
        .as_array()
        .map(|list| {
            list.iter()
                .filter(|p| {
                    p["sides"].as_array().is_none_or(|sides| sides.iter().any(|s| s.as_str() == Some("client")))
                })
                .collect()
        })
        .unwrap_or_default();

    let major = vanilla.java_version.as_ref().map_or(8, |j| j.major_version);
    let java = java::ensure_java(major, reporter).await?;
    let total = processors.len() as u64;
    for (i, proc) in processors.iter().enumerate() {
        report(reporter, &format!("Установка {label}"), i as u64, total);
        let jar = library_path(proc["jar"].as_str().ok_or_else(|| anyhow!("процессор без jar"))?)?;
        let mut classpath = vec![jar.to_string_lossy().into_owned()];
        for coord in proc["classpath"].as_array().into_iter().flatten().filter_map(Value::as_str) {
            classpath.push(library_path(coord)?.to_string_lossy().into_owned());
        }
        let args = proc["args"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .map(resolve_arg)
            .collect::<Result<Vec<_>>>()?;
        let main = main_class(&jar)?;
        let java = java.clone();
        let cp = classpath.join(paths::classpath_separator());
        tokio::task::spawn_blocking(move || run_processor(&java, &cp, &main, &args)).await??;
    }
    report(reporter, &format!("Установка {label}"), total, total);

    write_version(&id, &version_text)?;
    std::fs::write(installed_marker(&id), "")?;
    let _ = std::fs::remove_dir_all(&work_dir);
    Ok(mojang::merge(vanilla, mojang::read_version_json(&version_path)?))
}

fn run_processor(java: &Path, classpath: &str, main: &str, args: &[String]) -> Result<()> {
    let mut cmd = std::process::Command::new(java);
    cmd.arg("-cp").arg(classpath).arg(main).args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let output = cmd.output().with_context(|| format!("не удалось запустить {main}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        let tail: Vec<&str> = stderr.lines().chain(stdout.lines()).rev().take(8).collect();
        bail!(
            "шаг установки {main} завершился с ошибкой:\n{}",
            tail.into_iter().rev().collect::<Vec<_>>().join("\n")
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn neoforge_prefixes() {
        assert_eq!(neoforge_prefix("1.21.1"), "21.1.");
        assert_eq!(neoforge_prefix("1.21"), "21.0.");
        assert_eq!(neoforge_prefix("1.20.4"), "20.4.");
        assert_eq!(neoforge_prefix("26.1"), "26.1.");
    }

    #[test]
    fn parses_maven_metadata() {
        let xml = "<metadata><versioning><versions><version>1.20.1-47.2.0</version>\n<version>1.20.1-47.3.0</version></versions></versioning></metadata>";
        assert_eq!(maven_versions(xml), vec!["1.20.1-47.2.0", "1.20.1-47.3.0"]);
    }
}
