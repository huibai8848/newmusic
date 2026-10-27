/**
 * 埋点 SDK 的纯函数部分（模块 17 FR-26-002）。
 *
 * 这里刻意不碰 `window` / `localStorage` / `fetch`：所有状态都由调用方传入，
 * 这样 Node 的原生类型擦除就能直接 import 做断言（`web/scripts/test-track.mjs`），
 * 也避免「导入即执行」在 SSR 或测试环境里炸掉。
 *
 * 真正发请求、读写存储的胶水在 `services/track.ts`。
 */

/** 业务属性：值必须是 JSON 可序列化的，服务端最多 8KB */
export type TrackProps = Record<string, unknown>;

/** 公共属性（服务端 TrackEventReq.common） */
export interface TrackCommon {
  deviceId: string;
  sessionId: string;
  platform: string;
  appVersion: string;
  os: string;
  browser: string;
  screen: string;
  network: string;
  pageId: string;
  pageUrl: string;
  referrer: string;
  abTests?: Record<string, string>;
}

/** 与后端 `TrackEventReq` 对齐的线上结构 */
export interface TrackEventPayload {
  eventId: string;
  event: string;
  eventTime: number;
  common: TrackCommon;
  properties: TrackProps;
}

/** 队列里的一条：失败重试次数决定是否丢弃 */
export interface QueuedTrackEvent {
  payload: TrackEventPayload;
  attempts: number;
  queuedAt: number;
}

/** 每分钟固定窗口限流状态 */
export interface RateLimitState {
  windowStart: number;
  count: number;
}

export const TRACK_LIMITS = {
  /** 单批条数，与 PRD「5 秒 / 20 条」一致 */
  batchSize: 20,
  /** 本地最多积压的条数，超出丢最旧的 */
  queueSize: 100,
  /** 失败最多重试 3 次（连同首次共 4 次投递） */
  maxAttempts: 3,
  /** 客户端限流：100 条/分钟（BR-26-008） */
  ratePerMinute: 100,
  /** 500ms 内同名同属性视为重复点击 */
  dedupWindowMs: 500,
  /** 会话空闲 30 分钟后换新 sessionId */
  sessionIdleMs: 30 * 60 * 1000,
  /** 一次批量请求的 body 上限，和本地队列上限一起防爆 localStorage */
  maxPersistedBytes: 64 * 1024,
} as const;

/**
 * 与后端 `EventNameValidator` 完全一致：全小写蛇形 2~6 段。
 * 允许两段是因为事件目录里就有 `page_view` / `search_submit` 这类 `domain_action`。
 */
const EVENT_NAME = /^[a-z][a-z0-9]*(?:_[a-z0-9]+){1,5}$/;

export function isEventNameValid(name: string): boolean {
  return typeof name === 'string' && name.length <= 64 && EVENT_NAME.test(name);
}

/**
 * 页面 ID 归一化：埋点看板按页面聚合，不能把 `/song/123` 和 `/song/456`
 * 当成两个页面，否则曲库一多，页面维度就退化成「按 ID 排名」。
 */
export function derivePageId(pathname: string): string {
  const path = (pathname || '/').split('?')[0].split('#')[0].replace(/\/+$/, '') || '/';
  if (path === '/') return 'home';
  const rules: Array<[RegExp, string]> = [
    [/^\/search$/, 'search'],
    [/^\/song\/[^/]+$/, 'song_detail'],
    [/^\/artist\/[^/]+$/, 'artist_detail'],
    [/^\/album\/[^/]+$/, 'album_detail'],
    [/^\/playlist\/[^/]+$/, 'playlist_detail'],
    [/^\/rooms$/, 'room_list'],
    [/^\/room\/[^/]+$/, 'room'],
    [/^\/feed$/, 'feed'],
    [/^\/post\/[^/]+$/, 'post_detail'],
    [/^\/topic\/[^/]+$/, 'topic'],
    [/^\/messages$/, 'messages'],
    [/^\/message\/[^/]+$/, 'chat'],
    [/^\/notifications$/, 'notification'],
    [/^\/me$/, 'user_center'],
    [/^\/user\/[^/]+$/, 'user_profile'],
    [/^\/recommend$/, 'recommend'],
    [/^\/login$/, 'login'],
    [/^\/register$/, 'register'],
    [/^\/settings$/, 'settings'],
  ];
  for (const [pattern, id] of rules) {
    if (pattern.test(path)) return id;
  }
  const first = path.split('/').filter(Boolean)[0] || 'unknown';
  return `page_${first.replace(/[^a-z0-9_-]/gi, '').toLowerCase() || 'unknown'}`;
}

/** 生成 64 字符内的唯一 ID：事件 ID 只要求 5 分钟幂等，不追求全局唯一 */
export function newTrackId(prefix: string, now: number, random: number): string {
  const safePrefix = (prefix || 'evt').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 12) || 'evt';
  const suffix = Math.floor(Math.abs(random) * 0x100000000).toString(36);
  return `${safePrefix}_${now.toString(36)}_${suffix}`;
}

/** 会话是否已空闲超时（纯时间判断，方便测试边界） */
export function sessionExpired(lastActiveAt: number, now: number, idleMs = TRACK_LIMITS.sessionIdleMs): boolean {
  if (!lastActiveAt || lastActiveAt > now) return true;
  return now - lastActiveAt >= idleMs;
}

/**
 * 稳定序列化：对象键排序后拼串，用于「同名同属性」去重。
 * 只处理 JSON 值；函数、undefined 忽略，循环引用不在埋点属性里出现。
 */
export function stableKey(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'null';
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableKey).join(',')}]`;
  }
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableKey(record[k])}`).join(',')}}`;
}

/** 去重键：事件名 + 属性内容（不含时间、不含事件 ID） */
export function dedupKey(event: string, properties: TrackProps): string {
  return `${event}#${stableKey(properties || {})}`;
}

/** 500ms 内的同名同属性视为重复 */
export function shouldSuppressDuplicate(
  lastKey: string | null,
  lastAt: number,
  key: string,
  now: number,
  windowMs = TRACK_LIMITS.dedupWindowMs,
): boolean {
  if (!lastKey || lastKey !== key) return false;
  const delta = now - lastAt;
  return delta >= 0 && delta < windowMs;
}

/** 固定窗口限流：窗口过期即重置，避免滑动窗口在长会话里积累时间戳数组 */
export function takeRateToken(
  state: RateLimitState,
  now: number,
  limit = TRACK_LIMITS.ratePerMinute,
  windowMs = 60_000,
): { allowed: boolean; state: RateLimitState } {
  if (!state || !state.windowStart || now - state.windowStart >= windowMs) {
    return { allowed: true, state: { windowStart: now, count: 1 } };
  }
  const next = state.count + 1;
  return { allowed: next <= limit, state: { windowStart: state.windowStart, count: next } };
}

/** 入队并裁剪：保留最新的 max 条，最旧的先丢 */
export function enqueueTrack(
  queue: QueuedTrackEvent[],
  item: QueuedTrackEvent,
  max = TRACK_LIMITS.queueSize,
): QueuedTrackEvent[] {
  const next = queue.concat(item);
  return next.length > max ? next.slice(next.length - max) : next;
}

/** 从队首取一批；返回剩余队列与本次要发的批次 */
export function takeBatch(
  queue: QueuedTrackEvent[],
  size = TRACK_LIMITS.batchSize,
): { batch: QueuedTrackEvent[]; rest: QueuedTrackEvent[] } {
  if (queue.length === 0) return { batch: [], rest: [] };
  return { batch: queue.slice(0, size), rest: queue.slice(size) };
}

/**
 * 失败回滚：批次重新放回队首并累加 attempts；达到上限的直接丢弃。
 * 返回被丢弃的条数，调用方据此累计 `track_dropped`。
 */
export function requeueFailed(
  rest: QueuedTrackEvent[],
  batch: QueuedTrackEvent[],
  maxAttempts = TRACK_LIMITS.maxAttempts,
): { queue: QueuedTrackEvent[]; dropped: number } {
  const retried: QueuedTrackEvent[] = [];
  let dropped = 0;
  for (const item of batch) {
    if (item.attempts >= maxAttempts) {
      dropped += 1;
    } else {
      retried.push({ ...item, attempts: item.attempts + 1 });
    }
  }
  return { queue: retried.concat(rest), dropped };
}

/** 持久化前的兜底校验：手工改坏 localStorage 时不能把整条链路卡死 */
export function parsePersistedQueue(raw: string | null, maxAttempts = TRACK_LIMITS.maxAttempts): QueuedTrackEvent[] {
  if (!raw) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: QueuedTrackEvent[] = [];
  for (const item of parsed) {
    if (!item || typeof item !== 'object') continue;
    const candidate = item as Partial<QueuedTrackEvent>;
    const payload = candidate.payload as Partial<TrackEventPayload> | undefined;
    if (!payload || typeof payload.event !== 'string' || typeof payload.eventId !== 'string') continue;
    const attempts = typeof candidate.attempts === 'number' && candidate.attempts >= 0 ? candidate.attempts : 0;
    if (attempts > maxAttempts) continue;
    const properties = payload.properties && typeof payload.properties === 'object'
      ? (payload.properties as TrackProps)
      : {};
    const common = (payload.common && typeof payload.common === 'object'
      ? payload.common
      : {}) as Partial<TrackCommon>;
    out.push({
      attempts,
      queuedAt: typeof candidate.queuedAt === 'number' ? candidate.queuedAt : Date.now(),
      payload: {
        eventId: payload.eventId,
        event: payload.event,
        eventTime: typeof payload.eventTime === 'number' ? payload.eventTime : Date.now(),
        properties,
        common: {
          deviceId: common.deviceId ?? '',
          sessionId: common.sessionId ?? '',
          platform: common.platform ?? 'web',
          appVersion: common.appVersion ?? '',
          os: common.os ?? '',
          browser: common.browser ?? '',
          screen: common.screen ?? '',
          network: common.network ?? '',
          pageId: common.pageId ?? 'unknown',
          pageUrl: common.pageUrl ?? '',
          referrer: common.referrer ?? '',
          ...(common.abTests ? { abTests: common.abTests } : {}),
        },
      },
    });
  }
  return out.slice(-TRACK_LIMITS.queueSize);
}

/** 批次请求体：与后端 `TrackBatchReq` 对齐 */
export function toBatchBody(batch: QueuedTrackEvent[], deviceId: string): { events: TrackEventPayload[]; deviceId: string } {
  return { events: batch.map((item) => item.payload), deviceId };
}

/** 生成失败重试的退避时间：1s / 2s / 4s，最长 30s（纯函数便于测试） */
export function retryDelay(attempts: number): number {
  if (attempts <= 0) return 0;
  return Math.min(30_000, 1000 * 2 ** (attempts - 1));
}

/** 网络类型粗分类（navigator.connection.effectiveType） */
export function normalizeNetwork(effectiveType?: string | null): string {
  if (!effectiveType) return 'unknown';
  const value = effectiveType.toLowerCase();
  // `slow-2g` 也包含 "2g"，必须先判 slow，否则会被归成普通 2g
  if (value.includes('slow')) return 'slow';
  if (value.includes('4g')) return '4g';
  if (value.includes('3g')) return '3g';
  if (value.includes('2g')) return '2g';
  if (value.includes('wifi')) return 'wifi';
  return 'unknown';
}

/**
 * 播放完成率：服务端用 0~1 的比例判定有效播放（`valid-play-ratio` 默认 0.6）。
 * duration 未知时返回 0，而不是 NaN —— MySQL 的 DECIMAL 不接受 NaN。
 */
export function completionRate(position: number, duration: number): number {
  if (!Number.isFinite(position) || !Number.isFinite(duration) || duration <= 0) return 0;
  const ratio = position / duration;
  return Math.max(0, Math.min(1, Number(ratio.toFixed(4))));
}
