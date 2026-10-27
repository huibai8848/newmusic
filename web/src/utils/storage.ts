/**
 * localStorage 薄封装（模块 01 D-05：Token 存 localStorage，不用 Cookie，规避 CSRF）。
 *
 * 统一前缀 + 容错：隐私模式或存储被禁用时不能抛异常中断渲染。
 */

const PREFIX = 'ml.';

export const StorageKey = {
  ACCESS_TOKEN: 'accessToken',
  REFRESH_TOKEN: 'refreshToken',
  USER: 'user',
  THEME: 'theme',
  PLAYER: 'player',
  /** 断点续播（模块 07 D-14）：单条轻量记录，与 `PLAYER` 分开写，避免每 5 秒重写整个队列 */
  LAST_PLAY: 'lastPlay',
  /** WS 设备标识（模块 04）：同设备多标签页共享，服务端据此做「同设备新连接顶替旧连接」 */
  WS_DEVICE_ID: 'wsDeviceId',
  /** 歌词展示模式（模块 14）：原文 / 双语 / 仅译文，跨页面与刷新保持 */
  LYRIC_DISPLAY_MODE: 'lyricDisplayMode',
  /** 埋点待发送队列（模块 17）：请求失败时落盘，最多重试 3 次 */
  TRACK_QUEUE: 'trackQueue',
  /** 埋点会话（模块 17）：空闲 30 分钟换新 sessionId */
  TRACK_SESSION: 'trackSession',
} as const;

export function readRaw(key: string): string | null {
  try {
    return window.localStorage.getItem(PREFIX + key);
  } catch {
    return null;
  }
}

export function writeRaw(key: string, value: string): void {
  try {
    window.localStorage.setItem(PREFIX + key, value);
  } catch {
    /* 存储不可用时降级为内存态（刷新即失效） */
  }
}

export function removeRaw(key: string): void {
  try {
    window.localStorage.removeItem(PREFIX + key);
  } catch {
    /* ignore */
  }
}

export function readJson<T>(key: string): T | null {
  const raw = readRaw(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    removeRaw(key);
    return null;
  }
}

export function writeJson(key: string, value: unknown): void {
  try {
    writeRaw(key, JSON.stringify(value));
  } catch {
    /* ignore */
  }
}
