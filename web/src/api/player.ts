import { api } from '@/api/request';
import { QUALITY_LADDER, type Quality } from '@/api/playability';
import type { Id } from '@/types/api';

// 可播放性判定是纯函数，单独放在 playability.ts 以便 Node 零依赖单测；
// 这里原样再导出，保持 `@/api/player` 现有的引用口径不变
export * from '@/api/playability';

/**
 * 播放地址解析（模块 02 D-08 → 模块 07 T712 重写）。
 *
 * 模块 02 是本地适配层：直接从歌曲详情的 `audioStandard/audioHigh/audioLossless` 取地址。
 * 模块 07 换成真实接口 `GET /api/v1/player/url`，由**服务端**做版权校验、审核校验与音质降级，
 * 并签发带过期时间的地址。
 *
 * 这里不再提供「本地拿到的地址」，只保留 {@link fetchPlayUrl}：
 * 真正的播放地址必须来自服务端，否则会绕过签名与过期时间。
 * 置灰用的同步预判在 `playability.ts` 里。
 */

/** `GET /player/url` 的响应体（模块 07 §4.1，与后端 `PlayUrlVO` 一一对应） */
export interface PlayUrlVO {
  songId: Id;
  /** 实际下发的音质 */
  quality: Quality;
  /** 请求的音质；与 `quality` 不同即为降级 */
  requestedQuality: Quality;
  downgraded: boolean;
  /** 降级原因（模块 12）：NO_SOURCE / VIP_REQUIRED / LOGIN_REQUIRED；未降级为 null */
  downgradeReason?: 'NO_SOURCE' | 'VIP_REQUIRED' | 'LOGIN_REQUIRED' | null;
  /** 可直接展示的降级说明；未降级为 null */
  notice?: string | null;
  /** 带签名的播放地址；`newmusic.play.cdn-base` 为空时是相对路径 */
  url: string;
  /** 时长（秒） */
  duration: number | null;
  /** 音频格式 mp3/wav/flac */
  format: string;
  /** 过期时间（毫秒时间戳） */
  expiresAt: number;
  /** 试听片段时长；MVP 恒为 null（游客试听属模块 03/12） */
  previewDuration: number | null;
}

// ---------------------------------------------------------------------------
// 真实播放地址：带 sessionStorage 缓存与 5 分钟预刷新（§5.2）
// ---------------------------------------------------------------------------

/**
 * 距离过期不足这个时长就认为「临近过期」，需要提前换新地址。
 *
 * 取值 5 分钟：比签发接口的 RTT 长得多，又短于 2h 的 TTL，
 * 保证切歌时命中缓存的地址一定还有 5 分钟以上寿命（`EX-16-001-8`）。
 */
const PRE_REFRESH_MS = 5 * 60 * 1000;

/**
 * 用 `sessionStorage` 而不是 `localStorage`：
 * 地址带 `userId` 签名，**换账号后原地址无效**，且它本就只在一个播放会话内有意义（D-13）。
 */
const CACHE_PREFIX = 'playurl.';

interface CachedUrl extends PlayUrlVO {
  /** 写入时的账号；`null` 代表游客，换账号后缓存失效 */
  userId: string | null;
}

function cacheKey(songId: Id, quality: Quality): string {
  return CACHE_PREFIX + songId + '.' + quality;
}

function readCache(songId: Id, quality: Quality, userId: string | null): CachedUrl | null {
  try {
    const raw = window.sessionStorage.getItem(cacheKey(songId, quality));
    if (!raw) return null;
    const cached = JSON.parse(raw) as CachedUrl;
    // 换账号 / 退出登录后签名口径变了，缓存必须作废，否则会拿到 403 的地址
    if (cached.userId !== userId) return null;
    // 临近过期视为未命中，避免"刚用上就过期"
    if (typeof cached.expiresAt !== 'number' || cached.expiresAt - Date.now() <= PRE_REFRESH_MS) return null;
    return cached;
  } catch {
    // sessionStorage 被禁用或内容损坏 → 退化为每次请求
    return null;
  }
}

function writeCache(songId: Id, quality: Quality, userId: string | null, value: PlayUrlVO): void {
  try {
    window.sessionStorage.setItem(cacheKey(songId, quality), JSON.stringify({ ...value, userId } satisfies CachedUrl));
  } catch {
    /* 存储不可用时不影响播放，只是下次要重新签发 */
  }
}

/** 清掉某首歌的全部音质缓存（播放失败时调用：地址可能已失效） */
export function invalidatePlayUrl(songId: Id): void {
  try {
    for (const q of Object.keys(QUALITY_LADDER) as Quality[]) {
      window.sessionStorage.removeItem(cacheKey(songId, q));
    }
  } catch {
    /* ignore */
  }
}

export interface FetchPlayUrlOptions {
  /** 当前登录账号 ID；游客传 null。参与缓存失效判断 */
  userId?: string | null;
  /** 跳过缓存强制走网络（播放失败重试时用，可能是签名过期） */
  force?: boolean;
  /** 关闭错误 Toast（重试链自己处理提示时用） */
  silent?: boolean;
  signal?: AbortSignal;
}

/**
 * 取播放地址：命中缓存直接返回，否则调 `GET /player/url`（§5.2）。
 *
 * 失败时**原样抛出 `ApiError`**，由 `AudioEngine` 按 §5.3 决定重试还是跳曲；
 * 这里不吞异常，否则上层无从区分「无版权」和「网络抖动」。
 */
export async function fetchPlayUrl(
  songId: Id,
  quality: Quality = 'standard',
  options: FetchPlayUrlOptions = {},
): Promise<PlayUrlVO> {
  const userId = options.userId ?? null;
  if (!options.force) {
    const cached = readCache(songId, quality, userId);
    if (cached) {
      return cached;
    }
  }
  const vo = await api.get<PlayUrlVO>(
    '/player/url',
    { songId, quality },
    { skipErrorToast: options.silent, signal: options.signal },
  );
  writeCache(songId, quality, userId, vo);
  return vo;
}

/**
 * 按降级阶梯重试取地址（`audio.error.code === 4 SRC_NOT_SUPPORTED` 用，§5.3）。
 *
 * 从请求档位往下降，**跳过已经试过的档位**；全部失败时抛出最后一个错误。
 */
export async function fetchPlayUrlWithFallback(
  songId: Id,
  from: Quality,
  options: FetchPlayUrlOptions = {},
): Promise<PlayUrlVO> {
  const ladder = QUALITY_LADDER[from];
  let lastError: unknown = null;
  for (const q of ladder) {
    try {
      return await fetchPlayUrl(songId, q, { ...options, force: true });
    } catch (err) {
      lastError = err;
    }
  }
  throw lastError ?? new Error('无可用音质');
}
