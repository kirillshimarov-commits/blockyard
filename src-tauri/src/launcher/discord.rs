//! Discord Rich Presence: "Playing Blockyard · Fabric 1.21.1" in the player's Discord profile.
//!
//! Talks to the Discord desktop app over its local IPC pipe. The application id is embedded at
//! build time from the DISCORD_CLIENT_ID environment variable (a GitHub Actions secret); without
//! it, or when Discord is not running, nothing happens.

use anyhow::{bail, Result};
use serde_json::json;
use std::io::{Read, Write};
use std::sync::mpsc::{self, Sender};
use std::sync::OnceLock;

fn client_id() -> Option<&'static str> {
    option_env!("DISCORD_CLIENT_ID").map(str::trim).filter(|id| !id.is_empty())
}

pub fn is_available() -> bool {
    client_id().is_some()
}

/// What the profile shows while the game runs.
#[derive(Debug, Clone)]
pub struct Activity {
    /// First line, e.g. the instance name.
    pub details: String,
    /// Second line, e.g. "Minecraft 1.21.1 · Fabric".
    pub state: String,
    /// Unix seconds when the game started, for the "elapsed" timer.
    pub start: u64,
}

/// Shows the activity, or clears it with `None`. Never blocks: a background thread talks to Discord.
pub fn set(activity: Option<Activity>) {
    if let Some(tx) = worker() {
        let _ = tx.send(activity);
    }
}

fn worker() -> Option<&'static Sender<Option<Activity>>> {
    static WORKER: OnceLock<Option<Sender<Option<Activity>>>> = OnceLock::new();
    WORKER
        .get_or_init(|| {
            let id = client_id()?;
            let (tx, rx) = mpsc::channel::<Option<Activity>>();
            std::thread::spawn(move || {
                let mut pipe: Option<Pipe> = None;
                while let Ok(mut next) = rx.recv() {
                    // Only the newest state matters.
                    while let Ok(newer) = rx.try_recv() {
                        next = newer;
                    }
                    // A second try covers Discord having restarted since the last update.
                    for _ in 0..2 {
                        if pipe.is_none() {
                            pipe = Pipe::connect(id).ok();
                        }
                        let Some(p) = pipe.as_mut() else { break };
                        if p.set_activity(next.as_ref()).is_ok() {
                            break;
                        }
                        pipe = None;
                    }
                }
            });
            Some(tx)
        })
        .as_ref()
}

/// One connection to the Discord app: frames of (opcode, length, JSON), little-endian.
struct Pipe {
    file: std::fs::File,
}

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;

impl Pipe {
    #[cfg(windows)]
    fn open() -> std::io::Result<std::fs::File> {
        let mut last = std::io::Error::from(std::io::ErrorKind::NotFound);
        // Discord listens on the first free pipe of discord-ipc-0..9.
        for i in 0..10 {
            match std::fs::OpenOptions::new().read(true).write(true).open(format!(r"\\.\pipe\discord-ipc-{i}")) {
                Ok(file) => return Ok(file),
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    #[cfg(not(windows))]
    fn open() -> std::io::Result<std::fs::File> {
        Err(std::io::Error::from(std::io::ErrorKind::Unsupported))
    }

    fn connect(client_id: &str) -> Result<Self> {
        let mut pipe = Pipe { file: Self::open()? };
        pipe.send(OP_HANDSHAKE, &json!({ "v": 1, "client_id": client_id }))?;
        pipe.recv()?;
        Ok(pipe)
    }

    fn send(&mut self, op: u32, payload: &serde_json::Value) -> Result<()> {
        let body = serde_json::to_vec(payload)?;
        let mut frame = Vec::with_capacity(8 + body.len());
        frame.extend_from_slice(&op.to_le_bytes());
        frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
        frame.extend_from_slice(&body);
        self.file.write_all(&frame)?;
        self.file.flush()?;
        Ok(())
    }

    fn recv(&mut self) -> Result<serde_json::Value> {
        let mut header = [0u8; 8];
        self.file.read_exact(&mut header)?;
        let op = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
        let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
        if len > 1 << 20 {
            bail!("unexpected Discord reply");
        }
        let mut body = vec![0u8; len];
        self.file.read_exact(&mut body)?;
        if op == OP_CLOSE {
            bail!("Discord closed the connection");
        }
        Ok(serde_json::from_slice(&body).unwrap_or_default())
    }

    fn set_activity(&mut self, activity: Option<&Activity>) -> Result<()> {
        let activity = activity.map(|a| {
            json!({
                "details": a.details,
                "state": a.state,
                "timestamps": { "start": a.start },
                // An image uploaded as "blockyard" in the app's Rich Presence art assets.
                "assets": { "large_image": "blockyard", "large_text": "Blockyard" },
            })
        });
        self.send(
            OP_FRAME,
            &json!({
                "cmd": "SET_ACTIVITY",
                "args": { "pid": std::process::id(), "activity": activity },
                "nonce": uuid::Uuid::new_v4().to_string(),
            }),
        )?;
        let reply = self.recv()?;
        if reply.get("evt").and_then(|e| e.as_str()) == Some("ERROR") {
            bail!("Discord refused the activity: {reply}");
        }
        Ok(())
    }
}
