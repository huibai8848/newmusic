import { Link } from 'react-router-dom';
import { Avatar, Button, Card, Empty, Skeleton, Space, Tag, Typography } from 'antd';
import { CloseOutlined, UserOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { profileApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { useUserStore } from '@/stores/userStore';
import { toastSuccess } from '@/utils/feedback';
import type { Id } from '@/types/api';
import type { RecommendUserVO } from '@/types/socialUpgrade';
import { FollowButton } from './FollowButton';

const SOURCE_LABEL: Record<string, string> = {
  mutual: '互关好友',
  second_degree: '你可能认识',
  region: '同城用户',
  hot: '热门用户',
};

/**
 * 推荐关注（FR-25-021）。
 *
 * 「忽略」是 30 天级别的偏好，成功后必须把推荐列表失效掉，
 * 否则用户点完还在原地看到同一个人，会以为按钮没生效。
 */
export function RecommendUsersCard({ limit = 5 }: { limit?: number }): JSX.Element | null {
  const isLogin = useUserStore((s) => s.isLogin);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: socialUpgradeKeys.recommend,
    queryFn: () => profileApi.recommend(limit),
    enabled: isLogin,
    staleTime: 60_000,
  });

  const ignoreMutation = useMutation({
    mutationFn: (userId: Id) => profileApi.ignoreRecommend(userId),
    onSuccess: () => {
      toastSuccess('已减少该推荐');
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.recommend });
    },
  });

  if (!isLogin) return null;

  return (
    <Card
      size="small"
      title="推荐关注"
      styles={{ body: { padding: query.isLoading ? 16 : '4px 8px' } }}
    >
      {query.isLoading ? (
        <Skeleton active avatar paragraph={{ rows: 1 }} />
      ) : query.isError ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="推荐加载失败" />
      ) : (query.data ?? []).length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无推荐，去发现页逛逛" />
      ) : (
        <div>
          {(query.data ?? []).map((item) => (
            <RecommendRow
              key={item.user.id}
              item={item}
              ignoring={ignoreMutation.isPending}
              onIgnore={() => ignoreMutation.mutate(item.user.id)}
            />
          ))}
        </div>
      )}
    </Card>
  );
}

function RecommendRow({
  item,
  ignoring,
  onIgnore,
}: {
  item: RecommendUserVO;
  ignoring: boolean;
  onIgnore: () => void;
}): JSX.Element {
  return (
    <div style={{ display: 'flex', gap: 10, padding: '10px 8px', alignItems: 'flex-start' }}>
      <Link to={`/user/${item.user.id}`}>
        <Avatar size={40} src={item.user.avatar ?? undefined} icon={<UserOutlined />} />
      </Link>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Link to={`/user/${item.user.id}`} style={{ fontWeight: 600, color: 'var(--color-text)' }}>
          {item.user.nickname ?? '用户'}
        </Link>
        <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 2 }}>
          <Space size={4} wrap>
            <Tag bordered={false}>{SOURCE_LABEL[item.source] ?? '推荐'}</Tag>
            {item.followBack ? <Tag color="blue" bordered={false}>回关你</Tag> : null}
            {item.mutualCount > 0 ? <span>{item.mutualCount} 位共同好友</span> : null}
          </Space>
        </div>
        {item.reason ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis>
            {item.reason}
          </Typography.Text>
        ) : null}
      </div>
      <Space direction="vertical" size={2} align="end">
        <FollowButton kind="USER" targetId={item.user.id} size="small" />
        <Button
          type="text"
          size="small"
          icon={<CloseOutlined />}
          loading={ignoring}
          onClick={onIgnore}
          aria-label="忽略推荐"
        />
      </Space>
    </div>
  );
}
