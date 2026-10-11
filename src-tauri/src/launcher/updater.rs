//! Self-update: compares the running version with the latest GitHub release and runs
//! the new installer. Needs the repository to be public, otherwise the release API
//! returns 404 and no update is offered.

use super::http::{self, Download};
use super::paths;
use anyhow::{anyhow, bail, Result};
use serde::{Deserialize, Serialize};

const RELEASES_URL: &str = "https://api.github.com/repos/kirillshimarov-commits/blockyard/releases/latest";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LauncherUpdate {
    pub version: String,
    pub notes: String,
    pub url: String,
}

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    body: Option<String>,
    assets: Vec<Asset>,
}

#[derive(Deserialize)]
struct Asset {
    name: String,
    browser_download_url: String,
}

/// "v0.2.10" -> (0, 2, 10); missing parts count as 0.
fn parse_version(v: &str) -> (u64, u64, u64) {
    let mut parts = v.trim_start_matches('v').split(['.', '-']).map(|p| p.parse::<u64>().unwrap_or(0));
    (parts.next().unwrap_or(0), parts.next().unwrap_or(0), parts.next().unwrap_or(0))
}

pub fn current_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

pub async fn check() -> Result<Option<LauncherUpdate>> {
    let resp = http::client()
        .get(RELEASES_URL)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None); // No releases yet, or the repository is private.
    }
    let release: Release = resp.error_for_status()?.json().await?;
    if parse_version(&release.tag_name) <= parse_version(current_version()) {
        return Ok(None);
    }
    let asset = release
        .assets
        .iter()
        .find(|a| a.name.ends_with("-setup.exe"))
        .ok_or_else(|| anyhow!("в релизе {} нет установщика", release.tag_name))?;
    Ok(Some(LauncherUpdate {
        version: release.tag_name.trim_start_matches('v').to_string(),
        notes: release.body.unwrap_or_default(),
        url: asset.browser_download_url.clone(),
    }))
}

/// Downloads the installer and starts it; the caller then exits the launcher.
pub async fn install(update_url: &str) -> Result<()> {
    if !cfg!(target_os = "windows") {
        bail!("Автообновление пока есть только для Windows");
    }
    if !update_url.starts_with("https://github.com/kirillshimarov-commits/blockyard/releases/download/") {
        bail!("недопустимый адрес обновления");
    }
    let installer = paths::data_dir().join("updates").join("Blockyard-setup.exe");
    let _ = std::fs::remove_file(&installer);
    http::download(&Download { url: update_url.to_string(), path: installer.clone(), sha1: None }).await?;
    // /P: passive install with a progress bar, /R: start the launcher again afterwards.
    std::process::Command::new(&installer).args(["/P", "/R"]).spawn()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_versions() {
        assert!(parse_version("v0.2.10") > parse_version("0.2.9"));
        assert!(parse_version("v1.0.0") > parse_version("0.9.9"));
        assert_eq!(parse_version("v0.2.0"), parse_version("0.2.0"));
    }
}
