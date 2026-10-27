import { Badge, Button, Select, Slider, Space, Tooltip, Typography } from 'antd';
import {
  CaretRightOutlined,
  CustomerServiceOutlined,
  ExpandOutlined,
  LoadingOutlined,
  PauseOutlined,
  SoundOutlined,
  StepBackwardOutlined,
  StepForwardOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { usePlayerStore } from '@/stores/playerStore';
import { useWsStore } from '@/stores/wsStore';
import { QUALITY_LABEL, isPlayable, type Quality } from '@/api/player';
import { RATE_OPTIONS } from '@/components/player/playbackRate';
import { CoverImage } from '@/components/common/CoverImage';
import { PlayingPage } from '@/components/player/PlayingPage';
import { QueueDrawer } from '@/components/player/QueueDrawer';
import { PlayerHotkeyHelp } from '@/components/player/PlayerHotkeyHelp';
import { MODE_ICON, MODE_LABEL, modeTooltip } from '@/components/player/playMode';
import { usePlayerHotkeys } from '@/hooks/usePlayerHotkeys';
import { useMediaSession } from '@/hooks/useMediaSession';
import { formatTime } from '@/utils/format';
import styles from './PlayerBar.module.less';

/**
 * 迷你播放条（PRD FR-11-005；模块 02 交付外壳，模块 07 T713 补齐）。
 *
 * 模块 07 在本组件上挂了三样「跟着播放器走」的东西，而不是挂到各布局里：
 * `<QueueDrawer>`、`<PlayingPage>`、`<PlayerHotkeyHelp>`，以及全局快捷键与 MediaSession。
 * 理由是它们都属于播放器本身，两个布局（C 端 / 后台）都渲染本组件，
 * 挂在这里天然两处都有；挂到布局里就得写两遍、且容易漏。
 *
 * 全屏页与抽屉都是 `position: fixed` 的浮层，落在播放条内部不会继承它的尺寸约束。
 */

const QUALITIES: Quality[] = ['standard', 'high', 'lossless'];

export function PlayerBar(): JSX.Element {
  const currentSong = usePlayerStore((s) => s.currentSong);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const isBuffering = usePlayerStore((s) => s.isBuffering);
  const currentTime = usePlayerStore((s) => s.currentTime);
  const duration = usePlayerStore((s) => s.duration);
  const volume = usePlayerStore((s) => s.volume);
  const muted = usePlayerStore((s) => s.muted);
  const playMode = usePlayerStore((s) => s.playMode);
  // 展示「实际生效音质」：服务端降级/弱网自动降级后，下拉框要如实反映正在播的档位
  const effectiveQuality = usePlayerStore((s) => s.effectiveQuality);
  const playbackRate = usePlayerStore((s) => s.playbackRate);
  const error = usePlayerStore((s) => s.error);
  const resumeHint = usePlayerStore((s) => s.resumeHint);
  const queueSize = usePlayerStore((s) => s.queue.length);
  const roomId = useWsStore((s) => s.roomId);

  const {
    toggle,
    next,
    prev,
    seek,
    setVolume,
    toggleMute,
    cyclePlayMode,
    setQuality,
    setPlaybackRate,
    openOverlay,
    setQueueDrawerOpen,
    setResumeHint,
  } = usePlayerStore.getState();

  usePlayerHotkeys();
  useMediaSession();

  const playable = isPlayable(currentSong);
  const total = duration || currentSong?.duration || 0;

  return (
    <div className={styles.bar} role="contentinfo" aria-label="播放器">
      {/* 左：当前歌曲（点封面展开全屏播放页） */}
      <div className={styles.song}>
        <Tooltip title="展开全屏播放页">
          <button type="button" className={styles.coverButton} onClick={openOverlay} aria-label="展开全屏播放页">
            <CoverImage src={currentSong?.cover} alt={currentSong?.name ?? '未播放'} size={48} />
            <span className={styles.coverHover}>
              <ExpandOutlined />
            </span>
          </button>
        </Tooltip>
        <div className={styles.songMeta}>
          {currentSong ? (
            <>
              <Link to={`/song/${currentSong.id}`} className={styles.songName} title={currentSong.name}>
                {currentSong.name}
              </Link>
              <Typography.Text type="secondary" className={styles.songArtist} ellipsis>
                {currentSong.artistName}
              </Typography.Text>
            </>
          ) : (
            <Typography.Text type="secondary">选择一首歌开始播放</Typography.Text>
          )}
          {error ? (
            <Typography.Text type="warning" className={styles.songArtist} ellipsis title={error}>
              {error}
            </Typography.Text>
          ) : resumeHint ? (
            // 断点续播提示（BR-16-039）：只在用户还没开始播、且确实是恢复态时显示
            <Typography.Text
              type="secondary"
              className={styles.songArtist}
              ellipsis
              title={`${resumeHint}，点播放继续`}
              onClick={() => setResumeHint(null)}
            >
              {resumeHint}，点播放继续
            </Typography.Text>
          ) : null}
        </div>
      </div>

      {/* 中：控制区 + 进度 */}
      <div className={styles.center}>
        <Space size={4} className={styles.controls}>
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
          {/* `next(auto?)` 的第一个参数是「是否自动切歌」，直接传给 onClick 会把事件对象当 auto 传进去 */}
          <Button type="text" aria-label="下一首" icon={<StepForwardOutlined />} onClick={() => next()} />
          <Tooltip title="歌词（在全屏播放页展开）">
            <Button
              type="text"
              aria-label="歌词"
              icon={<CustomerServiceOutlined />}
              disabled={!currentSong}
              onClick={openOverlay}
            />
          </Tooltip>
        </Space>

        <div className={styles.progress}>
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
      </div>

      {/* 右：队列 / 音质 / 音量 */}
      <div className={styles.right}>
        <Badge count={queueSize} size="small" offset={[-2, 2]} overflowCount={999}>
          <Tooltip title="播放队列">
            <Button
              type="text"
              aria-label="播放队列"
              icon={<UnorderedListOutlined />}
              onClick={() => setQueueDrawerOpen(true)}
            />
          </Tooltip>
        </Badge>

        <Select<Quality>
          size="small"
          value={effectiveQuality}
          onChange={setQuality}
          style={{ width: 84 }}
          options={QUALITIES.map((q) => ({ value: q, label: QUALITY_LABEL[q] }))}
          aria-label="音质"
        />

        <Tooltip title={roomId ? '音乐房内固定 1.0 倍速，保证各端进度同步' : '播放速度'}>
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

        <Space size={4} className={styles.volume}>
          <Button
            type="text"
            aria-label={muted ? '取消静音' : '静音'}
            icon={<SoundOutlined />}
            onClick={toggleMute}
          />
          <Slider
            className={styles.volumeSlider}
            min={0}
            max={100}
            value={muted ? 0 : Math.round(volume * 100)}
            tooltip={{ formatter: (v) => `${v}%` }}
            onChange={(value) => setVolume(Number(value) / 100)}
          />
        </Space>
      </div>

      {/*
        浮层：挂在播放条里而不是各布局里——它们属于播放器，两个布局都渲染本组件，
        挂这里天然两处都有。都是 fixed 定位，不受播放条尺寸影响；
        PlayingPage 自己在 `overlayOpen` 为假时返回 null，所以这里不需要再过一遍条件。
      */}
      <PlayingPage />
      <QueueDrawer />
      <PlayerHotkeyHelp />
    </div>
  );
}
