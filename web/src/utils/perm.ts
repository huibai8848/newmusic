/**
 * 权限码判断（模块 02 D-06），与后端 `PermissionCatalogue.covers` 保持同一套语义：
 *
 * - 精确匹配：`music:song:create`
 * - 资源级通配：`music:artist:*` 覆盖 `music:artist:create`
 * - 模块级通配：`music:*` 覆盖 `music:artist:create`
 * - 全量：`*:*:*`
 *
 * 前端判断**仅用于按钮/菜单显隐**，服务端必须二次校验（PRD BR-02-011）。
 */

export const ALL_PERM = '*:*:*';

export function hasPerm(perms: string[] | undefined | null, required: string): boolean {
  if (!perms || perms.length === 0 || !required) return false;
  if (perms.includes(ALL_PERM) || perms.includes(required)) return true;

  // 逐层前缀通配：music:artist:create → music:artist:* → music:* → *:*:*
  let prefix = required;
  let idx = prefix.lastIndexOf(':');
  while (idx > 0) {
    prefix = prefix.slice(0, idx);
    if (perms.includes(`${prefix}:*`)) return true;
    idx = prefix.lastIndexOf(':');
  }
  return false;
}

/** 是否具备任一权限 */
export function hasAnyPerm(perms: string[] | undefined | null, required: string[]): boolean {
  return required.some((p) => hasPerm(perms, p));
}
