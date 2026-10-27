import { useLocation, useNavigate } from 'react-router-dom';
import { Button } from 'antd';
import { PlusOutlined, CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import type { ArtistFollowStatus, FollowStatus } from '@/types/social';
import { artistKeys } from '@/api/catalog';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import { toastSuccess } from '@/utils/feedback';
import type { Id } from '@/types/api';

/**
 * 关注按钮（用户/歌手）。
 *
 * 登录后先查一次状态，避免「已关注」显示成「+ 关注」；
 * 游客点击直接去登录，不在本地假装已关注。
 */
export function FollowButton({
  kind,
  targetId,
  followerCount,
  size,
}: {
  kind: 'USER' | 'ARTIST';
  targetId: Id;
  followerCount?: number;
  size?: 'small' | 'middle' | 'large';
}): JSX.Element | null {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const selfId = useUserStore((s) => s.user?.id);

  const isSelf = kind === 'USER' && Boolean(selfId && String(selfId) === String(targetId));

  const queryKey = ['social', 'follow-status', kind, targetId] as const;
  const query = useQuery<FollowStatus | ArtistFollowStatus>({
    queryKey,
    queryFn: async () =>
      kind === 'ARTIST'
        ? socialApi.artistFollowStatus(targetId)
        : socialApi.followStatus(targetId),
    enabled: isLogin && Boolean(targetId) && !isSelf,
    staleTime: 30_000,
  });

  const mutation = useMutation<FollowStatus | ArtistFollowStatus, Error, boolean>({
    mutationFn: async (next: boolean) => {
      if (kind === 'ARTIST') {
        return next ? socialApi.followArtist(targetId) : socialApi.unfollowArtist(targetId);
      }
      return next ? socialApi.followUser(targetId) : socialApi.unfollowUser(targetId);
    },
    onSuccess: (_data, next) => {
      queryClient.setQueryData(queryKey, _data);
      void queryClient.invalidateQueries({ queryKey: ['social', 'follow-status'] });
      void queryClient.invalidateQueries({ queryKey: ['social', 'following'] });
      void queryClient.invalidateQueries({ queryKey: socialKeys.followers() });
      void queryClient.invalidateQueries({ queryKey: artistKeys.all });
      // 模块 16：主页粉丝数、关注流、推荐关注都依赖关注关系
      void queryClient.invalidateQueries({ queryKey: ['social-upgrade'] });
      if (next && kind === 'USER') {
        // 模块 17：新增关注是互动用户口径的一部分；取消关注不产生互动。
        // 歌手关注没有独立的埋点文档，按「无文档不采集」不硬套 user_follow_click。
        track('user_follow_click', { target_user_id: String(targetId) });
      }
      toastSuccess(next ? '关注成功' : '已取消关注');
    },
  });

  if (isSelf) return null;

  const followed = Boolean(query.data && 'followed' in query.data && query.data.followed);

  const handleClick = (): void => {
    if (!isLogin) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    mutation.mutate(!followed);
  };

  return (
    <Button
      size={size}
      type={followed ? 'default' : 'primary'}
      icon={followed ? <CheckOutlined /> : <PlusOutlined />}
      loading={mutation.isPending || query.isLoading}
      onClick={handleClick}
    >
      {followed ? '已关注' : kind === 'ARTIST' ? '关注歌手' : '关注'}
      {typeof followerCount === 'number' && followerCount > 0 ? ` · ${followerCount}` : ''}
    </Button>
  );
}
