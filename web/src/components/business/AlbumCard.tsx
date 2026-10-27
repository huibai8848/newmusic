import { Card, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { AlbumBrief } from '@/types/api';
import { CoverImage } from '@/components/common/CoverImage';
import { HighlightText } from '@/components/common/HighlightText';

/**
 * 专辑卡片（搜索页专辑 Tab / 歌手页专辑列表）。
 *
 * 收 `AlbumBrief` 而不是 `Album`：搜索 VO 的发版字段叫 `publishTime`，
 * 曲库实体叫 `publishDate`，这里取二者之一即可。
 */
interface Props {
  album: AlbumBrief;
  keyword?: string;
}

export function AlbumCard({ album, keyword }: Props): JSX.Element {
  const navigate = useNavigate();
  // 曲库实体与搜索 VO 各带一个发版字段，取到哪个用哪个
  const publishDate = album.publishDate ?? album.publishTime ?? null;
  const publishYear = publishDate ? publishDate.slice(0, 4) : '';

  return (
    <Card
      hoverable
      size="small"
      onClick={() => navigate(`/album/${album.id}`)}
      styles={{ body: { padding: 12 } }}
      cover={
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 12 }}>
          <CoverImage src={album.cover} alt={album.name} size={140} />
        </div>
      }
    >
      <Typography.Text strong ellipsis style={{ display: 'block', color: 'var(--color-text)' }} title={album.name}>
        <HighlightText text={album.name} keyword={keyword} />
      </Typography.Text>
      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
        <HighlightText text={album.artistName} keyword={keyword} />
      </div>
      <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 4 }}>
        {album.songCount ?? 0} 首{publishYear ? ` · ${publishYear}` : ''}
      </div>
    </Card>
  );
}
