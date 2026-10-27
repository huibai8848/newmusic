import { useQuery } from '@tanstack/react-query';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { artistApi, artistKeys } from '@/api/catalog';
import { playlistApi, playlistKeys } from '@/api/playlist';
import { recommendApi, recommendKeys } from '@/api/recommend';
import { songApi, songKeys } from '@/api/song';
import { Section } from '@/components/common/Section';
import { SectionBoundary } from '@/components/common/SectionBoundary';
import { CardSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { SongCard } from '@/components/business/SongCard';
import { PlaylistCard } from '@/components/business/PlaylistCard';
import { ArtistCard } from '@/components/business/ArtistCard';
import { RecommendSongCard } from '@/components/business/RecommendSongCard';
import { ContinuePlayList } from '@/components/recommend/ContinuePlayList';
import { usePlayerStore } from '@/stores/playerStore';
import { useUserStore } from '@/stores/userStore';
import styles from './HomePage.module.less';

/**
 * 首页（模块 02 T110 / PRD FR-11-001）。
 *
 * 每个区块**独立请求**并各自包在 `<SectionBoundary>` 里：
 * 任一区块接口挂掉只影响它自己，其余照常展示（AC-11-204）。
 */

function RecommendedPlaylists(): JSX.Element {
  const query = useQuery({
    queryKey: playlistKeys.list({ isRecommend: 1, pageSize: 10 }),
    queryFn: () => playlistApi.list({ isRecommend: 1, pageSize: 10 }),
  });

  if (query.isLoading) return <CardSkeleton cards={5} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const list = query.data?.list ?? [];
  if (list.length === 0) return <EmptyState compact description="暂无推荐歌单" />;

  return (
    <div className={styles.grid}>
      {list.map((item) => (
        <PlaylistCard key={item.id} playlist={item} />
      ))}
    </div>
  );
}

function NewSongs(): JSX.Element {
  const query = useQuery({
    queryKey: songKeys.list({ pageSize: 10, sortBy: 'releaseDate', sortOrder: 'desc' }),
    queryFn: () => songApi.list({ pageSize: 10, sortBy: 'releaseDate', sortOrder: 'desc' }),
  });
  const playList = usePlayerStore((s) => s.playList);

  if (query.isLoading) return <CardSkeleton />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const list = query.data?.list ?? [];
  if (list.length === 0) return <EmptyState compact description="暂无新歌" />;

  return (
    <>
      <div style={{ marginBottom: 12 }}>
        <Button type="primary" ghost onClick={() => playList(list, 0, 'home')}>
          播放全部
        </Button>
      </div>
      <div className={styles.grid}>
        {list.map((song) => (
          <SongCard key={song.id} song={song} list={list} />
        ))}
      </div>
    </>
  );
}

function HotArtists(): JSX.Element {
  const query = useQuery({
    queryKey: artistKeys.list({ pageSize: 10, sortBy: 'hotScore', sortOrder: 'desc' }),
    queryFn: () => artistApi.list({ pageSize: 10, sortBy: 'hotScore', sortOrder: 'desc' }),
  });

  if (query.isLoading) return <CardSkeleton cards={6} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const list = query.data?.list ?? [];
  if (list.length === 0) return <EmptyState compact description="暂无歌手" />;

  return (
    <div className={styles.artistGrid}>
      {list.map((artist) => (
        <ArtistCard key={artist.id} artist={artist} />
      ))}
    </div>
  );
}

/** 未登录时退回热门榜：游客没有画像，展示个性化推荐只会是「假个性化」 */
function HotSongs(): JSX.Element {
  const query = useQuery({
    queryKey: songKeys.list({ pageSize: 12, sortBy: 'hotScore', sortOrder: 'desc', pageNum: 1 }),
    queryFn: () => songApi.list({ pageSize: 12, sortBy: 'hotScore', sortOrder: 'desc', pageNum: 1 }),
  });
  const playList = usePlayerStore((s) => s.playList);

  if (query.isLoading) return <CardSkeleton cards={6} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const list = query.data?.list ?? [];
  if (list.length === 0) return <EmptyState compact description="暂无内容" />;

  return (
    <>
      <div style={{ marginBottom: 12 }}>
        <Button type="primary" ghost onClick={() => playList(list, 0, 'home')}>
          播放全部
        </Button>
      </div>
      <div className={styles.grid}>
        {list.map((song) => (
          <SongCard key={song.id} song={song} list={list} source="home" />
        ))}
      </div>
    </>
  );
}

/** 每日推荐首页预览：只展示前 8 首，完整 30 首在 /recommend */
function DailyPicks(): JSX.Element {
  const navigate = useNavigate();
  const isLogin = useUserStore((s) => s.isLogin);
  const query = useQuery({
    queryKey: recommendKeys.daily(),
    queryFn: recommendApi.daily,
    enabled: isLogin,
  });

  if (!isLogin) {
    return (
      <EmptyState
        compact
        description="登录后每天为你生成 30 首推荐"
        actionText="去登录"
        onAction={() => navigate('/login?redirect=%2Frecommend')}
      />
    );
  }
  if (query.isLoading) return <CardSkeleton cards={6} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;

  const items = (query.data?.items ?? []).slice(0, 8);
  if (items.length === 0) return <EmptyState compact description="暂时没有可推荐的歌曲" />;
  const list = items.map((item) => item.song);

  return (
    <div className={styles.grid}>
      {items.map((item, index) => (
        <RecommendSongCard key={String(item.song.id)} item={item} scene="DAILY" position={index + 1} list={list} />
      ))}
    </div>
  );
}

/** 猜你喜欢首页预览：登录后取真实推荐，游客退回热门 */
function GuessSongs(): JSX.Element {
  const isLogin = useUserStore((s) => s.isLogin);
  const query = useQuery({
    queryKey: recommendKeys.guess(1),
    queryFn: () => recommendApi.guess(1, 10),
    enabled: isLogin,
  });

  if (!isLogin) return <HotSongs />;
  if (query.isLoading) return <CardSkeleton cards={6} />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;

  const items = query.data?.items ?? [];
  if (items.length === 0) return <EmptyState compact description="先听几首歌，我们再猜你喜欢" />;
  const list = items.map((item) => item.song);

  return (
    <div className={styles.grid}>
      {items.map((item, index) => (
        <RecommendSongCard key={String(item.song.id)} item={item} scene="GUESS" position={index + 1} list={list} />
      ))}
    </div>
  );
}

export function HomePage(): JSX.Element {
  const isLogin = useUserStore((s) => s.isLogin);

  return (
    <div className="ml-page">
      <SectionBoundary title="每日推荐">
        <Section title="每日推荐" subtitle="每天 0 点更新" moreText="查看全部" moreTo="/recommend">
          <DailyPicks />
        </Section>
      </SectionBoundary>

      <SectionBoundary title="猜你喜欢">
        <Section
          title="猜你喜欢"
          subtitle={isLogin ? '根据你的播放记录实时生成' : '登录后根据你的口味推荐，当前展示热门'}
          moreText="更多"
          moreTo="/recommend"
        >
          <GuessSongs />
        </Section>
      </SectionBoundary>

      {isLogin ? (
        <SectionBoundary title="继续播放">
          <Section title="继续播放" subtitle="上次听到一半的歌曲" moreText="查看全部" moreTo="/recommend">
            <ContinuePlayList />
          </Section>
        </SectionBoundary>
      ) : null}

      <SectionBoundary title="推荐歌单">
        <Section title="推荐歌单" moreText="更多" moreTo="/search?type=playlist">
          <RecommendedPlaylists />
        </Section>
      </SectionBoundary>

      <SectionBoundary title="新歌速递">
        <Section title="新歌速递" subtitle="按发行日期排序">
          <NewSongs />
        </Section>
      </SectionBoundary>

      <SectionBoundary title="热门歌手">
        <Section title="热门歌手">
          <HotArtists />
        </Section>
      </SectionBoundary>
    </div>
  );
}
