/* 스토리지 모드 스위치 — VITE_STORAGE=http 면 백엔드 API, 아니면 localStorage. */
import type { VaultRepository } from "./VaultRepository";
import { LocalStorageRepository } from "./LocalStorageRepository";
import { HttpVaultRepository } from "./HttpVaultRepository";

export const storageMode: "http" | "local" = import.meta.env.VITE_STORAGE === "http" ? "http" : "local";

export const repository: VaultRepository =
  storageMode === "http" ? new HttpVaultRepository() : new LocalStorageRepository();
