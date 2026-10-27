import { useParams } from 'react-router-dom';
import { Typography } from 'antd';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { socialUpgradeKeys, topicApi } from '@/api/socialUpgrade';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { ListSkeleton } from '@/components/common/PageLoading';
import { LoadMore } from '@/components/common/LoadMore';
import { PostCard } from '@/components/social/PostCard';
import type { Id, PageResult } from '@/types/api';
import type { PostVO } from '@/types/socialUpgrade';
import styles from './TopicPage.module.less';

interface TopicPages {
  pages: PageResult<PostVO>[];
  pageParams: number[];
}

/**
 * 话题页（P1）。
 *
 * 路由参数由 React Router 自动解码；这里不再 `decodeURIComponent`，
 * 否则带 `%` 的话题名会被二次解码而 404。
 */
export function TopicPage(): JSX.Element {
  const { name = '' } = useParams<{ name: string }>();
  const queryClient = useQueryClient();

  const query = useInfiniteQuery({
    queryKey: socialUpgradeKeys.topicPosts(name),
    queryFn: ({ pageParam }) => topicApi.posts(name, pageParam, 20),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
    enabled: Boolean(name.trim()),
  });

  const updatePages = (updater: (post: PostVO) => PostVO, removedId?: Id): void => {
    queryClient.setQueryData(socialUpgradeKeys.topicPosts(name), (data: TopicPages | undefined) => {
      if (!data) return data;
      return {
        ...data,
        pages: data.pages.map((page) => ({
          ...page,
          list: page.list
            .filter((post: PostVO) => post.id !== removedId)
            .map((post: PostVO) => (removedId ? post : updater(post))),
        })),
      };
    });
  };

  if (!name.trim()) {
    return (
      <div className="ml-page">
        <EmptyState description="话题不存在" />
      </div>
    );
  }

  const posts = query.data?.pages.flatMap((page) => page.list) ?? [];
  const total = query.data?.pages[0]?.total ?? 0;

  return (
    <div className={`ml-page ${styles.page}`}>
      <header className={styles.head}>
        <Typography.Title level={3} className={styles.title}>#{name}</Typography.Title>
        <Typography.Text type="secondary">{total > 0 ? `${total} 条动态` : '还没有人参与'}</Typography.Text>
      </header>

      {query.isLoading ? (
        <ListSkeleton rows={4} />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : posts.length === 0 ? (
        <EmptyState description="这个话题还没有动态，去发布第一条吧" />
      ) : (
        <div className={styles.list}>
          {posts.map((post) => (
            <PostCard
              key={post.id}
              post={post}
              onChange={(next) => updatePages((item) => (item.id === next.id ? next : item))}
              onDeleted={(id) => updatePages((item) => item, id)}
            />
          ))}
          <LoadMore
            hasNext={Boolean(query.hasNextPage)}
            loading={query.isFetchingNextPage}
            onLoadMore={() => void query.fetchNextPage()}
            total={total}
          />
        </div>
      )}
    </div>
  );
}
