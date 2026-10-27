import { create } from 'zustand';
import { StorageKey, readRaw, writeRaw } from '@/utils/storage';

/** 主题模式（PRD §6.2）：默认跟随系统 */
export type ThemeMode = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

const MEDIA = '(prefers-color-scheme: dark)';

function systemTheme(): ResolvedTheme {
  return typeof window !== 'undefined' && window.matchMedia(MEDIA).matches ? 'dark' : 'light';
}

function resolve(mode: ThemeMode): ResolvedTheme {
  return mode === 'system' ? systemTheme() : mode;
}

/** 写入 `<html data-theme>`：所有 CSS 变量（variables.less）据此切换 */
function applyToDom(theme: ResolvedTheme): void {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
}

interface ThemeState {
  mode: ThemeMode;
  /** 实际生效的主题（mode=system 时由系统决定） */
  resolved: ResolvedTheme;
  setMode: (mode: ThemeMode) => void;
  /** 系统主题变化时由 useSystemThemeSync 调用 */
  syncFromSystem: () => void;
}

const storedMode = (readRaw(StorageKey.THEME) as ThemeMode | null) ?? 'system';
const initialResolved = resolve(storedMode);
applyToDom(initialResolved);

export const useThemeStore = create<ThemeState>((set, get) => ({
  mode: storedMode,
  resolved: initialResolved,

  setMode: (mode) => {
    const resolved = resolve(mode);
    writeRaw(StorageKey.THEME, mode);
    applyToDom(resolved);
    set({ mode, resolved });
  },

  syncFromSystem: () => {
    if (get().mode !== 'system') return;
    const resolved = systemTheme();
    applyToDom(resolved);
    set({ resolved });
  },
}));
