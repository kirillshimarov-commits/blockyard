//! Finding and summarizing Minecraft crash reports after the game exits with an error.

use std::path::{Path, PathBuf};
use std::time::SystemTime;

#[derive(Debug)]
pub struct GameCrash {
    pub message: String,
    pub report: Option<PathBuf>,
}

impl std::fmt::Display for GameCrash {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for GameCrash {}

/// Newest crash report written since the game started, if any.
pub fn find_report(game_dir: &Path, since: SystemTime) -> Option<PathBuf> {
    std::fs::read_dir(game_dir.join("crash-reports"))
        .ok()?
        .flatten()
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .filter(|(modified, path)| *modified >= since && path.extension().is_some_and(|x| x == "txt"))
        .max_by_key(|(modified, _)| *modified)
        .map(|(_, path)| path)
}

/// "Description" line plus the first line of the exception that follows it.
pub fn summarize(text: &str) -> Option<String> {
    let mut lines = text.lines();
    let description = lines.find_map(|l| l.trim().strip_prefix("Description:"))?.trim().to_string();
    let exception = lines.map(str::trim).find(|l| !l.is_empty());
    Some(match exception {
        Some(e) => format!("{description}\n{e}"),
        None => description,
    })
}

pub fn describe(game_dir: &Path, since: SystemTime, exit_code: i32) -> GameCrash {
    let report = find_report(game_dir, since);
    let summary = report
        .as_ref()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|text| summarize(&text));
    let message = match summary {
        Some(s) => format!("Игра вылетела: {s}"),
        None => format!("Игра завершилась с ошибкой (код {exit_code}). Подробности в журнале."),
    };
    GameCrash { message, report }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summarizes_crash_report() {
        let text = "---- Minecraft Crash Report ----\n// Oops.\n\nTime: today\nDescription: Ticking entity\n\njava.lang.NullPointerException: Ticking entity\n\tat foo.Bar.baz(Bar.java:1)\n";
        assert_eq!(summarize(text).unwrap(), "Ticking entity\njava.lang.NullPointerException: Ticking entity");
        assert!(summarize("no report here").is_none());
    }
}
