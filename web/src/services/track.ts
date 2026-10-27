import {
  TRACK_LIMITS,
  completionRate as calcCompletionRate,
  dedupKey,
  derivePageId,
  enqueueTrack,
  isEventNameValid,
  newTrackId,
  normalizeNetwork,
  parsePersistedQueue,
  requeueFailed,
  sessionExpired,
  shouldSuppressDuplicate,
  takeBatch,
  takeRateToken,
  toBatchBody,
  type QueuedTrackEvent,
  type TrackCommon,
  type TrackEventPayload,
  type TrackProps,
  type RateLimitState,
} from '@/utils/trackCore';
import { StorageKey, readRaw, removeRaw, writeRaw } from '@/utils/storage';
import { getDeviceId } from '@/utils/device';

/**
 * C 端埋点 SDK（模块 17 FR-26-002）。
 *
 * 设计取舍：
 * - **批量 + 定时**：5 秒或满 20 条发一批，单次请求最多 200 条（服务端限制）；
 * - **失败落盘**：`localStorage` 最多 100 条，每条最多重试 3 次，关页也不丢；
 * - **关页用 sendBeacon**：`fetch` 在卸载时会被取消，而埋点不需要鉴权头；
 * - **500ms 去重 + 100 条/分钟上限**：防连点与脚本刷量，超限时只累计
 *   `track_dropped`，不打爆服务端；
 * - **静默失败**：埋点是旁路数据，任何异常都不能影响业务。
 *
 * 纯计算逻辑都在 `utils/trackCore.ts`，这里只做 DOM / 网络胶水。
 */

const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1';
const FLUSH_INTERVAL_MS = 5_000;
const APP_VERSION = import.meta.env.VITE_APP_VERSION ?? 'web-1.0.0';

interface PageContext {
  path: string;
  pageId: string;
  enteredAt: number;
  referrer: string;
}

interface SessionRecord {
  id: string;
  lastAt: number;
}

let started = false;
let timer: ReturnType<typeof setInterval> | null = null;
let queue: QueuedTrackEvent[] = [];
let flushing = false;
let retryTimer: ReturnType<typeof setTimeout> | null = null;

let sessionId = '';
let sessionLastAt = 0;
let currentPage: PageContext | null = null;
let appReferrer = '';

let rateState: RateLimitState = { windowStart: 0, count: 0 };
let lastKey: string | null = null;
let lastKeyAt = 0;
/** 因限流被丢弃的条数，下一次 flush 前折算成一条 track_dropped */
let droppedSinceFlush = 0;
let abTests: Record<string, string> | undefined;

// ================================================================== 公共 API

/** 幂等的启动：路由组件挂载时调用即可 */
export function initTracking(): void {
  if (started) return;
  started = true;
  ensureSession(Date.now());
  queue = parsePersistedQueue(readRaw(StorageKey.TRACK_QUEUE)).slice(-TRACK_LIMITS.queueSize);
  appReferrer = safeDocumentReferrer();

  timer = setInterval(() => flushTracking(false), FLUSH_INTERVAL_MS);

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibilityChange);
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('online', onOnline);
  }
}

/** 页面浏览：切换路由时调用；内部先给上一页补 page_leave */
export function trackPageView(path?: string): void {
  initTracking();
  const now = Date.now();
  ensureSession(now);
  const nextPath = path ?? currentLocation();
  if (currentPage && currentPage.path === nextPath) return;

  if (currentPage) {
    emitPageLeave(currentPage, now);
  }
  const pageId = derivePageId(nextPath.split('?')[0]);
  const referrer = currentPage ? currentPage.path : appReferrer;
  currentPage = { path: nextPath, pageId, enteredAt: now, referrer };
  sessionLastAt = now;
  persistSession();

  track('page_view', {
    page_id: pageId,
    path: nextPath,
    referrer: referrer.slice(0, 500),
  });
}

/** 页面离开：路由切换 / 关闭标签页 / 切后台时调用；重复调用只发一次 */
export function trackPageLeave(): void {
  if (!currentPage) return;
  emitPageLeave(currentPage, Date.now());
  currentPage = null;
  persistQueue();
}

/** 业务事件入口：eventName 必须符合 {domain}_{object}_{action}，否则静默丢弃 */
export function track(event: string, properties: TrackProps = {}): void {
  if (!isEventNameValid(event)) {
    if (import.meta.env.DEV) console.warn('[track] 非法事件名，已丢弃:', event);
    return;
  }
  initTracking();
  const now = Date.now();
  ensureSession(now);

  const { allowed, state } = takeRateToken(rateState, now);
  rateState = state;
  if (!allowed) {
    droppedSinceFlush += 1;
    return;
  }

  const key = dedupKey(event, properties);
  if (shouldSuppressDuplicate(lastKey, lastKeyAt, key, now)) return;
  lastKey = key;
  lastKeyAt = now;

  const payload: TrackEventPayload = {
    eventId: newTrackId('evt', now, Math.random()),
    event,
    eventTime: now,
    common: buildCommon(now),
    properties: { ...properties },
  };
  queue = enqueueTrack(queue, { payload, attempts: 0, queuedAt: now });
  persistQueue();
  sessionLastAt = now;
  persistSession();

  if (queue.length >= TRACK_LIMITS.batchSize) {
    flushTracking(false);
  }
}

/** 立即发送；页面卸载时用 beacon（无需鉴权头，且不会被取消） */
export function flushTracking(useBeacon = false): void {
  if (!started) return;
  if (flushing) return;
  if (queue.length === 0 && droppedSinceFlush === 0) return;

  if (droppedSinceFlush > 0) {
    queue = enqueueTrack(queue, {
      payload: buildSystemEvent('track_dropped', { count: droppedSinceFlush, reason: 'rate_limit' }),
      attempts: 0,
      queuedAt: Date.now(),
    });
    droppedSinceFlush = 0;
  }

  const { batch, rest } = takeBatch(queue);
  if (batch.length === 0) return;
  queue = rest;
  persistQueue();
  flushing = true;

  const body = JSON.stringify(toBatchBody(batch, getDeviceId()));
  const url = `${API_BASE}/track/batch`;

  if (useBeacon && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    let ok = false;
    try {
      ok = navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }));
    } catch {
      ok = false;
    }
    flushing = false;
    if (!ok) {
      const failed = requeueFailed(queue, batch);
      queue = failed.queue;
      persistQueue();
    }
    return;
  }

  void fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true,
    credentials: 'include',
  })
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      flushing = false;
      if (queue.length >= TRACK_LIMITS.batchSize) {
        scheduleFlush(0);
      } else if (queue.length > 0) {
        // 失败重试的积压慢慢消化，不抢占首屏带宽
        scheduleFlush(TRACK_LIMITS.dedupWindowMs);
      }
    })
    .catch(() => {
      const failed = requeueFailed(queue, batch);
      queue = failed.queue;
      droppedSinceFlush = failed.dropped;
      persistQueue();
      flushing = false;
      const maxAttempts = batch.reduce((max, item) => Math.max(max, item.attempts), 0);
      if (queue.length > 0 && maxAttempts < TRACK_LIMITS.maxAttempts) {
        scheduleFlush(Math.min(30_000, 1000 * 2 ** maxAttempts));
      }
    });
}

/** 实验分流（模块 15）由调用方注入，避免 SDK 反向依赖推荐 store */
export function setTrackAbTests(tests: Record<string, string> | null | undefined): void {
  abTests = tests && Object.keys(tests).length > 0 ? { ...tests } : undefined;
}

/** 停止定时器：热更新/测试环境用，业务代码不需要调用 */
export function stopTracking(): void {
  if (timer !== null) {
    clearInterval(timer);
    timer = null;
  }
}

/** 登出时清空会话：下次登录是一个新会话，避免把两个人的行为串在一条 session 里 */
export function resetTrackingSession(): void {
  sessionId = '';
  sessionLastAt = 0;
  removeRaw(StorageKey.TRACK_SESSION);
  currentPage = null;
  rateState = { windowStart: 0, count: 0 };
  lastKey = null;
  lastKeyAt = 0;
}

/** 播放完成率（供 AudioEngine 复用同一口径） */
export function playCompletionRate(position: number, duration: number): number {
  return calcCompletionRate(position, duration);
}

// ================================================================== 内部

function scheduleFlush(delay: number): void {
  if (retryTimer !== null) clearTimeout(retryTimer);
  retryTimer = setTimeout(() => {
    retryTimer = null;
    flushTracking(false);
  }, Math.max(0, delay));
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'hidden') {
    trackPageLeave();
    flushTracking(true);
  }
}

function onPageHide(): void {
  trackPageLeave();
  flushTracking(true);
}

function onOnline(): void {
  flushTracking(false);
}

function ensureSession(now: number): void {
  if (sessionId && !sessionExpired(sessionLastAt, now)) return;
  const stored = readRaw(StorageKey.TRACK_SESSION);
  if (stored) {
    try {
      const record = JSON.parse(stored) as SessionRecord;
      if (record.id && !sessionExpired(record.lastAt, now)) {
        sessionId = record.id;
        sessionLastAt = record.lastAt;
        return;
      }
    } catch {
      /* 坏数据直接换新会话 */
    }
  }
  sessionId = newTrackId('sess', now, Math.random());
  sessionLastAt = now;
  persistSession();
}

function persistSession(): void {
  writeRaw(StorageKey.TRACK_SESSION, JSON.stringify({ id: sessionId, lastAt: sessionLastAt }));
}

function persistQueue(): void {
  let payload = JSON.stringify(queue);
  if (payload.length > TRACK_LIMITS.maxPersistedBytes) {
    // 超限时丢最旧的，直到放得下（至少保留 1 条，避免把刚入队的也丢了）
    while (queue.length > 1 && payload.length > TRACK_LIMITS.maxPersistedBytes) {
      queue = queue.slice(Math.max(1, Math.ceil(queue.length / 4)));
      payload = JSON.stringify(queue);
    }
  }
  writeRaw(StorageKey.TRACK_QUEUE, payload);
}

function emitPageLeave(page: PageContext, now: number): void {
  track('page_leave', {
    page_id: page.pageId,
    path: page.path,
    dwell_ms: Math.max(0, now - page.enteredAt),
  });
}

function buildCommon(now: number): TrackCommon {
  const page = currentPage;
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  const common: TrackCommon = {
    deviceId: getDeviceId(),
    sessionId: sessionId || newTrackId('sess', now, Math.random()),
    platform: 'web',
    appVersion: APP_VERSION,
    os: detectOs(ua),
    browser: detectBrowser(ua),
    screen: typeof window === 'undefined' ? '' : `${window.screen.width}x${window.screen.height}`,
    network: detectNetwork(),
    pageId: page ? page.pageId : derivePageId(currentLocation()),
    pageUrl: currentLocation().slice(0, 500),
    referrer: (page ? page.referrer : appReferrer).slice(0, 500),
  };
  if (abTests) common.abTests = abTests;
  return common;
}

function buildSystemEvent(event: string, properties: TrackProps): TrackEventPayload {
  return {
    eventId: newTrackId('evt', Date.now(), Math.random()),
    event,
    eventTime: Date.now(),
    common: buildCommon(Date.now()),
    properties,
  };
}

function currentLocation(): string {
  if (typeof window === 'undefined' || !window.location) return '/';
  return `${window.location.pathname}${window.location.search}`;
}

function safeDocumentReferrer(): string {
  try {
    return typeof document === 'undefined' ? '' : document.referrer.slice(0, 500);
  } catch {
    return '';
  }
}

function detectOs(ua: string): string {
  if (/Windows/i.test(ua)) return 'Windows';
  if (/Android/i.test(ua)) return 'Android';
  if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS';
  if (/Mac OS X/i.test(ua)) return 'macOS';
  if (/Linux/i.test(ua)) return 'Linux';
  return 'unknown';
}

function detectBrowser(ua: string): string {
  if (/Edg\//.test(ua)) return 'Edge';
  if (/OPR\//.test(ua)) return 'Opera';
  if (/Firefox\//.test(ua)) return 'Firefox';
  if (/Chrome\//.test(ua)) return 'Chrome';
  if (/Safari\//.test(ua)) return 'Safari';
  return 'unknown';
}

function detectNetwork(): string {
  const connection = (navigator as Navigator & {
    connection?: { effectiveType?: string };
  }).connection;
  return normalizeNetwork(connection?.effectiveType);
}
