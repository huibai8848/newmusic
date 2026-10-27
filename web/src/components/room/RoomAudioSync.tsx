import { useEffect, useRef, useState } from 'react';
import { Button } from 'antd';
import { CustomerServiceOutlined } from '@ant-design/icons';
import { fetchPlayUrl } from '@/api/player';
import { roomApi } from '@/api/room';
import {
  HARD_CHECK_INTERVAL_MS,
  PLAY_STATUS,
  SOFT_CALIBRATION_MS,
  SOFT_CHECK_INTERVAL_MS,
  SOFT_THRESHOLD_MS,
  expectedPositionMs,
  planCalibration,
  serverNow,
} from '@/services/roomSync';
import { usePlayerStore } from '@/stores/playerStore';
import { useUserStore } from '@/stores/userStore';
import { useWsStore } from '@/stores/wsStore';

/**
 * 音乐房音频同步（模块 13 FR-22-001 / FR-22-002）。
 *
 * 与全局 `AudioEngine` **分离**：房间音频是一条独立的 `<audio>`，进房即暂停全局
 * 播放器，离房销毁。这样房间的硬校准 seek 不会污染用户自己的播放进度，
 * 也避免两套「自动下一首」逻辑互相打架。
 *
 * 职责边界：
 * - `roomSync.ts` 只算规则（期望位置 / 漂移分级 / 倍速）；
 * - 本组件只负责 I/O：取地址、改 `currentTime` / `playbackRate`、上报收听时长；
 * - 权威状态只来自 `wsStore.playState`，本地永远不「自己往下播」。
 *
 * 刻意不做的事：本地队列自动切歌。歌曲结束由**房主/房管**客户端发起
 * `room.switch`（后端不自动推进），普通成员等待 `ROOM_PLAY_STATE`。
 */

interface Latest {
  roomId: string | null;
  playState: ReturnType<typeof useWsStore.getState>['playState'];
  clockOffsetMs: number;
  queue: ReturnType<typeof useWsStore.getState>['queue'];
  canControl: boolean;
}

const HAVE_FUTURE_DATA = 3;
/**
 * 小于这个偏差就不写 `currentTime`。
 *
 * 写 `currentTime` 会触发 `seeking`/`waiting`/`canplay` 一串媒体事件；如果事件回调又
 * 无条件 seek，就会形成「seek → canplay → seek」死循环，听感是持续卡顿。
 * 100ms 以内的对齐人耳不可辨，直接跳过。
 */
const SEEK_EPSILON_MS = 100;

export function RoomAudioSync({ canControl }: { canControl: boolean }): JSX.Element | null {
  const roomId = useWsStore((s) => s.roomId);
  const playState = useWsStore((s) => s.playState);
  const clockOffsetMs = useWsStore((s) => s.clockOffsetMs);
  const weakNetwork = useWsStore((s) => s.weakNetwork);
  const queue = useWsStore((s) => s.queue);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [blocked, setBlocked] = useState(false);

  /** 已加载的歌曲；切歌才换源，同一首歌的进度变化只 seek */
  const loadedSongRef = useRef<string | null>(null);
  /** 异步取地址的序号：切歌后返回的旧请求直接丢弃 */
  const loadSeqRef = useRef(0);
  /** 元数据/可播状态就绪后才允许校准，否则 currentTime 没有意义 */
  const readyRef = useRef(false);
  const bufferingRef = useRef(false);
  const playingRef = useRef(false);
  const calibratingUntilRef = useRef(0);
  const lastSoftCheckRef = useRef(0);
  const lastHardCheckRef = useRef(0);
  const forceHardRef = useRef(false);
  const softTimerRef = useRef<number | null>(null);
  const wasWeakRef = useRef(weakNetwork);

  // 组件每次渲染都更新镜像，供长期存活的定时器/事件监听器读取最新状态
  const latestRef = useRef<Latest>({ roomId, playState, clockOffsetMs, queue, canControl });
  latestRef.current = { roomId, playState, clockOffsetMs, queue, canControl };

  /** 恢复 1.0 倍速（软校准窗口结束、暂停、切歌都要还原） */
  const restoreRate = (): void => {
    if (softTimerRef.current !== null) {
      window.clearTimeout(softTimerRef.current);
      softTimerRef.current = null;
    }
    calibratingUntilRef.current = 0;
    const audio = audioRef.current;
    if (audio) audio.playbackRate = 1;
  };

  /** 硬校准：直接跳到期望位置 */
  const hardSeek = (expectedMs: number): void => {
    const audio = audioRef.current;
    if (!audio) return;
    const target = Math.max(0, expectedMs) / 1000;
    restoreRate();
    // 位置已经对齐时不要再写 `currentTime`：会白触发一次 seek 与 canplay，
    // 若调用方挂在 canplay 上就会自激成死循环。
    if (Math.abs(audio.currentTime - target) * 1000 < SEEK_EPSILON_MS) {
      lastHardCheckRef.current = Date.now();
      return;
    }
    audio.currentTime = target;
    lastHardCheckRef.current = Date.now();
  };

  /** 按权威状态对齐并播放/暂停 */
  const applyAuthority = (forceSeek: boolean): void => {
    const audio = audioRef.current;
    const state = latestRef.current.playState;
    if (!audio || !state?.songId) return;
    // 地址还没取回来：不要 play()，否则必然被拒并误报「需要手势」
    if (!audio.src) return;
    if (!readyRef.current && !forceSeek) return;

    const expected = expectedPositionMs(
      state,
      serverNow(Date.now(), latestRef.current.clockOffsetMs),
    );
    // 暂停/继续也会推新版本，但位置通常只差几十毫秒；这时 seek 只会制造一次可听的断点。
    // 只有真正偏了（显式 seek、切歌后对齐、前端算错）才跳。
    if (forceSeek && Math.abs(audio.currentTime * 1000 - expected) > SOFT_THRESHOLD_MS) {
      hardSeek(expected);
    }

    if (state.playStatus === PLAY_STATUS.PLAYING) {
      if (audio.paused) {
        void audio.play()
          .then(() => setBlocked(false))
          .catch(() => setBlocked(true));
      }
    } else {
      audio.pause();
    }
  };

  // ------------------------------------------------------------------ 生命周期
  useEffect(() => {
    const audio = new Audio();
    audio.preload = 'auto';
    // 变速不变调；房间的 ±8% 只是追赶，不应让人声变调
    const pitch = audio as HTMLAudioElement & {
      preservesPitch?: boolean;
      webkitPreservesPitch?: boolean;
      mozPreservesPitch?: boolean;
    };
    pitch.preservesPitch = true;
    pitch.webkitPreservesPitch = true;
    pitch.mozPreservesPitch = true;
    audioRef.current = audio;

    // 进房先停掉全局播放器，避免两路声音叠加
    usePlayerStore.getState().pause();

    const onLoaded = (): void => {
      readyRef.current = true;
      bufferingRef.current = false;
      // 元数据就绪后按权威态精确对齐一次（src 设置时的 seek 可能被浏览器丢弃）。
      // 只挂 `loadedmetadata`：它每个 src 只触发一次；`canplay` 会在每次 seek 后再触发，
      // 挂在上面会与 hardSeek 互相触发，形成持续卡顿。
      applyAuthority(true);
    };
    const onWaiting = (): void => {
      bufferingRef.current = true;
    };
    const onPlaying = (): void => {
      readyRef.current = true;
      bufferingRef.current = false;
      playingRef.current = true;
    };
    const onPause = (): void => {
      playingRef.current = false;
    };
    const onEnded = (): void => {
      playingRef.current = false;
      const latest = latestRef.current;
      // 只有房主/房管推进队列；普通成员等服务端广播
      if (!latest.canControl || !latest.playState?.songId) return;
      const currentSongId = latest.playState.songId;
      const next = latest.queue.find(
        (item) => item.status === 1 && item.songId && item.songId !== currentSongId,
      );
      if (next?.songId) useWsStore.getState().switchSong(String(next.songId));
    };
    const onError = (): void => {
      // 房间音频失败不做本地跳曲：让房主看到、由控播状态机决定
      const code = audio.error?.code;
      if (code !== 1 /* ABORTED */) {
        console.warn('[RoomAudioSync] 音频错误', code);
        readyRef.current = false;
      }
    };

    audio.addEventListener('loadedmetadata', onLoaded);
    audio.addEventListener('waiting', onWaiting);
    audio.addEventListener('playing', onPlaying);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('ended', onEnded);
    audio.addEventListener('error', onError);

    return () => {
      audio.removeEventListener('loadedmetadata', onLoaded);
      audio.removeEventListener('waiting', onWaiting);
      audio.removeEventListener('playing', onPlaying);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('error', onError);
      if (softTimerRef.current !== null) window.clearTimeout(softTimerRef.current);
      audio.pause();
      audio.removeAttribute('src');
      audioRef.current = null;
      loadedSongRef.current = null;
      readyRef.current = false;
      playingRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 只创建一次元素，applyAuthority 读 ref
  }, []);

  /** 换歌：取地址并换源。同一首歌由下方的状态 effect 只做 seek。 */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    const songId = playState?.songId ?? null;
    const seq = (loadSeqRef.current += 1);
    restoreRate();
    if (!songId) {
      loadedSongRef.current = null;
      readyRef.current = false;
      audio.pause();
      audio.removeAttribute('src');
      return;
    }
    if (loadedSongRef.current === songId) return;
    loadedSongRef.current = songId;
    readyRef.current = false;
    bufferingRef.current = false;
    lastSoftCheckRef.current = 0;
    lastHardCheckRef.current = 0;

    const expected = expectedPositionMs(
      playState,
      serverNow(Date.now(), clockOffsetMs),
    );

    void fetchPlayUrl(songId, 'standard', {
      userId: useUserStore.getState().user?.id ?? null,
      silent: true,
      force: true,
    })
      .then((vo) => {
        // 切歌后返回的旧地址直接丢弃，否则会把上一首重新灌进来
        if (seq !== loadSeqRef.current) return;
        audio.src = vo.url;
        audio.playbackRate = 1;
        audio.currentTime = Math.max(0, expected) / 1000;
        if (latestRef.current.playState?.playStatus === PLAY_STATUS.PLAYING) {
          void audio.play()
            .then(() => setBlocked(false))
            .catch(() => setBlocked(true));
        }
      })
      .catch(() => {
        if (seq !== loadSeqRef.current) return;
        // 取地址失败：留空让用户重进房间或下一次状态更新再试，不静默跳曲
        readyRef.current = false;
      });
    // 只在歌曲变化时换源；clockOffset 变化不需要重新下载
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId, playState?.songId]);

  /** 权威状态变化（控播/暂停/seek/切歌）：立即对齐 */
  useEffect(() => {
    if (!playState?.songId) return;
    if (loadedSongRef.current !== playState.songId) return;
    applyAuthority(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- applyAuthority 只读 ref
  }, [playState?.version, playState?.playStatus, playState?.startPositionMs, playState?.songId]);

  /** 音量：服务端权威值 0-100；缺省保持 1.0 */
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    const volume = playState?.volume;
    if (typeof volume === 'number' && Number.isFinite(volume)) {
      audio.volume = Math.max(0, Math.min(1, volume / 100));
    }
  }, [playState?.volume]);

  /** 页面隐藏时暂停校准；恢复可见立即硬校准（BR-22-021） */
  useEffect(() => {
    const onVisibility = (): void => {
      if (!document.hidden) forceHardRef.current = true;
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  /** 弱网恢复后先硬校准一次，把重连期间累积的偏差一次抹平（BR-22-085） */
  useEffect(() => {
    if (wasWeakRef.current && !weakNetwork) forceHardRef.current = true;
    wasWeakRef.current = weakNetwork;
  }, [weakNetwork]);

  /**
   * 校准循环：每秒比对一次，软校准受 10 秒间隔限制、硬校准受 30 秒限制，
   * 不叠加（BR-22-012 / 016）。真正的分级规则都在 `planCalibration`。
   */
  useEffect(() => {
    const timer = window.setInterval(() => {
      const audio = audioRef.current;
      const state = latestRef.current.playState;
      if (!audio || !state?.songId || !readyRef.current) return;

      const now = Date.now();
      const expected = expectedPositionMs(
        state,
        serverNow(now, latestRef.current.clockOffsetMs),
      );
      const drift = audio.currentTime * 1000 - expected;
      const forceHard = forceHardRef.current;
      forceHardRef.current = false;

      const plan = planCalibration({
        driftMs: drift,
        expectedMs: expected,
        audioReady: readyRef.current,
        buffering: bufferingRef.current
          || state.playStatus === PLAY_STATUS.BUFFERING
          || audio.readyState < HAVE_FUTURE_DATA,
        hidden: typeof document !== 'undefined' && document.hidden,
        calibrating: now < calibratingUntilRef.current,
        forceHard,
      });

      if (plan.type === 'hard') {
        if (forceHard || now - lastHardCheckRef.current >= HARD_CHECK_INTERVAL_MS) {
          hardSeek(plan.seekToMs ?? expected);
          applyAuthority(false);
        }
        return;
      }

      if (plan.type === 'soft') {
        if (now - lastSoftCheckRef.current < SOFT_CHECK_INTERVAL_MS) return;
        lastSoftCheckRef.current = now;
        calibratingUntilRef.current = now + SOFT_CALIBRATION_MS;
        audio.playbackRate = plan.rate;
        if (softTimerRef.current !== null) window.clearTimeout(softTimerRef.current);
        softTimerRef.current = window.setTimeout(() => {
          softTimerRef.current = null;
          calibratingUntilRef.current = 0;
          const el = audioRef.current;
          if (el) el.playbackRate = 1;
        }, plan.durationMs);
      }
    }, 1_000);
    return () => {
      window.clearInterval(timer);
      restoreRate();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 定时器读 ref，不能随渲染重建
  }, []);

  /** 收听时长：每 60 秒上报一次（服务端按真实间隔截断） */
  useEffect(() => {
    const timer = window.setInterval(() => {
      const { roomId: currentRoom, playState: state } = latestRef.current;
      if (!currentRoom || !state?.songId || !playingRef.current) return;
      void roomApi.listen(currentRoom, 60).catch(() => undefined);
    }, 60_000);
    return () => window.clearInterval(timer);
  }, []);

  // 浏览器自动播放策略拒绝时，给一个明确的用户手势入口
  if (!blocked) return null;
  return (
    <Button
      type="primary"
      icon={<CustomerServiceOutlined />}
      style={{ position: 'fixed', right: 24, bottom: 24, zIndex: 1000 }}
      onClick={() => {
        const audio = audioRef.current;
        if (!audio) return;
        void audio.play()
          .then(() => setBlocked(false))
          .catch(() => setBlocked(true));
      }}
    >
      开启房间声音
    </Button>
  );
}
