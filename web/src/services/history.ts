import type { Id, SongBrief } from '@/types/api';
import { readJson, removeRaw, writeJson } from '@/utils/storage';

/**
 * 「最近播放」本地记录（模块 02 个人中心）。
 *
 * 播放历史的后端接口属模块 12/17（播放升级 / 数据平台），MVP 先落 localStorage：
 * 只存展示所需的最小字段，避免把整个 Song 快照塞进 5MB 的存储配额。
 */

const HISTORY_KEY = 'history';
const MAX_ITEMS = 100;

export interface HistoryItem {
  songId: Id;
  name: string;
  artistName: string;
  cover: string | null;
  albumName: string | null;
  /** 秒级时长，列表展示用 */
  duration: number | null;
  playedAt: number;
}

export function listHistory(): HistoryItem[] {
  const items = readJson<HistoryItem[]>(HISTORY_KEY);
  if (!Array.isArray(items)) return [];
  return items.filter((it) => it && typeof it.songId === 'string');
}

/**
 * 记录一次播放：同曲去重后置顶，最多保留 100 条。
 *
 * 入参用 `SongBrief`：播放器队列里存的本来就是精简结构（`playerStore`），
 * 完整 `Song` 结构化满足该类型，可直接传入。
 */
export function pushHistory(song: SongBrief): HistoryItem[] {
  const item: HistoryItem = {
    songId: song.id,
    name: song.name,
    artistName: song.artistName,
    cover: song.cover ?? null,
    albumName: song.albumName ?? null,
    duration: song.duration ?? null,
    playedAt: Date.now(),
  };
  const next = [item, ...listHistory().filter((it) => it.songId !== song.id)].slice(0, MAX_ITEMS);
  writeJson(HISTORY_KEY, next);
  return next;
}

export function clearHistory(): void {
  removeRaw(HISTORY_KEY);
}

/** 播放历史 → 播放器/列表可用的最小歌曲形状 */
export function toSongBrief(item: HistoryItem): SongBrief {
  return {
    id: item.songId,
    name: item.name,
    artistName: item.artistName,
    albumName: item.albumName,
    cover: item.cover,
    duration: item.duration,
  };
}
