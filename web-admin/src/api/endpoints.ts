import { del, get, post, postUpload, put } from './client';
import type {
  AdminRole,
  AdminUser,
  Album,
  Artist,
  AuditRecord,
  AuditTask,
  Captcha,
  CUser,
  Dashboard,
  DictAnalyzeResult,
  DictChangeLog,
  DictImportResult,
  DictPinyin,
  DictStatus,
  DictStopword,
  DictSynonymGroup,
  DictSyncResult,
  DictWord,
  Id,
  LoginLog,
  LoginResult,
  OperationLog,
  PageResult,
  Report,
  Room,
  RoomMessage,
  Song,
  SysConfig,
  SysConfigHistory,
} from './types';

export interface PageParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  [key: string]: unknown;
}

export const authApi = {
  captcha: () => get<Captcha>('/auth/captcha'),
  login: (body: {
    username: string;
    password: string;
    captchaId: string;
    captchaCode: string;
    totpCode?: string;
  }) => post<LoginResult>('/auth/login', body),
  me: () => get<AdminUser>('/auth/me'),
  logout: () => post<void>('/auth/logout'),
  changePassword: (body: { oldPassword: string; newPassword: string }) =>
    post<LoginResult>('/auth/password', body),
};

export const dashboardApi = {
  overview: () => get<Dashboard>('/dashboard'),
};

export const userApi = {
  page: (params: PageParams) => get<PageResult<CUser>>('/users', params),
  detail: (id: Id) => get<CUser>(`/users/${id}`),
  edit: (id: Id, body: { nickname?: string; email?: string; userType?: number }) =>
    put<CUser>(`/users/${id}`, body),
  phone: (id: Id) => get<{ phone: string }>(`/users/${id}/phone`),
  ban: (id: Id, body: { reason: string; banUntil?: string }) =>
    post<number>(`/users/${id}/ban`, body),
  unban: (id: Id) => post<void>(`/users/${id}/unban`),
  resetPassword: (id: Id) => post<string>(`/users/${id}/reset-password`),
};

export interface SongQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: number;
  auditStatus?: number;
  artistId?: string;
  albumId?: string;
  genre?: string;
  language?: string;
}

export interface SongPayload {
  name: string;
  artistId: Id;
  subtitle?: string;
  albumId?: Id;
  cover?: string;
  language?: string;
  genre?: string;
  duration?: number;
  releaseDate?: string;
  audioStandard?: string;
  audioHigh?: string;
  audioLossless?: string;
  /** 歌词正文（LRC 或纯文本）。新增/编辑时随歌曲写入，空字符串表示不改动。 */
  lyric?: string;
  copyrightStatus?: number;
  auditStatus?: number;
  status?: number;
  version?: number;
}

export interface SongLyric {
  id: Id;
  songId: Id;
  content?: string;
  contentType?: number;
}

export interface UploadVO {
  url: string;
  size?: number;
  originalName?: string;
}

/**
 * 后台素材上传（模块 09）。
 *
 * 图片类型由服务端按文件头识别，这里只需要把文件和原始文件名一起发出；
 * 不要手动设置 `Content-Type`，否则会丢掉 multipart 的 boundary。
 */
export const fileApi = {
  uploadImage: (file: File) => {
    const form = new FormData();
    form.append('file', file, file.name || 'image');
    return postUpload<UploadVO>('/files/image', form);
  },
  /** 音频不做转码，服务端只校验扩展名（mp3/wav/flac）与文件头，返回原始文件 URL。 */
  uploadAudio: (file: File) => {
    const form = new FormData();
    form.append('file', file, file.name || 'audio');
    return postUpload<UploadVO>('/files/audio', form);
  },
};

export const songApi = {
  page: (params: SongQuery) => get<PageResult<Song>>('/songs', params),
  detail: (id: Id) => get<Song>(`/songs/${id}`),
  /** 后台读歌词：没歌词时返回 null，抽屉里显示为空文本域 */
  lyric: (id: Id) => get<SongLyric | null>(`/songs/${id}/lyric`),
  create: (body: SongPayload) => post<Id>('/songs', body),
  update: (id: Id, body: SongPayload) => put<Id>(`/songs/${id}`, body),
  changeStatus: (id: Id, status: number) => put<Id>(`/songs/${id}/status/${status}`),
  batchStatus: (ids: Id[], status: number) =>
    post<number>('/songs/batch-status', { ids, status }),
  remove: (id: Id, reason: string) => del<Id>(`/songs/${id}`, { reason }),
  importRows: (rows: SongPayload[]) => post<ImportResult>('/songs/import', { rows }),
  exportUrl: (params: SongQuery) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        query.set(key, String(value));
      }
    });
    return `/api/v1/admin/songs/export?${query.toString()}`;
  },
};

export interface ImportRowError {
  row: number;
  message: string;
}

export interface ImportResult {
  success: number;
  failed: number;
  errors: ImportRowError[];
}

export interface ArtistPayload {
  name: string;
  alias?: string;
  avatar?: string;
  cover?: string;
  gender?: number;
  country?: string;
  birthday?: string;
  debutDate?: string;
  description?: string;
  styles?: string;
  firstLetter?: string;
  status?: number;
  version?: number;
}

export const artistApi = {
  page: (params: PageParams & { country?: string; firstLetter?: string; status?: number }) =>
    get<PageResult<Artist>>('/artists', params),
  detail: (id: Id) => get<Artist>(`/artists/${id}`),
  create: (body: ArtistPayload) => post<Id>('/artists', body),
  update: (id: Id, body: ArtistPayload) => put<Id>(`/artists/${id}`, body),
  remove: (id: Id) => del<Id>(`/artists/${id}`),
};

export interface AlbumPayload {
  name: string;
  artistId: Id;
  cover?: string;
  publishDate?: string;
  company?: string;
  description?: string;
  language?: string;
  status?: number;
  version?: number;
}

export const albumApi = {
  page: (params: PageParams & { artistId?: string; status?: number }) =>
    get<PageResult<Album>>('/albums', params),
  detail: (id: Id) => get<Album>(`/albums/${id}`),
  create: (body: AlbumPayload) => post<Id>('/albums', body),
  update: (id: Id, body: AlbumPayload) => put<Id>(`/albums/${id}`, body),
  remove: (id: Id) => del<Id>(`/albums/${id}`),
};

export const roomApi = {
  page: (params: PageParams & { status?: number; roomType?: number; ownerId?: string }) =>
    get<PageResult<Room>>('/rooms', params),
  detail: (id: Id) => get<Room>(`/rooms/${id}`),
  messages: (id: Id, params: PageParams) =>
    get<PageResult<RoomMessage>>(`/rooms/${id}/messages`, params),
  forceClose: (id: Id, reason: string) => post<void>(`/rooms/${id}/force-close`, { reason }),
  banCreate: (userId: Id, body: { reason: string; hours?: number }) =>
    post<void>(`/rooms/owners/${userId}/create-ban`, body),
  unbanCreate: (userId: Id) => del<void>(`/rooms/owners/${userId}/create-ban`),
};

export const auditApi = {
  page: (params: PageParams & {
    status?: number;
    priority?: number;
    taskType?: string;
    targetType?: string;
    onlyMine?: boolean;
    escalated?: boolean;
  }) => get<PageResult<AuditTask>>('/audits', params),
  detail: (id: Id) => get<AuditTask>(`/audits/${id}`),
  records: (id: Id) => get<AuditRecord[]>(`/audits/${id}/records`),
  claim: (id: Id) => post<AuditTask>(`/audits/${id}/claim`),
  release: (id: Id) => post<void>(`/audits/${id}/release`),
  handle: (id: Id, body: { result: 'APPROVE' | 'REJECT'; reason?: string; version?: number }) =>
    post<AuditTask>(`/audits/${id}/handle`, body),
};

export const reportApi = {
  page: (params: PageParams & {
    status?: number;
    priority?: number;
    targetType?: string;
  }) => get<PageResult<Report>>('/reports', params),
  handle: (id: Id, body: {
    result: 'VALID' | 'INVALID' | 'NEED_MORE';
    reason: string;
    action?: string;
    mergeSiblings?: boolean;
  }) => post<Report>(`/reports/${id}/handle`, body),
};

export const logApi = {
  operations: (params: PageParams & {
    opType?: string;
    targetType?: string;
    result?: number;
    startDate?: string;
    endDate?: string;
  }) => get<PageResult<OperationLog>>('/logs/operations', params),
  logins: (params: PageParams & {
    loginType?: number;
    result?: number;
    suspicious?: boolean;
    ip?: string;
    startDate?: string;
    endDate?: string;
  }) => get<PageResult<LoginLog>>('/logs/logins', params),
  exportUrl: (params: Record<string, unknown>) => {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        query.set(key, String(value));
      }
    });
    return `/api/v1/admin/logs/operations/export?${query.toString()}`;
  },
};

export const configApi = {
  page: (params: PageParams & { group?: string }) => get<PageResult<SysConfig>>('/configs', params),
  create: (body: {
    configKey: string;
    configValue: string;
    valueType?: number;
    configGroup?: string;
    description?: string;
  }) => post<{ id: Id }>('/configs', body),
  update: (id: Id, body: { configValue: string; remark?: string }) =>
    put<void>(`/configs/${id}`, body),
  history: (id: Id) => get<SysConfigHistory[]>(`/configs/${id}/history`),
  rollback: (historyId: Id) => post<void>(`/configs/history/${historyId}/rollback`),
};

export const accountApi = {
  page: (params: PageParams & { status?: number }) => get<PageResult<AdminUser>>('/accounts', params),
  roles: () => get<AdminRole[]>('/accounts/roles'),
  create: (body: AccountPayload) => post<Id>('/accounts', body),
  update: (id: Id, body: AccountPayload) => put<void>(`/accounts/${id}`, body),
  changeStatus: (id: Id, status: number) => post<void>(`/accounts/${id}/status`, { status }),
  resetPassword: (id: Id) => post<string>(`/accounts/${id}/reset-password`),
  resetTotp: (id: Id) => post<string>(`/accounts/${id}/reset-totp`),
};

export interface AccountPayload {
  username: string;
  password?: string;
  realName?: string;
  email?: string;
  phone?: string;
  roleCode: string;
  totpEnabled?: boolean;
  allowIpList?: string;
  remark?: string;
}

/** ---------------- 模块 11：搜索词典 ---------------- */

export interface DictWordPayload {
  word: string;
  frequency?: number;
  pos?: string;
  enabled?: number;
  dictType?: string;
}

export interface DictImportPayload {
  content: string;
  dictType?: string;
  overwrite?: boolean;
}

export interface DictSynonymPayload {
  words: string[];
  primaryWord?: string;
  synonymType?: string;
  category?: string;
  enabled?: number;
}

function exportUrl(path: string, params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString();
  return suffix ? `${path}?${suffix}` : path;
}

export const dictApi = {
  status: () => get<DictStatus>('/dict/status'),
  analyze: (params: { text: string; analyzer?: string; previewWords?: string }) =>
    get<DictAnalyzeResult>('/dict/analyze', params),
  changeLog: (params: PageParams & { dictType?: string; changeType?: string; operatorId?: Id }) =>
    get<PageResult<DictChangeLog>>('/dict/change-log', params),

  words: {
    page: (params: PageParams & { source?: string; dictType?: string; enabled?: number }) =>
      get<PageResult<DictWord>>('/dict/words', params),
    create: (body: DictWordPayload) => post<DictWord>('/dict/words', body),
    update: (id: Id, body: DictWordPayload) => put<DictWord>(`/dict/words/${id}`, body),
    remove: (id: Id) => del<Id>(`/dict/words/${id}`),
    importWords: (body: DictImportPayload) => post<DictImportResult>('/dict/words/import', body),
    exportUrl: (params: Record<string, unknown> = {}) =>
      exportUrl('/api/v1/admin/dict/words/export', params),
  },

  stopwords: {
    page: (params: PageParams & { enabled?: number }) =>
      get<PageResult<DictStopword>>('/dict/stopwords', params),
    create: (body: { word: string; enabled?: number }) => post<DictStopword>('/dict/stopwords', body),
    update: (id: Id, body: { word: string; enabled?: number }) =>
      put<DictStopword>(`/dict/stopwords/${id}`, body),
    remove: (id: Id) => del<Id>(`/dict/stopwords/${id}`),
    importWords: (body: DictImportPayload) => post<DictImportResult>('/dict/stopwords/import', body),
    exportUrl: () => '/api/v1/admin/dict/stopwords/export',
  },

  synonyms: {
    page: (params: PageParams & { category?: string; enabled?: number }) =>
      get<PageResult<DictSynonymGroup>>('/dict/synonyms', params),
    create: (body: DictSynonymPayload) => post<DictSynonymGroup>('/dict/synonyms', body),
    update: (groupNo: number, body: DictSynonymPayload) =>
      put<DictSynonymGroup>(`/dict/synonyms/${groupNo}`, body),
    remove: (groupNo: number) => del<number>(`/dict/synonyms/${groupNo}`),
    importWords: (body: DictImportPayload) => post<DictImportResult>('/dict/synonyms/import', body),
    exportUrl: () => '/api/v1/admin/dict/synonyms/export',
  },

  pinyin: {
    page: (params: PageParams & { enabled?: number }) =>
      get<PageResult<DictPinyin>>('/dict/pinyin', params),
    create: (body: { word: string; pinyin: string; enabled?: number }) =>
      post<DictPinyin>('/dict/pinyin', body),
    update: (id: Id, body: { word: string; pinyin: string; enabled?: number }) =>
      put<DictPinyin>(`/dict/pinyin/${id}`, body),
    remove: (id: Id) => del<Id>(`/dict/pinyin/${id}`),
    importWords: (body: DictImportPayload) => post<DictImportResult>('/dict/pinyin/import', body),
    exportUrl: () => '/api/v1/admin/dict/pinyin/export',
  },

  reload: () => post<void>('/dict/reload'),
  syncArtists: () => post<DictSyncResult>('/dict/sync-artists'),
  reindex: () => post<DictSyncResult>('/dict/reindex'),
};
