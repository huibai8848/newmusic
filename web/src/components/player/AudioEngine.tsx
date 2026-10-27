import { useEffect, useRef } from 'react';
import { fetchPlayUrl, QUALITY_LADDER, QUALITY_LABEL, type PlayUrlVO, type Quality } from '@/api/player';
import { playApi } from '@/api/play';
import { usePlayerStore, FAIL_STREAK_LIMIT, PLAYBACK_RATES } from '@/stores/playerStore';
import { useUserStore } from '@/stores/userStore';
import { useThemeStore } from '@/stores/themeStore';
import { useWsStore } from '@/stores/wsStore';
import { useLyric } from '@/hooks/useLyric';
import { pushHistory } from '@/services/history';
import { saveLastPlay, saveLastPlayThrottled } from '@/services/lastPlay';
import { playCompletionRate, track } from '@/services/track';
import {
  currentSource,
  endPlay,
  endPlayIfDifferent,
  flushProgress,
  startPlay,
  tickPlay,
} from '@/services/playReport';
import {
  STALL_THRESHOLD_MS,
  planAutoDowngrade,
  shouldPromptRemoteResume,
} from '@/components/player/playbackPolicy';
import { findCurrentLine } from '@/utils/lrc';
import { toastWarning } from '@/utils/feedback';

/**
 * 音频引擎（模块 02 D-07，模块 07 T711 重写）。
 *
 * `<audio>` 挂在应用根节点（`main.tsx`）且**不在任何路由树内**，路由切换不销毁，
 * 因此播放不会中断（AC-11-201）。本组件是唯一直接操作 DOM 的地方，
 * 也是唯一订阅 `timeupdate` 的地方（所以歌词行号由它算、写进 store）。
 *
 * 模块 07 新增四件事：
 * 1. **地址来自服务端**：不再从歌曲字段里扒 URL，改走 `GET /player/url`（带签名与过期时间）；
 * 2. **失败退避重试**（§5.3）：网络类错误重试 2 次（1s / 3s，重试前重新签发地址），
 *    解码错误不重试，格式不支持则降一档音质；
 * 3. **连续 3 首熔断**（`BR-16-089`）：同一首歌的重复失败不累加，避免一首坏歌直接锁死播放器；
 * 4. **断点续播**：`timeupdate` 节流 5 秒写一次 `ml.lastPlay`。
 *
 * 模块 12 又加了四件事（判定逻辑抽到 `playbackPolicy.ts` 以便单测）：
 * 1. **实际生效音质**：服务端降级后回写 `effectiveQuality`，但不能再触发一次换源；
 * 2. **同曲换音质保位置**：切档前记下 `audio.currentTime`，新源就绪后 seek 回去；
 * 3. **倍速**：7 档 + `preservesPitch`，音乐房内强制 1.0；
 * 4. **弱网自动降级与跨设备续播提示**：只降不升、只提示不自动跳。
 *
 * 重试计数、退避定时器、当前地址都放在 `attemptRef` 里而不是 store（D-11）：
 * 它们与 `HTMLMediaElement` 的生命周期强绑定，进 store 会引入无意义的全局可变状态。
 */

/** `MediaError.code` 的取值（用不了 `MediaError.` 常量：它只在实例上挂） */
const ERR_ABORTED = 1;
const ERR_NETWORK = 2;
const ERR_DECODE = 3;
const ERR_SRC_NOT_SUPPORTED = 4;

/** 网络错误的退避间隔（毫秒）；下标即第几次重试（`BR-16-088` 的 1s / 3s） */
const RETRY_BACKOFF_MS = [1_000, 3_000];

interface Attempt {
  /** 当前正在加载的歌曲；用于丢弃"切歌后才返回"的过期请求 */
  songId: string | null;
  /** 本次加载请求的音质（重试降档后会变） */
  quality: Quality;
  /** 已重试次数（仅 NETWORK） */
  retryCount: number;
  /** 已因 SRC_NOT_SUPPORTED 降过档；只降一次 */
  downgraded: boolean;
  /** 退避定时器 */
  timer: number | null;
  /** 递增序号：异步加载返回时若序号变了说明已切歌，结果丢弃 */
  seq: number;
}

export function AudioEngine(): null {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const attemptRef = useRef<Attempt>({ songId: null, quality: 'standard', retryCount: 0, downgraded: false, timer: null, seq: 0 });
  /** 弱网降级的状态机：进房间/换歌都从零算 */
  const stallRef = useRef({ waitingSince: null as number | null, count: 0, downgradeAt: [] as number[] });
  /** 上一次加载的歌曲，用来区分「换歌」与「同曲换音质」 */
  const loadedSongRef = useRef<string | null>(null);
  /** 最近一次「加载成功后回写 effectiveQuality」的标记，避免回写本身再触发一次换源 */
  const servedRef = useRef<{ songId: string; quality: Quality } | null>(null);
  /** 埋点会话：只在真正出声后开始，切歌时把上一首结算成 skip */
  const trackPlayRef = useRef<{ songId: string | null; startedAt: number; position: number }>({
    songId: null,
    startedAt: 0,
    position: 0,
  });

  const currentSong = usePlayerStore((s) => s.currentSong);
  const effectiveQuality = usePlayerStore((s) => s.effectiveQuality);
  const playbackRate = usePlayerStore((s) => s.playbackRate);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const volume = usePlayerStore((s) => s.volume);
  const muted = usePlayerStore((s) => s.muted);
  const seekRequest = usePlayerStore((s) => s.seekRequest);
  const replayToken = usePlayerStore((s) => s.replayToken);
  // 注意：`repeatOne` 不在这里订阅——它只被 `ended` 监听器读，而监听器是长期存活的闭包，
  // 必须用 `getState()` 现取（订阅到的值会停在注册那一刻），见下方 onEnded
  const userId = useUserStore((s) => s.user?.id ?? null);
  // 音乐房内倍速必须锁定 1.0（BR-21-005）：房间播放进度由房主/服务端同步，倍速会让各端错位
  const roomId = useWsStore((s) => s.roomId);

  /** 把当前倍速应用到 `<audio>`；`preservesPitch` 保证变速不变调 */
  const applyRate = (audio: HTMLAudioElement): void => {
    const rate = useWsStore.getState().roomId ? 1 : usePlayerStore.getState().playbackRate;
    audio.playbackRate = rate;
    audio.defaultPlaybackRate = rate;
    const el = audio as HTMLAudioElement & {
      preservesPitch?: boolean;
      webkitPreservesPitch?: boolean;
      mozPreservesPitch?: boolean;
    };
    el.preservesPitch = true;
    el.webkitPreservesPitch = true;
    el.mozPreservesPitch = true;
  };

  // 歌词：挂在引擎里而不是面板里，保证整页只取一次、只解析一次
  const lyric = useLyric(currentSong?.id);

  /** 把解析结果发布到 store，供任意数量的面板读取 */
  useEffect(() => {
    usePlayerStore.getState().setLyricData({
      lines: lyric.lines,
      timed: lyric.timed,
      hasWordLevel: lyric.hasWordLevel,
      empty: lyric.empty,
      skippedLines: lyric.skippedLines,
    });
  }, [lyric.lines, lyric.timed, lyric.hasWordLevel, lyric.empty, lyric.skippedLines]);

  useEffect(() => {
    if (lyric.skippedLines > 0 && currentSong) {
      console.warn(`[AudioEngine] 《${currentSong.name}》歌词有 ${lyric.skippedLines} 行无法解析，已跳过`);
    }
  }, [lyric.skippedLines, currentSong]);

  /**
   * 创建 audio 元素（必须放在最前面的 effect 里）。
   *
   * 不在 render 阶段创建：StrictMode 下 render 会被调用两次，会导致多出一个
   * 无法回收的 Audio 对象（并且它可能已经开始下载音频）。
   * effect 按声明顺序执行，因此后面的 effect 一定能拿到实例。
   */
  useEffect(() => {
    const audio = new Audio();
    audio.preload = 'metadata';
    audioRef.current = audio;
    return () => {
      audio.pause();
      audio.removeAttribute('src');
      audioRef.current = null;
    };
  }, []);

  /**
   * 加载并播放一首歌（唯一改 `audio.src` 的地方）。
   *
   * @param resumeAt 换源后要跳到的秒数（网络重试保留进度用）；`undefined` 表示从头播
   */
  const load = async (songId: string, wantQuality: Quality, resumeAt?: number): Promise<void> => {
    const audio = audioRef.current;
    if (!audio) return;
    const attempt = attemptRef.current;
    attempt.songId = songId;
    attempt.quality = wantQuality;
    // seq 在**取地址之前**就自增：两次加载并发时，先返回的那个也不该覆盖后发起的
    const seq = (attempt.seq += 1);

    usePlayerStore.getState().setBuffering(true);
    let vo: PlayUrlVO;
    try {
      // userId 从 getState() 现取而不是闭包捕获：`load` 会被事件监听器长期持有，
      // 闭包里的 userId 会停在挂载那一刻（退出登录后仍在用旧账号签名）
      vo = await fetchPlayUrl(songId, wantQuality, {
        userId: useUserStore.getState().user?.id ?? null,
        force: resumeAt !== undefined,
        silent: true,
      });
    } catch (err) {
      if (seq !== attemptRef.current.seq) return;
      handleFailure(err, songId);
      return;
    }
    if (seq !== attemptRef.current.seq) return;

    // 服务端降过档 → 告诉用户实际听到的是什么（否则"选了无损却是标准"会被当成 bug）
    if (vo.downgraded && wantQuality !== 'standard') {
      toastWarning(vo.notice || `该歌曲暂无${QUALITY_LABEL[wantQuality]}音源，已降级为${QUALITY_LABEL[vo.quality]}`);
    }
    // 记录**实际**下发的档位：换源 effect 用它判断「这一档已经加载过」，
    // 否则服务端降级回写 effectiveQuality 会再触发一次加载
    attempt.quality = vo.quality;
    // 只有「实际档位与 store 里不同」才会触发换源 effect；这时才留标记。
    // 档位没变时留标记会污染后续「同曲重播」的判断。
    if (usePlayerStore.getState().effectiveQuality !== vo.quality) {
      servedRef.current = { songId, quality: vo.quality };
      usePlayerStore.getState().setEffectiveQuality(vo.quality);
    } else {
      servedRef.current = null;
    }

    // 先把服务端已知的时长填进 store：元数据可能还在路上（弱网、wav 大文件），
    // 这段时间进度条会因为 duration=0 而处于禁用态，看起来像「拉不动」
    if (vo.duration && vo.duration > 0 && !usePlayerStore.getState().duration) {
      usePlayerStore.getState().setDuration(vo.duration);
    }
    audio.src = vo.url;
    // 新 src 不一定保留倍速（部分浏览器会重置），每次换源都重新应用
    applyRate(audio);
    // 「继续播放」/断点续播的起播位置必须在 src 之后应用：
    // 先 seek 再赋 src 会被浏览器重置回 0。重试时的 resumeAt 优先级低于显式续播点。
    const pendingSeek = usePlayerStore.getState().consumePendingSeek();
    const startAt = pendingSeek ?? (resumeAt !== undefined && resumeAt > 0 ? resumeAt : null);
    if (startAt !== null && startAt > 0) {
      // 直接赋值即可：浏览器会在元数据就绪后自己 seek，不需要等 loadedmetadata
      audio.currentTime = startAt;
    }
    if (usePlayerStore.getState().isPlaying) {
      void audio.play().catch((err: unknown) => {
        usePlayerStore.getState().setPlaying(false);
        // 浏览器自动播放策略会拒绝"无用户交互的播放"，这是正常情况，不弹 Toast
        console.warn('[AudioEngine] play rejected', err);
      });
    }
  };

  /** 一次加载彻底失败后的处置：能重试就重试，不能就跳下一首或熔断（§5.3） */
  const handleFailure = (err: unknown, songId: string): void => {
    const audio = audioRef.current;
    const attempt = attemptRef.current;
    // 地址取不到（无版权 / 已下架 / 网络）：按"取地址失败"重试，语义与音频 NETWORK 错误一致
    if (attempt.retryCount < RETRY_BACKOFF_MS.length) {
      const delay = RETRY_BACKOFF_MS[attempt.retryCount];
      attempt.retryCount += 1;
      attempt.timer = window.setTimeout(() => {
        attempt.timer = null;
        // 重试前重新签发：很可能是 URL 过期而不是网络问题
        void load(songId, attempt.quality, audio ? audio.currentTime : undefined);
      }, delay);
      console.warn(`[AudioEngine] 取播放地址失败，${delay}ms 后第 ${attempt.retryCount} 次重试`, err);
      return;
    }
    advanceAfterFailure(songId, err);
  };

  /**
   * 连续失败判定与跳曲（`BR-16-089`）。
   *
   * 连败按**不同歌曲**累计：同一首歌的重试不计入，否则一首坏歌 3 次重试就能把熔断打开。
   */
  const advanceAfterFailure = (songId: string, err?: unknown): void => {
    const streak = usePlayerStore.getState().noteFailure(songId);
    if (streak >= FAIL_STREAK_LIMIT) {
      // 熔断：停播但保留播放按钮可用，让用户手动重试
      usePlayerStore.getState().setBuffering(false);
      usePlayerStore.getState().setPlaying(false);
      usePlayerStore.getState().setError('网络异常，请检查网络后重试');
      toastWarning('连续多首歌曲播放失败，已暂停自动播放');
      return;
    }
    const state = usePlayerStore.getState();
    const name = state.currentSong?.name ?? '当前歌曲';
    console.warn('[AudioEngine] 跳过失败的歌曲', songId, err);
    // 先把 loading 收掉：跳过成功后要等下一首的 `playing` 事件才清，
    // 但如果下面直接停播（队列只剩这一首），就永远等不到那个事件。
    state.setBuffering(false);
    // 队列只有这一首时 `next(true)` 会走 `replayFromStart()`，等于拿同一首坏歌无限重试：
    // 同曲失败不累计连败，熔断永远打不开，用户看到的是转圈 + 反复请求。
    // 这里直接停播，把播放按钮留给用户手动重试。
    if (state.queue.length <= 1) {
      state.setError(`《${name}》播放失败，请稍后重试`);
      state.setPlaying(false);
      return;
    }
    state.setError(`《${name}》播放失败，已跳过`);
    state.next(true);
  };

  /**
   * 弱网自动降级（BR-21-045）：缓冲结束后判定，只降不升。
   *
   * 约束：`autoQuality` 关闭时不动；音乐房内不动（音质由房间统一）；
   * 5 分钟内最多降 2 次，防止网络抖动时一路降到 standard 还反复弹提示。
   */
  const maybeAutoDowngrade = (): void => {
    const state = usePlayerStore.getState();
    const stall = stallRef.current;
    const plan = planAutoDowngrade({
      stallCount: stall.count,
      effectiveQuality: state.effectiveQuality,
      autoQuality: state.autoQuality,
      inRoom: Boolean(useWsStore.getState().roomId),
      downgradeAt: stall.downgradeAt,
      now: Date.now(),
    });
    if (!plan) return;
    stall.downgradeAt = plan.downgradeAt;
    stall.count = 0;
    state.setEffectiveQuality(plan.quality);
    toastWarning(`网络较慢，已自动切换为${QUALITY_LABEL[plan.quality]}（可在播放设置中关闭自动切换）`);
  };

  /** 切歌 / 换音质：取地址并换源（BR-11-045：按需获取，不预加载整条队列） */
  useEffect(() => {
    const attempt = attemptRef.current;
    if (attempt.timer !== null) {
      window.clearTimeout(attempt.timer);
      attempt.timer = null;
    }
    attempt.retryCount = 0;
    attempt.downgraded = false;

    const audio = audioRef.current;
    if (!currentSong) {
      attempt.songId = null;
      attempt.seq += 1; // 让"在途"的加载失效
      loadedSongRef.current = null;
      stallRef.current = { waitingSince: null, count: 0, downgradeAt: stallRef.current.downgradeAt };
      endPlayIfDifferent(null);
      audio?.removeAttribute('src');
      return;
    }

    const songId = currentSong.id;
    const sameSong = loadedSongRef.current === songId;
    // 同曲同档位：可能是 `load()` 刚把服务端实际下发的音质回写成 effectiveQuality。
    // 这种回写不能再次换源，否则每次降级都会多拉一次音频、并打断刚起播的进度。
    if (sameSong && attempt.songId === songId && attempt.quality === effectiveQuality) {
      const served = servedRef.current;
      if (served && served.songId === songId && served.quality === effectiveQuality) {
        servedRef.current = null;
        return;
      }
      // 其余情况是「同一首歌被重新选中」（列表点同一首、单曲队列点下一首）：
      // 保持模块 07 的语义从头重播，而不是把这次点击吞掉
      stallRef.current = { waitingSince: null, count: 0, downgradeAt: stallRef.current.downgradeAt };
      void load(songId, effectiveQuality);
      return;
    }

    if (!sameSong) {
      // 换歌重置弱网计数；降级次数的时间窗口跨歌保留，防止靠反复切歌绕过上限
      stallRef.current = { waitingSince: null, count: 0, downgradeAt: stallRef.current.downgradeAt };
      pushHistory(currentSong);
      // 模块 08：切歌时先结算上一首（服务端行为数据是推荐画像的输入）。
      // 同一首歌换音质不结束，否则一次音质切换会多出一条历史。
      endPlayIfDifferent(songId);
    }

    // 同曲换音质保留播放位置（BR-21-014）；换歌从头开始
    const resumeAt =
      sameSong && audio && Number.isFinite(audio.currentTime) && audio.currentTime > 0 ? audio.currentTime : undefined;
    loadedSongRef.current = songId;
    void load(songId, effectiveQuality, resumeAt);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- load 每次渲染都会重建，依赖它会导致无限加载
  }, [currentSong, effectiveQuality]);

  /** 播放/暂停：浏览器自动播放策略要求首次播放由用户交互触发（BR-11-046） */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !currentSong) return;
    if (isPlaying) {
      if (audio.src) {
        // `audio.error` 非空说明当前 src 已被浏览器判死（解码失败 / 来源不支持）。
        // 直接 play() 只会再报一次 NotSupportedError，并把这个新发起的播放翻回暂停——
        // 用户从详情页点「重新播放」时还会触发一次换源，于是出现「点了没反应」。
        // 有在途加载时交给它（`load()` 在 isPlaying 为 true 时会自己播）；否则重新取地址。
        if (audio.error) {
          if (!usePlayerStore.getState().isBuffering) {
            void load(currentSong.id, effectiveQuality);
          }
          return;
        }
        void audio.play().catch((err: unknown) => {
          usePlayerStore.getState().setPlaying(false);
          console.warn('[AudioEngine] play rejected', err);
        });
      }
      // 还没有 src（地址在途）时什么都不做：load() 完成后会自己 play
    } else {
      audio.pause();
    }
  }, [isPlaying, currentSong]);

  /** 从头重播（D-15）：`single` 模式手动切歌、以及「上一首」回到开头都会走这里 */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || replayToken === 0) return;
    audio.currentTime = 0;
    if (audio.src) {
      void audio.play().catch((err: unknown) => console.warn('[AudioEngine] replay rejected', err));
    }
  }, [replayToken]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    audio.volume = volume;
    audio.muted = muted;
  }, [volume, muted]);

  /** 倍速：进音乐房强制 1.0；离开后恢复用户此前选择的倍速 */
  useEffect(() => {
    const audio = audioRef.current;
    if (audio) applyRate(audio);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- applyRate 只读 getState()，无需进依赖
  }, [playbackRate, roomId]);

  /** seek：通过 nonce 识别新请求，进度条拖动与歌词行点击共用 */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !seekRequest) return;
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      audio.currentTime = Math.max(0, Math.min(seekRequest.time, audio.duration));
    } else {
      // 时长还没就绪（元数据在途）也允许先设，浏览器会自己排到 seekable 之后
      audio.currentTime = Math.max(0, seekRequest.time);
    }
  }, [seekRequest]);

  /** 进度、歌词行号、断点续播、各类状态回写 */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;

    const onTimeUpdate = (): void => {
      const state = usePlayerStore.getState();
      const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
      state.setProgress(audio.currentTime, duration);

      // 歌词行号：二分查找，且只在行号变化时才写 store（§5.4）
      const lines = state.lyric.lines;
      if (lines.length > 0) {
        state.setCurrentLine(findCurrentLine(lines, audio.currentTime));
      }

      // 断点续播：节流 5 秒（BR-16-036）
      const song = state.currentSong;
      if (song) {
        saveLastPlayThrottled(song.id, audio.currentTime, duration, useUserStore.getState().user?.id ?? null);
      }

      // 模块 08：播放行为上报（内部 30 秒节流；只有真正在播时才累计）
      tickPlay(audio.currentTime, duration, state.isPlaying);
      // 模块 17：记下最后位置，切歌时用它对「跳过」与「播完」做区分
      trackPlayRef.current.position = audio.currentTime;
    };

    const onEnded = (): void => {
      const state = usePlayerStore.getState();
      // 模块 17：自然播完 = 有效播放，完成率按 100% 记（服务端仍会复核时长）
      const tracked = trackPlayRef.current;
      const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
      if (tracked.songId) {
        track('player_song_end', {
          song_id: tracked.songId,
          play_duration: Math.round(duration || audio.currentTime),
          completion_rate: duration > 0 ? playCompletionRate(duration, duration) : 1,
          is_valid_play: true,
        });
        trackPlayRef.current = { songId: null, startedAt: 0, position: 0 };
      }
      // 自然播完计为有效播放，先结算再决定下一首/重播
      endPlay(true);
      // 单曲循环就地重播：走 store.next() 只会把 currentIndex 设回同一值，
      // React 看不到变化、<audio> 不会被重新播放（D-15）
      if (state.repeatOne) {
        audio.currentTime = 0;
        state.setCurrentLine(-1);
        void audio.play().catch((err: unknown) => console.warn('[AudioEngine] repeat rejected', err));
        return;
      }
      state.next(true);
    };

    const onWaiting = (): void => {
      usePlayerStore.getState().setBuffering(true);
      // 只在「已经出声后的再次缓冲」计时：起播慢由加载失败重试兜底，
      // 否则每次切歌都算一次弱网，会把音质一路降下去
      if (audio.currentTime > 0.5 && stallRef.current.waitingSince === null) {
        stallRef.current.waitingSince = Date.now();
      }
    };

    /** 暂停时补报一次进度：否则「听到一半暂停、关页」最多丢 30 秒的续播点 */
    const onPause = (): void => {
      // 暂停期间的等待不算弱网：否则「缓冲时暂停 5 分钟再播」会被误判成一次超长缓冲
      stallRef.current.waitingSince = null;
      flushProgress();
      // 模块 17：切歌换源时也会触发 pause，只有歌没变才是「用户暂停」
      const tracked = trackPlayRef.current;
      const song = usePlayerStore.getState().currentSong;
      if (tracked.songId && song && String(song.id) === tracked.songId) {
        track('player_song_pause', {
          song_id: tracked.songId,
          position: Math.round(audio.currentTime),
        });
      }
    };

    const onPlaying = (): void => {
      const state = usePlayerStore.getState();
      const stall = stallRef.current;
      if (stall.waitingSince !== null) {
        const waited = Date.now() - stall.waitingSince;
        stall.waitingSince = null;
        if (waited >= STALL_THRESHOLD_MS) stall.count += 1;
      }
      state.setBuffering(false);
      state.setPlaying(true);
      state.setError(null);
      // 任一成功播放都清零连败（BR-16-089）
      state.clearFailures();
      // 模块 08：真正开始出声时才记一条播放历史（恢复队列但没播不算）。
      // 上报实际生效音质而不是用户偏好：行为分析要的是真正听到的档位
      const song = state.currentSong;
      if (song) {
        startPlay(song, currentSource(), state.effectiveQuality);
        // 模块 17：真正出声才算一次播放；同一首的暂停恢复不重复计
        const songId = String(song.id);
        const tracked = trackPlayRef.current;
        if (tracked.songId !== songId) {
          if (tracked.songId) {
            track('player_song_skip', {
              song_id: tracked.songId,
              position: Math.round(tracked.position),
              play_duration: Math.round(tracked.position),
            });
          }
          trackPlayRef.current = { songId, startedAt: Date.now(), position: 0 };
          track('player_song_play', {
            song_id: songId,
            source: currentSource(),
            quality: state.effectiveQuality,
            position: Math.round(audio.currentTime),
            duration: Number.isFinite(audio.duration) ? Math.round(audio.duration) : null,
          });
        }
      }
      maybeAutoDowngrade();
    };

    const onError = (): void => {
      const mediaError = audio.error;
      const code = mediaError?.code ?? ERR_NETWORK;
      const attempt = attemptRef.current;
      const songId = attempt.songId;
      if (!songId) return;

      // 1 ABORTED：用户主动切歌/暂停导致的中止，不是错误
      if (code === ERR_ABORTED) return;

      if (code === ERR_DECODE) {
        // 3 DECODE：文件本身坏了，重试同一个文件没有意义，也不保留进度（§5.3）
        usePlayerStore.getState().setError(`《${usePlayerStore.getState().currentSong?.name ?? '当前歌曲'}》音频文件损坏，已跳过`);
        advanceAfterFailure(songId, mediaError);
        return;
      }

      if (code === ERR_SRC_NOT_SUPPORTED && !attempt.downgraded) {
        // 4 SRC_NOT_SUPPORTED：降一档音质重试一次（lossless → high → standard）
        const ladder = QUALITY_LADDER[attempt.quality];
        const idx = ladder.indexOf(attempt.quality);
        const lower = idx >= 0 ? ladder[idx + 1] : undefined;
        if (lower) {
          attempt.downgraded = true;
          toastWarning(`当前音质不可用，已降级为${lower === 'high' ? '高清' : '标准'}`);
          void load(songId, lower, audio.currentTime);
          return;
        }
        advanceAfterFailure(songId, mediaError);
        return;
      }

      // 2 NETWORK（以及已降过档的 4）：退避重试 1s / 3s，重试前重新签发地址
      if (attempt.retryCount < RETRY_BACKOFF_MS.length) {
        const delay = RETRY_BACKOFF_MS[attempt.retryCount];
        attempt.retryCount += 1;
        const resumeAt = audio.currentTime;
        attempt.timer = window.setTimeout(() => {
          attempt.timer = null;
          void load(songId, attempt.quality, resumeAt);
        }, delay);
        console.warn(`[AudioEngine] 播放失败(code=${code})，${delay}ms 后第 ${attempt.retryCount} 次重试`);
        return;
      }
      advanceAfterFailure(songId, mediaError);
    };

    audio.addEventListener('timeupdate', onTimeUpdate);
    audio.addEventListener('ended', onEnded);
    audio.addEventListener('waiting', onWaiting);
    audio.addEventListener('playing', onPlaying);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('error', onError);
    return () => {
      audio.removeEventListener('timeupdate', onTimeUpdate);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('waiting', onWaiting);
      audio.removeEventListener('playing', onPlaying);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('error', onError);
    };
  }, []);

  /** 启动时恢复断点续播（BR-16-039）；7 天前的记录不再恢复，见 services/lastPlay */
  useEffect(() => {
    usePlayerStore.getState().restoreLastPlay(userId);
  }, [userId]);

  /**
   * 登录后同步账号播放设置（模块 12 FR-21-008）。
   *
   * 失败静默：设置拉不到时沿用本地偏好，不能因为一个非关键请求阻塞播放。
   * 默认音质只在用户「没手动选过」时采纳，避免覆盖本机更明确的选择。
   */
  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    void playApi
      .settings()
      .then((settings) => {
        if (cancelled) return;
        const state = usePlayerStore.getState();
        state.applyServerSettings({
          defaultQuality: settings.defaultQuality,
          autoQuality: settings.autoQuality,
          dataSaver: settings.dataSaver,
        });
        if ((PLAYBACK_RATES as readonly number[]).includes(settings.playbackRate)) {
          state.setPlaybackRate(settings.playbackRate);
        }
        // 主题只改本地显示，失败也没有副作用
        useThemeStore.getState().setMode(settings.theme);
      })
      .catch(() => {
        /* 设置同步失败不影响播放 */
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  /**
   * 跨设备续播（模块 12 FR-21-061 / BR-21-066）：只提示，绝不自动跳转。
   *
   * 延迟 1.5 秒再查：让本地断点续播与首次换源先落定，避免两套续播逻辑互相抢 seek。
   * 只提示「有意义的进度」——开头 5 秒内、结尾 10 秒内、本机上报的都不提示。
   */
  useEffect(() => {
    if (!currentSong || !userId || roomId) return;
    const songId = currentSong.id;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void playApi
        .progressOf(songId)
        .then((vo) => {
          if (cancelled) return;
          const state = usePlayerStore.getState();
          if (state.currentSong?.id !== songId) return;
          // 本地已经播起来了就以本地为准，别再弹「从别处继续」
          const localPosition = audioRef.current?.currentTime ?? 0;
          if (!shouldPromptRemoteResume(vo, localPosition, state.pendingSeek !== null)) return;
          state.setRemoteProgress({
            position: vo.position,
            duration: vo.duration,
            deviceId: vo.deviceId,
            updateTime: vo.updateTime,
          });
        })
        .catch(() => {
          /* 续播提示不是关键路径：未登录/网络失败都静默 */
        });
    }, 1_500);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [currentSong, userId, roomId]);

  /** 关页前把进度落盘（不受 5 秒节流限制），否则"刚听到一半就关掉"会丢 */
  useEffect(() => {
    const flush = (): void => {
      const audio = audioRef.current;
      const song = usePlayerStore.getState().currentSong;
      if (!audio || !song) return;
      saveLastPlay(
        song.id,
        audio.currentTime,
        Number.isFinite(audio.duration) ? audio.duration : 0,
        useUserStore.getState().user?.id ?? null,
      );
      // 关页也要把本次播放结算掉：keepalive fetch 不会被浏览器取消
      endPlay(false, true);
    };
    window.addEventListener('pagehide', flush);
    return () => {
      window.removeEventListener('pagehide', flush);
      // 根组件卸载（示例/测试环境）时同样结算，避免留下「一直播到天荒地老」的历史
      endPlay(false);
    };
  }, []);

  /** 卸载时清掉挂起的退避定时器，避免组件没了还去改 store */
  useEffect(() => {
    const attempt = attemptRef.current;
    return () => {
      if (attempt.timer !== null) window.clearTimeout(attempt.timer);
    };
  }, []);

  return null;
}
