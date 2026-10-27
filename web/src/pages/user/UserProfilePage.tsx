import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  Avatar,
  Button,
  Card,
  Dropdown,
  Empty,
  Segmented,
  Space,
  Statistic,
  Tabs,
  Tag,
  Typography,
  type MenuProps,
} from 'antd';
import {
  EnvironmentOutlined,
  FlagOutlined,
  LockOutlined,
  MailOutlined,
  MoreOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi } from '@/api/social';
import { profileApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { CoverImage } from '@/components/common/CoverImage';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { ListSkeleton, PageLoading } from '@/components/common/PageLoading';
import { LoadMore } from '@/components/common/LoadMore';
import { FollowButton } from '@/components/social/FollowButton';
import { IntimacyCard } from '@/components/social/IntimacyCard';
import { PostCard } from '@/components/social/PostCard';
import { RecommendUsersCard } from '@/components/social/RecommendUsersCard';
import { RelationListModal } from '@/components/social/RelationListModal';
import { ReportDialog } from '@/components/social/ReportDialog';
import { useUserStore } from '@/stores/userStore';
import { formatCount, mediaUrl } from '@/utils/format';
import { getModalApi, toastSuccess, toastWarning } from '@/utils/feedback';
import type { Id } from '@/types/api';
import type {
  FanRankVO,
  ProfileAbout,
  ProfilePlaylistItem,
  ProfileStatsVO,
  RecentListeningItem,
} from '@/types/socialUpgrade';
import styles from './UserProfilePage.module.less';

const PAGE_SIZE = 20;

const ABOUT_LABELS: Record<string, string> = {
  gender: '性别',
  birthday: '生日',
  region: '地区',
  constellation: '星座',
  occupation: '职业',
  school: '学校',
  mbti: 'MBTI',
  height: '身高',
  weight: '体重',
  joinTime: '加入时间',
  userCode: '短码',
};

const GENDER_LABEL: Record<number, string> = { 1: '男', 2: '女' };

/**
 * 公开个人主页（FR-25-020）。
 *
 * `contentVisible=false` 时只渲染头部与提示，**不请求**动态/统计/歌单等接口——
 * 后端已经会拦截，但前端再少发一轮请求既省流量，也避免控制台出现一串 403。
 */
export function UserProfilePage(): JSX.Element {
  const { userId = '' } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const selfId = useUserStore((s) => s.user?.id);

  const [tab, setTab] = useState('posts');
  const [relation, setRelation] = useState<'following' | 'followers' | null>(null);
  const [reportOpen, setReportOpen] = useState(false);

  const profileQuery = useQuery({
    queryKey: socialUpgradeKeys.profile(userId),
    queryFn: () => profileApi.profile(userId),
    enabled: Boolean(userId),
  });

  const profile = profileQuery.data;
  const visible = Boolean(profile?.contentVisible);
  const self = Boolean(profile?.self || (selfId && String(selfId) === String(userId)));

  const statsQuery = useQuery({
    queryKey: socialUpgradeKeys.profileStats(userId),
    queryFn: () => profileApi.stats(userId, true),
    enabled: Boolean(userId) && visible,
  });

  const blockMutation = useMutation({
    mutationFn: () => socialApi.addBlacklist(userId),
    onSuccess: () => {
      toastSuccess('已拉黑');
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
      void queryClient.invalidateQueries({ queryKey: ['social'] });
    },
  });

  const goLogin = (): void => {
    navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
  };

  const openMessage = (): void => {
    if (!isLogin) {
      goLogin();
      return;
    }
    const nickname = profile?.nickname ?? '';
    navigate(
      `/messages?userId=${encodeURIComponent(userId)}&nickname=${encodeURIComponent(nickname)}`,
    );
  };

  const handleBlock = (): void => {
    if (!isLogin) {
      goLogin();
      return;
    }
    if (!profile) return;
    const modal = getModalApi();
    if (!modal) {
      toastWarning('确认弹窗未就绪，请稍后重试');
      return;
    }
    modal.confirm({
      title: `拉黑 ${profile.nickname ?? '该用户'}？`,
      content: '拉黑后你们将无法互相私信、评论或查看动态。',
      okText: '拉黑',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => blockMutation.mutateAsync(),
    });
  };

  if (!userId) {
    return (
      <div className="ml-page">
        <EmptyState description="用户不存在" />
      </div>
    );
  }
  if (profileQuery.isLoading) return <PageLoading tip="主页加载中…" />;
  if (profileQuery.isError) {
    return <ErrorState error={profileQuery.error} onRetry={() => void profileQuery.refetch()} />;
  }
  if (!profile) {
    return (
      <div className="ml-page">
        <EmptyState description="用户不存在或已被封禁" />
      </div>
    );
  }

  const menuItems: MenuProps['items'] = self
    ? []
    : [
        {
          key: 'report',
          icon: <FlagOutlined />,
          label: '举报',
          onClick: () => {
            if (!isLogin) {
              goLogin();
              return;
            }
            setReportOpen(true);
          },
        },
        {
          key: 'block',
          danger: true,
          icon: <StopOutlined />,
          label: '拉黑',
          onClick: handleBlock,
        },
      ];

  return (
    <div className={`ml-page ${styles.page}`}>
      <div className={styles.layout}>
        <main className={styles.main}>
          <Card className={styles.profileCard} styles={{ body: { padding: 0 } }}>
            <div
              className={styles.cover}
              style={
                profile.coverUrl
                  ? { backgroundImage: `url(${mediaUrl(profile.coverUrl)})` }
                  : undefined
              }
            />
            <div className={styles.profileBody}>
              <div className={styles.avatarRow}>
                <Avatar
                  size={88}
                  src={mediaUrl(profile.avatar)}
                  icon={<UserOutlined />}
                  className={styles.avatar}
                />
                <Space wrap>
                  {self ? (
                    <Button onClick={() => navigate('/user?tab=profile')}>编辑资料</Button>
                  ) : (
                    <>
                      <FollowButton kind="USER" targetId={profile.userId} />
                      <Button icon={<MailOutlined />} onClick={openMessage}>
                        私信
                      </Button>
                      {menuItems.length > 0 ? (
                        <Dropdown menu={{ items: menuItems }} trigger={['click']}>
                          <Button icon={<MoreOutlined />} aria-label="更多操作" />
                        </Dropdown>
                      ) : null}
                    </>
                  )}
                </Space>
              </div>

              <div className={styles.nameRow}>
                <Typography.Title level={4} className={styles.nickname}>
                  {profile.nickname ?? '用户'}
                </Typography.Title>
                {profile.certInfo ? <Tag color="gold">{profile.certInfo}</Tag> : null}
                {profile.mutual ? <Tag color="blue">互关</Tag> : null}
                {profile.banned ? <Tag color="red">已封禁</Tag> : null}
              </div>
              {profile.userCode ? (
                <Typography.Text type="secondary">@{profile.userCode}</Typography.Text>
              ) : null}
              {profile.bio ? <div className={styles.bio}>{profile.bio}</div> : null}

              <Space size={[6, 6]} wrap className={styles.metaRow}>
                {profile.gender && GENDER_LABEL[profile.gender] ? (
                  <Tag bordered={false}>{GENDER_LABEL[profile.gender]}</Tag>
                ) : null}
                {profile.region ? (
                  <Tag bordered={false} icon={<EnvironmentOutlined />}>
                    {profile.region}
                  </Tag>
                ) : null}
                {profile.birthdayText ? <Tag bordered={false}>{profile.birthdayText}</Tag> : null}
                {profile.ipRegion ? <Tag bordered={false}>IP 属地：{profile.ipRegion}</Tag> : null}
                {profile.joinTime ? (
                  <Tag bordered={false}>加入于 {profile.joinTime.slice(0, 10)}</Tag>
                ) : null}
              </Space>

              {profile.tags && profile.tags.length > 0 ? (
                <Space size={[6, 6]} wrap className={styles.tags}>
                  {profile.tags.map((tag) => (
                    <Tag key={tag} color="blue">
                      {tag}
                    </Tag>
                  ))}
                </Space>
              ) : null}

              <div className={styles.stats}>
                <StatButton
                  title="关注"
                  value={profile.followCount}
                  onClick={() => setRelation('following')}
                />
                <StatButton
                  title="粉丝"
                  value={profile.fansCount}
                  onClick={() => setRelation('followers')}
                />
                <StatButton title="动态" value={profile.postCount} />
                <StatButton title="获赞" value={formatCount(profile.likeReceivedCount)} />
              </div>
            </div>
          </Card>

          {!visible ? (
            <Card className={styles.locked}>
              <Empty
                image={<LockOutlined className={styles.lockIcon} />}
                description={profile.visibilityHint ?? '该用户设置了主页可见范围'}
              />
            </Card>
          ) : (
            <>
              <Card className={styles.statsCard} title="听歌统计">
                <ProfileStatsPanel data={statsQuery.data} loading={statsQuery.isLoading} />
              </Card>

              <Card className={styles.tabsCard} styles={{ body: { paddingTop: 8 } }}>
                <Tabs
                  activeKey={tab}
                  onChange={setTab}
                  items={[
                    {
                      key: 'posts',
                      label: `动态${profile.postCount > 0 ? ` ${profile.postCount}` : ''}`,
                      children: <UserPosts userId={profile.userId} enabled={visible} />,
                    },
                    {
                      key: 'playlists',
                      label: '歌单',
                      children: <UserPlaylists userId={profile.userId} />,
                    },
                    {
                      key: 'recent',
                      label: '最近常听',
                      children: <UserRecent userId={profile.userId} />,
                    },
                    {
                      key: 'rank',
                      label: '粉丝榜',
                      children: <UserFanRank userId={profile.userId} />,
                    },
                    {
                      key: 'about',
                      label: '关于',
                      children: <UserAbout userId={profile.userId} />,
                    },
                  ]}
                />
              </Card>
            </>
          )}
        </main>

        <aside className={styles.side}>
          {!self ? <IntimacyCard userId={profile.userId} /> : null}
          <RecommendUsersCard limit={5} />
        </aside>
      </div>

      {relation ? (
        <RelationListModal
          open
          userId={profile.userId}
          kind={relation}
          onClose={() => setRelation(null)}
        />
      ) : null}

      <ReportDialog
        open={reportOpen}
        targetType="USER"
        targetId={profile.userId}
        targetName={profile.nickname ?? '用户'}
        onClose={() => setReportOpen(false)}
      />
    </div>
  );
}

function StatButton({
  title,
  value,
  onClick,
}: {
  title: string;
  value: number | string;
  onClick?: () => void;
}): JSX.Element {
  return (
    <div
      className={`${styles.statButton} ${onClick ? styles.clickable : ''}`}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onClick={onClick}
      onKeyDown={(event) => {
        if (onClick && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault();
          onClick();
        }
      }}
    >
      <Statistic title={title} value={value} />
    </div>
  );
}

function ProfileStatsPanel({
  data,
  loading,
}: {
  data: ProfileStatsVO | undefined;
  loading: boolean;
}): JSX.Element {
  if (loading) return <ListSkeleton rows={2} />;
  if (!data) return <Typography.Text type="secondary">统计暂不可用</Typography.Text>;

  const items: JSX.Element[] = [];
  if (data.playDurationVisible) {
    items.push(
      <Statistic
        key="duration"
        title="累计播放"
        value={data.totalPlaySeconds > 0 ? (data.totalPlaySeconds / 3600).toFixed(1) : 0}
        suffix="小时"
      />,
    );
  }
  if (data.weekCountVisible) {
    items.push(<Statistic key="week" title="本周听歌" value={data.weekSongCount} suffix="首" />);
  }
  if (data.listenDaysVisible) {
    items.push(<Statistic key="days" title="听歌天数" value={data.listenDays} suffix="天" />);
  }
  if (data.topArtistVisible && (data.topArtists ?? []).length > 0) {
    items.push(
      <div key="artist" className={styles.statsList}>
        <Typography.Text type="secondary">常听歌手</Typography.Text>
        {(data.topArtists ?? []).map((artist) => (
          <Link
            key={artist.artistId}
            to={`/artist/${artist.artistId}`}
            className={styles.statsListItem}
          >
            {artist.artistName} <span>{artist.playCount} 次</span>
          </Link>
        ))}
      </div>,
    );
  }
  if (data.genreVisible && (data.genres ?? []).length > 0) {
    items.push(
      <div key="genre" className={styles.statsList}>
        <Typography.Text type="secondary">偏好风格</Typography.Text>
        {(data.genres ?? []).map((genre) => (
          <div key={genre.genre} className={styles.statsListItem}>
            {genre.genre} <span>{genre.playCount} 次</span>
          </div>
        ))}
      </div>,
    );
  }

  if (items.length === 0) {
    return <Typography.Text type="secondary">该用户未公开听歌统计</Typography.Text>;
  }
  return <div className={styles.statsGrid}>{items}</div>;
}

function UserPosts({ userId, enabled }: { userId: Id; enabled: boolean }): JSX.Element {
  const query = useInfiniteQuery({
    queryKey: socialUpgradeKeys.userPosts(userId),
    queryFn: ({ pageParam }) => profileApi.posts(userId, pageParam, PAGE_SIZE),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
    enabled,
  });

  if (query.isLoading) return <ListSkeleton rows={3} />;
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }
  const posts = query.data?.pages.flatMap((page) => page.list) ?? [];
  if (posts.length === 0) return <EmptyState compact description="还没有发布动态" />;

  return (
    <div className={styles.postList}>
      {posts.map((post) => (
        <PostCard key={post.id} post={post} />
      ))}
      <LoadMore
        hasNext={Boolean(query.hasNextPage)}
        loading={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
        total={query.data?.pages[0]?.total}
      />
    </div>
  );
}

function UserPlaylists({ userId }: { userId: Id }): JSX.Element {
  const query = useInfiniteQuery({
    queryKey: socialUpgradeKeys.profilePlaylists(userId),
    queryFn: ({ pageParam }) => profileApi.playlists(userId, pageParam, PAGE_SIZE, true),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
  });

  if (query.isLoading) return <ListSkeleton rows={2} />;
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }
  const items = query.data?.pages.flatMap((page) => page.list) ?? [];
  if (items.length === 0) return <EmptyState compact description="还没有公开歌单" />;

  return (
    <div>
      <div className={styles.playlistGrid}>
        {items.map((item: ProfilePlaylistItem) => (
          <Link key={item.id} to={`/playlist/${item.id}`} className={styles.playlistItem}>
            <CoverImage src={item.cover ?? null} alt={item.name} size={148} />
            <span className={styles.playlistName}>{item.name}</span>
            <Typography.Text type="secondary" className={styles.playlistMeta}>
              {item.songCount ?? 0} 首 · {formatCount(item.playCount ?? 0)} 次播放
            </Typography.Text>
          </Link>
        ))}
      </div>
      <LoadMore
        hasNext={Boolean(query.hasNextPage)}
        loading={query.isFetchingNextPage}
        onLoadMore={() => void query.fetchNextPage()}
      />
    </div>
  );
}

function UserRecent({ userId }: { userId: Id }): JSX.Element {
  const query = useQuery({
    queryKey: socialUpgradeKeys.recentListening(userId),
    queryFn: () => profileApi.recentListening(userId, true),
  });

  if (query.isLoading) return <ListSkeleton rows={3} />;
  if (query.isError || !query.data) {
    return <EmptyState compact description="最近常听加载失败或未公开" />;
  }
  if (query.data.length === 0) {
    return <EmptyState compact description="没有公开最近常听，或暂未听歌" />;
  }
  return (
    <div className={styles.recentList}>
      {query.data.map((item: RecentListeningItem) => (
        <Link
          key={`${item.songId}-${item.playTime}`}
          to={`/song/${item.songId}`}
          className={styles.recentItem}
        >
          <span className={styles.recentName}>{item.name}</span>
          <span className={styles.recentSub}>{item.artistName}</span>
          <Typography.Text type="secondary" className={styles.recentTime}>
            {String(item.playTime ?? '').slice(0, 16)}
          </Typography.Text>
        </Link>
      ))}
    </div>
  );
}

function UserFanRank({ userId }: { userId: Id }): JSX.Element {
  const [type, setType] = useState(1);
  const query = useQuery({
    queryKey: socialUpgradeKeys.fansRank(userId, type),
    queryFn: () => profileApi.fansRank(userId, type),
    retry: false,
  });

  return (
    <div>
      <Segmented
        value={type}
        onChange={(value) => setType(value as number)}
        options={[
          { label: '铁粉榜', value: 1 },
          { label: '互动榜', value: 2 },
          { label: '礼物榜', value: 3 },
        ]}
        className={styles.rankSwitch}
      />
      <FanRankList loading={query.isLoading} data={query.data} />
    </div>
  );
}

function FanRankList({
  loading,
  data,
}: {
  loading: boolean;
  data: FanRankVO | undefined;
}): JSX.Element {
  if (loading) return <ListSkeleton rows={3} />;
  if (!data || !data.visible) return <EmptyState compact description="该用户未开启粉丝榜" />;
  if (data.list.length === 0) return <EmptyState compact description="榜单还没有数据" />;

  return (
    <div className={styles.rankList}>
      {data.list.map((item) => (
        <Link key={item.userId} to={`/user/${item.userId}`} className={styles.rankItem}>
          <span className={`${styles.rankNo} ${item.rank <= 3 ? styles.rankTop : ''}`}>
            {item.rank}
          </span>
          <Avatar size={32} src={item.avatar ?? undefined} icon={<UserOutlined />} />
          <span className={styles.rankName}>
            {item.nickname ?? '用户'}
            {item.badge ? (
              <Tag color="gold" className={styles.rankBadge}>
                铁粉
              </Tag>
            ) : null}
          </span>
          <Typography.Text type="secondary">{item.score} 分</Typography.Text>
        </Link>
      ))}
      {data.statDate ? (
        <Typography.Text type="secondary" className={styles.rankDate}>
          统计日期：{data.statDate}
        </Typography.Text>
      ) : null}
    </div>
  );
}

function UserAbout({ userId }: { userId: Id }): JSX.Element {
  const query = useQuery({
    queryKey: socialUpgradeKeys.about(userId),
    queryFn: () => profileApi.about(userId, true),
  });

  if (query.isLoading) return <ListSkeleton rows={2} />;
  if (query.isError) return <EmptyState compact description="关于我加载失败或未公开" />;

  const entries = aboutEntries(query.data);
  if (entries.length === 0) return <EmptyState compact description="还没有填写更多资料" />;

  return (
    <div className={styles.aboutList}>
      {entries.map(([key, value]) => (
        <div key={key} className={styles.aboutRow}>
          <span className={styles.aboutKey}>{ABOUT_LABELS[key] ?? key}</span>
          <span className={styles.aboutValue}>{formatAboutValue(value)}</span>
        </div>
      ))}
    </div>
  );
}

export function aboutEntries(data: ProfileAbout | undefined): [string, unknown][] {
  return Object.entries(data ?? {})
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .filter(([key]) => !['avatar', 'coverUrl'].includes(key))
    .filter(([, value]) => !(Array.isArray(value) && value.length === 0));
}

function formatAboutValue(value: unknown): string {
  if (Array.isArray(value)) return value.map((item) => String(item)).join('、');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
