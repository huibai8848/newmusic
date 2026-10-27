import { Link } from 'react-router-dom';
import { Avatar, Empty, Modal, Skeleton, Typography } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { useInfiniteQuery } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import { LoadMore } from '@/components/common/LoadMore';
import type { Id } from '@/types/api';
import { FollowButton } from './FollowButton';

const PAGE_SIZE = 20;

/**
 * 关注/粉丝列表弹窗。
 *
 * 直接复用模块 10 的 `/follows/following|followers`，避免为同一个数据再开一套接口；
 * 关注按钮同样复用，保证在弹窗里关注后主页计数同步失效。
 */
export function RelationListModal({
  open,
  userId,
  kind,
  onClose,
}: {
  open: boolean;
  userId: Id;
  kind: 'following' | 'followers';
  onClose: () => void;
}): JSX.Element {
  const query = useInfiniteQuery({
    queryKey: [...socialKeys[kind](userId), 'infinite'],
    queryFn: ({ pageParam }) =>
      kind === 'following'
        ? socialApi.following(userId, pageParam, PAGE_SIZE)
        : socialApi.followers(userId, pageParam, PAGE_SIZE),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
    enabled: open && Boolean(userId),
  });

  const items = query.data?.pages.flatMap((page) => page.list) ?? [];

  return (
    <Modal
      open={open}
      title={kind === 'following' ? '关注列表' : '粉丝列表'}
      footer={null}
      width={520}
      onCancel={onClose}
      destroyOnHidden
    >
      {query.isLoading ? (
        <Skeleton active avatar paragraph={{ rows: 1 }} />
      ) : query.isError ? (
        <Empty description="加载失败，请稍后重试" />
      ) : items.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={kind === 'following' ? '还没有关注任何人' : '还没有粉丝'}
        />
      ) : (
        <div>
          {items.map((user) => (
            <div
              key={user.id}
              style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 0' }}
            >
              <Link to={`/user/${user.id}`} onClick={onClose}>
                <Avatar size={40} src={user.avatar ?? undefined} icon={<UserOutlined />} />
              </Link>
              <div style={{ flex: 1, minWidth: 0 }}>
                <Link
                  to={`/user/${user.id}`}
                  onClick={onClose}
                  style={{ fontWeight: 600, color: 'var(--color-text)' }}
                >
                  {user.nickname ?? '用户'}
                </Link>
                {user.signature ? (
                  <Typography.Text type="secondary" ellipsis style={{ display: 'block', fontSize: 12 }}>
                    {user.signature}
                  </Typography.Text>
                ) : null}
              </div>
              <FollowButton kind="USER" targetId={user.id} size="small" />
            </div>
          ))}
          <LoadMore
            hasNext={Boolean(query.hasNextPage)}
            loading={query.isFetchingNextPage}
            onLoadMore={() => void query.fetchNextPage()}
            total={query.data?.pages[0]?.total}
          />
        </div>
      )}
    </Modal>
  );
}
