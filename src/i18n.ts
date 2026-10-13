/**
 * Interface language. Strings are written in Russian in the code and looked up in the English
 * dictionary by that text; anything missing there simply stays Russian. `{name}` placeholders
 * are filled from `vars`.
 */
import { en } from "./locales/en";

export type Language = "ru" | "en";

let current: Language = "ru";

export function setLanguage(language: Language) {
  current = language === "en" ? "en" : "ru";
  document.documentElement.lang = current;
}

export function language(): Language {
  return current;
}

/** Locale for dates and numbers. */
export function locale(): string {
  return current === "en" ? "en-US" : "ru-RU";
}

/** Launch stages come from the backend, some with a name or version appended ("Скачивание Java 21"). */
const STAGE_PREFIXES = ["Скачивание Java", "Распаковка Java", "Скачивание", "Установка", "Библиотеки"];

export function tStage(stage: string): string {
  if (current !== "en" || en[stage]) return t(stage);
  const prefix = STAGE_PREFIXES.find((p) => stage.startsWith(`${p} `));
  // Looked up as "Установка …", so the word alone can mean something else elsewhere.
  return prefix ? `${t(`${prefix} …`)} ${stage.slice(prefix.length + 1)}` : stage;
}

export function t(ru: string, vars?: Record<string, string | number>): string {
  const text = current === "en" ? (en[ru] ?? ru) : ru;
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (match, key: string) => (key in vars ? String(vars[key]) : match));
}
