import type { MouseEvent } from 'react';
import { Button, Card, Space, Tag, Tooltip, Typography } from 'antd';
import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { RecommendItem } from '@/api/recommend';
import type { SongBrief } from '@/types/api';
import { isPlayable } from '@/api/player';
import { formatCount, formatDuration } from '@/utils/format';
import { CoverImage } from '@/components/common/CoverImage';
import { FeedbackMenu } from '@/components/business/FeedbackMenu';
import { usePlayerStore } from '@/stores/playerStore';
import { track } from '@/services/track';
import styles from './RecommendSongCard.module.less';

/**
 * 推荐歌曲卡片（模块 08）：在普通歌曲卡片上多展示「推荐理由」与反馈入口。
 *
 * 理由由服务端生成（≤20 字），这里只负责展示，不在前端拼文案——
 * 前端拼理由必然与服务端实际打分依据脱节，解释性就名存实亡了。
 */

interface Props {
  item: RecommendItem;
  /** DAILY / GUESS / SIMILAR，用于反馈落库 */
  scene: string;
  position: number;
  /** 播放时把整个推荐列表作为队列 */
  list?: SongBrief[];
  /** 相似歌曲区块里不需要反馈（用户正在看的就是目标歌曲） */
  showFeedback?: boolean;
}

export function RecommendSongCard({ item, scene, position, list, showFeedback = true }: Props): JSX.Element {
  const navigate = useNavigate();
  const currentSongId = usePlayerStore((s) => s.currentSong?.id);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const playSong = usePlayerStore((s) => s.playSong);
  const toggle = usePlayerStore((s) => s.toggle);

  const { song } = item;
  const isCurrent = String(currentSongId ?? '') === String(song.id);
  const playable = isPlayable(song);

  const handlePlay = (e: MouseEvent<HTMLElement>): void => {
    e.stopPropagation();
    // 模块 17：暂停/继续不算一次点击，只有真正起播才计 CTR 的分子
    if (!isCurrent) {
      track('recommend_click', {
        song_id: String(song.id),
        scene,
        position,
        action: 'play',
      });
    }
    if (isCurrent) {
      toggle();
      return;
    }
    playSong(song, list, 'recommend');
  };

  return (
    <Card
      hoverable
      size="small"
      styles={{ body: { padding: 12 } }}
      onClick={() => {
        track('recommend_click', {
          song_id: String(song.id),
          scene,
          position,
          action: 'detail',
        });
        navigate(`/song/${song.id}`);
      }}
      cover={
        <div className={styles.cover}>
          <CoverImage src={song.cover} alt={song.name} size={132} />
          <Button
            className={styles.play}
            type="primary"
            shape="circle"
            size="large"
            aria-label={isCurrent && isPlaying ? '暂停' : '播放'}
            disabled={!playable}
            icon={isCurrent && isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
            onClick={handlePlay}
          />
          {showFeedback ? (
            <div className={styles.feedback} onClick={(e) => e.stopPropagation()}>
              <FeedbackMenu song={song} scene={scene} position={position} />
            </div>
          ) : null}
        </div>
      }
    >
      <Typography.Text strong ellipsis style={{ display: 'block', color: 'var(--color-text)' }} title={song.name}>
        {song.name}
      </Typography.Text>
      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 2 }}>{song.artistName}</div>
      <Space size={8} style={{ marginTop: 4, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        <span>{formatDuration(song.duration)}</span>
        <span>{formatCount(song.playCount)} 次播放</span>
        {item.listened ? <Tag bordered={false}>听过</Tag> : null}
      </Space>
      <Tooltip title={item.reason}>
        <span className={styles.reason}>{item.reason}</span>
      </Tooltip>
    </Card>
  );
}
