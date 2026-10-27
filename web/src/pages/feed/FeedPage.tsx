import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, Card, Tag, Tabs, Typography } from 'antd';
import { FireOutlined, ReloadOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { feedApi, socialUpgradeKeys, topicApi } from '@/api/socialUpgrade';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { ListSkeleton } from '@/components/common/PageLoading';
import { LoadMore } from '@/components/common/LoadMore';
import { PostCard } from '@/components/social/PostCard';
import { RecommendUsersCard } from '@/components/social/RecommendUsersCard';
import { useUserStore } from '@/stores/userStore';
import {
  feedCursorParam,
  removePostFromInfiniteData,
  replacePostInInfiniteData,
} from '@/utils/socialRules';
import { topicPath } from '@/utils/socialText';
import type { Id } from '@/types/api';
import type { FeedType, PostVO } from '@/types/socialUpgrade';
import styles from './FeedPage.module.less';

/**
 * 关注流 / 发现流（FR-25-001）。
 *
 * 两条流分页语义不同：
 * - 关注流 cursor 是 `{score}_{postId}`，原样回传；
 * - 发现流 cursor 是曝光偏移量，曝光计数变化会让 offset 不稳定，
 *   因此必须跨页去重，并在 cursor 与上次相同时强制停止，避免死循环。
 *
 * 「N 条新动态」不做实时推送，也不在打开页面时清零——用户点开才算已读。
 */
export function FeedPage(): JSX.Element {
  const [params, setParams] = useSearchParams();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const tab: FeedType = params.get('tab') === 'discover' ? 'discover' : 'following';

  const following = useInfiniteQuery({
    queryKey: socialUpgradeKeys.feed('following'),
    queryFn: ({ pageParam }) => feedApi.following(feedCursorParam(pageParam), 20),
    initialPageParam: '',
    getNextPageParam: (last, allPages) => {
      const cursor = last.nextCursor ?? '';
      if (!last.hasMore || !cursor) return undefined;
      // 防御：后端异常导致 cursor 不前进时，不能无限请求同一页。
      // allPages 包含当前最后一页，必须排除它，否则当前页的 nextCursor 恒等于 cursor。
      if (allPages.slice(0, -1).some((page) => page.nextCursor === cursor)) return undefined;
      return cursor;
    },
    enabled: isLogin && tab === 'following',
  });

  const discover = useInfiniteQuery({
    queryKey: socialUpgradeKeys.feed('discover'),
    queryFn: ({ pageParam }) => feedApi.discover(feedCursorParam(pageParam), 20),
    initialPageParam: '',
    getNextPageParam: (last, allPages) => {
      const cursor = last.nextCursor ?? '';
      if (!last.hasMore || !cursor) return undefined;
      // 发现流 offset 会因曝光计数变化而不稳定，cursor 重复即停
      if (allPages.slice(0, -1).some((page) => page.nextCursor === cursor)) return undefined;
      return cursor;
    },
    enabled: isLogin && tab === 'discover',
  });

  const active = tab === 'following' ? following : discover;
  const key = socialUpgradeKeys.feed(tab);

  const markRead = useMutation({
    mutationFn: feedApi.markRead,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.feed('following') });
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.feed('discover') });
    },
  });

  const hotTopics = useQuery({
    queryKey: socialUpgradeKeys.topicsHot,
    queryFn: () => topicApi.hot(10),
    staleTime: 5 * 60_000,
  });

  const posts = useMemo(() => {
    const seen = new Set<Id>();
    const list: PostVO[] = [];
    for (const page of active.data?.pages ?? []) {
      for (const post of page.list) {
        if (seen.has(post.id)) continue;
        seen.add(post.id);
        list.push(post);
      }
    }
    return list;
  }, [active.data]);

  const newCount = following.data?.pages[0]?.newCount ?? 0;

  const handleChange = (post: PostVO): void => {
    queryClient.setQueryData(key, (data: typeof active.data) => replacePostInInfiniteData(data, post));
  };

  const handleDeleted = (postId: Id): void => {
    queryClient.setQueryData(key, (data: typeof active.data) => removePostFromInfiniteData(data, postId));
  };

  const handleDislike = (postId: Id): void => {
    queryClient.setQueryData(socialUpgradeKeys.feed('discover'), (data: typeof discover.data) =>
      removePostFromInfiniteData(data, postId));
  };

  const switchTab = (next: string): void => {
    if (next === tab) return;
    setParams(next === 'discover' ? { tab: 'discover' } : {});
  };

  return (
    <div className="ml-page">
      <div className={styles.layout}>
        <main className={styles.main}>
          <div className={styles.tabsRow}>
            <Tabs
              activeKey={tab}
              onChange={switchTab}
              items={[
                { key: 'following', label: '关注' },
                { key: 'discover', label: '发现' },
              ]}
            />
            <Button
              size="small"
              icon={<ReloadOutlined />}
              loading={active.isFetching && !active.isFetchingNextPage}
              onClick={() => void active.refetch()}
            >
              刷新
            </Button>
          </div>

          {tab === 'following' && newCount > 0 ? (
            <Button
              type="primary"
              block
              className={styles.newBanner}
              loading={markRead.isPending}
              onClick={() => markRead.mutate()}
            >
              {newCount} 条新动态
            </Button>
          ) : null}

          {active.isLoading ? (
            <ListSkeleton rows={4} />
          ) : active.isError ? (
            <ErrorState error={active.error} onRetry={() => void active.refetch()} />
          ) : posts.length === 0 ? (
            tab === 'following' ? (
              <EmptyState
                description="关注一些用户，这里就会出现他们的动态"
                actionText="去发现页找人"
                onAction={() => switchTab('discover')}
              />
            ) : (
              <EmptyState
                description="暂时没有更多推荐了"
                actionText="刷新看看"
                onAction={() => void active.refetch()}
              />
            )
          ) : (
            <div className={styles.list}>
              {posts.map((post) => (
                <PostCard
                  key={post.id}
                  post={post}
                  onChange={handleChange}
                  onDeleted={handleDeleted}
                  onDislike={tab === 'discover' ? () => handleDislike(post.id) : undefined}
                />
              ))}
              <LoadMore
                hasNext={Boolean(active.hasNextPage)}
                loading={active.isFetchingNextPage}
                onLoadMore={() => void active.fetchNextPage()}
              />
            </div>
          )}
        </main>

        <aside className={styles.side}>
          <RecommendUsersCard limit={5} />
          <Card size="small" title={<span><FireOutlined /> 热门话题</span>}>
            {hotTopics.isLoading ? (
              <ListSkeleton rows={3} />
            ) : (hotTopics.data ?? []).length === 0 ? (
              <Typography.Text type="secondary">暂无热门话题</Typography.Text>
            ) : (
              <div className={styles.topicList}>
                {(hotTopics.data ?? []).map((topic, index) => (
                  <Link key={topic.id} to={topicPath(topic.name)} className={styles.topicRow}>
                    <span className={styles.topicRank}>{index + 1}</span>
                    <span className={styles.topicName}>#{topic.name}</span>
                    <Typography.Text type="secondary" className={styles.topicCount}>
                      {topic.postCount} 条
                    </Typography.Text>
                    {topic.isOfficial === 1 ? <Tag color="blue">官方</Tag> : null}
                  </Link>
                ))}
              </div>
            )}
          </Card>
        </aside>
      </div>
    </div>
  );
}
