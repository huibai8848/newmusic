import { playApi } from '@/api/play';
import type { Id, SongBrief } from '@/types/api';
import { usePlayerStore } from '@/stores/playerStore';
import { useUserStore } from '@/stores/userStore';
import { StorageKey, readRaw } from '@/utils/storage';
import { getDeviceId } from '@/utils/device';

/**
 * 服务端播放行为上报（模块 08）。
 *
 * 模块 07 把播放统计推迟到模块 12，但个性化推荐没有服务端行为就无从谈起，
 * 因此这里补齐最小闭环：**开始播放时 record、播放中每 30 秒 progress、切歌/暂停/关页时 end**。
 *
 * 几个刻意的设计：
 * - `record` 在 `playing` 事件里发起，而不是切歌时：恢复的队列在页面加载时只是「选中」
 *   并未真正播放，此时上报会凭空制造行为数据。
 * - `playedDuration` 由 `timeupdate` 的**正向增量**累加，而不是直接取 `currentTime`：
 *   用户拖进度条不会把「听了 5 秒」算成「听了 3 分钟」。
 * - 关页用 `fetch(keepalive)`：axios 的请求会在页面卸载时被取消，而 `sendBeacon`
 *   无法携带 `Authorization`，登录用户的 end 会被服务端按游客归属校验直接拒绝。
 * - 所有失败静默：播放行为是旁路数据，不能因为统计接口抖动影响听歌。
 */

/** 进度上报间隔（PRD 建议 30 秒） */
const PROGRESS_INTERVAL_MS = 30_000;
/** 单次 tick 允许计入的最大增量：超过视为拖动，不计入实际播放时长 */
const MAX_TICK_DELTA = 10;

interface PlaySession {
  /** 与异步响应配对，切歌后旧响应直接丢弃 */
  token: number;
  songId: string | null;
  playId: Id | null;
  userId: Id | null;
  duration: number;
  position: number;
  listened: number;
  lastTickAt: number;
  lastReportAt: number;
}

const EMPTY_SESSION: PlaySession = {
  token: 0,
  songId: null,
  playId: null,
  userId: null,
  duration: 0,
  position: 0,
  listened: 0,
  lastTickAt: 0,
  lastReportAt: 0,
};

let session: PlaySession = { ...EMPTY_SESSION };
let tokenSeq = 0;

function isLoggedIn(): boolean {
  return Boolean(useUserStore.getState().user?.id && readRaw(StorageKey.ACCESS_TOKEN));
}

/**
 * 确保当前歌曲有一个进行中的播放会话；已经在上报中则什么都不做。
 * 由 `AudioEngine` 在 `playing` 事件里调用。
 */
export function startPlay(song: SongBrief, source: string, quality: string): void {
  const songId = String(song.id);
  if (session.songId === songId) {
    // 同一首的重复 playing（缓冲恢复、暂停后继续）不另起一条历史
    return;
  }
  endPlay(false);

  const token = ++tokenSeq;
  const userId = useUserStore.getState().user?.id ?? null;
  session = {
    token,
    songId,
    playId: null,
    userId,
    duration: song.duration ?? 0,
    position: 0,
    listened: 0,
    lastTickAt: Date.now(),
    lastReportAt: Date.now(),
  };

  playApi
    .record({ songId, source, quality })
    .then((vo) => {
      // 请求返回前已切歌：丢弃，避免把上一首的 playId 记到新会话上
      if (session.token !== token) return;
      session.playId = vo.playId;
    })
    .catch(() => {
      /* 上报失败不影响播放 */
    });
}

function sendProgress(force: boolean): void {
  const current = session;
  if (!current.playId || !isLoggedIn()) return;
  const now = Date.now();
  if (!force && now - current.lastReportAt < PROGRESS_INTERVAL_MS) return;
  current.lastReportAt = now;
  const userId = useUserStore.getState().user?.id ?? null;
  if (String(userId) !== String(current.userId)) return;
  playApi
    .progress({
      songId: current.songId as Id,
      playId: current.playId,
      position: current.position,
      duration: current.duration,
    })
    .catch(() => {
      /* 静默 */
    });
}

/**
 * 每次 `timeupdate` 调用。
 *
 * @param position 当前播放位置（秒）
 * @param duration 音频总时长（秒），元数据未就绪时为 0
 * @param playing 当前是否处于播放态
 */
export function tickPlay(position: number, duration: number, playing: boolean): void {
  if (!session.songId) return;
  if (duration > 0) session.duration = duration;

  const now = Date.now();
  if (playing && position > session.position) {
    const delta = position - session.position;
    // 拖动进度条会一次性跳过很多秒，不计入实际播放时长
    if (delta <= MAX_TICK_DELTA) {
      session.listened += delta;
    }
  }
  session.position = position;
  session.lastTickAt = now;
  sendProgress(false);
}

/** 暂停/切页时补一次进度，避免最多丢 30 秒的续播点 */
export function flushProgress(): void {
  sendProgress(true);
}

/** 关页时的请求：axios 会被取消，这里用 keepalive fetch 并手动带 Token */
function sendEndKeepalive(body: Record<string, unknown>): void {
  const base = import.meta.env.VITE_API_BASE ?? '/api/v1';
  const token = readRaw(StorageKey.ACCESS_TOKEN);
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  try {
    void fetch(`${base.replace(/\/$/, '')}/play/end`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      keepalive: true,
    });
  } catch {
    /* 静默 */
  }
}

/**
 * 结束当前播放会话并上报。
 *
 * @param completed 是否自然播完（`ended` 事件）
 * @param keepalive 页面正在卸载，改用 keepalive fetch
 */
export function endPlay(completed = false, keepalive = false): void {
  const current = session;
  if (!current.songId) return;
  session = { ...EMPTY_SESSION };

  if (!current.playId) return;
  const payload = {
    playId: current.playId,
    playedDuration: Math.max(0, Math.round(current.listened)),
    songDuration: Math.round(current.duration),
    position: Math.max(0, current.position),
    completed,
    deviceId: getDeviceId(),
  };
  if (keepalive) {
    sendEndKeepalive(payload as unknown as Record<string, unknown>);
    return;
  }
  playApi.end(payload).catch(() => {
    /* 静默 */
  });
}

/** 切歌时结束上一首；同一首歌换音质不结束（否则会多出一条历史） */
export function endPlayIfDifferent(songId: string | null): void {
  if (!session.songId) return;
  if (songId !== null && session.songId === songId) return;
  endPlay(false);
}

/** 播放来源映射到后端约定字符串（模块 08 增加 recommend） */
export function currentSource(): string {
  const source = usePlayerStore.getState().playSource;
  return source ?? 'unknown';
}
