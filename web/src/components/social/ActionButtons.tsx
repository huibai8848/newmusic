import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, Space } from 'antd';
import {
  FlagOutlined,
  HeartFilled,
  HeartOutlined,
  ShareAltOutlined,
  StarFilled,
  StarOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import { formatCount } from '@/utils/format';
import type { Id } from '@/types/api';
import type { CollectTargetType, ShareTargetType } from '@/types/social';
import { CollectDialog } from './CollectDialog';
import { ReportDialog } from './ReportDialog';
import { ShareDialog } from './ShareDialog';

/**
 * 内容详情页的社交动作条：点赞 / 收藏 / 分享 / 举报。
 *
 * 点赞状态由 `/likes` 返回（公开接口，游客拿到的是 count）；收藏状态走
 * `/collects/status`（需登录）。未登录点击直接跳登录，不做本地乐观假动作，
 * 否则登录后状态与本地不一致。
 *
 * 专辑不在举报目标白名单里（后端 REPORT_TYPES 没有 ALBUM），因此 `showReport` 默认按类型关闭。
 */
export function ActionButtons({
  targetType,
  targetId,
  likeCount: initialLikeCount,
  collectCount: initialCollectCount,
  showReport,
}: {
  targetType: CollectTargetType & ShareTargetType;
  targetId: Id;
  likeCount?: number;
  collectCount?: number;
  showReport?: boolean;
}): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);

  const [collectOpen, setCollectOpen] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [collectCount, setCollectCount] = useState(initialCollectCount ?? 0);

  useEffect(() => {
    setCollectCount(initialCollectCount ?? 0);
  }, [initialCollectCount]);

  const likeQuery = useQuery({
    queryKey: socialKeys.like(targetType, targetId),
    queryFn: () => socialApi.likeStatus(targetType, targetId),
    enabled: Boolean(targetId),
  });

  const collectQuery = useQuery({
    queryKey: socialKeys.collect(targetType, targetId),
    queryFn: () => socialApi.collectStatus(targetType, targetId),
    enabled: isLogin && Boolean(targetId),
    staleTime: 30_000,
  });

  const likeMutation = useMutation({
    mutationFn: (next: boolean) =>
      next ? socialApi.like(targetType, targetId) : socialApi.unlike(targetType, targetId),
    onSuccess: (data) => {
      queryClient.setQueryData(socialKeys.like(targetType, targetId), data);
      // 模块 17：这里只覆盖歌曲/歌单/专辑；动态点赞在 PostCard 里单独上报
    },
  });

  const liked = Boolean(likeQuery.data?.liked);
  const likeCount = likeQuery.data?.count ?? initialLikeCount ?? 0;
  const collected = Boolean(collectQuery.data?.collected);

  const requireLogin = (): boolean => {
    if (isLogin) return false;
    navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
    return true;
  };

  const handleLike = (): void => {
    if (requireLogin()) return;
    likeMutation.mutate(!liked);
  };

  const handleCollect = (): void => {
    if (requireLogin()) return;
    setCollectOpen(true);
  };

  return (
    <>
      <Space wrap>
        <Button
          icon={liked ? <HeartFilled style={{ color: '#eb2f96' }} /> : <HeartOutlined />}
          loading={likeMutation.isPending}
          onClick={handleLike}
        >
          点赞 {formatCount(likeCount)}
        </Button>
        <Button
          icon={collected ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
          onClick={handleCollect}
        >
          收藏 {formatCount(collectCount)}
        </Button>
        <Button icon={<ShareAltOutlined />} onClick={() => setShareOpen(true)}>
          分享
        </Button>
        {(showReport ?? targetType !== 'ALBUM') ? (
          <Button
            icon={<FlagOutlined />}
            onClick={() => {
              if (requireLogin()) return;
              setReportOpen(true);
            }}
          >
            举报
          </Button>
        ) : null}
      </Space>

      <CollectDialog
        open={collectOpen}
        targetType={targetType}
        targetId={targetId}
        collected={collected}
        folderId={collectQuery.data?.folderId ?? null}
        onClose={() => setCollectOpen(false)}
        onChanged={(next) => {
          setCollectCount((count) => Math.max(0, count + (next ? 1 : -1) * (collected === next ? 0 : 1)));
          void queryClient.invalidateQueries({ queryKey: socialKeys.collect(targetType, targetId) });
          if (next && targetType === 'PLAYLIST') {
            track('playlist_collect_click', { playlist_id: String(targetId) });
          }
        }}
      />

      <ShareDialog
        open={shareOpen}
        targetType={targetType}
        targetId={targetId}
        onClose={() => setShareOpen(false)}
      />

      {reportOpen && targetType !== 'ALBUM' ? (
        <ReportDialog
          open
          targetType={targetType}
          targetId={targetId}
          onClose={() => setReportOpen(false)}
        />
      ) : null}
    </>
  );
}
