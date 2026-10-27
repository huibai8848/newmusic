import { api } from '@/api/request';
import type { Id, NotificationItem, NotificationPushReq, PageResult, RoomMessageItem } from '@/types/api';

/**
 * 模块 04 的 HTTP 侧（WS 只负责实时性，这些接口负责离线/补偿）。
 *
 * `GET /ws/room(s)/{id}/messages` 用 `sinceId` 游标而不是时间戳：
 * 消息 ID 是可比较的雪花值，天然去重、不受时钟回拨影响（BR-13-037）。
 */
export const wsApi = {
  notifications: (pageNum = 1, pageSize = 20) =>
    api.get<PageResult<NotificationItem>>('/ws/notifications', { pageNum, pageSize }),

  unread: () => api.get<{ unread: number }>('/ws/notifications/unread'),

  markRead: (id: Id) => api.put<void>(`/ws/notifications/${id}/read`),

  markAllRead: () => api.put<{ changed: number }>('/ws/notifications/read-all'),

  /** 管理端批量推送（运营/管理员） */
  push: (data: NotificationPushReq) => api.post<{ created: number }>('/ws/admin/notifications/push', data),

  /** 断线补偿：拉取 sinceId 之后的消息 */
  messages: (roomId: Id, sinceId?: Id, limit = 50) =>
    api.get<RoomMessageItem[]>(`/ws/rooms/${roomId}/messages`, {
      sinceId: sinceId ?? '0',
      limit,
    }),

  playState: (roomId: Id) => api.get<Record<string, unknown>>(`/ws/rooms/${roomId}/playstate`),
};
