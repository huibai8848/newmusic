import { useEffect, useState } from 'react';
import { Form, Input, Modal, Radio } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/request';
import { playlistApi, playlistKeys } from '@/api/playlist';
import type { Playlist, PlaylistSaveReq } from '@/types/api';
import { toastSuccess } from '@/utils/feedback';

/**
 * 新建 / 编辑歌单弹窗（模块 02 T113）。
 *
 * 编辑时必须带上详情里的 `version`（乐观锁，模块 01 D-11）：
 * 版本不一致后端返回 10008，说明别人先改了，需要刷新后再改。
 */

interface Props {
  open: boolean;
  /** 传入则为编辑，不传为新建 */
  playlist?: Playlist | null;
  onClose: () => void;
  onSaved?: (id: string) => void;
}

export function PlaylistFormModal({ open, playlist, onClose, onSaved }: Props): JSX.Element {
  const [form] = Form.useForm<PlaylistSaveReq>();
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const isEdit = Boolean(playlist);

  useEffect(() => {
    if (!open) return;
    if (playlist) {
      form.setFieldsValue({
        name: playlist.name,
        cover: playlist.cover,
        description: playlist.description,
        isPublic: playlist.isPublic,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ isPublic: 1 });
    }
  }, [open, playlist, form]);

  const handleOk = async (): Promise<void> => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (playlist) {
        await playlistApi.update(playlist.id, { ...values, version: playlist.version });
        toastSuccess('歌单已更新');
      } else {
        const id = await playlistApi.create(values);
        toastSuccess('歌单已创建');
        onSaved?.(id);
      }
      await queryClient.invalidateQueries({ queryKey: playlistKeys.all });
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === 10008) {
        form.setFields([{ name: 'name', errors: ['该歌单已被他人修改，请刷新页面后重试'] }]);
      }
      // 其它错误请求层已 Toast
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={isEdit ? '编辑歌单' : '新建歌单'}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={{ isPublic: 1 }}>
        <Form.Item
          name="name"
          label="歌单名称"
          rules={[{ required: true, message: '请输入歌单名称' }, { max: 50, message: '最多 50 个字' }]}
        >
          <Input placeholder="给歌单起个名字" maxLength={50} showCount />
        </Form.Item>
        <Form.Item name="description" label="简介" rules={[{ max: 200, message: '最多 200 个字' }]}>
          <Input.TextArea rows={3} placeholder="介绍一下这个歌单（选填）" maxLength={200} showCount />
        </Form.Item>
        <Form.Item name="cover" label="封面地址" rules={[{ max: 255 }]}>
          <Input placeholder="图片 URL 或 /media/... 相对路径（选填）" />
        </Form.Item>
        <Form.Item name="tags" label="标签" tooltip="英文逗号分隔，用于歌单广场筛选">
          <Input placeholder="如：华语,流行" />
        </Form.Item>
        <Form.Item name="isPublic" label="可见性">
          <Radio.Group>
            <Radio value={1}>公开</Radio>
            <Radio value={0}>私密（仅自己可见）</Radio>
          </Radio.Group>
        </Form.Item>
      </Form>
    </Modal>
  );
}
