import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Avatar, Button, Drawer, Empty, List, Space, Tabs, Tag, Typography } from 'antd';
import { DeleteOutlined, SoundOutlined, UserOutlined } from '@ant-design/icons';
import { recommendApi, recommendKeys } from '@/api/recommend';
import { CoverImage } from '@/components/common/CoverImage';
import { PageLoading } from '@/components/common/PageLoading';
import { toastSuccess } from '@/utils/feedback';

/**
 * 屏蔽管理（模块 08 BR-17-022）。
 *
 * 不喜欢歌曲上限 200、不喜欢歌手上限 50；移除即撤销屏蔽，
 * 下一轮生成（或刷新每日推荐）时这些歌/歌手会重新参与推荐。
 */

interface Props {
  open: boolean;
  onClose: () => void;
}

export function BlocklistDrawer({ open, onClose }: Props): JSX.Element {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: recommendKeys.blocklist(),
    queryFn: recommendApi.blocklist,
    enabled: open,
  });

  const invalidate = (): void => {
    void queryClient.invalidateQueries({ queryKey: recommendKeys.all });
  };

  const removeSong = useMutation({
    mutationFn: recommendApi.removeBlockedSong,
    onSuccess: () => {
      toastSuccess('已从屏蔽列表移除');
      invalidate();
    },
  });

  const removeArtist = useMutation({
    mutationFn: recommendApi.removeBlockedArtist,
    onSuccess: () => {
      toastSuccess('已解除歌手屏蔽');
      invalidate();
    },
  });

  const data = query.data;

  return (
    <Drawer title="屏蔽管理" width={480} open={open} onClose={onClose}>
      {query.isLoading ? (
        <PageLoading />
      ) : (
        <Tabs
          items={[
            {
              key: 'songs',
              label: `不喜欢的歌曲 ${data ? `${data.songs.length}/${data.songLimit}` : ''}`,
              children: (
                <List
                  dataSource={data?.songs ?? []}
                  locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有屏蔽歌曲" /> }}
                  renderItem={(song) => (
                    <List.Item
                      actions={[
                        <Button
                          key="remove"
                          type="text"
                          danger
                          size="small"
                          icon={<DeleteOutlined />}
                          loading={removeSong.isPending}
                          onClick={() => removeSong.mutate(song.id)}
                        >
                          移除
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta
                        avatar={<CoverImage src={song.cover} alt={song.name} size={40} />}
                        title={song.name}
                        description={song.artistName}
                      />
                    </List.Item>
                  )}
                />
              ),
            },
            {
              key: 'artists',
              label: `不喜欢的歌手 ${data ? `${data.artists.length}/${data.artistLimit}` : ''}`,
              children: (
                <List
                  dataSource={data?.artists ?? []}
                  locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有屏蔽歌手" /> }}
                  renderItem={(artist) => (
                    <List.Item
                      actions={[
                        <Button
                          key="remove"
                          type="text"
                          danger
                          size="small"
                          icon={<DeleteOutlined />}
                          loading={removeArtist.isPending}
                          onClick={() => removeArtist.mutate(artist.id)}
                        >
                          移除
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta
                        avatar={
                          <Avatar src={artist.avatar ?? undefined} icon={<UserOutlined />} shape="square" size={40} />
                        }
                        title={artist.name}
                        description={
                          <Space size={4}>
                            <SoundOutlined />
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {artist.songCount} 首作品
                            </Typography.Text>
                          </Space>
                        }
                      />
                    </List.Item>
                  )}
                />
              ),
            },
          ]}
        />
      )}
      {data ? (
        <Tag bordered={false} color="default" style={{ marginTop: 12 }}>
          上限：歌曲 {data.songLimit} / 歌手 {data.artistLimit}
        </Tag>
      ) : null}
    </Drawer>
  );
}
