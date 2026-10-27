import { Link, useParams } from 'react-router-dom';
import { Button, Typography } from 'antd';
import { CaretRightOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { albumApi, albumKeys } from '@/api/catalog';
import { songApi, songKeys } from '@/api/song';
import { useInfiniteList } from '@/hooks/useInfiniteList';
import { usePlayerStore } from '@/stores/playerStore';
import { CoverImage } from '@/components/common/CoverImage';
import { PageLoading, ListSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { LoadMore } from '@/components/common/LoadMore';
import { SongTable } from '@/components/business/SongTable';
import { AddToPlaylistButton } from '@/components/business/AddToPlaylistButton';
import { ActionButtons } from '@/components/social/ActionButtons';
import { CommentSection } from '@/components/social/CommentSection';
import { SectionBoundary } from '@/components/common/SectionBoundary';
import styles from './AlbumDetailPage.module.less';

/** 专辑详情页（模块 02 T113）：专辑信息 + 曲目列表 */

const PAGE_SIZE = 30;

export function AlbumDetailPage(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const playList = usePlayerStore((s) => s.playList);

  const albumQuery = useQuery({
    queryKey: albumKeys.detail(id ?? ''),
    queryFn: () => albumApi.detail(id as string),
    enabled: Boolean(id),
  });

  const songs = useInfiniteList({
    queryKey: songKeys.list({ albumId: id, pageSize: PAGE_SIZE }),
    queryFn: (pageNum, pageSize) => songApi.list({ albumId: id, pageNum, pageSize }),
    pageSize: PAGE_SIZE,
    enabled: Boolean(id),
  });

  if (!id) return <ErrorState title="参数错误" error={new Error('缺少专辑 ID')} />;
  if (albumQuery.isLoading) return <PageLoading />;
  if (albumQuery.isError || !albumQuery.data) {
    return <ErrorState title="专辑加载失败" error={albumQuery.error} onRetry={() => void albumQuery.refetch()} />;
  }

  const album = albumQuery.data;
  const songIds = songs.items.map((s) => s.id);

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <CoverImage src={album.cover} alt={album.name} size={200} />
        <div className={styles.info}>
          <h1 className={styles.title}>{album.name}</h1>
          <div className={styles.meta}>
            <span>
              歌手：
              <Link to={`/artist/${album.artistId}`} style={{ color: 'var(--color-primary)' }}>
                {album.artistName}
              </Link>
            </span>
            {album.publishDate ? <span>发行：{album.publishDate}</span> : null}
            {album.company ? <span>发行方：{album.company}</span> : null}
            {album.language ? <span>语种：{album.language}</span> : null}
            <span>曲目：{album.songCount}</span>
          </div>
          {album.description ? (
            <Typography.Paragraph type="secondary" style={{ maxWidth: 720 }}>
              {album.description}
            </Typography.Paragraph>
          ) : null}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <Button
              type="primary"
              icon={<CaretRightOutlined />}
              disabled={songs.items.length === 0}
              onClick={() => playList(songs.items, 0, 'album')}
            >
              播放全部
            </Button>
            <AddToPlaylistButton songIds={songIds} disabled={songIds.length === 0} />
            <ActionButtons
              targetType="ALBUM"
              targetId={album.id}
              likeCount={album.likeCount}
              collectCount={album.collectCount}
            />
          </div>
        </div>
      </div>

      {songs.isLoading ? (
        <ListSkeleton rows={6} />
      ) : songs.isError ? (
        <ErrorState error={songs.error} onRetry={songs.reload} />
      ) : songs.items.length === 0 ? (
        <EmptyState description="该专辑暂无歌曲" />
      ) : (
        <>
          <SongTable songs={songs.items} showAlbum={false} onPlay={(_s, index) => playList(songs.items, index, 'album')} />
          <LoadMore
            hasNext={songs.hasNext}
            loading={songs.isFetchingNextPage}
            onLoadMore={songs.loadMore}
            total={songs.total}
          />
        </>
      )}

      <SectionBoundary title="评论">
        <CommentSection targetType="ALBUM" targetId={album.id} />
      </SectionBoundary>
    </div>
  );
}
