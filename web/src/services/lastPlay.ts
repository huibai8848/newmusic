import type { Id } from '@/types/api';
import { StorageKey, readJson, removeRaw, writeJson } from '@/utils/storage';

/**
 * 断点续播（模块 07 D-14 / `BR-16-036`~`BR-16-039`）。
 *
 * 为什么单独一个 key 而不并进 `ml.player`：进度每 5 秒写一次，队列可能 500 首；
 * 合在一起会让"只保存进度"也重写整条队列的 JSON（D-09）。
 *
 * 只存**一条**记录：断点续播的语义是"上次听到哪"，不是"听歌历史"（那是 `services/history.ts`）。
 */

/** 记录多久内有效；超过就当作过期，不再提示续播（`BR-16-039`） */
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/** 进度写入节流间隔（`BR-16-036`） */
export const LAST_PLAY_THROTTLE_MS = 5_000;

/** 距结尾不足这个秒数就记 0，避免"续播时直接跳下一首"（`BR-16-038`） */
const NEAR_END_SECONDS = 3;

export interface LastPlay {
  songId: Id;
  /** 秒，已四舍五入到 0.1（`BR-16-037`） */
  position: number;
  updatedAt: number;
  /** 登录账号 ID；游客为 null。换账号后不恢复别人的进度 */
  userId: string | null;
}

export function readLastPlay(): LastPlay | null {
  const saved = readJson<LastPlay>(StorageKey.LAST_PLAY);
  if (!saved || typeof saved.songId !== 'string' || typeof saved.position !== 'number') return null;
  if (typeof saved.updatedAt !== 'number' || Date.now() - saved.updatedAt > MAX_AGE_MS) return null;
  if (!Number.isFinite(saved.position) || saved.position < 0) return null;
  return saved;
}

/**
 * 保存进度。**不做节流**，页面卸载等一次性场景直接调它；
 * 高频场景请用 {@link saveLastPlayThrottled}。
 * 这里只负责「距结尾 < 3 秒记 0」与「四舍五入到 0.1 秒」两条规范化。
 */
export function saveLastPlay(songId: Id, position: number, duration: number, userId: string | null): void {
  const safePosition =
    Number.isFinite(duration) && duration > 0 && duration - position < NEAR_END_SECONDS
      ? 0
      : Math.max(0, Math.round(position * 10) / 10);
  writeJson(StorageKey.LAST_PLAY, { songId, position: safePosition, updatedAt: Date.now(), userId } satisfies LastPlay);
}

export function clearLastPlay(): void {
  removeRaw(StorageKey.LAST_PLAY);
}

/**
 * 是否该写入：距上次写入超过 {@link LAST_PLAY_THROTTLE_MS}。
 *
 * 放在模块里而不是 `AudioEngine` 的 ref：`AudioEngine` 平时不需要关心存储细节，
 * 它只要每个 `timeupdate` 调一次 {@link saveLastPlayThrottled} 即可。
 */
let lastWriteAt = 0;

export function saveLastPlayThrottled(
  songId: Id,
  position: number,
  duration: number,
  userId: string | null,
  now = Date.now(),
): void {
  if (now - lastWriteAt < LAST_PLAY_THROTTLE_MS) return;
  lastWriteAt = now;
  saveLastPlay(songId, position, duration, userId);
}

/** 测试/换账号时重置节流窗口 */
export function resetLastPlayThrottle(): void {
  lastWriteAt = 0;
}
