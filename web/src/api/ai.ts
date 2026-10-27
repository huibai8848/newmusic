import { api } from '@/api/request';
import type { Id } from '@/types/api';

/**
 * AI 能力（模块 14 P1）。
 *
 * 只有 C 端用得到的两类接口放在这里：歌词翻译与歌曲情绪。
 * 后台的 `/admin/ai/**` 走管理端自身的请求层，不混进来——两边登录态与错误处理完全不同。
 *
 * 读接口都用 `skipErrorToast`：翻译/情绪是**装饰性内容**，拿不到不该打断用户听歌，
 * 页面自己降级成「不显示」即可；写接口（生成、反馈）保留 toast，那是用户主动点出来的。
 */

export interface TranslationLine {
  /** 标签上的原始时间（毫秒），与后端 LRC 解析结果一致，用于与前端 `rawTime * 1000` 对齐 */
  timeMs: number;
  original: string;
  /** 未生成时为 `null` */
  translation: string | null;
}

export interface LyricTranslation {
  songId: Id;
  songName: string;
  language: string | null;
  transLang: string;
  /** 是否已有译文（数据库/Redis 命中或本次刚生成） */
  generated: boolean;
  /** 本次是否命中 Redis 缓存；命中不产生成本 */
  fromCache: boolean;
  model: string | null;
  costCent: number;
  /** 带时间轴的译文 LRC；未生成时为 null */
  translation: string | null;
  lines: TranslationLine[];
}

export interface SongEmotion {
  songId: Id;
  songName: string;
  /** false 时各数值只是中性默认值，不能当真实结果展示 */
  annotated: boolean;
  valence: number;
  arousal: number;
  energy: number;
  emotionTags: string[];
  sceneTags: string[];
  confidence: number;
  /** `AI` / `MANUAL`；未标注为 null */
  source: 'AI' | 'MANUAL' | null;
  reason: string | null;
  modelVersion: string | null;
  promptVersion: string | null;
  lowConfidence: boolean;
  updatedAt: string | null;
}

export const aiKeys = {
  all: ['ai'] as const,
  translation: (songId: Id) => [...aiKeys.all, 'translation', songId] as const,
  emotion: (songId: Id) => [...aiKeys.all, 'emotion', songId] as const,
};

export const aiApi = {
  /**
   * 取歌词翻译。未生成时 `generated=false`，这是正常结果而不是错误。
   *
   * 中文歌曲/无歌词会返回 60006（`AI_NOT_APPLICABLE`），由调用方按「不适用」处理。
   */
  getTranslation: (songId: Id, lang = 'zh') =>
    api.get<LyricTranslation>(`/lyric/${songId}/translation`, { lang }, { skipErrorToast: true }),

  /** 生成翻译（需登录）：已有译文时幂等返回，不重复花钱 */
  generateTranslation: (songId: Id) => api.post<LyricTranslation>(`/lyric/${songId}/translation`),

  /** 翻译质量反馈：正文最多 200 字，后端校验 */
  feedback: (songId: Id, issue: string) =>
    api.post<number>(`/lyric/${songId}/translation/feedback`, { issue }),

  /** 歌曲情绪/场景标注；未标注时 `annotated=false` */
  getEmotion: (songId: Id) =>
    api.get<SongEmotion>(`/songs/${songId}/emotion`, undefined, { skipErrorToast: true }),
};
