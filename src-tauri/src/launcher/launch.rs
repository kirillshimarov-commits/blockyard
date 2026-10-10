//! Preparing and starting the game process.

use super::auth::Session;
use super::instance::{Instance, Loader};
use super::mojang::{self, ArgValue, Argument, AssetLayout, VersionJson};
use super::fabric::{self, Flavor};
use super::forge::{self, Kind};
use super::{java, now, paths, report, Reporter};
use anyhow::{Context, Result};
use md5::{Digest, Md5};
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use tokio::process::{Child, Command};

/// UUID the vanilla server assigns to offline players: MD5("OfflinePlayer:<name>") as UUID v3.
pub fn offline_uuid(username: &str) -> String {
    let mut hash: [u8; 16] = Md5::digest(format!("OfflinePlayer:{username}").as_bytes()).into();
    hash[6] = (hash[6] & 0x0f) | 0x30;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    hex::encode(hash)
}

pub struct Prepared {
    pub java: PathBuf,
    pub args: Vec<String>,
    pub game_dir: PathBuf,
}

async fn load_version(instance: &Instance, reporter: &Reporter) -> Result<VersionJson> {
    let game = &instance.game_version;
    match &instance.loader {
        Loader::Vanilla => mojang::load_vanilla(game).await,
        Loader::Fabric { version } => fabric::load(Flavor::Fabric, game, version).await,
        Loader::Quilt { version } => fabric::load(Flavor::Quilt, game, version).await,
        Loader::Forge { version } => forge::load(Kind::Forge, game, version, reporter).await,
        Loader::NeoForge { version } => forge::load(Kind::NeoForge, game, version, reporter).await,
    }
}

/// Downloads everything the instance needs and builds the java command line.
/// Offline session: any nickname, the same UUID the vanilla server would assign.
pub fn offline_session(username: &str) -> Session {
    Session {
        username: username.to_string(),
        uuid: offline_uuid(username),
        access_token: "0".into(),
        user_type: "legacy",
    }
}

pub async fn prepare(instance: &Instance, session: &Session, reporter: &Reporter) -> Result<Prepared> {
    let username = session.username.as_str();
    report(reporter, "Подготовка", 0, 1);
    let version = load_version(instance, reporter).await.context("не удалось загрузить данные версии")?;
    let game_dir = instance.game_dir();
    let natives_dir = instance.natives_dir();
    std::fs::create_dir_all(&game_dir)?;

    let layout = mojang::install_files(&version, &natives_dir, &game_dir, reporter).await?;
    let major = version.java_version.as_ref().map_or(8, |j| j.major_version);
    let java = java::ensure_java(major, reporter).await?;

    // Classpath: libraries, then the client jar.
    let libs = mojang::resolve_libraries(&version);
    let mut classpath: Vec<String> = libs
        .iter()
        .filter(|l| l.classpath)
        .map(|l| l.path.to_string_lossy().into_owned())
        .collect();
    classpath.push(mojang::client_jar_path(&version.client_jar_id).to_string_lossy().into_owned());

    let assets_root = paths::assets_dir();
    let game_assets = match &layout {
        AssetLayout::Virtual(dir) => dir.clone(),
        AssetLayout::Resources => game_dir.join("resources"),
        AssetLayout::Normal => assets_root.clone(),
    };
    let uuid = session.uuid.clone();
    let path_str = |p: &PathBuf| p.to_string_lossy().into_owned();

    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", username.to_string()),
        ("version_name", version.id.clone()),
        ("game_directory", path_str(&game_dir)),
        ("assets_root", path_str(&assets_root)),
        ("game_assets", path_str(&game_assets)),
        ("assets_index_name", version.asset_index.as_ref().map(|a| a.id.clone()).unwrap_or_default()),
        ("auth_uuid", uuid.clone()),
        ("auth_access_token", session.access_token.clone()),
        ("auth_session", session.access_token.clone()),
        ("auth_xuid", "0".to_string()),
        ("clientid", "0".to_string()),
        ("user_type", session.user_type.to_string()),
        ("user_properties", "{}".to_string()),
        ("version_type", version.kind.clone().unwrap_or_else(|| "release".into())),
        ("natives_directory", path_str(&natives_dir)),
        ("library_directory", path_str(&paths::libraries_dir())),
        ("classpath_separator", paths::classpath_separator().to_string()),
        ("launcher_name", "blockyard".to_string()),
        ("launcher_version", env!("CARGO_PKG_VERSION").to_string()),
        ("classpath", classpath.join(paths::classpath_separator())),
    ]);
    let substitute = |s: &str| {
        let mut out = s.to_string();
        for (key, value) in &vars {
            out = out.replace(&format!("${{{key}}}"), value);
        }
        out
    };
    let expand = |args: &[Argument]| -> Vec<String> {
        let mut out = Vec::new();
        for arg in args {
            match arg {
                Argument::Plain(s) => out.push(substitute(s)),
                Argument::Conditional { rules, value } if mojang::rules_allow(rules) => match value {
                    ArgValue::One(s) => out.push(substitute(s)),
                    ArgValue::Many(list) => out.extend(list.iter().map(|s| substitute(s))),
                },
                Argument::Conditional { .. } => {}
            }
        }
        out
    };

    let mut args = vec![format!("-Xmx{}M", instance.memory_mb), "-Xms512M".to_string()];
    match &version.arguments {
        Some(a) if !a.jvm.is_empty() => args.extend(expand(&a.jvm)),
        // Old versions have no JVM arguments in their JSON.
        _ => {
            args.push(format!("-Djava.library.path={}", path_str(&natives_dir)));
            args.push("-cp".into());
            args.push(vars["classpath"].clone());
        }
    }
    args.push(version.main_class.clone());
    match (&version.arguments, &version.minecraft_arguments) {
        (_, Some(legacy)) => args.extend(legacy.split_whitespace().map(substitute)),
        (Some(a), None) => args.extend(expand(&a.game)),
        (None, None) => {}
    }

    Ok(Prepared { java, args, game_dir })
}

/// Starts the game. stdout/stderr are piped so the UI can show the log.
pub fn spawn(prepared: &Prepared) -> Result<Child> {
    let mut cmd = Command::new(&prepared.java);
    cmd.args(&prepared.args)
        .current_dir(&prepared.game_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.spawn().context("не удалось запустить Java")
}

pub fn mark_played(instance: &mut Instance) -> Result<()> {
    instance.last_played = Some(now());
    instance.save()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offline_uuid_matches_vanilla() {
        // Value from Java: UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8))
        assert_eq!(offline_uuid("Notch"), "b50ad385829d3141a2167e7d7539ba7f");
    }
}
