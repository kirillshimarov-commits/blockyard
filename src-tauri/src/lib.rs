mod launcher;

use crate::launcher::instance::{self, Instance, Loader};
use crate::launcher::settings::{self, Settings};
use crate::launcher::appearance;
use crate::launcher::mods::{self, Source};
use crate::launcher::{curseforge, fabric, launch, modrinth, mojang, Progress, Reporter};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::window::{Effect, EffectsBuilder};
use tauri::{AppHandle, Emitter, Manager, State, Theme, WebviewWindow};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::Notify;

type CmdResult<T> = Result<T, String>;

fn err(e: anyhow::Error) -> String {
    format!("{e:#}")
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
}

fn emit_state(app: &AppHandle, instance_id: &str, state: &'static str, error: Option<String>) {
    let _ = app.emit("game-state", StateEvent { instance_id: instance_id.to_string(), state, error });
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
async fn list_fabric_loaders(game_version: String) -> CmdResult<Vec<fabric::LoaderVersion>> {
    fabric::loader_versions(&game_version).await.map_err(err)
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
    let mut inst = instance::get(&id).map_err(err)?;
    let username = settings::load().username;
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
        let result = run_game(&app2, &mut inst, &username, stop).await;
        app2.state::<Running>().0.lock().unwrap().remove(&inst.id);
        emit_state(&app2, &inst.id, "stopped", result.err().map(err));
    });
    Ok(())
}

async fn run_game(app: &AppHandle, inst: &mut Instance, username: &str, stop: Arc<Notify>) -> anyhow::Result<()> {
    emit_state(app, &inst.id, "installing", None);
    let app_for_progress = app.clone();
    let id_for_progress = inst.id.clone();
    let reporter: Reporter = Arc::new(move |progress| {
        let _ = app_for_progress.emit("launch-progress", ProgressEvent { instance_id: id_for_progress.clone(), progress });
    });

    let prepared = tokio::select! {
        p = launch::prepare(inst, username, &reporter) => p?,
        _ = stop.notified() => return Ok(()),
    };
    let mut child = launch::spawn(&prepared)?;
    launch::mark_played(inst)?;
    emit_state(app, &inst.id, "running", None);

    for stream in [child.stdout.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>),
                   child.stderr.take().map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>)]
        .into_iter()
        .flatten()
    {
        let app = app.clone();
        let instance_id = inst.id.clone();
        tauri::async_runtime::spawn(async move {
            let mut lines = BufReader::new(stream).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let _ = app.emit("game-log", LogEvent { instance_id: instance_id.clone(), line });
            }
        });
    }

    tokio::select! {
        status = child.wait() => {
            let status = status?;
            if !status.success() {
                anyhow::bail!("Игра завершилась с ошибкой (код {}). Подробности в журнале.", status.code().unwrap_or(-1));
            }
        }
        _ = stop.notified() => {
            let _ = child.kill().await;
        }
    }
    Ok(())
}

#[tauri::command]
fn stop_instance(id: String, running: State<'_, Running>) {
    if let Some(stop) = running.0.lock().unwrap().get(&id) {
        stop.notify_one();
    }
}

// ---------- Mods ----------

#[tauri::command]
fn curseforge_available() -> bool {
    curseforge::is_available()
}

#[tauri::command]
async fn search_mods(
    source: Source,
    query: String,
    game_version: Option<String>,
    loader: Option<String>,
    sort: String,
    offset: u64,
) -> CmdResult<mods::SearchResult> {
    let (q, v, l) = (&query, game_version.as_deref(), loader.as_deref());
    match source {
        Source::Modrinth => modrinth::search(q, v, l, &sort, offset).await,
        Source::Curseforge => curseforge::search(q, v, l, &sort, offset).await,
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

/// Picks up mods the user downloaded manually from the Downloads folder.
#[tauri::command]
fn import_pending_mods(instance_id: String) -> CmdResult<Instance> {
    mods::import_pending(&instance_id).map_err(err)
}

#[tauri::command]
fn remove_mod(instance_id: String, project_id: String) -> CmdResult<Instance> {
    instance::remove_mod(&instance_id, &project_id).map_err(err)
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

#[tauri::command]
fn set_window_style(window: WebviewWindow, effect: String, theme: String) -> &'static str {
    apply_window_style(&window, &effect, &theme)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(Running::default())
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
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            list_versions,
            list_fabric_loaders,
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
            search_mods,
            install_mod,
            import_pending_mods,
            get_appearance,
            save_appearance,
            set_window_style,
            remove_mod,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
