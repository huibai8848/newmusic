import { create } from 'zustand';
import { refreshAccessToken } from '@/api/request';
import { roomApi } from '@/api/room';
import { wsApi } from '@/api/ws';
import { WsClient, type ClockSyncState, type WsEnvelope } from '@/services/wsClient';
import { shouldApplyVersion, type WsStatus } from '@/services/wsCore';
import { voteRemainingSeconds } from '@/services/roomSync';
import type {
  MicVO,
  NotificationItem,
  QueueItemVO,
  RankType,
  RoomDetailVO,
  RoomMemberVO,
  RoomMessageItem,
  RoomRankEntryVO,
} from '@/types/api';

export type { RankType };
import { StorageKey, readRaw } from '@/utils/storage';
import { getDeviceId } from '@/utils/device';
import { getNotificationApi, toastError, toastInfo, toastWarning } from '@/utils/feedback';

/**
 * 实时通信状态（模块 04）。
 *
 * 分工：`wsClient` 管连接与重发，`wsCore` 管纯规则，本 store 只把 WS 帧翻译成
 * 页面要的视图状态。组件不直接碰 WebSocket。
 *
 * 关键不变量：
 * - 房间消息最多保留 {@link MAX_MESSAGES} 条，超出丢最旧的（弹幕高频，不能无限涨）；
 * - 播放态只接受 version 更大的快照（BR-13-021），乱序/重复帧不会让进度回跳；
 * - 通知未读数以服务端 `notify.unread` 为准，本地只做「刚标记已读」的即时反馈。
 */

export interface RoomMessageView {
  /** 服务端 messageId；本地乐观消息用 clientMsgId 占位 */
  messageId: string;
  roomId: string;
  userId: string;
  nickname?: string;
  content: string;
  /** 1聊天 2弹幕 3系统 5进房 */
  messageType: number;
  createTime: number;
  /** 已发送、尚未收到 ack */
  pending?: boolean;
  /** 快捷表情（ROOM_EMOJI） */
  emoji?: string;
}

export interface PlayStateView {
  songId: string | null;
  songName: string | null;
  artistName: string | null;
  cover: string | null;
  /** 秒制兼容字段（模块 04） */
  duration: number;
  position: number;
  isPlaying: boolean;
  version: number;
  serverTime: number;
  operatorId?: string | null;

  // 模块 13 毫秒制权威字段（FR-22-001）
  durationMs: number;
  /** 1播放中 2暂停 3缓冲中 4结束 */
  playStatus: number;
  startPositionMs: number;
  stateUpdateTimeMs: number;
  playStateVersion: number;
  /** 百分比，100 = 1.0x */
  playbackRate: number;
  volume: number;
  /** 当前歌曲累计点赞数（BR-22-069）；老服务端不带时为 undefined */
  likeCount?: number;
}

/** 当前/最近一次跳过投票（FR-22-004） */
export interface VoteView {
  voteId: string;
  roomId: string;
  songId: string | null;
  songName: string | null;
  initiatorId: string | null;
  initiatorNickname: string | null;
  onlineCount: number;
  agreeCount: number;
  /** 1进行中 2通过 3未通过 4取消 */
  status: number;
  startTime: number | null;
  endTime: number | null;
  remainingSeconds: number;
  /** 当前用户是否已投（服务端 voted 字段或本地投票后置位） */
  voted: boolean;
  passed: boolean;
  message?: string | null;
}

/** 弹幕/表情浮层（BR-22-065：最多 10 条，展示 3 秒） */
export interface DanmakuView {
  messageId: string;
  userId: string;
  nickname: string;
  content: string;
  createTime: number;
  emoji?: boolean;
}


export interface RoomLikeView {
  songId: string | null;
  count: number;
  liked: boolean;
}

export interface MentionView {
  roomId: string;
  fromNickname: string;
  content: string;
  createTime: number;
}

export interface NotificationView {
  id: string;
  notifyType: number;
  title: string;
  content: string;
  fromNickname?: string | null;
  targetType?: number | null;
  targetId?: string | null;
  isRead: boolean;
  createTime: number;
}

interface WsState {
  status: WsStatus;
  /** 连续 10 次重连失败后置 true，UI 展示「点击重试」 */
  gaveUp: boolean;
  deviceId: string;

  roomId: string | null;
  isOwner: boolean;
  ownerId: string | null;
  onlineCount: number;
  messages: RoomMessageView[];
  playState: PlayStateView | null;

  /** 模块 05：房间资料与成员/麦位/队列（HTTP 首屏与 WS 增量共用一个视图） */
  roomDetail: RoomDetailVO | null;
  members: RoomMemberVO[];
  mics: MicVO[];
  queue: QueueItemVO[];
  micQueue: QueueItemVO[];
  myRole: number;
  myPermissions: string[];
  myMuteRemainingSeconds: number;
  kickedRemainingSeconds: number;
  /** 密码房密码：仅保存在内存，重连时重放，不落 localStorage */
  roomPassword: string | null;
  /** 被踢/被拉黑/房间关闭时的提示，页面消费后清除 */
  roomExitNotice: string | null;

  unread: number;
  notifications: NotificationView[];

  // ---------------------------------------------------------------- 模块 13
  /** 服务端时间 - 本地时间（毫秒）；时钟同步失败时为 0 且 syncDegraded=true */
  clockOffsetMs: number;
  clockRttMs: number;
  syncDegraded: boolean;
  /** RTT > 500ms 或连续重连失败：UI 显示弱网提示（BR-22-083） */
  weakNetwork: boolean;
  reconnectAttempt: number;
  vote: VoteView | null;
  danmaku: DanmakuView[];
  like: RoomLikeView;
  ranks: Record<RankType, RoomRankEntryVO[]>;
  mention: MentionView | null;

  start: () => void;
  stop: () => void;
  retry: () => void;

  joinRoom: (roomId: string, password?: string) => void;
  leaveRoom: () => void;
  /** HTTP 拉到的详情写入 store，WS 后续事件做增量覆盖 */
  applyRoomDetail: (detail: RoomDetailVO | null) => void;
  clearRoomExitNotice: () => void;
  sendChat: (content: string) => void;
  sendDanmaku: (content: string) => void;
  play: (songId?: string, position?: number) => void;
  pause: (position?: number) => void;
  seek: (position: number, isPlaying?: boolean) => void;
  switchSong: (songId: string, position?: number) => void;
  requestSync: () => void;

  // ---------------------------------------------------------------- 模块 13
  /** 进房首屏拉一次权威播放态（WS 只推增量） */
  loadPlayState: () => Promise<void>;
  sendEmoji: (emoji: string) => void;
  startVote: () => Promise<void>;
  agreeVote: () => Promise<void>;
  loadVote: () => Promise<void>;
  likeCurrent: () => Promise<void>;
  loadRank: (type: RankType) => Promise<void>;
  setRankVisible: (visible: boolean) => Promise<void>;
  clearMention: () => void;

  loadNotifications: () => Promise<void>;
  markRead: (id: string) => Promise<void>;
  markAllRead: () => Promise<void>;
}

const MAX_MESSAGES = 200;
const MAX_NOTIFICATIONS = 20;
/** 弹幕浮层只保留最近 30 条（UI 按 3 秒窗口最多同时显示 10 条） */
const MAX_DANMAKU = 30;

/** 换房/离房时要清空模块 13 的房间级状态 */
function roomUpgradeReset(): Pick<WsState,
  'vote' | 'danmaku' | 'like' | 'ranks' | 'mention'> {
  return {
    vote: null,
    danmaku: [],
    like: { songId: null, count: 0, liked: false },
    ranks: { gift: [], active: [], request: [], listen: [] },
    mention: null,
  };
}

/** 模块级单例：WS 连接跨页面共享，不能随组件挂载次数重建 */
let client: WsClient | null = null;

/** 昵称缓存（断线补偿消息没有 nickname 字段时兜底） */
const nicknameCache: Record<string, string> = {};

const NOTIFY_LABEL: Record<number, string> = {
  1: '系统通知',
  2: '评论回复',
  3: '点赞',
  4: '关注',
  5: '私信',
  6: '@提醒',
  7: '房间邀请',
  8: '审核结果',
  9: '举报结果',
  10: '新歌提醒',
  11: '封禁通知',
  12: '申诉结果',
  13: '推荐',
  14: '房间关注',
};

/**
 * 模块 10/16：私信与群聊相关 WS 帧（页面通过订阅消费，不把消息塞进全局 store）。
 *
 * `group.removed` / `group.dissolved` 的后端 data 是裸 conversationId（数字），
 * 这里统一包成 `{ conversationId }`，页面不用再兼容两种形状。
 */
export interface SocialMessageEvent {
  type: 'msg.new' | 'msg.read' | 'msg.revoke' | 'group.removed' | 'group.dissolved';
  data: Record<string, unknown>;
}

const socialMessageListeners = new Set<(event: SocialMessageEvent) => void>();

/** 订阅私信 WS 事件；返回取消订阅函数（组件卸载时调用） */
export function subscribeSocialMessageEvents(
  listener: (event: SocialMessageEvent) => void,
): () => void {
  socialMessageListeners.add(listener);
  return () => {
    socialMessageListeners.delete(listener);
  };
}

const REJECT_LABEL: Record<string, string> = {
  empty: '内容不能为空',
  too_long: '内容过长',
  not_in_room: '尚未加入房间',
  blacklisted: '你已被禁言',
  muted: '你正在禁言中',
  rate_limited: '发送太频繁，请稍后再试',
  sensitive: '内容包含敏感词',
  missing_client_msg_id: '客户端消息 ID 缺失',
};

function rejectText(reason: string): string {
  return REJECT_LABEL[reason] ?? `发送失败（${reason}）`;
}

function str(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  return String(value);
}

function num(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function bool(value: unknown): boolean {
  return value === true || value === 'true' || value === 1;
}

function toPlayState(data: Record<string, unknown>): PlayStateView | null {
  // HTTP `/play-state` 的 VO 只有 playStateVersion；WS 快照两个都给。
  // 缺了 version 就不能丢帧——否则首屏永远拿不到权威态。
  const version = num(data.version) ?? num(data.playStateVersion);
  if (version === null) return null;
  const duration = num(data.duration) ?? 0;
  const position = num(data.position) ?? 0;
  const playStatus = num(data.playStatus) ?? (bool(data.isPlaying) ? 1 : 2);
  const durationMs = num(data.durationMs) ?? duration * 1000;
  return {
    songId: str(data.songId),
    songName: str(data.songName),
    artistName: str(data.artistName),
    cover: str(data.cover),
    duration,
    position,
    isPlaying: bool(data.isPlaying) || playStatus === 1,
    version,
    serverTime: num(data.serverTime) ?? Date.now(),
    operatorId: str(data.operatorId),
    durationMs,
    playStatus,
    startPositionMs: num(data.startPositionMs) ?? position * 1000,
    stateUpdateTimeMs: num(data.stateUpdateTimeMs) ?? num(data.serverTime) ?? 0,
    playStateVersion: num(data.playStateVersion) ?? version,
    playbackRate: num(data.playbackRate) ?? 100,
    volume: num(data.volume) ?? 100,
    // BR-22-069：累计点赞数随播放态下发，后进房的人也能看到；老服务端不带
    likeCount: num(data.likeCount) ?? undefined,
  };
}

function toVoteView(data: Record<string, unknown>, now = Date.now()): VoteView | null {
  const voteId = str(data.voteId);
  if (!voteId) return null;
  const endTime = num(data.endTime);
  const status = num(data.status) ?? 1;
  return {
    voteId,
    roomId: str(data.roomId) ?? '',
    songId: str(data.songId),
    songName: str(data.songName),
    initiatorId: str(data.initiatorId),
    initiatorNickname: str(data.initiatorNickname),
    onlineCount: num(data.onlineCount) ?? 0,
    agreeCount: num(data.agreeCount) ?? 0,
    status,
    startTime: num(data.startTime),
    endTime,
    remainingSeconds: num(data.remainingSeconds) ?? voteRemainingSeconds(endTime, now),
    voted: bool(data.voted),
    passed: bool(data.passed) || status === 2,
    message: str(data.message),
  };
}

function fromHttpMessage(item: RoomMessageItem, nicknames: Record<string, string>): RoomMessageView {
  return {
    messageId: String(item.id),
    roomId: String(item.roomId),
    userId: String(item.userId),
    nickname: nicknames[String(item.userId)],
    content: item.content ?? '',
    messageType: item.messageType,
    createTime: parseTime(item.createTime),
  };
}

/** 后端 LocalDateTime 是 `yyyy-MM-dd HH:mm:ss`（本地时区），Safari 对空格分隔解析不稳 */
function parseTime(value: string | number | null | undefined): number {
  if (typeof value === 'number') return value;
  if (!value) return Date.now();
  const parsed = Date.parse(value.replace(' ', 'T'));
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

/** 队列按 orderIndex 降序；没有该字段时退回入队序号升序（老服务端） */
function compareQueue(a: QueueItemVO, b: QueueItemVO): number {
  const ao = a.orderIndex;
  const bo = b.orderIndex;
  if (ao !== undefined && bo !== undefined && ao !== bo) return bo - ao;
  return (a.seq ?? 0) - (b.seq ?? 0);
}

function toNotificationView(item: NotificationItem): NotificationView {
  return {
    id: String(item.id),
    notifyType: item.notifyType,
    title: item.title ?? NOTIFY_LABEL[item.notifyType] ?? '通知',
    content: item.content ?? '',
    fromNickname: item.fromNickname,
    targetType: item.targetType,
    targetId: item.targetId === null || item.targetId === undefined ? null : String(item.targetId),
    isRead: Number(item.isRead) === 1,
    createTime: parseTime(item.createTime),
  };
}

export const useWsStore = create<WsState>((set, get) => {
  /** 把一帧消息追加进当前房间；按 messageId 幂等，重复帧直接忽略 */
  const appendMessage = (message: RoomMessageView): void => {
    set((state) => {
      if (message.roomId !== state.roomId) return state;
      const exists = state.messages.some((m) => m.messageId === message.messageId);
      if (exists) {
        // 乐观消息被服务端确认：用确认后的内容替换（保留顺序）
        return {
          messages: state.messages.map((m) => (m.messageId === message.messageId ? message : m)),
        };
      }
      const next = [...state.messages, message];
      return { messages: next.length > MAX_MESSAGES ? next.slice(next.length - MAX_MESSAGES) : next };
    });
  };

  const appendSystem = (roomId: string, content: string, type: number): void => {
    appendMessage({
      messageId: `sys-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      roomId,
      userId: '0',
      content,
      messageType: type,
      createTime: Date.now(),
    });
  };

  const applyPlayState = (data: Record<string, unknown>): void => {
    const incoming = toPlayState(data);
    if (!incoming) return;
    const current = get().playState;
    if (!shouldApplyVersion(current?.version, incoming.version)) return;
    const songChanged = incoming.songId !== current?.songId;
    set((state) => ({
      playState: incoming,
      // 换了歌就重置点赞；同一首歌以服务端累计值为准（本地 liked 保留）
      like: songChanged
        ? { songId: incoming.songId, count: incoming.likeCount ?? 0, liked: false }
        : { ...state.like, count: incoming.likeCount ?? state.like.count },
      // 换歌时旧投票已失效；已出结果的保留几秒，由页面按 endTime 自行淡出
      vote: songChanged && state.vote?.status === 1 ? null : state.vote,
    }));
  };

  const appendDanmaku = (item: DanmakuView): void => {
    set((state) => {
      const list = [...state.danmaku, item];
      return { danmaku: list.slice(-MAX_DANMAKU) };
    });
  };

  const rememberNickname = (userId: string, nickname: string | null | undefined): void => {
    if (!nickname) return;
    // 仅用于断线补偿消息（HTTP 结构里没有昵称）
    nicknameCache[userId] = nickname;
  };

  const onNotification = (data: Record<string, unknown>): void => {
    const id = str(data.notificationId);
    if (!id) return;
    const title = str(data.title) ?? '通知';
    const content = str(data.content) ?? '';
    const notifyType = num(data.notifyType) ?? 1;
    const fromUser = (data.fromUser ?? {}) as Record<string, unknown>;
    set((state) => {
      if (state.notifications.some((n) => n.id === id)) return state;
      const next: NotificationView = {
        id,
        notifyType,
        title,
        content,
        fromNickname: str(fromUser.nickname),
        targetType: num(data.targetType),
        targetId: str(data.targetId),
        isRead: false,
        createTime: num(data.createTime) ?? Date.now(),
      };
      const list = [next, ...state.notifications];
      return { notifications: list.slice(0, MAX_NOTIFICATIONS) };
    });

    const api = getNotificationApi();
    if (api) {
      api.open({
        key: `notify-${id}`,
        message: title,
        description: content,
        placement: 'bottomRight',
        duration: 5,
      });
    } else {
      toastInfo(content ? `${title}：${content}` : title);
    }
  };

  const handleMessage = (message: WsEnvelope): void => {
    const data = (message.data ?? {}) as Record<string, unknown>;
    switch (message.type) {
      case 'notify.unread':
        set({ unread: num(data.unread) ?? 0 });
        break;
      case 'room.join.ack': {
        const roomId = str(data.roomId);
        if (!roomId || roomId !== get().roomId) break;
        const detail = (data.room ?? null) as RoomDetailVO | null;
        set({
          isOwner: bool(data.isOwner),
          ownerId: str(data.ownerId),
          onlineCount: num(data.onlineCount) ?? 0,
          roomDetail: detail,
          members: (data.members ?? detail?.members ?? []) as RoomMemberVO[],
          mics: (data.mics ?? detail?.mics ?? []) as MicVO[],
          queue: (data.queue ?? detail?.queue ?? []) as QueueItemVO[],
          myRole: num(data.myRole) ?? detail?.myRole ?? -1,
          myPermissions: Array.isArray(data.myPermissions)
            ? (data.myPermissions as string[])
            : (detail?.myPermissions ?? []),
          myMuteRemainingSeconds: num(data.myMuteRemainingSeconds) ?? 0,
          kickedRemainingSeconds: num(data.kickedRemainingSeconds) ?? 0,
          roomExitNotice: null,
        });
        const snapshot = (data.snapshot ?? {}) as Record<string, unknown>;
        if (Object.keys(snapshot).length > 0) {
          // 首次入房时 version 可能是 0，仍要应用
          const incoming = toPlayState(snapshot);
          if (incoming && (get().playState === null
            || shouldApplyVersion(get().playState?.version, incoming.version))) {
            set({ playState: incoming });
          }
        }
        break;
      }
      case 'room.chat':
      case 'room.danmaku': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId) break;
        const userId = str(data.userId) ?? '0';
        rememberNickname(userId, str(data.nickname));
        appendMessage({
          messageId: str(data.messageId) ?? message.msgId ?? `anon-${Date.now()}`,
          roomId,
          userId,
          nickname: str(data.nickname) ?? undefined,
          content: str(data.content) ?? '',
          messageType: num(data.messageType) ?? (message.type === 'room.danmaku' ? 2 : 1),
          createTime: num(data.createTime) ?? Date.now(),
        });
        break;
      }
      case 'room.member.join': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId) break;
        const nickname = str(data.nickname) ?? '有人';
        const userId = str(data.userId) ?? '';
        rememberNickname(userId, nickname);
        set((state) => {
          const exists = state.members.some((m) => m.userId === userId);
          return {
            onlineCount: num(data.onlineCount) ?? state.onlineCount,
            members: exists
              ? state.members.map((m) => (m.userId === userId ? { ...m, online: true } : m))
              : [...state.members, {
                userId,
                nickname,
                avatar: null,
                role: 0,
                online: true,
                muted: false,
                muteUntil: null,
                joinTime: null,
                totalDuration: 0,
                contribute: 0,
              }],
          };
        });
        appendSystem(roomId, `${nickname} 加入了房间`, 5);
        break;
      }
      case 'room.member.leave': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId) break;
        const userId = str(data.userId);
        set((state) => ({
          onlineCount: num(data.onlineCount) ?? state.onlineCount,
          members: userId
            ? state.members.map((m) => (m.userId === userId ? { ...m, online: false } : m))
            : state.members,
        }));
        appendSystem(roomId, `${str(data.nickname) ?? '有人'} 离开了房间`, 3);
        break;
      }
      case 'room.member.update': {
        const roomId = str(data.roomId) ?? message.roomId;
        const userId = str(data.userId);
        if (!roomId || !userId) break;
        const role = num(data.role);
        set((state) => {
          const selfId = readSelf().id;
          return {
            members: state.members.map((m) => (m.userId === userId
              ? {
                ...m,
                role: role ?? m.role,
                muted: bool(data.muted) || (data.muted === undefined ? m.muted : false),
                muteUntil: str(data.muteUntil) ?? m.muteUntil,
              }
              : m)),
            myRole: userId === selfId && role !== null ? role : state.myRole,
            myMuteRemainingSeconds: userId === selfId
              ? num(data.muteRemainingSeconds) ?? state.myMuteRemainingSeconds
              : state.myMuteRemainingSeconds,
            roomExitNotice: userId === selfId && role === 9 ? '你已被拉黑，无法进入该房间' : state.roomExitNotice,
          };
        });
        break;
      }
      case 'room.mic.update': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId) break;
        set((state) => ({
          mics: Array.isArray(data.mics) ? (data.mics as MicVO[]) : state.mics,
          micQueue: Array.isArray(data.micQueue) ? (data.micQueue as QueueItemVO[]) : state.micQueue,
          onlineCount: num(data.onlineCount) ?? state.onlineCount,
        }));
        break;
      }
      case 'room.queue.update': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId) break;
        set((state) => ({
          queue: Array.isArray(data.queue) ? (data.queue as QueueItemVO[]) : state.queue,
          micQueue: Array.isArray(data.micQueue) ? (data.micQueue as QueueItemVO[]) : state.micQueue,
        }));
        break;
      }
      case 'room.notice.update': {
        set((state) => (state.roomDetail
          ? { roomDetail: { ...state.roomDetail, notice: str(data.notice) } }
          : state));
        break;
      }
      case 'room.update': {
        const room = (data.room ?? null) as RoomDetailVO | null;
        if (!room) break;
        set((state) => (state.roomDetail
          ? {
            roomDetail: { ...state.roomDetail, ...room },
            ownerId: room.ownerId ? String(room.ownerId) : state.ownerId,
            isOwner: room.ownerId ? String(room.ownerId) === readSelf().id : state.isOwner,
          }
          : state));
        break;
      }
      case 'room.owner.changed': {
        const newOwnerId = str(data.newOwnerId);
        set((state) => ({
          ownerId: newOwnerId ?? state.ownerId,
          isOwner: newOwnerId === readSelf().id,
          myRole: newOwnerId === readSelf().id ? 2 : state.myRole,
        }));
        break;
      }
      case 'room.closed': {
        set((state) => ({
          roomDetail: state.roomDetail ? { ...state.roomDetail, status: 2 } : null,
          roomExitNotice: str(data.message) ?? '房间已关闭',
        }));
        break;
      }
      case 'room.reopened': {
        set((state) => ({
          roomDetail: state.roomDetail ? { ...state.roomDetail, status: 1 } : null,
          roomExitNotice: null,
        }));
        break;
      }
      case 'room.kicked': {
        set({
          roomExitNotice: `你已被移出房间${str(data.reason) ? `：${str(data.reason)}` : ''}`,
          kickedRemainingSeconds: num(data.remainingSeconds) ?? 0,
        });
        get().leaveRoom();
        break;
      }
      case 'room.system': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (roomId) appendSystem(roomId, str(data.content) ?? '', 3);
        break;
      }
      case 'room.play':
      case 'room.pause':
      case 'room.seek':
      case 'room.switch':
      case 'room.sync':
      case 'ROOM_PLAY_STATE':
        applyPlayState(data);
        break;
      case 'ROOM_QUEUE_UPDATE': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (roomId && roomId !== get().roomId) break;
        const action = str(data.action) ?? '';
        if (action === 'cleared') {
          set({ queue: [] });
          break;
        }
        const item = data.item as QueueItemVO | undefined;
        if (!item) break;
        set((state) => {
          const others = state.queue.filter((q) => q.id !== item.id);
          const next = action === 'removed'
            ? others
            : [...others, item].sort(compareQueue);
          return { queue: next };
        });
        break;
      }
      case 'ROOM_VOTE_START':
      case 'ROOM_VOTE_UPDATE':
      case 'ROOM_VOTE_RESULT': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (roomId && roomId !== get().roomId) break;
        const incoming = toVoteView(data);
        if (!incoming) break;
        const current = get().vote;
        set({
          vote: {
            ...incoming,
            // 服务端更新/结果帧不带 voted，沿用本地已投状态
            voted: incoming.voted || (current?.voteId === incoming.voteId ? current.voted : false),
            message: str(data.message),
          },
        });
        break;
      }
      case 'ROOM_RANK_UPDATE': {
        const roomId = str(data.roomId);
        if (roomId && roomId !== get().roomId) break;
        const type = str(data.type) as RankType | null;
        if (!type || !['gift', 'active', 'request', 'listen'].includes(type)) break;
        const top = Array.isArray(data.top) ? (data.top as RoomRankEntryVO[]) : [];
        set((state) => ({ ranks: { ...state.ranks, [type]: top } }));
        break;
      }
      case 'ROOM_LIKE_UPDATE': {
        const roomId = str(data.roomId);
        if (roomId && roomId !== get().roomId) break;
        set({
          like: {
            songId: str(data.songId),
            count: num(data.likeCount) ?? num(data.count) ?? 0,
            liked: get().like.liked,
          },
        });
        break;
      }
      case 'room.like.ack': {
        set({ like: {
          songId: str(data.songId),
          count: num(data.likeCount) ?? 0,
          liked: bool(data.liked) || get().like.liked,
        } });
        break;
      }
      case 'ROOM_DANMAKU': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId || roomId !== get().roomId) break;
        const messageId = str(data.messageId) ?? message.msgId ?? `dm-${Date.now()}`;
        const userId = str(data.userId) ?? '0';
        rememberNickname(userId, str(data.nickname));
        appendMessage({
          messageId,
          roomId,
          userId,
          nickname: str(data.nickname) ?? undefined,
          content: str(data.content) ?? '',
          messageType: 2,
          createTime: num(data.createTime) ?? Date.now(),
        });
        appendDanmaku({
          messageId,
          userId,
          nickname: str(data.nickname) ?? '有人',
          content: str(data.content) ?? '',
          createTime: num(data.createTime) ?? Date.now(),
        });
        break;
      }
      case 'ROOM_EMOJI': {
        const roomId = str(data.roomId) ?? message.roomId;
        if (!roomId || roomId !== get().roomId) break;
        const userId = str(data.userId) ?? '0';
        const emoji = str(data.emoji) ?? '';
        appendDanmaku({
          messageId: str(data.messageId) ?? `emoji-${Date.now()}`,
          userId,
          nickname: str(data.nickname) ?? '有人',
          content: emoji,
          createTime: num(data.createTime) ?? Date.now(),
          emoji: true,
        });
        break;
      }
      case 'room.mention': {
        const roomId = str(data.roomId);
        if (roomId && roomId !== get().roomId) break;
        const mention: MentionView = {
          roomId: roomId ?? '',
          fromNickname: str(data.fromNickname) ?? '有人',
          content: str(data.content) ?? '',
          createTime: num(data.serverTime) ?? Date.now(),
        };
        set({ mention });
        toastInfo(`${mention.fromNickname} @了你：${mention.content}`);
        break;
      }
      case 'room.emoji.rejected':
      case 'room.danmaku.rejected':
        toastWarning(rejectText(str(data.reason) ?? 'rejected'));
        break;
      case 'room.chat.rejected':
        // 带 clientMsgId 的已在 sendChat 的 onReject 中提示；无关联的直接提示
        if (!message.clientMsgId) toastWarning(rejectText(str(data.reason) ?? 'rejected'));
        break;
      case 'msg.new':
      case 'msg.read':
      case 'msg.revoke':
        socialMessageListeners.forEach((listener) => listener({ type: message.type as SocialMessageEvent['type'], data }));
        break;
      case 'group.removed':
      case 'group.dissolved':
        socialMessageListeners.forEach((listener) => listener({
          type: message.type as SocialMessageEvent['type'],
          data: { conversationId: String(message.data ?? '') },
        }));
        break;
      case 'auth.expiring':
        // 由 WsClient 回调处理（刷新 token 后重连）
        break;
      case 'error': {
        const context = (data.context ?? {}) as { clientMsgId?: string };
        if (!context.clientMsgId) {
          toastError(str(data.message) ?? '实时通信异常');
        }
        break;
      }
      default:
        // notify.system / notify.comment / … 统一按通知展示（§8.2）
        if (message.type.startsWith('notify.')) onNotification(data);
        break;
    }
  };

  /** 断线补偿：从最后一条服务端消息之后拉取（BR-13-037） */
  const compensate = async (roomId: string): Promise<void> => {
    const messages = get().messages;
    const lastServerId = [...messages]
      .reverse()
      .map((m) => m.messageId)
      .find((id) => /^\d+$/.test(id));
    if (!lastServerId) return;
    try {
      // 模块 13 BR-22-082：断线期间最多补偿 100 条
      const list = await wsApi.messages(roomId, lastServerId, 100);
      list.forEach((item) => appendMessage(fromHttpMessage(item, nicknameCache)));
    } catch {
      // 补偿失败不影响实时通道；用户仍能收到后续消息
    }
  };

  const reconnectSubscription = (): void => {
    if (!client) return;
    const roomId = get().roomId;
    if (!roomId) return;
    client.joinRoom(roomId, get().roomPassword ?? undefined, {
      onReject: (reason) => toastError(`重新加入房间失败：${rejectText(reason)}`),
      onError: (_code, message) => toastError(message),
    });
    void compensate(roomId);
  };

  return {
    status: 'idle',
    gaveUp: false,
    deviceId: '',

    roomId: null,
    isOwner: false,
    ownerId: null,
    onlineCount: 0,
    messages: [],
    playState: null,

    roomDetail: null,
    members: [],
    mics: [],
    queue: [],
    micQueue: [],
    myRole: -1,
    myPermissions: [],
    myMuteRemainingSeconds: 0,
    kickedRemainingSeconds: 0,
    roomPassword: null,
    roomExitNotice: null,

    unread: 0,
    notifications: [],

    clockOffsetMs: 0,
    clockRttMs: 0,
    syncDegraded: false,
    weakNetwork: false,
    reconnectAttempt: 0,
    vote: null,
    danmaku: [],
    like: { songId: null, count: 0, liked: false },
    ranks: { gift: [], active: [], request: [], listen: [] },
    mention: null,

    start: () => {
      if (client) {
        client.start();
        return;
      }
      const deviceId = getDeviceId();
      client = new WsClient({
        url: import.meta.env.VITE_WS_URL ?? '',
        deviceId,
        getToken: () => readRaw(StorageKey.ACCESS_TOKEN),
        onStatus: (status) => set({ status, gaveUp: status === 'closed' ? get().gaveUp : false }),
        onMessage: handleMessage,
        onOpen: reconnectSubscription,
        onGiveUp: () => {
          set({ gaveUp: true, status: 'closed' });
          toastWarning('实时连接失败，点击重试');
        },
        onAuthExpiring: () => {
          // PRD §4.3：剩余有效期 < 5 分钟 → 刷新 token 并用新 token 重连
          void refreshAccessToken()
            .then(() => client?.reconnect())
            .catch(() => toastWarning('登录凭证即将过期，请重新登录'));
        },
        onClockSync: (state: ClockSyncState) => {
          set({
            clockOffsetMs: state.offsetMs,
            clockRttMs: state.rttMs,
            syncDegraded: state.degraded,
            weakNetwork: state.degraded ? get().weakNetwork : state.weak,
          });
        },
        onReconnectAttempt: (attempt) => {
          // BR-22-083：连续 3 次重连失败提示「网络不稳定」
          if (attempt === 3) toastWarning('网络不稳定，正在尝试恢复连接…');
          set((state) => ({
            reconnectAttempt: attempt,
            weakNetwork: attempt >= 3 || state.weakNetwork,
          }));
        },
      });
      set({ deviceId });
      client.start();
    },

    stop: () => {
      client?.stop();
      client = null;
      set({
        status: 'idle',
        gaveUp: false,
        roomId: null,
        isOwner: false,
        ownerId: null,
        onlineCount: 0,
        messages: [],
        playState: null,
        roomDetail: null,
        members: [],
        mics: [],
        queue: [],
        micQueue: [],
        myRole: -1,
        myPermissions: [],
        myMuteRemainingSeconds: 0,
        kickedRemainingSeconds: 0,
        roomPassword: null,
        roomExitNotice: null,
        unread: 0,
        notifications: [],
        ...roomUpgradeReset(),
      });
    },

    retry: () => {
      set({ gaveUp: false });
      client?.manualReconnect();
      if (!client) get().start();
    },

    joinRoom: (roomId, password) => {
      if (get().roomId !== roomId) {
        set({
          roomId,
          isOwner: false,
          ownerId: null,
          onlineCount: 0,
          messages: [],
          playState: null,
          roomDetail: null,
          members: [],
          mics: [],
          queue: [],
          micQueue: [],
          myRole: -1,
          myPermissions: [],
          myMuteRemainingSeconds: 0,
          kickedRemainingSeconds: 0,
          roomExitNotice: null,
          ...roomUpgradeReset(),
        });
      }
      set({ roomPassword: password ?? null });
      if (!client || client.currentStatus !== 'open') {
        // 连接建立后 onOpen 会按 roomId 自动加入（页面可能先于 WS 就绪挂载）
        return;
      }
      client.joinRoom(roomId, password, {
        onReject: (reason) => {
          toastError(`加入房间失败：${rejectText(reason)}`);
          set({ roomId: null });
        },
        onError: (_code, message) => toastError(message),
      });
    },

    leaveRoom: () => {
      client?.leaveRoom();
      set({
        roomId: null,
        isOwner: false,
        ownerId: null,
        onlineCount: 0,
        messages: [],
        playState: null,
        roomDetail: null,
        members: [],
        mics: [],
        queue: [],
        micQueue: [],
        myRole: -1,
        myPermissions: [],
        myMuteRemainingSeconds: 0,
        kickedRemainingSeconds: 0,
        roomPassword: null,
        // roomExitNotice 保留：被踢/关房提示要在页面跳转后仍能看到
        ...roomUpgradeReset(),
      });
    },

    applyRoomDetail: (detail) => {
      if (!detail) {
        set({ roomDetail: null });
        return;
      }
      set({
        roomDetail: detail,
        members: detail.members ?? [],
        mics: detail.mics ?? [],
        queue: detail.queue ?? [],
        myRole: detail.myRole ?? -1,
        myPermissions: detail.myPermissions ?? [],
        myMuteRemainingSeconds: detail.myMuteRemainingSeconds ?? 0,
        kickedRemainingSeconds: detail.kickedRemainingSeconds ?? 0,
        ownerId: detail.ownerId ? String(detail.ownerId) : get().ownerId,
        onlineCount: detail.onlineCount ?? get().onlineCount,
      });
    },

    clearRoomExitNotice: () => set({ roomExitNotice: null }),

    sendChat: (content) => {
      const text = content.trim();
      if (!text) return;
      if (!client || client.currentStatus !== 'open') {
        toastWarning('实时连接尚未建立，请稍后重试');
        return;
      }
      const self = readSelf();
      const clientMsgId = client.chat(text, {
        onAck: (data) => {
          const messageId = str(data.messageId);
          if (!messageId) return;
          appendMessage({
            messageId,
            roomId: get().roomId ?? '',
            userId: self.id,
            nickname: self.nickname,
            content: text,
            messageType: 1,
            createTime: num(data.serverTime) ?? Date.now(),
          });
        },
        onReject: (reason, data) => {
          set((state) => ({ messages: state.messages.filter((m) => m.messageId !== clientMsgId) }));
          const remaining = num(data.remainingSeconds);
          toastWarning(remaining ? `你正在禁言中（剩余 ${remaining} 秒）` : rejectText(reason));
        },
        onError: (_code, message) => {
          set((state) => ({ messages: state.messages.filter((m) => m.messageId !== clientMsgId) }));
          toastError(message);
        },
        onGiveUp: () => {
          set((state) => ({ messages: state.messages.filter((m) => m.messageId !== clientMsgId) }));
          toastError('消息发送失败，请检查网络后重试');
        },
      });
      // 乐观渲染：先占位，ack 到达后按 messageId 替换
      appendMessage({
        messageId: clientMsgId,
        roomId: get().roomId ?? '',
        userId: self.id,
        nickname: self.nickname,
        content: text,
        messageType: 1,
        createTime: Date.now(),
        pending: true,
      });
    },

    sendDanmaku: (content) => {
      const text = content.trim();
      if (!text || !client) return;
      // 弹幕不可靠：发送失败就失败，不做乐观渲染（它本来就会广播回自己）
      client.danmaku(text);
    },

    play: (songId, position) => {
      client?.play(songId, position, controlHandlers());
    },
    pause: (position) => {
      client?.pause(position, controlHandlers());
    },
    seek: (position, isPlaying) => {
      client?.seek(position, isPlaying, controlHandlers());
    },
    switchSong: (songId, position) => {
      client?.switchSong(songId, position, controlHandlers());
    },
    requestSync: () => {
      client?.requestSync();
    },

    // ---------------------------------------------------------------- 模块 13

    loadPlayState: async () => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        const vo = await roomApi.playState(roomId);
        applyPlayState(vo as unknown as Record<string, unknown>);
      } catch {
        // 首屏失败不阻塞进房：后续 ROOM_PLAY_STATE / room.sync 仍会带来状态
      }
    },

    sendEmoji: (emoji) => {
      if (!emoji) return;
      // 快捷表情与弹幕同属「不可靠、不回显」的即时互动
      client?.sendUnreliable('room.emoji', { emoji });
    },

    startVote: async () => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        const vo = await roomApi.startVote(roomId);
        const next = toVoteView(vo as unknown as Record<string, unknown>);
        if (next) set({ vote: next });
        toastInfo('已发起跳过投票，等待房间成员表决');
      } catch {
        /* 请求层已提示；失败态由 currentVote 兜底 */
      }
    },

    agreeVote: async () => {
      const { roomId, vote } = get();
      if (!roomId || !vote) return;
      try {
        const vo = await roomApi.agreeVote(roomId, vote.voteId);
        const next = toVoteView(vo as unknown as Record<string, unknown>);
        // 服务端不保证回带 voted（它按在线用户逐个下发），这里本地置位
        set({ vote: next ? { ...next, voted: true } : { ...vote, voted: true } });
      } catch {
        /* 请求层已提示 */
      }
    },

    loadVote: async () => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        const vo = await roomApi.currentVote(roomId);
        const next = vo ? toVoteView(vo as unknown as Record<string, unknown>) : null;
        set((state) => ({
          vote: next
            ? {
              ...next,
              voted: next.voted
                || (state.vote?.voteId === next.voteId && state.vote.voted),
            }
            : null,
        }));
      } catch {
        // 首屏拉取失败不打断进房；后续 ROOM_VOTE_* 帧仍会补上
      }
    },

    likeCurrent: async () => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        const result = await roomApi.like(roomId);
        const songId = result.songId === null || result.songId === undefined
          ? get().playState?.songId ?? null
          : String(result.songId);
        set((state) => ({
          like: {
            songId,
            count: Number(result.likeCount ?? 0),
            liked: Boolean(result.liked) || (state.like.songId === songId && state.like.liked),
          },
        }));
        if (!result.liked) toastInfo('你已经给这首歌点过赞了');
      } catch {
        /* 请求层已提示（未登录/无歌曲会在服务端报错） */
      }
    },

    loadRank: async (type) => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        const list = await roomApi.rank(roomId, type, 10);
        set((state) => ({ ranks: { ...state.ranks, [type]: list } }));
      } catch {
        // 榜单是附属信息，失败保留旧数据，下次刷新再试
      }
    },

    setRankVisible: async (visible) => {
      const roomId = get().roomId;
      if (!roomId) return;
      try {
        await roomApi.setRankHidden(roomId, !visible);
        toastInfo(visible ? '已开启上榜' : '已隐藏上榜');
      } catch {
        /* 请求层已提示 */
      }
    },

    clearMention: () => set({ mention: null }),

    loadNotifications: async () => {
      try {
        const page = await wsApi.notifications(1, MAX_NOTIFICATIONS);
        set({ notifications: page.list.map(toNotificationView) });
      } catch {
        // 请求层已提示；保留已有列表
      }
    },

    markRead: async (id) => {
      try {
        await wsApi.markRead(id);
        set((state) => ({
          notifications: state.notifications.map((n) => (n.id === id ? { ...n, isRead: true } : n)),
          unread: Math.max(0, state.unread - 1),
        }));
      } catch {
        /* 请求层已提示 */
      }
    },

    markAllRead: async () => {
      try {
        await wsApi.markAllRead();
        set((state) => ({
          notifications: state.notifications.map((n) => ({ ...n, isRead: true })),
          unread: 0,
        }));
      } catch {
        /* 请求层已提示 */
      }
    },
  };
});

function readSelf(): { id: string; nickname?: string } {
  const raw = readRaw(StorageKey.USER);
  if (!raw) return { id: '0' };
  try {
    const user = JSON.parse(raw) as { id?: string | number; nickname?: string; username?: string };
    return {
      id: user.id === undefined ? '0' : String(user.id),
      nickname: user.nickname ?? user.username,
    };
  } catch {
    return { id: '0' };
  }
}

function controlHandlers() {
  return {
    onReject: (reason: string) => toastWarning(`操作未执行：${reason}`),
    onError: (_code: number, message: string) => toastError(message),
  };
}
