//! Finding and summarizing Minecraft crash reports after the game exits with an error.

use std::path::{Path, PathBuf};
use std::time::SystemTime;

#[derive(Debug)]
pub struct GameCrash {
    pub message: String,
    pub report: Option<PathBuf>,
    /// Plain-language explanations of what likely went wrong.
    pub hints: Vec<String>,
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

/// Text after `marker` up to the next quote, e.g. the mod id in `from mod 'sodium'`.
fn quoted_after<'a>(line: &'a str, marker: &str) -> Option<&'a str> {
    let rest = &line[line.find(marker)? + marker.len()..];
    let rest = rest.trim_start_matches(['\'', '"', ' ']);
    rest.split(['\'', '"', ' ', ')', ']']).next().filter(|s| !s.is_empty())
}

/// Recognizes common crash causes in a crash report and the game log.
pub fn analyze(text: &str) -> Vec<String> {
    let mut hints: Vec<String> = Vec::new();
    let mut add = |h: String| {
        if !hints.contains(&h) {
            hints.push(h);
        }
    };

    for line in text.lines() {
        let l = line.trim();
        // Fabric: "Mod 'X' (x) 1.0 requires version 2.0 or later of 'Y' (y), which is missing!"
        if l.contains("requires") && (l.contains("which is missing") || l.contains("but only the wrong version")) {
            if let (Some(a), Some(b)) = (l.split('\'').nth(1), l.split('\'').nth(3)) {
                add(format!("Мод «{a}» требует мод «{b}» (или другую его версию). Установите «{b}» через вкладку «Моды»."));
                continue;
            }
        }
        // Forge 1.13+: "Mod ID: 'y', Requested by: 'x'"
        if let (Some(dep), Some(by)) = (quoted_after(l, "Mod ID:"), quoted_after(l, "Requested by:")) {
            add(format!("Мод «{by}» требует мод «{dep}», которого нет в сборке."));
            continue;
        }
        if l.contains("Mixin apply") || l.contains("MixinApplyError") || l.contains("InvalidInjectionException") {
            match quoted_after(l, "from mod") {
                Some(m) => add(format!("Мод «{m}» несовместим с другим модом или с этой версией игры (ошибка Mixin). Попробуйте выключить его.")),
                None => add("Конфликт модов (ошибка Mixin). Выключайте недавно добавленные моды по одному, чтобы найти виновника.".into()),
            }
        }
    }

    let has = |needle: &str| text.contains(needle);
    if has("java.lang.OutOfMemoryError") || has("Java heap space") {
        add("Игре не хватило памяти. Увеличьте ОЗУ во вкладке «Настройки» сборки (для больших модпаков 6–8 ГБ).".into());
    }
    if has("UnsupportedClassVersionError") || has("Unsupported class file major version") {
        add("Мод собран под более новую Java, чем требует эта версия игры. Скорее всего, мод не для этой версии Minecraft.".into());
    }
    if has("DuplicateModsFoundException") || has("Duplicate mods found") || (has("Mod ID") && has("multiple times"))
        || has("Found duplicate mods")
    {
        add("В сборке есть дубликаты модов (один мод установлен дважды). Удалите лишнюю копию.".into());
    }
    if has("NoClassDefFoundError") || has("ClassNotFoundException") {
        add("Одному из модов не хватает другого мода (зависимости) или стоит неподходящая версия. Проверьте, что все моды скачаны и подходят к версии игры.".into());
    }
    if has("Pixel format not accelerated") || has("GLFW error 65542") || has("WGL: The driver does not appear to support OpenGL")
        || has("Couldn't set pixel format")
    {
        add("Проблема с драйвером видеокарты: обновите драйвер с сайта NVIDIA, AMD или Intel.".into());
    }
    if has("Incompatible mods found") || has("Incompatible mod set") {
        add("Загрузчик нашёл несовместимые моды. Подробности — в журнале, обычно там указано, какой мод нужно убрать или обновить.".into());
    }
    hints
}

pub fn describe(game_dir: &Path, since: SystemTime, exit_code: i32, log_tail: &str) -> GameCrash {
    let report = find_report(game_dir, since);
    let report_text = report.as_ref().and_then(|p| std::fs::read_to_string(p).ok());
    let summary = report_text.as_deref().and_then(summarize);
    let hints = analyze(&format!("{}\n{log_tail}", report_text.as_deref().unwrap_or_default()));
    let message = match summary {
        Some(s) => format!("Игра вылетела: {s}"),
        None => format!("Игра завершилась с ошибкой (код {exit_code}). Подробности в журнале."),
    };
    GameCrash { message, report, hints }
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

    #[test]
    fn explains_common_crashes() {
        let fabric = "Mod 'Iris' (iris) 1.8.0 requires version 0.6.0 or later of 'Sodium' (sodium), which is missing!";
        assert!(analyze(fabric)[0].contains("«Iris» требует мод «Sodium»"));

        let forge = "\tMod ID: 'geckolib', Requested by: 'alexsmobs', Expected range: '[4.0,)', Actual version: '[MISSING]'";
        assert!(analyze(forge)[0].contains("«alexsmobs» требует мод «geckolib»"));

        let oom = "java.lang.OutOfMemoryError: Java heap space";
        assert!(analyze(oom)[0].contains("памяти"));

        let mixin = "Mixin apply for mod 'sodium' failed sodium.mixins.json:core.MixinFoo from mod sodium -> net.minecraft.Foo";
        assert!(analyze(mixin).iter().any(|h| h.contains("«sodium»")));

        assert!(analyze("everything is fine").is_empty());
    }
}
