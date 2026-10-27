import { useMemo, useRef } from 'react';
import { Button, Drawer, Empty, Popconfirm, Space, Tooltip, Typography } from 'antd';
import { CaretRightOutlined, ClearOutlined, DeleteOutlined, PauseOutlined, SoundOutlined, VerticalAlignTopOutlined } from '@ant-design/icons';
import { QUEUE_LIMIT, usePlayerStore } from '@/stores/playerStore';
import { CoverImage } from '@/components/common/CoverImage';
import { formatDuration, formatTime } from '@/utils/format';
import styles from './QueueDrawer.module.less';

/**
 * 播放队列抽屉（模块 07 T709 / `FR-16-006`）。
 *
 * 队列**整体可拖拽排序**属模块 12 的「播放升级」，本期只做「选中 / 移除 / 清空」。
 * 当前曲目那一条会自动滚进视野——队列几百首时，打开抽屉看不到自己放的那首是很挫败的。
 */

export function QueueDrawer(): JSX.Element {
  const open = usePlayerStore((s) => s.queueDrawerOpen);
  const setOpen = usePlayerStore((s) => s.setQueueDrawerOpen);
  const queue = usePlayerStore((s) => s.queue);
  const currentIndex = usePlayerStore((s) => s.currentIndex);
  const isPlaying = usePlayerStore((s) => s.isPlaying);

  const { jumpTo, removeFromQueue, clearQueue, toggle, nextPlay } = usePlayerStore.getState();

  const currentRef = useRef<HTMLLIElement | null>(null);

  const totalSeconds = useMemo(
    () => queue.reduce((sum, song) => sum + (song.duration ?? 0), 0),
    [queue],
  );

  return (
    <Drawer
      title={
        <Space size={8}>
          <span>播放队列</span>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            {queue.length} 首
            {/* 总时长用 formatTime：整队列几小时很常见，formatDuration 的 mm:ss 会把 125 分钟显示成 "125:30" */}
            {totalSeconds > 0 ? ` · ${formatTime(totalSeconds)}` : ''}
            {queue.length >= QUEUE_LIMIT ? `（上限 ${QUEUE_LIMIT}）` : ''}
          </Typography.Text>
        </Space>
      }
      placement="right"
      width={420}
      open={open}
      onClose={() => setOpen(false)}
      // 打开时把当前曲目滚进视野。放在 afterOpenChange 而不是 effect：
      // 抽屉关闭时内容还在 DOM 里，那时滚动没有意义
      afterOpenChange={(visible) => {
        if (visible) currentRef.current?.scrollIntoView({ block: 'center' });
      }}
      extra={
        <Popconfirm
          title="清空播放队列？"
          description="队列里的歌曲会被全部移除，当前播放也会停止。"
          okText="清空"
          cancelText="取消"
          onConfirm={() => {
            clearQueue();
            setOpen(false);
          }}
        >
          <Tooltip title="清空队列">
            <Button type="text" size="small" icon={<ClearOutlined />} disabled={queue.length === 0}>
              清空
            </Button>
          </Tooltip>
        </Popconfirm>
      }
      styles={{ body: { padding: 0 } }}
    >
      {queue.length === 0 ? (
        <Empty className={styles.empty} description="播放队列是空的" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <>
          {queue.length >= QUEUE_LIMIT ? (
            <div className={styles.limit}>队列已达上限 {QUEUE_LIMIT} 首，新增歌曲前请先移除部分</div>
          ) : null}
          <ul className={styles.list}>
            {queue.map((song, index) => {
              const isCurrent = index === currentIndex;
              return (
                <li
                  key={`${song.id}-${index}`}
                  ref={isCurrent ? currentRef : undefined}
                  className={[styles.item, isCurrent ? styles.itemCurrent : ''].filter(Boolean).join(' ')}
                  onDoubleClick={() => {
                    if (isCurrent) toggle();
                    else jumpTo(index);
                  }}
                >
                  <span className={styles.index}>
                    {isCurrent && isPlaying ? <SoundOutlined className={styles.playing} /> : index + 1}
                  </span>
                  <CoverImage src={song.cover} alt={song.name} size={36} />
                  <div className={styles.meta}>
                    <div className={styles.name} title={song.name}>
                      {song.name}
                    </div>
                    <div className={styles.artist} title={song.artistName}>
                      {song.artistName}
                    </div>
                  </div>
                  <span className={styles.duration}>{formatDuration(song.duration)}</span>
                  <Space size={0} className={styles.actions}>
                    {/*
                      当前曲目：切换播放/暂停；其它曲目：切到这首。
                      用单击而不是双击——抽屉里单击是最自然的期望，双击才重播（见 li 的 onDoubleClick）
                    */}
                    <Tooltip title={isCurrent ? (isPlaying ? '暂停' : '播放') : '播放这首'}>
                      <Button
                        type="text"
                        size="small"
                        icon={isCurrent && isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
                        onClick={() => {
                          if (isCurrent) toggle();
                          else jumpTo(index);
                        }}
                        aria-label={isCurrent && isPlaying ? '暂停' : `播放 ${song.name}`}
                      />
                    </Tooltip>
                    <Tooltip title="下一首播放">
                      <Button
                        type="text"
                        size="small"
                        icon={<VerticalAlignTopOutlined />}
                        // 把这首歌挪到当前曲目正后方；当前曲目自己点它等价于重播，store 里已处理
                        onClick={() => nextPlay(song)}
                        aria-label={`将 ${song.name} 设为下一首播放`}
                      />
                    </Tooltip>
                    <Tooltip title="从队列移除">
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => removeFromQueue(index)}
                        aria-label={`从队列移除 ${song.name}`}
                      />
                    </Tooltip>
                  </Space>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </Drawer>
  );
}
