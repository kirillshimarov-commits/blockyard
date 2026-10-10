import { invoke } from "@tauri-apps/api/core";

export type Loader = { type: "vanilla" } | { type: "fabric"; version: string };

export interface InstalledMod {
  projectId: string;
  versionId: string;
  title: string;
  fileName: string;
  iconUrl?: string | null;
}

export interface Instance {
  id: string;
  name: string;
  gameVersion: string;
  loader: Loader;
  memoryMb: number;
  createdAt: number;
  lastPlayed?: number | null;
  mods: InstalledMod[];
}

export interface Settings {
  username: string;
  memoryMb: number;
}

export interface McVersion {
  id: string;
  type: "release" | "snapshot" | "old_beta" | "old_alpha";
  releaseTime: string;
}

export interface FabricLoader {
  version: string;
  stable: boolean;
}

export interface ModHit {
  project_id: string;
  slug: string;
  title: string;
  description: string;
  author: string;
  icon_url?: string | null;
  downloads: number;
  categories: string[];
}

export interface SearchResult {
  hits: ModHit[];
  total_hits: number;
  offset: number;
}

export type GameState = "installing" | "running" | "stopped";

export interface ProgressEvent {
  instanceId: string;
  stage: string;
  current: number;
  total: number;
}

export interface StateEvent {
  instanceId: string;
  state: GameState;
  error?: string | null;
}

export interface LogEvent {
  instanceId: string;
  line: string;
}

export const api = {
  getSettings: () => invoke<Settings>("get_settings"),
  saveSettings: (settings: Settings) => invoke<void>("save_settings", { settings }),
  listVersions: () => invoke<McVersion[]>("list_versions"),
  listFabricLoaders: (gameVersion: string) => invoke<FabricLoader[]>("list_fabric_loaders", { gameVersion }),
  listInstances: () => invoke<Instance[]>("list_instances"),
  getInstance: (id: string) => invoke<Instance>("get_instance", { id }),
  createInstance: (name: string, gameVersion: string, loader: Loader) =>
    invoke<Instance>("create_instance", { name, gameVersion, loader }),
  deleteInstance: (id: string) => invoke<void>("delete_instance", { id }),
  setInstanceMemory: (id: string, memoryMb: number) => invoke<Instance>("set_instance_memory", { id, memoryMb }),
  instanceFolder: (id: string) => invoke<string>("instance_folder", { id }),
  runningInstances: () => invoke<string[]>("running_instances"),
  launch: (id: string) => invoke<void>("launch_instance", { id }),
  stop: (id: string) => invoke<void>("stop_instance", { id }),
  searchMods: (query: string, gameVersion: string | null, loader: string | null, sort: string, offset: number) =>
    invoke<SearchResult>("search_mods", { query, gameVersion, loader, sort, offset }),
  installMod: (instanceId: string, projectId: string) =>
    invoke<{ instance: Instance; installed: string[] }>("install_mod", { instanceId, projectId }),
  removeMod: (instanceId: string, projectId: string) => invoke<Instance>("remove_mod", { instanceId, projectId }),
};

export function loaderLabel(loader: Loader): string {
  return loader.type === "fabric" ? `Fabric ${loader.version}` : "Vanilla";
}

export function formatNumber(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, "")} млн`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, "")} тыс.`;
  return String(n);
}

export function errorText(e: unknown): string {
  return typeof e === "string" ? e : e instanceof Error ? e.message : String(e);
}
