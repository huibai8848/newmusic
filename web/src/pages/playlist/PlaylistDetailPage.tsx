import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button, Popconfirm, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CaretRightOutlined,
  DeleteOutlined,
  EditOutlined,
  PauseOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { playlistApi, playlistKeys } from '@/api/playlist';
import { ApiError } from '@/api/request';
import { songKeys } from '@/api/song';
import type { Id, Playlist, Song } from '@/types/api';
import { formatCount, formatDuration } from '@/utils/format';
import { usePlayerStore } from '@/stores/playerStore';
import { usePerm } from '@/hooks/usePerm';
import { useUserStore } from '@/stores/userStore';
import { CoverImage } from '@/components/common/CoverImage';
import { PageLoading, ListSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { AddToPlaylistButton } from '@/components/business/AddToPlaylistButton';
import { ActionButtons } from '@/components/social/ActionButtons';
import { CommentSection } from '@/components/social/CommentSection';
import { SectionBoundary } from '@/components/common/SectionBoundary';
import { PlaylistFormModal } from '@/components/business/PlaylistFormModal';
import { toastSuccess } from '@/utils/feedback';
import { aiLabelText } from '@/utils/safety';
import styles from './PlaylistDetailPage.module.less';

/**
 * 歌单详情页（模块 02 T114 / PRD FR-11-004）。
 *
 * 服务端分页展示曲目；创建者（或有 `music:playlist:manage`）可编辑、移出歌曲、
 * 在**单页内**上下移排序（传完整有序 songId 数组给后端，见模块 01 D-10）。
 */

const PAGE_SIZE = 50;

export function PlaylistDetailPage(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [selectedIds, setSelectedIds] = useState<Id[]>([]);
  const [editOpen, setEditOpen] = useState(false);

  const currentUserId = useUserStore((s) => s.user?.id);
  const isLogin = useUserStore((s) => s.isLogin);
  const canManage = usePerm('music:playlist:manage');
  const playList = usePlayerStore((s) => s.playList);
  const currentSongId = usePlayerStore((s) => s.currentSong?.id);
  const isPlaying = usePlayerStore((s) => s.isPlaying);
  const toggle = usePlayerStore((s) => s.toggle);

  const query = useQuery({
    queryKey: playlistKeys.detail(id ?? '', pageNum, PAGE_SIZE),
    queryFn: () => playlistApi.detail(id as string, pageNum, PAGE_SIZE),
    enabled: Boolean(id),
  });

  const playlist: Playlist | undefined = query.data?.playlist;
  const songs: Song[] = query.data?.songs.list ?? [];
  const total = query.data?.songs.total ?? 0;

  /** 排序/移出后歌曲总数会变，列表 key 变化才会重新拉取 */
  const invalidate = async (): Promise<void> => {
    await queryClient.invalidateQueries({ queryKey: playlistKeys.all });
    await queryClient.invalidateQueries({ queryKey: songKeys.all });
  };

  /**
   * 是否允许「上下移」：后端要求传**完整有序**的 songId 数组，
   * 因此只有歌曲总数不超过一页（能一次拿全）时才开启，避免传半截数组把顺序改坏。
   */
  const canReorder = useMemo(() => Boolean(playlist) && total <= PAGE_SIZE && songs.length === total, [playlist, total, songs.length]);

  const isOwner = Boolean(playlist && currentUserId && String(playlist.userId) === String(currentUserId));
  const canEdit = isOwner || canManage;

  const handlePlayAll = (): void => {
    if (songs.length === 0) return;
    playList(songs, 0, 'playlist');
  };

  const handlePlayRow = (_song: Song, index: number): void => {
    if (songs[index]?.id === currentSongId) {
      toggle();
      return;
    }
    playList(songs, index, 'playlist');
  };

  const handleRemoveSongs = async (songIds: Id[]): Promise<void> => {
    if (!id || songIds.length === 0) return;
    try {
      const removed = await playlistApi.removeSongs(id, songIds);
      toastSuccess(`已移出 ${removed ?? 0} 首`);
      setSelectedIds([]);
      await invalidate();
    } catch {
      // 请求层已 Toast
    }
  };

  const handleMove = async (index: number, delta: number): Promise<void> => {
    if (!id) return;
    const target = index + delta;
    if (target < 0 || target >= songs.length) return;
    const ordered = songs.map((s) => s.id);
    [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
    try {
      await playlistApi.reorder(id, ordered);
      await invalidate();
    } catch {
      // 请求层已 Toast
    }
  };

  const handleDeletePlaylist = async (): Promise<void> => {
    if (!id) return;
    try {
      await playlistApi.remove(id);
      toastSuccess('歌单已删除');
      await invalidate();
      navigate('/user?tab=playlists');
    } catch {
      // 请求层已 Toast
    }
  };

  const columns: ColumnsType<Song> = [
    {
      title: '#',
      key: 'index',
      width: 64,
      render: (_v, record, index) => {
        const isCurrent = record.id === currentSongId;
        return (
          <Space size={6}>
            <Button
              type="text"
              size="small"
              aria-label={isCurrent && isPlaying ? '暂停' : '播放'}
              icon={isCurrent && isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                handlePlayRow(record, index);
              }}
            />
            {!isCurrent && <span style={{ color: 'var(--color-text-secondary)' }}>{(pageNum - 1) * PAGE_SIZE + index + 1}</span>}
          </Space>
        );
      },
    },
    {
      title: '歌曲',
      key: 'name',
      ellipsis: true,
      render: (_v, record) => (
        <Space size={12}>
          <CoverImage src={record.cover} alt={record.name} size={40} />
          <div style={{ minWidth: 0 }}>
            <Link to={`/song/${record.id}`} style={{ color: 'var(--color-text)' }}>
              {record.name}
            </Link>
            <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
              <Link to={`/artist/${record.artistId}`}>{record.artistName}</Link>
            </div>
          </div>
        </Space>
      ),
    },
    {
      title: '专辑',
      dataIndex: 'albumName',
      key: 'albumName',
      width: 180,
      ellipsis: true,
      responsive: ['lg'],
      render: (value: string | null) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {value ?? '—'}
        </Typography.Text>
      ),
    },
    {
      title: '时长',
      dataIndex: 'duration',
      key: 'duration',
      width: 80,
      align: 'right',
      render: (value: number | null) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {formatDuration(value)}
        </Typography.Text>
      ),
    },
  ];

  if (canEdit) {
    columns.push({
      title: '操作',
      key: 'actions',
      width: 132,
      align: 'right',
      render: (_v, record, index) => (
        <Space size={0}>
          <Tooltip title={canReorder ? '上移' : '歌曲超过一页时暂不支持排序'}>
            <Button
              type="text"
              size="small"
              aria-label="上移"
              disabled={!canReorder || index === 0}
              icon={<ArrowUpOutlined />}
              onClick={() => void handleMove(index, -1)}
            />
          </Tooltip>
          <Tooltip title={canReorder ? '下移' : '歌曲超过一页时暂不支持排序'}>
            <Button
              type="text"
              size="small"
              aria-label="下移"
              disabled={!canReorder || index === songs.length - 1}
              icon={<ArrowDownOutlined />}
              onClick={() => void handleMove(index, 1)}
            />
          </Tooltip>
          <Popconfirm
            title="移出歌单"
            description={`确定把《${record.name}》移出歌单？`}
            okText="移出"
            cancelText="取消"
            onConfirm={() => void handleRemoveSongs([record.id])}
          >
            <Button type="text" size="small" danger aria-label="移出歌单" icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    });
  }

  if (!id) return <ErrorState title="参数错误" error={new Error('缺少歌单 ID')} />;
  if (query.isLoading) return <PageLoading />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.code === 30004;
    return (
      <ErrorState
        title={notFound ? '歌单不存在或无权访问' : '歌单加载失败'}
        error={query.error}
        onRetry={() => void query.refetch()}
      />
    );
  }
  if (!playlist) return <EmptyState description="歌单不存在" />;

  const tags = (playlist.tags ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter(Boolean);

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <CoverImage src={playlist.cover} alt={playlist.name} size={200} />
        <div className={styles.info}>
          <h1 className={styles.title}>
            {playlist.isPublic === 0 ? <Tag color="default">私密</Tag> : null}
            {aiLabelText(playlist.aiGenerated) ? (
              <Tag color="purple" bordered={false}>{aiLabelText(playlist.aiGenerated)}</Tag>
            ) : null}
            {playlist.name}
          </h1>
          <div className={styles.meta}>
            <span>
              <UserOutlined /> {playlist.userId === '0' ? '官方歌单' : isOwner ? '我创建的' : '用户歌单'}
            </span>
            <span>歌曲：{playlist.songCount}</span>
            <span>播放：{formatCount(playlist.playCount)}</span>
            <span>收藏：{formatCount(playlist.collectCount)}</span>
            <span>更新：{playlist.updateTime?.slice(0, 10)}</span>
          </div>
          {tags.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              {tags.map((tag) => (
                <Tag key={tag} color="blue" bordered={false}>
                  {tag}
                </Tag>
              ))}
            </div>
          )}
          {playlist.description ? (
            <Typography.Paragraph type="secondary" style={{ maxWidth: 720 }}>
              {playlist.description}
            </Typography.Paragraph>
          ) : null}

          <Space wrap>
            <Button type="primary" icon={<CaretRightOutlined />} disabled={songs.length === 0} onClick={handlePlayAll}>
              播放全部
            </Button>
            <AddToPlaylistButton songIds={selectedIds} disabled={selectedIds.length === 0} text={`加入其它歌单（已选 ${selectedIds.length}）`} />
            {playlist.isPublic === 1 ? (
              <ActionButtons
                targetType="PLAYLIST"
                targetId={playlist.id}
                likeCount={playlist.likeCount}
                collectCount={playlist.collectCount}
              />
            ) : null}
            {canEdit ? (
              <>
                <Button icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
                  编辑歌单
                </Button>
                <Popconfirm
                  title="删除歌单"
                  description="删除后不可恢复，确定继续？"
                  okText="删除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => void handleDeletePlaylist()}
                >
                  <Button danger icon={<DeleteOutlined />}>
                    删除歌单
                  </Button>
                </Popconfirm>
              </>
            ) : null}
            {isLogin && !canEdit ? (
              <Tooltip title="只有创建者可以编辑该歌单">
                <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>非创建者仅可播放</span>
              </Tooltip>
            ) : null}
          </Space>
        </div>
      </div>

      {query.isFetching && !songs.length ? (
        <ListSkeleton rows={6} />
      ) : songs.length === 0 ? (
        <EmptyState
          description="这个歌单还没有歌曲"
          actionText={canEdit ? '去添加歌曲' : undefined}
          onAction={canEdit ? () => navigate('/search') : undefined}
        />
      ) : (
        <Table<Song>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={songs}
          rowSelection={
            canEdit
              ? {
                  selectedRowKeys: selectedIds,
                  onChange: (keys) => setSelectedIds(keys as Id[]),
                  preserveSelectedRowKeys: false,
                }
              : undefined
          }
          onRow={(record) => ({
            onClick: () => {
              const index = songs.findIndex((s) => s.id === record.id);
              handlePlayRow(record, index);
            },
            style: { cursor: 'pointer' },
          })}
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
            showTotal: (t) => `共 ${t} 首`,
            onChange: (page) => {
              setPageNum(page);
              setSelectedIds([]);
            },
          }}
        />
      )}

      {canEdit ? (
        <div style={{ marginTop: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
          <Popconfirm
            title="移出所选歌曲"
            description={`确定把选中的 ${selectedIds.length} 首移出歌单？`}
            okText="移出"
            cancelText="取消"
            onConfirm={() => void handleRemoveSongs(selectedIds)}
          >
            <Button danger disabled={selectedIds.length === 0}>
              批量移出{selectedIds.length > 0 ? `（${selectedIds.length}）` : ''}
            </Button>
          </Popconfirm>
          {!canReorder && total > PAGE_SIZE ? (
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
              歌曲超过 {PAGE_SIZE} 首，拖动排序将在模块 09 后台提供
            </span>
          ) : null}
        </div>
      ) : null}

      {playlist.isPublic === 1 ? (
        <SectionBoundary title="评论">
          <CommentSection targetType="PLAYLIST" targetId={playlist.id} />
        </SectionBoundary>
      ) : null}

      {/* 编辑成功后弹窗内部已失效 playlistKeys.all，本页查询会自动刷新 */}
      <PlaylistFormModal open={editOpen} playlist={playlist} onClose={() => setEditOpen(false)} />
    </div>
  );
}
