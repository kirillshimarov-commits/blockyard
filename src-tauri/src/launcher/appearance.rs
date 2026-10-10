//! UI appearance settings. The frontend owns the format; the backend only stores it
//! and reads the window effect and theme so they can be applied before the window shows.

use super::paths;
use anyhow::Result;
use serde_json::Value;

fn file() -> std::path::PathBuf {
    paths::data_dir().join("appearance.json")
}

pub fn load() -> Value {
    std::fs::read_to_string(file())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or(Value::Object(Default::default()))
}

pub fn save(value: &Value) -> Result<()> {
    std::fs::create_dir_all(paths::data_dir())?;
    std::fs::write(file(), serde_json::to_string(value)?)?;
    Ok(())
}

pub fn field<'a>(value: &'a Value, key: &str, default: &'a str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or(default)
}

/// Windows 11 starts at build 22000; Mica only exists there.
pub fn is_windows_11() -> bool {
    match os_info::get().version() {
        os_info::Version::Semantic(10, 0, build) => *build >= 22000,
        _ => false,
    }
}
