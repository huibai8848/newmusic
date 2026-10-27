import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, Space, Tabs, Tag, Typography } from 'antd';
import { CaretRightOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { albumApi, albumKeys, artistApi, artistKeys } from '@/api/catalog';
import { songApi, songKeys } from '@/api/song';
import { useInfiniteList } from '@/hooks/useInfiniteList';
import { usePlayerStore } from '@/stores/playerStore';
import { CoverImage } from '@/components/common/CoverImage';
import { PageLoading, ListSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { LoadMore } from '@/components/common/LoadMore';
import { SongTable } from '@/components/business/SongTable';
import { AlbumCard } from '@/components/business/AlbumCard';
import { FollowButton } from '@/components/social/FollowButton';
import styles from './ArtistDetailPage.module.less';

/**
 * 歌手详情页（模块 02 T113 / PRD FR-11-003）。
 *
 * 头像 + 简介 + 代表作品 + 全部歌曲 + 专辑；数据全部来自模块 01 的曲库接口。
 */

const PAGE_SIZE = 20;

function ArtistSongs({ artistId }: { artistId: string }): JSX.Element {
  const playList = usePlayerStore((s) => s.playList);
  const list = useInfiniteList({
    queryKey: songKeys.list({ artistId, pageSize: PAGE_SIZE }),
    queryFn: (pageNum, pageSize) => songApi.list({ artistId, pageNum, pageSize }),
    pageSize: PAGE_SIZE,
  });

  if (list.isLoading) return <ListSkeleton rows={5} />;
  if (list.isError) return <ErrorState compact error={list.error} onRetry={list.reload} />;
  if (list.items.length === 0) return <EmptyState description="该歌手暂无歌曲" />;

  return (
    <>
      <SongTable songs={list.items} onPlay={(_s, index) => playList(list.items, index, 'artist')} />
      <LoadMore hasNext={list.hasNext} loading={list.isFetchingNextPage} onLoadMore={list.loadMore} total={list.total} />
    </>
  );
}

function ArtistAlbums({ artistId }: { artistId: string }): JSX.Element {
  const list = useInfiniteList({
    queryKey: albumKeys.list({ artistId, pageSize: 12 }),
    queryFn: (pageNum, pageSize) => albumApi.list({ artistId, pageNum, pageSize }),
    pageSize: 12,
  });

  if (list.isLoading) return <ListSkeleton rows={3} />;
  if (list.isError) return <ErrorState compact error={list.error} onRetry={list.reload} />;
  if (list.items.length === 0) return <EmptyState description="该歌手暂无专辑" />;

  return (
    <>
      <div className={styles.grid}>
        {list.items.map((album) => (
          <AlbumCard key={album.id} album={album} />
        ))}
      </div>
      <LoadMore hasNext={list.hasNext} loading={list.isFetchingNextPage} onLoadMore={list.loadMore} total={list.total} />
    </>
  );
}

export function ArtistDetailPage(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState('songs');
  const playList = usePlayerStore((s) => s.playList);

  const artistQuery = useQuery({
    queryKey: artistKeys.detail(id ?? ''),
    queryFn: () => artistApi.detail(id as string),
    enabled: Boolean(id),
  });

  const songsQuery = useQuery({
    queryKey: songKeys.list({ artistId: id, pageSize: 20, sortBy: 'hotScore', sortOrder: 'desc' }),
    queryFn: () => songApi.list({ artistId: id, pageSize: 20, sortBy: 'hotScore', sortOrder: 'desc' }),
    enabled: Boolean(id),
  });

  if (!id) return <ErrorState title="参数错误" error={new Error('缺少歌手 ID')} />;
  if (artistQuery.isLoading) return <PageLoading />;
  if (artistQuery.isError || !artistQuery.data) {
    return <ErrorState title="歌手信息加载失败" error={artistQuery.error} onRetry={() => void artistQuery.refetch()} />;
  }

  const artist = artistQuery.data;
  const hotSongs = songsQuery.data?.list ?? [];
  /** 注意：不要命名为 `styles`，会遮蔽上面 CSS Modules 的 `styles` 导入 */
  const styleTags = (artist.styles ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <CoverImage src={artist.avatar ?? artist.cover} alt={artist.name} size={160} shape="circle" />
        <div className={styles.info}>
          <h1 className={styles.title}>{artist.name}</h1>
          {artist.alias ? (
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              {artist.alias}
            </Typography.Text>
          ) : null}
          <div className={styles.meta}>
            {artist.country ? <span>地区：{artist.country}</span> : null}
            <span>歌曲：{artist.songCount}</span>
            <span>专辑：{artist.albumCount}</span>
            <span>粉丝：{artist.followerCount}</span>
          </div>
          {styleTags.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              {styleTags.map((style) => (
                <Tag key={style} color="blue" bordered={false}>
                  {style}
                </Tag>
              ))}
            </div>
          )}
          {artist.description ? (
            <Typography.Paragraph type="secondary" style={{ maxWidth: 720 }}>
              {artist.description}
            </Typography.Paragraph>
          ) : null}
          <Space wrap>
            <Button
              type="primary"
              icon={<CaretRightOutlined />}
              disabled={hotSongs.length === 0}
              onClick={() => playList(hotSongs, 0, 'artist')}
            >
              播放热门歌曲
            </Button>
            <FollowButton kind="ARTIST" targetId={artist.id} />
          </Space>
        </div>
      </div>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'songs', label: '歌曲', children: <ArtistSongs artistId={id} /> },
          { key: 'albums', label: '专辑', children: <ArtistAlbums artistId={id} /> },
        ]}
      />
    </div>
  );
}
