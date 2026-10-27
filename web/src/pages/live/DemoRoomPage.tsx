import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Empty,
  Input,
  InputNumber,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { songApi } from '@/api/song';
import { useUserStore } from '@/stores/userStore';
import { useWsStore } from '@/stores/wsStore';
import { computeServerPosition } from '@/services/wsCore';
import { formatTime } from '@/utils/format';
import styles from './DemoRoomPage.module.less';

/**
 * 实时房间联调页（模块 04）。
 *
 * <p>模块 05（直播间）还没交付，这里只提供一个**能看见 WS 在干活**的最小页面：
 * 进房/退房、聊天、弹幕、房主控播、断线重连状态。样式刻意从简，
 * 房间的真实视觉与业务（礼物、连麦、榜单）留给模块 05。
 */

const STATUS_TEXT: Record<string, string> = {
  idle: '未连接',
  connecting: '连接中',
  open: '已连接',
  reconnecting: '重连中',
  closed: '已断开',
};

export function DemoRoomPage(): JSX.Element {
  const { roomId: routeRoomId } = useParams<{ roomId: string }>();
  const roomId = routeRoomId ?? '1';

  const status = useWsStore((s) => s.status);
  const gaveUp = useWsStore((s) => s.gaveUp);
  const retry = useWsStore((s) => s.retry);
  const joinRoom = useWsStore((s) => s.joinRoom);
  const leaveRoom = useWsStore((s) => s.leaveRoom);
  const isOwner = useWsStore((s) => s.isOwner);
  const onlineCount = useWsStore((s) => s.onlineCount);
  const messages = useWsStore((s) => s.messages);
  const playState = useWsStore((s) => s.playState);
  const sendChat = useWsStore((s) => s.sendChat);
  const sendDanmaku = useWsStore((s) => s.sendDanmaku);
  const switchSong = useWsStore((s) => s.switchSong);
  const pause = useWsStore((s) => s.pause);
  const play = useWsStore((s) => s.play);
  const seek = useWsStore((s) => s.seek);
  const requestSync = useWsStore((s) => s.requestSync);
  const selfId = useUserStore((s) => s.user?.id);

  useEffect(() => {
    joinRoom(roomId);
    return () => leaveRoom();
  }, [roomId, joinRoom, leaveRoom]);

  // 播放进度显示：以服务端快照为锚点每秒重算，不在本地累加（避免计时器漂移）
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!playState?.isPlaying) return undefined;
    const timer = setInterval(() => setTick((v) => v + 1), 1000);
    return () => clearInterval(timer);
  }, [playState?.isPlaying, playState?.version]);

  const displayPosition = useMemo(
    () => Math.floor(computeServerPosition(playState, Date.now())),
    // tick 每秒变化，触发重算；playState 变化（切歌/暂停/校准）立即重算
    [playState, tick],
  );

  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length]);

  const [mode, setMode] = useState<'chat' | 'danmaku'>('chat');
  const [text, setText] = useState('');
  const submit = (): void => {
    const content = text.trim();
    if (!content) return;
    if (mode === 'chat') sendChat(content);
    else sendDanmaku(content);
    setText('');
  };

  const songsQuery = useQuery({
    queryKey: ['ws-demo-songs'],
    queryFn: () => songApi.list({ pageSize: 50 }),
  });
  const [selectedSong, setSelectedSong] = useState<string | undefined>();
  const [seekTo, setSeekTo] = useState<number>(0);

  const songOptions = (songsQuery.data?.list ?? []).map((song) => ({
    value: song.id,
    label: `${song.name}${song.artistName ? ` - ${song.artistName}` : ''}`,
  }));

  return (
    <div className={styles.wrap}>
      <div className={styles.header}>
        <Space size={12} wrap>
          <Typography.Title level={4} className={styles.title}>
            实时房间 #{roomId}
          </Typography.Title>
          <Tag color={status === 'open' ? 'green' : gaveUp ? 'red' : 'orange'}>
            {STATUS_TEXT[status] ?? status}
          </Tag>
          <Tag>在线 {onlineCount}</Tag>
          {isOwner && <Tag color="gold">房主</Tag>}
          {status !== 'open' && (
            <Button size="small" onClick={() => retry()}>
              重试连接
            </Button>
          )}
        </Space>
      </div>

      <div className={styles.body}>
        <section className={styles.chat}>
          <div className={styles.messages} ref={listRef}>
            {messages.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有消息，说点什么吧" />
            ) : (
              messages.map((message) => {
                if (message.messageType === 3 || message.messageType === 5) {
                  return (
                    <div key={message.messageId} className={styles.system}>
                      {message.content}
                    </div>
                  );
                }
                const mine = message.userId === (selfId === undefined ? '' : String(selfId));
                return (
                  <div
                    key={message.messageId}
                    className={`${styles.message} ${message.messageType === 2 ? styles.danmaku : ''} ${
                      mine ? styles.mine : ''
                    } ${message.pending ? styles.pending : ''}`}
                  >
                    <span className={styles.nickname}>{message.nickname || `用户${message.userId.slice(-4)}`}</span>
                    <span className={styles.content}>{message.content}</span>
                    {message.pending && <span className={styles.sending}>发送中…</span>}
                  </div>
                );
              })
            )}
          </div>

          <div className={styles.composer}>
            <Segmented
              options={[
                { label: '聊天', value: 'chat' },
                { label: '弹幕', value: 'danmaku' },
              ]}
              value={mode}
              onChange={(value) => setMode(value as 'chat' | 'danmaku')}
            />
            <Input
              value={text}
              maxLength={mode === 'chat' ? 200 : 50}
              placeholder={mode === 'chat' ? '发送聊天消息（≤200 字，20 条/分钟）' : '发送弹幕（≤50 字，10 条/分钟）'}
              onChange={(e) => setText(e.target.value)}
              onPressEnter={submit}
              disabled={status !== 'open'}
            />
            <Button type="primary" icon={<SendOutlined />} onClick={submit} disabled={status !== 'open'}>
              发送
            </Button>
          </div>
        </section>

        <aside className={styles.side}>
          <Typography.Title level={5}>播放同步</Typography.Title>
          {playState?.songId ? (
            <div className={styles.nowPlaying}>
              <Typography.Text strong ellipsis>
                {playState.songName}
              </Typography.Text>
              <Typography.Text type="secondary" ellipsis>
                {playState.artistName}
              </Typography.Text>
              <div className={styles.time}>
                {formatTime(displayPosition)}
                {playState.duration > 0 ? ` / ${formatTime(playState.duration)}` : ''}
                {' · '}
                {playState.isPlaying ? '播放中' : '已暂停'}
              </div>
              <Typography.Text type="secondary" className={styles.version}>
                version {playState.version}
              </Typography.Text>
            </div>
          ) : (
            <Alert type="info" showIcon message="房间暂无歌曲" />
          )}

          {isOwner ? (
            <Space direction="vertical" className={styles.controls}>
              <Select
                className={styles.select}
                showSearch
                optionFilterProp="label"
                placeholder="选择要播放的歌曲"
                loading={songsQuery.isLoading}
                options={songOptions}
                value={selectedSong}
                onChange={setSelectedSong}
              />
              <Space wrap>
                <Button
                  type="primary"
                  disabled={!selectedSong}
                  onClick={() => selectedSong && switchSong(selectedSong, 0)}
                >
                  切歌
                </Button>
                {playState?.isPlaying ? (
                  <Button onClick={() => pause(displayPosition)}>暂停</Button>
                ) : (
                  <Button
                    disabled={!playState?.songId}
                    onClick={() => play(undefined, displayPosition)}
                  >
                    继续
                  </Button>
                )}
                <Button onClick={() => requestSync()}>同步</Button>
              </Space>
              <Space>
                <InputNumber
                  min={0}
                  value={seekTo}
                  onChange={(value) => setSeekTo(Number(value ?? 0))}
                  addonAfter="秒"
                />
                <Button disabled={!playState?.songId} onClick={() => seek(seekTo, playState?.isPlaying)}>
                  跳转
                </Button>
              </Space>
            </Space>
          ) : (
            <Alert
              type="warning"
              showIcon
              message="只有房主可以控制播放"
              description="首个进入房间的用户成为房主（模块 05 交付前的演示策略）。"
            />
          )}
        </aside>
      </div>
    </div>
  );
}
