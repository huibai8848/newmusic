import { create } from 'zustand';
import type { Quality } from '@/api/player';
import { isPlayable } from '@/api/player';
import type { Id, SongBrief } from '@/types/api';
import type { LyricLine } from '@/utils/lrc';
import { StorageKey, readJson, writeJson } from '@/utils/storage';
import { formatTime } from '@/utils/format';
import { toastWarning } from '@/utils/feedback';
import { readLastPlay } from '@/services/lastPlay';
import { track } from '@/services/track';
import { advance, prevIndexIn, reshuffle, shufflePosOf } from './playOrder';
import type { PlayMode } from './playOrder';

/**
 * 播放器全局状态（模块 02 §5.2；PRD FR-11-005 的 store 结构；模块 07 §5.1 增量）。
 *
 * 模块 02 交付「外壳」：队列、播放/暂停、切歌、音量、进度、播放模式、音质。
 * 模块 07 在此之上补：随机序列（`shuffleOrder`/`shufflePos`）、断点续播、熔断计数、
 * 歌词当前行、浮层开关、`replayToken`。
 *
 * <p>本 store **不直接操作 DOM**：`AudioEngine` 订阅本状态驱动 `<audio>`；
 * 进度回写走 `setProgress`，避免把 HTMLMediaElement 塞进全局状态。
 *
 * <p>「谁写谁读」的约定（模块 07 新增）：
 * - `currentLine` 只由 `AudioEngine` 写（它独占 `timeupdate`），歌词面板只读；
 * - `failStreak` 只由 `AudioEngine` 写（D-11：重试计数留在 ref，只有跨曲连败才进 store）；
 * - 两个浮层开关只由 UI 写，不持久化。
 */

/**
 * 播放模式。定义在 {@link ./playOrder}（那里能零依赖被断言脚本加载），
 * 此处原样转出——既有的 `import type { PlayMode } from '@/stores/playerStore'` 不受影响。
 */
export type { PlayMode } from './playOrder';

/** 播放发起处：模块 12 埋点与模块 08 播放行为上报共用 */
export type PlaySource =
  | 'search'
  | 'playlist'
  | 'album'
  | 'artist'
  | 'home'
  | 'queue'
  | 'recommend'
  | 'unknown';

/** 连续失败多少首就停止自动切歌（`BR-16-089`） */
export const FAIL_STREAK_LIMIT = 3;

/**
 * 播放队列上限（`BR-16-020`）。
 *
 * 超限时**拒绝新增并提示**，而不是静默丢掉队尾：静默丢曲会让用户
 * "点了播放但没有声音"，比直接说"队列已满"难排查得多。
 */
export const QUEUE_LIMIT = 500;

/**
 * 歌词数据的快照。
 *
 * 抽成 `LyricSnapshot` 而不是把 `UseLyricResult` 整个塞进 store：
 * 那边还带着 `isLoading` / `setUserOffsetMs` 等"Hook 的东西"，
 * 而 store 只该存**渲染需要的数据**。
 */
export interface LyricSnapshot {
  lines: LyricLine[];
  timed: boolean;
  hasWordLevel: boolean;
  /** 接口返回 `null` 或解析后没有任何行 */
  empty: boolean;
  skippedLines: number;
}

const EMPTY_LYRIC: LyricSnapshot = {
  lines: [],
  timed: false,
  hasWordLevel: false,
  empty: true,
  skippedLines: 0,
};

export interface SeekRequest {
  time: number;
  /** 每次 seek 递增，AudioEngine 据此识别新请求（同一秒内连续拖动也能生效） */
  nonce: number;
}

interface PersistedPlayer {
  queue: SongBrief[];
  currentIndex: number;
  volume: number;
  muted: boolean;
  playMode: PlayMode;
  quality: Quality;
  /** 模块 12：默认倍速（7 档之一），跨会话保留 */
  playbackRate?: number;
  /**
   * 用户是否手动选过音质。为 false 时，登录后可采纳服务端设置里的默认音质；
   * 一旦手动选过就以本地为准，避免「我明明选了无损，刷新后又变回高清」。
   */
  qualityExplicit?: boolean;
}

/** 跨设备续播提示的数据；只用于展示，不自动跳转（BR-21-066） */
export interface RemoteProgress {
  position: number;
  duration: number;
  deviceId: string | null;
  updateTime: number;
}

interface PlayerState {
  queue: SongBrief[];
  currentIndex: number;
  currentSong: SongBrief | null;

  isPlaying: boolean;
  isBuffering: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  muted: boolean;
  playMode: PlayMode;
  /** 用户偏好音质（持久化） */
  quality: Quality;
  /** 实际生效音质：服务端降级 / 弱网自动降级后会低于 `quality`，换歌时重置 */
  effectiveQuality: Quality;
  /** 用户是否手动选过音质（决定登录后是否采纳服务端默认音质） */
  qualityExplicit: boolean;
  /** 模块 12：7 档倍速之一，跨会话保留；音乐房内强制 1.0 */
  playbackRate: number;
  /** 是否允许弱网自动降质（来自服务端设置，默认开） */
  autoQuality: boolean;
  /** 省流量模式：开启时实际下发音质不超过 standard */
  dataSaver: boolean;
  /** 跨设备续播提示；null 表示没有可提示的远端进度 */
  remoteProgress: RemoteProgress | null;
  error: string | null;
  /** 上次播放到一半就换走时留下的提示，如「上次播放到 01:02」 */
  resumeHint: string | null;

  seekRequest: SeekRequest | null;
  /**
   * 待生效的起播位置（秒）。与 `seekRequest` 的区别是它**必须等新音源加载完成后**
   * 才由 AudioEngine 消费——「继续播放」点进去时音频地址还在路上，
   * 立刻 seek 会被随后赋值的 `audio.src` 重置回 0。
   */
  pendingSeek: number | null;
  /** 递增即代表「从头重播当前曲」（D-15） */
  replayToken: number;

  // ---------------------------------------------------------------- 模块 07 增量
  shuffleOrder: number[];
  shufflePos: number;
  /** = `playMode === 'single'`，单独存是为了 `ended` 里少读一个字段 */
  repeatOne: boolean;
  lastPlayedId: Id | null;
  failStreak: number;
  playSource: PlaySource;
  /** 当前歌词行号；`-1` 表示前奏。AudioEngine 单向写入，面板只读 */
  currentLine: number;
  /** 歌词数据快照，见 {@link setLyricData} */
  lyric: LyricSnapshot;

  overlayOpen: boolean;
  queueDrawerOpen: boolean;
  hotkeyHelpOpen: boolean;

  playSong: (song: SongBrief, list?: SongBrief[], source?: PlaySource) => void;
  playList: (list: SongBrief[], index?: number, source?: PlaySource) => void;
  /** 「继续播放」：从指定秒数开始播；位置等音源就绪后由 AudioEngine 应用 */
  playAt: (song: SongBrief, list: SongBrief[], position: number, source?: PlaySource) => void;
  /** AudioEngine 取走待生效位置；取走即清空，只生效一次 */
  consumePendingSeek: () => number | null;
  /** 插入到当前曲之后（「下一首播放」） */
  nextPlay: (song: SongBrief) => void;
  toggle: () => void;
  play: () => void;
  pause: () => void;
  next: (auto?: boolean) => void;
  prev: () => void;
  seek: (time: number) => void;
  setProgress: (currentTime: number, duration: number) => void;
  /** 只更新时长：拿到 `/player/url` 的 duration 时先填上，不必等音频元数据 */
  setDuration: (duration: number) => void;
  setPlaying: (playing: boolean) => void;
  setBuffering: (buffering: boolean) => void;
  setError: (error: string | null) => void;
  setResumeHint: (hint: string | null) => void;
  setVolume: (volume: number) => void;
  toggleMute: () => void;
  setPlayMode: (mode: PlayMode) => void;
  cyclePlayMode: () => void;
  cycleShuffleSlot: () => void;
  setQuality: (quality: Quality) => void;
  /** 引擎侧回写「实际生效音质」（服务端降级/弱网降级） */
  setEffectiveQuality: (quality: Quality) => void;
  setPlaybackRate: (rate: number) => void;
  setAutoQualityEnabled: (enabled: boolean) => void;
  /** 登录后套用服务端播放设置（默认音质/自动降质/省流量） */
  applyServerSettings: (settings: { defaultQuality: Quality; autoQuality: number; dataSaver: number }) => void;
  setRemoteProgress: (progress: RemoteProgress | null) => void;
  /** 用户点击「从 xx:xx 继续」：跳到远端进度并播放（只在用户手势里调用） */
  applyRemoteResume: () => void;
  addToQueue: (song: SongBrief) => void;
  removeFromQueue: (index: number) => void;
  clearQueue: () => void;
  /** 从当前曲之后移除一首（队列抽屉的「移除」） */
  jumpTo: (index: number) => void;

  setCurrentLine: (line: number) => void;
  /**
   * 发布歌词数据。**只由 `AudioEngine` 调用**——它是唯一持有 `timeupdate` 的单例，
   * 索引一次、面板读 N 次；此时歌词面板挂几个副本都不会重复解析（§5.4）。
   */
  setLyricData: (data: LyricSnapshot) => void;
  /** 记一次失败，返回累加后的连败数；同一首歌重复失败不累加（§5.3） */
  noteFailure: (songId: Id) => number;
  clearFailures: () => void;
  /** 启动时恢复断点；`playableIds` 用于排除已下架/无版权的曲目（`EX-16-005-4`） */
  restoreLastPlay: (userId: string | null) => void;

  setQueueDrawerOpen: (open: boolean) => void;
  setHotkeyHelpOpen: (open: boolean) => void;
  openOverlay: () => void;
  closeOverlay: () => void;
  toggleOverlay: () => void;
}

const DEFAULT_VOLUME = 0.7;

/** 与后端 `PlaySettingSupport.RATES` 对齐的 7 档倍速 */
export const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2] as const;

function isPlaybackRate(value: unknown): value is number {
  return typeof value === 'number' && PLAYBACK_RATES.includes(value as (typeof PLAYBACK_RATES)[number]);
}

function loadPersisted(): Partial<PersistedPlayer> {
  const saved = readJson<PersistedPlayer>(StorageKey.PLAYER);
  if (!saved || !Array.isArray(saved.queue)) return {};
  const currentIndex =
    typeof saved.currentIndex === 'number' && saved.currentIndex >= 0 && saved.currentIndex < saved.queue.length
      ? saved.currentIndex
      : 0;
  return {
    queue: saved.queue,
    currentIndex,
    volume: typeof saved.volume === 'number' ? saved.volume : DEFAULT_VOLUME,
    muted: Boolean(saved.muted),
    playMode: saved.playMode ?? 'order',
    quality: saved.quality ?? 'standard',
    playbackRate: isPlaybackRate(saved.playbackRate) ? saved.playbackRate : 1,
    qualityExplicit: Boolean(saved.qualityExplicit),
  };
}

const persisted = loadPersisted();

export const usePlayerStore = create<PlayerState>((set, get) => {
  const persist = (): void => {
    const { queue, currentIndex, volume, muted, playMode, quality, playbackRate, qualityExplicit } = get();
    writeJson(StorageKey.PLAYER, {
      queue,
      currentIndex,
      volume,
      muted,
      playMode,
      quality,
      playbackRate,
      qualityExplicit,
    });
  };

  /** 从头重播当前曲：`replayToken` 变化会让 AudioEngine 做 `currentTime = 0; play()` */
  const replayFromStart = (): void => {
    set((s) => ({
      currentTime: 0,
      currentLine: -1,
      isPlaying: true,
      replayToken: s.replayToken + 1,
      seekRequest: { time: 0, nonce: (s.seekRequest?.nonce ?? 0) + 1 },
    }));
  };

  /**
   * 切到指定索引。
   *
   * "明显不可播"（无版权字段 / 没有任何音源字段）的歌曲在本地就跳过，
   * 省一次注定失败的网络请求；真正权威的判定在服务端，由 `AudioEngine` 兜底（模块 07 D-08 注）。
   *
   * @param cursor 由 `advance` 算好的「序列 + 游标」。**随机模式走「下一首」时必须传**：
   *               一轮播完时 `advance` 会重建序列，再用旧的去算游标就会错位（§9 R-10）。
   *               直接点某首歌（不经过 `advance`）时才省略，此时按当前序列现算。
   * @param hops 本次「跳过不可播歌曲」已经跳过的次数。**这是防无限递归的护栏**：
   *             整队列都不可播时 `advance`（`loop`/`single`/`shuffle`）会一直给出下一个索引，
   *             没有护栏就是 `RangeError: Maximum call stack size exceeded`。绕满一圈就停。
   */
  const goTo = (
    index: number,
    auto: boolean,
    cursor?: { shuffleOrder: number[]; shufflePos: number },
    hops = 0,
  ): void => {
    const { queue, playMode, shuffleOrder, shufflePos } = get();
    if (queue.length === 0) return;
    if (index < 0 || index >= queue.length) {
      set({ isPlaying: false });
      return;
    }
    const song = queue[index];
    if (!isPlayable(song, get().quality)) {
      const skipped = advance(playMode, index, queue.length, shuffleOrder, shufflePos);
      // 条件写在行内而不是抽成变量：`skipped.index !== null` 要留在这里才收得住窄
      if (skipped.index !== null && skipped.index !== index && hops + 1 < queue.length) {
        set({ error: `《${song.name}》暂无可用音质，已跳过` });
        goTo(skipped.index, auto, skipped, hops + 1);
        return;
      }
      // 停在这里。绕满一圈（`hops + 1 >= 队列长度`）说明整个队列都没有能播的歌；
      // 否则是本模式后面已经没有再能跳的了。两种都不该递归下去撑爆调用栈。
      set({
        error: hops + 1 >= queue.length ? '队列中没有可播放的歌曲' : `《${song.name}》暂无可用音质，已跳过`,
        isPlaying: false,
        currentIndex: index,
        currentSong: song,
      });
      return;
    }
    // 随机模式下要把游标挪到新曲在序列里的位置，否则随机模式的"上一首/下一首"会错位
    const shufflePatch =
      playMode === 'shuffle'
        ? cursor
          ? { shuffleOrder: cursor.shuffleOrder, shufflePos: cursor.shufflePos }
          : { shufflePos: shufflePosOf(shuffleOrder, index) }
        : null;
    set({
      currentIndex: index,
      currentSong: song,
      currentTime: 0,
      duration: 0,
      currentLine: -1,
      error: null,
      pendingSeek: null,
      // 换歌重新从用户偏好档位开始尝试：上一首的自动降级不跨曲延续（BR-21-048）
      effectiveQuality: get().dataSaver ? 'standard' : get().quality,
      remoteProgress: null,
      isPlaying: auto ? get().isPlaying : true,
      ...shufflePatch,
    });
    persist();
  };

  return {
    queue: persisted.queue ?? [],
    currentIndex: persisted.currentIndex ?? 0,
    currentSong: persisted.queue?.[persisted.currentIndex ?? 0] ?? null,

    isPlaying: false,
    isBuffering: false,
    currentTime: 0,
    duration: 0,
    volume: persisted.volume ?? DEFAULT_VOLUME,
    muted: persisted.muted ?? false,
    playMode: persisted.playMode ?? 'order',
    quality: persisted.quality ?? 'standard',
    effectiveQuality: persisted.quality ?? 'standard',
    qualityExplicit: persisted.qualityExplicit ?? false,
    playbackRate: persisted.playbackRate ?? 1,
    autoQuality: true,
    dataSaver: false,
    remoteProgress: null,
    error: null,
    resumeHint: null,
    seekRequest: null,
    pendingSeek: null,
    replayToken: 0,

    shuffleOrder: [],
    shufflePos: 0,
    repeatOne: (persisted.playMode ?? 'order') === 'single',
    lastPlayedId: null,
    failStreak: 0,
    playSource: 'unknown',
    currentLine: -1,
    lyric: EMPTY_LYRIC,

    overlayOpen: false,
    queueDrawerOpen: false,
    hotkeyHelpOpen: false,

    playSong: (song, list, source = 'unknown') => {
      if (list && list.length > 0) {
        const index = Math.max(
          0,
          list.findIndex((s) => s.id === song.id),
        );
        set({
          queue: list,
          currentIndex: index,
          currentSong: list[index] ?? song,
          currentTime: 0,
          duration: 0,
          currentLine: -1,
          error: null,
          pendingSeek: null,
          effectiveQuality: get().dataSaver ? 'standard' : get().quality,
          remoteProgress: null,
          isPlaying: true,
          playSource: source,
          ...reshuffle(list.length, index),
        });
        persist();
        return;
      }
      const { queue } = get();
      const existIndex = queue.findIndex((s) => s.id === song.id);
      if (existIndex >= 0) {
        set({ playSource: source });
        goTo(existIndex, false);
      } else {
        set({
          queue: [...queue, song],
          currentIndex: queue.length,
          currentSong: song,
          currentTime: 0,
          duration: 0,
          currentLine: -1,
          error: null,
          pendingSeek: null,
          effectiveQuality: get().dataSaver ? 'standard' : get().quality,
          remoteProgress: null,
          isPlaying: true,
          playSource: source,
          ...reshuffle(queue.length + 1, queue.length),
        });
        persist();
      }
    },

    playList: (list, index = 0, source = 'unknown') => {
      if (list.length === 0) return;
      // BR-16-020：队列上限 500。超出部分截断并说明，而不是让一次"播放全部"把内存撑爆
      const trimmed = list.length > QUEUE_LIMIT ? list.slice(0, QUEUE_LIMIT) : list;
      if (trimmed.length < list.length) {
        toastWarning(`播放队列最多 ${QUEUE_LIMIT} 首，已截断为前 ${QUEUE_LIMIT} 首`);
      }
      const safeIndex = Math.min(Math.max(0, index), trimmed.length - 1);
      set({
        queue: trimmed,
        currentIndex: safeIndex,
        currentSong: trimmed[safeIndex] ?? null,
        currentTime: 0,
        duration: 0,
        currentLine: -1,
        error: null,
        pendingSeek: null,
        effectiveQuality: get().dataSaver ? 'standard' : get().quality,
        remoteProgress: null,
        isPlaying: true,
        playSource: source,
        ...reshuffle(trimmed.length, safeIndex),
      });
      persist();
    },

    playAt: (song, list, position, source = 'unknown') => {
      if (list.length === 0) return;
      const index = Math.max(0, list.findIndex((s) => s.id === song.id));
      get().playList(list, index, source);
      // playList 已经把 pendingSeek 清空，这里再写入本次的续播点
      set({ pendingSeek: Math.max(0, position) });
    },

    consumePendingSeek: () => {
      const value = get().pendingSeek;
      if (value !== null) set({ pendingSeek: null });
      return value;
    },

    /** 「下一首播放」：插到当前曲正后方，不打断正在放的这首 */
    nextPlay: (song) => {
      const { queue, currentIndex } = get();
      const current = queue[currentIndex];
      // 对正在播的这首点「下一首播放」没有意义，直接当作重播（否则会插出一条重复项）
      if (current && current.id === song.id) {
        replayFromStart();
        return;
      }
      // 队列里已有同一首 → 先摘掉旧位置再插，避免出现两条一样的
      const rest = queue.filter((s) => s.id !== song.id);
      if (rest.length >= QUEUE_LIMIT) {
        toastWarning(`播放队列已满（${QUEUE_LIMIT} 首），请先清空或移除部分歌曲`);
        return;
      }
      const anchor = rest.findIndex((s) => s.id === current?.id);
      const at = anchor < 0 ? currentIndex : anchor + 1;
      const next = [...rest.slice(0, at), song, ...rest.slice(at)];
      const nextIndex = at;
      set({ queue: next, currentIndex: nextIndex, ...reshuffle(next.length, nextIndex) });
      persist();
    },

    toggle: () => set((s) => ({ isPlaying: !s.isPlaying })),
    play: () => set({ isPlaying: true }),
    pause: () => set({ isPlaying: false }),

    next: (auto = false) => {
      const { playMode, currentIndex, queue, shuffleOrder, shufflePos } = get();
      if (queue.length === 0) return;
      // 用 advance 而不是 nextIndexIn：随机模式一轮播完时它会重建序列，
      // 必须把新序列一起落位（R-10）。nextIndexIn 只给索引，会把重建信息丢掉。
      const step = advance(playMode, currentIndex, queue.length, shuffleOrder, shufflePos);
      if (step.index === null) {
        set({ isPlaying: false, currentTime: 0 });
        return;
      }
      // 目标就是本曲（队列只剩一首 / 单曲循环且长度为 1）：只能靠 replayToken 让引擎真的重来。
      // 若走 goTo，index 没变化，`AudioEngine` 观察不到、`<audio>` 不会被重新播放，
      // 而 store 的 currentTime 已被清零 —— 界面与声音就此错位（这正是 D-15 记的那个坑）。
      if (step.index === currentIndex) {
        replayFromStart();
        return;
      }
      goTo(step.index, auto, step);
    },

    prev: () => {
      const { currentTime, currentIndex, queue, playMode, shuffleOrder, shufflePos } = get();
      // BR-11-042：播放超过 3 秒时「上一首」= 回到开头
      if (currentTime > 3) {
        replayFromStart();
        return;
      }
      if (queue.length === 0) return;
      // 洗牌模式下「序列首曲再往回」与「队列为空」都返回 null，处理都是回到本曲开头
      const target = prevIndexIn(playMode, currentIndex, queue.length, shuffleOrder, shufflePos);
      if (target === null || target === currentIndex) {
        replayFromStart();
        return;
      }
      goTo(target, false);
    },

    seek: (time) => set((s) => ({ currentTime: time, seekRequest: { time, nonce: (s.seekRequest?.nonce ?? 0) + 1 } })),
    setProgress: (currentTime, duration) => set({ currentTime, duration }),
    setDuration: (duration) => set({ duration }),
    setPlaying: (isPlaying) => set({ isPlaying }),
    setBuffering: (isBuffering) => set({ isBuffering }),
    setError: (error) => set({ error }),
    setResumeHint: (resumeHint) => set({ resumeHint }),

    setVolume: (volume) => {
      const v = Math.min(1, Math.max(0, volume));
      set({ volume: v, muted: v === 0 });
      persist();
    },
    toggleMute: () => {
      set((s) => ({ muted: !s.muted }));
      persist();
    },

    setPlayMode: (playMode) => {
      set({
        playMode,
        repeatOne: playMode === 'single',
        // 切进随机模式必须重建序列：否则会沿用"上一轮随机"的顺序
        ...(playMode === 'shuffle' ? reshuffle(get().queue.length, get().currentIndex) : null),
      });
      persist();
    },
    cyclePlayMode: () => {
      const order: PlayMode[] = ['order', 'loop', 'single', 'shuffle'];
      get().setPlayMode(order[(order.indexOf(get().playMode) + 1) % order.length]);
    },

    /**
     * 「随机」按钮的常规语义：非随机 → 切到随机；已在随机 → **重新洗牌**。
     * 与 {@link cyclePlayMode}（四档轮换）不同，这条是 PRD `BR-16-013` 的按钮行为。
     */
    cycleShuffleSlot: () => {
      const { playMode, queue, currentIndex } = get();
      if (playMode !== 'shuffle') {
        get().setPlayMode('shuffle');
        return;
      }
      set(reshuffle(queue.length, currentIndex));
    },

    setQuality: (quality) => {
      const before = get().effectiveQuality;
      set({
        quality,
        effectiveQuality: get().dataSaver ? 'standard' : quality,
        qualityExplicit: true,
      });
      persist();
      if (before !== get().effectiveQuality) {
        track('player_quality_change', {
          song_id: get().currentSong?.id ? String(get().currentSong?.id) : null,
          from: before,
          to: get().effectiveQuality,
          auto: false,
        });
      }
    },

    setEffectiveQuality: (effectiveQuality) => {
      // 只在真正变化时写：`load()` 成功后会回写，避免无意义的重渲染
      const before = get().effectiveQuality;
      if (before !== effectiveQuality) {
        set({ effectiveQuality });
        // 服务端降级 / 弱网自动降级都走这里；与用户手选区分开，才能评估自动降级的影响面
        track('player_quality_change', {
          song_id: get().currentSong?.id ? String(get().currentSong?.id) : null,
          from: before,
          to: effectiveQuality,
          auto: true,
        });
      }
    },

    setPlaybackRate: (playbackRate) => {
      if (!isPlaybackRate(playbackRate)) return;
      const before = get().playbackRate;
      set({ playbackRate });
      persist();
      if (before !== playbackRate) {
        track('player_speed_change', {
          song_id: get().currentSong?.id ? String(get().currentSong?.id) : null,
          from: before,
          to: playbackRate,
        });
      }
    },

    setAutoQualityEnabled: (autoQuality) => set({ autoQuality }),

    applyServerSettings: ({ defaultQuality, autoQuality, dataSaver }) => {
      const next: Partial<PlayerState> = {
        autoQuality: Number(autoQuality) === 1,
        dataSaver: Number(dataSaver) === 1,
      };
      // 用户手动选过音质就以本地为准；没选过才采纳账号默认音质
      if (!get().qualityExplicit) {
        next.quality = defaultQuality;
        next.effectiveQuality = Number(dataSaver) === 1 ? 'standard' : defaultQuality;
      } else if (Number(dataSaver) === 1) {
        next.effectiveQuality = 'standard';
      } else {
        next.effectiveQuality = get().quality;
      }
      set(next);
      persist();
    },

    setRemoteProgress: (remoteProgress) => set({ remoteProgress }),

    applyRemoteResume: () => {
      const remote = get().remoteProgress;
      if (!remote) return;
      set({
        remoteProgress: null,
        isPlaying: true,
        seekRequest: { time: remote.position, nonce: (get().seekRequest?.nonce ?? 0) + 1 },
      });
    },

    addToQueue: (song) => {
      const { queue, currentIndex } = get();
      if (queue.length >= QUEUE_LIMIT) {
        toastWarning(`播放队列已满（${QUEUE_LIMIT} 首），请先清空或移除部分歌曲`);
        return;
      }
      const next = [...queue, song];
      set({ queue: next, ...reshuffle(next.length, currentIndex) });
      persist();
    },
    removeFromQueue: (index) => {
      const { queue, currentIndex } = get();
      if (index < 0 || index >= queue.length) return;
      const next = queue.filter((_, i) => i !== index);
      let nextIndex = currentIndex;
      if (index < currentIndex) nextIndex = currentIndex - 1;
      if (index === currentIndex) nextIndex = Math.min(currentIndex, Math.max(0, next.length - 1));
      set({ queue: next, currentIndex: nextIndex, currentSong: next[nextIndex] ?? null, ...reshuffle(next.length, nextIndex) });
      persist();
    },
    clearQueue: () => {
      set({
        queue: [],
        currentIndex: 0,
        currentSong: null,
        isPlaying: false,
        currentTime: 0,
        duration: 0,
        currentLine: -1,
        pendingSeek: null,
        effectiveQuality: get().quality,
        remoteProgress: null,
        shuffleOrder: [],
        shufflePos: 0,
      });
      persist();
    },

    /** 队列抽屉里点某一行：等价于切歌，但播放来源标成 queue */
    jumpTo: (index) => {
      set({ playSource: 'queue' });
      goTo(index, false);
    },

    setCurrentLine: (currentLine) => {
      // 行号没变就不 set：timeupdate ≈ 4Hz，无脑写会让所有订阅者每秒重渲染 4 次（§5.4）
      if (get().currentLine !== currentLine) set({ currentLine });
    },

    setLyricData: (lyric) => {
      const prev = get().lyric;
      // 数据没变就不 set：useLyric 每次返回新对象，直接 set 会每帧触发面板重渲染
      if (
        prev.timed === lyric.timed &&
        prev.empty === lyric.empty &&
        prev.hasWordLevel === lyric.hasWordLevel &&
        prev.skippedLines === lyric.skippedLines &&
        prev.lines === lyric.lines
      ) {
        return;
      }
      set({ lyric, currentLine: -1 });
    },

    noteFailure: (songId) => {
      const { lastPlayedId, failStreak } = get();
      // 同一首歌的重复失败只算一次：否则一首坏歌自己就能把熔断打开（§5.3）
      if (lastPlayedId === songId) return failStreak;
      const next = failStreak + 1;
      set({ lastPlayedId: songId, failStreak: next });
      return next;
    },
    clearFailures: () => {
      if (get().failStreak !== 0 || get().lastPlayedId !== null) set({ failStreak: 0, lastPlayedId: null });
    },

    restoreLastPlay: (userId) => {
      const last = readLastPlay();
      if (!last || last.userId !== userId) return;
      const { queue } = get();
      const index = queue.findIndex((s) => s.id === last.songId);
      // 队列里没有这首歌（清了队列 / 换了浏览器）就放弃，不做"只恢复一首歌"的队列重建
      if (index < 0) return;
      const song = queue[index];
      // EX-16-005-4：已下架/无版权的曲目不恢复（本地预判，服务端还会再判一次）
      if (!isPlayable(song, get().quality)) return;
      set({
        currentIndex: index,
        currentSong: song,
        currentTime: last.position,
        effectiveQuality: get().dataSaver ? 'standard' : get().quality,
        remoteProgress: null,
        // 恢复为**暂停态**并 seek 到断点；不自动 play（浏览器会拦截无交互的自动播放）
        isPlaying: false,
        seekRequest: { time: last.position, nonce: 1 },
        pendingSeek: last.position,
        resumeHint: `上次播放到 ${formatTime(last.position)}`,
        ...reshuffle(queue.length, index),
      });
    },

    setQueueDrawerOpen: (queueDrawerOpen) => set({ queueDrawerOpen }),
    setHotkeyHelpOpen: (hotkeyHelpOpen) => set({ hotkeyHelpOpen }),
    openOverlay: () => set({ overlayOpen: true }),
    closeOverlay: () => set({ overlayOpen: false, queueDrawerOpen: false }),
    toggleOverlay: () => set((s) => ({ overlayOpen: !s.overlayOpen, queueDrawerOpen: false })),
  };
});
