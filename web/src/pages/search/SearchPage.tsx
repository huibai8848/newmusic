import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, AutoComplete, Button, Input, Select, Space, Tabs, Tag, Tooltip } from 'antd';
import { CloseOutlined, FireOutlined, HistoryOutlined, SearchOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  searchApi,
  searchKeys,
  pickList,
  type DurationBucket,
  type SearchAlbumVO,
  type SearchArtistVO,
  type SearchCategory,
  type SearchPlaylistVO,
  type SearchResultVO,
  type SearchSort,
  type SearchSongVO,
  type SearchType,
} from '@/api/search';
import {
  buildParams,
  buildQuery,
  readState,
  FILTER_TYPES,
  type SearchState,
} from './searchUrlState';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useUserStore } from '@/stores/userStore';
import { usePlayerStore } from '@/stores/playerStore';
import { track } from '@/services/track';
import { LoadMore } from '@/components/common/LoadMore';
import { ListSkeleton, CardSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { HighlightText } from '@/components/common/HighlightText';
import { SongTable } from '@/components/business/SongTable';
import { ArtistCard } from '@/components/business/ArtistCard';
import { AlbumCard } from '@/components/business/AlbumCard';
import { PlaylistCard } from '@/components/business/PlaylistCard';
import styles from './SearchPage.module.less';

/**
 * 搜索页（模块 06 T614 / T625）。
 *
 * 取代模块 02 的占位实现：那一版是「把 keyword 透传给曲库的 CRUD 列表接口」，
 * 四个 Tab 就是四次 `LIKE` 查询，既没有相关性排序也没有拼音/联想。
 * 现在改走 `/api/v1/search`（后端 `MysqlSearchEngine`，D-33），
 * 相关性、拼音、筛选、热搜、历史都由后端算好。
 *
 * **URL 是唯一事实来源**：关键词、Tab、筛选、排序全在 query string 上（AC-15-006-4）。
 * 好处有三个——刷新不丢状态、可直接分享、浏览器前进/后退可用；
 * 代价是每次改条件都要走一轮 `setSearchParams`，所以下面把「改 URL」收敛成一个 `patch`。
 */

const PAGE_SIZE = 20;

const TYPE_LABEL: Record<SearchType, string> = {
  all: '综合',
  song: '单曲',
  artist: '歌手',
  album: '专辑',
  playlist: '歌单',
  lyric: '歌词',
};

/** 联想下拉里的类型标签颜色，与 Tab 顺序无关，只为区分 */
const TYPE_TAG_COLOR: Record<SearchCategory, string> = {
  song: 'blue',
  artist: 'magenta',
  album: 'cyan',
  playlist: 'green',
  lyric: 'gold',
};

/** 热搜词的角标（BR-15-033） */
const MARK_COLOR: Record<string, string> = { 爆: 'red', 热: 'volcano', 新: 'blue' };

/** §4.4 的固定取值 */
const ERA_OPTIONS = [
  { value: '80s', label: '80 年代' },
  { value: '90s', label: '90 年代' },
  { value: '00s', label: '00 年代' },
  { value: '10s', label: '10 年代' },
  { value: '20s', label: '20 年代' },
];

const DURATION_OPTIONS: { value: DurationBucket; label: string }[] = [
  { value: 'lt3', label: '3 分钟以下' },
  { value: '3to5', label: '3 - 5 分钟' },
  { value: 'gt5', label: '5 分钟以上' },
];

const SORT_OPTIONS: { value: SearchSort; label: string }[] = [
  { value: 'relevance', label: '相关度' },
  { value: 'hot', label: '热度' },
  { value: 'newest', label: '最新' },
  { value: 'duration_asc', label: '时长从短到长' },
  { value: 'duration_desc', label: '时长从长到短' },
];

/**
 * 语言 / 体裁的候选项。
 *
 * ⚠️ **这是 MVP 的静态词表，不是从后端拉的**：`t_song.language` / `t_song.genre`
 * 是自由文本列，没有字典表也没有 facet 接口，所以拿不到「库里实际有哪些值」。
 * 先给一份本产品通用的固定词表（已覆盖演示数据里的 国语 / 流行 / 摇滚），
 * 待后台补 facet 接口或字典表后替换。已登记在 §9 的偏差表。
 * 取值照旧以 URL 为准——即使用户通过链接带进来一个不在词表里的值，后端照样能筛。
 */
const LANGUAGE_OPTIONS = ['国语', '粤语', '英语', '日语', '韩语', '纯音乐'].map((v) => ({ value: v, label: v }));
const GENRE_OPTIONS = ['流行', '摇滚', '民谣', '电子', '嘻哈', '古典', '爵士'].map((v) => ({ value: v, label: v }));

// ================================================================ 检索 Hook

/**
 * 分类 Tab 的分页检索。
 *
 * 用**游标**而不是页码驱动「加载更多」：后端只要 `hasMore` 就在响应里给 `cursor`
 * （§5.2、BR-15-010），而页码分页从第 1000 条起会被后端用 `70005` 拒掉（D-40）。
 * 所以游标是唯一能一路翻到底的方式，`pageNum` 只用来给「第几页」计数。
 *
 * `total` 在游标页是**不返回**的（后端不跑 COUNT，`SearchPage.total` 是可空的），
 * 所以只取第一页的 `total`；后续页沿用第一页的值，而不是把它当成 0。
 */
function useCategorySearch(state: SearchState, enabled: boolean) {
  // 发往后端的参数（含「筛选只发给歌曲类 Tab」这条规则）由 `buildQuery` 统一决定，
  // 它同时是 queryKey 的来源——键和请求参数从同一个函数出，不会各写一份而走偏
  const queryParams = useMemo(() => buildQuery(state), [state]);

  const query = useInfiniteQuery({
    queryKey: searchKeys.query({ ...queryParams, pageSize: PAGE_SIZE }),
    enabled,
    initialPageParam: { pageNum: 1, cursor: undefined as string | undefined },
    queryFn: ({ pageParam }) =>
      searchApi.query({
        ...queryParams,
        pageSize: PAGE_SIZE,
        pageNum: pageParam.pageNum,
        cursor: pageParam.cursor,
      }),
    getNextPageParam: (last, pages) =>
      last.hasMore ? { pageNum: (last.pageNum ?? pages.length) + 1, cursor: last.cursor } : undefined,
  });

  const pages = query.data?.pages ?? [];
  const first = pages[0];

  return {
    pages,
    /** 综合 Tab 只有一页，取第一页即可 */
    result: first,
    items: pickList<unknown>(first),
    total: first?.total,
    hasMore: Boolean(query.hasNextPage),
    isFirstLoading: query.isLoading,
    isFetchingMore: query.isFetchingNextPage,
    isError: query.isError,
    error: query.error,
    loadMore: () => {
      if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
    },
    reload: () => void query.refetch(),
  };
}

// ================================================================ 综合 Tab

interface SegmentProps<T> {
  title: string;
  total: number;
  items: T[];
  onSeeAll: () => void;
  render: (items: T[]) => JSX.Element;
}

/** 综合 Tab 的一个分段：标题 + 「查看全部」+ 内容（配额由后端给，前端不再截断） */
function Segment<T>({ title, total, items, onSeeAll, render }: SegmentProps<T>): JSX.Element | null {
  if (items.length === 0) return null;
  return (
    <section className={styles.segment}>
      <div className={styles.segmentHead}>
        <h3 className={styles.segmentTitle}>
          {title}
          <span className={styles.segmentCount}>共 {total} 条</span>
        </h3>
        <Button type="link" size="small" onClick={onSeeAll}>
          查看全部
        </Button>
      </div>
      {render(items)}
    </section>
  );
}

function AllResults({
  result,
  keyword,
  onSeeAll,
}: {
  result: SearchResultVO;
  keyword: string;
  onSeeAll: (type: SearchCategory) => void;
}): JSX.Element {
  const playList = usePlayerStore((s) => s.playList);

  const songs = result.songs?.list ?? [];
  const artists = result.artists?.list ?? [];
  const albums = result.albums?.list ?? [];
  const playlists = result.playlists?.list ?? [];

  const nothing = songs.length + artists.length + albums.length + playlists.length === 0;
  if (nothing) return <NoResult result={result} />;

  return (
    <>
      <Segment
        title="歌曲"
        total={result.songs?.total ?? songs.length}
        items={songs}
        onSeeAll={() => onSeeAll('song')}
        render={(items) => (
          <SongTable
            songs={items}
            keyword={keyword}
            onPlay={(_song, index) => {
              track('search_result_click', {
                keyword: keyword.trim().slice(0, 100),
                target_type: 'song',
                target_id: String(items[index]?.id ?? ''),
                position: index,
                action: 'play',
              });
              playList(items, index, 'search');
            }}
          />
        )}
      />
      <Segment
        title="歌手"
        total={result.artists?.total ?? artists.length}
        items={artists}
        onSeeAll={() => onSeeAll('artist')}
        render={(items) => (
          <div className={styles.artistGrid}>
            {items.map((a) => (
              <ArtistCard key={a.id} artist={a} keyword={keyword} />
            ))}
          </div>
        )}
      />
      <Segment
        title="专辑"
        total={result.albums?.total ?? albums.length}
        items={albums}
        onSeeAll={() => onSeeAll('album')}
        render={(items) => (
          <div className={styles.grid}>
            {items.map((a) => (
              <AlbumCard key={a.id} album={a} keyword={keyword} />
            ))}
          </div>
        )}
      />
      <Segment
        title="歌单"
        total={result.playlists?.total ?? playlists.length}
        items={playlists}
        onSeeAll={() => onSeeAll('playlist')}
        render={(items) => (
          <div className={styles.grid}>
            {items.map((p) => (
              <PlaylistCard key={p.id} playlist={p} />
            ))}
          </div>
        )}
      />
    </>
  );
}

// ================================================================ 分类 Tab

function CategoryResults({
  type,
  keyword,
  search,
}: {
  type: SearchCategory;
  keyword: string;
  search: ReturnType<typeof useCategorySearch>;
}): JSX.Element {
  const playList = usePlayerStore((s) => s.playList);

  if (search.isFirstLoading) {
    return type === 'song' || type === 'lyric' ? <ListSkeleton rows={5} /> : <CardSkeleton cards={5} />;
  }
  if (search.isError) return <ErrorState compact error={search.error} onRetry={search.reload} />;
  if (search.items.length === 0) return <NoResult result={search.result} />;

  const footer = (
    <LoadMore
      hasNext={search.hasMore}
      loading={search.isFetchingMore}
      onLoadMore={search.loadMore}
      total={search.total}
    />
  );

  if (type === 'song' || type === 'lyric') {
    const songs = search.items as unknown as SearchSongVO[];
    return (
      <>
        {type === 'lyric' ? (
          <div className={styles.hint}>按歌词内容匹配，命中片段见「歌词」列</div>
        ) : null}
        <SongTable
          songs={songs}
          keyword={keyword}
          showLyric={type === 'lyric'}
          onPlay={(_song, index) => {
            track('search_result_click', {
              keyword: keyword.trim().slice(0, 100),
              target_type: 'song',
              target_id: String(songs[index]?.id ?? ''),
              position: index,
              action: 'play',
            });
            playList(songs, index, 'search');
          }}
        />
        {footer}
      </>
    );
  }

  if (type === 'artist') {
    const items = search.items as unknown as SearchArtistVO[];
    return (
      <>
        <div className={styles.artistGrid}>
          {items.map((a) => (
            <ArtistCard key={a.id} artist={a} keyword={keyword} />
          ))}
        </div>
        {footer}
      </>
    );
  }

  if (type === 'album') {
    const items = search.items as unknown as SearchAlbumVO[];
    return (
      <>
        <div className={styles.grid}>
          {items.map((a) => (
            <AlbumCard key={a.id} album={a} keyword={keyword} />
          ))}
        </div>
        {footer}
      </>
    );
  }

  const items = search.items as unknown as SearchPlaylistVO[];
  return (
    <>
      <div className={styles.grid}>
        {items.map((p) => (
          <PlaylistCard key={p.id} playlist={p} />
        ))}
      </div>
      {footer}
    </>
  );
}

/** 无结果：**不是错误**（D-41），给推荐词当台阶 */
function NoResult({ result }: { result?: SearchResultVO }): JSX.Element {
  const words = result?.suggestWords ?? [];
  return (
    <EmptyState
      description={
        words.length > 0 ? (
          <Space direction="vertical" size={8}>
            <span>没有找到相关内容，换个词试试：</span>
            <Space wrap>
              {words.map((w) => (
                <Tag key={w} color="blue">
                  {w}
                </Tag>
              ))}
            </Space>
          </Space>
        ) : (
          '没有找到相关内容'
        )
      }
    />
  );
}

// ================================================================ 热搜 / 历史（T625）

/**
 * 热搜榜（`GET /search/hot`）。
 *
 * 后端给的是 **5 分钟快照**（BR-15-032），所以 `refreshedAt` 是快照生成时间而不是当前时间，
 * 直接展示它比展示「刚刚」更诚实。`isTop` 的置顶词由后端排在最前，前端不再排序。
 */
function HotBoard({ onPick }: { onPick: (word: string) => void }): JSX.Element {
  const query = useInfiniteQuery({
    queryKey: searchKeys.hot(10),
    initialPageParam: undefined,
    queryFn: () => searchApi.hot(10),
    getNextPageParam: () => undefined,
    staleTime: 5 * 60_000,
  });

  const data = query.data?.pages?.[0];
  const words = data?.words ?? [];

  if (query.isLoading) return <CardSkeleton cards={3} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  if (words.length === 0) return <EmptyState description="热搜榜暂时是空的" />;

  return (
    <section className={styles.board}>
      <div className={styles.segmentHead}>
        <h3 className={styles.segmentTitle}>
          <FireOutlined className={styles.boardIcon} />
          热搜榜
          {data?.refreshedAt ? <span className={styles.segmentCount}>{data.refreshedAt} 更新</span> : null}
        </h3>
      </div>
      <ol className={styles.hotList}>
        {words.map((w) => (
          <li key={`${w.rank}:${w.word}`} className={styles.hotItem}>
            <button type="button" className={styles.hotWord} onClick={() => onPick(w.word)}>
              <span className={`${styles.hotRank} ${w.rank <= 3 ? styles.hotRankTop : ''}`}>{w.rank}</span>
              <span className={styles.hotText}>{w.word}</span>
              {w.isTop ? <Tag color="gold">置顶</Tag> : null}
              {w.mark ? <Tag color={MARK_COLOR[w.mark] ?? 'default'}>{w.mark}</Tag> : null}
            </button>
          </li>
        ))}
      </ol>
    </section>
  );
}

/**
 * 搜索历史（`GET /search/history`，需登录）。
 *
 * 历史数据不从独立接口取，而是用空关键词那次搜索响应里内嵌的 `history`
 * （后端 BR-15-001 就是这么设计的）——省一次请求，也不会出现
 * 「榜单已更新但历史还是旧的」这种两份数据不同步。
 * 删除后失效 `search` 整个命名空间即可。
 */
function HistoryPanel({
  words,
  onPick,
  onRemove,
  onClear,
  busy,
}: {
  words: string[];
  onPick: (word: string) => void;
  onRemove: (word: string) => void;
  onClear: () => void;
  busy: boolean;
}): JSX.Element | null {
  if (words.length === 0) return null;
  return (
    <section className={styles.board}>
      <div className={styles.segmentHead}>
        <h3 className={styles.segmentTitle}>
          <HistoryOutlined className={styles.boardIcon} />
          搜索历史
        </h3>
        <Button type="link" size="small" disabled={busy} onClick={onClear}>
          清空
        </Button>
      </div>
      <div className={styles.historyList}>
        {words.map((w) => (
          <span key={w} className={styles.historyItem}>
            <button type="button" className={styles.historyWord} onClick={() => onPick(w)}>
              {w}
            </button>
            <Tooltip title="删除这条">
              <CloseOutlined
                className={styles.historyRemove}
                onClick={(e) => {
                  e.stopPropagation();
                  onRemove(w);
                }}
              />
            </Tooltip>
          </span>
        ))}
      </div>
    </section>
  );
}

// ================================================================ 筛选栏

function FilterBar({
  state,
  patch,
}: {
  state: SearchState;
  patch: (next: Partial<SearchState>, push?: boolean) => void;
}): JSX.Element {
  return (
    <div className={styles.filterBar}>
      <Space wrap size={8}>
        <Select
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          placeholder="语言"
          aria-label="语言筛选"
          className={styles.filterSelect}
          value={state.language}
          options={LANGUAGE_OPTIONS}
          onChange={(v: string[]) => patch({ language: v })}
        />
        <Select
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          placeholder="体裁"
          aria-label="体裁筛选"
          className={styles.filterSelect}
          value={state.genre}
          options={GENRE_OPTIONS}
          onChange={(v: string[]) => patch({ genre: v })}
        />
        <Select
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          placeholder="年代"
          aria-label="年代筛选"
          className={styles.filterSelect}
          value={state.era}
          options={ERA_OPTIONS}
          onChange={(v: string[]) => patch({ era: v })}
        />
        <Select
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          placeholder="时长"
          aria-label="时长筛选"
          className={styles.filterSelect}
          value={state.duration}
          options={DURATION_OPTIONS}
          onChange={(v: string[]) => patch({ duration: v })}
        />
        <Select
          aria-label="排序方式"
          className={styles.sortSelect}
          value={state.sort}
          options={SORT_OPTIONS}
          onChange={(v: SearchSort) => patch({ sort: v })}
        />
      </Space>
      {/* 这几个维度都是 t_song 的列，非歌曲 Tab 上它们不生效——把这件事说出来，
          而不是让用户调了半天发现没反应（后端实测：artist/album/playlist 的.total 不随筛选变化） */}
      {state.type === 'all' ? (
        <span className={styles.filterHint}>筛选与排序仅作用于歌曲</span>
      ) : null}
    </div>
  );
}

// ================================================================ 页面

export function SearchPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams();
  const state = useMemo(() => readState(searchParams), [searchParams]);
  const { keyword, type } = state;

  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);

  // 输入框是**受控的本地状态**，与 URL 单向同步：URL 变了输入框跟随（前进/后退、外链），
  // 输入框变了等防抖再写回 URL。反过来（输入框直接读 URL）会让中文输入法组字被打断。
  const [input, setInputState] = useState(keyword);
  // 「这次 input 变化是用户敲的」——只有敲出来的值才允许防抖写回 URL。
  // 热搜词/历史词/联想项/前进后退都会改 URL 并让 input 跟随，那不是输入，不能反过来覆盖 URL。
  const typingRef = useRef(false);
  const setInput = useCallback((value: string) => {
    typingRef.current = true;
    setInputState(value);
  }, []);
  useEffect(() => {
    setInputState(keyword);
  }, [keyword]);

  const patch = useCallback(
    (next: Partial<SearchState>, push = false) => {
      // 序列化统一走 `buildParams`：默认值不落 URL，所以「干净的搜索页」是 `/search` 而不是
      // `/search?type=all&sort=relevance&language=`。这条也是 AC-15-006-4 的另一半——
      // 写进去的能被 `readState` 原样读回来。
      const merged: SearchState = { ...state, ...next };
      // 关键词/Tab 变化是一次「新搜索」，进历史栈（后退能回上一个结果）；
      // 筛选/排序是同一次搜索的细化，用 replace 免得后退键要按十几次
      setSearchParams(buildParams(merged), { replace: !push });
    },
    [state, setSearchParams],
  );

  // 停止输入 300ms 后自动搜索（BR-11-020）。
  //
  // 两个前置判断缺一不可：
  // 1. `debouncedInput` 必须已经追平输入框当前值——否则说明这是**旧防抖值**。
  //    点热搜词/历史词/联想项时，URL 先变、`input` 由 `[keyword]` 的 effect 同步成新词，
  //    而 `debouncedInput` 还停在上一次输入（常常是空串），此时回写会把刚设的关键词清掉，
  //    表现为「点了热搜不跳转」（URL 被 push 一次又立刻被 replace 回去）。
  // 2. 真的和 URL 里的关键词不同才写：避免同值写一次无意义的历史记录。
  const debouncedInput = useDebouncedValue(input.trim(), 300);
  useEffect(() => {
    if (!typingRef.current) return;
    // 防抖值还没追平输入框：说明用户还在打字（或者输入框刚被外部改过），不写 URL
    if (debouncedInput !== input.trim()) return;
    typingRef.current = false;
    if (debouncedInput === keyword.trim()) return;
    patch({ keyword: debouncedInput }, true);
  }, [debouncedInput, input, keyword, patch]);

  const hasKeyword = keyword.trim().length > 0;
  const isAll = type === 'all';

  // ---- 联想：只在有输入时查。防抖直接复用 `debouncedInput`——它本来就是
  // 「输入停止 300ms 后的值」，再叠一层防抖只会让联想比搜索还慢 ----
  const suggestEnabled = hasKeyword && debouncedInput.length >= 1;

  const suggestQuery = useInfiniteQuery({
    queryKey: searchKeys.suggest(debouncedInput),
    initialPageParam: undefined,
    enabled: suggestEnabled,
    queryFn: () => searchApi.suggest(debouncedInput, 10),
    getNextPageParam: () => undefined,
    staleTime: 5 * 60_000,
  });

  const suggestOptions = useMemo(() => {
    const items = suggestQuery.data?.pages?.[0]?.suggestions ?? [];
    return items.map((s) => ({
      // value 可能重名（同名歌曲与歌手），所以显式给 key
      key: `${s.type}:${s.id ?? s.text}`,
      value: s.text,
      label: (
        <div className={styles.suggestItem}>
          <Tag color={TYPE_TAG_COLOR[s.type] ?? 'default'}>{TYPE_LABEL[s.type]}</Tag>
          <span className={styles.suggestText}>
            <HighlightText text={s.text} keyword={debouncedInput} />
          </span>
          {s.extra ? <span className={styles.suggestExtra}>{s.extra}</span> : null}
        </div>
      ),
    }));
  }, [suggestQuery.data, debouncedInput]);

  // ---- 检索 ----
  const search = useCategorySearch(state, hasKeyword);

  // ---- 历史的增删 ----
  const invalidateSearch = () => void queryClient.invalidateQueries({ queryKey: searchKeys.all });
  const removeHistory = useMutation({
    mutationFn: (word: string) => searchApi.removeHistory(word),
    onSuccess: invalidateSearch,
  });
  const clearHistory = useMutation({
    mutationFn: () => searchApi.clearHistory(),
    onSuccess: invalidateSearch,
  });

  const commit = useCallback(
    (nextKeyword: string, nextType: SearchType = type) => {
      patch({ keyword: nextKeyword, type: nextType }, true);
    },
    [patch, type],
  );

  // 模块 17：一次「新关键词」算一次搜索提交；切换 Tab/筛选不重复计
  useEffect(() => {
    const word = keyword.trim();
    if (!word) return;
    track('search_submit', { keyword: word.slice(0, 100), search_type: type, source: 'web' });
  }, [keyword]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 结果点击：链接在子组件里，用捕获阶段统一识别，避免每个卡片各改一遍 */
  const onResultClick = (event: MouseEvent<HTMLDivElement>): void => {
    const anchor = (event.target as HTMLElement).closest?.('a[href]');
    const href = anchor?.getAttribute('href') ?? '';
    const matched = /^\/(song|artist|album|playlist)\/(\d+)/.exec(href);
    if (!matched) return;
    track('search_result_click', {
      keyword: keyword.trim().slice(0, 100),
      target_type: matched[1],
      target_id: matched[2],
      position: null,
    });
  };

  const history = isLogin ? (search.result?.history ?? []) : [];

  // ---- 空关键词：热搜 + 历史（BR-15-001）----
  if (!hasKeyword) {
    return (
      <div className="ml-page">
        <SearchBar
          input={input}
          setInput={setInput}
          options={suggestOptions}
          onCommit={commit}
          onSelect={(v) => commit(v)}
          loading={suggestQuery.isFetching}
        />
        <div className={styles.guide}>
          <HotBoard onPick={(w) => commit(w)} />
          <HistoryPanel
            words={history}
            onPick={(w) => commit(w)}
            onRemove={(w) => removeHistory.mutate(w)}
            onClear={() => clearHistory.mutate()}
            busy={removeHistory.isPending || clearHistory.isPending}
          />
          {!isLogin ? <div className={styles.hint}>登录后可保存搜索历史</div> : null}
        </div>
      </div>
    );
  }

  const items = (Object.keys(TYPE_LABEL) as SearchType[]).map((key) => ({
    key,
    label: TYPE_LABEL[key],
    children: null, // 内容统一在 Tabs 外面渲染，见下
  }));

  return (
    <div className="ml-page">
      <SearchBar
        input={input}
        setInput={setInput}
        options={suggestOptions}
        onCommit={commit}
        onSelect={(v) => commit(v)}
        loading={suggestQuery.isFetching}
      />

      {FILTER_TYPES.includes(type) ? <FilterBar state={state} patch={patch} /> : null}

      <Tabs
        activeKey={type}
        onChange={(key) => patch({ type: key as SearchType }, true)}
        items={items}
        className={styles.tabs}
      />

      {search.result?.degraded || search.result?.notice ? (
        <Alert
          type="warning"
          showIcon
          className={styles.notice}
          message={search.result.notice ?? '当前使用的是降级检索通道，结果可能不完整'}
        />
      ) : null}

      <div onClickCapture={onResultClick}>
        {isAll ? (
          search.isFirstLoading ? (
            <CardSkeleton cards={3} />
          ) : search.isError ? (
            <ErrorState error={search.error} onRetry={search.reload} />
          ) : search.result ? (
            <AllResults result={search.result} keyword={keyword} onSeeAll={(t) => patch({ type: t }, true)} />
          ) : null
        ) : (
          <CategoryResults type={type as SearchCategory} keyword={keyword} search={search} />
        )}
      </div>

      {search.result?.costMs != null ? (
        <div className={styles.cost}>
          耗时 {search.result.costMs} ms
          {search.total != null ? ` · 共 ${search.total} 条` : ''}
        </div>
      ) : null}
    </div>
  );
}

// ================================================================ 搜索框

function SearchBar({
  input,
  setInput,
  options,
  onCommit,
  onSelect,
  loading,
}: {
  input: string;
  setInput: (v: string) => void;
  options: { key: string; value: string; label: JSX.Element }[];
  onCommit: (keyword: string, type?: SearchType) => void;
  onSelect: (value: string) => void;
  loading: boolean;
}): JSX.Element {
  return (
    <div className={styles.searchBar}>
      {/* `value`/`onChange` 只挂在 AutoComplete 上，**不再重复挂到子输入框**：
          AutoComplete 会 cloneElement 子元素并注入这两个 prop，两边都写就是同一份状态
          被两个受控源接管——行为取决于 antd 内部注入顺序，属于能跑但说不清的类型。
          子元素只保留 AutoComplete 不会注入的东西：`onSearch`（回车/按钮立即提交）
          与展示相关的 `enterButton`/`prefix`。 */}
      <AutoComplete
        value={input}
        options={options}
        onChange={setInput}
        onSelect={onSelect}
        allowClear
        style={{ width: '100%' }}
        // 选中联想词时不要把剩余部分补全到输入框里，直接提交更符合搜索习惯
        backfill={false}
      >
        <Input.Search
          size="large"
          onSearch={(value) => onCommit(value)}
          placeholder="搜索歌曲、歌手、专辑、歌单、歌词"
          prefix={<SearchOutlined style={{ color: 'var(--color-text-secondary)' }} />}
          aria-label="搜索"
          enterButton="搜索"
          loading={loading}
        />
      </AutoComplete>
    </div>
  );
}
