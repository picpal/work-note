import { usePersist } from "./usePersist";
import type { Settings } from "../types";

export const SETTINGS_DEFAULTS: Settings = {
  dark: false, sidebarWidth: 264, density: "comfortable",
  showIcons: true, guides: true, fontSize: 16,
};

export function useSettings() {
  const [settings, setSettings] = usePersist<Settings>("wn.settings.v1", SETTINGS_DEFAULTS);
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) =>
    setSettings((s) => ({ ...s, [k]: v }));
  return { settings: { ...SETTINGS_DEFAULTS, ...settings }, set };
}
