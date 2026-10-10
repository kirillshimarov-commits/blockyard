use std::path::PathBuf;

/// Root data directory: %APPDATA%\Blockyard on Windows, ~/.local/share/Blockyard on Linux.
pub fn data_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Blockyard")
}

pub fn versions_dir() -> PathBuf {
    data_dir().join("meta").join("versions")
}

pub fn libraries_dir() -> PathBuf {
    data_dir().join("meta").join("libraries")
}

pub fn assets_dir() -> PathBuf {
    data_dir().join("meta").join("assets")
}

pub fn runtimes_dir() -> PathBuf {
    data_dir().join("runtimes")
}

pub fn instances_dir() -> PathBuf {
    data_dir().join("instances")
}

pub fn settings_file() -> PathBuf {
    data_dir().join("settings.json")
}

/// Name Mojang uses for the current OS in library rules.
pub fn os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

pub fn classpath_separator() -> &'static str {
    if cfg!(target_os = "windows") {
        ";"
    } else {
        ":"
    }
}
