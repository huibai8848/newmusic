import { api } from '@/api/request';
import type {
  Id,
  KickReq,
  MicVO,
  MuteReq,
  PageResult,
  PlayControlReq,
  QueueItemVO,
  RankType,
  RoomCreateReq,
  RoomDetailVO,
  RoomMemberVO,
  RoomMessageItem,
  RoomPlayStateVO,
  RoomQuery,
  RoomRankEntryVO,
  RoomUpdateReq,
  RoomVoteVO,
  RoomVO,
} from '@/types/api';

/**
 * 音乐房 HTTP 接口（模块 05，模块 13 扩展播放态/投票/榜单/点赞/收听）。
 *
 * 实时状态走 WS；这里的接口负责「页面首屏 / 断线补偿 / 稳定的写操作」。
 * 写操作成功后服务端会通过 WS 广播新状态，因此不在这里手动改缓存。
 */
export const roomKeys = {
  all: ['room'] as const,
  list: (query: RoomQuery) => [...roomKeys.all, 'list', query] as const,
  detail: (roomNo: string) => [...roomKeys.all, 'detail', roomNo] as const,
  members: (roomId: Id) => [...roomKeys.all, 'members', roomId] as const,
  mics: (roomId: Id) => [...roomKeys.all, 'mics', roomId] as const,
  queue: (roomId: Id) => [...roomKeys.all, 'queue', roomId] as const,
};

export const roomApi = {
  list: (query: RoomQuery) => api.get<PageResult<RoomVO>>('/room/rooms', query),

  detail: (roomNo: string) => api.get<RoomDetailVO>(`/room/rooms/${roomNo}`),

  create: (data: RoomCreateReq) => api.post<RoomDetailVO>('/room/rooms', data),

  update: (id: Id, data: RoomUpdateReq) => api.put<RoomDetailVO>(`/room/rooms/${id}`, data),

  notice: (id: Id, notice: string) => api.put<RoomDetailVO>(`/room/rooms/${id}/notice`, { notice }),

  /** HTTP 加入：只写成员关系，在线状态由 WS 建立 */
  join: (id: Id, password?: string) =>
    api.post<RoomDetailVO>(`/room/rooms/${id}/join`, password ? { password } : {}),

  leave: (id: Id) => api.post<void>(`/room/rooms/${id}/leave`),

  members: (id: Id) => api.get<RoomMemberVO[]>(`/room/rooms/${id}/members`),

  setAdmin: (id: Id, userId: Id, admin: boolean) =>
    admin
      ? api.post<void>(`/room/rooms/${id}/admins`, { userId })
      : api.delete<void>(`/room/rooms/${id}/admins/${userId}`),

  mute: (id: Id, data: MuteReq) => api.post<void>(`/room/rooms/${id}/mute`, data),

  unmute: (id: Id, userId: Id) => api.delete<void>(`/room/rooms/${id}/mute/${userId}`),

  kick: (id: Id, data: KickReq) => api.post<void>(`/room/rooms/${id}/kick`, data),

  blacklist: (id: Id, userId: Id) => api.post<void>(`/room/rooms/${id}/blacklist`, { userId }),

  unblacklist: (id: Id, userId: Id) =>
    api.delete<void>(`/room/rooms/${id}/blacklist/${userId}`),

  applyMic: (id: Id) => api.post<Record<string, unknown>>(`/room/rooms/${id}/mic/apply`),

  leaveMic: (id: Id) => api.post<void>(`/room/rooms/${id}/mic/leave`),

  approveMic: (id: Id, userId: Id, approve: boolean) =>
    api.post<void>(`/room/rooms/${id}/mic/approve`, { userId, approve }),

  mics: (id: Id) => api.get<MicVO[]>(`/room/rooms/${id}/mics`),

  lockMic: (id: Id, micPos: number, locked: boolean) =>
    api.post<void>(`/room/rooms/${id}/mic/lock`, { micPos, locked }),

  addSong: (id: Id, songId: Id) => api.post<QueueItemVO>(`/room/rooms/${id}/queue`, { songId }),

  queue: (id: Id) => api.get<QueueItemVO[]>(`/room/rooms/${id}/queue`),

  approveSong: (id: Id, queueId: Id, approve: boolean) =>
    api.post<void>(`/room/rooms/${id}/queue/${queueId}/approve`, undefined, {
      params: { approve },
    }),

  removeSong: (id: Id, queueId: Id) => api.delete<void>(`/room/rooms/${id}/queue/${queueId}`),

  clearQueue: (id: Id) => api.delete<{ removed: number }>(`/room/rooms/${id}/queue`),

  close: (id: Id) => api.post<void>(`/room/rooms/${id}/close`),

  reopen: (id: Id) => api.post<RoomDetailVO>(`/room/rooms/${id}/reopen`),

  dissolve: (id: Id, confirmName: string) =>
    api.delete<void>(`/room/rooms/${id}`, { confirmName }),

  transfer: (id: Id, userId: Id) => api.post<void>(`/room/rooms/${id}/transfer`, { userId }),

  messages: (id: Id, sinceId: Id = '0', limit = 50) =>
    api.get<RoomMessageItem[]>(`/room/rooms/${id}/messages`, { sinceId, limit }),

  // ------------------------------------------------------------------ 模块 13

  /** 权威播放态（FR-22-001）；客户端按 stateUpdateTimeMs 推算应到位置 */
  playState: (id: Id) => api.get<RoomPlayStateVO>(`/room/rooms/${id}/play-state`),

  /**
   * HTTP 控播，与 WS `room.play` 等消息共用同一状态机。
   * 注意 `positionMs` 是毫秒；模块 04 的 WS 控播用秒，别混用。
   */
  controlPlay: (id: Id, req: PlayControlReq) =>
    api.post<RoomPlayStateVO>(`/room/rooms/${id}/play`, req),

  /** 房主置顶/取消置顶队列项（BR-22-024） */
  pinQueue: (id: Id, queueId: Id, pinned: boolean) =>
    api.post<QueueItemVO>(`/room/rooms/${id}/queue/${queueId}/priority`, { pinned }),

  startVote: (id: Id) => api.post<RoomVoteVO>(`/room/rooms/${id}/votes`),

  agreeVote: (id: Id, voteId: Id) =>
    api.post<RoomVoteVO>(`/room/rooms/${id}/votes/${voteId}/agree`),

  currentVote: (id: Id) => api.get<RoomVoteVO | null>(`/room/rooms/${id}/votes/current`),

  rank: (id: Id, type: RankType, limit = 10) =>
    api.get<RoomRankEntryVO[]>(`/room/rooms/${id}/rank`, { type, limit }),

  /** 隐私开关：hidden=true 不上榜 */
  setRankHidden: (id: Id, hidden: boolean) =>
    api.post<void>(`/room/rooms/${id}/rank/hidden`, { hidden }),

  like: (id: Id) =>
    api.post<{ songId: Id | null; liked: boolean; likeCount: number }>(
      `/room/rooms/${id}/like`,
    ),

  listen: (id: Id, seconds: number) =>
    api.post<{ acceptedSeconds: number; listenScore: number; serverTime: number }>(
      `/room/rooms/${id}/listen`,
      { seconds },
    ),
};
