import { Card, Space, Tag, Typography } from 'antd';
import { LockOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { PlaylistBrief } from '@/types/api';
import { formatCount } from '@/utils/format';
import { CoverImage } from '@/components/common/CoverImage';

/**
 * 歌单卡片（首页推荐歌单 / 歌单广场 / 搜索页歌单 Tab）。
 *
 * 收 `PlaylistBrief` 而不是 `Playlist`：搜索 VO 不带 `tags` 与 `isPublic`，
 * 缺了就少渲染一行标签、不画锁图标——这两个字段本来就是「有则更好」的附加信息。
 */
interface Props {
  playlist: PlaylistBrief;
}

export function PlaylistCard({ playlist }: Props): JSX.Element {
  const navigate = useNavigate();
  const tags = (playlist.tags ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 2);

  return (
    <Card
      hoverable
      size="small"
      onClick={() => navigate(`/playlist/${playlist.id}`)}
      styles={{ body: { padding: 12 } }}
      cover={
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 12 }}>
          <div style={{ position: 'relative' }}>
            <CoverImage src={playlist.cover} alt={playlist.name} size={140} />
            <PlayCircleOutlined
              style={{
                position: 'absolute',
                right: 6,
                bottom: 6,
                fontSize: 22,
                color: 'var(--color-bg)',
                filter: 'drop-shadow(0 0 4px rgba(0,0,0,.45))',
              }}
            />
          </div>
        </div>
      }
    >
      <Typography.Text strong ellipsis style={{ display: 'block', color: 'var(--color-text)' }} title={playlist.name}>
        {playlist.isPublic === 0 ? <LockOutlined style={{ marginRight: 4 }} /> : null}
        {playlist.name}
      </Typography.Text>
      <Space size={6} style={{ marginTop: 6, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        <span>{playlist.songCount} 首</span>
        <span>{formatCount(playlist.playCount)} 播放</span>
      </Space>
      {tags.length > 0 && (
        <div style={{ marginTop: 6 }}>
          {tags.map((tag) => (
            <Tag key={tag} color="blue" bordered={false} style={{ fontSize: 11 }}>
              {tag}
            </Tag>
          ))}
        </div>
      )}
    </Card>
  );
}
