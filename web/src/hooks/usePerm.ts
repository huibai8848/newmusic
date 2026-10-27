import { useCallback } from 'react';
import { useUserStore } from '@/stores/userStore';

/**
 * 权限码判断 Hook（模块 02 D-06）。
 *
 * 用法：`const canCreate = usePerm('music:song:create');`
 * 仅控制按钮/菜单显隐，写接口的服务端仍有 `@PreAuthorize` 二次校验。
 */
export function usePerm(perm: string): boolean {
  return useUserStore((s) => s.hasPerm(perm));
}

export function useAnyPerm(perms: string[]): boolean {
  return useUserStore((s) => s.hasAnyPerm(perms));
}

/** 需要以回调形式判断（如表格单元格渲染）时使用 */
export function usePermChecker(): (perm: string) => boolean {
  const hasPerm = useUserStore((s) => s.hasPerm);
  return useCallback((perm: string) => hasPerm(perm), [hasPerm]);
}
