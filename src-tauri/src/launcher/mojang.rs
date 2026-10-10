//! Vanilla Minecraft metadata: version manifest, version JSON, libraries, assets.

use super::http::{self, Download};
use super::{paths, report, Reporter};
use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const LIBRARIES_URL: &str = "https://libraries.minecraft.net/";
const RESOURCES_URL: &str = "https://resources.download.minecraft.net/";

// ---------- Manifest ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    pub release_time: String,
    pub sha1: String,
}

pub async fn fetch_manifest() -> Result<VersionManifest> {
    http::get_json(MANIFEST_URL).await
}

// ---------- Version JSON ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(default)]
    pub inherits_from: Option<String>,
    pub main_class: String,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    #[serde(default)]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default)]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(default)]
    pub downloads: Option<VersionDownloads>,
    #[serde(default)]
    pub java_version: Option<JavaVersion>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    /// Version whose client jar is used (the vanilla parent for mod loaders).
    #[serde(skip)]
    pub client_jar_id: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub os: Option<OsRule>,
    #[serde(default)]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OsRule {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub arch: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    /// Maven repository base URL (used by Fabric libraries).
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
    /// Legacy natives: OS name -> classifier key.
    #[serde(default)]
    pub natives: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Artifact>,
    #[serde(default)]
    pub classifiers: Option<HashMap<String, Artifact>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub url: String,
    pub sha1: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionDownloads {
    pub client: Artifact,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub major_version: u32,
}

#[derive(Debug, Deserialize)]
struct AssetIndex {
    objects: HashMap<String, AssetObject>,
    #[serde(default, rename = "virtual")]
    is_virtual: bool,
    #[serde(default)]
    map_to_resources: bool,
}

#[derive(Debug, Deserialize)]
struct AssetObject {
    hash: String,
}

// ---------- Rules ----------

/// Evaluates Mojang rules for this machine. All optional "features" are treated as off.
pub fn rules_allow(rules: &[Rule]) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        let os_matches = rule.os.as_ref().is_none_or(|os| {
            let name_ok = os.name.as_deref().is_none_or(|n| n == paths::os_name());
            let arch_ok = os.arch.as_deref().is_none_or(|a| a == "x86" && cfg!(target_arch = "x86"));
            name_ok && arch_ok
        });
        let features_match = rule
            .features
            .as_ref()
            .is_none_or(|f| f.values().all(|enabled| !enabled));
        if os_matches && features_match {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

// ---------- Libraries ----------

/// Converts "group:artifact:version[:classifier][@ext]" to a repository path.
pub fn maven_path(name: &str) -> Option<String> {
    let (coords, ext) = name.split_once('@').unwrap_or((name, "jar"));
    let parts: Vec<&str> = coords.split(':').collect();
    if parts.len() < 3 {
        return None;
    }
    let (group, artifact, version) = (parts[0].replace('.', "/"), parts[1], parts[2]);
    let file = match parts.get(3) {
        Some(classifier) => format!("{artifact}-{version}-{classifier}.{ext}"),
        None => format!("{artifact}-{version}.{ext}"),
    };
    Some(format!("{group}/{artifact}/{version}/{file}"))
}

/// Key used to drop duplicate libraries: group, artifact and classifier, without version.
fn library_key(name: &str) -> String {
    let parts: Vec<&str> = name.split('@').next().unwrap_or(name).split(':').collect();
    match parts.as_slice() {
        [g, a, _, c, ..] => format!("{g}:{a}:{c}"),
        [g, a, ..] => format!("{g}:{a}"),
        _ => name.to_string(),
    }
}

/// Native jars for other CPU architectures share the OS rule, so filter them by classifier.
fn classifier_matches_arch(classifier: &str) -> bool {
    let arch = std::env::consts::ARCH;
    if classifier.contains("arm64") || classifier.contains("aarch") {
        arch == "aarch64"
    } else if classifier.ends_with("-x86") {
        arch == "x86"
    } else {
        true
    }
}

#[derive(Debug, Clone)]
pub struct ResolvedLibrary {
    pub path: PathBuf,
    pub download: Option<Download>,
    /// Goes on the classpath.
    pub classpath: bool,
    /// Native library jar that must be extracted.
    pub native: bool,
}

pub fn resolve_libraries(version: &VersionJson) -> Vec<ResolvedLibrary> {
    let libs_dir = paths::libraries_dir();
    let mut out = Vec::new();

    for lib in &version.libraries {
        if !rules_allow(lib.rules.as_deref().unwrap_or(&[])) {
            continue;
        }
        let classifier = lib.name.split('@').next().unwrap_or("").split(':').nth(3);
        if let Some(c) = classifier {
            if !classifier_matches_arch(c) {
                continue;
            }
        }
        let is_native_jar = classifier.is_some_and(|c| c.starts_with("natives-"));

        // Main artifact.
        let artifact = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref());
        let has_legacy_natives = lib.natives.is_some();
        if let Some(art) = artifact {
            if let Some(rel) = art.path.clone().or_else(|| maven_path(&lib.name)) {
                let path = libs_dir.join(&rel);
                let download = (!art.url.is_empty()).then(|| Download {
                    url: art.url.clone(),
                    path: path.clone(),
                    sha1: art.sha1.clone(),
                });
                out.push(ResolvedLibrary { path, download, classpath: true, native: is_native_jar });
            }
        } else if !has_legacy_natives || lib.downloads.is_none() {
            if let Some(rel) = maven_path(&lib.name) {
                let base = lib.url.as_deref().unwrap_or(LIBRARIES_URL);
                let base = if base.ends_with('/') { base.to_string() } else { format!("{base}/") };
                let path = libs_dir.join(&rel);
                out.push(ResolvedLibrary {
                    download: Some(Download { url: format!("{base}{rel}"), path: path.clone(), sha1: lib.sha1.clone() }),
                    path,
                    classpath: true,
                    native: is_native_jar,
                });
            }
        }

        // Legacy natives (pre-1.19): a classifier per OS, extracted but not on the classpath.
        if let Some(natives) = &lib.natives {
            let Some(key) = natives.get(paths::os_name()) else { continue };
            let bits = if cfg!(target_pointer_width = "64") { "64" } else { "32" };
            let key = key.replace("${arch}", bits);
            let art = lib
                .downloads
                .as_ref()
                .and_then(|d| d.classifiers.as_ref())
                .and_then(|c| c.get(&key));
            if let Some(art) = art {
                let rel = art.path.clone().or_else(|| maven_path(&format!("{}:{key}", lib.name)));
                if let Some(rel) = rel {
                    let path = libs_dir.join(rel);
                    out.push(ResolvedLibrary {
                        download: Some(Download { url: art.url.clone(), path: path.clone(), sha1: art.sha1.clone() }),
                        path,
                        classpath: false,
                        native: true,
                    });
                }
            }
        }
    }
    out
}

/// Extracts native libraries (.dll/.so/.dylib) from native jars into a flat directory.
pub fn extract_natives(libs: &[ResolvedLibrary], dir: &Path) -> Result<()> {
    std::fs::create_dir_all(dir)?;
    for lib in libs.iter().filter(|l| l.native) {
        let file = std::fs::File::open(&lib.path)
            .with_context(|| format!("missing native jar {}", lib.path.display()))?;
        let mut zip = zip::ZipArchive::new(file)?;
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i)?;
            let name = entry.name().to_string();
            if entry.is_dir() || name.starts_with("META-INF/") {
                continue;
            }
            let is_native = [".dll", ".so", ".dylib", ".jnilib"].iter().any(|ext| name.ends_with(ext));
            if !is_native {
                continue;
            }
            let Some(file_name) = Path::new(&name).file_name() else { continue };
            let mut buf = Vec::new();
            entry.read_to_end(&mut buf)?;
            std::fs::write(dir.join(file_name), buf)?;
        }
    }
    Ok(())
}

// ---------- Installing ----------

fn version_json_path(id: &str) -> PathBuf {
    paths::versions_dir().join(id).join(format!("{id}.json"))
}

pub fn client_jar_path(id: &str) -> PathBuf {
    paths::versions_dir().join(id).join(format!("{id}.jar"))
}

pub fn read_version_json(path: &Path) -> Result<VersionJson> {
    let text = std::fs::read_to_string(path)?;
    let mut v: VersionJson = serde_json::from_str(&text)
        .with_context(|| format!("invalid version file {}", path.display()))?;
    v.client_jar_id = v.inherits_from.clone().unwrap_or_else(|| v.id.clone());
    Ok(v)
}

/// Downloads (if needed) and parses a vanilla version JSON.
pub async fn load_vanilla(id: &str) -> Result<VersionJson> {
    let path = version_json_path(id);
    if !path.is_file() {
        let manifest = fetch_manifest().await?;
        let entry = manifest
            .versions
            .iter()
            .find(|v| v.id == id)
            .ok_or_else(|| anyhow!("Minecraft version {id} not found"))?;
        http::download(&Download { url: entry.url.clone(), path: path.clone(), sha1: Some(entry.sha1.clone()) }).await?;
    }
    read_version_json(&path)
}

/// Combines a mod loader profile with its vanilla parent.
pub fn merge(parent: VersionJson, child: VersionJson) -> VersionJson {
    let mut seen = HashSet::new();
    let libraries = child
        .libraries
        .into_iter()
        .chain(parent.libraries)
        .filter(|l| seen.insert(library_key(&l.name)))
        .collect();

    let arguments = match (parent.arguments, child.arguments) {
        (Some(mut p), Some(c)) => {
            p.game.extend(c.game);
            p.jvm.extend(c.jvm);
            Some(p)
        }
        (p, c) => c.or(p),
    };

    VersionJson {
        id: child.id,
        inherits_from: None,
        main_class: child.main_class,
        arguments,
        minecraft_arguments: child.minecraft_arguments.or(parent.minecraft_arguments),
        libraries,
        asset_index: child.asset_index.or(parent.asset_index),
        assets: child.assets.or(parent.assets),
        downloads: child.downloads.or(parent.downloads),
        java_version: child.java_version.or(parent.java_version),
        kind: parent.kind,
        client_jar_id: parent.client_jar_id,
    }
}

/// Where legacy versions expect their assets, if not the normal objects store.
#[derive(Debug, Clone)]
pub enum AssetLayout {
    Normal,
    /// Pre-1.7 "virtual" layout: files copied under assets/virtual/<index>.
    Virtual(PathBuf),
    /// Very old versions read from <game dir>/resources.
    Resources,
}

/// Downloads the client jar, libraries and assets; extracts natives.
pub async fn install_files(
    version: &VersionJson,
    natives_dir: &Path,
    game_dir: &Path,
    reporter: &Reporter,
) -> Result<AssetLayout> {
    // Client jar.
    if let Some(dl) = &version.downloads {
        report(reporter, "Скачивание игры", 0, 1);
        http::download(&Download {
            url: dl.client.url.clone(),
            path: client_jar_path(&version.client_jar_id),
            sha1: dl.client.sha1.clone(),
        })
        .await?;
    }

    // Libraries.
    let libs = resolve_libraries(version);
    let downloads = libs.iter().filter_map(|l| l.download.clone()).collect();
    http::download_all(downloads, "Скачивание библиотек", reporter).await?;
    report(reporter, "Распаковка нативных библиотек", 0, 1);
    let libs_for_natives = libs.clone();
    let natives_dir_owned = natives_dir.to_path_buf();
    tokio::task::spawn_blocking(move || extract_natives(&libs_for_natives, &natives_dir_owned)).await??;

    // Assets.
    let Some(index_ref) = &version.asset_index else { return Ok(AssetLayout::Normal) };
    let assets = paths::assets_dir();
    let index_path = assets.join("indexes").join(format!("{}.json", index_ref.id));
    http::download(&Download { url: index_ref.url.clone(), path: index_path.clone(), sha1: Some(index_ref.sha1.clone()) })
        .await?;
    let index: AssetIndex = serde_json::from_str(&std::fs::read_to_string(&index_path)?)?;

    let object_path = |hash: &str| assets.join("objects").join(&hash[..2]).join(hash);
    let mut unique = HashSet::new();
    let downloads = index
        .objects
        .values()
        .filter(|o| unique.insert(o.hash.clone()))
        .map(|o| Download {
            url: format!("{RESOURCES_URL}{}/{}", &o.hash[..2], o.hash),
            path: object_path(&o.hash),
            sha1: Some(o.hash.clone()),
        })
        .collect();
    http::download_all(downloads, "Скачивание ресурсов", reporter).await?;

    let target = if index.map_to_resources {
        Some((game_dir.join("resources"), AssetLayout::Resources))
    } else if index.is_virtual {
        let dir = assets.join("virtual").join(&index_ref.id);
        Some((dir.clone(), AssetLayout::Virtual(dir)))
    } else {
        None
    };
    let Some((dir, layout)) = target else { return Ok(AssetLayout::Normal) };
    for (name, obj) in &index.objects {
        let dest = dir.join(name);
        if dest.is_file() {
            continue;
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::copy(object_path(&obj.hash), dest)?;
    }
    Ok(layout)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven_paths() {
        assert_eq!(
            maven_path("net.fabricmc:fabric-loader:0.16.0").unwrap(),
            "net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar"
        );
        assert_eq!(
            maven_path("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap(),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"
        );
        assert_eq!(maven_path("a.b:c:1@zip").unwrap(), "a/b/c/1/c-1.zip");
    }

    #[test]
    fn rules() {
        let allow_this_os: Rule = serde_json::from_value(serde_json::json!({
            "action": "allow", "os": { "name": paths::os_name() }
        }))
        .unwrap();
        let disallow_this_os = Rule { action: "disallow".into(), ..allow_this_os.clone() };
        let allow_all = Rule { action: "allow".into(), os: None, features: None };
        let demo: Rule = serde_json::from_value(serde_json::json!({
            "action": "allow", "features": { "is_demo_user": true }
        }))
        .unwrap();

        assert!(rules_allow(&[]));
        assert!(rules_allow(&[allow_this_os]));
        assert!(!rules_allow(&[allow_all, disallow_this_os]));
        assert!(!rules_allow(&[demo]));
    }

    #[test]
    fn library_dedupe_keeps_classifiers_apart() {
        assert_ne!(library_key("org.lwjgl:lwjgl:3.3.3"), library_key("org.lwjgl:lwjgl:3.3.3:natives-windows"));
        assert_eq!(library_key("org.ow2.asm:asm:9.6"), library_key("org.ow2.asm:asm:9.7"));
    }
}
