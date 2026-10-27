import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button, Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { DislikeOutlined, HeartOutlined, MoreOutlined, StopOutlined, UndoOutlined } from '@ant-design/icons';
import {
  recommendApi,
  recommendKeys,
  type FeedbackAction,
  type FeedbackVO,
  type RecommendItem,
} from '@/api/recommend';
import { getModalApi, getNotificationApi, toastError, toastSuccess } from '@/utils/feedback';

/**
 * 推荐反馈菜单（模块 08 BR-17-015~021）。
 *
 * 反馈是**追加日志**：喜欢/不喜欢/屏蔽歌手/撤销都是新事件，服务端回放得到当前屏蔽态。
 * 提交后统一失效全部 `recommend` 查询：猜你喜欢与首页立即重算，
 * 每日推荐虽然保留缓存，但读取时会按最新屏蔽列表过滤。
 *
 * 两处 P0 交互按 PRD 落在前端：
 * - BR-17-016：不喜欢后 5 秒内提示「撤销」，通知消失后仍可在屏蔽管理中恢复；
 * - BR-17-018：屏蔽歌手影响较大，先二次确认再提交。
 */

interface Props {
  song: RecommendItem['song'];
  /** DAILY / GUESS / SIMILAR / DETAIL，落库用于效果分析 */
  scene: string;
  position?: number;
  size?: 'small' | 'middle';
}

const SUCCESS_TEXT: Record<FeedbackAction, string> = {
  LIKE: '已记录，会多推荐类似的歌',
  DISLIKE: '已减少这首歌的推荐',
  DISLIKE_ARTIST: '已减少该歌手的推荐',
  UNDO: '已撤销对该歌曲的反馈',
  UNDO_ARTIST: '已撤销对该歌手的屏蔽',
};

export function FeedbackMenu({ song, scene, position, size = 'small' }: Props): JSX.Element {
  const queryClient = useQueryClient();

  const invalidate = (): void => {
    // 前缀失效：daily / guess / continue / blocklist 全部刷新
    void queryClient.invalidateQueries({ queryKey: recommendKeys.all });
  };

  const mutation = useMutation({
    mutationFn: recommendApi.feedback,
    // EX-17-002-4：反馈失败重试 2 次；仍失败由请求层统一提示
    retry: 2,
    onSuccess: (vo) => {
      toastSuccess(SUCCESS_TEXT[vo.action as FeedbackAction] ?? '反馈已记录');
      invalidate();
      if (vo.action === 'DISLIKE' || vo.action === 'DISLIKE_ARTIST') {
        showUndoNotice(vo);
      }
    },
  });

  /** BR-17-016：5 秒内可撤销。超时后通知消失，屏蔽管理仍可手动恢复。 */
  const showUndoNotice = (vo: FeedbackVO): void => {
    const notification = getNotificationApi();
    if (!notification) return;
    const artistLevel = vo.action === 'DISLIKE_ARTIST';
    notification.open({
      message: artistLevel ? '已减少该歌手的推荐' : '已减少这类推荐',
      description: '5 秒内可撤销；超时后可在「屏蔽管理」中恢复。',
      duration: 5,
      btn: (
        <Button
          size="small"
          type="primary"
          ghost
          onClick={() => {
            recommendApi
              .feedback({
                action: artistLevel ? 'UNDO_ARTIST' : 'UNDO',
                ...(artistLevel ? { artistId: vo.artistId ?? undefined } : { songId: vo.songId ?? undefined }),
              })
              .then(() => {
                toastSuccess(SUCCESS_TEXT[artistLevel ? 'UNDO_ARTIST' : 'UNDO']);
                invalidate();
              })
              .catch(() => toastError('撤销失败，请在屏蔽管理中重试'));
          }}
        >
          撤销
        </Button>
      ),
    });
  };

  const items: MenuProps['items'] = [
    { key: 'LIKE', label: '喜欢', icon: <HeartOutlined /> },
    { key: 'DISLIKE', label: '不感兴趣', icon: <DislikeOutlined /> },
    {
      key: 'DISLIKE_ARTIST',
      label: `减少歌手「${song.artistName}」`,
      icon: <StopOutlined />,
    },
    { type: 'divider' },
    { key: 'UNDO', label: '撤销反馈', icon: <UndoOutlined /> },
  ];

  const submit = (key: FeedbackAction): void => {
    mutation.mutate({
      action: key,
      songId: song.id,
      scene,
      position,
      // 屏蔽歌手显式传 artistId；其余动作由服务端从歌曲派生
      ...(key === 'DISLIKE_ARTIST' ? { artistId: song.artistId } : {}),
    });
  };

  const handleClick: MenuProps['onClick'] = ({ key }) => {
    const action = key as FeedbackAction;
    // BR-17-018：歌手级屏蔽影响较大，先二次确认
    if (action === 'DISLIKE_ARTIST') {
      const modal = getModalApi();
      if (modal) {
        modal.confirm({
          title: `将减少歌手「${song.artistName}」的推荐？`,
          content: '该歌手的歌曲会从推荐中移除，可随时在「屏蔽管理」中恢复。',
          okText: '确认减少',
          cancelText: '取消',
          onOk: () => submit(action),
        });
        return;
      }
    }
    submit(action);
  };

  return (
    <Dropdown menu={{ items, onClick: handleClick }} trigger={['click']} placement="bottomRight">
      <Button
        type="text"
        size={size}
        loading={mutation.isPending}
        aria-label="推荐反馈"
        icon={<MoreOutlined />}
        onClick={(e) => e.stopPropagation()}
      />
    </Dropdown>
  );
}
