fn main() {
    // The CurseForge key is baked in with option_env!, so rebuild when it changes.
    println!("cargo:rerun-if-env-changed=CURSEFORGE_API_KEY");
    println!("cargo:rerun-if-env-changed=MS_CLIENT_ID");
    println!("cargo:rerun-if-env-changed=DISCORD_CLIENT_ID");
    embed_ui_mods();
    tauri_build::build()
}

/// Embeds the in-game menu mod jars that CI built into mod/<loader>-<version>/build/libs.
/// Writes `gameui_jars.rs` with one entry per target ("fabric-1.21.1", "forge-1.20.1"…); a
/// target that was not built is simply missing, which turns the menus off for it.
fn embed_ui_mods() {
    let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
    println!("cargo:rerun-if-changed=../mod");
    let mut entries = Vec::new();
    if let Ok(projects) = std::fs::read_dir("../mod") {
        for project in projects.flatten() {
            let target = project.file_name().to_string_lossy().into_owned();
            let Ok(libs) = std::fs::read_dir(project.path().join("build").join("libs")) else { continue };
            let prefix = format!("blockyard-ui-{target}-");
            let jar = libs.flatten().map(|e| e.path()).find(|p| {
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
                name.starts_with(&prefix) && name.ends_with(".jar") && !name.contains("sources") && !name.contains("dev")
            });
            if let Some(jar) = jar {
                let copy = out_dir.join(format!("blockyard-ui-{target}.jar"));
                std::fs::copy(&jar, &copy).unwrap();
                entries.push(format!("    ({target:?}, include_bytes!({:?})),", copy.to_string_lossy()));
            }
        }
    }
    entries.sort();
    let code = format!("/// (target, jar) for every build of the menu mod.\npub static JARS: &[(&str, &[u8])] = &[\n{}\n];\n", entries.join("\n"));
    std::fs::write(out_dir.join("gameui_jars.rs"), code).unwrap();
}
