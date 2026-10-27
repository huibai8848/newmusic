import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Avatar,
  Badge,
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  List,
  Modal,
  Segmented,
  Select,
  Slider,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CustomerServiceOutlined,
  LockOutlined,
  LogoutOutlined,
  MoreOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SendOutlined,
  LikeOutlined,
  TrophyOutlined,
  UnlockOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { roomApi, roomKeys } from '@/api/room';
import { songApi } from '@/api/song';
import { RoomAudioSync } from '@/components/room/RoomAudioSync';
import {
  PLAY_STATUS,
  expectedPositionMs,
  isVoteActive,
  requiredAgreeCount,
  serverNow,
  voteRemainingSeconds,
  voteResultText,
} from '@/services/roomSync';
import { useUserStore } from '@/stores/userStore';
import { useWsStore, type RankType } from '@/stores/wsStore';
import { track } from '@/services/track';
import type { MicVO, RoomMemberVO, Song } from '@/types/api';
import type { DanmakuView, RoomMessageView } from '@/stores/wsStore';
import { formatTime } from '@/utils/format';
import styles from './RoomPage.module.less';

/**
 * 音乐房详情页（模块 05）。
 *
 * 数据来源分工：
 * - 首屏用 HTTP `GET /room/rooms/{roomNo}` 拿到完整详情（含成员/麦位/队列）；
 * - 之后全部由 WS 增量更新（`wsStore`），HTTP 不参与高频状态；
 * - 所有写操作仍走 HTTP，成功后服务端再用 WS 广播结果，页面不自行猜测状态。
 */

const ROLE_LABEL: Record<number, string> = { 0: '成员', 1: '房管', 2: '房主', 9: '黑名单' };

const QUEUE_STATUS_LABEL: Record<number, string> = {
  0: '待审',
  1: '已通过',
  2: '已拒绝',
  3: '已播放',
  4: '已移除',
};

const RANK_TABS: { label: string; value: RankType }[] = [
  { label: '活跃', value: 'active' },
  { label: '礼物', value: 'gift' },
  { label: '点歌', value: 'request' },
  { label: '收听', value: 'listen' },
];

/** 快捷表情（BR-22-066）；必须与后端 PRESET_EMOJIS 完全一致，否则会被判 invalid_emoji */
const EMOJI_PRESET = ['😂', '👍', '❤️', '🎉', '🔥', '👏', '😍', '😭', '🤔', '🐶'];

export function RoomPage(): JSX.Element {
  const { roomNo = '' } = useParams<{ roomNo: string }>();
  const navigate = useNavigate();
  const selfId = useUserStore((s) => s.user?.id ? String(s.user.id) : '');

  const status = useWsStore((s) => s.status);
  const roomDetail = useWsStore((s) => s.roomDetail);
  const members = useWsStore((s) => s.members);
  const mics = useWsStore((s) => s.mics);
  const queue = useWsStore((s) => s.queue);
  const micQueue = useWsStore((s) => s.micQueue);
  const myPermissions = useWsStore((s) => s.myPermissions);
  const myMuteRemainingSeconds = useWsStore((s) => s.myMuteRemainingSeconds);
  const onlineCount = useWsStore((s) => s.onlineCount);
  const isOwner = useWsStore((s) => s.isOwner);
  const playState = useWsStore((s) => s.playState);
  const messages = useWsStore((s) => s.messages);
  const roomExitNotice = useWsStore((s) => s.roomExitNotice);
  const joinRoom = useWsStore((s) => s.joinRoom);
  const leaveRoom = useWsStore((s) => s.leaveRoom);
  const applyRoomDetail = useWsStore((s) => s.applyRoomDetail);
  const clearRoomExitNotice = useWsStore((s) => s.clearRoomExitNotice);
  const sendChat = useWsStore((s) => s.sendChat);
  const sendDanmaku = useWsStore((s) => s.sendDanmaku);
  const play = useWsStore((s) => s.play);
  const pause = useWsStore((s) => s.pause);
  const seek = useWsStore((s) => s.seek);
  const switchSong = useWsStore((s) => s.switchSong);

  // 模块 13：权威态 / 时钟 / 投票 / 榜单 / 互动
  const activeRoomId = useWsStore((s) => s.roomId);
  const clockOffsetMs = useWsStore((s) => s.clockOffsetMs);
  const syncDegraded = useWsStore((s) => s.syncDegraded);
  const weakNetwork = useWsStore((s) => s.weakNetwork);
  const vote = useWsStore((s) => s.vote);
  const like = useWsStore((s) => s.like);
  const ranks = useWsStore((s) => s.ranks);
  const danmaku = useWsStore((s) => s.danmaku);
  const loadPlayState = useWsStore((s) => s.loadPlayState);
  const loadVote = useWsStore((s) => s.loadVote);
  const startVote = useWsStore((s) => s.startVote);
  const agreeVote = useWsStore((s) => s.agreeVote);
  const likeCurrent = useWsStore((s) => s.likeCurrent);
  const loadRank = useWsStore((s) => s.loadRank);
  const setRankVisible = useWsStore((s) => s.setRankVisible);
  const sendEmoji = useWsStore((s) => s.sendEmoji);
  /** 模块 17：房间停留时长；同一房间的重复 join（refetch/密码重试）不重复计进入 */
  const trackRoomRef = useRef<{ roomId: string | null; enteredAt: number }>({ roomId: null, enteredAt: 0 });
  const [rankType, setRankType] = useState<RankType>('active');
  const [rankVisible, setRankVisibleLocal] = useState(true);

  const [password, setPassword] = useState<string | undefined>();
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordInput, setPasswordInput] = useState('');
  const [addSongOpen, setAddSongOpen] = useState(false);
  const [noticeOpen, setNoticeOpen] = useState(false);
  const [noticeInput, setNoticeInput] = useState('');
  const [chatMode, setChatMode] = useState<'chat' | 'danmaku'>('chat');
  const [chatText, setChatText] = useState('');

  const { data: detail, isLoading, refetch } = useQuery({
    queryKey: roomKeys.detail(roomNo),
    queryFn: () => roomApi.detail(roomNo),
    enabled: !!roomNo,
    retry: false,
  });

  const canManage = myPermissions.includes('room.moderate');
  const canControl = myPermissions.includes('room.control') || roomDetail?.playMode === 2;
  const selfMember = members.find((m) => m.userId === selfId);
  const selfOnMic = mics.some((m) => m.userId === selfId);

  // HTTP 详情写入 store；WS join ack 到达后会再覆盖一次（两者结构一致）
  useEffect(() => {
    if (detail) applyRoomDetail(detail);
  }, [detail, applyRoomDetail]);

  // 密码房：非房主且还没有密码时先弹框，校验通过（HTTP join）再建立 WS 在线态
  useEffect(() => {
    // 用户信息尚未水合完成时先不决策，避免房主被误判为需要密码
    if (!detail || !selfId) return;
    // 已关闭/已解散的房间不建立在线态；房主可在页面上重开后重新进入
    if (detail.status !== 1) return;
    if (detail.passwordRequired && !password
      && String(detail.ownerId) !== selfId && detail.myRole !== 2) {
      setPasswordOpen(true);
      return;
    }
    joinRoom(String(detail.id), password);
    const roomId = String(detail.id);
    if (trackRoomRef.current.roomId !== roomId) {
      trackRoomRef.current = { roomId, enteredAt: Date.now() };
      track('room_enter_success', {
        room_id: roomId,
        password_required: Boolean(detail.passwordRequired),
        role: detail.myRole ?? null,
      });
    }
    // detail 的引用会因 refetch 变化，依赖只取真正影响入房决策的字段
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.id, detail?.status, detail?.passwordRequired, detail?.ownerId, password, selfId, joinRoom]);

  // 离开页面时关闭在线态；依赖为空，避免 refetch 触发误退房
  useEffect(
    () => () => {
      const tracked = trackRoomRef.current;
      if (tracked.roomId) {
        track('room_exit', {
          room_id: tracked.roomId,
          dwell_ms: Math.max(0, Date.now() - tracked.enteredAt),
        });
        trackRoomRef.current = { roomId: null, enteredAt: 0 };
      }
      leaveRoom();
    },
    [leaveRoom],
  );

  // 被踢 / 拉黑 / 关房：提示后回到列表
  useEffect(() => {
    if (!roomExitNotice) return;
    message.warning(roomExitNotice);
    clearRoomExitNotice();
    navigate('/rooms', { replace: true });
  }, [roomExitNotice, clearRoomExitNotice, navigate]);

  const submitPassword = async (): Promise<void> => {
    if (!detail) return;
    if (!passwordInput.trim()) {
      message.warning('请输入房间密码');
      return;
    }
    try {
      await roomApi.join(detail.id, passwordInput.trim());
      setPassword(passwordInput.trim());
      setPasswordOpen(false);
      setPasswordInput('');
    } catch {
      // 请求层已提示
    }
  };

  // 每秒重算：权威态 + 时钟偏移推期望位置，本地不累加（FR-22-001）
  const [nowTs, setNowTs] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNowTs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const isPlaying = playState?.playStatus === PLAY_STATUS.PLAYING
    || Boolean(playState?.isPlaying);
  const positionMs = useMemo(
    () => expectedPositionMs(playState, serverNow(nowTs, clockOffsetMs)),
    [playState, nowTs, clockOffsetMs],
  );
  const position = Math.floor(positionMs / 1000);
  const duration = ((playState?.durationMs ?? 0) > 0
    ? playState!.durationMs
    : (playState?.duration ?? 0) * 1000) / 1000;

  const voteNow = serverNow(nowTs, clockOffsetMs);
  const voteRemaining = vote ? voteRemainingSeconds(vote.endTime, voteNow) : 0;
  const voteRunning = isVoteActive(vote
    ? { status: vote.status, endTime: vote.endTime }
    : null, voteNow);
  // 结果只在结束后展示 10 秒，避免换了歌还挂着上一首的「投票通过」
  const voteEndedRecently = Boolean(vote?.endTime) && voteNow - (vote?.endTime ?? 0) <= 10_000;
  const showVote = voteRunning || voteEndedRecently;

  // 进房首屏：WS 只推增量，权威播放态与投票各拉一次
  useEffect(() => {
    if (!activeRoomId) return;
    void loadPlayState();
    void loadVote();
  }, [activeRoomId, loadPlayState, loadVote]);

  // 榜单每 30 秒刷新，切换 tab 立即拉一次
  useEffect(() => {
    if (!activeRoomId) return undefined;
    void loadRank(rankType);
    const timer = setInterval(() => void loadRank(rankType), 30_000);
    return () => clearInterval(timer);
  }, [activeRoomId, rankType, loadRank]);

  const [seekValue, setSeekValue] = useState<number | null>(null);
  const shownPosition = seekValue ?? position;

  const submitChat = (): void => {
    const text = chatText.trim();
    if (!text) return;
    if (chatMode === 'chat') {
      sendChat(text);
    } else {
      sendDanmaku(text);
      track('room_danmaku_send', {
        room_id: trackRoomRef.current.roomId ?? String(activeRoomId ?? ''),
        length: text.length,
      });
    }
    setChatText('');
  };

  if (!roomNo) return <Empty description="房间号缺失" />;
  if (isLoading && !detail) return <div className={styles.loading}>加载房间中…</div>;
  if (!detail && !roomDetail) return <Empty description="房间不存在或已解散" />;

  const room = roomDetail ?? detail!;

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/rooms')}>
          返回
        </Button>
        <Avatar src={room.cover ?? undefined} size={52} icon={<CustomerServiceOutlined />} />
        <div className={styles.headInfo}>
          <div className={styles.nameRow}>
            <Typography.Title level={4} className={styles.name}>{room.name}</Typography.Title>
            <Tag>#{room.roomNo}</Tag>
            {room.roomType !== 1 && <Tag color="purple">{room.roomType === 2 ? '私密' : '密码'}</Tag>}
            {room.status === 2 && <Tag color="orange">已关闭</Tag>}
          </div>
          <div className={styles.sub}>
            <span>{room.ownerNickname ?? '房主'}</span>
            <span>·</span>
            <Badge status={status === 'open' ? 'processing' : 'default'} text={
              status === 'open' ? `${onlineCount} 人在线` : `实时连接：${status}`
            } />
            {syncDegraded && <Tag color="red">时间同步失败</Tag>}
            {!syncDegraded && weakNetwork && <Tag color="orange">网络不稳定</Tag>}
            {room.themeTags && room.themeTags.split(',').filter(Boolean).map((tag) => (
              <Tag key={tag}>{tag}</Tag>
            ))}
          </div>
        </div>
        <Space className={styles.headerActions}>
          {isOwner && (
            <Button onClick={() => { setNoticeInput(room.notice ?? ''); setNoticeOpen(true); }}>
              编辑公告
            </Button>
          )}
          {myPermissions.includes('room.close') && room.status === 1 && (
            <Button danger onClick={() => void closeRoom(room.id, navigate)}>关闭房间</Button>
          )}
          {String(room.ownerId) === selfId && room.status === 2 && (
            <Button
              type="primary"
              onClick={() => {
                void roomApi.reopen(room.id)
                  .then(() => {
                    message.success('房间已重开');
                    return refetch();
                  })
                  .catch(() => undefined);
              }}
            >
              重新开播
            </Button>
          )}
          <Button icon={<LogoutOutlined />} onClick={() => {
            void roomApi.leave(room.id).catch(() => undefined);
            navigate('/rooms');
          }}>
            退出
          </Button>
        </Space>
      </header>

      {room.notice && <Alert className={styles.notice} type="info" showIcon message={room.notice} />}
      {myMuteRemainingSeconds > 0 && (
        <Alert
          className={styles.notice}
          type="warning"
          showIcon
          message={`你正在被禁言，剩余 ${Math.ceil(myMuteRemainingSeconds / 60)} 分钟`}
        />
      )}

      <div className={styles.body}>
        <main className={styles.main}>
          <Card
            className={styles.player}
            title={<Space><CustomerServiceOutlined />正在播放</Space>}
            extra={playState?.operatorId ? <Typography.Text type="secondary">由 {playState.operatorId} 控播</Typography.Text> : null}
          >
            <DanmakuLayer items={danmaku} now={nowTs} />
            <div className={styles.nowPlaying}>
              <Avatar shape="square" size={64} src={playState?.cover ?? undefined} icon={<CustomerServiceOutlined />} />
              <div className={styles.songInfo}>
                <div className={styles.songName}>{playState?.songName ?? '暂无歌曲'}</div>
                <div className={styles.artist}>{playState?.artistName ?? '从播放队列选择一首歌'}</div>
              </div>
              <Space>
                <Tooltip title={like.liked ? '已点赞' : '给当前歌曲点赞'}>
                  <Button
                    icon={<LikeOutlined />}
                    type={like.liked ? 'primary' : 'default'}
                    disabled={!playState?.songId}
                    onClick={() => {
                      if (!like.liked) {
                        track('room_like_click', {
                          room_id: trackRoomRef.current.roomId ?? String(activeRoomId ?? ''),
                          song_id: playState?.songId ? String(playState.songId) : null,
                        });
                      }
                      void likeCurrent();
                    }}
                  >
                    {like.count}
                  </Button>
                </Tooltip>
                <Button
                  type="primary"
                  shape="circle"
                  size="large"
                  disabled={!canControl || !playState?.songId}
                  icon={isPlaying ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
                  onClick={() => {
                    if (!canControl) return;
                    if (isPlaying) pause(shownPosition);
                    else play(undefined, shownPosition);
                  }}
                />
                <Button
                  disabled={!canControl || !playState?.songId}
                  onClick={() => seek(0, isPlaying)}
                >
                  重头播放
                </Button>
              </Space>
            </div>
            <Slider
              min={0}
              max={Math.max(duration, 1)}
              value={Math.min(shownPosition, Math.max(duration, 1))}
              disabled={!canControl || !playState?.songId}
              tooltip={{ formatter: (v) => formatTime(v ?? 0) }}
              onChange={(value) => setSeekValue(value)}
              onChangeComplete={(value) => {
                setSeekValue(null);
                if (canControl) seek(value, isPlaying);
              }}
            />
            <div className={styles.progressText}>
              <span>{formatTime(shownPosition)}</span>
              <span>{formatTime(duration)}</span>
            </div>
            <div className={styles.voteRow}>
              {vote && showVote ? (
                voteRunning ? (
                  <>
                    <Tag color="orange">跳过投票</Tag>
                    <span className={styles.voteText}>
                      {vote.agreeCount} 票 · 还差{' '}
                      {Math.max(0, requiredAgreeCount(vote.onlineCount) - vote.agreeCount)} 票
                      · 剩 {voteRemaining}s
                    </span>
                    <Button
                      size="small"
                      type="primary"
                      disabled={vote.voted}
                      onClick={() => void agreeVote()}
                    >
                      {vote.voted ? '已同意' : '同意跳过'}
                    </Button>
                  </>
                ) : (
                  <Typography.Text type="secondary">
                    {voteResultText(vote.status, vote.passed)}
                  </Typography.Text>
                )
              ) : (
                <Typography.Text type="secondary">
                  想跳过这首歌？发起投票，60 秒内在线成员表决
                </Typography.Text>
              )}
              {!voteRunning && playState?.songId && (
                <Button size="small" onClick={() => void startVote()}>发起投票</Button>
              )}
            </div>
          </Card>
          <RoomAudioSync canControl={canControl} />

          <Card
            title={`麦位 ${mics.filter((m) => m.userId).length}/${room.micCount}`}
            extra={
              <Button
                size="small"
                onClick={() => void toggleMic(room.id, selfOnMic)}
                disabled={!myPermissions.includes('room.mic.apply') || selfMember?.role === 9}
              >
                {selfOnMic ? '下麦' : '申请上麦'}
              </Button>
            }
          >
            <div className={styles.mics}>
              {mics.map((mic) => (
                <MicSeat
                  key={mic.micPos}
                  mic={mic}
                  canManage={canManage}
                  isOwner={isOwner}
                  onLock={(locked) => void lockMic(room.id, mic.micPos, locked)}
                />
              ))}
            </div>
            {micQueue.length > 0 && (
              <div className={styles.micQueue}>
                <Typography.Text type="secondary">排麦申请：</Typography.Text>
                {micQueue.map((item) => (
                  <Space key={item.id} size={4}>
                    <Tag>{item.nickname ?? item.userId}</Tag>
                    {canManage && (
                      <>
                        <Button size="small" type="link" onClick={() => void approveMic(room.id, item.userId, true)}>同意</Button>
                        <Button size="small" type="link" danger onClick={() => void approveMic(room.id, item.userId, false)}>拒绝</Button>
                      </>
                    )}
                  </Space>
                ))}
              </div>
            )}
          </Card>

          <Card
            title={`播放队列（${queue.length}）`}
            extra={
              <Button
                size="small"
                icon={<PlusOutlined />}
                disabled={!myPermissions.includes('room.queue.add')}
                onClick={() => setAddSongOpen(true)}
              >
                点歌
              </Button>
            }
          >
            {queue.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="队列为空" />
            ) : (
              <List
                size="small"
                dataSource={queue}
                renderItem={(item, index) => (
                  <List.Item
                    actions={[
                      isOwner && item.songId && item.status !== 3 ? (
                        <Button
                          key="pin"
                          size="small"
                          type="link"
                          onClick={() => void pinQueue(room.id, item.id, item.priority !== 100)}
                        >
                          {item.priority === 100 ? '取消置顶' : '置顶'}
                        </Button>
                      ) : null,
                      item.status === 0 && canManage ? (
                        <Button key="ok" size="small" type="link" onClick={() => void approveSong(room.id, item.id, true)}>通过</Button>
                      ) : null,
                      item.status === 0 && canManage ? (
                        <Button key="no" size="small" type="link" danger onClick={() => void approveSong(room.id, item.id, false)}>拒绝</Button>
                      ) : null,
                      (canManage || item.userId === selfId) ? (
                        <Button key="rm" size="small" type="link" danger onClick={() => void removeSong(room.id, item.id)}>移除</Button>
                      ) : null,
                    ].filter(Boolean)}
                  >
                    <List.Item.Meta
                      avatar={<span className={styles.queueIndex}>{index + 1}</span>}
                      title={
                        <Space size={4}>
                          <Typography.Text
                            className={canControl ? styles.queueSongLink : undefined}
                            onClick={() => {
                              if (!canControl || !item.songId) return;
                              switchSong(item.songId);
                            }}
                          >
                            {item.songName ?? '未知歌曲'}
                          </Typography.Text>
                          {item.status !== 1 && <Tag>{QUEUE_STATUS_LABEL[item.status]}</Tag>}
                          {(item.voteCount ?? 0) > 0 && <Tag color="blue">{item.voteCount} 票</Tag>}
                          {item.priority === 100 && <Tag color="gold">置顶</Tag>}
                        </Space>
                      }
                      description={`${item.artistName ?? ''} · 点歌人 ${item.nickname ?? item.userId}`}
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>

          <Card
            title={<Space><TrophyOutlined />房间榜单</Space>}
            extra={(
              <Space size={6}>
                <Typography.Text type="secondary">上榜</Typography.Text>
                <Switch
                  size="small"
                  checked={rankVisible}
                  onChange={(checked) => {
                    setRankVisibleLocal(checked);
                    void setRankVisible(checked);
                  }}
                />
              </Space>
            )}
          >
            <Segmented
              size="small"
              options={RANK_TABS}
              value={rankType}
              onChange={(value) => setRankType(value as RankType)}
              style={{ marginBottom: 8 }}
            />
            {ranks[rankType].length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无上榜数据" />
            ) : (
              ranks[rankType].map((entry) => (
                <div key={entry.userId} className={styles.rankRow}>
                  <span className={styles.rankNo}>{entry.rank}</span>
                  <Avatar size={22} src={entry.avatar ?? undefined} icon={<UserOutlined />} />
                  <span className={styles.rankName}>{entry.nickname ?? entry.userId}</span>
                  <span className={styles.rankScore}>{entry.score.toLocaleString()}</span>
                </div>
              ))
            )}
          </Card>

          <Card title={`成员（${onlineCount} 在线 / ${members.length}）`}>
            <List
              size="small"
              dataSource={members}
              renderItem={(member) => (
                <List.Item
                  actions={memberActions(member, {
                    isOwner,
                    canManage,
                    selfId,
                    roomId: room.id,
                    canAdminManage: myPermissions.includes('room.admin.manage'),
                    onTransfer: (userId) => roomApi.transfer(room.id, userId),
                  })}
                >
                  <List.Item.Meta
                    avatar={<Avatar src={member.avatar ?? undefined} icon={<UserOutlined />} />}
                    title={
                      <Space size={6}>
                        <span>{member.nickname ?? member.userId}</span>
                        {member.role > 0 && <Tag color={member.role === 2 ? 'gold' : 'blue'}>{ROLE_LABEL[member.role]}</Tag>}
                        {member.muted && <Tag color="red">禁言中</Tag>}
                      </Space>
                    }
                    description={
                      <Badge
                        status={member.online ? 'success' : 'default'}
                        text={member.online ? '在线' : '离线'}
                      />
                    }
                  />
                </List.Item>
              )}
            />
          </Card>
        </main>

        <aside className={styles.chat}>
          <div className={styles.chatHeader}>
            <span>房间聊天</span>
            <Segmented
              size="small"
              options={[{ label: '聊天', value: 'chat' }, { label: '弹幕', value: 'danmaku' }]}
              value={chatMode}
              onChange={(value) => setChatMode(value as 'chat' | 'danmaku')}
            />
          </div>
          <ChatList messages={messages} selfId={selfId} />
          <div className={styles.inputRow}>
            <Input
              value={chatText}
              maxLength={chatMode === 'chat' ? 200 : 30}
              placeholder={chatMode === 'chat' ? '说点什么…（回车发送）' : '发条弹幕…'}
              onChange={(e) => setChatText(e.target.value)}
              onPressEnter={submitChat}
            />
            <Button type="primary" icon={<SendOutlined />} onClick={submitChat}>发送</Button>
          </div>
          <div className={styles.emojiRow}>
            {EMOJI_PRESET.map((emoji) => (
              <button
                key={emoji}
                type="button"
                className={styles.emojiBtn}
                onClick={() => {
                  sendEmoji(emoji);
                  track('room_emoji_send', {
                    room_id: trackRoomRef.current.roomId ?? String(activeRoomId ?? ''),
                    emoji,
                  });
                }}
              >
                {emoji}
              </button>
            ))}
          </div>
        </aside>
      </div>

      <Modal
        title="输入房间密码"
        open={passwordOpen}
        onOk={() => void submitPassword()}
        onCancel={() => navigate('/rooms')}
        okText="进入房间"
        cancelText="返回列表"
        closable={false}
        maskClosable={false}
      >
        <Input.Password
          value={passwordInput}
          onChange={(e) => setPasswordInput(e.target.value)}
          onPressEnter={() => void submitPassword()}
          placeholder="请输入 4-16 位房间密码"
          maxLength={16}
        />
      </Modal>

      <AddSongModal
        open={addSongOpen}
        roomId={room.id}
        onClose={() => setAddSongOpen(false)}
      />

      <Modal
        title="编辑公告"
        open={noticeOpen}
        onOk={() => void saveNotice(room.id, noticeInput, refetch)}
        onCancel={() => setNoticeOpen(false)}
        okText="保存"
      >
        <Input.TextArea
          rows={4}
          maxLength={500}
          showCount
          value={noticeInput}
          onChange={(e) => setNoticeInput(e.target.value)}
        />
      </Modal>
    </div>
  );
}

// ------------------------------------------------------------------ 子组件

/**
 * 弹幕浮层（BR-22-065）：只展示最近 3 秒、最多 10 条。
 *
 * `items` 是 store 里的 30 条环形缓冲；这里按时间过滤而不是在 store 里删，
 * 保证聊天区仍能看到历史弹幕。
 */
function DanmakuLayer({ items, now }: { items: DanmakuView[]; now: number }): JSX.Element {
  const visible = items
    .filter((item) => now - item.createTime <= 3_000)
    .slice(-10);
  return (
    <div className={styles.danmakuLayer}>
      {visible.map((item, index) => (
        <div
          key={item.messageId}
          className={styles.danmakuItem}
          style={{ top: 8 + index * 34 }}
        >
          {item.nickname && <span className={styles.danmakuUser}>{item.nickname}</span>}
          {item.content}
        </div>
      ))}
    </div>
  );
}

function ChatList({ messages: list, selfId }: {
  messages: RoomMessageView[];
  selfId: string;
}): JSX.Element {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [list.length]);
  return (
    <div className={styles.messageList} ref={ref}>
      {list.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有消息" />
      ) : (
        list.map((item) => {
          const system = item.messageType === 3 || item.messageType === 5;
          return (
            <div
              key={item.messageId}
              className={`${styles.message} ${system ? styles.systemMessage : ''} ${item.userId === selfId ? styles.selfMessage : ''}`}
            >
              {system ? (
                <span>{item.content}</span>
              ) : (
                <>
                  <span className={styles.messageUser}>{item.nickname ?? item.userId}：</span>
                  <span className={item.messageType === 2 ? styles.danmaku : undefined}>{item.content}</span>
                </>
              )}
            </div>
          );
        })
      )}
    </div>
  );
}

function MicSeat({ mic, canManage, isOwner, onLock }: {
  mic: MicVO;
  canManage: boolean;
  isOwner: boolean;
  onLock: (locked: boolean) => void;
}): JSX.Element {
  const occupied = !!mic.userId;
  const canLock = canManage && !mic.ownerSeat && !occupied;
  return (
    <div className={`${styles.mic} ${occupied ? styles.micBusy : ''}`}>
      <Avatar
        size={44}
        src={mic.avatar ?? undefined}
        icon={occupied ? <UserOutlined /> : <CustomerServiceOutlined />}
      />
      <div className={styles.micName}>{mic.nickname ?? (mic.locked ? '已锁定' : '空麦')}</div>
      <div className={styles.micPos}>#{mic.micPos}{mic.ownerSeat ? ' 房主' : ''}</div>
      {occupied && !mic.online && <Tag className={styles.offlineTag}>离线</Tag>}
      {canLock && (
        <Tooltip title={mic.locked ? '解锁麦位' : '锁定麦位'}>
          <Button
            size="small"
            type="text"
            icon={mic.locked ? <UnlockOutlined /> : <LockOutlined />}
            onClick={() => onLock(!mic.locked)}
          />
        </Tooltip>
      )}
      {isOwner && mic.ownerSeat && <Tag color="gold">专属</Tag>}
    </div>
  );
}

function AddSongModal({ open, roomId, onClose }: {
  open: boolean;
  roomId: string;
  onClose: () => void;
}): JSX.Element {
  const [songId, setSongId] = useState<string | undefined>();
  const { data } = useQuery({
    queryKey: ['room', 'songs', open],
    queryFn: () => songApi.list({ pageNum: 1, pageSize: 50, status: 1 }),
    enabled: open,
  });
  const songs = (data?.list ?? []) as Song[];
  return (
    <Modal
      title="点歌"
      open={open}
      onCancel={onClose}
      onOk={() => {
        if (!songId) {
          message.warning('请选择歌曲');
          return;
        }
        void roomApi.addSong(roomId, songId)
          .then(() => {
            message.success('已加入点歌队列');
            setSongId(undefined);
            onClose();
          })
          .catch(() => undefined);
      }}
      okText="加入队列"
    >
      <Select
        showSearch
        style={{ width: '100%' }}
        placeholder="搜索并选择歌曲"
        value={songId}
        onChange={setSongId}
        optionFilterProp="label"
        options={songs.map((song) => ({
          value: String(song.id),
          label: `${song.name} - ${song.artistName ?? ''}`,
        }))}
      />
    </Modal>
  );
}

// ------------------------------------------------------------------ 写操作

async function toggleMic(roomId: string, onMic: boolean): Promise<void> {
  try {
    if (onMic) {
      await roomApi.leaveMic(roomId);
      message.success('已下麦');
      return;
    }
    const result = await roomApi.applyMic(roomId);
    message.success(result.onMic ? '已上麦' : '已提交排麦申请，等待房主审批');
  } catch {
    // 请求层已提示
  }
}

async function lockMic(roomId: string, micPos: number, locked: boolean): Promise<void> {
  try {
    await roomApi.lockMic(roomId, micPos, locked);
    message.success(locked ? '麦位已锁定' : '麦位已解锁');
  } catch {
    /* ignore */
  }
}

async function approveMic(roomId: string, userId: string, approve: boolean): Promise<void> {
  try {
    await roomApi.approveMic(roomId, userId, approve);
    message.success(approve ? '已通过上麦申请' : '已拒绝上麦申请');
  } catch {
    /* ignore */
  }
}

async function approveSong(roomId: string, queueId: string, approve: boolean): Promise<void> {
  try {
    await roomApi.approveSong(roomId, queueId, approve);
  } catch {
    /* ignore */
  }
}

async function removeSong(roomId: string, queueId: string): Promise<void> {
  try {
    await roomApi.removeSong(roomId, queueId);
  } catch {
    /* ignore */
  }
}

async function pinQueue(roomId: string, queueId: string, pinned: boolean): Promise<void> {
  try {
    await roomApi.pinQueue(roomId, queueId, pinned);
    message.success(pinned ? '已置顶' : '已取消置顶');
  } catch {
    /* 请求层已提示 */
  }
}

async function closeRoom(roomId: string, navigate: (path: string) => void): Promise<void> {
  Modal.confirm({
    title: '关闭房间',
    content: '关闭后所有成员会断开，24 小时内可以重开。确定关闭吗？',
    okText: '关闭房间',
    okButtonProps: { danger: true },
    onOk: async () => {
      await roomApi.close(roomId);
      message.success('房间已关闭');
      navigate('/rooms');
    },
  });
}

async function saveNotice(roomId: string, notice: string, refetch: () => void): Promise<void> {
  try {
    await roomApi.notice(roomId, notice);
    message.success('公告已更新');
    refetch();
  } catch {
    /* ignore */
  }
}

function memberActions(
  member: RoomMemberVO,
  options: {
    isOwner: boolean;
    canManage: boolean;
    selfId: string;
    roomId: string;
    canAdminManage: boolean;
    onTransfer: (userId: string) => Promise<unknown>;
  },
): JSX.Element[] {
  const { isOwner, canManage, selfId, roomId, canAdminManage } = options;
  if (!canManage || member.userId === selfId) return [];
  const actions: JSX.Element[] = [];

  const run = (fn: () => Promise<unknown>, success: string): void => {
    void fn().then(() => message.success(success)).catch(() => undefined);
  };

  if (member.muted) {
    actions.push(
      <Button key="unmute" size="small" type="link"
        onClick={() => run(() => roomApi.unmute(roomId, member.userId), '已解除禁言')}>
        解除禁言
      </Button>,
    );
  } else {
    actions.push(
      <Button key="mute" size="small" type="link"
        onClick={() => run(() => roomApi.mute(roomId, { userId: member.userId, durationMinutes: 10 }), '已禁言 10 分钟')}>
        禁言
      </Button>,
    );
    if (isOwner) {
      actions.push(
        <Button key="mute-perm" size="small" type="link" danger
          onClick={() => run(() => roomApi.mute(roomId, { userId: member.userId, durationMinutes: -1 }), '已永久禁言')}>
          永久禁言
        </Button>,
      );
    }
  }

  if (canAdminManage && member.role !== 2) {
    actions.push(
      <Button key="admin" size="small" type="link"
        onClick={() => run(
          () => roomApi.setAdmin(roomId, member.userId, member.role !== 1),
          member.role === 1 ? '已撤销房管' : '已设为房管',
        )}>
        {member.role === 1 ? '撤销房管' : '设为房管'}
      </Button>,
    );
  }

  if (canManage) {
    actions.push(
      <Dropdown
        key="more"
        menu={{
          items: [
            { key: 'transfer', label: '转让房主', disabled: !isOwner || member.role === 9 },
            { key: 'kick', label: '踢出房间', danger: true },
            { key: 'blacklist', label: '拉黑', danger: true, disabled: !isOwner },
          ],
          onClick: ({ key }) => {
            if (key === 'transfer') {
              Modal.confirm({
                title: `把房主转让给 ${member.nickname ?? member.userId}？`,
                content: '转让后你将变为房管。',
                onOk: () => options.onTransfer(member.userId),
              });
            } else if (key === 'kick') {
              Modal.confirm({
                title: `踢出 ${member.nickname ?? member.userId}？`,
                content: '被踢用户 5 分钟内不能重新进入。',
                onOk: () => roomApi.kick(roomId, { userId: member.userId, reason: '违反房间规则' }),
              });
            } else if (key === 'blacklist') {
              Modal.confirm({
                title: `拉黑 ${member.nickname ?? member.userId}？`,
                content: '拉黑后该用户将无法再进入本房间。',
                onOk: () => roomApi.blacklist(roomId, member.userId),
              });
            }
          },
        }}
      >
        <Button size="small" type="text" icon={<MoreOutlined />} />
      </Dropdown>,
    );
  }
  return actions;
}
