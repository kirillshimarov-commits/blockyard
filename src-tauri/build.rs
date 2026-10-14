fn main() {
    // The CurseForge key is baked in with option_env!, so rebuild when it changes.
    println!("cargo:rerun-if-env-changed=CURSEFORGE_API_KEY");
    println!("cargo:rerun-if-env-changed=MS_CLIENT_ID");
    println!("cargo:rerun-if-env-changed=DISCORD_CLIENT_ID");
    embed_ui_mods();
    tauri_build::build()
}

/// Embeds the in-game menu mod jars that CI built into mod/<loader>-<version>/build/libs.
/// Writes `gameui_jars.rs` with one entry per build: its loader, the game versions it is meant
/// for (`game_versions` in the project's gradle.properties) and the jar. A build that was not
/// made is simply missing, which turns the menus off for its versions.
fn embed_ui_mods() {
    let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
    println!("cargo:rerun-if-changed=../mod");
    let mut entries = Vec::new();
    if let Ok(projects) = std::fs::read_dir("../mod") {
        for project in projects.flatten() {
            let target = project.file_name().to_string_lossy().into_owned();
            let Some((loader, _)) = target.split_once('-') else { continue };
            let Ok(libs) = std::fs::read_dir(project.path().join("build").join("libs")) else { continue };
            let prefix = format!("blockyard-ui-{target}-");
            let jar = libs.flatten().map(|e| e.path()).find(|p| {
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
                name.starts_with(&prefix) && name.ends_with(".jar") && !name.contains("sources") && !name.contains("dev")
            });
            let Some(jar) = jar else { continue };
            let properties = std::fs::read_to_string(project.path().join("gradle.properties")).unwrap_or_default();
            let versions: Vec<String> = properties
                .lines()
                .find_map(|l| l.trim().strip_prefix("game_versions="))
                .map(|v| v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
                .unwrap_or_default();
            if versions.is_empty() {
                continue;
            }
            let copy = out_dir.join(format!("blockyard-ui-{target}.jar"));
            std::fs::copy(&jar, &copy).unwrap();
            entries.push(format!(
                "    Build {{ loader: {loader:?}, versions: &{versions:?}, jar: include_bytes!({:?}) }},",
                copy.to_string_lossy()
            ));
        }
    }
    entries.sort();
    let code = format!(
        "/// One build of the menu mod: the loader it is for (\"fabric\" also serves Quilt), the game\n\
         /// versions it is installed into, and the jar.\n\
         pub struct Build {{\n    pub loader: &'static str,\n    pub versions: &'static [&'static str],\n    pub jar: &'static [u8],\n}}\n\n\
         pub static BUILDS: &[Build] = &[\n{}\n];\n",
        entries.join("\n")
    );
    std::fs::write(out_dir.join("gameui_jars.rs"), code).unwrap();
}
