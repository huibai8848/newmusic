import type { Id } from '@/types/api';

/**
 * 模块 10 社交基础的前端契约。
 *
 * 后端社交接口的 `targetType` 是**大写字符串**（SONG/PLAYLIST/ALBUM/…），
 * 与通知表里的数字 `targetType` 不是同一套编码，混用会得到 50020。
 * 列表分页参数也沿用后端命名 `page/size`（不是模块 01 的 pageNum/pageSize）。
 */

export type SocialTargetType =
  | 'SONG'
  | 'PLAYLIST'
  | 'ALBUM'
  | 'COMMENT'
  | 'ROOM'
  | 'ROOM_MESSAGE'
  | 'USER'
  | 'MESSAGE'
  | 'LYRIC';

/** 可点赞的类型（后端 LIKE_TYPES） */
export type LikeTargetType = 'SONG' | 'PLAYLIST' | 'ALBUM' | 'COMMENT' | 'ROOM_MESSAGE';
/** 可收藏的类型（后端 COLLECT_TYPES） */
export type CollectTargetType = 'SONG' | 'PLAYLIST' | 'ALBUM' | 'ROOM';
/** 可评论的类型（后端 COMMENT_TYPES，模块 16 起包含 POST） */
export type CommentTargetType = 'SONG' | 'PLAYLIST' | 'ALBUM' | 'ROOM' | 'COMMENT' | 'POST';
/** 可分享的类型（后端 SHARE_TYPES） */
export type ShareTargetType = 'SONG' | 'PLAYLIST' | 'ALBUM';
/** 举报目标（后端 REPORT_TYPES，没有 ALBUM；模块 16 起包含 POST） */
export type ReportTargetType =
  | 'SONG'
  | 'COMMENT'
  | 'USER'
  | 'ROOM'
  | 'MESSAGE'
  | 'PLAYLIST'
  | 'LYRIC'
  | 'POST';

export interface LikeStatus {
  targetType: string;
  targetId: Id;
  liked: boolean;
  count: number;
}

export interface LikeBatchCheck {
  targetType: string;
  liked: Record<string, boolean>;
  counts: Record<string, number>;
}

export interface CollectFolder {
  /** 默认收藏夹为 null */
  id: Id | null;
  name: string;
  isDefault: boolean;
  itemCount: number;
  sortOrder: number;
  createTime?: string | null;
}

export interface CollectItem {
  /** 未收藏时为 null */
  id: Id | null;
  targetType: string;
  targetId: Id;
  name: string;
  cover: string | null;
  subtitle: string | null;
  folderId: Id | null;
  /** 收藏状态接口返回；列表接口恒为 true */
  collected: boolean;
  offline: boolean;
  createTime?: string | null;
}

export interface CommentItem {
  id: Id;
  targetType: string;
  targetId: Id;
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  rootId: Id | null;
  parentId: Id | null;
  replyUserId: Id | null;
  replyNickname: string | null;
  content: string;
  likeCount: number;
  replyCount: number;
  liked: boolean;
  /** 0待审核 1已发布 2已删除 */
  status: number;
  mine: boolean;
  createTime: string;
  replies?: CommentItem[] | null;
}

export interface UserBrief {
  id: Id;
  nickname: string | null;
  avatar: string | null;
  signature: string | null;
  followed: boolean;
  mutual: boolean;
}

export interface FollowStatus {
  userId: Id;
  followed: boolean;
  mutual: boolean;
  followingCount: number;
  followerCount: number;
}

export interface ArtistFollowStatus {
  artistId: Id;
  followed: boolean;
  followerCount: number;
}

export interface Conversation {
  id: Id;
  otherUserId: Id;
  nickname: string | null;
  avatar: string | null;
  lastMessagePreview: string | null;
  lastMessageTime: string | null;
  unread: number;
}

export interface PrivateMessage {
  id: Id;
  conversationId: Id;
  senderId: Id;
  receiverId: Id;
  /** TEXT / IMAGE / SONG / PLAYLIST / ROOM */
  msgType: string;
  content: string;
  isRead: boolean;
  isRevoked: boolean;
  mine: boolean;
  createTime: string;
}

export interface BlacklistUser {
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  createTime: string;
}

export interface NotificationSetting {
  userId?: Id;
  likeEnabled: number;
  commentEnabled: number;
  followEnabled: number;
  messageEnabled: number;
  systemEnabled: number;
  newSongEnabled: number;
  readReceiptEnabled: number;
}

export interface PrivacySetting {
  userId?: Id;
  /** 0所有人 1互关 2关闭 */
  messagePolicy: number;
  /** 0所有人 1关注我的人 2关闭 */
  commentPolicy: number;
  mentionPolicy: number;
  showFollowing: number;
  showFollowers: number;
  /** 0所有人 1互关 2仅自己 */
  playHistoryVisibility: number;
  readReceipt: number;
  newSongNotify: number;
  systemNotify: number;
}

export interface ReportReason {
  code: string;
  label: string;
  category: string;
  priority: number;
}

export interface ReportItem {
  id: Id;
  targetType: string;
  targetId: Id;
  targetName: string | null;
  reasonType: string;
  reason: string | null;
  /** 0待处理 1有效 2无效 3需补充 4已合并 */
  status: number;
  handleResult: string | null;
  handleReason: string | null;
  actionTaken: string | null;
  createTime: string;
  handledAt: string | null;
  mergedIntoId: Id | null;
}

export interface ShareResult {
  code: string;
  shortUrl: string;
  targetUrl: string;
  expireTime: string;
}

/** 通知中心 tab（后端白名单，其它值按 all 处理） */
export type NotificationTab = 'all' | 'interaction' | 'follow' | 'message' | 'system';

export const NOTIFICATION_TYPE_LABEL: Record<number, string> = {
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
  15: '转发',
  16: '动态收藏',
};

export const REPORT_STATUS_LABEL: Record<number, string> = {
  0: '待处理',
  1: '有效',
  2: '无效',
  3: '需补充',
  4: '已合并',
};

export const REPORT_STATUS_COLOR: Record<number, string> = {
  0: 'processing',
  1: 'success',
  2: 'default',
  3: 'warning',
  4: 'default',
};
