import { invoke } from "@tauri-apps/api/core";
import { t } from "./i18n";

export type LoaderType = "fabric" | "quilt" | "forge" | "neoforge";
export type Loader = { type: "vanilla" } | { type: LoaderType; version: string };
export type ContentKind = "mod" | "shader" | "modpack" | "resourcepack";
export type Asset = "shader" | "resourcepack";
export type ContentFolder = "mods" | "shaderpacks" | "resourcepacks";

export type Source = "modrinth" | "curseforge";

export interface InstalledMod {
  source: Source;
  projectId: string;
  versionId: string;
  title: string;
  fileName: string;
  iconUrl?: string | null;
  disabled?: boolean;
}

export interface Instance {
  id: string;
  name: string;
  gameVersion: string;
  loader: Loader;
  memoryMb: number;
  createdAt: number;
  lastPlayed?: number | null;
  /** Time spent in the game, in seconds. */
  playSeconds?: number;
  mods: InstalledMod[];
  pending: PendingDownload[];
  shaders: InstalledMod[];
  resourcepacks: InstalledMod[];
  iconUrl?: string | null;
  modpack?: { source: Source; projectId: string; versionId: string; title: string } | null;
  settings: InstanceSettings;
}

export interface InstanceSettings {
  group: string;
  updateChannel: "" | "release" | "beta" | "alpha";
  javaPath: string;
  jvmArgs: string;
  envVars: string;
  fullscreen: boolean;
  width?: number | null;
  height?: number | null;
  preLaunch: string;
  wrapper: string;
  postExit: string;
  /** In-game menus: "" automatic, "on" Blockyard, "off" standard. */
  gameUi?: "" | "on" | "off";
}

export interface InstancePatch {
  name: string;
  iconUrl?: string | null;
  memoryMb: number;
  gameVersion: string;
  loader: Loader;
  settings: InstanceSettings;
}

export interface PendingDownload {
  source: Source;
  projectId: string;
  title: string;
  fileName: string;
  pageUrl: string;
  iconUrl?: string | null;
}

export interface Settings {
  username: string;
  memoryMb: number;
  /** Show the game inside the launcher window (Windows). */
  embedGame: boolean;
  /** Install the Blockyard UI mod (launcher-style main menu) where supported. */
  gameUi: boolean;
  /** Show the running game in the Discord profile. */
  discord: boolean;
  /** UUID of the Microsoft account in use; offline when empty. */
  activeAccount?: string | null;
}

export interface Account {
  uuid: string;
  username: string;
}

export interface DeviceCode {
  userCode: string;
  deviceCode: string;
  verificationUri: string;
  interval: number;
  expiresIn: number;
}

export interface ModUpdate {
  source: Source;
  projectId: string;
  title: string;
  currentFile: string;
  newFile: string;
}

export interface McVersion {
  id: string;
  type: "release" | "snapshot" | "old_beta" | "old_alpha";
  releaseTime: string;
}

export interface LoaderVersion {
  version: string;
  stable: boolean;
  recommended: boolean;
}

export interface ModHit {
  source: Source;
  projectId: string;
  title: string;
  description: string;
  author: string;
  iconUrl?: string | null;
  downloads: number;
  categories: string[];
  pageUrl: string;
}

export interface ModPage {
  source: Source;
  projectId: string;
  title: string;
  summary: string;
  author: string;
  iconUrl?: string | null;
  downloads: number;
  followers?: number | null;
  categories: string[];
  pageUrl: string;
  /** Markdown (Modrinth) or HTML (CurseForge, `bodyHtml`). */
  body: string;
  bodyHtml: boolean;
  gallery: { url: string; thumbnail: string; title?: string | null }[];
  links: { kind: "website" | "source" | "issues" | "wiki" | "discord"; url: string }[];
  license?: string | null;
  updated?: string | null;
  versions: {
    name: string;
    gameVersions: string[];
    loaders: string[];
    releaseType: "release" | "beta" | "alpha";
    date: string;
    compatible: boolean;
  }[];
  dependencies: {
    projectId: string;
    title: string;
    iconUrl?: string | null;
    kind: "required" | "optional" | "incompatible" | "embedded";
  }[];
}

export interface SearchResult {
  hits: ModHit[];
  totalHits: number;
}

export interface InstallResult {
  instance: Instance;
  installed: string[];
  pending: PendingDownload[];
  warnings: string[];
}

export interface News {
  title: string;
  version: string;
  kind: string;
  image?: string | null;
  text: string;
  date: string;
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
  crashReport?: string | null;
  hints?: string[];
}

export interface LinkInfo {
  id: string;
  rttMs?: number | null;
  relayed: boolean;
}

export interface InviteInfo {
  world: string;
  gameVersion: string;
  loader: Loader;
}

export interface TunnelStatus {
  lanPorts: Record<string, number>;
  host?: { instanceId: string; invite: string; peers: LinkInfo[] } | null;
  guest?: { info: InviteInfo; connected: boolean; link: LinkInfo } | null;
}

export interface LauncherUpdate {
  version: string;
  notes: string;
  url: string;
}

export interface BisectStatus {
  step: number;
  stepsLeft: number;
  total: number;
  enabled: number;
  suspects: number;
  culprit?: { projectId: string; title: string } | null;
  notFound: boolean;
}

export interface LogEvent {
  instanceId: string;
  line: string;
}

export const api = {
  getSettings: () => invoke<Settings>("get_settings"),
  saveSettings: (settings: Settings) => invoke<void>("save_settings", { settings }),
  listVersions: () => invoke<McVersion[]>("list_versions"),
  listLoaderVersions: (loader: LoaderType, gameVersion: string) =>
    invoke<LoaderVersion[]>("list_loader_versions", { loader, gameVersion }),
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
  curseforgeAvailable: () => invoke<boolean>("curseforge_available"),
  discordAvailable: () => invoke<boolean>("discord_available"),
  minecraftNews: () => invoke<News[]>("minecraft_news"),
  gameUiAvailable: () => invoke<boolean>("game_ui_available"),
  search: (
    kind: ContentKind,
    source: Source,
    query: string,
    gameVersion: string | null,
    loader: string | null,
    sort: string,
    offset: number,
  ) => invoke<SearchResult>("search_mods", { kind, source, query, gameVersion, loader, sort, offset }),
  modPage: (source: Source, projectId: string, gameVersion: string | null, loader: string | null) =>
    invoke<ModPage>("mod_page", { source, projectId, gameVersion, loader }),
  installMod: (instanceId: string, source: Source, projectId: string) =>
    invoke<InstallResult>("install_mod", { instanceId, source, projectId }),
  installAsset: (instanceId: string, source: Source, projectId: string, asset: Asset) =>
    invoke<InstallResult>("install_asset", { instanceId, source, projectId, asset }),
  removeContent: (instanceId: string, folder: ContentFolder, projectId: string) =>
    invoke<Instance>("remove_content", { instanceId, folder, projectId }),
  checkModUpdates: (instanceId: string) => invoke<ModUpdate[]>("check_mod_updates", { instanceId }),
  applyModUpdates: (instanceId: string, projectIds: string[]) =>
    invoke<Instance>("apply_mod_updates", { instanceId, projectIds }),
  microsoftAvailable: () => invoke<boolean>("microsoft_available"),
  listAccounts: () => invoke<Account[]>("list_accounts"),
  startMicrosoftLogin: () => invoke<DeviceCode>("start_microsoft_login"),
  finishMicrosoftLogin: (code: DeviceCode) => invoke<Account>("finish_microsoft_login", { code }),
  removeAccount: (uuid: string) => invoke<Settings>("remove_account", { uuid }),
  installModpack: (source: Source, projectId: string) => invoke<Instance>("install_modpack", { source, projectId }),
  importModpack: (path: string) => invoke<Instance>("import_modpack", { path }),
  exportInstance: (instanceId: string, path: string) => invoke<void>("export_instance", { instanceId, path }),
  duplicateInstance: (id: string) => invoke<Instance>("duplicate_instance", { id }),
  saveInstanceSettings: (id: string, patch: InstancePatch) => invoke<Instance>("save_instance_settings", { id, patch }),
  repairInstance: (id: string) => invoke<void>("repair_instance", { id }),
  testJava: (path: string) => invoke<string>("test_java", { path }),
  setModEnabled: (instanceId: string, projectId: string, enabled: boolean) =>
    invoke<Instance>("set_mod_enabled", { instanceId, projectId, enabled }),
  bisectStatus: (instanceId: string) => invoke<BisectStatus | null>("bisect_status", { instanceId }),
  bisectStart: (instanceId: string) => invoke<BisectStatus>("bisect_start", { instanceId }),
  bisectReport: (instanceId: string, crashed: boolean) => invoke<BisectStatus>("bisect_report", { instanceId, crashed }),
  bisectFinish: (instanceId: string, disableCulprit: boolean) =>
    invoke<Instance>("bisect_finish", { instanceId, disableCulprit }),
  launcherVersion: () => invoke<string>("launcher_version"),
  checkLauncherUpdate: () => invoke<LauncherUpdate | null>("check_launcher_update"),
  installLauncherUpdate: (url: string) => invoke<void>("install_launcher_update", { url }),
  tunnelStatus: () => invoke<TunnelStatus>("tunnel_status"),
  tunnelInvite: (instanceId: string) => invoke<string>("tunnel_invite", { instanceId }),
  tunnelStopHost: () => invoke<void>("tunnel_stop_host"),
  tunnelDecode: (code: string) => invoke<InviteInfo>("tunnel_decode", { code }),
  tunnelJoin: (code: string, instanceId: string) => invoke<void>("tunnel_join", { code, instanceId }),
  tunnelLeave: () => invoke<void>("tunnel_leave"),
  importPendingMods: (instanceId: string) => invoke<Instance>("import_pending_mods", { instanceId }),
};

export const LOADER_NAMES: Record<Loader["type"], string> = {
  vanilla: "Vanilla",
  fabric: "Fabric",
  quilt: "Quilt",
  forge: "Forge",
  neoforge: "NeoForge",
};

export function loaderLabel(loader: Loader): string {
  if (loader.type === "vanilla") return "Vanilla";
  // Forge versions repeat the game version ("1.20.1-47.2.0"); show only the loader part.
  const version = loader.type === "forge" ? loader.version.replace(/^[^-]+-/, "") : loader.version;
  return `${LOADER_NAMES[loader.type]} ${version}`;
}

export function formatNumber(n: number): string {
  if (n >= 1_000_000) return t("{n} млн", { n: (n / 1_000_000).toFixed(1).replace(/\.0$/, "") });
  if (n >= 1_000) return t("{n} тыс.", { n: (n / 1_000).toFixed(1).replace(/\.0$/, "") });
  return String(n);
}

/** Play time like "12 ч 5 мин"; empty when the instance has not been played yet. */
export function formatPlayTime(seconds?: number): string {
  const s = seconds ?? 0;
  if (s <= 0) return "";
  if (s < 60) return t("меньше минуты");
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return t("{m} мин", { m: minutes });
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours >= 10 || rest === 0 ? t("{h} ч", { h: hours }) : t("{h} ч {m} мин", { h: hours, m: rest });
}

export function errorText(e: unknown): string {
  return typeof e === "string" ? e : e instanceof Error ? e.message : String(e);
}
