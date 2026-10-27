import { useState } from 'react';
import { Button, Card, Input, Popconfirm, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { artistApi, artistKeys } from '@/api/catalog';
import type { Artist } from '@/types/api';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { usePerm } from '@/hooks/usePerm';
import { ArtistFormModal } from '@/components/business/ArtistFormModal';
import { CoverImage } from '@/components/common/CoverImage';
import { ListSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { toastSuccess } from '@/utils/feedback';

/** 后台 - 艺人管理（模块 02 T116）：增删改查歌手 */

const PAGE_SIZE = 20;

export function AdminArtistsPage(): JSX.Element {
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Artist | null>(null);
  const debouncedKeyword = useDebouncedValue(keyword.trim(), 400);

  // 后端把歌手增删改统一挂在 `music:artist:*` 通配码下，expand 后包含 create/update/delete
  const canCreate = usePerm('music:artist:create');
  const canUpdate = usePerm('music:artist:update');
  const canDelete = usePerm('music:artist:delete');

  const query = useQuery({
    queryKey: artistKeys.list({ pageNum, pageSize: PAGE_SIZE, keyword: debouncedKeyword || undefined }),
    queryFn: () => artistApi.list({ pageNum, pageSize: PAGE_SIZE, keyword: debouncedKeyword || undefined }),
  });

  const handleDelete = async (artist: Artist): Promise<void> => {
    try {
      await artistApi.remove(artist.id);
      toastSuccess(`已删除歌手「${artist.name}」`);
      await queryClient.invalidateQueries({ queryKey: artistKeys.all });
    } catch {
      // 请求层已 Toast
    }
  };

  const columns: ColumnsType<Artist> = [
    {
      title: '歌手',
      key: 'name',
      render: (_v, record) => (
        <Space size={12}>
          <CoverImage src={record.avatar ?? record.cover} alt={record.name} size={40} shape="circle" />
          <div>
            <div style={{ color: 'var(--color-text)' }}>{record.name}</div>
            <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{record.alias ?? '—'}</div>
          </div>
        </Space>
      ),
    },
    {
      title: '地区',
      dataIndex: 'country',
      key: 'country',
      width: 120,
      render: (value: string | null) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {value ?? '—'}
        </Typography.Text>
      ),
    },
    {
      title: '风格',
      dataIndex: 'styles',
      key: 'styles',
      responsive: ['lg'],
      render: (value: string | null) =>
        (value ?? '')
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean)
          .map((style) => (
            <Tag key={style} color="blue" bordered={false}>
              {style}
            </Tag>
          )),
    },
    { title: '歌曲', dataIndex: 'songCount', key: 'songCount', width: 80, align: 'right' },
    { title: '专辑', dataIndex: 'albumCount', key: 'albumCount', width: 80, align: 'right' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: number) => <Tag color={value === 1 ? 'green' : 'default'}>{value === 1 ? '正常' : '停用'}</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      align: 'right',
      render: (_v, record) => (
        <Space size={0}>
          {canUpdate ? (
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                setFormOpen(true);
              }}
            >
              编辑
            </Button>
          ) : null}
          {canDelete ? (
            <Popconfirm
              title="删除歌手"
              description={`确定删除「${record.name}」？若其名下仍有歌曲，后端会拒绝并说明原因。`}
              okText="删除"
              okButtonProps={{ danger: true }}
              cancelText="取消"
              onConfirm={() => void handleDelete(record)}
            >
              <Button type="link" size="small" danger>
                删除
              </Button>
            </Popconfirm>
          ) : null}
        </Space>
      ),
    },
  ];

  const artists = query.data?.list ?? [];

  return (
    <Card
      title="艺人管理"
      extra={
        canCreate ? (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(null);
              setFormOpen(true);
            }}
          >
            新增歌手
          </Button>
        ) : null
      }
    >
      <Input.Search
        allowClear
        style={{ width: 260, marginBottom: 16 }}
        placeholder="搜索歌手名"
        value={keyword}
        onChange={(e) => {
          setKeyword(e.target.value);
          setPageNum(1);
        }}
        onSearch={(value) => {
          setKeyword(value);
          setPageNum(1);
        }}
      />

      {query.isLoading ? (
        <ListSkeleton rows={6} />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : artists.length === 0 ? (
        <EmptyState description="没有符合条件的歌手" />
      ) : (
        <Table<Artist>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={artists}
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total: query.data?.total ?? 0,
            showSizeChanger: false,
            showTotal: (t) => `共 ${t} 位`,
            onChange: setPageNum,
          }}
        />
      )}

      <ArtistFormModal
        open={formOpen}
        artist={editing}
        onClose={() => {
          setFormOpen(false);
          setEditing(null);
        }}
      />
    </Card>
  );
}
