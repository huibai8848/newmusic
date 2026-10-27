import { useEffect, useState } from 'react';
import { Button, Empty, Input, Modal, Radio, Space, Spin } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import { toastSuccess } from '@/utils/feedback';
import type { Id } from '@/types/api';
import type { CollectTargetType } from '@/types/social';

/**
 * 收藏夹选择弹窗。
 *
 * 取消收藏也走同一个弹窗：详情页只有一个「收藏」入口，
 * 已收藏状态下再点应能取消或换收藏夹，而不是立即取消（误触成本太高）。
 */
export function CollectDialog({
  open,
  targetType,
  targetId,
  collected,
  folderId,
  onClose,
  onChanged,
}: {
  open: boolean;
  targetType: CollectTargetType;
  targetId: Id;
  collected: boolean;
  folderId: Id | null;
  onClose: () => void;
  onChanged: (collected: boolean) => void;
}): JSX.Element {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string>('default');
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);

  const folders = useQuery({
    queryKey: socialKeys.collectFolders,
    queryFn: socialApi.collectFolders,
    enabled: open,
  });

  useEffect(() => {
    if (!open) return;
    setNewName('');
    setSelected(folderId ? String(folderId) : 'default');
  }, [open, folderId]);

  const collectMutation = useMutation({
    mutationFn: (toFolderId: Id | null) => socialApi.collect(targetType, targetId, toFolderId),
    onSuccess: () => {
      toastSuccess(collected ? '收藏夹已更新' : '已收藏');
      void queryClient.invalidateQueries({ queryKey: socialKeys.collectFolders });
      void queryClient.invalidateQueries({ queryKey: socialKeys.collect(targetType, targetId) });
      onChanged(true);
      onClose();
    },
  });

  const cancelMutation = useMutation({
    mutationFn: () => socialApi.uncollect(targetType, targetId),
    onSuccess: () => {
      toastSuccess('已取消收藏');
      void queryClient.invalidateQueries({ queryKey: socialKeys.collectFolders });
      void queryClient.invalidateQueries({ queryKey: socialKeys.collect(targetType, targetId) });
      onChanged(false);
      onClose();
    },
  });

  const createMutation = useMutation({
    mutationFn: (name: string) => socialApi.createFolder(name),
    onSuccess: (folder) => {
      toastSuccess('收藏夹已创建');
      setNewName('');
      setCreating(false);
      if (folder.id) setSelected(String(folder.id));
      void queryClient.invalidateQueries({ queryKey: socialKeys.collectFolders });
    },
    onSettled: () => setCreating(false),
  });

  const handleCreate = (): void => {
    const name = newName.trim();
    if (!name) return;
    setCreating(true);
    createMutation.mutate(name);
  };

  const handleSave = (): void => {
    collectMutation.mutate(selected === 'default' ? null : selected);
  };

  return (
    <Modal
      open={open}
      title={collected ? '收藏夹' : '收藏到'}
      onCancel={onClose}
      destroyOnHidden
      footer={
        <Space>
          {collected ? (
            <Button danger loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>
              取消收藏
            </Button>
          ) : null}
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={collectMutation.isPending} onClick={handleSave}>
            {collected ? '保存' : '收藏'}
          </Button>
        </Space>
      }
    >
      {folders.isLoading ? (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin />
        </div>
      ) : folders.isError ? (
        <Empty description="收藏夹加载失败，请关闭后重试" />
      ) : (
        <Radio.Group
          value={selected}
          onChange={(e) => setSelected(e.target.value as string)}
          style={{ display: 'flex', flexDirection: 'column', gap: 8, width: '100%' }}
        >
          {(folders.data ?? []).map((folder) => (
            <Radio key={folder.id ?? 'default'} value={folder.id ? String(folder.id) : 'default'}>
              {folder.name}
              <span style={{ color: 'var(--color-text-secondary)', marginLeft: 8, fontSize: 12 }}>
                {folder.itemCount} 项
              </span>
            </Radio>
          ))}
        </Radio.Group>
      )}

      <Space.Compact style={{ width: '100%', marginTop: 16 }}>
        <Input
          value={newName}
          maxLength={30}
          placeholder="新建收藏夹名称"
          onChange={(e) => setNewName(e.target.value)}
          onPressEnter={handleCreate}
        />
        <Button
          icon={<PlusOutlined />}
          loading={creating}
          disabled={!newName.trim()}
          onClick={handleCreate}
        >
          新建
        </Button>
      </Space.Compact>
    </Modal>
  );
}
