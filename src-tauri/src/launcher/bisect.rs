//! Finding the mod that makes the game crash, by halving.
//!
//! Each step keeps half of the suspects enabled (plus the mods they need) and disables the rest;
//! the player starts the game, and whether it crashed narrows the suspects down. The state lives
//! in the instance folder, so a search survives restarting the launcher; finishing or cancelling
//! puts every mod back the way it was.

use super::instance::{self, Instance};
use anyhow::{anyhow, bail, Result};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct State {
    /// Every tracked mod and whether it was disabled before the search.
    original: Vec<(String, bool)>,
    /// Mods that were enabled when the search started.
    candidates: Vec<String>,
    /// For each candidate, the candidates it cannot run without.
    requires: HashMap<String, Vec<String>>,
    /// Candidates that may still be the cause.
    suspects: Vec<String>,
    /// The suspects tested in this step, and everything enabled for them.
    testing: Vec<String>,
    enabled: Vec<String>,
    step: u32,
    culprit: Option<String>,
    /// The game did not crash with any half: likely two mods together, or a random crash.
    not_found: bool,
}

/// What the UI shows about a search.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub step: u32,
    /// Rough number of game launches still needed.
    pub steps_left: u32,
    pub total: usize,
    pub enabled: usize,
    pub suspects: usize,
    pub culprit: Option<Culprit>,
    pub not_found: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Culprit {
    pub project_id: String,
    pub title: String,
}

fn state_file(id: &str) -> Result<PathBuf> {
    Ok(instance::get(id)?.dir().join("bisect.json"))
}

fn load(id: &str) -> Option<State> {
    let text = std::fs::read_to_string(state_file(id).ok()?).ok()?;
    serde_json::from_str(&text).ok()
}

fn store(id: &str, state: &State) -> Result<()> {
    std::fs::write(state_file(id)?, serde_json::to_string_pretty(state)?)?;
    Ok(())
}

pub fn is_active(id: &str) -> bool {
    state_file(id).is_ok_and(|p| p.is_file())
}

pub fn status(id: &str) -> Option<Status> {
    let state = load(id)?;
    let inst = instance::get(id).ok()?;
    Some(describe(&state, &inst))
}

fn describe(state: &State, inst: &Instance) -> Status {
    let suspects = state.suspects.len();
    Status {
        step: state.step,
        steps_left: if state.culprit.is_some() || state.not_found {
            0
        } else {
            (usize::BITS - suspects.saturating_sub(1).leading_zeros()).max(1)
        },
        total: state.candidates.len(),
        enabled: state.enabled.len(),
        suspects,
        culprit: state.culprit.as_ref().map(|pid| Culprit {
            project_id: pid.clone(),
            title: inst.mods.iter().find(|m| &m.project_id == pid).map_or_else(|| pid.clone(), |m| m.title.clone()),
        }),
        not_found: state.not_found,
    }
}

/// Starts a search in an instance that crashes with its current mods.
pub fn start(id: &str) -> Result<Status> {
    if is_active(id) {
        bail!("Поиск уже идёт");
    }
    let inst = instance::get(id)?;
    let candidates: Vec<String> = inst.mods.iter().filter(|m| !m.disabled).map(|m| m.project_id.clone()).collect();
    if candidates.len() < 2 {
        bail!("Включено меньше двух модов: искать не из чего");
    }
    let mut state = State {
        original: inst.mods.iter().map(|m| (m.project_id.clone(), m.disabled)).collect(),
        requires: dependencies(&inst, &candidates),
        suspects: candidates.clone(),
        candidates,
        ..State::default()
    };
    next_test(&mut state);
    apply(id, &state.enabled, &state.candidates)?;
    store(id, &state)?;
    Ok(describe(&state, &inst))
}

/// Records how the test launch went and prepares the next one.
pub fn report(id: &str, crashed: bool) -> Result<Status> {
    let mut state = load(id).ok_or_else(|| anyhow!("Поиск не запущен"))?;
    if state.culprit.is_some() || state.not_found {
        return Ok(describe(&state, &instance::get(id)?));
    }
    let enabled: HashSet<&String> = state.enabled.iter().collect();
    let before = state.suspects.len();
    if crashed {
        // The cause is among the mods that were on.
        state.suspects.retain(|s| enabled.contains(s));
        if state.suspects.len() == before && state.testing.len() == 1 {
            // A single mod (with what it needs) still crashes: that is it.
            state.suspects = state.testing.clone();
        }
    } else {
        state.suspects.retain(|s| !enabled.contains(s));
    }
    state.step += 1;
    match state.suspects.len() {
        0 => state.not_found = true,
        1 => state.culprit = state.suspects.first().cloned(),
        _ => next_test(&mut state),
    }
    if state.culprit.is_some() || state.not_found {
        // Done: back to the mods the player had, the result stays until they choose what to do.
        restore(id, &state)?;
    } else {
        apply(id, &state.enabled, &state.candidates)?;
    }
    store(id, &state)?;
    Ok(describe(&state, &instance::get(id)?))
}

/// Ends the search: every mod goes back to how it was, optionally with the culprit turned off.
pub fn finish(id: &str, disable_culprit: bool) -> Result<Instance> {
    let state = load(id).ok_or_else(|| anyhow!("Поиск не запущен"))?;
    restore(id, &state)?;
    if disable_culprit {
        if let Some(culprit) = &state.culprit {
            instance::set_mod_enabled(id, culprit, false)?;
        }
    }
    std::fs::remove_file(state_file(id)?)?;
    instance::get(id)
}

/// Picks the suspects to test: half of them, or fewer when their dependencies would pull in all.
fn next_test(state: &mut State) {
    let suspects: HashSet<&String> = state.suspects.iter().collect();
    let mut take = state.suspects.len().div_ceil(2);
    loop {
        let testing: Vec<String> = state.suspects[..take].to_vec();
        let enabled = closure(&testing, &state.requires);
        let covers_all = suspects.iter().all(|s| enabled.contains(*s));
        if !covers_all || take == 1 {
            state.testing = testing;
            state.enabled = enabled.into_iter().collect();
            return;
        }
        take = take.div_ceil(2).min(take - 1);
    }
}

/// The mods plus everything they need, recursively.
fn closure(mods: &[String], requires: &HashMap<String, Vec<String>>) -> BTreeSet<String> {
    let mut out: BTreeSet<String> = BTreeSet::new();
    let mut queue: Vec<&String> = mods.iter().collect();
    while let Some(m) = queue.pop() {
        if out.insert(m.clone()) {
            if let Some(deps) = requires.get(m) {
                queue.extend(deps.iter());
            }
        }
    }
    out
}

/// Turns the candidates on or off so that exactly `enabled` of them are on.
fn apply(id: &str, enabled: &[String], candidates: &[String]) -> Result<()> {
    let on: HashSet<&String> = enabled.iter().collect();
    let wanted: HashMap<&String, bool> = candidates.iter().map(|c| (c, !on.contains(c))).collect();
    set_disabled(id, |pid| wanted.get(&pid.to_string()).copied())
}

fn restore(id: &str, state: &State) -> Result<()> {
    let original: HashMap<&str, bool> = state.original.iter().map(|(p, d)| (p.as_str(), *d)).collect();
    set_disabled(id, |pid| original.get(pid).copied())
}

/// Renames mod files so each mod `disabled_for` has an answer for is on or off as asked.
fn set_disabled(id: &str, disabled_for: impl Fn(&str) -> Option<bool>) -> Result<()> {
    let mut inst = instance::get(id)?;
    let dir = inst.mods_dir();
    for m in &mut inst.mods {
        let Some(disabled) = disabled_for(&m.project_id) else { continue };
        if m.disabled == disabled {
            continue;
        }
        let from = dir.join(m.disk_name());
        m.disabled = disabled;
        let to = dir.join(m.disk_name());
        if from.is_file() {
            std::fs::rename(&from, &to)?;
        }
    }
    inst.save()
}

// ---------- Dependencies from the mod files ----------

#[derive(Default)]
struct ModMeta {
    /// Mod ids this file provides.
    provides: Vec<String>,
    /// Mod ids it cannot run without.
    requires: Vec<String>,
}

/// For each candidate, the other candidates it needs, read from the mods' own metadata.
fn dependencies(inst: &Instance, candidates: &[String]) -> HashMap<String, Vec<String>> {
    let dir = inst.mods_dir();
    let metas: Vec<(String, ModMeta)> = candidates
        .iter()
        .filter_map(|pid| {
            let m = inst.mods.iter().find(|m| &m.project_id == pid)?;
            Some((pid.clone(), read_meta(&dir.join(m.disk_name())).unwrap_or_default()))
        })
        .collect();
    let mut provider: HashMap<&str, &str> = HashMap::new();
    for (pid, meta) in &metas {
        for id in &meta.provides {
            provider.entry(id.as_str()).or_insert(pid.as_str());
        }
    }
    metas
        .iter()
        .map(|(pid, meta)| {
            let mut deps: Vec<String> = meta
                .requires
                .iter()
                .filter_map(|id| provider.get(id.as_str()))
                .filter(|dep| **dep != pid.as_str())
                .map(|dep| dep.to_string())
                .collect();
            deps.sort();
            deps.dedup();
            (pid.clone(), deps)
        })
        .collect()
}

fn read_entry(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let mut entry = archive.by_name(name).ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).ok()?;
    Some(text)
}

fn read_meta(path: &Path) -> Option<ModMeta> {
    let mut archive = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    if let Some(text) = read_entry(&mut archive, "fabric.mod.json") {
        return Some(fabric_meta(&text));
    }
    if let Some(text) = read_entry(&mut archive, "quilt.mod.json") {
        return Some(quilt_meta(&text));
    }
    for name in ["META-INF/neoforge.mods.toml", "META-INF/mods.toml"] {
        if let Some(text) = read_entry(&mut archive, name) {
            return Some(forge_meta(&text));
        }
    }
    None
}

fn strings(value: &serde_json::Value) -> Vec<String> {
    match value {
        serde_json::Value::String(s) => vec![s.clone()],
        serde_json::Value::Array(items) => items
            .iter()
            .filter_map(|v| v.as_str().or_else(|| v.get("id").and_then(|id| id.as_str())))
            .map(str::to_string)
            .collect(),
        _ => Vec::new(),
    }
}

fn fabric_meta(text: &str) -> ModMeta {
    // Some mods put raw line breaks inside strings, which strict JSON does not allow.
    let Ok(json) = serde_json::from_str::<serde_json::Value>(&text.replace(['\n', '\r', '\t'], " ")) else {
        return ModMeta::default();
    };
    let mut provides = strings(&json["provides"]);
    if let Some(id) = json["id"].as_str() {
        provides.push(id.to_string());
    }
    let requires = json["depends"].as_object().map(|o| o.keys().cloned().collect()).unwrap_or_default();
    ModMeta { provides, requires }
}

fn quilt_meta(text: &str) -> ModMeta {
    let Ok(json) = serde_json::from_str::<serde_json::Value>(text) else { return ModMeta::default() };
    let loader = &json["quilt_loader"];
    let mut provides = strings(&loader["provides"]);
    if let Some(id) = loader["id"].as_str() {
        provides.push(id.to_string());
    }
    let requires = match &loader["depends"] {
        serde_json::Value::Array(items) => items
            .iter()
            .filter(|d| !d.get("optional").and_then(|o| o.as_bool()).unwrap_or(false))
            .filter_map(|d| d.as_str().or_else(|| d.get("id").and_then(|id| id.as_str())))
            .map(str::to_string)
            .collect(),
        _ => Vec::new(),
    };
    ModMeta { provides, requires }
}

/// Reads `[[mods]] modId` and required `[[dependencies.x]]` entries from a (Neo)Forge mods.toml.
fn forge_meta(text: &str) -> ModMeta {
    let mut meta = ModMeta::default();
    let mut section = String::new();
    let mut dep_id: Option<String> = None;
    let mut dep_required = false;
    let flush = |meta: &mut ModMeta, dep_id: &mut Option<String>, required: &mut bool| {
        if let Some(id) = dep_id.take() {
            if *required {
                meta.requires.push(id);
            }
        }
        *required = false;
    };
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') {
            flush(&mut meta, &mut dep_id, &mut dep_required);
            section = line.trim_matches(|c| c == '[' || c == ']').trim().to_string();
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        let key = key.trim();
        let value = value.split('#').next().unwrap_or("").trim().trim_matches(|c| c == '"' || c == '\'');
        if section == "mods" && key == "modId" {
            meta.provides.push(value.to_string());
        } else if section.starts_with("dependencies") {
            match key {
                "modId" => dep_id = Some(value.to_string()),
                "mandatory" => dep_required = value == "true",
                "type" => dep_required = value.eq_ignore_ascii_case("required"),
                _ => {}
            }
        }
    }
    flush(&mut meta, &mut dep_id, &mut dep_required);
    meta
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_forge_dependencies() {
        let meta = forge_meta(
            r#"
            modLoader="javafml"
            [[mods]]
            modId="create"
            [[dependencies.create]]
                modId="flywheel"
                mandatory=true
            [[dependencies.create]]
                modId="jei" # optional
                type="optional"
            "#,
        );
        assert_eq!(meta.provides, vec!["create"]);
        assert_eq!(meta.requires, vec!["flywheel"]);
    }

    #[test]
    fn reads_fabric_dependencies() {
        let meta = fabric_meta(r#"{"id": "sodium-extra", "provides": ["x"], "depends": {"sodium": "*", "minecraft": "1.21.1"}}"#);
        assert!(meta.provides.contains(&"sodium-extra".to_string()));
        assert!(meta.requires.contains(&"sodium".to_string()));
    }

    #[test]
    fn halving_keeps_dependencies_together() {
        let mut state = State {
            suspects: vec!["a".into(), "b".into(), "c".into(), "d".into()],
            requires: HashMap::from([("a".to_string(), vec!["d".to_string()])]),
            ..State::default()
        };
        next_test(&mut state);
        assert_eq!(state.testing, vec!["a", "b"]);
        assert_eq!(state.enabled, vec!["a", "b", "d"]);
    }
}
