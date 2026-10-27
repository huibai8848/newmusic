/**
 * LRC 解析（模块 07 D-05，PRD FR-16-007）。
 *
 * 纯函数、无 React / DOM 依赖：解析逻辑与渲染解耦，方便单测与在 Node 里跑。
 *
 * 支持：
 * - 元信息标签 `[ti:] [ar:] [al:] [by:] [offset:] [length:]`
 * - 一行多个时间标签 `[01:02.00][02:15.00]同一句`
 * - 毫秒分隔符 `.` 或 `:`，1~3 位毫秒（`[00:12.5]` 要当 500ms 而不是 5ms）
 * - 逐字标签 `<00:12.80>` 会被剥离（逐字渲染属 T718，本期只保留行级时间轴）
 * - 时间戳乱序 → 解析后排序
 * - 脏数据 → 逐行 try/catch 跳过，不因一行异常丢掉整首歌词
 */

/** 时间标签：分:秒[.:毫秒]，分支持 1~3 位（有的歌词库写 `[100:00.00]`） */
const TIME_TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;

/** 元信息标签：只认白名单，避免把 `[00:12.00]` 误判成 `key:value` 形式 */
const META_TAG = /^\[(ti|ar|al|by|offset|length|re|ve):([\s\S]*)\]$/i;

/** 逐字标签（本次只剥离，不解析） */
const WORD_TAG = /<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g;

export interface LyricMeta {
  title?: string;
  artist?: string;
  album?: string;
  by?: string;
  /** 文本内 `[offset:]` 的值（毫秒）；正值表示歌词提前（PRD FR-16-007 的 offset 语义） */
  offsetMs: number;
}

/**
 * 正文 `[offset:]` 标签与传入偏移的关系。
 *
 * - `add`（默认）：`总偏移 = 标签 + extraOffsetMs`。用于叠加**用户校准**（BR-16-069 第二项）。
 * - `override`：`总偏移 = extraOffsetMs`，正文标签被忽略。用于让 `t_lyric.offset_ms`
 *   这份「服务端校准」**替换**文件自带的 offset，而不是与之相加。
 */
export type TagOffsetMode = 'add' | 'override';

export interface LyricLine {
  /** 生效时间（秒，已扣除 offset）。**跳转/高亮一律用这个值** */
  time: number;
  /** 标签上的原始时间（秒，未扣除 offset），仅用于调试与断言 */
  rawTime: number;
  text: string;
}

export interface ParsedLyric {
  meta: LyricMeta;
  /** 按时间升序 */
  lines: LyricLine[];
  /** 是否含时间轴。false 表示只能按纯文本展示（contentType=3 或解析不出时间标签） */
  timed: boolean;
  /** 含逐字标签（本期不渲染，仅用于给出提示） */
  hasWordLevel: boolean;
  /** 被跳过的脏行数（用于日志/排查） */
  skippedLines: number;
}

const EMPTY: ParsedLyric = {
  meta: { offsetMs: 0 },
  lines: [],
  timed: false,
  hasWordLevel: false,
  skippedLines: 0,
};

/** `[mm:ss.xxx]` / `[mm:ss:xxx]` 的三段 → 秒 */
function toSeconds(minute: string, second: string, fraction?: string): number {
  const m = Number.parseInt(minute, 10);
  const s = Number.parseInt(second, 10);
  // `[00:12.5]` 的 5 是 500ms：用右填充而不是直接转数字
  const ms = fraction ? Number.parseInt(fraction.padEnd(3, '0'), 10) : 0;
  return m * 60 + s + ms / 1000;
}

/**
 * 解析 LRC。
 *
 * @param raw LRC 原文
 * @param extraOffsetMs 额外偏移（毫秒）
 * @param tagMode 见 {@link TagOffsetMode}。默认 `add`
 */
export function parseLrc(
  raw: string | null | undefined,
  extraOffsetMs = 0,
  tagMode: TagOffsetMode = 'add',
): ParsedLyric {
  if (!raw || raw.trim().length === 0) {
    return { ...EMPTY, meta: { offsetMs: 0 } };
  }

  const meta: LyricMeta = { offsetMs: 0 };
  const rawLines: Array<{ time: number; text: string }> = [];
  let hasWordLevel = false;
  let skipped = 0;

  for (const rawLine of raw.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;

    try {
      const metaMatch = line.match(META_TAG);
      if (metaMatch) {
        const key = metaMatch[1].toLowerCase();
        const value = metaMatch[2].trim();
        if (key === 'offset') {
          const n = Number.parseInt(value, 10);
          meta.offsetMs = Number.isFinite(n) ? n : 0;
        } else if (key === 'ti') meta.title = value;
        else if (key === 'ar') meta.artist = value;
        else if (key === 'al') meta.album = value;
        else if (key === 'by') meta.by = value;
        continue;
      }

      if (line.includes('<') && WORD_TAG.test(line)) {
        hasWordLevel = true;
        WORD_TAG.lastIndex = 0;
      }

      // 一行可能有多个时间标签，逐个展开
      TIME_TAG.lastIndex = 0;
      const tags = [...line.matchAll(TIME_TAG)];
      if (tags.length === 0) {
        // 没有时间轴的纯文本行：内容非空才留，作为"无时间轴歌词"的兜底
        if (!line.startsWith('[')) {
          rawLines.push({ time: -1, text: line.replace(WORD_TAG, '') });
        }
        continue;
      }

      const text = line.replace(TIME_TAG, '').replace(WORD_TAG, '').trim();
      if (!text) continue;
      for (const tag of tags) {
        rawLines.push({ time: toSeconds(tag[1], tag[2], tag[3]), text });
      }
    } catch {
      // EX-16-007-3：单行脏数据只跳过该行，不放弃整首
      skipped += 1;
    }
  }

  // 全部没有时间轴 → 纯文本模式（contentType=3）
  const timedLines = rawLines.filter((l) => l.time >= 0);
  if (timedLines.length === 0) {
    return {
      meta,
      lines: rawLines.map((l) => ({ time: 0, rawTime: 0, text: l.text })),
      timed: false,
      hasWordLevel,
      skippedLines: skipped,
    };
  }

  // 统一转成"提前量"（正值 = 歌词提前）。
  // override 模式下正文标签被丢弃，只认传入值——见 TagOffsetMode 与 useLyric 的组合规则。
  const totalOffsetMs = tagMode === 'override' ? extraOffsetMs : meta.offsetMs + extraOffsetMs;
  const advanceSec = totalOffsetMs / 1000;

  const lines = timedLines
    .map((l) => ({ time: l.time - advanceSec, rawTime: l.time, text: l.text }))
    .sort((a, b) => a.time - b.time || a.rawTime - b.rawTime);

  return { meta, lines, timed: true, hasWordLevel, skippedLines: skipped };
}

/**
 * 二分查找当前行（BR-16-054：禁止线性扫描，每帧都会调用）。
 *
 * @returns 当前行下标；`-1` 表示还在前奏（第一行之前）
 */
export function findCurrentLine(lines: readonly LyricLine[], currentTime: number): number {
  let lo = 0;
  let hi = lines.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].time <= currentTime) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/**
 * 取歌词的纯文本（无时间轴时用于详情页预览）。
 *
 * 模块 02 曾在页面里内联过一份同样的正则，这里统一收口，避免两处正则漂移。
 */
export function plainLyric(raw: string | null | undefined): string[] {
  return parseLrc(raw).lines.map((l) => l.text);
}
