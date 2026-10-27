import { api } from '@/api/request';
import type { Id, PageResult, Playlist, PlaylistDetail, PlaylistQuery, PlaylistSaveReq, Song } from '@/types/api';

export const playlistKeys = {
  all: ['playlist'] as const,
  list: (query: PlaylistQuery) => [...playlistKeys.all, 'list', query] as const,
  detail: (id: Id, pageNum = 1, pageSize = 20) =>
    [...playlistKeys.all, 'detail', id, pageNum, pageSize] as const,
};

export const playlistApi = {
  /** mine=true 返回我的歌单（含私密）；匿名仅见公开歌单 */
  list: (query: PlaylistQuery, skipErrorToast = false) =>
    api.get<PageResult<Playlist>>('/music/playlists', query, { skipErrorToast }),
  detail: (id: Id, pageNum = 1, pageSize = 20, skipErrorToast = false) =>
    api.get<PlaylistDetail>(`/music/playlists/${id}`, { pageNum, pageSize }, { skipErrorToast }),
  songs: (id: Id, pageNum = 1, pageSize = 20) =>
    api.get<PageResult<Song>>(`/music/playlists/${id}/songs`, { pageNum, pageSize }),
  create: (data: PlaylistSaveReq) => api.post<Id>('/music/playlists', data),
  /** 必须回传详情里的 version，否则 10001 */
  update: (id: Id, data: PlaylistSaveReq) => api.put<Id>(`/music/playlists/${id}`, data),
  remove: (id: Id) => api.delete<Id>(`/music/playlists/${id}`),
  /** 加歌：重复歌曲幂等跳过，返回实际新增数 */
  addSongs: (id: Id, songIds: Id[]) => api.post<number>(`/music/playlists/${id}/songs`, { songIds }),
  /** 移歌，返回实际移除数 */
  removeSongs: (id: Id, songIds: Id[]) => api.delete<number>(`/music/playlists/${id}/songs`, { songIds }),
  /** 重排：传完整有序 songId 数组 */
  reorder: (id: Id, songIds: Id[]) => api.put<number>(`/music/playlists/${id}/songs/order`, { songIds }),
};
