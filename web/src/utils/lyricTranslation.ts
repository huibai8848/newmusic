import type { LyricLine } from './lrc';

/**
 * 歌词原文与译文的对齐（模块 14 FR-23-002）。
 *
 * 后端返回的 `lines` 已经带 `timeMs`，但前端用的是自己解析的 LRC（含 offset 与用户校准），
 * 所以**不能按行号硬拼**：一旦 offset 不同，第 3 行配到第 3 行就是错句。
 *
 * 对齐优先用**标签原始时间**（`rawTime * 1000`）——它与后端解析的是同一份内容，
 * 不受 offset 影响。时间对不上时退化为按行号对齐，但仅当两边行数完全一致：
 * 行数不同就说明有一边漏行，按行号拼只会把错误扩散到整段。
 */

export type LyricDisplayMode = 'original' | 'original_translation' | 'translation';

/** 后端 `LyricTranslationVO.Line` 里我们真正需要的部分 */
export interface TranslationLineLike {
  timeMs: number;
  translation: string | null;
}

export interface AlignedTranslations {
  /** 与入参 `lines` 等长；没有译文的行为 null */
  translations: (string | null)[];
  /** `time` 按时间对齐 / `index` 行数一致时按行号兜底 / `none` 无法对齐 */
  mode: 'time' | 'index' | 'none';
  /** 成功配上译文的行数 */
  matched: number;
}

const EMPTY: AlignedTranslations = { translations: [], mode: 'none', matched: 0 };

/** 译文是否为可用内容（空串、纯空白都当作没有） */
export function hasText(value: string | null | undefined): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

export function alignTranslations(
  lines: readonly Pick<LyricLine, 'rawTime'>[],
  translations: readonly TranslationLineLike[] | null | undefined,
): AlignedTranslations {
  if (lines.length === 0) return EMPTY;
  const filled: (string | null)[] = lines.map(() => null);
  if (!translations || translations.length === 0) {
    return { translations: filled, mode: 'none', matched: 0 };
  }

  // 同一时间可能有重复行（副歌重复很常见），所以每个时间点存一个队列而不是单个下标
  const byTime = new Map<number, number[]>();
  translations.forEach((item, index) => {
    const key = Math.round(item.timeMs);
    const bucket = byTime.get(key);
    if (bucket) bucket.push(index);
    else byTime.set(key, [index]);
  });

  let matched = 0;
  lines.forEach((line, lineIndex) => {
    const key = Math.round(line.rawTime * 1000);
    const bucket = byTime.get(key);
    if (!bucket) return;
    while (bucket.length > 0) {
      const cursor = bucket.shift() as number;
      const text = translations[cursor].translation;
      if (hasText(text)) {
        filled[lineIndex] = text.trim();
        matched += 1;
        return;
      }
    }
  });

  if (matched > 0) return { translations: filled, mode: 'time', matched };

  // 时间一条都对不上：只有两边行数完全一致才按行号兜底（常见于无时间轴歌词，
  // 或后端因规范化重新编号了时间）
  if (lines.length === translations.length) {
    let indexMatched = 0;
    translations.forEach((item, index) => {
      if (hasText(item.translation)) {
        filled[index] = item.translation.trim();
        indexMatched += 1;
      }
    });
    if (indexMatched > 0) {
      return { translations: filled, mode: 'index', matched: indexMatched };
    }
  }

  return { translations: filled, mode: 'none', matched: 0 };
}

export interface DisplayText {
  primary: string;
  /** 第二行小字；`null` 表示这一行只渲染一行 */
  secondary: string | null;
}

/**
 * 按展示模式决定主行/副行。
 *
 * 「仅译文」遇到某行没有译文时回退到原文——宁可显示原文，也不要留一行空白，
 * 否则整段歌词看起来像缺行。
 */
export function displayText(
  original: string,
  translation: string | null | undefined,
  mode: LyricDisplayMode,
): DisplayText {
  const tr = hasText(translation) ? translation.trim() : null;
  if (mode === 'original' || !tr) return { primary: original, secondary: null };
  if (mode === 'translation') return { primary: tr, secondary: null };
  return { primary: original, secondary: tr };
}
