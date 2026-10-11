//! Playing with a friend: a peer-to-peer QUIC tunnel (iroh) between two launchers.
//!
//! The host opens their world to LAN; the launcher accepts tunnel connections and forwards
//! each stream to the LAN port. The guest's launcher listens on a local port and forwards
//! every Minecraft connection through the tunnel, so the game just connects to localhost.
//! Connections go direct when NAT allows, otherwise through iroh's relay servers;
//! either way they are end-to-end encrypted.

use super::instance::{Instance, Loader};
use anyhow::{anyhow, Context, Result};
use base64::Engine;
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

pub const ALPN: &[u8] = b"blockyard/mc-tunnel/1";
const INVITE_PREFIX: &str = "BY1-";

/// Everything the guest needs: how to reach the host and which game to start.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Invite {
    pub addr: EndpointAddr,
    pub world: String,
    pub game_version: String,
    pub loader: Loader,
}

/// The parts of an invite the UI shows before joining.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InviteInfo {
    pub world: String,
    pub game_version: String,
    pub loader: Loader,
}

impl Invite {
    pub fn encode(&self) -> Result<String> {
        let json = serde_json::to_vec(self)?;
        Ok(format!("{INVITE_PREFIX}{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json)))
    }

    pub fn decode(code: &str) -> Result<Invite> {
        let body: String = code.trim().chars().filter(|c| !c.is_whitespace()).collect();
        let body = body.strip_prefix(INVITE_PREFIX).ok_or_else(|| anyhow!("Это не код-приглашение Blockyard"))?;
        let json = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(body)
            .map_err(|_| anyhow!("Код-приглашение повреждён — скопируйте его целиком"))?;
        serde_json::from_slice(&json).map_err(|_| anyhow!("Код-приглашение повреждён или от другой версии лаунчера"))
    }

    pub fn info(&self) -> InviteInfo {
        InviteInfo { world: self.world.clone(), game_version: self.game_version.clone(), loader: self.loader.clone() }
    }
}

/// How a connection is currently routed.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkInfo {
    pub id: String,
    pub rtt_ms: Option<u64>,
    /// True when traffic goes through a relay server instead of directly.
    pub relayed: bool,
}

fn link_info(conn: &Connection) -> LinkInfo {
    let paths = conn.paths();
    let selected = paths.iter().find(|p| p.is_selected());
    let id = conn.remote_id().to_string();
    LinkInfo {
        id: id.chars().take(8).collect(),
        rtt_ms: selected.as_ref().map(|p| p.rtt().as_millis() as u64),
        relayed: selected.as_ref().is_some_and(|p| p.is_relay()),
    }
}

/// Copies both directions between a QUIC stream pair and a TCP connection.
async fn pump(mut send: SendStream, mut recv: RecvStream, tcp: TcpStream) {
    let _ = tcp.set_nodelay(true);
    let (mut tcp_read, mut tcp_write) = tcp.into_split();
    let to_tcp = async {
        let _ = tokio::io::copy(&mut recv, &mut tcp_write).await;
        let _ = tcp_write.shutdown().await;
    };
    let to_quic = async {
        let _ = tokio::io::copy(&mut tcp_read, &mut send).await;
        let _ = send.finish();
    };
    tokio::join!(to_tcp, to_quic);
}

async fn bind_endpoint(alpn: bool) -> Result<Endpoint> {
    let builder = Endpoint::builder(presets::N0);
    let builder = if alpn { builder.alpns(vec![ALPN.to_vec()]) } else { builder };
    builder.bind().await.map_err(|e| anyhow!("Не удалось запустить сетевой модуль: {e}"))
}

// ---------- Host ----------

pub struct Host {
    pub instance_id: String,
    pub invite: String,
    endpoint: Endpoint,
    peers: Arc<Mutex<HashMap<EndpointId, Connection>>>,
    task: JoinHandle<()>,
}

impl Host {
    /// Starts accepting friends and forwarding them to the world opened on `lan_port`.
    pub async fn start(instance: &Instance, world: &str, lan_port: u16) -> Result<Host> {
        let endpoint = bind_endpoint(true).await?;
        // Wait briefly for a relay connection so the invite contains a fallback route.
        let _ = tokio::time::timeout(Duration::from_secs(8), endpoint.online()).await;
        Self::with_endpoint(endpoint, instance, world, lan_port)
    }

    fn with_endpoint(endpoint: Endpoint, instance: &Instance, world: &str, lan_port: u16) -> Result<Host> {
        let invite = Invite {
            addr: endpoint.addr(),
            world: world.to_string(),
            game_version: instance.game_version.clone(),
            loader: instance.loader.clone(),
        }
        .encode()?;

        let peers: Arc<Mutex<HashMap<EndpointId, Connection>>> = Arc::default();
        let task = tokio::spawn({
            let endpoint = endpoint.clone();
            let peers = peers.clone();
            async move {
                while let Some(incoming) = endpoint.accept().await {
                    let peers = peers.clone();
                    tokio::spawn(async move {
                        let Ok(conn) = incoming.await else { return };
                        let id = conn.remote_id();
                        peers.lock().unwrap().insert(id, conn.clone());
                        // Each Minecraft TCP connection from the guest arrives as its own stream.
                        while let Ok((send, recv)) = conn.accept_bi().await {
                            tokio::spawn(async move {
                                if let Ok(tcp) = TcpStream::connect(("127.0.0.1", lan_port)).await {
                                    pump(send, recv, tcp).await;
                                }
                            });
                        }
                        peers.lock().unwrap().remove(&id);
                    });
                }
            }
        });
        Ok(Host { instance_id: instance.id.clone(), invite, endpoint, peers, task })
    }

    pub fn peers(&self) -> Vec<LinkInfo> {
        self.peers.lock().unwrap().values().map(link_info).collect()
    }

    pub async fn stop(self) {
        self.task.abort();
        self.endpoint.close().await;
    }
}

// ---------- Guest ----------

pub struct Guest {
    pub local_port: u16,
    pub info: InviteInfo,
    endpoint: Endpoint,
    conn: Connection,
    task: JoinHandle<()>,
}

impl Guest {
    /// Connects to the host and starts a local port that forwards into the tunnel.
    pub async fn connect(code: &str) -> Result<Guest> {
        let invite = Invite::decode(code)?;
        let endpoint = bind_endpoint(false).await?;
        Self::with_endpoint(endpoint, invite).await
    }

    async fn with_endpoint(endpoint: Endpoint, invite: Invite) -> Result<Guest> {
        let conn = tokio::time::timeout(Duration::from_secs(25), endpoint.connect(invite.addr.clone(), ALPN))
            .await
            .map_err(|_| anyhow!("Не удалось связаться с другом: он не отвечает. Проверьте, что у него открыт мир и запущено приглашение."))?
            .map_err(|e| anyhow!("Не удалось подключиться к другу: {e}"))?;

        let listener = TcpListener::bind(("127.0.0.1", 0)).await.context("не удалось открыть локальный порт")?;
        let local_port = listener.local_addr()?.port();
        let task = tokio::spawn({
            let conn = conn.clone();
            async move {
                while let Ok((tcp, _)) = listener.accept().await {
                    let conn = conn.clone();
                    tokio::spawn(async move {
                        if let Ok((send, recv)) = conn.open_bi().await {
                            pump(send, recv, tcp).await;
                        }
                    });
                }
            }
        });
        Ok(Guest { local_port, info: invite.info(), endpoint, conn, task })
    }

    pub fn link(&self) -> LinkInfo {
        link_info(&self.conn)
    }

    pub fn is_connected(&self) -> bool {
        self.conn.close_reason().is_none()
    }

    pub async fn stop(self) {
        self.task.abort();
        self.conn.close(0u32.into(), b"bye");
        self.endpoint.close().await;
    }
}

// ---------- Helpers ----------

/// Port of a world opened to LAN, from a game log line.
/// Modern versions log "Started serving on 51234"; older ones "Local game hosted on port 51234".
pub fn parse_lan_port(line: &str) -> Option<u16> {
    ["Started serving on ", "hosted on port "].iter().find_map(|marker| {
        let rest = &line[line.find(marker)? + marker.len()..];
        rest.chars().take_while(|c| c.is_ascii_digit()).collect::<String>().parse().ok()
    })
}

/// Game arguments that make Minecraft join the given local port right after starting.
pub fn join_args(game_version: &str, port: u16) -> Vec<String> {
    // Quick Play (1.20+, snapshots and the new year-based versions) replaced --server/--port.
    let quick_play = match game_version.strip_prefix("1.") {
        Some(rest) => rest.split('.').next().and_then(|m| m.parse::<u32>().ok()).is_none_or(|minor| minor >= 20),
        None => true,
    };
    if quick_play {
        vec!["--quickPlayMultiplayer".into(), format!("127.0.0.1:{port}")]
    } else {
        vec!["--server".into(), "127.0.0.1".into(), "--port".into(), port.to_string()]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    #[test]
    fn finds_lan_port() {
        assert_eq!(parse_lan_port("[Server thread/INFO]: Started serving on 53212"), Some(53212));
        assert_eq!(parse_lan_port("[CHAT] Local game hosted on port 40000"), Some(40000));
        assert_eq!(parse_lan_port("nothing here"), None);
    }

    #[test]
    fn picks_join_arguments() {
        assert_eq!(join_args("1.20.1", 5)[0], "--quickPlayMultiplayer");
        assert_eq!(join_args("26.1", 5)[0], "--quickPlayMultiplayer");
        assert_eq!(join_args("1.12.2", 5), vec!["--server", "127.0.0.1", "--port", "5"]);
    }

    fn test_instance() -> Instance {
        serde_json::from_value(serde_json::json!({
            "id": "t", "name": "Test", "gameVersion": "1.21.1",
            "loader": { "type": "fabric", "version": "0.16.0" }, "memoryMb": 1024, "createdAt": 0
        }))
        .unwrap()
    }

    /// Full round trip on this machine: a fake "world" echo server, a host and a guest.
    #[tokio::test]
    async fn tunnel_round_trip() {
        // Stand-in for the Minecraft LAN server: echoes everything back.
        let world = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let world_port = world.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = world.accept().await {
                tokio::spawn(async move {
                    let (mut r, mut w) = s.split();
                    let _ = tokio::io::copy(&mut r, &mut w).await;
                });
            }
        });

        // No relays or discovery in tests: connect over loopback.
        let host_ep = Endpoint::builder(presets::Minimal).alpns(vec![ALPN.to_vec()]).bind().await.unwrap();
        let host_addr = EndpointAddr::new(host_ep.id())
            .with_ip_addr(std::net::SocketAddr::from(([127, 0, 0, 1], host_ep.bound_sockets()[0].port())));
        let host = Host::with_endpoint(host_ep, &test_instance(), "Мир", world_port).unwrap();

        let mut invite = Invite::decode(&host.invite).unwrap();
        assert_eq!(invite.world, "Мир");
        invite.addr = host_addr;
        let guest_ep = Endpoint::builder(presets::Minimal).bind().await.unwrap();
        let guest = Guest::with_endpoint(guest_ep, invite).await.unwrap();

        let mut client = TcpStream::connect(("127.0.0.1", guest.local_port)).await.unwrap();
        client.write_all(b"hello minecraft").await.unwrap();
        let mut buf = [0u8; 15];
        tokio::time::timeout(Duration::from_secs(10), client.read_exact(&mut buf)).await.unwrap().unwrap();
        assert_eq!(&buf, b"hello minecraft");
        assert!(guest.is_connected());
        assert_eq!(host.peers().len(), 1);

        guest.stop().await;
        host.stop().await;
    }
}
