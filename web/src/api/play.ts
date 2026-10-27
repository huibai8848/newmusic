import { api } from '@/api/request';
import type { Quality } from '@/api/player';
import type { BaseEntity, Id, PageResult } from '@/types/api';
import { getDeviceId, getDeviceName, getDeviceType } from '@/utils/device';

/**
 * 播放行为上报（模块 08 落地，模块 07 系分原定模块 12）。
 *
 * 推荐画像依赖服务端行为数据，因此这里实现最小闭环：
 * 开始播放（record）→ 每 30 秒进度（progress）→ 切歌/结束时结算（end）。
 * `record` / `end` 允许游客（按 deviceId 归属），`progress` 与历史列表要求登录。
 */

export interface RecordPlayReq {
  songId: Id;
  quality?: string;
  /** search/playlist/album/artist/home/room/recommend */
  source?: string;
  roomId?: Id;
  /** 游客必传；登录用户可不传，服务端以 userId 归属 */
  deviceId?: string;
  /** 设备管理列表展示用（模块 12） */
  deviceName?: string;
  deviceType?: 'WEB' | 'PWA' | 'MOBILE_WEB';
  playId?: string;
}

export interface PlayRecordVO {
  playId: Id | null;
  songId: Id;
  songName: string;
  artistName: string;
  /** 5 秒内重复上报，服务端复用了已有记录 */
  duplicated: boolean;
}

export interface ProgressReportReq {
  songId: Id;
  playId?: Id | null;
  position: number;
  duration?: number;
  /** 模块 12：跨设备续播需要知道进度是哪台设备上报的 */
  deviceId?: string;
  deviceName?: string;
  deviceType?: 'WEB' | 'PWA' | 'MOBILE_WEB';
}

/** 7 档倍速（PRD FR-21-002），顺序即 UI 展示顺序 */
export const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2] as const;
export type PlaybackRate = (typeof PLAYBACK_RATES)[number];

/** 播放设置（模块 12 FR-21-008）；音量刻意不在其中（BR-21-076） */
export interface PlaySetting {
  userId: Id;
  defaultQuality: Quality;
  playMode: 'order' | 'loop' | 'single' | 'shuffle';
  /** 0 / 1 */
  autoQuality: number;
  dataSaver: number;
  fadeEnabled: number;
  equalizerPreset: string;
  equalizerBands: string | null;
  playbackRate: number;
  lyricMode: 'original' | 'translation' | 'original_translation' | 'none';
  lyricOffset: number;
  theme: 'system' | 'light' | 'dark';
  autoDownload: number;
}

export type PlaySettingPatch = Partial<Omit<PlaySetting, 'userId'>>;

export interface PlayQualityOption {
  code: Quality;
  label: string;
  bitrate: string;
  format: string;
  vipOnly: boolean;
  available: boolean;
  unavailableReason: string | null;
}

export interface PlayQualitiesVO {
  songId: Id | null;
  vip: boolean;
  recommended: Quality | null;
  qualities: PlayQualityOption[];
}

/** 跨设备续播读取结果：服务端只给数据，不自动跳转（BR-21-066） */
export interface PlayProgressVO {
  songId: Id;
  found: boolean;
  position: number;
  duration: number;
  playId: Id | null;
  deviceId: string | null;
  updateTime: number;
  fromCurrentDevice: boolean;
  source: 'REDIS' | 'DB' | 'NONE';
}

export interface DeviceVO {
  deviceId: string;
  deviceName: string | null;
  deviceType: 'WEB' | 'PWA' | 'MOBILE_WEB';
  lastActiveTime: string;
  lastIp: string | null;
  current: boolean;
}

export interface EndPlayReq {
  playId: Id;
  playedDuration: number;
  songDuration?: number;
  /** 结束时的播放位置（秒），用于续播点；与 playedDuration 语义不同 */
  position?: number;
  completed?: boolean;
  deviceId?: string;
}

export interface PlayHistory extends BaseEntity {
  userId: Id | null;
  deviceId: string | null;
  songId: Id;
  songName: string;
  artistId: Id | null;
  artistName: string;
  genre: string | null;
  language: string | null;
  /** 1标准 2高清 3无损 */
  quality: number | null;
  playDuration: number | null;
  songDuration: number | null;
  lastPosition: number | null;
  /** 1 = 有效播放（≥30 秒或 ≥60%） */
  isValid: number | null;
  source: string | null;
  roomId: Id | null;
  playTime: string;
}

export const playApi = {
  record: (data: Omit<RecordPlayReq, 'deviceId' | 'deviceName' | 'deviceType'>) =>
    api.post<PlayRecordVO>(
      '/play/record',
      { ...data, deviceId: getDeviceId(), deviceName: getDeviceName(), deviceType: getDeviceType() },
      { skipErrorToast: true },
    ),

  progress: (data: Omit<ProgressReportReq, 'deviceId' | 'deviceName' | 'deviceType'>) =>
    api.post<void>(
      '/play/progress',
      { ...data, deviceId: getDeviceId(), deviceName: getDeviceName(), deviceType: getDeviceType() },
      { skipErrorToast: true },
    ),

  end: (data: Omit<EndPlayReq, 'deviceId'>) =>
    api.post<void>('/play/end', { ...data, deviceId: getDeviceId() }, { skipErrorToast: true }),

  history: (pageNum = 1, pageSize = 20) =>
    api.get<PageResult<PlayHistory>>('/play/history', { pageNum, pageSize }),

  remove: (id: Id) => api.delete<void>(`/play/history/${id}`),

  clear: () => api.delete<void>('/play/history'),

  // ------------------------------------------------------------ 模块 12

  settings: () => api.get<PlaySetting>('/play/settings'),

  updateSettings: (patch: PlaySettingPatch) => api.put<PlaySetting>('/play/settings', patch),

  /** 可用音质列表；不传 songId 时只反映权限维度 */
  qualities: (songId?: Id) => api.get<PlayQualitiesVO>('/player/qualities', songId ? { songId } : undefined),

  /** 读取某首歌最近一次进度（登录用户） */
  progressOf: (songId: Id) =>
    api.get<PlayProgressVO>('/play/progress', { songId, deviceId: getDeviceId() }, { skipErrorToast: true }),

  devices: () => api.get<DeviceVO[]>('/devices', { deviceId: getDeviceId() }),

  logoutDevice: (deviceId: string) => api.delete<void>(`/devices/${encodeURIComponent(deviceId)}`),

  logoutAllDevices: () => api.delete<number>('/devices/all'),
};
