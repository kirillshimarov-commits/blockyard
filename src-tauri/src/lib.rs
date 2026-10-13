mod launcher;

use crate::launcher::instance::{self, Instance, Loader};
use crate::launcher::settings::{self, Settings};
use crate::launcher::appearance;
use crate::launcher::auth;
use crate::launcher::tunnel;
use crate::launcher::updater;
use crate::launcher::updates;
use crate::launcher::crash;
use crate::launcher::mods::{self, Asset, ContentKind, Source};
use crate::launcher::{curseforge, fabric, forge, launch, modpack, modrinth, mojang, Progress, Reporter};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::window::{Effect, EffectsBuilder};
use tauri::{AppHandle, Emitter, Manager, State, Theme, WebviewWindow, WindowEvent};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::Notify;

type CmdResult<T> = Result<T, String>;

fn err(e: anyhow::Error) -> String {
    // Windows "sharing violation": a file of the instance is held by a game that is still running.
    let locked = e.chain().any(|c| c.downcast_ref::<std::io::Error>().and_then(|io| io.raw_os_error()) == Some(32));
    if locked {
        return format!(
            "Файлы сборки заняты: похоже, эта игра ещё запущена, возможно без окна. \
             Закройте Minecraft или завершите процесс Java (javaw.exe) в диспетчере задач и попробуйте снова.\n\n{e:#}"
        );
    }
    format!("{e:#}")
}

/// Play-with-a-friend state: LAN ports of open worlds and the active host/guest tunnels.
#[derive(Default)]
struct Tunnels {
    lan_ports: Mutex<HashMap<String, u16>>,
    host: tokio::sync::Mutex<Option<tunnel::Host>>,
    guest: tokio::sync::Mutex<Option<tunnel::Guest>>,
}

/// Instances that are installing or running, with a handle to stop them.
#[derive(Default)]
struct Running(Mutex<HashMap<String, Arc<Notify>>>);

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ProgressEvent {
    instance_id: String,
    #[serde(flatten)]
    progress: Progress,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LanEvent {
    instance_id: String,
    port: u16,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LogEvent {
    instance_id: String,
    line: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct StateEvent {
    instance_id: String,
    /// "installing" | "running" | "stopped"
    state: &'static str,
    error: Option<String>,
    /// Crash report written by the game, if it crashed.
    crash_report: Option<String>,
    /// Plain-language guesses at the cause of a crash.
    hints: Vec<String>,
}

fn emit_state(app: &AppHandle, instance_id: &str, state: &'static str, error: Option<String>) {
    let _ = app.emit(
        "game-state",
        StateEvent { instance_id: instance_id.to_string(), state, error, crash_report: None, hints: Vec::new() },
    );
}

fn emit_stopped(app: &AppHandle, instance_id: &str, result: anyhow::Result<()>) {
    let (error, crash_report, hints) = match result {
        Ok(()) => (None, None, Vec::new()),
        Err(e) => {
            let crash = e.downcast_ref::<crash::GameCrash>();
            let report = crash.and_then(|c| c.report.as_ref()).map(|p| p.to_string_lossy().into_owned());
            let hints = crash.map(|c| c.hints.clone()).unwrap_or_default();
            (Some(err(e)), report, hints)
        }
    };
    let _ = app.emit(
        "game-state",
        StateEvent { instance_id: instance_id.to_string(), state: "stopped", error, crash_report, hints },
    );
}

// ---------- Settings ----------

#[tauri::command]
fn get_settings() -> Settings {
    settings::load()
}

#[tauri::command]
fn save_settings(settings: Settings) -> CmdResult<()> {
    if !settings::is_valid_username(&settings.username) {
        return Err("Ник: от 3 до 16 символов, латиница, цифры и _".into());
    }
    settings::save(&settings).map_err(err)
}

// ---------- Versions ----------

#[tauri::command]
async fn list_versions() -> CmdResult<Vec<mojang::ManifestEntry>> {
    Ok(mojang::fetch_manifest().await.map_err(err)?.versions)
}

#[tauri::command]
async fn list_loader_versions(loader: String, game_version: String) -> CmdResult<Vec<fabric::LoaderVersion>> {
    let g = &game_version;
    match loader.as_str() {
        "fabric" => fabric::loader_versions(fabric::Flavor::Fabric, g).await,
        "quilt" => fabric::loader_versions(fabric::Flavor::Quilt, g).await,
        "forge" => forge::loader_versions(forge::Kind::Forge, g).await,
        "neoforge" => forge::loader_versions(forge::Kind::NeoForge, g).await,
        other => return Err(format!("неизвестный загрузчик {other}")),
    }
    .map_err(err)
}

// ---------- Instances ----------

#[tauri::command]
fn list_instances() -> Vec<Instance> {
    instance::list()
}

#[tauri::command]
fn get_instance(id: String) -> CmdResult<Instance> {
    instance::get(&id).map_err(err)
}

#[tauri::command]
fn create_instance(name: String, game_version: String, loader: Loader) -> CmdResult<Instance> {
    instance::create(&name, &game_version, loader, settings::load().memory_mb).map_err(err)
}

#[tauri::command]
fn delete_instance(id: String, running: State<'_, Running>) -> CmdResult<()> {
    if running.0.lock().unwrap().contains_key(&id) {
        return Err("Сначала закройте игру".into());
    }
    instance::delete(&id).map_err(err)
}

#[tauri::command]
fn set_instance_memory(id: String, memory_mb: u32) -> CmdResult<Instance> {
    let mut inst = instance::get(&id).map_err(err)?;
    inst.memory_mb = memory_mb.clamp(1024, 32768);
    inst.save().map_err(err)?;
    Ok(inst)
}

#[tauri::command]
fn instance_folder(id: String) -> CmdResult<String> {
    let inst = instance::get(&id).map_err(err)?;
    std::fs::create_dir_all(inst.game_dir()).map_err(|e| e.to_string())?;
    Ok(inst.game_dir().to_string_lossy().into_owned())
}

#[tauri::command]
fn running_instances(running: State<'_, Running>) -> Vec<String> {
    running.0.lock().unwrap().keys().cloned().collect()
}

// ---------- Launch ----------

#[tauri::command]
async fn launch_instance(app: AppHandle, id: String, running: State<'_, Running>) -> CmdResult<()> {
    start_game(app, id, running.inner(), None).await
}

/// Starts the game; `join_port` makes it connect to a friend's world through the local tunnel port.
async fn start_game(app: AppHandle, id: String, running: &Running, join_port: Option<u16>) -> CmdResult<()> {
    let mut inst = instance::get(&id).map_err(err)?;
    let settings = settings::load();
    let session = match &settings.active_account {
        Some(uuid) => auth::session(uuid).await.map_err(err)?,
        None => launch::offline_session(&settings.username),
    };
    let stop = Arc::new(Notify::new());
    {
        let mut map = running.0.lock().unwrap();
        if map.contains_key(&id) {
            return Err("Игра уже запущена".into());
        }
        map.insert(id.clone(), stop.clone());
    }

    let app2 = app.clone();
    tauri::async_runtime::spawn(async move {
        let result = run_game(&app2, &mut inst, &session, stop, join_port).await;
        let none_left = {
            let running = app2.state::<Running>();
            let mut map = running.0.lock().unwrap();
            map.remove(&inst.id);
            map.is_empty()
        };
        if none_left {
            launcher::discord::set(None);
        }
        // The world is gone with the game: forget its LAN port and stop inviting friends to it.
        let tunnels = app2.state::<Tunnels>();
        tunnels.lan_ports.lock().unwrap().remove(&inst.id);
        let mut host = tunnels.host.lock().await;
        if host.as_ref().is_some_and(|h| h.instance_id == inst.id) {
            if let Some(h) = host.take() {
                h.stop().await;
            }
        }
        drop(host);
        emit_stopped(&app2, &inst.id, result);
    });
    Ok(())
}

async fn run_game(
    app: &AppHandle,
    inst: &mut Instance,
    session: &auth::Session,
    stop: Arc<Notify>,
    join_port: Option<u16>,
) -> anyhow::Result<()> {
    emit_state(app, &inst.id, "installing", None);
    let app_for_progress = app.clone();
    let id_for_progress = inst.id.clone();
    let reporter: Reporter = Arc::new(move |progress| {
        let _ = app_for_progress.emit("launch-progress", ProgressEvent { instance_id: id_for_progress.clone(), progress });
    });

    let mut prepared = tokio::select! {
        p = launch::prepare(inst, session, &reporter) => p?,
        _ = stop.notified() => return Ok(()),
    };
    if let Some(port) = join_port {
        prepared.args.extend(tunnel::join_args(&inst.game_version, port));
    }
    let pre_launch = inst.settings.pre_launch.trim().to_string();
    if !pre_launch.is_empty() {
        report_stage(app, &inst.id, "Команда перед запуском");
        launch::run_hook(&pre_launch, &prepared.game_dir)
            .await
            .map_err(|e| anyhow::anyhow!("Команда перед запуском не выполнилась: {e:#}"))?;
    }
    let launcher_settings = settings::load();
    let game_ui = launcher::gameui::wanted(&inst.settings.game_ui, launcher_settings.game_ui, &prepared.game_dir);
    if let Err(e) = launcher::gameui::sync(&prepared.game_dir, inst, game_ui).await {
        eprintln!("Blockyard UI mod: {e:#}");
    }
    let started = std::time::SystemTime::now();
    let mut child = launch::spawn(&prepared)?;
    launch::mark_played(inst)?;
    emit_state(app, &inst.id, "running", None);
    if launcher_settings.discord {
        launcher::discord::set(Some(launcher::discord::Activity {
            details: inst.name.clone(),
            state: format!("Minecraft {} · {}", inst.game_version, inst.loader.display_name()),
            start: launcher::now(),
        }));
    }

    // Fullscreen and wrapped launches keep their own window: a wrapper's PID is not the game's.
    let embed = launcher_settings.embed_game && !inst.settings.fullscreen && inst.settings.wrapper.trim().is_empty();
    if let (true, Some(pid), Some(parent)) = (embed, child.id(), main_hwnd(app)) {
        let app = app.clone();
        let instance_id = inst.id.clone();
        tauri::async_runtime::spawn(async move {
            if launcher::embed::embed_when_ready(pid, parent).await {
                let _ = app.emit("game-embedded", instance_id);
            }
        });
    }

    // Where the friend invite code is left for the game to show.
    let invite_file = prepared.game_dir.join("blockyard").join("invite.txt");
    let _ = std::fs::remove_file(&invite_file);

    // The last lines of output, for crash analysis when there is no crash report.
    let log_tail: Arc<Mutex<std::collections::VecDeque<String>>> = Arc::default();
    for stream in [child.stdout.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>),
                   child.stderr.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>)]
        .into_iter()
        .flatten()
    {
        let app = app.clone();
        let instance_id = inst.id.clone();
        let tail = log_tail.clone();
        let invite_file = invite_file.clone();
        tauri::async_runtime::spawn(async move {
            let mut lines = BufReader::new(stream).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Some(port) = tunnel::parse_lan_port(&line) {
                    app.state::<Tunnels>().lan_ports.lock().unwrap().insert(instance_id.clone(), port);
                    let _ = app.emit("lan-opened", LanEvent { instance_id: instance_id.clone(), port });
                    // The game covers the launcher, so hand the invite code to the game itself:
                    // Blockyard UI shows it in the pause menu.
                    let (app, instance_id, file) = (app.clone(), instance_id.clone(), invite_file.clone());
                    tauri::async_runtime::spawn(async move {
                        if let Ok(code) = create_invite(&instance_id, &app.state::<Tunnels>()).await {
                            if let Some(dir) = file.parent() {
                                let _ = std::fs::create_dir_all(dir);
                            }
                            let _ = std::fs::write(&file, code);
                        }
                    });
                }
                {
                    let mut t = tail.lock().unwrap();
                    t.push_back(line.clone());
                    if t.len() > 400 {
                        t.pop_front();
                    }
                }
                let _ = app.emit("game-log", LogEvent { instance_id: instance_id.clone(), line });
            }
        });
    }

    let post_exit = inst.settings.post_exit.trim().to_string();
    let game_dir = prepared.game_dir.clone();
    let outcome: anyhow::Result<()> = async { tokio::select! {
        status = child.wait() => {
            let status = status?;
            if !status.success() {
                // Give the log readers a moment to drain the last lines.
                tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                let tail = log_tail.lock().unwrap().iter().cloned().collect::<Vec<_>>().join("\n");
                return Err(crash::describe(&prepared.game_dir, started, status.code().unwrap_or(-1), &tail).into());
            }
        }
        _ = stop.notified() => {
            let _ = child.kill().await;
        }
    }
    Ok(()) }.await;
    let played = started.elapsed().map(|d| d.as_secs()).unwrap_or(0);
    if let Err(e) = instance::add_play_time(&inst.id, played) {
        eprintln!("play time: {e:#}");
    }
    let _ = std::fs::remove_file(&invite_file);
    if !post_exit.is_empty() {
        if let Err(e) = launch::run_hook(&post_exit, &game_dir).await {
            let _ = app.emit("game-log", LogEvent { instance_id: inst.id.clone(), line: format!("[Blockyard] {e:#}") });
        }
    }
    outcome
}

fn report_stage(app: &AppHandle, instance_id: &str, stage: &str) {
    let _ = app.emit(
        "launch-progress",
        ProgressEvent { instance_id: instance_id.to_string(), progress: Progress { stage: stage.into(), current: 0, total: 1 } },
    );
}

#[tauri::command]
fn stop_instance(id: String, running: State<'_, Running>) {
    if let Some(stop) = running.0.lock().unwrap().get(&id) {
        stop.notify_one();
    }
}

// ---------- Mods ----------

#[tauri::command]
async fn minecraft_news() -> CmdResult<Vec<launcher::news::News>> {
    launcher::news::latest().await.map_err(err)
}

#[tauri::command]
fn game_ui_available() -> bool {
    launcher::gameui::available()
}

#[tauri::command]
fn curseforge_available() -> bool {
    curseforge::is_available()
}

#[tauri::command]
fn discord_available() -> bool {
    launcher::discord::is_available()
}

#[tauri::command]
async fn search_mods(
    kind: ContentKind,
    source: Source,
    query: String,
    game_version: Option<String>,
    loader: Option<String>,
    sort: String,
    offset: u64,
) -> CmdResult<mods::SearchResult> {
    let (q, v, l) = (&query, game_version.as_deref(), loader.as_deref());
    match source {
        Source::Modrinth => modrinth::search(kind, q, v, l, &sort, offset).await,
        Source::Curseforge => curseforge::search(kind, q, v, l, &sort, offset).await,
    }
    .map_err(err)
}

#[tauri::command]
async fn install_mod(instance_id: String, source: Source, project_id: String) -> CmdResult<mods::InstallResult> {
    match source {
        Source::Modrinth => modrinth::install(&instance_id, &project_id).await,
        Source::Curseforge => curseforge::install(&instance_id, &project_id).await,
    }
    .map_err(err)
}

#[tauri::command]
async fn install_asset(
    instance_id: String,
    source: Source,
    project_id: String,
    asset: Asset,
) -> CmdResult<mods::InstallResult> {
    match source {
        Source::Modrinth => modrinth::install_asset(&instance_id, &project_id, asset).await,
        Source::Curseforge => curseforge::install_asset(&instance_id, &project_id, asset).await,
    }
    .map_err(err)
}

/// Removes a mod, shader or resource pack; `folder` is "mods", "shaderpacks" or "resourcepacks".
#[tauri::command]
fn remove_content(instance_id: String, folder: String, project_id: String) -> CmdResult<Instance> {
    instance::remove_content(&instance_id, &folder, &project_id).map_err(err)
}

#[tauri::command]
async fn check_mod_updates(instance_id: String) -> CmdResult<Vec<updates::ModUpdate>> {
    updates::check(&instance_id).await.map_err(err)
}

#[tauri::command]
async fn apply_mod_updates(instance_id: String, project_ids: Vec<String>) -> CmdResult<Instance> {
    updates::apply(&instance_id, &project_ids).await.map_err(err)
}

// ---------- Microsoft accounts ----------

#[tauri::command]
fn microsoft_available() -> bool {
    auth::is_available()
}

#[tauri::command]
fn list_accounts() -> Vec<auth::AccountInfo> {
    auth::list()
}

#[tauri::command]
async fn start_microsoft_login() -> CmdResult<auth::DeviceCode> {
    auth::start_login().await.map_err(err)
}

/// Waits for the user to enter the code, saves the account and makes it active.
#[tauri::command]
async fn finish_microsoft_login(code: auth::DeviceCode) -> CmdResult<auth::AccountInfo> {
    let account = auth::finish_login(&code).await.map_err(err)?;
    let mut s = settings::load();
    s.active_account = Some(account.uuid.clone());
    settings::save(&s).map_err(err)?;
    Ok(account)
}

#[tauri::command]
fn remove_account(uuid: String) -> CmdResult<Settings> {
    auth::remove(&uuid).map_err(err)?;
    let mut s = settings::load();
    if s.active_account.as_deref() == Some(uuid.as_str()) {
        s.active_account = None;
        settings::save(&s).map_err(err)?;
    }
    Ok(s)
}

/// Runs an install job for an instance in the background, reporting progress like a launch.
fn spawn_install<F, Fut>(app: &AppHandle, running: &Running, instance_id: &str, job: F)
where
    F: FnOnce(Reporter) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = anyhow::Result<()>> + Send,
{
    let stop = Arc::new(Notify::new());
    running.0.lock().unwrap().insert(instance_id.to_string(), stop.clone());
    emit_state(app, instance_id, "installing", None);

    let app = app.clone();
    let id = instance_id.to_string();
    tauri::async_runtime::spawn(async move {
        let app_for_progress = app.clone();
        let id_for_progress = id.clone();
        let reporter: Reporter = Arc::new(move |progress| {
            let _ = app_for_progress
                .emit("launch-progress", ProgressEvent { instance_id: id_for_progress.clone(), progress });
        });
        let result = tokio::select! {
            r = job(reporter) => r,
            _ = stop.notified() => Err(anyhow::anyhow!("Установка отменена")),
        };
        app.state::<Running>().0.lock().unwrap().remove(&id);
        emit_state(&app, &id, "stopped", result.err().map(err));
    });
}

/// Creates an instance for the modpack right away and installs it in the background.
#[tauri::command]
async fn install_modpack(
    app: AppHandle,
    source: Source,
    project_id: String,
    running: State<'_, Running>,
) -> CmdResult<Instance> {
    let (inst, plan) = modpack::prepare(source, &project_id, settings::load().memory_mb).await.map_err(err)?;
    let id = inst.id.clone();
    spawn_install(&app, &running, &inst.id, move |reporter| async move {
        modpack::finish(&id, plan, &reporter).await.map(|_| ())
    });
    Ok(inst)
}

/// Imports a .mrpack or CurseForge modpack zip from disk into a new instance.
#[tauri::command]
async fn import_modpack(app: AppHandle, path: String, running: State<'_, Running>) -> CmdResult<Instance> {
    let path = std::path::PathBuf::from(path);
    let (inst, kind) = modpack::prepare_import(&path, settings::load().memory_mb).map_err(err)?;
    let id = inst.id.clone();
    spawn_install(&app, &running, &inst.id, move |reporter| async move {
        modpack::finish_import(&id, &path, kind, &reporter).await.map(|_| ())
    });
    Ok(inst)
}

#[tauri::command]
async fn export_instance(instance_id: String, path: String) -> CmdResult<()> {
    modpack::export_mrpack(&instance_id, std::path::Path::new(&path)).await.map_err(err)
}

#[tauri::command]
fn duplicate_instance(id: String) -> CmdResult<Instance> {
    instance::duplicate(&id).map_err(err)
}

#[tauri::command]
fn save_instance_settings(id: String, patch: instance::InstancePatch, running: State<'_, Running>) -> CmdResult<Instance> {
    if running.0.lock().unwrap().contains_key(&id) {
        return Err("Сначала закройте игру".into());
    }
    instance::apply_patch(&id, patch).map_err(err)
}

/// Re-downloads the loader and game files in the background, like a launch without starting the game.
#[tauri::command]
fn repair_instance(app: AppHandle, id: String, running: State<'_, Running>) -> CmdResult<()> {
    if running.0.lock().unwrap().contains_key(&id) {
        return Err("Сначала закройте игру".into());
    }
    let inst = instance::get(&id).map_err(err)?;
    spawn_install(&app, &running, &id, move |reporter| async move { launch::repair(&inst, &reporter).await });
    Ok(())
}

/// Runs `java -version` and returns the first line, to check a custom Java path.
#[tauri::command]
async fn test_java(path: String) -> CmdResult<String> {
    let mut cmd = tokio::process::Command::new(&path);
    cmd.arg("-version");
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let out = cmd.output().await.map_err(|e| format!("Не удалось запустить: {e}"))?;
    // java -version prints to stderr.
    let text = String::from_utf8_lossy(&out.stderr);
    text.lines().next().map(str::to_string).filter(|l| !l.is_empty()).ok_or_else(|| "Это не похоже на Java".into())
}

#[tauri::command]
fn set_mod_enabled(instance_id: String, project_id: String, enabled: bool) -> CmdResult<Instance> {
    instance::set_mod_enabled(&instance_id, &project_id, enabled).map_err(err)
}

// ---------- Play with a friend ----------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HostStatus {
    instance_id: String,
    invite: String,
    peers: Vec<tunnel::LinkInfo>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GuestStatus {
    info: tunnel::InviteInfo,
    connected: bool,
    link: tunnel::LinkInfo,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TunnelStatus {
    lan_ports: HashMap<String, u16>,
    host: Option<HostStatus>,
    guest: Option<GuestStatus>,
}

#[tauri::command]
async fn tunnel_status(tunnels: State<'_, Tunnels>) -> CmdResult<TunnelStatus> {
    let lan_ports = tunnels.lan_ports.lock().unwrap().clone();
    let host = tunnels.host.lock().await.as_ref().map(|h| HostStatus {
        instance_id: h.instance_id.clone(),
        invite: h.invite.clone(),
        peers: h.peers(),
    });
    let guest = tunnels.guest.lock().await.as_ref().map(|g| GuestStatus {
        info: g.info.clone(),
        connected: g.is_connected(),
        link: g.link(),
    });
    Ok(TunnelStatus { lan_ports, host, guest })
}

/// Starts inviting friends into the world this instance has opened to LAN; returns the invite code.
#[tauri::command]
async fn tunnel_invite(instance_id: String, tunnels: State<'_, Tunnels>) -> CmdResult<String> {
    create_invite(&instance_id, &tunnels).await
}

/// Starts hosting the instance's open LAN world (or reuses the running host) and returns the code.
async fn create_invite(instance_id: &str, tunnels: &Tunnels) -> CmdResult<String> {
    let instance_id = instance_id.to_string();
    let port = tunnels
        .lan_ports
        .lock()
        .unwrap()
        .get(&instance_id)
        .copied()
        .ok_or("Сначала откройте мир для сети: в игре Esc → «Открыть для сети»")?;
    let mut host = tunnels.host.lock().await;
    if let Some(h) = host.as_ref().filter(|h| h.instance_id == instance_id) {
        return Ok(h.invite.clone());
    }
    if let Some(old) = host.take() {
        old.stop().await;
    }
    let inst = instance::get(&instance_id).map_err(err)?;
    let h = tunnel::Host::start(&inst, &inst.name, port).await.map_err(err)?;
    let invite = h.invite.clone();
    *host = Some(h);
    Ok(invite)
}

#[tauri::command]
async fn tunnel_stop_host(tunnels: State<'_, Tunnels>) -> CmdResult<()> {
    if let Some(h) = tunnels.host.lock().await.take() {
        h.stop().await;
    }
    Ok(())
}

#[tauri::command]
fn tunnel_decode(code: String) -> CmdResult<tunnel::InviteInfo> {
    tunnel::Invite::decode(&code).map(|i| i.info()).map_err(err)
}

/// Connects to a friend's world and starts the chosen instance straight into it.
#[tauri::command]
async fn tunnel_join(
    app: AppHandle,
    code: String,
    instance_id: String,
    tunnels: State<'_, Tunnels>,
    running: State<'_, Running>,
) -> CmdResult<()> {
    if running.0.lock().unwrap().contains_key(&instance_id) {
        return Err("Эта сборка уже запущена — закройте игру и попробуйте снова".into());
    }
    let mut guest = tunnels.guest.lock().await;
    if let Some(old) = guest.take() {
        old.stop().await;
    }
    let g = tunnel::Guest::connect(&code).await.map_err(err)?;
    let port = g.local_port;
    *guest = Some(g);
    drop(guest);
    start_game(app, instance_id, running.inner(), Some(port)).await
}

#[tauri::command]
async fn tunnel_leave(tunnels: State<'_, Tunnels>) -> CmdResult<()> {
    if let Some(g) = tunnels.guest.lock().await.take() {
        g.stop().await;
    }
    Ok(())
}

// ---------- Launcher updates ----------

#[tauri::command]
fn launcher_version() -> &'static str {
    updater::current_version()
}

#[tauri::command]
async fn check_launcher_update() -> CmdResult<Option<updater::LauncherUpdate>> {
    updater::check().await.map_err(err)
}

#[tauri::command]
async fn install_launcher_update(app: AppHandle, url: String) -> CmdResult<()> {
    updater::install(&url).await.map_err(err)?;
    app.exit(0);
    Ok(())
}

/// Picks up mods the user downloaded manually from the Downloads folder.
#[tauri::command]
fn import_pending_mods(instance_id: String) -> CmdResult<Instance> {
    mods::import_pending(&instance_id).map_err(err)
}

// ---------- Appearance ----------

#[tauri::command]
fn get_appearance() -> serde_json::Value {
    appearance::load()
}

#[tauri::command]
fn save_appearance(value: serde_json::Value) -> CmdResult<()> {
    appearance::save(&value).map_err(err)
}

/// Applies the translucency effect and returns the one actually in use
/// ("mica" | "acrylic" | "blur" | "none"), so the UI can switch to an opaque background.
fn apply_window_style(window: &WebviewWindow, effect: &str, theme: &str) -> &'static str {
    let _ = window.set_theme(match theme {
        "dark" => Some(Theme::Dark),
        "light" => Some(Theme::Light),
        _ => None,
    });

    let wanted = match effect {
        "auto" if appearance::is_windows_11() => "mica",
        "auto" => "acrylic",
        "mica" if !appearance::is_windows_11() => "acrylic",
        other => other,
    };
    let (name, effect) = match wanted {
        "mica" => ("mica", Effect::Mica),
        "acrylic" => ("acrylic", Effect::Acrylic),
        "blur" => ("blur", Effect::Blur),
        _ => {
            let _ = window.set_effects(None);
            return "none";
        }
    };
    if cfg!(target_os = "windows") && window.set_effects(EffectsBuilder::new().effect(effect).build()).is_ok() {
        name
    } else {
        let _ = window.set_effects(None);
        "none"
    }
}

/// Raw handle of the launcher window, for embedding the game into it.
fn main_hwnd(app: &AppHandle) -> Option<isize> {
    #[cfg(windows)]
    {
        app.get_webview_window("main")?.hwnd().ok().map(|h| h.0 as isize)
    }
    #[cfg(not(windows))]
    {
        let _ = app;
        None
    }
}

#[tauri::command]
fn set_window_style(window: WebviewWindow, effect: String, theme: String) -> &'static str {
    apply_window_style(&window, &effect, &theme)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(Running::default())
        .manage(Tunnels::default())
        .setup(|app| {
            // The window starts hidden so the saved effect is in place before it appears.
            if let Some(window) = app.get_webview_window("main") {
                let saved = appearance::load();
                apply_window_style(
                    &window,
                    appearance::field(&saved, "effect", "auto"),
                    appearance::field(&saved, "theme", "system"),
                );
                window.show()?;
                // Keep an embedded game filling the window and receiving the keyboard.
                let handle = app.handle().clone();
                window.on_window_event(move |event| match event {
                    WindowEvent::Resized(_) | WindowEvent::Moved(_) => {
                        if let Some(parent) = main_hwnd(&handle) {
                            launcher::embed::resize_all(parent);
                        }
                    }
                    WindowEvent::Focused(true) => launcher::embed::focus(),
                    // Closing the launcher must not take the game's window with it.
                    WindowEvent::CloseRequested { .. } | WindowEvent::Destroyed => launcher::embed::detach_all(),
                    _ => {}
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            list_versions,
            list_loader_versions,
            list_instances,
            get_instance,
            create_instance,
            delete_instance,
            set_instance_memory,
            instance_folder,
            running_instances,
            launch_instance,
            stop_instance,
            curseforge_available,
            discord_available,
            minecraft_news,
            game_ui_available,
            search_mods,
            install_mod,
            import_pending_mods,
            install_asset,
            remove_content,
            check_mod_updates,
            apply_mod_updates,
            microsoft_available,
            list_accounts,
            start_microsoft_login,
            finish_microsoft_login,
            remove_account,
            install_modpack,
            import_modpack,
            export_instance,
            set_mod_enabled,
            duplicate_instance,
            save_instance_settings,
            repair_instance,
            test_java,
            launcher_version,
            check_launcher_update,
            install_launcher_update,
            tunnel_status,
            tunnel_invite,
            tunnel_stop_host,
            tunnel_decode,
            tunnel_join,
            tunnel_leave,
            get_appearance,
            save_appearance,
            set_window_style,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
