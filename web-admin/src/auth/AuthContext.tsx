import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { UNAUTHORIZED_EVENT } from '@/api/client';
import { authApi } from '@/api/endpoints';
import type { AdminUser, LoginResult } from '@/api/types';

interface LoginPayload {
  username: string;
  password: string;
  captchaId: string;
  captchaCode: string;
  totpCode?: string;
}

interface AuthContextValue {
  admin: AdminUser | null;
  loading: boolean;
  login: (payload: LoginPayload) => Promise<LoginResult>;
  logout: () => Promise<void>;
  reload: () => Promise<void>;
  hasPerm: (code: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * 后台登录态。Token 只在 httpOnly Cookie 中，前端不读取、不写 localStorage；
 * 页面刷新后通过 /auth/me 重新确认身份。
 */
export function AuthProvider({ children }: { children: ReactNode }): JSX.Element {
  const [admin, setAdmin] = useState<AdminUser | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    try {
      setAdmin(await authApi.me());
    } catch {
      setAdmin(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    const onUnauthorized = (): void => setAdmin(null);
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [reload]);

  const login = useCallback(async (payload: LoginPayload) => {
    const result = await authApi.login(payload);
    setAdmin(result.admin);
    return result;
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setAdmin(null);
    }
  }, []);

  const hasPerm = useCallback(
    (code: string) => {
      if (!admin) return false;
      return admin.perms.includes(code) || admin.perms.includes('*:*:*');
    },
    [admin],
  );

  const value = useMemo<AuthContextValue>(
    () => ({ admin, loading, login, logout, reload, hasPerm }),
    [admin, loading, login, logout, reload, hasPerm],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return context;
}
