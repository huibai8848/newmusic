import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  Avatar,
  Button,
  Empty,
  Input,
  Popconfirm,
  Segmented,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  FlagOutlined,
  HeartFilled,
  HeartOutlined,
  MessageOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import { toastInfo, toastSuccess } from '@/utils/feedback';
import dayjs from 'dayjs';
import type { Id } from '@/types/api';
import type { CommentItem, CommentTargetType } from '@/types/social';
import { ReportDialog } from './ReportDialog';
import styles from './CommentSection.module.less';

const PAGE_SIZE = 10;

interface ReplyTarget {
  rootId: Id;
  parentId: Id;
  nickname: string;
}

/**
 * 评论区（FR-19-004 / 005）。
 *
 * 两级结构：根评论分页 + 根下回复。接口已把前 2 条回复塞进 `replies`，
 * 剩余回复点「查看全部」再拉，避免一屏几十条回复把首屏拖慢。
 *
 * 待审核评论只对作者本人可见（后端 `applyVisibility`），前端用「审核中」标签解释
 * 为什么别人看不到——否则用户会以为评论丢了。
 */
export function CommentSection({
  targetType,
  targetId,
  onChanged,
}: {
  targetType: CommentTargetType;
  targetId: Id;
  /** 评论增删后回调；动态详情用它刷新 commentCount，避免整页重拉 */
  onChanged?: () => void;
}): JSX.Element {
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const [sort, setSort] = useState<'hot' | 'time'>('hot');
  const [content, setContent] = useState('');
  const [replyTo, setReplyTo] = useState<ReplyTarget | null>(null);
  const [reportTarget, setReportTarget] = useState<CommentItem | null>(null);

  const query = useInfiniteQuery({
    queryKey: [...socialKeys.comments(targetType, targetId, sort), 'infinite'],
    queryFn: ({ pageParam }) => socialApi.comments(targetType, targetId, sort, pageParam, PAGE_SIZE),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
    enabled: Boolean(targetId),
  });

  const invalidateComments = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['social', 'comments'] });
    void queryClient.invalidateQueries({ queryKey: ['social', 'replies'] });
    // 详情页上的 commentCount 由模块 01 实体返回，删除/新增后一并刷新
    void queryClient.invalidateQueries({ queryKey: [targetType.toLowerCase()] });
  };

  const createMutation = useMutation({
    mutationFn: () =>
      socialApi.createComment({
        targetType,
        targetId,
        content: content.trim(),
        rootId: replyTo?.rootId,
        parentId: replyTo?.parentId,
      }),
    onSuccess: (created) => {
      setContent('');
      setReplyTo(null);
      invalidateComments();
      onChanged?.();
      // 模块 17：评论/回复同属一次互动；`status=0` 是待审核，仍然计入内容生产
      if (targetType === 'POST') {
        track('post_comment_submit', {
          post_id: String(targetId),
          root_id: created.rootId != null ? String(created.rootId) : null,
          is_reply: Boolean(replyTo),
          length: created.content?.length ?? 0,
        });
      }
      if (created.status === 0) {
        toastInfo('评论已提交，审核通过后对其他人可见');
      } else {
        toastSuccess('评论成功');
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (commentId: Id) => socialApi.deleteComment(commentId),
    onSuccess: () => {
      toastSuccess('评论已删除');
      invalidateComments();
      onChanged?.();
    },
  });

  const items = query.data?.pages.flatMap((page) => page.list) ?? [];
  const total = query.data?.pages[0]?.total ?? 0;

  return (
    <div className={styles.wrap}>
      <div className={styles.toolbar}>
        <Typography.Text strong>全部评论 {total > 0 ? `（${total}）` : ''}</Typography.Text>
        <Segmented
          size="small"
          value={sort}
          onChange={(value) => setSort(value as 'hot' | 'time')}
          options={[
            { label: '最热', value: 'hot' },
            { label: '最新', value: 'time' },
          ]}
        />
      </div>

      {isLogin ? (
        <div className={styles.composer}>
          {replyTo ? (
            <div className={styles.replyHint}>
              回复 @{replyTo.nickname}
              <Button type="link" size="small" onClick={() => setReplyTo(null)}>
                取消
              </Button>
            </div>
          ) : null}
          <Input.TextArea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder={replyTo ? `回复 @${replyTo.nickname}` : '说点什么…'}
            maxLength={500}
            showCount
            autoSize={{ minRows: 2, maxRows: 6 }}
          />
          <div className={styles.composerActions}>
            <Button
              type="primary"
              loading={createMutation.isPending}
              disabled={!content.trim()}
              onClick={() => createMutation.mutate()}
            >
              发表评论
            </Button>
          </div>
        </div>
      ) : (
        <div className={styles.loginTip}>
          <Link to={`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`}>
            登录
          </Link>
          后参与评论
        </div>
      )}

      {query.isLoading ? (
        <div className={styles.loading}>评论加载中…</div>
      ) : query.isError ? (
        <Empty description="评论加载失败，请稍后重试" />
      ) : items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有评论，来抢沙发" />
      ) : (
        <div className={styles.list}>
          {items.map((comment) => (
            <RootComment
              key={comment.id}
              comment={comment}
              onReply={(reply) => setReplyTo(reply)}
              onDelete={(id) => deleteMutation.mutate(id)}
              onReport={(item) => setReportTarget(item)}
            />
          ))}
        </div>
      )}

      {query.hasNextPage ? (
        <div className={styles.more}>
          <Button loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            加载更多评论
          </Button>
        </div>
      ) : null}

      <ReportDialog
        open={Boolean(reportTarget)}
        targetType="COMMENT"
        targetId={reportTarget?.id ?? ''}
        targetName={reportTarget ? `评论：${reportTarget.content.slice(0, 40)}` : undefined}
        onClose={() => setReportTarget(null)}
      />
    </div>
  );
}

function RootComment({
  comment,
  onReply,
  onDelete,
  onReport,
}: {
  comment: CommentItem;
  onReply: (reply: ReplyTarget) => void;
  onDelete: (id: Id) => void;
  onReport: (comment: CommentItem) => void;
}): JSX.Element {
  const [expanded, setExpanded] = useState(false);
  const firstReplies = comment.replies ?? [];

  return (
    <div className={styles.root}>
      <CommentRow
        comment={comment}
        onReply={() => onReply({ rootId: comment.id, parentId: comment.id, nickname: comment.nickname ?? '用户' })}
        onDelete={() => onDelete(comment.id)}
        onReport={() => onReport(comment)}
      />

      {firstReplies.length > 0 && !expanded ? (
        <div className={styles.replies}>
          {firstReplies.map((reply) => (
            <CommentRow
              key={reply.id}
              comment={reply}
              small
              onReply={() =>
                onReply({
                  rootId: comment.id,
                  parentId: reply.id,
                  nickname: reply.nickname ?? '用户',
                })
              }
              onDelete={() => onDelete(reply.id)}
              onReport={() => onReport(reply)}
            />
          ))}
        </div>
      ) : null}

      {expanded ? <ReplyList rootId={comment.id} onReply={onReply} onDelete={onDelete} onReport={onReport} /> : null}

      {comment.replyCount > firstReplies.length || expanded ? (
        <Button
          type="link"
          size="small"
          className={styles.expand}
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? '收起回复' : `查看全部 ${comment.replyCount} 条回复`}
        </Button>
      ) : null}
    </div>
  );
}

function ReplyList({
  rootId,
  onReply,
  onDelete,
  onReport,
}: {
  rootId: Id;
  onReply: (reply: ReplyTarget) => void;
  onDelete: (id: Id) => void;
  onReport: (comment: CommentItem) => void;
}): JSX.Element {
  const query = useQuery({
    queryKey: socialKeys.replies(rootId),
    queryFn: () => socialApi.replies(rootId, 1, 100),
  });

  if (query.isLoading) return <div className={styles.repliesLoading}>回复加载中…</div>;
  if (query.isError) return <div className={styles.repliesLoading}>回复加载失败</div>;

  const replies = query.data?.list ?? [];
  if (replies.length === 0) return <div className={styles.repliesLoading}>暂无回复</div>;

  return (
    <div className={styles.replies}>
      {replies.map((reply) => (
        <CommentRow
          key={reply.id}
          comment={reply}
          small
          onReply={() =>
            onReply({ rootId, parentId: reply.id, nickname: reply.nickname ?? '用户' })
          }
          onDelete={() => onDelete(reply.id)}
          onReport={() => onReport(reply)}
        />
      ))}
    </div>
  );
}

function CommentRow({
  comment,
  small,
  onReply,
  onDelete,
  onReport,
}: {
  comment: CommentItem;
  small?: boolean;
  onReply: () => void;
  onDelete: () => void;
  onReport: () => void;
}): JSX.Element {
  const queryClient = useQueryClient();
  const [liked, setLiked] = useState(comment.liked);
  const [likeCount, setLikeCount] = useState(comment.likeCount);

  useEffect(() => {
    setLiked(comment.liked);
    setLikeCount(comment.likeCount);
  }, [comment.liked, comment.likeCount]);

  const likeMutation = useMutation({
    mutationFn: () =>
      liked
        ? socialApi.unlike('COMMENT', comment.id)
        : socialApi.like('COMMENT', comment.id),
    onSuccess: (data) => {
      setLiked(data.liked);
      setLikeCount(data.count);
      queryClient.setQueryData(socialKeys.like('COMMENT', comment.id), data);
    },
  });

  return (
    <div className={`${styles.comment} ${small ? styles.small : ''}`}>
      <Avatar
        size={small ? 24 : 32}
        src={comment.avatar ?? undefined}
        icon={<UserOutlined />}
      />
      <div className={styles.body}>
        <div className={styles.meta}>
          <Typography.Text strong className={styles.nickname}>
            {comment.nickname ?? '用户'}
          </Typography.Text>
          <Typography.Text type="secondary" className={styles.time}>
            {dayjs(comment.createTime).format('YYYY-MM-DD HH:mm')}
          </Typography.Text>
          {comment.status === 0 ? <Tag color="gold">审核中</Tag> : null}
        </div>
        <div className={styles.content}>
          {comment.replyNickname ? (
            <span className={styles.replyTo}>回复 @{comment.replyNickname}：</span>
          ) : null}
          {comment.content}
        </div>
        <Space size={4} className={styles.actions}>
          <Button
            type="text"
            size="small"
            loading={likeMutation.isPending}
            icon={liked ? <HeartFilled style={{ color: '#eb2f96' }} /> : <HeartOutlined />}
            onClick={() => likeMutation.mutate()}
          >
            {likeCount > 0 ? likeCount : '点赞'}
          </Button>
          <Button type="text" size="small" icon={<MessageOutlined />} onClick={onReply}>
            回复
          </Button>
          {!comment.mine ? (
            <Button type="text" size="small" icon={<FlagOutlined />} onClick={onReport}>
              举报
            </Button>
          ) : null}
          {comment.mine ? (
            <Popconfirm title="删除这条评论？" okText="删除" cancelText="取消" onConfirm={onDelete}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          ) : null}
        </Space>
      </div>
    </div>
  );
}
