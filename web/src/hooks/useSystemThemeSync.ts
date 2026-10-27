import { useEffect } from 'react';
import { useThemeStore } from '@/stores/themeStore';

/** mode='system' 时跟随系统主题变化（PRD §6.2） */
export function useSystemThemeSync(): void {
  const syncFromSystem = useThemeStore((s) => s.syncFromSystem);

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (): void => syncFromSystem();
    media.addEventListener('change', handler);
    return () => media.removeEventListener('change', handler);
  }, [syncFromSystem]);
}
