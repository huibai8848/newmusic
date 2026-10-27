import { useState } from 'react';
import { Button, Card, Input, Popconfirm, Select, Space, Tooltip } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { songApi, songKeys } from '@/api/song';
import type { Id, Song } from '@/types/api';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useAnyPerm, usePerm } from '@/hooks/usePerm';
import { SongTable } from '@/components/business/SongTable';
import { SongFormModal } from '@/components/business/SongFormModal';
import { ListSkeleton } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { EmptyState } from '@/components/common/EmptyState';
import { toastSuccess } from '@/utils/feedback';

/**
 * 后台 - 歌曲管理（模块 02 T116）。
 *
 * 覆盖模块 01 提供的歌曲写接口：新增/编辑、上架下架、审核、单个与批量删除。
 * 按钮按权限码显隐（`music:song:online/offline`、`music:song:audit`、`music:song:delete`），
 * 服务端仍有 `@PreAuthorize` 兜底。
 */

const PAGE_SIZE = 20;

export function AdminSongsPage(): JSX.Element {
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<number | undefined>();
  const [auditStatus, setAuditStatus] = useState<number | undefined>();
  const [selectedIds, setSelectedIds] = useState<Id[]>([]);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Song | null>(null);
  const debouncedKeyword = useDebouncedValue(keyword.trim(), 400);

  const canCreate = usePerm('music:song:create');
  const canUpdate = usePerm('music:song:update');
  const canAudit = usePerm('music:song:audit');
  const canDelete = usePerm('music:song:delete');
  // 后端 `PUT /music/songs/{id}/status/{status}` 接受 online/offline/update 三者之一
  const canChangeStatus = useAnyPerm(['music:song:online', 'music:song:offline', 'music:song:update']);

  const query = useQuery({
    queryKey: songKeys.list({ pageNum, pageSize: PAGE_SIZE, keyword: debouncedKeyword || undefined, status, auditStatus }),
    queryFn: () =>
      songApi.list({ pageNum, pageSize: PAGE_SIZE, keyword: debouncedKeyword || undefined, status, auditStatus }),
  });

  const invalidate = async (): Promise<void> => {
    await queryClient.invalidateQueries({ queryKey: songKeys.all });
  };

  const handleStatus = async (song: Song, next: number): Promise<void> => {
    try {
      await songApi.changeStatus(song.id, next);
      toastSuccess(next === 1 ? `《${song.name}》已上架` : `《${song.name}》已下架`);
      await invalidate();
    } catch {
      // 请求层已 Toast（上架不满足 BR-04-001/002 时后端会给出具体原因）
    }
  };

  const handleAudit = async (song: Song, next: number): Promise<void> => {
    try {
      await songApi.audit(song.id, next);
      toastSuccess(next === 1 ? `《${song.name}》审核通过` : `《${song.name}》已驳回`);
      await invalidate();
    } catch {
      // 请求层已 Toast
    }
  };

  const handleDelete = async (ids: Id[]): Promise<void> => {
    try {
      if (ids.length === 1) {
        await songApi.remove(ids[0]);
      } else {
        await songApi.batchRemove(ids);
      }
      toastSuccess(`已删除 ${ids.length} 首`);
      setSelectedIds([]);
      await invalidate();
    } catch {
      // 请求层已 Toast
    }
  };

  const songs = query.data?.list ?? [];

  return (
    <Card
      title="歌曲管理"
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
            新增歌曲
          </Button>
        ) : null
      }
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          style={{ width: 240 }}
          placeholder="搜索歌曲名/歌手"
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
        <Select
          allowClear
          placeholder="上架状态"
          style={{ width: 130 }}
          value={status}
          onChange={(value) => {
            setStatus(value);
            setPageNum(1);
          }}
          options={[
            { value: 1, label: '已上架' },
            { value: 0, label: '已下架' },
          ]}
        />
        <Select
          allowClear
          placeholder="审核状态"
          style={{ width: 130 }}
          value={auditStatus}
          onChange={(value) => {
            setAuditStatus(value);
            setPageNum(1);
          }}
          options={[
            { value: 0, label: '待审核' },
            { value: 1, label: '审核通过' },
            { value: 2, label: '审核驳回' },
          ]}
        />
        <Popconfirm
          title="批量删除"
          description={`确定删除选中的 ${selectedIds.length} 首歌曲？删除为逻辑删除，可级联清理标签/歌单关联/歌词。`}
          okText="删除"
          okButtonProps={{ danger: true }}
          cancelText="取消"
          onConfirm={() => void handleDelete(selectedIds)}
        >
          <Button danger disabled={!canDelete || selectedIds.length === 0}>
            批量删除{selectedIds.length > 0 ? `（${selectedIds.length}）` : ''}
          </Button>
        </Popconfirm>
      </Space>

      {query.isLoading ? (
        <ListSkeleton rows={6} />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : songs.length === 0 ? (
        <EmptyState description="没有符合条件的歌曲" />
      ) : (
        <SongTable
          songs={songs}
          showStatus
          rowSelection={
            canDelete
              ? {
                  selectedRowKeys: selectedIds,
                  onChange: (keys) => setSelectedIds(keys as Id[]),
                }
              : undefined
          }
          pagination={{
            current: pageNum,
            pageSize: PAGE_SIZE,
            total: query.data?.total ?? 0,
            showSizeChanger: false,
            showTotal: (t) => `共 ${t} 首`,
            onChange: (page) => {
              setPageNum(page);
              setSelectedIds([]);
            },
          }}
          rowActions={(song) => (
            <Space size={0}>
              {canUpdate ? (
                <Tooltip title="编辑">
                  <Button
                    type="link"
                    size="small"
                    onClick={() => {
                      setEditing(song);
                      setFormOpen(true);
                    }}
                  >
                    编辑
                  </Button>
                </Tooltip>
              ) : null}
              {canAudit ? (
                <>
                  <Button type="link" size="small" onClick={() => void handleAudit(song, 1)}>
                    通过
                  </Button>
                  <Button type="link" size="small" danger onClick={() => void handleAudit(song, 2)}>
                    驳回
                  </Button>
                </>
              ) : null}
              <Button
                type="link"
                size="small"
                disabled={!canChangeStatus}
                onClick={() => void handleStatus(song, song.status === 1 ? 0 : 1)}
              >
                {song.status === 1 ? '下架' : '上架'}
              </Button>
              {canDelete ? (
                <Popconfirm
                  title="删除歌曲"
                  description={`确定删除《${song.name}》？`}
                  okText="删除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => void handleDelete([song.id])}
                >
                  <Button type="link" size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              ) : null}
            </Space>
          )}
        />
      )}

      <SongFormModal
        open={formOpen}
        song={editing}
        onClose={() => {
          setFormOpen(false);
          setEditing(null);
        }}
      />
    </Card>
  );
}
