use super::{report, Reporter};
use anyhow::{bail, Context, Result};
use futures::{StreamExt, TryStreamExt};
use reqwest::Client;
use serde::de::DeserializeOwned;
use sha1::{Digest, Sha1};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;
use tokio::io::AsyncWriteExt;

/// Modrinth asks every client to send a unique User-Agent.
const USER_AGENT: &str = concat!("blockyard-launcher/", env!("CARGO_PKG_VERSION"));
const PARALLEL_DOWNLOADS: usize = 16;

pub fn client() -> &'static Client {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        Client::builder()
            .user_agent(USER_AGENT)
            .build()
            .expect("failed to build HTTP client")
    })
}

pub async fn get_json<T: DeserializeOwned>(url: &str) -> Result<T> {
    let resp = client()
        .get(url)
        .send()
        .await
        .with_context(|| format!("request to {url} failed"))?
        .error_for_status()?;
    Ok(resp.json().await?)
}

/// A file to download, optionally checked against a SHA-1 hash.
#[derive(Debug, Clone)]
pub struct Download {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
}

fn sha1_of(path: &Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    Some(hex::encode(Sha1::digest(&bytes)))
}

/// True if the file exists and matches the expected hash (or no hash is known).
fn is_valid(path: &Path, sha1: Option<&str>) -> bool {
    if !path.is_file() {
        return false;
    }
    match sha1 {
        Some(expected) => sha1_of(path).is_some_and(|h| h.eq_ignore_ascii_case(expected)),
        None => true,
    }
}

/// Maven repositories that mirror each other's Java libraries. Old Forge versions point at
/// paths that only exist on one of them, so a failed library download is retried on the others.
const MAVEN_MIRRORS: &[&str] = &[
    "https://maven.minecraftforge.net/",
    "https://libraries.minecraft.net/",
    "https://repo1.maven.org/maven2/",
];

fn candidate_urls(url: &str) -> Vec<String> {
    let mut urls = vec![url.to_string()];
    if let Some(rest) = MAVEN_MIRRORS.iter().find_map(|m| url.strip_prefix(m)) {
        urls.extend(MAVEN_MIRRORS.iter().map(|m| format!("{m}{rest}")).filter(|u| u != url));
    }
    urls
}

async fn fetch(url: &str) -> Result<reqwest::Response> {
    Ok(client()
        .get(url)
        .send()
        .await
        .with_context(|| format!("download of {url} failed"))?
        .error_for_status()?)
}

pub async fn download(dl: &Download) -> Result<()> {
    if is_valid(&dl.path, dl.sha1.as_deref()) {
        return Ok(());
    }
    if let Some(parent) = dl.path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let mut last_error = None;
    let mut response = None;
    for url in candidate_urls(&dl.url) {
        match fetch(&url).await {
            Ok(r) => {
                response = Some(r);
                break;
            }
            Err(e) => last_error = Some(e),
        }
    }
    let Some(resp) = response else {
        return Err(last_error.unwrap_or_else(|| anyhow::anyhow!("download of {} failed", dl.url)));
    };

    // Write to a temp file first so an interrupted download never looks complete.
    let tmp = dl.path.with_extension("part");
    let mut file = tokio::fs::File::create(&tmp).await?;
    let mut hasher = Sha1::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
    }
    file.flush().await?;
    drop(file);

    if let Some(expected) = &dl.sha1 {
        let actual = hex::encode(hasher.finalize());
        if !actual.eq_ignore_ascii_case(expected) {
            let _ = tokio::fs::remove_file(&tmp).await;
            bail!("hash mismatch for {}", dl.url);
        }
    }
    tokio::fs::rename(&tmp, &dl.path).await?;
    Ok(())
}

/// Downloads many files in parallel, reporting progress after each one.
pub async fn download_all(downloads: Vec<Download>, stage: &str, reporter: &Reporter) -> Result<()> {
    let total = downloads.len() as u64;
    let done = AtomicU64::new(0);
    report(reporter, stage, 0, total);

    futures::stream::iter(downloads.into_iter().map(Ok::<_, anyhow::Error>))
        .try_for_each_concurrent(PARALLEL_DOWNLOADS, |dl| {
            let done = &done;
            async move {
                download(&dl).await?;
                let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                report(reporter, stage, n, total);
                Ok(())
            }
        })
        .await
}
