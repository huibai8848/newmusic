import { api } from '@/api/request';
import type { Id, Lyric, PageResult, Song, SongQuery, SongSaveReq, Tag } from '@/types/api';

/** QueryKey 统一收口，方便失效与调试 */
export const songKeys = {
  all: ['song'] as const,
  list: (query: SongQuery) => [...songKeys.all, 'list', query] as const,
  detail: (id: Id) => [...songKeys.all, 'detail', id] as const,
  lyric: (songId: Id) => [...songKeys.all, 'lyric', songId] as const,
  tags: (songId: Id) => [...songKeys.all, 'tags', songId] as const,
};

export const songApi = {
  /** 列表：游客仅返回已上架歌曲，带 music:song:read 权限可查全部 */
  list: (query: SongQuery, skipErrorToast = false) =>
    api.get<PageResult<Song>>('/music/songs', query, { skipErrorToast }),
  detail: (id: Id, skipErrorToast = false) =>
    api.get<Song>(`/music/songs/${id}`, undefined, { skipErrorToast }),
  create: (data: SongSaveReq) => api.post<Id>('/music/songs', data),
  update: (id: Id, data: SongSaveReq) => api.put<Id>(`/music/songs/${id}`, data),
  /** status: 0 下架 / 1 上架 */
  changeStatus: (id: Id, status: number) => api.put<Id>(`/music/songs/${id}/status/${status}`),
  /** auditStatus: 0 待审 / 1 通过 / 2 拒绝（拒绝自动下架） */
  audit: (id: Id, auditStatus: number) => api.put<Id>(`/music/songs/${id}/audit/${auditStatus}`),
  remove: (id: Id) => api.delete<Id>(`/music/songs/${id}`),
  batchRemove: (ids: Id[]) => api.delete<number>('/music/songs/batch', { ids }),
  lyric: (songId: Id) => api.get<Lyric | null>(`/music/lyrics/${songId}`, undefined, { skipErrorToast: true }),
  tags: (songId: Id) => api.get<Tag[]>(`/music/tags/song/${songId}`, undefined, { skipErrorToast: true }),
};
