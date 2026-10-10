//! Automatic Java runtime download from Eclipse Adoptium (Temurin).

use super::http::{self, Download};
use super::{paths, report, Reporter};
use anyhow::{bail, Context, Result};
use std::io::Read;
use std::path::{Component, Path, PathBuf};

/// Temurin has no Java 16 JRE builds; Minecraft 1.17 runs fine on 17.
fn normalize_major(major: u32) -> u32 {
    match major {
        0..=8 => 8,
        16 => 17,
        m => m,
    }
}

fn java_binary(home: &Path) -> PathBuf {
    if cfg!(target_os = "windows") {
        home.join("bin").join("java.exe")
    } else {
        home.join("bin").join("java")
    }
}

/// Returns the java executable for the major version, downloading it if necessary.
pub async fn ensure_java(major: u32, reporter: &Reporter) -> Result<PathBuf> {
    let major = normalize_major(major);
    let home = paths::runtimes_dir().join(format!("java-{major}"));
    let bin = java_binary(&home);
    if bin.is_file() {
        return Ok(bin);
    }

    let os = match paths::os_name() {
        "osx" => "mac",
        other => other,
    };
    let arch = match std::env::consts::ARCH {
        "aarch64" => "aarch64",
        "x86" => "x32",
        _ => "x64",
    };
    let url = format!("https://api.adoptium.net/v3/binary/latest/{major}/ga/{os}/{arch}/jre/hotspot/normal/eclipse");
    let is_zip = cfg!(target_os = "windows");
    let archive = paths::runtimes_dir().join(format!("java-{major}.{}", if is_zip { "zip" } else { "tar.gz" }));

    report(reporter, &format!("Скачивание Java {major}"), 0, 1);
    http::download(&Download { url, path: archive.clone(), sha1: None })
        .await
        .with_context(|| format!("не удалось скачать Java {major}"))?;

    report(reporter, &format!("Распаковка Java {major}"), 0, 1);
    let home_clone = home.clone();
    let archive_clone = archive.clone();
    tokio::task::spawn_blocking(move || extract_stripping_root(&archive_clone, &home_clone, is_zip)).await??;
    let _ = std::fs::remove_file(&archive);

    if !bin.is_file() {
        bail!("Java {major} распакована, но {} не найден", bin.display());
    }
    Ok(bin)
}

/// Archives contain a single top-level folder (jdk-21.0.4+7-jre); drop it while extracting.
fn strip_root(path: &Path) -> Option<PathBuf> {
    let mut components = path.components();
    components.next()?;
    let rest: PathBuf = components.collect();
    let safe = rest.components().all(|c| matches!(c, Component::Normal(_)));
    (safe && !rest.as_os_str().is_empty()).then_some(rest)
}

fn extract_stripping_root(archive: &Path, dest: &Path, is_zip: bool) -> Result<()> {
    if dest.exists() {
        std::fs::remove_dir_all(dest)?;
    }
    std::fs::create_dir_all(dest)?;
    let file = std::fs::File::open(archive)?;

    if is_zip {
        let mut zip = zip::ZipArchive::new(file)?;
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i)?;
            let Some(name) = entry.enclosed_name() else { continue };
            let Some(rel) = strip_root(&name) else { continue };
            let out = dest.join(rel);
            if entry.is_dir() {
                std::fs::create_dir_all(&out)?;
                continue;
            }
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut buf = Vec::new();
            entry.read_to_end(&mut buf)?;
            std::fs::write(&out, buf)?;
        }
    } else {
        let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(file));
        for entry in tar.entries()? {
            let mut entry = entry?;
            let path = entry.path()?.into_owned();
            let Some(rel) = strip_root(&path) else { continue };
            let out = dest.join(rel);
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            entry.unpack(&out)?;
        }
    }
    Ok(())
}
