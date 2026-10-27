import { useEffect, useState } from 'react';
import { Button, Select, Slider, Space, Tooltip, Typography } from 'antd';
import {
  CaretRightOutlined,
  DownOutlined,
  HeartOutlined,
  LoadingOutlined,
  MessageOutlined,
  PauseOutlined,
  StepBackwardOutlined,
  StepForwardOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { usePlayerStore } from '@/stores/playerStore';
import { useWsStore } from '@/stores/wsStore';
import { QUALITY_LABEL, isPlayable, type Quality } from '@/api/player';
import { CoverImage } from '@/components/common/CoverImage';
import { LyricPanel } from '@/components/player/LyricPanel';
import { MODE_ICON, MODE_LABEL, modeTooltip } from '@/components/player/playMode';
import { RATE_OPTIONS } from '@/components/player/playbackRate';
import { formatTime } from '@/utils/format';
import styles from './PlayingPage.module.less';

/**
 * 全屏播放页（模块 07 T708 / `FR-16-004`、`FR-16-005`）。
 *
 * 这是**视图**，不是第二个播放器：`<audio>` 仍然只由根节点的 `AudioEngine` 驱动
 * （`BR-16-045`），所以打开/关闭本页不会打断播放，也不会产生第二条音频流。
 *
 * 关闭有三条路：右上角「收起」按钮、`Esc`（在 `usePlayerHotkeys` 里）、浏览器后退。
 * 三者最终都走 {@link closePlayingOverlay}，保证 `pushState` 压进去的那条历史记录
 * 一定被 `history.back()` 消费掉——否则用户按后退会"没反应"一次。
 */

const QUALITIES: Quality[] = ['standard', 'high', 'lossless'];

/**
 * 关闭全屏播放页。
 *
 * 优先用 `history.back()` 而不是直接改 store：这样浏览器历史与我们压进去的那条记录
 * 是对称的。只有在历史里找不到我们压的那条（比如用户中途导航过）时才直接关。
 */
export function closePlayingOverlay(): void {
  const state = usePlayerStore.getState();
  if (!state.overlayOpen) return;
  if ((window.history.state as { mlPlaying?: boolean } | null)?.mlPlaying === true) {
    window.history.back();
  } else {
    state.closeOverlay();
  }
}

export function PlayingPage(): JSX.Element | null {
  const open = usePlayerStore((s) => s.overlayOpen);
  const closeOverlay = usePlayerStore((s) => s.closeOverlay);
  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const isBuffering = usePlayerStore((s) => s.isBuffering);
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);
  const effectiveQuality = usePlayerStore((s) => s.effectiveQuality);
  const playbackRate = usePlayerStore((s) => s.playbackRate);
  const remoteProgress = usePlayerStore((s) => s.remoteProgress);
  const playMode = usePlayerStore((s) => s.playMode);
  const queueSize = usePlayerStore((s) => s.queue.length);
  const roomId = useWsStore((s) => s.roomId);

  const {
    toggle,
    next,
    prev,
    seek,
    setQuality,
    setPlaybackRate,
    applyRemoteResume,
    setRemoteProgress,
    cyclePlayMode,
    setQueueDrawerOpen,
  } = usePlayerStore.getState();

  /** 移动端视口高度变化的兜底：`100vh` 在移动浏览器里包含地址栏，会溢出 */
  const [viewportHeight, setViewportHeight] = useState<number | null>(null);
  useEffect(() => {
    if (!open) return undefined;
    const sync = (): void => setViewportHeight(window.innerHeight);
    sync();
    window.addEventListener('resize', sync);
    return () => window.removeEventListener('resize', sync);
  }, [open]);

  /** 打开时压一条历史记录；`popstate` 且不是我们这条 → 说明用户点了后退，关闭 */
  useEffect(() => {
    if (!open) return undefined;
    window.history.pushState({ ...window.history.state, mlPlaying: true }, '');
    const onPop = (event: PopStateEvent): void => {
      if ((event.state as { mlPlaying?: boolean } | null)?.mlPlaying !== true) closeOverlay();
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [open, closeOverlay]);

  /** 锁定背景滚动，否则滑动歌词会带着整页滚 */
  useEffect(() => {
    if (!open) return undefined;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  if (!open) return null;

  const playable = isPlayable(currentSong);
  const total = duration || currentSong?.duration || 0;

  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-label="正在播放">
      <div className={styles.card} style={viewportHeight ? { maxHeight: viewportHeight } : undefined}>
        <div className={styles.header}>
          <Typography.Text type="secondary" className={styles.headerHint}>
            正在播放
          </Typography.Text>
          <Button
            type="text"
            icon={<DownOutlined />}
            onClick={closePlayingOverlay}
            aria-label="收起播放页"
            className={styles.collapse}
          >
            收起
          </Button>
        </div>

        <div className={styles.body}>
          {/* 左：封面与歌曲信息 */}
          <div className={styles.left}>
            <CoverImage
              src={currentSong?.cover}
              alt={currentSong?.name ?? '未播放'}
              size={320}
              className={styles.cover}
            />
            <div className={styles.meta}>
              {currentSong ? (
                <>
                  <Link to={`/song/${currentSong.id}`} className={styles.name} onClick={closePlayingOverlay}>
                    {currentSong.name}
                  </Link>
                  <Link to={`/artist/${currentSong.artistId ?? ''}`} className={styles.artist}>
                    {currentSong.artistName}
                  </Link>
                </>
              ) : (
                <Typography.Text type="secondary">选择一首歌开始播放</Typography.Text>
              )}
            </div>
          </div>

          {/* 右：歌词（全屏态，可点击跳转、跟随滚动） */}
          <div className={styles.right}>
            <LyricPanel song={currentSong} variant="full" />
          </div>
        </div>

        <div className={styles.footer}>
          {/* 跨设备续播（BR-21-066）：只提示不自动跳，用户点了才 seek */}
          {remoteProgress ? (
            <div className={styles.remoteResume}>
              <Typography.Text type="secondary">
                另一台设备上次播放到 {formatTime(remoteProgress.position)}
              </Typography.Text>
              <Space size={8}>
                <Button size="small" type="primary" onClick={applyRemoteResume}>
                  从 {formatTime(remoteProgress.position)} 继续
                </Button>
                <Button size="small" type="text" onClick={() => setRemoteProgress(null)}>
                  忽略
                </Button>
              </Space>
            </div>
          ) : null}

          <div className={styles.progressRow}>
            <span className={styles.time}>{formatTime(currentTime)}</span>
            <Slider
              className={styles.slider}
              min={0}
              max={total || 1}
              step={1}
              value={Math.min(currentTime, total || 1)}
              disabled={!playable || !total}
              tooltip={{ formatter: (v) => formatTime(Number(v ?? 0)) }}
              onChange={(value) => seek(Number(value))}
            />
            <span className={styles.time}>{formatTime(total)}</span>
          </div>

          <div className={styles.controlRow}>
            <Space size={4} className={styles.leftControls}>
              <Tooltip title="收藏功能将在模块 10 交付">
                <Button type="text" icon={<HeartOutlined />} disabled aria-label="收藏" />
              </Tooltip>
              <Tooltip title="评论功能将在模块 11 交付">
                <Button type="text" icon={<MessageOutlined />} disabled aria-label="评论" />
              </Tooltip>
            </Space>

            <Space size={12} className={styles.centerControls}>
              <Tooltip title={modeTooltip(playMode)}>
                <Button
                  type="text"
                  aria-label={MODE_LABEL[playMode]}
                  icon={MODE_ICON[playMode]}
                  onClick={cyclePlayMode}
                />
              </Tooltip>
              <Button type="text" aria-label="上一首" icon={<StepBackwardOutlined />} onClick={prev} />
              <Button
                type="primary"
                shape="circle"
                size="large"
                aria-label={isPlaying ? '暂停' : '播放'}
                disabled={!playable}
                icon={isBuffering ? <LoadingOutlined /> : isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
                onClick={toggle}
              />
              {/* `next(auto?)` 的参数是「是否自动切歌」，直接挂 onClick 会把事件对象传进去 */}
              <Button type="text" aria-label="下一首" icon={<StepForwardOutlined />} onClick={() => next()} />
            </Space>

            <Space size={8} className={styles.rightControls}>
              <Select<Quality>
                size="small"
                value={effectiveQuality}
                onChange={setQuality}
                style={{ width: 84 }}
                options={QUALITIES.map((q) => ({ value: q, label: QUALITY_LABEL[q] }))}
                aria-label="音质"
              />
              <Tooltip title={roomId ? '音乐房内固定 1.0 倍速' : '播放速度'}>
                <Select<number>
                  size="small"
                  value={roomId ? 1 : playbackRate}
                  onChange={setPlaybackRate}
                  style={{ width: 72 }}
                  options={RATE_OPTIONS}
                  disabled={Boolean(roomId)}
                  aria-label="播放速度"
                />
              </Tooltip>
              <Tooltip title="播放队列">
                <Button
                  type="text"
                  aria-label="播放队列"
                  icon={<UnorderedListOutlined />}
                  onClick={() => setQueueDrawerOpen(true)}
                >
                  {queueSize}
                </Button>
              </Tooltip>
            </Space>
          </div>
        </div>
      </div>
    </div>
  );
}
