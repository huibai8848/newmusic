import { create } from 'zustand';
import { authApi } from '@/api/auth';
import type { LoginReq, UserVO } from '@/types/api';
import { StorageKey, readRaw, readJson, removeRaw, writeJson, writeRaw } from '@/utils/storage';
import { hasPerm as hasPermIn } from '@/utils/perm';
import { resetTrackingSession } from '@/services/track';

/**
 * 登录态（模块 02 §5.2）。
 *
 * 持久化策略：`accessToken` / `refreshToken` 用 writeRaw（裸字符串），
 * `user` 用 writeJson。刷新页面时从 localStorage 恢复，避免闪一下未登录态。
 */

interface UserState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserVO | null;
  isLogin: boolean;
  /** 登录中（按钮 loading） */
  logging: boolean;

  login: (req: LoginReq) => Promise<UserVO>;
  /** 用 accessToken 拉一次最新资料（含角色/权限码），App 启动时调用 */
  refreshUser: () => Promise<void>;
  logout: () => Promise<void>;
  /** 权限码判断，支持 `*:*:*` 与资源级通配（D-06） */
  hasPerm: (perm: string) => boolean;
  hasAnyPerm: (perms: string[]) => boolean;
}

function initialUser(): UserVO | null {
  return readJson<UserVO>(StorageKey.USER);
}

const storedUser = initialUser();

export const useUserStore = create<UserState>((set, get) => ({
  accessToken: readRaw(StorageKey.ACCESS_TOKEN),
  refreshToken: readRaw(StorageKey.REFRESH_TOKEN),
  user: storedUser,
  isLogin: Boolean(readRaw(StorageKey.ACCESS_TOKEN) && storedUser),
  logging: false,

  login: async (req) => {
    set({ logging: true });
    try {
      const data = await authApi.login(req);
      writeRaw(StorageKey.ACCESS_TOKEN, data.accessToken);
      writeRaw(StorageKey.REFRESH_TOKEN, data.refreshToken);
      writeJson(StorageKey.USER, data.user);
      set({
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        user: data.user,
        isLogin: true,
      });
      return data.user;
    } finally {
      set({ logging: false });
    }
  },

  refreshUser: async () => {
    if (!get().accessToken) return;
    try {
      const user = await authApi.me();
      writeJson(StorageKey.USER, user);
      set({ user, isLogin: true });
    } catch {
      // 401 已在请求层处理（刷新或跳登录）；其它错误保留本地缓存的资料
    }
  },

  logout: async () => {
    try {
      await authApi.logout();
    } catch {
      // 登出接口失败不影响本地清理
    }
    removeRaw(StorageKey.ACCESS_TOKEN);
    removeRaw(StorageKey.REFRESH_TOKEN);
    removeRaw(StorageKey.USER);
    // 埋点会话按登录态切开：否则下一个登录用户会继承上一个人的 session
    resetTrackingSession();
    set({ accessToken: null, refreshToken: null, user: null, isLogin: false });
  },

  hasPerm: (perm) => hasPermIn(get().user?.perms, perm),
  hasAnyPerm: (perms) => perms.some((p) => hasPermIn(get().user?.perms, p)),
}));
