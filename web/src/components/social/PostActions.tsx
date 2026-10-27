import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  Button,
  Dropdown,
  Input,
  Modal,
  Popconfirm,
  Space,
  Tooltip,
  type MenuProps,
} from 'antd';
import {
  CloseCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  FlagOutlined,
  HeartFilled,
  HeartOutlined,
  MessageOutlined,
  MoreOutlined,
  ShareAltOutlined,
  StarFilled,
  StarOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { postApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import { copyText } from '@/utils/clipboard';
import { getModalApi, toastInfo, toastSuccess, toastWarning } from '@/utils/feedback';
import { canEditPost } from '@/utils/socialRules';
import type { Id } from '@/types/api';
import type { PostVO } from '@/types/socialUpgrade';
import { ReportDialog } from './ReportDialog';
import styles from './PostCard.module.less';

interface Props {
  post: PostVO;
  onChange?: (post: PostVO) => void;
  onDeleted?: (id: Id) => void;
  onComment?: () => void;
  /** 发现流才有「不感兴趣」 */
  onDislike?: () => void;
  compact?: boolean;
}

/**
 * 动态操作条（模块 16）。
 *
 * 不复用模块 10 的 `ActionButtons`：那套接口按 targetType 走 `/likes`、`/collects`，
 * 动态的收藏/转发有独立语义，混用会写错计数与通知。
 *
 * 点赞/收藏接口会回传完整 PostVO，直接交给页面替换缓存，避免整页重拉。
 */
export function PostActions({
  post,
  onChange,
  onDeleted,
  onComment,
  onDislike,
  compact = false,
}: Props): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const [reportOpen, setReportOpen] = useState(false);
  const [repostOpen, setRepostOpen] = useState(false);
  const [repostContent, setRepostContent] = useState('');

  const requireLogin = (): boolean => {
    if (isLogin) return true;
    navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
    return false;
  };

  const likeMutation = useMutation({
    mutationFn: () => (post.liked ? postApi.unlike(post.id) : postApi.like(post.id)),
    onSuccess: (next) => {
      // 模块 17：取消点赞不算互动
      if (next?.liked) track('post_like_click', { post_id: String(post.id) });
      onChange?.(next);
    },
  });

  const collectMutation = useMutation({
    mutationFn: () => (post.collected ? postApi.uncollect(post.id) : postApi.collect(post.id)),
    onSuccess: (next) => onChange?.(next),
  });

  const repostMutation = useMutation({
    mutationFn: () => postApi.repost(post.id, repostContent.trim() || undefined),
    onSuccess: () => {
      const hasContent = repostContent.trim().length > 0;
      setRepostOpen(false);
      setRepostContent('');
      track('post_repost_click', { post_id: String(post.id), has_content: hasContent });
      toastSuccess('转发成功');
      // 转发会生成新动态，流与个人主页动态数都可能变化
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['social'] });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => postApi.remove(post.id),
    onSuccess: () => {
      toastSuccess('动态已删除');
      onDeleted?.(post.id);
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
    },
  });

  const dislikeMutation = useMutation({
    mutationFn: () => postApi.dislike(post.id),
    onSuccess: () => {
      toastInfo('已减少此类推荐');
      onDislike?.();
    },
  });

  const share = async (): Promise<void> => {
    if (!requireLogin()) return;
    try {
      await postApi.share(post.id);
    } catch {
      // 分享信息仅用于文案，失败不影响复制链接
    }
    const url = `${window.location.origin}/post/${post.id}`;
    const ok = await copyText(url);
    if (ok) toastSuccess('链接已复制');
    else toastInfo(url);
  };

  const menuItems: MenuProps['items'] = [];
  if (post.authorSelf) {
    if (canEditPost(post.createTime)) {
      menuItems.push({
        key: 'edit',
        icon: <EditOutlined />,
        label: '编辑',
        onClick: () => navigate(`/post/new?editId=${post.id}`),
      });
    }
    menuItems.push({
      key: 'delete',
      danger: true,
      icon: <DeleteOutlined />,
      label: '删除',
      onClick: () => {
        const modal = getModalApi();
        if (!modal) {
          toastWarning('确认弹窗未就绪，请稍后重试');
          return;
        }
        modal.confirm({
          title: '删除这条动态？',
          content: '删除后评论与点赞数据将不再展示，且无法恢复。',
          okText: '删除',
          okButtonProps: { danger: true },
          cancelText: '取消',
          onOk: () => deleteMutation.mutateAsync(),
        });
      },
    });
  } else {
    menuItems.push({
      key: 'report',
      icon: <FlagOutlined />,
      label: '举报',
      onClick: () => {
        if (!requireLogin()) return;
        setReportOpen(true);
      },
    });
  }
  if (onDislike) {
    menuItems.push({
      key: 'dislike',
      icon: <CloseCircleOutlined />,
      label: '不感兴趣',
      onClick: () => {
        if (!requireLogin()) return;
        dislikeMutation.mutate();
      },
    });
  }

  return (
    <div className={styles.actions}>
      <Space size={compact ? 4 : 16} wrap>
        <Button
          type="text"
          size={compact ? 'small' : 'middle'}
          className={post.liked ? styles.activeAction : undefined}
          icon={post.liked ? <HeartFilled /> : <HeartOutlined />}
          loading={likeMutation.isPending}
          onClick={() => {
            if (!requireLogin()) return;
            likeMutation.mutate();
          }}
        >
          {post.likeCount > 0 ? post.likeCount : '点赞'}
        </Button>

        <Button
          type="text"
          size={compact ? 'small' : 'middle'}
          icon={<MessageOutlined />}
          onClick={() => {
            if (onComment) {
              onComment();
              return;
            }
            navigate(`/post/${post.id}`);
          }}
        >
          {post.commentCount > 0 ? post.commentCount : '评论'}
        </Button>

        <Button
          type="text"
          size={compact ? 'small' : 'middle'}
          icon={<SwapOutlined />}
          onClick={() => {
            if (!requireLogin()) return;
            setRepostOpen(true);
          }}
        >
          {post.repostCount > 0 ? post.repostCount : '转发'}
        </Button>

        <Button
          type="text"
          size={compact ? 'small' : 'middle'}
          className={post.collected ? styles.collectedAction : undefined}
          icon={post.collected ? <StarFilled /> : <StarOutlined />}
          loading={collectMutation.isPending}
          onClick={() => {
            if (!requireLogin()) return;
            collectMutation.mutate();
          }}
        >
          {post.collectCount > 0 ? post.collectCount : '收藏'}
        </Button>

        <Tooltip title="复制链接">
          <Button
            type="text"
            size={compact ? 'small' : 'middle'}
            icon={<ShareAltOutlined />}
            onClick={() => void share()}
          />
        </Tooltip>

        {menuItems.length > 0 ? (
          <Dropdown menu={{ items: menuItems }} trigger={['click']}>
            <Button type="text" size={compact ? 'small' : 'middle'} icon={<MoreOutlined />} />
          </Dropdown>
        ) : null}
      </Space>

      <Modal
        open={repostOpen}
        title="转发动态"
        okText="转发"
        cancelText="取消"
        confirmLoading={repostMutation.isPending}
        onCancel={() => setRepostOpen(false)}
        onOk={() => repostMutation.mutate()}
      >
        <Input.TextArea
          value={repostContent}
          onChange={(e) => setRepostContent(e.target.value)}
          placeholder="说点什么（选填）"
          maxLength={200}
          showCount
          autoSize={{ minRows: 3, maxRows: 5 }}
        />
      </Modal>

      <ReportDialog
        open={reportOpen}
        targetType="POST"
        targetId={post.id}
        targetName={`动态：${(post.content ?? '').slice(0, 40) || post.id}`}
        onClose={() => setReportOpen(false)}
      />
    </div>
  );
}

/** 供页面在 Popconfirm 里复用的删除按钮（例如个人主页动态列表） */
export function DeletePostButton({
  postId,
  onDeleted,
}: {
  postId: Id;
  onDeleted?: (id: Id) => void;
}): JSX.Element {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => postApi.remove(postId),
    onSuccess: () => {
      toastSuccess('动态已删除');
      onDeleted?.(postId);
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
    },
  });
  return (
    <Popconfirm
      title="删除这条动态？"
      okText="删除"
      cancelText="取消"
      onConfirm={() => mutation.mutate()}
    >
      <Button type="text" size="small" danger icon={<DeleteOutlined />} loading={mutation.isPending}>
        删除
      </Button>
    </Popconfirm>
  );
}

/** 转发图标复用于空态引导，避免页面各自找图标 */
export const RepostIcon = SwapOutlined;
