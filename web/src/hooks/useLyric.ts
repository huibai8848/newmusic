import { useCallback, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { songApi } from '@/api/song';
import type { Id, Lyric } from '@/types/api';
import { parseLrc, type LyricLine, type LyricMeta } from '@/utils/lrc';
import { readRaw, removeRaw, writeRaw } from '@/utils/storage';

/**
 * 取歌词 + 解析 + 偏移合成（模块 07 T706）。
 *
 * 三层缓存，各管一件事：
 * 1. React Query 内存缓存 —— 同一首歌在多个组件间复用，切歌来回不重复请求（`staleTime` 10 分钟）；
 * 2. `localStorage` 30 天 —— 刷新页面、离线打开也有歌词（PRD 场景，`R-09`）。
 *    **只缓存"有歌词"的结果**：null 是暂时性结论，后台随时可能补上歌词，
 *    把 null 也存 30 天会让用户一直看到「暂无歌词」；
 * 3. `parseLrc` 的结果用 `useMemo` 缓存 —— 解析本身很快，但不该每帧重来。
 *
 * 偏移有**两个来源**，按 `BR-16-069` 相加：
 * - `t_lyric.offset_ms`（运营配的整体偏移，服务端下发）；
 * - 用户自己的校准值（`FR-16-009`，本地上调，不上传）。校准面板属 P1（T720），
 *   这里先把"能存能生效"的路铺好，面板只是多几个按钮。
 */

/** 歌词在 `localStorage` 里的保鲜期 */
const CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** React Query 内存缓存保鲜期：歌词改动极少 */
const STALE_MS = 10 * 60 * 1000;

const CACHE_PREFIX = 'lyric.';
const OFFSET_PREFIX = 'lyricOffset.';

interface CachedLyric {
  /**
   * 写入时的实体版本号。
   *
   * **当前不参与失效判断**：过期只看 `fetchedAt`（30 天）。存它是为了排查
   * "为什么我改了歌词前端没变"时能一眼看出缓存里是哪一版（`R-09` 的缓解措施是缩短 TTL + 手动清缓存，
   * 不是版本比对——那需要一次额外的"查版本"请求，为 MVP 的收益不值）。
   */
  version: number;
  fetchedAt: number;
  lyric: Lyric | null;
}

function cacheKey(songId: Id): string {
  return CACHE_PREFIX + songId;
}

function readLyricCache(songId: Id): Lyric | null | undefined {
  const raw = readRaw(cacheKey(songId));
  if (!raw) return undefined;
  try {
    const cached = JSON.parse(raw) as CachedLyric;
    if (Date.now() - cached.fetchedAt > CACHE_TTL_MS) {
      removeRaw(cacheKey(songId));
      return undefined;
    }
    // 旧版本会把"暂无歌词"也写进缓存。运营补歌词后用户刷新仍是空，
    // 这类负结果直接丢弃并重取，不再按 30 天保鲜。
    if (cached.lyric === null) {
      removeRaw(cacheKey(songId));
      return undefined;
    }
    return cached.lyric;
  } catch {
    // 内容损坏（手改过 / 半截写入）就丢掉重取
    removeRaw(cacheKey(songId));
    return undefined;
  }
}

function writeLyricCache(songId: Id, lyric: Lyric | null): void {
  writeRaw(
    cacheKey(songId),
    JSON.stringify({ version: lyric?.version ?? 0, fetchedAt: Date.now(), lyric } satisfies CachedLyric),
  );
}

/**
 * 取歌词：先看本地缓存，未命中或已过期才走网络。
 *
 * `readLyricCache` 用 `undefined` 表示"没有可用缓存"、`null` 表示"缓存里就是没有歌词"，
 * 两者必须分开——后者是有效结果，不该再去请求一次。
 */
async function fetchLyric(songId: Id): Promise<Lyric | null> {
  const cached = readLyricCache(songId);
  if (cached !== undefined) return cached;
  const lyric = await songApi.lyric(songId);
  // 负结果不落盘：下次打开/刷新会再问一次（React Query 在会话内仍会挡住重复请求）
  if (lyric) writeLyricCache(songId, lyric);
  return lyric;
}

/**
 * 清掉某首歌的歌词缓存（运营改了歌词后想立刻看到时用）。
 *
 * 目前没有 UI 入口，留给后续「歌词报错」按钮或调试用；导出是为了不写死在这里又不被 tree-shake 掉。
 */
export function invalidateLyricCache(songId: Id): void {
  removeRaw(cacheKey(songId));
}

function readUserOffset(songId: Id): number {
  const raw = readRaw(OFFSET_PREFIX + songId);
  if (!raw) return 0;
  const n = Number.parseInt(raw, 10);
  // 限幅 ±10s：超出这个范围不是"听感校准"而是配置错了，防止把歌词整段推走
  return Number.isFinite(n) ? Math.max(-10_000, Math.min(10_000, n)) : 0;
}

export interface UseLyricResult {
  /** 解析后的行（已含两层偏移），按时间升序 */
  lines: LyricLine[];
  meta: LyricMeta;
  /** 是否有时间轴；`false` 时歌词面板退化为纯文本滚动 */
  timed: boolean;
  /** 含逐字标签（本期不渲染，仅提示） */
  hasWordLevel: boolean;
  /** 被跳过的脏行数，> 0 时控制台会提示 */
  skippedLines: number;
  /** 接口返回 `null`：该歌曲没有歌词（`BR-16-056`） */
  empty: boolean;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  /** 实体上的整体偏移（毫秒），用于在面板上说明"已应用偏移" */
  entityOffsetMs: number;
  /** 用户校准值（毫秒，正值表示歌词提前） */
  userOffsetMs: number;
  setUserOffsetMs: (ms: number) => void;
}

const EMPTY_RESULT: Omit<UseLyricResult, 'isLoading' | 'isError' | 'error'> = {
  lines: [],
  meta: { offsetMs: 0 },
  timed: false,
  hasWordLevel: false,
  skippedLines: 0,
  empty: true,
  entityOffsetMs: 0,
  userOffsetMs: 0,
  setUserOffsetMs: () => undefined,
};

/**
 * @param songId 为 `null`/`undefined` 时不发请求（如播放条未播放任何歌）
 */
export function useLyric(songId: Id | null | undefined): UseLyricResult {
  // 用户校准值：按曲存本地。用 state 而不是只读存储，改完要立刻生效
  const [userOffsetMs, setOffsetState] = useState(() => (songId ? readUserOffset(songId) : 0));

  // 切歌时同步读一次新曲的校准值。放在 render 期比较而不是 effect：
  // 否则会先按上一首的偏移渲染一帧，歌词会"跳"一下
  const [offsetSongId, setOffsetSongId] = useState(songId);
  if (offsetSongId !== songId) {
    setOffsetSongId(songId);
    setOffsetState(songId ? readUserOffset(songId) : 0);
  }

  const setUserOffsetMs = useCallback(
    (ms: number) => {
      const clamped = Math.max(-10_000, Math.min(10_000, Math.round(ms)));
      setOffsetState(clamped);
      if (songId) {
        if (clamped === 0) removeRaw(OFFSET_PREFIX + songId);
        else writeRaw(OFFSET_PREFIX + songId, String(clamped));
      }
    },
    [songId],
  );

  const query = useQuery({
    queryKey: ['song', 'lyric', songId ?? ''],
    queryFn: () => fetchLyric(songId as Id),
    enabled: Boolean(songId),
    staleTime: STALE_MS,
    // 歌词请求失败不重试：歌都放起来了，歌词晚几秒有比没有更糟的是卡住整页
    retry: false,
  });

  /**
   * 偏移合成规则：**`t_lyric.offset_ms` 与正文 `[offset:]` 是同一个槽位，取其一，然后叠加用户校准。**
   *
   * 原实现把两者相加，实测出了一个真 bug：种子 `demo-03` 的正文写着 `[offset:500]`、
   * 列上也是 500（同一个 offset 记了两遍），于是按 1000ms 生效，`[00:05.00]` 那行落到
   * **4.0 秒**，而种子自己标注的预期是 **4.5 秒**。demo-03 是唯一带 `[offset:]` 的种子，
   * 所以只有它偏了 500ms，其余 5 首看不出来。
   *
   * 谁优先：**列值非 0 时用列值**（`override` 覆盖正文标签），否则用正文标签。
   * 反过来「有标签就一定用标签」是错的：这些 LRC 文件普遍带一行样板 `[offset:0]`，
   * 那样会让运营在后台改的 `offset_ms` 被静默忽略，等于把「校准」这个字段废掉。
   * 代价是列值无法显式表达"就是 0"（0 被当作"未设置"），要清零只能改成 ±1ms，
   * 这个取舍比"整个校准功能失效"轻。
   *
   * 用户校准（`BR-16-069` 的第二项）永远叠加，与上面选谁无关。
   */
  const parsed = useMemo(() => {
    if (!query.data?.content) return null;
    const entityOffsetMs = query.data.offsetMs ?? 0;
    if (entityOffsetMs === 0) {
      return parseLrc(query.data.content, userOffsetMs);
    }
    // override：传入值就是总偏移，正文标签不再参与
    return parseLrc(query.data.content, entityOffsetMs + userOffsetMs, 'override');
  }, [query.data?.content, query.data?.offsetMs, userOffsetMs]);

  return useMemo(() => {
    if (!query.data || !parsed) {
      return { ...EMPTY_RESULT, isLoading: query.isLoading, isError: query.isError, error: query.error, userOffsetMs, setUserOffsetMs };
    }
    return {
      lines: parsed.lines,
      meta: parsed.meta,
      timed: parsed.timed,
      hasWordLevel: parsed.hasWordLevel,
      skippedLines: parsed.skippedLines,
      empty: parsed.lines.length === 0,
      isLoading: false,
      isError: false,
      error: null,
      entityOffsetMs: query.data.offsetMs ?? 0,
      userOffsetMs,
      setUserOffsetMs,
    };
  }, [query.data, query.isLoading, query.isError, query.error, parsed, userOffsetMs, setUserOffsetMs]);
}
