import { Card, Empty, Skeleton, Tag, Typography } from 'antd';
import { HeartTwoTone } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { profileApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { useUserStore } from '@/stores/userStore';
import type { Id } from '@/types/api';

/**
 * 亲密度（FR-25-031）。
 *
 * 只有登录且不是本人时才请求；对方关闭展示时后端仍返回等级、score 为 null，
 * 因此不能把「score 为空」当成加载失败或 0 分。
 */
export function IntimacyCard({ userId }: { userId: Id }): JSX.Element | null {
  const isLogin = useUserStore((s) => s.isLogin);
  const selfId = useUserStore((s) => s.user?.id);
  const self = Boolean(selfId && String(selfId) === String(userId));

  const query = useQuery({
    queryKey: socialUpgradeKeys.intimacy(userId),
    queryFn: () => profileApi.intimacy(userId),
    enabled: isLogin && !self && Boolean(userId),
    staleTime: 60_000,
    retry: false,
  });

  if (!isLogin || self) return null;

  return (
    <Card size="small" title={<span><HeartTwoTone twoToneColor="#eb2f96" /> 亲密度</span>}>
      {query.isLoading ? (
        <Skeleton active paragraph={{ rows: 1 }} title={false} />
      ) : query.isError || !query.data ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂时无法查看亲密度" />
      ) : (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Tag color="magenta">{query.data.levelName}</Tag>
            <Typography.Text strong>
              {query.data.scoreVisible && query.data.score !== null
                ? `${query.data.score} 分`
                : '分数已隐藏'}
            </Typography.Text>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {query.data.scoreVisible && query.data.score !== null
              ? '多互动（聊天、评论、点赞、互关）可以提升亲密度'
              : '对方关闭了亲密度分数展示，等级仍然可见'}
          </Typography.Text>
        </div>
      )}
    </Card>
  );
}
