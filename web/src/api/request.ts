import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios';
import type { LoginVO, R } from '@/types/api';
import { ErrCode } from '@/types/api';
import { StorageKey, readRaw, removeRaw, writeJson, writeRaw } from '@/utils/storage';
import { toastError } from '@/utils/feedback';

/**
 * 统一请求层（模块 02 D-04）。
 *
 * 职责：
 * 1. 注入 `Authorization: Bearer`；
 * 2. 拆包 `R<T>`：业务成功直接把 `data` 交给调用方；
 * 3. 业务失败统一 reject 为 {@link ApiError}（携带 code / traceId）；
 * 4. HTTP 401/业务 401xx → **只刷新一次** Token 并重放原请求，失败则清登录态跳登录；
 * 5. 错误统一 Toast，可用 `skipErrorToast` 关闭（局部兜底 UI 自己展示错误时使用）。
 */

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 关闭本请求的错误 Toast（交由调用方展示局部失败态） */
    skipErrorToast?: boolean;
    /** 内部标记：已用新 Token 重放过一次，避免无限循环 */
    _retried?: boolean;
  }
}

/** 业务/网络错误统一形态，页面可据此分支处理 */
export class ApiError extends Error {
  readonly code: number;
  readonly traceId?: string;
  readonly httpStatus?: number;

  constructor(code: number, message: string, traceId?: string, httpStatus?: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.httpStatus = httpStatus;
  }
}

/** 登录态失效的错误码（HTTP 401 也会归一到这里） */
const AUTH_ERROR_CODES: number[] = [ErrCode.UNAUTHORIZED, ErrCode.TOKEN_INVALID, ErrCode.TOKEN_EXPIRED];

const LOGIN_PATH = '/login';

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1',
  timeout: 15_000,
  headers: { Accept: 'application/json' },
});

http.interceptors.request.use((config) => {
  const token = readRaw(StorageKey.ACCESS_TOKEN);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 清理登录态并跳登录页（保留来源路径，登录后可回跳） */
function redirectToLogin(): void {
  removeRaw(StorageKey.ACCESS_TOKEN);
  removeRaw(StorageKey.REFRESH_TOKEN);
  removeRaw(StorageKey.USER);
  const { pathname, search, hash } = window.location;
  const current = `${pathname}${search}${hash}`;
  if (!pathname.startsWith(LOGIN_PATH)) {
    const redirect = encodeURIComponent(current);
    window.location.replace(`${LOGIN_PATH}?redirect=${redirect}`);
  }
}

/** 并发 401 时只发起一次刷新，其余请求排队等这次刷新的结果 */
let refreshPromise: Promise<string> | null = null;

/**
 * 刷新 accessToken（并发安全：多次调用共享同一次请求）。
 * 模块 04 的 WS 收到 `auth.expiring` 后也走这里，拿到新 token 再重连。
 */
export async function refreshAccessToken(): Promise<string> {
  const refreshToken = readRaw(StorageKey.REFRESH_TOKEN);
  if (!refreshToken) {
    throw new ApiError(ErrCode.UNAUTHORIZED, '登录已过期，请重新登录');
  }
  if (!refreshPromise) {
    const base = http.defaults.baseURL ?? '';
    refreshPromise = axios
      .post<R<LoginVO>>(
        `${base}/auth/refresh`,
        { refreshToken },
        { timeout: 10_000, headers: { 'Content-Type': 'application/json' } },
      )
      .then((res) => {
        const body = res.data;
        if (body.code !== ErrCode.SUCCESS || !body.data?.accessToken) {
          throw new ApiError(body.code, body.message, body.traceId);
        }
        // Token 是裸字符串，必须用 writeRaw：writeJson 会写入带引号的 JSON，导致 Authorization 头非法
        writeRaw(StorageKey.ACCESS_TOKEN, body.data.accessToken);
        writeRaw(StorageKey.REFRESH_TOKEN, body.data.refreshToken);
        if (body.data.user) writeJson(StorageKey.USER, body.data.user);
        return body.data.accessToken;
      })
      .finally(() => {
        // 无论成功失败都释放，失败时下次请求会重新尝试（refreshToken 无效会被服务端拒绝）
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

function toastUnlessSkipped(config: AxiosRequestConfig | undefined, message: string, traceId?: string): void {
  if (config?.skipErrorToast) return;
  toastError(traceId ? `${message}（traceId: ${traceId}）` : message);
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<R<unknown>>) => {
    const config = (error.config ?? {}) as AxiosRequestConfig;
    const body = error.response?.data;
    const status = error.response?.status;
    const code = body?.code ?? (status ?? ErrCode.SYSTEM_BUSY);
    const message = body?.message ?? '网络异常，请稍后重试';

    // 登录态失效：刷新一次并重放
    const isAuthError = status === 401 || AUTH_ERROR_CODES.includes(code);
    const isRefreshCall = String(config.url ?? '').includes('/auth/refresh');
    if (isAuthError && !config._retried && !isRefreshCall) {
      try {
        const token = await refreshAccessToken();
        config._retried = true;
        return await http.request({ ...config, headers: { ...config.headers, Authorization: `Bearer ${token}` } });
      } catch {
        redirectToLogin();
        return Promise.reject(new ApiError(ErrCode.UNAUTHORIZED, '登录已过期，请重新登录', body?.traceId, status));
      }
    }
    if (isAuthError) {
      redirectToLogin();
    }

    toastUnlessSkipped(config, message, body?.traceId);
    return Promise.reject(new ApiError(code, message, body?.traceId, status));
  },
);

/**
 * 业务层拆包：HTTP 200 也可能是业务失败（模块 01 §5.1）。
 * 所有 api/*.ts 都通过它发请求，页面拿到的即 `data`。
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<R<T>>(config);
  const body = response.data;
  // 非 R 结构（如 /actuator/health 或反向代理返回的 HTML）直接原样返回
  if (body === null || typeof body !== 'object' || !('code' in body)) {
    return body as unknown as T;
  }
  if (body.code !== ErrCode.SUCCESS) {
    const isAuthError = AUTH_ERROR_CODES.includes(body.code);
    if (isAuthError) redirectToLogin();
    toastUnlessSkipped(config, body.message, body.traceId);
    throw new ApiError(body.code, body.message, body.traceId, response.status);
  }
  return body.data;
}

export const api = {
  get: <T>(url: string, params?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'GET', url, params }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'PUT', url, data }),
  delete: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'DELETE', url, data }),
};
