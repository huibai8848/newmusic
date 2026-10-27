import { useInfiniteQuery, type QueryKey, type UseInfiniteQueryResult } from '@tanstack/react-query';
import type { PageResult } from '@/types/api';

/**
 * 触底加载封装（模块 02 D-11 / PRD BR-11-026）。
 *
 * 翻页依据是后端 `PageResult.hasNext`，与 `pageNum` 组合成游标，
 * 因此前端不需要自己算总页数，`truncated=true` 时由页面提示「已限制 100 条/页」。
 */
export interface InfiniteListOptions<T> {
  queryKey: QueryKey;
  queryFn: (pageNum: number, pageSize: number) => Promise<PageResult<T>>;
  pageSize?: number;
  enabled?: boolean;
}

export interface InfiniteListResult<T> {
  items: T[];
  total: number;
  hasNext: boolean;
  isTruncated: boolean;
  isLoading: boolean;
  isFetchingNextPage: boolean;
  isError: boolean;
  error: unknown;
  loadMore: () => void;
  reload: () => void;
}

export function useInfiniteList<T>({
  queryKey,
  queryFn,
  pageSize = 20,
  enabled = true,
}: InfiniteListOptions<T>): InfiniteListResult<T> {
  const query: UseInfiniteQueryResult<{ pages: PageResult<T>[]; pageParams: number[] }, Error> = useInfiniteQuery({
    queryKey,
    enabled,
    initialPageParam: 1,
    queryFn: ({ pageParam }) => queryFn(pageParam as number, pageSize),
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.pageNum + 1 : undefined),
  });

  const pages = query.data?.pages ?? [];
  const items = pages.flatMap((p) => p.list ?? []);

  return {
    items,
    total: pages[0]?.total ?? 0,
    hasNext: Boolean(pages[pages.length - 1]?.hasNext),
    isTruncated: pages.some((p) => p.truncated),
    isLoading: query.isLoading,
    isFetchingNextPage: query.isFetchingNextPage,
    isError: query.isError,
    error: query.error,
    loadMore: () => {
      if (query.hasNextPage && !query.isFetchingNextPage) {
        void query.fetchNextPage();
      }
    },
    reload: () => {
      void query.refetch();
    },
  };
}
