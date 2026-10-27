import { api } from '@/api/request';
import type { Id, PageResult, Song } from '@/types/api';
import type {
  ArtistFollowStatus,
  BlacklistUser,
  CollectFolder,
  CollectItem,
  CommentItem,
  Conversation,
  FollowStatus,
  LikeBatchCheck,
  LikeStatus,
  NotificationSetting,
  NotificationTab,
  PrivacySetting,
  PrivateMessage,
  ReportItem,
  ReportReason,
  ReportTargetType,
  ShareResult,
  ShareTargetType,
  UserBrief,
} from '@/types/social';
import type { NotificationItem } from '@/types/api';

/**
 * 模块 10 社交接口封装（后端前缀 `/api/v1`）。
 *
 * 注意分页参数：社交域统一 `page/size`，不要套用模块 01 的 `pageNum/pageSize`；
 * 传错时后端会用默认值，表现为「翻页永远第一页」。
 */

export interface CommentCreateBody {
  targetType: string;
  targetId: Id;
  content: string;
  rootId?: Id;
  parentId?: Id;
}

export interface ReportCreateBody {
  targetType: ReportTargetType;
  targetId: Id;
  reasonType: string;
  reason?: string;
  evidenceUrls?: string[];
}

export interface CollectQuery {
  targetType?: string;
  folderId?: Id;
  page?: number;
  size?: number;
}

export const socialApi = {
  // ---------------------------------------------------------------- 点赞
  likeStatus: (targetType: string, targetId: Id) =>
    api.get<LikeStatus>('/likes', { targetType, targetId }),

  like: (targetType: string, targetId: Id) =>
    api.post<LikeStatus>('/likes', { targetType, targetId, like: true }),

  unlike: (targetType: string, targetId: Id) =>
    api.delete<LikeStatus>('/likes', { data: { targetType, targetId, like: false } }),

  likeBatchCheck: (targetType: string, targetIds: Id[]) =>
    api.post<LikeBatchCheck>('/likes/batch-check', { targetType, targetIds }),

  likedSongs: (page = 1, size = 20) =>
    api.get<PageResult<Song>>('/likes/songs', { page, size }),

  // ---------------------------------------------------------------- 收藏
  /** 查询收藏状态；后端返回 id=null 表示未收藏。需要登录。 */
  collectStatus: (targetType: string, targetId: Id) =>
    api.get<CollectItem>('/collects/status', { targetType, targetId }),

  collect: (targetType: string, targetId: Id, folderId?: Id | null) =>
    api.post<CollectItem>('/collects', { targetType, targetId, folderId, collect: true }),

  uncollect: (targetType: string, targetId: Id) =>
    api.delete<CollectItem>('/collects', { data: { targetType, targetId, collect: false } }),

  collects: (query: CollectQuery = {}) =>
    api.get<PageResult<CollectItem>>('/collects', {
      targetType: query.targetType,
      folderId: query.folderId,
      page: query.page ?? 1,
      size: query.size ?? 20,
    }),

  collectFolders: () => api.get<CollectFolder[]>('/collect-folders'),

  createFolder: (name: string) => api.post<CollectFolder>('/collect-folders', { name }),

  renameFolder: (id: Id, name: string) =>
    api.put<CollectFolder>(`/collect-folders/${id}`, { name }),

  deleteFolder: (id: Id) => api.delete<void>(`/collect-folders/${id}`),

  moveCollects: (collectIds: Id[], folderId: Id | null) =>
    api.put<number>('/collects/move', { collectIds, folderId }),

  // ---------------------------------------------------------------- 评论
  comments: (targetType: string, targetId: Id, sort: 'time' | 'hot', page = 1, size = 10) =>
    api.get<PageResult<CommentItem>>('/comments', { targetType, targetId, sort, page, size }),

  replies: (commentId: Id, page = 1, size = 20) =>
    api.get<PageResult<CommentItem>>(`/comments/${commentId}/replies`, { page, size }),

  createComment: (body: CommentCreateBody) => api.post<CommentItem>('/comments', body),

  deleteComment: (commentId: Id) => api.delete<void>(`/comments/${commentId}`),

  // ---------------------------------------------------------------- 关注
  followStatus: (userId: Id) => api.get<FollowStatus>('/follows/status', { userId }),

  followUser: (targetUserId: Id) => api.post<FollowStatus>('/follows', { targetUserId }),

  unfollowUser: (userId: Id) => api.delete<FollowStatus>(`/follows/${userId}`),

  following: (userId?: Id, page = 1, size = 20) =>
    api.get<PageResult<UserBrief>>('/follows/following', { userId, page, size }),

  followers: (userId?: Id, page = 1, size = 20) =>
    api.get<PageResult<UserBrief>>('/follows/followers', { userId, page, size }),

  artistFollowStatus: (artistId: Id) =>
    api.get<ArtistFollowStatus>(`/artist-follows/${artistId}`),

  followArtist: (artistId: Id) =>
    api.post<ArtistFollowStatus>(`/artist-follows/${artistId}`),

  unfollowArtist: (artistId: Id) =>
    api.delete<ArtistFollowStatus>(`/artist-follows/${artistId}`),

  // ---------------------------------------------------------------- 黑名单
  blacklist: (page = 1, size = 20) =>
    api.get<PageResult<BlacklistUser>>('/blacklist', { page, size }),

  addBlacklist: (targetUserId: Id) => api.post<void>('/blacklist', { targetUserId }),

  removeBlacklist: (userId: Id) => api.delete<void>(`/blacklist/${userId}`),

  // ---------------------------------------------------------------- 通知中心
  notifications: (tab: NotificationTab = 'all', page = 1, size = 20) =>
    api.get<PageResult<NotificationItem>>('/notifications', { tab, page, size }),

  notificationUnread: () => api.get<number>('/notifications/unread-count'),

  markNotificationsRead: (ids: Id[]) => api.put<number>('/notifications/read', { ids }),

  markAllNotificationsRead: () => api.put<number>('/notifications/read-all'),

  deleteNotification: (id: Id) => api.delete<boolean>(`/notifications/${id}`),

  clearNotifications: () => api.delete<number>('/notifications'),

  notificationSettings: () => api.get<NotificationSetting>('/notifications/settings'),

  updateNotificationSettings: (settings: NotificationSetting) =>
    api.put<NotificationSetting>('/notifications/settings', settings),

  // ---------------------------------------------------------------- 隐私设置
  privacySettings: () => api.get<PrivacySetting>('/privacy-settings'),

  updatePrivacySettings: (settings: PrivacySetting) =>
    api.put<PrivacySetting>('/privacy-settings', settings),

  // ---------------------------------------------------------------- 举报
  reportReasons: () => api.get<ReportReason[]>('/reports/reasons'),

  createReport: (body: ReportCreateBody) => api.post<ReportItem>('/reports', body),

  myReports: (page = 1, size = 20) =>
    api.get<PageResult<ReportItem>>('/reports/my', { page, size }),

  // ---------------------------------------------------------------- 分享
  createShare: (targetType: ShareTargetType, targetId: Id) =>
    api.post<ShareResult>('/share', { targetType, targetId, channel: 'COPY_LINK' }),

  // ---------------------------------------------------------------- 私信
  conversations: (page = 1, size = 20) =>
    api.get<PageResult<Conversation>>('/messages/conversations', { page, size }),

  messages: (conversationId: Id, beforeId?: Id, limit = 50) =>
    api.get<PrivateMessage[]>('/messages', { conversationId, beforeId, limit }),

  sendMessage: (targetUserId: Id, content: string, msgType = 'TEXT') =>
    api.post<PrivateMessage>('/messages', { targetUserId, msgType, content }),

  readConversation: (conversationId: Id) =>
    api.put<number>('/messages/read', { conversationId }),

  revokeMessage: (messageId: Id) => api.post<void>(`/messages/${messageId}/revoke`),

  deleteMessage: (messageId: Id) => api.delete<void>(`/messages/${messageId}`),

  messageUnread: () => api.get<number>('/messages/unread-count'),
};

/**
 * React Query key 统一收口：所有会改变社交数据的 mutation 都失效 `socialKeys.all`，
 * 避免出现「点了赞但列表里的红心没变」这种各页面各写一套 key 的经典问题。
 */
export const socialKeys = {
  all: ['social'] as const,
  like: (targetType: string, targetId: Id) => ['social', 'like', targetType, targetId] as const,
  collect: (targetType: string, targetId: Id) =>
    ['social', 'collect', targetType, targetId] as const,
  collectFolders: ['social', 'collect-folders'] as const,
  collects: (query: CollectQuery = {}) =>
    ['social', 'collects', query.targetType ?? 'ALL', query.folderId ?? 'DEFAULT', query.page ?? 1] as const,
  comments: (targetType: string, targetId: Id, sort: string) =>
    ['social', 'comments', targetType, targetId, sort] as const,
  replies: (commentId: Id) => ['social', 'replies', commentId] as const,
  follow: (userId: Id) => ['social', 'follow', userId] as const,
  following: (userId?: Id, page = 1) => ['social', 'following', userId ?? 'ME', page] as const,
  followers: (userId?: Id, page = 1) => ['social', 'followers', userId ?? 'ME', page] as const,
  artistFollow: (artistId: Id) => ['social', 'artist-follow', artistId] as const,
  blacklist: (page = 1) => ['social', 'blacklist', page] as const,
  notifications: (tab: NotificationTab) => ['social', 'notifications', tab] as const,
  notificationUnread: ['social', 'notifications', 'unread'] as const,
  notificationSettings: ['social', 'notification-settings'] as const,
  privacySettings: ['social', 'privacy-settings'] as const,
  reportReasons: ['social', 'report-reasons'] as const,
  myReports: (page = 1) => ['social', 'my-reports', page] as const,
  conversations: ['social', 'conversations'] as const,
  messages: (conversationId: Id) => ['social', 'messages', conversationId] as const,
  messageUnread: ['social', 'messages', 'unread'] as const,
};
