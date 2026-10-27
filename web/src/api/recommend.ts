import { api } from '@/api/request';
import type { Artist, Id, Song } from '@/types/api';

/** AI 推荐（模块 08）。所有 VO 与后端 `module/recommend/vo` 一一对应。 */

export interface RecommendItem {
  song: Song;
  /** 推荐理由，≤20 字，可直接展示 */
  reason: string;
  /** 召回来源：hot/genre/artist/tag/explore/... */
  recall: string;
  score: number;
  position: number;
  listened: boolean;
}

export interface DailyRecommendVO {
  /** yyyy-MM-dd */
  date: string;
  /** 生成失败时退化为热门 */
  fallback: boolean;
  generatedAt: number;
  items: RecommendItem[];
}

export interface GuessRecommendVO {
  page: number;
  pageSize: number;
  hasNext: boolean;
  /** 行为不足 5 条，当前结果以热门/问卷为主 */
  coldStart: boolean;
  items: RecommendItem[];
}

export interface SimilarRecommendVO {
  songId: Id;
  /** 无离线数据时实时计算并用热门补齐 */
  fallback: boolean;
  items: RecommendItem[];
}

export interface ContinuePlayVO {
  song: Song;
  position: number;
  duration: number;
  /** 0~1 */
  progress: number;
}

export interface FrequentArtistVO {
  artistId: Id;
  artistName: string;
  avatar: string | null;
  playCount: number;
  lastPlayTime: string;
}

export interface BlocklistVO {
  songs: Song[];
  artists: Artist[];
  songLimit: number;
  artistLimit: number;
}

export interface QuestionnairePreference {
  styles: string[];
  languages: string[];
  artists: Id[];
  mood: string | null;
}

export interface QuestionnaireVO {
  styleOptions: string[];
  languageOptions: string[];
  /** key 为稳定码（FOCUS…），value 为中文文案 */
  moodOptions: Record<string, string>;
  current: QuestionnairePreference | null;
}

export interface QuestionnaireReq {
  styles?: string[];
  languages?: string[];
  artists?: Id[];
  mood?: string | null;
}

export type FeedbackAction = 'LIKE' | 'DISLIKE' | 'DISLIKE_ARTIST' | 'UNDO' | 'UNDO_ARTIST';

export interface FeedbackReq {
  songId?: Id;
  artistId?: Id;
  action: FeedbackAction;
  /** DAILY / GUESS / SIMILAR / DETAIL */
  scene?: string;
  position?: number;
}

export interface FeedbackVO {
  action: string;
  songId: Id | null;
  artistId: Id | null;
  dislikedSongCount: number;
  dislikedArtistCount: number;
  likedSongCount: number;
}

export interface HomeRecommendVO {
  daily: DailyRecommendVO | null;
  guess: GuessRecommendVO | null;
  continuePlay: ContinuePlayVO[];
  frequentArtists: FrequentArtistVO[];
  coldStart: boolean;
  /** 加载失败的区块名，页面据此局部降级 */
  degraded: string[];
}

export const recommendKeys = {
  all: ['recommend'] as const,
  daily: () => [...recommendKeys.all, 'daily'] as const,
  guess: (page: number) => [...recommendKeys.all, 'guess', page] as const,
  similar: (songId: Id) => [...recommendKeys.all, 'similar', String(songId)] as const,
  continuePlay: () => [...recommendKeys.all, 'continue-play'] as const,
  frequentArtists: () => [...recommendKeys.all, 'frequent-artists'] as const,
  recentSongs: (limit: number) => [...recommendKeys.all, 'recent-songs', limit] as const,
  blocklist: () => [...recommendKeys.all, 'blocklist'] as const,
  questionnaire: () => [...recommendKeys.all, 'questionnaire'] as const,
};

export const recommendApi = {
  daily: () => api.get<DailyRecommendVO>('/recommend/daily'),
  guess: (page = 1, size = 20) => api.get<GuessRecommendVO>('/recommend/guess', { page, size }),
  similar: (songId: Id) =>
    api.get<SimilarRecommendVO>('/recommend/similar', { songId }, { skipErrorToast: true }),
  home: () => api.get<HomeRecommendVO>('/recommend/home'),

  continuePlay: () => api.get<ContinuePlayVO[]>('/recommend/continue-play', undefined, { skipErrorToast: true }),
  dismissContinue: (songId: Id) => api.delete<void>(`/recommend/continue-play/${songId}`),
  frequentArtists: () =>
    api.get<FrequentArtistVO[]>('/recommend/frequent-artists', undefined, { skipErrorToast: true }),
  recentSongs: (limit = 12) => api.get<Song[]>('/recommend/recent-songs', { limit }, { skipErrorToast: true }),

  feedback: (data: FeedbackReq) => api.post<FeedbackVO>('/recommend/feedback', data),
  blocklist: () => api.get<BlocklistVO>('/recommend/blocklist'),
  removeBlockedSong: (songId: Id) => api.delete<void>(`/recommend/blocklist/song/${songId}`),
  removeBlockedArtist: (artistId: Id) => api.delete<void>(`/recommend/blocklist/artist/${artistId}`),

  questionnaire: () => api.get<QuestionnaireVO>('/recommend/questionnaire'),
  saveQuestionnaire: (data: QuestionnaireReq) => api.post<QuestionnaireVO>('/recommend/questionnaire', data),

  /** 曝光上报：当日已曝光未点击的歌曲不再重复生成 */
  exposure: (songIds: Array<Id | number>, scene = 'DAILY') =>
    api.post<void>('/recommend/exposure', { songIds, scene }, { skipErrorToast: true }),
};
