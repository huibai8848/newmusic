import { useNavigate, useParams } from 'react-router-dom';
import { Button, Card, Space } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { postApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { CommentSection } from '@/components/social/CommentSection';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { PageLoading } from '@/components/common/PageLoading';
import { PostCard } from '@/components/social/PostCard';
import { replacePostInInfiniteData } from '@/utils/socialRules';
import type { FeedVO, PostVO } from '@/types/socialUpgrade';
import styles from './PostDetailPage.module.less';

/**
 * 动态详情（P0）。
 *
 * 评论复用模块 10 的 `CommentSection`（后端 COMMENT_TYPES 已含 POST）；
 * 评论增删后只失效本条动态与评论列表，不整页重拉动态流。
 */
export function PostDetailPage(): JSX.Element {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: socialUpgradeKeys.post(id),
    queryFn: () => postApi.detail(id),
    enabled: Boolean(id),
  });

  const handleChange = (post: PostVO): void => {
    queryClient.setQueryData(socialUpgradeKeys.post(post.id), post);
    queryClient.setQueryData(socialUpgradeKeys.feed('following'), (data: { pages: FeedVO[] } | undefined) =>
      replacePostInInfiniteData(data, post));
    queryClient.setQueryData(socialUpgradeKeys.feed('discover'), (data: { pages: FeedVO[] } | undefined) =>
      replacePostInInfiniteData(data, post));
  };

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.post(id) });
  };

  if (query.isLoading) return <PageLoading tip="动态加载中…" />;
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }
  if (!query.data || query.data.deleted) {
    return (
      <div className="ml-page">
        <EmptyState description="动态不存在或已被删除" actionText="返回动态" onAction={() => navigate('/feed')} />
      </div>
    );
  }

  return (
    <div className={`ml-page ${styles.page}`}>
      <Space className={styles.toolbar}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>返回</Button>
      </Space>

      <PostCard
        post={query.data}
        variant="detail"
        onChange={handleChange}
        onDeleted={() => navigate('/feed')}
      />

      <Card id="post-comments" className={styles.comments}>
        <CommentSection targetType="POST" targetId={id} onChanged={refresh} />
      </Card>
    </div>
  );
}
