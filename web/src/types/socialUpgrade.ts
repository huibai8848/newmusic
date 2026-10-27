import type { Id, NotificationItem } from '@/types/api';
import type { UserBrief } from '@/types/social';

/**
 * 模块 16 社交升级的前端契约。
 *
 * 与模块 10 的差别：
 * - 动态、会话、群聊都有自己的 VO，不能拿模块 10 的 `Conversation/PrivateMessage` 凑；
 * - 所有 ID 仍然是字符串（雪花 ID > 2^53），**禁止 `Number(id)`**；
 * - 分页：feed/会话消息用 `cursor`，列表用 `page/size`（不是 pageNum/pageSize）。
 */

// ---------------------------------------------------------------- 动态

/** 1纯文本 2歌曲 3歌单 4歌词 5打卡 6新建歌单 7转发 */
export type PostType = 1 | 2 | 3 | 4 | 5 | 6 | 7;

/** 0公开 1仅关注者 2仅自己 */
export type PostVisibility = 0 | 1 | 2;

export interface PostAuthor extends UserBrief {
  /** 后端 UserBriefVO 的 id 字段就是 userId 的来源 */
}

export interface PostSongCard {
  id: Id;
  name: string;
  cover: string | null;
  artistName: string;
  available: boolean;
}

export interface PostPlaylistCard {
  id: Id;
  name: string;
  cover: string | null;
  available: boolean;
}

export interface MentionedUser {
  userId: Id;
  nickname: string | null;
  /** UTF-16 偏移，与 JS `slice` 一致 */
  offset: number;
  length: number;
  clickable: boolean;
}

/** 发布器本地的 @ 候选（后端不存储；提交前用 `buildMentionRefs` 重算偏移） */
export interface MentionCandidate {
  userId: Id;
  nickname: string;
}

export interface PostVO {
  id: Id;
  userId: Id;
  author: PostAuthor | null;
  type: PostType;
  content: string | null;
  images: string[] | null;
  song: PostSongCard | null;
  playlist: PostPlaylistCard | null;
  lyricSnippet: string | null;
  topics: string[] | null;
  mentionedUsers: MentionedUser[] | null;
  repostPostId: Id | null;
  rootPostId: Id | null;
  /** 转发时内嵌的原动态，只渲染一层；原动态已删时 deleted=true */
  original: PostVO | null;
  likeCount: number;
  commentCount: number;
  repostCount: number;
  collectCount: number;
  viewCount: number;
  visibility: PostVisibility;
  /** 0待审核 1已发布 2已删除/驳回 */
  status: number;
  edited: boolean;
  ipRegion: string | null;
  createTime: string;
  liked: boolean;
  collected: boolean;
  authorSelf: boolean;
  deleted: boolean;
}

export interface MentionRefBody {
  userId: Id;
  offset: number;
  length: number;
}

export interface PostCreateBody {
  type: PostType;
  content?: string;
  images?: string[];
  songId?: Id;
  playlistId?: Id;
  lyricSnippet?: string;
  topics?: string[];
  mentions?: MentionRefBody[];
  visibility?: PostVisibility;
}

/** 编辑只允许正文/图片/话题/@/可见范围，类型与关联内容不可改 */
export interface PostUpdateBody {
  content?: string;
  images?: string[];
  topics?: string[];
  mentions?: MentionRefBody[];
  visibility?: PostVisibility;
}

export interface FeedVO {
  list: PostVO[];
  /** 关注流 `{lastScore}_{lastPostId}`；发现流是偏移量。空表示没有下一页 */
  nextCursor: string | null;
  hasMore: boolean;
  /** 关注流新增动态数；发现流不返回 */
  newCount?: number | null;
}

export type FeedType = 'following' | 'discover';

// ---------------------------------------------------------------- 个人主页

export interface ProfileVO {
  userId: Id;
  userCode: string | null;
  nickname: string | null;
  avatar: string | null;
  coverUrl: string | null;
  bio: string | null;
  /** 0未知 1男 2女 */
  gender: number | null;
  birthdayText: string | null;
  region: string | null;
  tags: string[] | null;
  certType: number | null;
  certInfo: string | null;
  ipRegion: string | null;
  joinTime: string | null;
  followCount: number;
  fansCount: number;
  postCount: number;
  likeReceivedCount: number;
  followed: boolean;
  mutual: boolean;
  self: boolean;
  blocked: boolean;
  banned: boolean;
  /** 访客是否有权查看动态/歌单等主体内容 */
  contentVisible: boolean;
  visibilityHint: string | null;
  privacy: ProfilePrivacy | null;
}

/** 主页隐私：统计项 0 显示 / 1 隐藏；recentListening、fansRank 1 显示 / 0 隐藏 */
export interface ProfilePrivacy {
  playDuration?: number;
  weekCount?: number;
  topArtist?: number;
  genre?: number;
  listenDays?: number;
  recentListening?: number;
  fansRank?: number;
  /** 0公开 1仅关注者 2仅自己 */
  profile?: number;
  /** 1 隐藏 / 0 显示 */
  intimacyScore?: number;
  [key: string]: number | undefined;
}

export interface ProfileStatsArtist {
  artistId: Id;
  artistName: string;
  playCount: number;
}

export interface ProfileStatsGenre {
  genre: string;
  playCount: number;
}

export interface ProfileStatsVO {
  totalPlaySeconds: number;
  weekSongCount: number;
  listenDays: number;
  topArtists: ProfileStatsArtist[] | null;
  genres: ProfileStatsGenre[] | null;
  playDurationVisible: boolean;
  weekCountVisible: boolean;
  topArtistVisible: boolean;
  genreVisible: boolean;
  listenDaysVisible: boolean;
}

export interface RecentListeningItem {
  songId: Id;
  name: string;
  artistName: string;
  playTime: string;
}

export interface ProfilePlaylistItem {
  id: Id;
  name: string;
  cover?: string | null;
  songCount?: number;
  playCount?: number;
  description?: string | null;
  [key: string]: unknown;
}

/** 后端返回的「关于我」是 Map，字段随版本变化，页面按需读取 */
export type ProfileAbout = Record<string, unknown>;

export interface ProfileUpdateBody {
  nickname?: string;
  bio?: string;
  gender?: number;
  /** yyyy-MM-dd */
  birthday?: string;
  region?: string;
  tags?: string[];
  userCode?: string;
  profileVisibility?: PostVisibility;
}

export interface IntimacyVO {
  userId: Id;
  targetUserId: Id;
  /** 对方关闭展示时为 null */
  score: number | null;
  level: number;
  levelName: string;
  scoreVisible: boolean;
}

export interface FanRankItem {
  rank: number;
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  score: number;
  badge: boolean;
}

export interface FanRankVO {
  userId: Id;
  /** 1 铁粉榜 2 互动榜 3 礼物榜 */
  type: number;
  visible: boolean;
  statDate: string | null;
  list: FanRankItem[];
}

export interface RecommendUserVO {
  user: UserBrief;
  followBack: boolean;
  reason: string | null;
  /** mutual / second_degree / region / hot */
  source: string;
  mutualCount: number;
}

// ---------------------------------------------------------------- 话题

export interface TopicItem {
  id: Id;
  name: string;
  postCount: number;
  viewCount: number;
  hotScore: number;
  isOfficial: number;
  status: number;
  createTime: string;
  updateTime: string;
}

// ---------------------------------------------------------------- 会话与消息

/** 1 单聊 2 群聊 */
export type ConversationType = 1 | 2;

export interface ConversationItemVO {
  id: Id;
  type: ConversationType;
  name: string | null;
  avatar: string | null;
  /** 单聊的对方 userId；群聊为空 */
  otherUserId: Id | null;
  lastMessagePreview: string | null;
  lastMessageTime: string | null;
  unread: number;
  pinned: boolean;
  archived: boolean;
  muted: boolean;
  memberCount: number | null;
  ownerId: Id | null;
}

export interface ConversationSearchHit {
  messageId: Id;
  conversationId: Id;
  conversationName: string | null;
  senderId: Id;
  senderNickname: string | null;
  content: string;
  createTime: string;
}

export interface ConversationSearchVO {
  conversations: ConversationItemVO[];
  messages: ConversationSearchHit[];
}

export interface MessageQuote {
  messageId: Id;
  senderId: Id;
  senderNickname: string | null;
  content: string;
  revoked: boolean;
}

/** TEXT / IMAGE / SONG / PLAYLIST / ROOM / SYSTEM */
export interface PrivateMessageVO {
  id: Id;
  conversationId: Id;
  senderId: Id;
  receiverId: Id;
  msgType: string;
  content: string;
  senderNickname: string | null;
  senderAvatar: string | null;
  forwarded: boolean;
  quote: MessageQuote | null;
  isRead: boolean;
  /** 后端字段名就是 isRevoked，序列化后保持不变 */
  isRevoked: boolean;
  mine: boolean;
  createTime: string;
}

export interface GroupMessageBody {
  msgType?: string;
  content: string;
  quoteMsgId?: Id;
}

export interface GroupCreateBody {
  name?: string;
  /** 除创建者外的成员，至少 2 人（总数 3~20） */
  memberIds: Id[];
}

export interface GroupMemberVO {
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  /** 1 群主 2 管理员 3 成员 */
  role: number;
  owner: boolean;
}

/** 消息中心五分类 */
export type MessageCategory = 'interaction' | 'follow' | 'message' | 'system' | 'music';

export interface UnreadVO {
  total: number;
  categories: Partial<Record<MessageCategory, number>>;
  overflow: boolean;
}

/** 消息中心列表项与通知共用一份结构 */
export type MessageCenterItem = NotificationItem;

// ---------------------------------------------------------------- 组合别名

/** 分页结果（避免每个文件重复 import） */
export type { PageResult } from '@/types/api';
