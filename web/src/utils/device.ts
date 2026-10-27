import { StorageKey, readRaw, writeRaw } from '@/utils/storage';

/**
 * 设备标识（模块 04 WS 引入，模块 08 游客播放历史上报复用）。
 *
 * 同设备多标签页共享同一个 ID：WS 据此做「同设备新连接顶替旧连接」，
 * 播放历史据此把游客的多次播放归到同一主体。
 * 复用 `ml.wsDeviceId` 而不是另起一个 key，避免两处生成的 ID 不一致。
 */
export function getDeviceId(): string {
  const existing = readRaw(StorageKey.WS_DEVICE_ID);
  if (existing) return existing;
  const generated =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  writeRaw(StorageKey.WS_DEVICE_ID, generated);
  return generated;
}

/**
 * 设备类型（模块 12 BR-21-071）。
 *
 * 判据是「当前是否以独立窗口运行」：PWA 安装后 `display-mode: standalone` 成立；
 * 移动浏览器（含微信内置）没有独立窗口，但 UA 能识别，归为 MOBILE_WEB。
 */
export function getDeviceType(): 'WEB' | 'PWA' | 'MOBILE_WEB' {
  try {
    if (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches) {
      return 'PWA';
    }
  } catch {
    /* matchMedia 不可用时按浏览器处理 */
  }
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  return /Android|iPhone|iPad|iPod|Mobile|MicroMessenger/i.test(ua) ? 'MOBILE_WEB' : 'WEB';
}

/**
 * 设备名（设备管理列表展示用，如 “Chrome on Windows”）。
 *
 * 不使用 UA 全串：它又长又泄露系统补丁版本，列表里只需要「哪个浏览器」。
 */
export function getDeviceName(): string {
  if (typeof navigator === 'undefined') return '未知设备';
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /OPR\//.test(ua)
      ? 'Opera'
      : /Firefox\//.test(ua)
        ? 'Firefox'
        : /Chrome\//.test(ua)
          ? 'Chrome'
          : /Safari\//.test(ua)
            ? 'Safari'
            : '浏览器';
  const os = /Windows/i.test(ua)
    ? 'Windows'
    : /Android/i.test(ua)
      ? 'Android'
      : /iPhone|iPad|iPod/i.test(ua)
        ? 'iOS'
        : /Mac OS X/i.test(ua)
          ? 'macOS'
          : /Linux/i.test(ua)
            ? 'Linux'
            : '未知系统';
  return `${browser} on ${os}`;
}
