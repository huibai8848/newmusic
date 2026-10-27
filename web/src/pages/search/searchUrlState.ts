import type { SearchSort, SearchType } from '@/api/search';

/**
 * 搜索页的「URL ↔ 状态」映射（T614）。
 *
 * 单独成文件有两个理由：
 * 1. **可测**：这里全是纯函数（只依赖 `URLSearchParams` 这个全局），
 *    没有任何运行时 import，所以能用 `node scripts/test-search-url.mjs` 直接跑，
 *    不必为了几个字符串处理去引 vitest；
 * 2. **AC-15-006-4 的落点就是它**：筛选条件刷新后还在，等价于
 *    `readState(buildParams(state)) ≈ state`。把这条性质收在一个模块里，
 *    测试断言的就是需求本身，而不是「页面上某个 Select 的 value」。
 *
 * 唯一允许的 `import` 是 `import type`——Node 擦除类型时会整句删掉，
 * 所以别名 `@/` 不会被解析，模块仍然零运行时依赖。
 */

export interface SearchState {
  keyword: string;
  type: SearchType;
  language: string[];
  genre: string[];
  era: string[];
  duration: string[];
  sort: SearchSort;
}

export const DEFAULT_TYPE: SearchType = 'all';
export const DEFAULT_SORT: SearchSort = 'relevance';

const TYPES: SearchType[] = ['all', 'song', 'artist', 'album', 'playlist', 'lyric'];
const SORTS: SearchSort[] = ['relevance', 'hot', 'newest', 'duration_asc', 'duration_desc'];

/**
 * 筛选只对歌曲有意义——语言/体裁/年代/时长都是 `t_song` 的列。
 *
 * 实测确认（模块 06 验收）：给 `type=artist` 加 `genre=摇滚` 前后 `total` 都是 1，
 * 而 `type=song` 会从 6 变 2；综合 Tab 里加 `genre` 只让 `songs` 段从 6 变 2，
 * `albums` 段不动。所以这三个 Tab 之外**不发**这些参数：既省得后端白解析，
 * 也让同一份条件在切换 Tab 时不会无谓地改变 queryKey 而触发一次多余请求。
 */
export const FILTER_TYPES: SearchType[] = ['all', 'song', 'lyric'];

export function splitCsv(raw: string | null | undefined): string[] {
  return (raw ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

/** 多值按后端约定序列化成逗号分隔（§4.4）；空数组返回 `undefined`，让调用方整个省掉这个参数 */
export function joinCsv(values: string[]): string | undefined {
  return values.length > 0 ? values.join(',') : undefined;
}

/** 解析 URL query。**未知值一律回退成默认值**，绝不把脏值透传给后端 */
export function readState(params: URLSearchParams): SearchState {
  const rawType = params.get('type') as SearchType | null;
  const rawSort = params.get('sort') as SearchSort | null;
  return {
    keyword: params.get('keyword') ?? '',
    type: rawType && TYPES.includes(rawType) ? rawType : DEFAULT_TYPE,
    language: splitCsv(params.get('language')),
    genre: splitCsv(params.get('genre')),
    era: splitCsv(params.get('era')),
    duration: splitCsv(params.get('duration')),
    sort: rawSort && SORTS.includes(rawSort) ? rawSort : DEFAULT_SORT,
  };
}

/**
 * 状态 → URL query。**默认值不写进 URL**：`type=all`、`sort=relevance`、
 * 空筛选都不落串，这样「干净的搜索页」就是 `/search`，而不是
 * `/search?type=all&sort=relevance&language=&genre=`。
 */
export function buildParams(state: SearchState): Record<string, string> {
  const params: Record<string, string> = {};
  const keyword = state.keyword.trim();
  if (keyword) params.keyword = keyword;
  if (state.type !== DEFAULT_TYPE) params.type = state.type;
  if (state.sort !== DEFAULT_SORT) params.sort = state.sort;
  const language = joinCsv(state.language);
  const genre = joinCsv(state.genre);
  const era = joinCsv(state.era);
  const duration = joinCsv(state.duration);
  if (language) params.language = language;
  if (genre) params.genre = genre;
  if (era) params.era = era;
  if (duration) params.duration = duration;
  return params;
}

/** 状态 → 后端 `/api/v1/search` 的查询参数（含「筛选只发给歌曲类 Tab」这条规则） */
export function buildQuery(state: SearchState): Record<string, string | number | undefined> {
  const query: Record<string, string | number | undefined> = {
    keyword: state.keyword.trim() || undefined,
    type: state.type,
  };
  if (state.sort !== DEFAULT_SORT) query.sort = state.sort;
  if (FILTER_TYPES.includes(state.type)) {
    query.language = joinCsv(state.language);
    query.genre = joinCsv(state.genre);
    query.era = joinCsv(state.era);
    query.duration = joinCsv(state.duration);
  }
  return query;
}

/** 供测试与调试：把状态渲染成 query string */
export function toQueryString(state: SearchState): string {
  return new URLSearchParams(buildParams(state)).toString();
}
