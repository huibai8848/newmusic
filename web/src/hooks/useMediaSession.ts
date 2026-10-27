import { useEffect } from 'react';
import { usePlayerStore } from '@/stores/playerStore';
import { mediaUrl } from '@/utils/format';
import type { Id } from '@/types/api';

/**
 * MediaSession 集成（模块 07 T715 / `FR-16-013`）。
 *
 * 作用：让系统级的媒体控制（锁屏、耳机线控、车机、Windows 的 SMTC 面板）能显示
 * 当前歌曲并控制播放。**没有它，笔记本键盘上的媒体键和蓝牙耳机的按键就完全无效**——
 * 这对"听歌"这个场景是很明显的缺失。
 *
 * 两个必须注意的点：
 *
 * 1. `navigator.mediaSession` 在 Safari 早期版本与部分内嵌浏览器里**不存在**，
 *    所有访问都要先判空；这里不做 polyfill，没有就安静降级。
 * 2. `setPositionState` 的 `position` 必须在 `[0, duration]` 内且 `duration > 0`，
 *    否则会抛 `TypeError`。元数据还没到时 `duration` 是 0，必须跳过。
 */

/** MediaSession 支持性判断；抽出来是因为有两个 effect 都要用 */
function getMediaSession(): MediaSession | null {
  if (typeof navigator === 'undefined') return null;
  return navigator.mediaSession ?? null;
}

export function useMediaSession(): void {
  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);

  /** 元数据：切歌时更新。封面用绝对 URL，系统面板解析不了相对路径 */
  useEffect(() => {
    const session = getMediaSession();
    if (!session || !currentSong) return;
    if (typeof MediaMetadata === 'undefined') return;

    const cover = mediaUrl(currentSong.cover);
    session.metadata = new MediaMetadata({
      title: currentSong.name,
      artist: currentSong.artistName,
      album: currentSong.albumName ?? '',
      artwork: cover ? [{ src: cover, sizes: '512x512' }] : [],
    });
  }, [currentSong]);

  /** 动作绑定：只绑一次，回调里用 getState() 现取，避免闭包停留在旧状态 */
  useEffect(() => {
    const session = getMediaSession();
    if (!session) return undefined;

    const handlers: Array<[MediaSessionAction, MediaSessionActionHandler]> = [
      ['play', () => usePlayerStore.getState().play()],
      ['pause', () => usePlayerStore.getState().pause()],
      ['previoustrack', () => usePlayerStore.getState().prev()],
      ['nexttrack', () => usePlayerStore.getState().next()],
      [
        'seekto',
        (details) => {
          if (typeof details.seekTime === 'number') usePlayerStore.getState().seek(details.seekTime);
        },
      ],
      [
        'seekbackward',
        (details) => {
          const state = usePlayerStore.getState();
          state.seek(Math.max(0, state.currentTime - (details.seekOffset ?? 10)));
        },
      ],
      [
        'seekforward',
        (details) => {
          const state = usePlayerStore.getState();
          const max = state.duration > 0 ? state.duration : Number.POSITIVE_INFINITY;
          state.seek(Math.min(state.currentTime + (details.seekOffset ?? 10), max));
        },
      ],
    ];

    for (const [action, handler] of handlers) {
      try {
        session.setActionHandler(action, handler);
      } catch {
        // 浏览器不认识某个 action（各浏览器支持的集合不同）会抛，忽略这一条即可
      }
    }

    return () => {
      for (const [action] of handlers) {
        try {
          session.setActionHandler(action, null);
        } catch {
          /* ignore */
        }
      }
    };
  }, []);

  /**
   * 播放状态：驱动系统面板的播放/暂停图标。
   *
   * 用 `navigator.mediaSession.playbackState` 而不是只依赖 `isPlaying`：
   * 某些系统（尤其 iOS）会以这个字段为准来决定媒体键的行为。
   */
  useEffect(() => {
    const session = getMediaSession();
    if (!session) return;
    try {
      session.playbackState = isPlaying ? 'playing' : 'paused';
    } catch {
      /* 个别实现里该字段只读 */
    }
  }, [isPlaying]);

  /**
   * 进度：让系统面板能显示进度条并支持拖动。
   *
   * **节流到 5 秒**：`timeupdate` 每秒 4 次，每次都建 `MediaPositionState` 是纯浪费，
   * 而且部分浏览器会因此刷屏调试日志。进度条本身的精度要求远低于 1 秒。
   */
  useEffect(() => {
    const session = getMediaSession();
    if (!session || !currentSong) return;
    // duration 为 0 时调用会抛 TypeError（position 必须落在 [0, duration] 内）
    if (!Number.isFinite(duration) || duration <= 0) return;

    const position = Math.min(Math.max(0, currentTime), duration);
    // 距上次上报不足 5 秒就跳过；用取整后的秒数比较，避免浮点噪声
    if (lastPositionReport.songId === currentSong.id && Math.abs(lastPositionReport.position - position) < 5) {
      return;
    }
    lastPositionReport = { songId: currentSong.id, position };

    try {
      session.setPositionState({ duration, playbackRate: 1, position });
    } catch {
      /* 非法区间（切换瞬间的竞态）忽略即可，下一次 timeupdate 会修正 */
    }
  }, [currentSong, currentTime, duration]);
}

/**
 * 上一次上报给系统面板的进度。
 *
 * 放模块级而不是 ref：这个节流窗口与"哪个组件实例"无关，
 * 而 `useMediaSession` 在根节点只会挂一次，模块级变量比 ref 更贴切，也更好读。
 */
let lastPositionReport: { songId: Id | null; position: number } = { songId: null, position: -1 };
