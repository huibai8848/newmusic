import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Button, List, Popconfirm, Space, Tabs, Tag } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { playlistApi, playlistKeys } from '@/api/playlist';
import type { Playlist } from '@/types/api';
import { usePlayerStore } from '@/stores/playerStore';
import { clearHistory, listHistory, toSongBrief } from '@/services/history';
import { formatCount } from '@/utils/format';
import { toastSuccess } from '@/utils/feedback';
import { CoverImage } from '@/components/common/CoverImage';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { ListSkeleton } from '@/components/common/PageLoading';
import { SongTable } from '@/components/business/SongTable';
import { PlaylistFormModal } from '@/components/business/PlaylistFormModal';
import { ProfileEditor } from '@/components/social/ProfileEditor';
import {
  AppealsTab,
  CollectTab,
  FollowTab,
  LikedSongsTab,
  ReportsTab,
} from '@/components/social/UserSocialTabs';

/**
 * 个人中心（模块 02 T115 / PRD FR-11-006）。
 *
 * 三个 Tab：
 * - 资料：展示 + 修改（`PUT /users/me`）
 * - 最近播放：MVP 用 localStorage（后端播放历史属模块 12/17）
 * - 我的歌单：增删改查（模块 01 的歌单接口）
 */

type TabKey = 'profile' | 'history' | 'playlists' | 'liked' | 'collect' | 'following' | 'reports' | 'appeals';

function HistoryTab(): JSX.Element {
  const [version, setVersion] = useState(0);
  const navigate = useNavigate();
  const playList = usePlayerStore((s) => s.playList);
  const items = listHistory();
  const songs = items.map(toSongBrief);

  if (songs.length === 0) {
    return (
      <EmptyState
        description="还没有播放记录。播放歌曲后会自动记录在本地（最多 100 条）"
        actionText="去找歌"
        onAction={() => navigate('/search')}
      />
    );
  }

  return (
    <div key={version}>
      <div style={{ marginBottom: 12, display: 'flex', gap: 12 }}>
        <Button onClick={() => playList(songs, 0)}>播放全部</Button>
        <Popconfirm
          title="清空播放记录"
          description="仅清空本地记录，不影响服务端数据。"
          okText="清空"
          cancelText="取消"
          onConfirm={() => {
            clearHistory();
            setVersion((v) => v + 1);
            toastSuccess('已清空播放记录');
          }}
        >
          <Button danger>清空记录</Button>
        </Popconfirm>
      </div>
      <SongTable songs={songs} onPlay={(_s, index) => playList(songs, index)} />
    </div>
  );
}

function MyPlaylistsTab(): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Playlist | null>(null);

  const query = useQuery({
    queryKey: playlistKeys.list({ mine: true, pageSize: 100 }),
    queryFn: () => playlistApi.list({ mine: true, pageSize: 100 }),
  });

  const handleDelete = async (playlist: Playlist): Promise<void> => {
    try {
      await playlistApi.remove(playlist.id);
      toastSuccess('歌单已删除');
      await queryClient.invalidateQueries({ queryKey: playlistKeys.all });
    } catch {
      // 请求层已 Toast
    }
  };

  const list = query.data?.list ?? [];

  return (
    <>
      <div style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditing(null);
            setModalOpen(true);
          }}
        >
          新建歌单
        </Button>
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={4} />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState description="还没有歌单，创建一个吧" />
      ) : (
        <List
          itemLayout="horizontal"
          dataSource={list}
          renderItem={(item) => (
            <List.Item
              actions={[
                <Button key="view" type="link" onClick={() => navigate(`/playlist/${item.id}`)}>
                  查看
                </Button>,
                <Button
                  key="edit"
                  type="link"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setEditing(item);
                    setModalOpen(true);
                  }}
                >
                  编辑
                </Button>,
                <Popconfirm
                  key="delete"
                  title="删除歌单"
                  description="删除后不可恢复，确定继续？"
                  okText="删除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => void handleDelete(item)}
                >
                  <Button type="link" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                avatar={<CoverImage src={item.cover} alt={item.name} size={56} />}
                title={
                  <Space size={8}>
                    <span>{item.name}</span>
                    {item.isPublic === 0 ? <Tag>私密</Tag> : null}
                  </Space>
                }
                description={
                  <span style={{ fontSize: 12 }}>
                    {item.songCount} 首 · 播放 {formatCount(item.playCount)} · 更新 {item.updateTime?.slice(0, 10)}
                  </span>
                }
              />
            </List.Item>
          )}
        />
      )}

      <PlaylistFormModal
        open={modalOpen}
        playlist={editing}
        onClose={() => setModalOpen(false)}
        onSaved={(id) => navigate(`/playlist/${id}`)}
      />
    </>
  );
}

export function UserCenterPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as TabKey | null) ?? 'profile';

  return (
    <div className="ml-page">
      <Tabs
        activeKey={tab}
        onChange={(key) => setSearchParams({ tab: key }, { replace: true })}
        items={[
          { key: 'profile', label: '个人资料', children: <ProfileEditor /> },
          { key: 'history', label: '最近播放', children: <HistoryTab /> },
          { key: 'playlists', label: '我的歌单', children: <MyPlaylistsTab /> },
          { key: 'liked', label: '我喜欢的歌曲', children: <LikedSongsTab /> },
          { key: 'collect', label: '我的收藏', children: <CollectTab /> },
          { key: 'following', label: '关注与粉丝', children: <FollowTab /> },
          { key: 'reports', label: '我的举报', children: <ReportsTab /> },
          { key: 'appeals', label: '我的申诉', children: <AppealsTab /> },
        ]}
      />
    </div>
  );
}
