import { api } from '@/api/request';
import type { Id, PageResult } from '@/types/api';

/**
 * 搜索（模块 06）。
 *
 * 与 `song.ts` 那批「CRUD 客户端」的区别：搜索**不复用曲库的实体类型**，
 * 而是照搬后端 `module/search/vo` 下的 VO。原因是搜索结果带的
 * `score` / `matchedFields` / `highlight` 是「本次查询的产物」，不是歌曲/歌手的属性——
 * 用 `Song` 去接会逼着后端把这些字段塞进通用实体，或者前端 `as any` 绕过去。
 */

/** 与后端 `SearchQueryReq.type` 一致；未知值后端会回退成 `all` */
export type SearchType = 'all' | 'song' | 'artist' | 'album' | 'playlist' | 'lyric';

/** `type` 里除 `all` 之外的分支——即「分类 Tab」 */
export type SearchCategory = Exclude<SearchType, 'all'>;

/** 与后端 `SearchQueryReq.sort` 一致（§4.3） */
export type SearchSort = 'relevance' | 'hot' | 'newest' | 'duration_asc' | 'duration_desc';

/** 时长分档（§4.4）；后端认的是 `lt3`/`3to5`/`gt5` 而不是秒数 */
export type DurationBucket = 'lt3' | '3to5' | 'gt5';

/**
 * 命中字段。取值来自后端 `SearchHit.Field` 的
 * `name().toLowerCase().replace('_', '.')`（`SearchServiceImpl#fieldNames`）。
 *
 * 保留字面量联合而不是 `string`：这几个值前端要用来决定「高亮放歌名还是歌手名」，
 * 拼错了不会有编译错误，只会静默不显示——那正是最该被类型挡住的错。
 */
export type SearchField =
  | 'name'
  | 'name.pinyin'
  | 'name.pinyin.first'
  | 'artist.name'
  | 'artist.name.pinyin'
  | 'album.name'
  | 'alias'
  | 'tag'
  | 'lyric';

/**
 * 高亮片段（`<em class="ml-kw">`）。
 *
 * 键**不固定**：歌曲是 `name`/`lyric`，歌单是 `name`，歌手/专辑命中拼音时是 `null`
 * （拼音命中没有可高亮的原文）。所以按「按需取用」的可选字段建模，
 * 而不是假设每个 VO 都有 `name`。
 */
export type SearchHighlight = Partial<Record<'name' | 'lyric' | 'artistName' | 'albumName', string>>;

export interface SearchSongVO {
  id: Id;
  name: string;
  artistId: Id;
  artistName: string;
  albumId: Id | null;
  albumName: string | null;
  cover: string | null;
  duration: number | null;
  language: string | null;
  genre: string | null;
  era: string | null;
  hotScore: number;
  score: number;
  matchedFields: SearchField[];
  highlight: SearchHighlight | null;
  /** 0未授权 1已授权 2已过期 3受限 */
  copyrightStatus: number;
  playable: boolean;
  /** 命中歌词时的片段（已剥时间轴）；未命中歌词时为 null */
  lyricFragment?: string | null;
}

export interface SearchArtistVO {
  id: Id;
  name: string;
  cover: string | null;
  songCount: number;
  hotScore: number;
  score: number;
  matchedFields: SearchField[];
  highlight: SearchHighlight | null;
}

export interface SearchAlbumVO {
  id: Id;
  name: string;
  artistId: Id;
  artistName: string;
  cover: string | null;
  publishTime: string | null;
  songCount: number;
  hotScore: number;
  score: number;
  matchedFields: SearchField[];
  highlight: SearchHighlight | null;
}

export interface SearchPlaylistVO {
  id: Id;
  name: string;
  cover: string | null;
  songCount: number;
  playCount: number;
  hotScore: number;
  score: number;
  matchedFields: SearchField[];
  highlight: SearchHighlight | null;
}

/** 热搜词（BR-15-034：**不含搜索量**，所以这里没有 count 字段） */
export interface HotWordVO {
  word: string;
  rank: number;
  /** 热 / 新 / 爆 / null */
  mark: string | null;
  isTop: boolean;
}

export interface SuggestItemVO {
  text: string;
  type: SearchCategory;
  id: Id | null;
  /** 歌曲联想项的歌手名等补充说明 */
  extra: string | null;
  /** 真的含 keyword 前缀时才有；拼音命中时为 null */
  highlight: string | null;
}

export interface SearchSuggestVO {
  suggestions: SuggestItemVO[];
  hotWords: string[];
}

export interface HotSearchVO {
  words: HotWordVO[];
  refreshedAt: string;
}

/**
 * 搜索结果（§5.2）。综合 Tab 与分类 Tab 共用——
 * 所以除了 `type` 之外所有字段都是可选的，**「字段存在」就等于「它在这个 Tab 下有值」**
 * （后端 `@JsonInclude(NON_NULL)`）。
 */
export interface SearchResultVO {
  keyword?: string;
  type: SearchType;
  degraded?: boolean;
  costMs?: number;
  /** 分类 Tab：命中总数。**游标页不返回本字段**（后端不跑 COUNT）*/
  total?: number;
  pageNum?: number;
  pageSize?: number;
  hasMore?: boolean;
  /** 分类 Tab：下一页游标（BR-15-010）。无更多数据时不返回 */
  cursor?: string;
  /**
   * 分类 Tab 的当前页结果，**元素类型由 `type` 决定**。
   *
   * 这里刻意是 `unknown[]` 而不是四个可选字段：后端契约就是「一个 list」，
   * 强行拆开会让前端在后端序列化分支和类型收窄之间做无谓的重复劳动。
   * 用 `pickList` 在唯一那个边界上收窄一次。
   */
  list?: unknown[];
  // ---- 综合 Tab 的四个分段（各自独立配额，BR-15-004）----
  songs?: PageResult<SearchSongVO>;
  artists?: PageResult<SearchArtistVO>;
  albums?: PageResult<SearchAlbumVO>;
  playlists?: PageResult<SearchPlaylistVO>;
  /** 无结果时的推荐词（D-41：无结果不是错误，所以给「换个词」的台阶） */
  suggestWords?: string[];
  // ---- 空关键词（BR-15-001）----
  hotWords?: HotWordVO[];
  /** 未登录时后端不返回本字段 */
  history?: string[];
  /** 需要告诉用户的一句话（降级等） */
  notice?: string;
}

export interface SearchQuery {
  keyword?: string;
  type?: SearchType;
  pageNum?: number;
  pageSize?: number;
  cursor?: string;
  /**
   * 筛选：多值逗号分隔（§4.4）。**只对 `type=song`（含综合 Tab 的歌曲段）生效**——
   * 语言/体裁/年代/时长都是歌曲列，歌手/专辑/歌单没有这些属性可筛。
   */
  language?: string;
  genre?: string;
  era?: string;
  duration?: string;
  sort?: SearchSort;
}

/** QueryKey 统一收口，方便失效与调试 */
export const searchKeys = {
  all: ['search'] as const,
  query: (query: SearchQuery) => [...searchKeys.all, 'query', query] as const,
  suggest: (keyword: string) => [...searchKeys.all, 'suggest', keyword] as const,
  hot: (limit: number) => [...searchKeys.all, 'hot', limit] as const,
  history: () => [...searchKeys.all, 'history'] as const,
};

export const searchApi = {
  /**
   * 综合/分类检索。
   *
   * `skipErrorToast`：搜索页自带 `ErrorState`（可重试），再弹一个全局 Toast 是重复告知。
   */
  query: (query: SearchQuery, skipErrorToast = true) =>
    api.get<SearchResultVO>('/search', query, { skipErrorToast }),

  /** 联想。`limit` 后端上限 10（BR-15-015），这里不做本地截断，交给后端 */
  suggest: (keyword: string, limit = 10) =>
    api.get<SearchSuggestVO>('/search/suggest', { keyword, limit }, { skipErrorToast: true }),

  hot: (limit = 10) => api.get<HotSearchVO>('/search/hot', { limit }, { skipErrorToast: true }),

  /** 搜索历史。**需登录**：后端匿名返回 401/40101，调用方要先判登录态 */
  history: () => api.get<string[]>('/search/history', undefined, { skipErrorToast: true }),

  /** 删单条。走查询串而不是 body：后端是 `@RequestParam`，且这样能进 access log */
  removeHistory: (keyword: string) =>
    api.delete<null>('/search/history', undefined, { params: { keyword }, skipErrorToast: true }),

  clearHistory: () => api.delete<null>('/search/history/all', undefined, { skipErrorToast: true }),
};

/**
 * 把分类 Tab 的 `list` 收窄到具体类型。
 *
 * 后端故意用 `List<Object>`（`SearchResultVO` 的注释解释了为什么），
 * 前端就在这一个地方把它换回强类型——只有这一处 `as`，
 * 之后所有组件都拿到真类型。
 */
export function pickList<T>(result: SearchResultVO | undefined): T[] {
  return (result?.list ?? []) as T[];
}

/** 综合 Tab 的四个分段是否都为空（用于「什么都没搜到」的判定） */
export function isEmptyResult(result: SearchResultVO | undefined): boolean {
  if (!result) return true;
  if (result.list) return result.list.length === 0;
  const segments = [result.songs, result.artists, result.albums, result.playlists];
  return segments.every((seg) => (seg?.list?.length ?? 0) === 0);
}
