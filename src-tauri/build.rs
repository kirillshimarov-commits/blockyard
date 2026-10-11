fn main() {
    // The CurseForge key is baked in with option_env!, so rebuild when it changes.
    println!("cargo:rerun-if-env-changed=CURSEFORGE_API_KEY");
    println!("cargo:rerun-if-env-changed=MS_CLIENT_ID");
    embed_ui_mod();
    tauri_build::build()
}

/// Copies the in-game menu mod jar (built by CI into mod/build/libs) next to the build output,
/// or leaves an empty file when it was not built, which turns the feature off.
fn embed_ui_mod() {
    let libs = std::path::Path::new("../mod/build/libs");
    println!("cargo:rerun-if-changed=../mod/build/libs");
    let jar = std::fs::read_dir(libs).ok().and_then(|dir| {
        dir.flatten().map(|e| e.path()).find(|p| {
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            name.starts_with("blockyard-ui") && name.ends_with(".jar") && !name.contains("sources")
        })
    });
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("blockyard-ui.jar");
    let bytes = jar.and_then(|p| std::fs::read(p).ok()).unwrap_or_default();
    std::fs::write(out, bytes).unwrap();
}
