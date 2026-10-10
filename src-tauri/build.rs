fn main() {
    // The CurseForge key is baked in with option_env!, so rebuild when it changes.
    println!("cargo:rerun-if-env-changed=CURSEFORGE_API_KEY");
    tauri_build::build()
}
