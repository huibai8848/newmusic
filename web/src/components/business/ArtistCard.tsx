import { Card, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { ArtistBrief } from '@/types/api';
import { CoverImage } from '@/components/common/CoverImage';
import { HighlightText } from '@/components/common/HighlightText';

/**
 * 歌手卡片（搜索页歌手 Tab / 首页歌手推荐）。
 *
 * 收 `ArtistBrief` 而不是 `Artist`：搜索接口返回的 `SearchArtistVO` 没有
 * `albumCount`，「N 首歌 · M 张专辑」里的后半段在搜索场景下就省略——
 * 与其编一个 0，不如只说有的那句。
 */
interface Props {
  artist: ArtistBrief;
  keyword?: string;
}

export function ArtistCard({ artist, keyword }: Props): JSX.Element {
  const navigate = useNavigate();

  return (
    <Card
      hoverable
      size="small"
      onClick={() => navigate(`/artist/${artist.id}`)}
      styles={{ body: { padding: 12, textAlign: 'center' } }}
    >
      <CoverImage src={artist.avatar ?? artist.cover} alt={artist.name} size={96} shape="circle" />
      <Typography.Text strong ellipsis style={{ display: 'block', marginTop: 8, color: 'var(--color-text)' }}>
        <HighlightText text={artist.name} keyword={keyword} />
      </Typography.Text>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {artist.songCount ?? 0} 首歌
        {artist.albumCount != null ? ` · ${artist.albumCount} 张专辑` : ''}
      </Typography.Text>
    </Card>
  );
}
