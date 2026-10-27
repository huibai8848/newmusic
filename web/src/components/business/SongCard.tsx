import type { MouseEvent } from 'react';
import { Button, Card, Space, Typography } from 'antd';
import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { SongBrief } from '@/types/api';
import { formatCount, formatDuration } from '@/utils/format';
import { CoverImage } from '@/components/common/CoverImage';
import { HighlightText } from '@/components/common/HighlightText';
import { usePlayerStore, type PlaySource } from '@/stores/playerStore';
import { isPlayable } from '@/api/player';

/**
 * 歌曲卡片（首页新歌榜 / 猜你喜欢网格）。
 *
 * 交互（PRD FR-11-001）：
 * - 点击卡片封面/播放按钮 → 直接播放，**不跳页**（AC-11-001-4）
 * - 点击歌名 → 进入歌曲详情
 * - 悬停显示播放按钮（BR-11-003）
 */

interface Props {
  song: SongBrief;
  keyword?: string;
  /** 播放时是否把整个列表作为队列 */
  list?: SongBrief[];
  /** 埋点用（模块 12 消费）；卡片出现在哪个版块就传哪个 */
  source?: PlaySource;
}

export function SongCard({ song, keyword, list, source = 'unknown' }: Props): JSX.Element {
  const navigate = useNavigate();
  const currentSongId = usePlayerStore((s) => s.currentSong?.id);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const playSong = usePlayerStore((s) => s.playSong);
  const toggle = usePlayerStore((s) => s.toggle);

  const isCurrent = currentSongId === song.id;
  const playable = isPlayable(song);

  const handlePlay = (e: MouseEvent<HTMLElement>): void => {
    e.stopPropagation();
    if (isCurrent) {
      toggle();
      return;
    }
    playSong(song, list, source);
  };

  return (
    <Card
      hoverable
      size="small"
      styles={{ body: { padding: 12 } }}
      onClick={() => navigate(`/song/${song.id}`)}
      cover={
        <div style={{ position: 'relative', display: 'flex', justifyContent: 'center', paddingTop: 12 }}>
          <CoverImage src={song.cover} alt={song.name} size={132} />
          <Button
            type="primary"
            shape="circle"
            size="large"
            aria-label={isCurrent && isPlaying ? '暂停' : '播放'}
            disabled={!playable}
            icon={isCurrent && isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
            onClick={handlePlay}
            style={{
              position: 'absolute',
              bottom: 8,
              right: 'calc(50% - 66px)',
              opacity: isCurrent ? 1 : 0.92,
            }}
          />
        </div>
      }
    >
      <Typography.Text
        strong
        ellipsis
        style={{ display: 'block', color: 'var(--color-text)' }}
        title={song.name}
      >
        <HighlightText text={song.name} keyword={keyword} />
      </Typography.Text>
      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 2 }}>
        <HighlightText text={song.artistName} keyword={keyword} />
      </div>
      <Space size={8} style={{ marginTop: 6, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        <span>{formatDuration(song.duration)}</span>
        <span>{formatCount(song.playCount)} 次播放</span>
      </Space>
    </Card>
  );
}
