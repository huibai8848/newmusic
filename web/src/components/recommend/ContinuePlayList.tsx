import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Progress, Space, Tooltip, Typography } from 'antd';
import { CaretRightOutlined, CloseOutlined } from '@ant-design/icons';
import { recommendApi, recommendKeys } from '@/api/recommend';
import { CoverImage } from '@/components/common/CoverImage';
import { CardSkeleton } from '@/components/common/PageLoading';
import { EmptyState } from '@/components/common/EmptyState';
import { formatTime } from '@/utils/format';
import { usePlayerStore } from '@/stores/playerStore';
import styles from './ContinuePlayList.module.less';

/**
 * 继续播放（模块 08 BR-17-001）：只取进度在 10%~90% 之间、最多 10 首。
 *
 * 点击卡片从断点起播：走 store 的 `playAt`，位置在音频就绪后再应用，
 * 直接 `playSong + seek` 会被随后设置的 `audio.src` 重置。
 */

interface Props {
  enabled?: boolean;
  /** 没有数据时是否占位显示空态（首页通常直接隐藏） */
  showEmpty?: boolean;
}

export function ContinuePlayList({ enabled = true, showEmpty = false }: Props): JSX.Element | null {
  const queryClient = useQueryClient();
  const playAt = usePlayerStore((s) => s.playAt);

  const query = useQuery({
    queryKey: recommendKeys.continuePlay(),
    queryFn: recommendApi.continuePlay,
    enabled,
  });

  const dismiss = useMutation({
    mutationFn: recommendApi.dismissContinue,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: recommendKeys.continuePlay() });
    },
  });

  if (!enabled) return null;
  if (query.isLoading) return <CardSkeleton cards={4} />;
  // 继续播放是增强区块：接口失败时直接隐藏，不打断首页主流程
  if (query.isError) return null;

  const items = query.data ?? [];
  if (items.length === 0) {
    return showEmpty ? <EmptyState compact description="最近没有听到一半的歌" /> : null;
  }

  const songs = items.map((item) => item.song);

  return (
    <div className={styles.grid}>
      {items.map((item) => {
        const percent = Math.round(item.progress * 100);
        return (
          <Card key={String(item.song.id)} size="small" hoverable className={styles.card}>
            <div className={styles.inner}>
              <CoverImage src={item.song.cover} alt={item.song.name} size={64} />
              <div className={styles.info}>
                <Typography.Text strong ellipsis style={{ display: 'block' }}>
                  {item.song.name}
                </Typography.Text>
                <Typography.Text type="secondary" ellipsis style={{ display: 'block', fontSize: 12 }}>
                  {item.song.artistName}
                </Typography.Text>
                <Progress
                  percent={percent}
                  size="small"
                  showInfo={false}
                  style={{ margin: '4px 0 0' }}
                  strokeColor="var(--color-primary)"
                />
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {formatTime(item.position)} / {formatTime(item.duration)}
                </Typography.Text>
              </div>
              <Space size={2} className={styles.actions}>
                <Tooltip title="从上次位置继续播放">
                  <Button
                    type="primary"
                    shape="circle"
                    size="small"
                    aria-label="继续播放"
                    icon={<CaretRightOutlined />}
                    onClick={() => playAt(item.song, songs, item.position, 'recommend')}
                  />
                </Tooltip>
                <Tooltip title="不再显示">
                  <Button
                    type="text"
                    size="small"
                    aria-label="从继续播放中移除"
                    icon={<CloseOutlined />}
                    loading={dismiss.isPending}
                    onClick={() => dismiss.mutate(item.song.id)}
                  />
                </Tooltip>
              </Space>
            </div>
          </Card>
        );
      })}
    </div>
  );
}
