import axios from 'axios';

interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
  timestamp?: number;
}

export class ApiError extends Error {
  readonly code: number;
  readonly traceId?: string;

  constructor(code: number, message: string, traceId?: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
  }
}

/** 全局 401 事件：AuthProvider 监听后清空登录态，RequireAuth 负责跳转 */
export const UNAUTHORIZED_EVENT = 'admin-unauthorized';

const http = axios.create({
  baseURL: '/api/v1/admin',
  withCredentials: true,
  timeout: 20000,
});

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiEnvelope<unknown>;
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        throw new ApiError(body.code, body.message || '请求失败', body.traceId);
      }
      return body.data as never;
    }
    return response.data as never;
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status;
      const body = error.response?.data as Partial<ApiEnvelope<unknown>> | undefined;
      if (status === 401) {
        window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
      }
      const message =
        body?.message ||
        (status === 403 ? '无权限执行该操作' : undefined) ||
        error.message ||
        '网络请求失败';
      return Promise.reject(new ApiError(body?.code ?? status ?? -1, message, body?.traceId));
    }
    return Promise.reject(error);
  },
);

export function get<T>(url: string, params?: object): Promise<T> {
  return http.get(url, { params }) as unknown as Promise<T>;
}

export function post<T>(url: string, data?: unknown): Promise<T> {
  return http.post(url, data) as unknown as Promise<T>;
}

/** 文件上传单独放宽超时：全局 20s 对 50MB 音频不够，慢盘/代理下会被误判为网络失败。 */
export function postUpload<T>(url: string, data: FormData): Promise<T> {
  return http.post(url, data, { timeout: 180000 }) as unknown as Promise<T>;
}

export function put<T>(url: string, data?: unknown): Promise<T> {
  return http.put(url, data) as unknown as Promise<T>;
}

export function del<T>(url: string, data?: unknown): Promise<T> {
  return http.delete(url, { data }) as unknown as Promise<T>;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return '操作失败';
}
