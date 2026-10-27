import { api } from '@/api/request';
import type {
  Album,
  AlbumQuery,
  Artist,
  ArtistQuery,
  ArtistSaveReq,
  Category,
  Id,
  PageResult,
  Tag,
  TagQuery,
} from '@/types/api';

/** 歌手 / 专辑 / 分类 / 标签（同属「曲库目录」，合成一个模块避免文件碎片化） */

export const artistKeys = {
  all: ['artist'] as const,
  list: (query: ArtistQuery) => [...artistKeys.all, 'list', query] as const,
  detail: (id: Id) => [...artistKeys.all, 'detail', id] as const,
};

export const albumKeys = {
  all: ['album'] as const,
  list: (query: AlbumQuery) => [...albumKeys.all, 'list', query] as const,
  detail: (id: Id) => [...albumKeys.all, 'detail', id] as const,
};

export const artistApi = {
  list: (query: ArtistQuery) => api.get<PageResult<Artist>>('/music/artists', query),
  detail: (id: Id) => api.get<Artist>(`/music/artists/${id}`, undefined, { skipErrorToast: true }),
  create: (data: ArtistSaveReq) => api.post<Id>('/music/artists', data),
  update: (id: Id, data: ArtistSaveReq) => api.put<Id>(`/music/artists/${id}`, data),
  remove: (id: Id) => api.delete<Id>(`/music/artists/${id}`),
};

export const albumApi = {
  list: (query: AlbumQuery) => api.get<PageResult<Album>>('/music/albums', query),
  detail: (id: Id) => api.get<Album>(`/music/albums/${id}`, undefined, { skipErrorToast: true }),
  remove: (id: Id) => api.delete<Id>(`/music/albums/${id}`),
};

export const categoryApi = {
  /** 启用中的分类（首页/后台筛选项） */
  enabled: () => api.get<Category[]>('/music/categories/enabled', undefined, { skipErrorToast: true }),
};

export const tagApi = {
  hot: (limit = 20) => api.get<Tag[]>('/music/tags/hot', { limit }, { skipErrorToast: true }),
  list: (params?: TagQuery) => api.get<PageResult<Tag>>('/music/tags', params),
};
