//! Local authentication for offline accounts.
//!
//! A world opened to LAN checks every joining player against Mojang's session servers, so
//! offline players are rejected with "Invalid session". For offline launches the game runs
//! with authlib-injector pointed at this tiny Yggdrasil-compatible server inside the launcher,
//! which accepts every join and vouches for every name. Both the host's and the guest's
//! launcher run their own copy, so no internet service is involved.

use super::http::{self, Download};
use super::launch::offline_uuid;
use super::paths;
use anyhow::{anyhow, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::OnceCell;

const ARTIFACT_URL: &str = "https://authlib-injector.yushi.moe/artifact/latest.json";
/// Used when the artifact API above is unreachable.
const FALLBACK_URL: &str =
    "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.5/authlib-injector-1.2.5.jar";

static SERVER_URL: OnceCell<String> = OnceCell::const_new();

/// Starts the local server once and returns its base URL.
pub async fn server_url() -> Result<String> {
    SERVER_URL
        .get_or_try_init(|| async {
            let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
            let url = format!("http://127.0.0.1:{}/", listener.local_addr()?.port());
            tokio::spawn(async move {
                while let Ok((stream, _)) = listener.accept().await {
                    tokio::spawn(async move {
                        let _ = handle(stream).await;
                    });
                }
            });
            Ok::<_, anyhow::Error>(url)
        })
        .await
        .cloned()
}

#[derive(Deserialize)]
struct Artifact {
    download_url: String,
    checksums: Checksums,
}

#[derive(Deserialize)]
struct Checksums {
    sha256: String,
}

/// Downloads authlib-injector once; returns the jar path.
pub async fn ensure_injector() -> Result<PathBuf> {
    let path = paths::data_dir().join("meta").join("authlib-injector.jar");
    if path.is_file() {
        return Ok(path);
    }
    let url = match http::get_json::<Artifact>(ARTIFACT_URL).await {
        Ok(a) => {
            http::download(&Download { url: a.download_url, path: path.clone(), sha1: None }).await?;
            let bytes = std::fs::read(&path)?;
            use sha2::Digest;
            if !hex::encode(sha2::Sha256::digest(&bytes)).eq_ignore_ascii_case(&a.checksums.sha256) {
                let _ = std::fs::remove_file(&path);
                return Err(anyhow!("authlib-injector скачался повреждённым"));
            }
            return Ok(path);
        }
        Err(_) => FALLBACK_URL,
    };
    http::download(&Download { url: url.into(), path: path.clone(), sha1: None }).await?;
    Ok(path)
}

/// JVM arguments that route the game's authentication to the local server.
pub async fn jvm_args() -> Result<Vec<String>> {
    let jar = ensure_injector().await?;
    let url = server_url().await?;
    Ok(vec![
        format!("-javaagent:{}={url}", jar.to_string_lossy()),
        // Do not show the auth server name on the title screen.
        "-Dauthlibinjector.noShowServerName".into(),
    ])
}

/// Routes one request; returns (status, JSON body).
fn route(method: &str, target: &str) -> (u16, Option<Value>) {
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let param = |name: &str| {
        query
            .split('&')
            .filter_map(|kv| kv.split_once('='))
            .find(|(k, _)| *k == name)
            .map(|(_, v)| urlencoding::decode(v).map(|s| s.into_owned()).unwrap_or_default())
    };
    let profile = |name: &str| json!({ "id": offline_uuid(name), "name": name, "properties": [] });

    match (method, path) {
        ("GET", "/") => (
            200,
            Some(json!({
                "meta": {
                    "serverName": "Blockyard",
                    "implementationName": "blockyard-local",
                    "implementationVersion": env!("CARGO_PKG_VERSION"),
                    "feature.non_email_login": true
                },
                "skinDomains": []
            })),
        ),
        // The client announces it is joining a server: always fine.
        ("POST", "/sessionserver/session/minecraft/join") => (204, None),
        // The server asks whether a player really joined: vouch for any name.
        ("GET", "/sessionserver/session/minecraft/hasJoined") => match param("username") {
            Some(name) if !name.is_empty() => (200, Some(profile(&name))),
            _ => (204, None),
        },
        ("POST", "/api/profiles/minecraft") => (200, Some(json!([]))),
        ("GET", p) if p.starts_with("/api/users/profiles/minecraft/") => {
            let name = &p["/api/users/profiles/minecraft/".len()..];
            (200, Some(json!({ "id": offline_uuid(name), "name": name })))
        }
        ("GET", "/minecraftservices/player/attributes") => (
            200,
            Some(json!({
                "privileges": {
                    "onlineChat": { "enabled": true },
                    "multiplayerServer": { "enabled": true },
                    "multiplayerRealms": { "enabled": false },
                    "telemetry": { "enabled": false }
                },
                "profanityFilterPreferences": { "profanityFilterOn": false }
            })),
        ),
        ("GET", "/minecraftservices/privacy/blocklist") => (200, Some(json!({ "blockedProfiles": [] }))),
        // No chat signing keys and no skins for offline players.
        _ => (404, None),
    }
}

async fn handle(stream: TcpStream) -> Result<()> {
    let mut reader = BufReader::new(stream);
    let mut request_line = String::new();
    reader.read_line(&mut request_line).await?;
    let mut parts = request_line.split_whitespace();
    let (method, target) = (parts.next().unwrap_or("").to_string(), parts.next().unwrap_or("/").to_string());

    // Skip headers, but read the body so the client is not cut off mid-request.
    let mut content_length = 0usize;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).await? == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            if k.trim().eq_ignore_ascii_case("content-length") {
                content_length = v.trim().parse().unwrap_or(0);
            }
        }
    }
    let mut body = vec![0u8; content_length.min(1 << 20)];
    reader.read_exact(&mut body).await?;

    let (status, json) = route(&method, &target);
    let reason = match status {
        200 => "OK",
        204 => "No Content",
        _ => "Not Found",
    };
    let body = json.map(|j| j.to_string()).unwrap_or_default();
    let response = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let mut stream = reader.into_inner();
    stream.write_all(response.as_bytes()).await?;
    stream.shutdown().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vouches_for_any_player() {
        let (status, body) = route("GET", "/sessionserver/session/minecraft/hasJoined?username=Steve&serverId=abc");
        assert_eq!(status, 200);
        let body = body.unwrap();
        assert_eq!(body["name"], "Steve");
        assert_eq!(body["id"], offline_uuid("Steve"));
        assert_eq!(route("POST", "/sessionserver/session/minecraft/join").0, 204);
        assert_eq!(route("POST", "/minecraftservices/player/certificates").0, 404);
    }

    #[tokio::test]
    async fn serves_http() {
        let url = server_url().await.unwrap();
        let meta: Value = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get(&url)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(meta["meta"]["serverName"], "Blockyard");
        let joined = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get(format!("{url}sessionserver/session/minecraft/hasJoined?username=Alex&serverId=1"))
            .send()
            .await
            .unwrap();
        assert_eq!(joined.status(), 200);
    }
}
