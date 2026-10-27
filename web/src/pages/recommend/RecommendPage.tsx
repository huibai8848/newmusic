import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Alert, Avatar, Button, Space, Typography } from 'antd';
import { ReloadOutlined, SettingOutlined, SoundOutlined } from '@ant-design/icons';
import { recommendApi, recommendKeys, type RecommendItem } from '@/api/recommend';
import { Section } from '@/components/common/Section';
import { SectionBoundary } from '@/components/common/SectionBoundary';
import { CardSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { SongTable } from '@/components/business/SongTable';
import { RecommendSongCard } from '@/components/business/RecommendSongCard';
import { ContinuePlayList } from '@/components/recommend/ContinuePlayList';
import { QuestionnaireModal } from '@/components/recommend/QuestionnaireModal';
import { BlocklistDrawer } from '@/components/recommend/BlocklistDrawer';
import { usePlayerStore } from '@/stores/playerStore';
import { track } from '@/services/track';
import { formatCount } from '@/utils/format';
import styles from './RecommendPage.module.less';

/**
 * 推荐页（模块 08）。
 *
 * 四个区块各自请求、各自兜底：每日推荐失败不影响猜你喜欢，继续播放失败更不应该影响整页。
 * 每日推荐在展示后上报一次曝光（BR-17-003），服务端据此避免连续多天推同一批歌。
 */

function PlayAllButton({ items }: { items: RecommendItem[] }): JSX.Element {
  const playList = usePlayerStore((s) => s.playList);
  return (
    <Button
      type="primary"
      ghost
      icon={<SoundOutlined />}
      disabled={items.length === 0}
      onClick={() => playList(items.map((item) => item.song), 0, 'recommend')}
    >
      播放全部
    </Button>
  );
}

export function RecommendPage(): JSX.Element {
  const [questionnaireOpen, setQuestionnaireOpen] = useState(false);
  const [blocklistOpen, setBlocklistOpen] = useState(false);
  const playList = usePlayerStore((s) => s.playList);

  const daily = useQuery({
    queryKey: recommendKeys.daily(),
    queryFn: recommendApi.daily,
  });

  const guess = useInfiniteQuery({
    queryKey: [...recommendKeys.all, 'guess', 'infinite'] as const,
    initialPageParam: 1,
    queryFn: ({ pageParam }) => recommendApi.guess(pageParam, 20),
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  });

  const frequent = useQuery({
    queryKey: recommendKeys.frequentArtists(),
    queryFn: recommendApi.frequentArtists,
  });

  const recent = useQuery({
    queryKey: recommendKeys.recentSongs(12),
    queryFn: () => recommendApi.recentSongs(12),
  });

  // 曝光上报：每次页面打开、每天一次（服务端是 Set，重复上报也无副作用）
  const exposedDate = useRef<string | null>(null);
  useEffect(() => {
    const data = daily.data;
    if (!data || data.items.length === 0 || exposedDate.current === data.date) return;
    exposedDate.current = data.date;
    void recommendApi.exposure(data.items.map((item) => item.song.id), 'DAILY');
  }, [daily.data]);

  // 模块 17：曝光按「卡片被渲染」计，同一首歌在同一场景只报一次（换一批/翻页才会再来）
  const exposedCards = useRef(new Set<string>());
  useEffect(() => {
    const cards: Array<{ item: RecommendItem; scene: string }> = [
      ...(daily.data?.items ?? []).map((item) => ({ item, scene: 'DAILY' })),
      ...(guess.data?.pages ?? []).flatMap((page) =>
        page.items.map((item) => ({ item, scene: 'GUESS' })),
      ),
    ];
    for (const { item, scene } of cards) {
      const key = `${scene}:${item.song.id}`;
      if (exposedCards.current.has(key)) continue;
      exposedCards.current.add(key);
      track('recommend_exposure', {
        song_id: String(item.song.id),
        scene,
        position: item.position,
        recall: item.recall,
      });
    }
  }, [daily.data, guess.data]);

  const dailyItems = daily.data?.items ?? [];
  const guessPages = guess.data?.pages ?? [];
  const guessItems = guessPages.flatMap((page) => page.items);
  const coldStart = guessPages[0]?.coldStart ?? false;

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <div>
          <h1 className={styles.title}>为你推荐</h1>
          <p className={styles.subtitle}>
            根据你的播放、喜欢和屏蔽记录生成；听得多，推荐会越来越准。
          </p>
        </div>
        <Space wrap>
          <Button icon={<SettingOutlined />} onClick={() => setQuestionnaireOpen(true)}>
            口味偏好
          </Button>
          <Button onClick={() => setBlocklistOpen(true)}>屏蔽管理</Button>
        </Space>
      </div>

      {coldStart ? (
        <Alert
          className={styles.notice}
          type="info"
          showIcon
          message="你的听歌记录还不多，当前推荐以热门和问卷偏好为主"
          description="完成口味问卷（风格 / 语种 / 歌手 / 心情）可以立刻改善推荐结果。"
          action={
            <Button size="small" type="primary" onClick={() => setQuestionnaireOpen(true)}>
              去填写
            </Button>
          }
        />
      ) : null}

      <SectionBoundary title="每日推荐">
        <Section
          title="每日推荐"
          subtitle={
            daily.data
              ? `${daily.data.date} · 每天 0 点更新${daily.data.fallback ? ' · 热门兜底' : ''}`
              : '每天 0 点更新'
          }
          extra={<PlayAllButton items={dailyItems} />}
        >
          {daily.isLoading ? <CardSkeleton cards={6} /> : null}
          {daily.isError ? (
            <ErrorState compact error={daily.error} onRetry={() => void daily.refetch()} />
          ) : null}
          {!daily.isLoading && !daily.isError && dailyItems.length === 0 ? (
            <EmptyState compact description="暂时没有可推荐的歌曲，先随便听听吧" />
          ) : null}
          {dailyItems.length > 0 ? (
            <div className={styles.grid}>
              {dailyItems.map((item, index) => (
                <RecommendSongCard
                  key={String(item.song.id)}
                  item={item}
                  scene="DAILY"
                  position={index + 1}
                  list={dailyItems.map((i) => i.song)}
                />
              ))}
            </div>
          ) : null}
        </Section>
      </SectionBoundary>

      <SectionBoundary title="猜你喜欢">
        <Section
          title="猜你喜欢"
          subtitle="基于最近播放的歌手、风格和语种实时生成"
          extra={
            guessItems.length > 0 ? (
              <Button
                icon={<ReloadOutlined />}
                onClick={() => void guess.refetch()}
                loading={guess.isRefetching && !guess.isFetchingNextPage}
              >
                换一批
              </Button>
            ) : undefined
          }
        >
          {guess.isLoading ? <CardSkeleton cards={6} /> : null}
          {guess.isError ? (
            <ErrorState compact error={guess.error} onRetry={() => void guess.refetch()} />
          ) : null}
          {!guess.isLoading && !guess.isError && guessItems.length === 0 ? (
            <EmptyState compact description="还没有足够的行为数据，先听几首歌吧" />
          ) : null}
          {guessItems.length > 0 ? (
            <>
              <div className={styles.grid}>
                {guessItems.map((item, index) => (
                  <RecommendSongCard
                    key={String(item.song.id)}
                    item={item}
                    scene="GUESS"
                    position={index + 1}
                    list={guessItems.map((i) => i.song)}
                  />
                ))}
              </div>
              <div className={styles.more}>
                {guess.hasNextPage ? (
                  <Button onClick={() => void guess.fetchNextPage()} loading={guess.isFetchingNextPage}>
                    加载更多
                  </Button>
                ) : (
                  <Typography.Text type="secondary">没有更多了</Typography.Text>
                )}
              </div>
            </>
          ) : null}
        </Section>
      </SectionBoundary>

      <SectionBoundary title="继续播放">
        <Section title="继续播放" subtitle="上次听到一半的歌曲">
          <ContinuePlayList showEmpty />
        </Section>
      </SectionBoundary>

      <SectionBoundary title="最近常听">
        <Section title="最近常听" subtitle="近 30 天播放最多的歌手">
          {frequent.isLoading ? <CardSkeleton cards={4} /> : null}
          {frequent.isError ? (
            <ErrorState compact error={frequent.error} onRetry={() => void frequent.refetch()} />
          ) : null}
          {!frequent.isLoading && !frequent.isError && (frequent.data ?? []).length === 0 ? (
            <EmptyState compact description="还没有常听歌手" />
          ) : null}
          {(frequent.data ?? []).length > 0 ? (
            <div className={styles.artistGrid}>
              {(frequent.data ?? []).map((artist) => (
                <Link key={String(artist.artistId)} to={`/artist/${artist.artistId}`} className={styles.artistCard}>
                  <Avatar src={artist.avatar ?? undefined} size={44}>
                    {artist.artistName.slice(0, 1)}
                  </Avatar>
                  <div className={styles.artistInfo}>
                    <Typography.Text strong ellipsis style={{ display: 'block' }}>
                      {artist.artistName}
                    </Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {formatCount(artist.playCount)} 次播放
                    </Typography.Text>
                  </div>
                </Link>
              ))}
            </div>
          ) : null}
        </Section>
      </SectionBoundary>

      <SectionBoundary title="最近播放">
        <Section title="最近播放" subtitle="最近 12 首" moreText="更多" moreTo="/user">
          {recent.isLoading ? <CardSkeleton cards={4} /> : null}
          {recent.isError ? (
            <ErrorState compact error={recent.error} onRetry={() => void recent.refetch()} />
          ) : null}
          {!recent.isLoading && !recent.isError && (recent.data ?? []).length === 0 ? (
            <EmptyState compact description="还没有播放记录" />
          ) : null}
          {(recent.data ?? []).length > 0 ? (
            <SongTable
              songs={recent.data ?? []}
              showCover
              onPlay={(_song, index) => playList(recent.data ?? [], index, 'recommend')}
            />
          ) : null}
        </Section>
      </SectionBoundary>

      <QuestionnaireModal open={questionnaireOpen} onClose={() => setQuestionnaireOpen(false)} />
      <BlocklistDrawer open={blocklistOpen} onClose={() => setBlocklistOpen(false)} />
    </div>
  );
}
