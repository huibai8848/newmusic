import { api } from '@/api/request';
import type { Id, PageResult } from '@/types/api';
import type { CommentItem } from '@/types/social';
import type {
  ConversationItemVO,
  ConversationSearchVO,
  FanRankVO,
  FeedType,
  FeedVO,
  GroupCreateBody,
  GroupMemberVO,
  GroupMessageBody,
  IntimacyVO,
  MessageCategory,
  MessageCenterItem,
  PostCreateBody,
  PostUpdateBody,
  PostVO,
  PrivateMessageVO,
  ProfileAbout,
  ProfilePlaylistItem,
  ProfilePrivacy,
  ProfileStatsVO,
  ProfileUpdateBody,
  ProfileVO,
  RecentListeningItem,
  RecommendUserVO,
  TopicItem,
  UnreadVO,
} from '@/types/socialUpgrade';

/**
 * 模块 16 社交升级接口。
 *
 * 只做「请求 + 拼参数」，不在这里做业务判断；query key 统一收口到
 * {@link socialUpgradeKeys}，跨页面失效时直接 `invalidateQueries({ queryKey: all })`。
 */

function fileForm(file: Blob): FormData {
  const form = new FormData();
  // 带上原始文件名：后端虽已按文件头识别类型，但有扩展名更利于日志与排障。
  // 不能固定传 'file'——那样后端拿不到扩展名，正常图片会被当成非法格式拒掉。
  const name = typeof File !== 'undefined' && file instanceof File && file.name ? file.name : 'image';
  form.append('file', file, name);
  return form;
}

// ---------------------------------------------------------------- 个人主页

export const profileApi = {
  profile: (userId: Id) => api.get<ProfileVO>(`/users/${userId}/profile`),

  updateProfile: (body: ProfileUpdateBody) => api.put<ProfileVO>('/users/me/profile', body),

  uploadAvatar: (file: Blob) => api.post<string>('/users/me/avatar', fileForm(file)),

  uploadCover: (file: Blob) => api.post<string>('/users/me/cover', fileForm(file)),

  stats: (userId: Id, skipErrorToast = false) =>
    api.get<ProfileStatsVO>(`/users/${userId}/stats`, undefined, { skipErrorToast }),

  recentListening: (userId: Id, skipErrorToast = false) =>
    api.get<RecentListeningItem[]>(`/users/${userId}/recent-listening`, undefined, { skipErrorToast }),

  playlists: (userId: Id, page = 1, size = 20, skipErrorToast = false) =>
    api.get<PageResult<ProfilePlaylistItem>>(`/users/${userId}/playlists`, { page, size }, { skipErrorToast }),

  about: (userId: Id, skipErrorToast = false) =>
    api.get<ProfileAbout>(`/users/${userId}/about`, undefined, { skipErrorToast }),

  posts: (userId: Id, page = 1, size = 20) =>
    api.get<PageResult<PostVO>>(`/users/${userId}/posts`, { page, size }),

  privacy: () => api.get<ProfilePrivacy>('/users/me/privacy'),

  updatePrivacy: (body: ProfilePrivacy) => api.put<ProfilePrivacy>('/users/me/privacy', body),

  recommend: (limit = 10) => api.get<RecommendUserVO[]>('/users/recommend', { limit }),

  ignoreRecommend: (userId: Id) => api.post<void>(`/users/recommend/${userId}/ignore`),

  searchUsers: (keyword: string, limit = 10) =>
    api.get<import('@/types/social').UserBrief[]>('/users/search', { keyword, limit }, { skipErrorToast: true }),

  intimacy: (userId: Id) => api.get<IntimacyVO>(`/users/${userId}/intimacy`),

  fansRank: (userId: Id, type: number) =>
    api.get<FanRankVO>(`/users/${userId}/fans-rank`, { type }),
};

// ---------------------------------------------------------------- 动态

export const postApi = {
  create: (body: PostCreateBody) => api.post<PostVO>('/posts', body),

  detail: (id: Id) => api.get<PostVO>(`/posts/${id}`),

  update: (id: Id, body: PostUpdateBody) => api.put<PostVO>(`/posts/${id}`, body),

  remove: (id: Id) => api.delete<void>(`/posts/${id}`),

  like: (id: Id) => api.post<PostVO>(`/posts/${id}/like`),

  unlike: (id: Id) => api.delete<PostVO>(`/posts/${id}/like`),

  collect: (id: Id) => api.post<PostVO>(`/posts/${id}/collect`),

  uncollect: (id: Id) => api.delete<PostVO>(`/posts/${id}/collect`),

  repost: (id: Id, content?: string) => api.post<PostVO>(`/posts/${id}/repost`, { content }),

  dislike: (id: Id) => api.post<void>(`/posts/${id}/dislike`),

  share: (id: Id) => api.get<Record<string, unknown>>(`/posts/${id}/share`),

  comments: (id: Id, sort: 'time' | 'hot' = 'time', page = 1, size = 20) =>
    api.get<PageResult<CommentItem>>(`/posts/${id}/comments`, { sort, page, size }),

  comment: (id: Id, body: { content: string; rootId?: Id; parentId?: Id }) =>
    api.post<CommentItem>(`/posts/${id}/comments`, body),

  uploadImage: (file: Blob) => api.post<string>('/posts/images', fileForm(file)),
};

// ---------------------------------------------------------------- Feed

export const feedApi = {
  /** 关注流 cursor 形如 `{lastScore}_{lastPostId}`，原样回传即可 */
  following: (cursor?: string | null, limit = 20) =>
    api.get<FeedVO>('/feed/following', { cursor: cursor ?? undefined, limit }),

  /** 发现流 cursor 是偏移量 */
  discover: (cursor?: string | null, limit = 20) =>
    api.get<FeedVO>('/feed/discover', { cursor: cursor ?? undefined, limit }),

  markRead: () => api.post<void>('/feed/read'),
};

// ---------------------------------------------------------------- 会话

export const conversationApi = {
  list: (page = 1, size = 20, archived = false) =>
    api.get<PageResult<ConversationItemVO>>('/conversations', { page, size, archived }),

  unreadCount: () => api.get<number>('/conversations/unread-count'),

  search: (keyword: string, limit = 10) =>
    api.get<ConversationSearchVO>('/conversations/search', { keyword, limit }, { skipErrorToast: true }),

  /** cursor 是当前最旧一条消息的 ID（beforeId 语义） */
  messages: (conversationId: Id, cursor?: Id | null, limit = 50) =>
    api.get<PrivateMessageVO[]>(`/conversations/${conversationId}/messages`, {
      cursor: cursor ?? undefined,
      limit,
    }),

  send: (conversationId: Id, body: GroupMessageBody) =>
    api.post<PrivateMessageVO>(`/conversations/${conversationId}/messages`, body),

  create: (targetUserId: Id) =>
    api.post<ConversationItemVO>('/conversations', { targetUserId }),

  createGroup: (body: GroupCreateBody) =>
    api.post<ConversationItemVO>('/conversations/group', body),

  pin: (id: Id, enabled: boolean) =>
    api.put<ConversationItemVO>(`/conversations/${id}/pin`, { enabled }),

  archive: (id: Id, enabled: boolean) =>
    api.put<ConversationItemVO>(`/conversations/${id}/archive`, { enabled }),

  mute: (id: Id, enabled: boolean) =>
    api.put<ConversationItemVO>(`/conversations/${id}/mute`, { enabled }),

  read: (id: Id) => api.put<void>(`/conversations/${id}/read`),

  forward: (conversationId: Id, messageId: Id) =>
    api.post<PrivateMessageVO>(`/conversations/${conversationId}/forward/${messageId}`),

  members: (id: Id) => api.get<GroupMemberVO[]>(`/conversations/${id}/members`),

  addMembers: (id: Id, userIds: Id[]) =>
    api.post<void>(`/conversations/${id}/members`, { userIds }),

  removeMember: (id: Id, userId: Id) =>
    api.delete<void>(`/conversations/${id}/members/${userId}`),

  quit: (id: Id) => api.delete<void>(`/conversations/${id}/members/me`),

  dissolve: (id: Id) => api.delete<void>(`/conversations/${id}`),
};

// ---------------------------------------------------------------- 消息中心

const CATEGORY_PATH: Record<Exclude<MessageCategory, 'message'>, string> = {
  interaction: 'interactions',
  follow: 'followers',
  system: 'system',
  music: 'music',
};

export const messageCenterApi = {
  /** 五分类之一；私信分类后端没有列表接口，走模块 10 `/notifications?tab=message` */
  list: (category: MessageCategory, page = 1, size = 20) => {
    if (category === 'message') {
      return api.get<PageResult<MessageCenterItem>>('/notifications', { tab: 'message', page, size });
    }
    return api.get<PageResult<MessageCenterItem>>(`/messages/${CATEGORY_PATH[category]}`, { page, size });
  },

  unread: () => api.get<UnreadVO>('/messages/unread'),

  read: (ids: Id[]) => api.post<number>('/messages/read', { ids }),

  /** 不传 category 表示全部；**不能传 `all`**（后端会按 10001 拒绝） */
  readAll: (category?: MessageCategory) =>
    api.post<number>('/messages/read-all', undefined, {
      params: category ? { category } : undefined,
    }),
};

// ---------------------------------------------------------------- 话题

export const topicApi = {
  hot: (limit = 20) => api.get<TopicItem[]>('/topics/hot', { limit }),

  search: (keyword: string, limit = 10) =>
    api.get<TopicItem[]>('/topics/search', { keyword, limit }, { skipErrorToast: true }),

  posts: (name: string, page = 1, size = 20) =>
    api.get<PageResult<PostVO>>(`/topics/${encodeURIComponent(name)}/posts`, { page, size }),
};

// ---------------------------------------------------------------- Query keys

export const socialUpgradeKeys = {
  all: ['social-upgrade'] as const,

  profile: (userId: Id) => ['social-upgrade', 'profile', userId] as const,
  profileStats: (userId: Id) => ['social-upgrade', 'profile-stats', userId] as const,
  recentListening: (userId: Id) => ['social-upgrade', 'recent-listening', userId] as const,
  profilePlaylists: (userId: Id) => ['social-upgrade', 'profile-playlists', userId] as const,
  about: (userId: Id) => ['social-upgrade', 'about', userId] as const,
  userPosts: (userId: Id) => ['social-upgrade', 'user-posts', userId] as const,
  intimacy: (userId: Id) => ['social-upgrade', 'intimacy', userId] as const,
  fansRank: (userId: Id, type: number) => ['social-upgrade', 'fans-rank', userId, type] as const,
  privacy: ['social-upgrade', 'privacy'] as const,
  recommend: ['social-upgrade', 'recommend'] as const,
  userSearch: (keyword: string) => ['social-upgrade', 'user-search', keyword] as const,

  post: (id: Id) => ['social-upgrade', 'post', id] as const,
  postComments: (id: Id, sort: string) => ['social-upgrade', 'post-comments', id, sort] as const,

  feed: (type: FeedType) => ['social-upgrade', 'feed', type] as const,

  conversations: (archived: boolean) => ['social-upgrade', 'conversations', archived] as const,
  conversationUnread: ['social-upgrade', 'conversation-unread'] as const,
  conversationSearch: (keyword: string) =>
    ['social-upgrade', 'conversation-search', keyword] as const,
  conversationMessages: (id: Id) => ['social-upgrade', 'conversation-messages', id] as const,
  groupMembers: (id: Id) => ['social-upgrade', 'group-members', id] as const,

  messageCenter: (category: MessageCategory) =>
    ['social-upgrade', 'message-center', category] as const,
  messageUnread: ['social-upgrade', 'message-unread'] as const,

  topicsHot: ['social-upgrade', 'topics-hot'] as const,
  topicSearch: (keyword: string) => ['social-upgrade', 'topic-search', keyword] as const,
  topicPosts: (name: string) => ['social-upgrade', 'topic-posts', name] as const,
};
