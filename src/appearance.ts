import { invoke } from "@tauri-apps/api/core";

export type ThemeMode = "system" | "dark" | "light";
export type WindowEffect = "auto" | "mica" | "acrylic" | "blur" | "none";
export type Background = "window" | "gradient" | "image";
export type Layout = "console" | "classic";

export interface Appearance {
  theme: ThemeMode;
  effect: WindowEffect;
  /** 0 = most see-through, 100 = solid. */
  opacity: number;
  accent: string;
  background: Background;
  gradient: string;
  /** Data URL of a downscaled JPEG. */
  backgroundImage: string;
  backgroundBlur: number;
  backgroundDim: number;
  radius: number;
  /** Percent. */
  scale: number;
  animations: boolean;
  compactSidebar: boolean;
  /** Console: top bar, tile row and big cards. Classic: sidebar. */
  layout: Layout;
  /** Animated starfield with light beams behind the console layout. */
  spaceScene: boolean;
  /** Intro animation when the launcher opens. */
  splash: boolean;
}

export const DEFAULT_APPEARANCE: Appearance = {
  theme: "system",
  effect: "auto",
  opacity: 45,
  accent: "#2fd17e",
  background: "window",
  gradient: "aurora",
  backgroundImage: "",
  backgroundBlur: 8,
  backgroundDim: 30,
  radius: 12,
  scale: 100,
  animations: true,
  compactSidebar: false,
  layout: "console",
  spaceScene: true,
  splash: true,
};

export const ACCENTS = [
  "#2fd17e",
  "#3ec7c2",
  "#3b8bff",
  "#6c5cff",
  "#a259ff",
  "#e85dd8",
  "#ff5c8a",
  "#ff5a4e",
  "#ff8a3d",
  "#f5c518",
  "#9ccc3d",
  "#9aa4b5",
];

export const GRADIENTS: Record<string, { label: string; css: string }> = {
  aurora: { label: "Аврора", css: "linear-gradient(135deg, #6a7cf0 0%, #b07cf0 45%, #f09cd4 100%)" },
  sunset: { label: "Закат", css: "linear-gradient(135deg, #ff7e5f 0%, #feb47b 50%, #ffcf8a 100%)" },
  ocean: { label: "Океан", css: "linear-gradient(135deg, #0f2027 0%, #203a43 50%, #2c5364 100%)" },
  forest: { label: "Лес", css: "linear-gradient(135deg, #134e5e 0%, #2f8f63 60%, #71b280 100%)" },
  candy: { label: "Конфета", css: "linear-gradient(135deg, #fbc2eb 0%, #c7b8f5 50%, #a6c1ee 100%)" },
  night: { label: "Ночь", css: "linear-gradient(135deg, #141e30 0%, #243b55 100%)" },
  lava: { label: "Лава", css: "linear-gradient(135deg, #3a0a0a 0%, #9b1c1c 55%, #ff6a3d 100%)" },
  mint: { label: "Мята", css: "linear-gradient(135deg, #d4fc79 0%, #96e6a1 100%)" },
};

export async function loadAppearance(): Promise<Appearance> {
  try {
    const saved = await invoke<Partial<Appearance>>("get_appearance");
    return { ...DEFAULT_APPEARANCE, ...saved };
  } catch {
    return DEFAULT_APPEARANCE;
  }
}

export function saveAppearance(a: Appearance): Promise<void> {
  return invoke("save_appearance", { value: a });
}

/** Applies the native window effect; returns the effect actually in use. */
export async function applyWindowStyle(a: Appearance): Promise<string> {
  try {
    return await invoke<string>("set_window_style", { effect: a.effect, theme: a.theme });
  } catch {
    return "none";
  }
}

function resolvedTheme(mode: ThemeMode): "dark" | "light" {
  if (mode !== "system") return mode;
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

/** Black or white text, whichever reads better on the color. */
function contrastText(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const v = c / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4 ? "#0b0d10" : "#ffffff";
}

/** Writes the appearance into CSS variables and data attributes on <html>. */
export function applyAppearance(a: Appearance, activeEffect: string) {
  const root = document.documentElement;
  const translucent = activeEffect !== "none" || a.background !== "window";
  const t = a.opacity / 100;

  root.dataset.theme = resolvedTheme(a.theme);
  root.dataset.translucent = String(translucent);
  root.dataset.animations = String(a.animations);
  root.dataset.compact = String(a.compactSidebar);
  root.dataset.layout = a.layout;
  root.dataset.space = String(a.layout === "console" && a.spaceScene);

  const set = (k: string, v: string) => root.style.setProperty(k, v);
  set("--accent", a.accent);
  set("--accent-text", contrastText(a.accent));
  set("--radius", `${a.radius}px`);
  set("--radius-sm", `${Math.round(a.radius * 0.75)}px`);
  // Window tint: how much of the effect or background shows through.
  set("--window-alpha", translucent ? (0.15 + t * 0.75).toFixed(2) : "1");
  set("--panel-alpha", translucent ? (0.35 + t * 0.6).toFixed(2) : "1");
  set("--bg-blur", `${a.backgroundBlur}px`);
  set("--bg-dim", (a.backgroundDim / 100).toFixed(2));
  root.style.fontSize = `${(14 * a.scale) / 100}px`;

  let bg = "none";
  if (a.background === "gradient") bg = GRADIENTS[a.gradient]?.css ?? GRADIENTS.aurora.css;
  if (a.background === "image" && a.backgroundImage) bg = `url("${a.backgroundImage}")`;
  set("--app-background", bg);
}

/** Downscales an image file to a JPEG data URL small enough to keep in settings. */
export function compressImage(file: File, maxSide = 1920): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      canvas.getContext("2d")!.drawImage(img, 0, 0, canvas.width, canvas.height);
      URL.revokeObjectURL(url);
      resolve(canvas.toDataURL("image/jpeg", 0.85));
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("Не удалось открыть картинку"));
    };
    img.src = url;
  });
}
