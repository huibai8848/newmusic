import { useState } from 'react';
import { Button, Empty, Form, Modal, Select, Spin } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { playlistApi, playlistKeys } from '@/api/playlist';
import { useUserStore } from '@/stores/userStore';
import type { Id } from '@/types/api';
import { toastError, toastSuccess } from '@/utils/feedback';

/**
 * 「加入歌单」（模块 02 T113）。
 *
 * 未登录先跳登录（带 redirect 回跳）；已登录则列出「我的歌单」，
 * 支持多选一次性加入 —— 后端对重复歌曲幂等跳过，并返回真实新增数。
 */

interface Props {
  songIds: Id[];
  /** 入口按钮文案；批量场景可传「加入歌单」 */
  text?: string;
  size?: 'small' | 'middle' | 'large';
  type?: 'default' | 'primary' | 'text' | 'link';
  block?: boolean;
  disabled?: boolean;
}

export function AddToPlaylistButton({
  songIds,
  text = '加入歌单',
  size = 'middle',
  type = 'default',
  block,
  disabled,
}: Props): JSX.Element {
  const navigate = useNavigate();
  const isLogin = useUserStore((s) => s.isLogin);
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<{ playlistIds: Id[] }>();

  const playlists = useQuery({
    queryKey: playlistKeys.list({ mine: true, pageSize: 100 }),
    queryFn: () => playlistApi.list({ mine: true, pageSize: 100 }),
    enabled: open && isLogin,
  });

  const openModal = (): void => {
    if (!isLogin) {
      navigate(`/login?redirect=${encodeURIComponent(window.location.pathname)}`);
      return;
    }
    if (songIds.length === 0) {
      toastError('请先选择歌曲');
      return;
    }
    setOpen(true);
  };

  const handleOk = async (values: { playlistIds: Id[] }): Promise<void> => {
    setSubmitting(true);
    try {
      let added = 0;
      for (const playlistId of values.playlistIds) {
        // 逐个提交：后端以「每单曲」为单位做幂等去重，逐个调用才能拿到准确的新增数
        added += (await playlistApi.addSongs(playlistId, songIds)) ?? 0;
      }
      const skipped = songIds.length * values.playlistIds.length - added;
      toastSuccess(skipped > 0 ? `已添加 ${added} 首，${skipped} 首已在歌单中自动跳过` : `已添加 ${added} 首`);
      setOpen(false);
      await queryClient.invalidateQueries({ queryKey: playlistKeys.all });
    } catch {
      // 请求层已统一 Toast
    } finally {
      setSubmitting(false);
    }
  };

  const options = (playlists.data?.list ?? []).map((p) => ({
    value: p.id,
    label: `${p.name}（${p.songCount} 首）`,
  }));

  return (
    <>
      <Button size={size} type={type} block={block} icon={<PlusOutlined />} onClick={openModal} disabled={disabled}>
        {text}
      </Button>

      <Modal
        title={`加入歌单（已选 ${songIds.length} 首）`}
        open={open}
        onCancel={() => setOpen(false)}
        afterClose={() => form.resetFields()}
        footer={[
          <Button key="cancel" onClick={() => setOpen(false)}>
            取消
          </Button>,
          <Button
            key="ok"
            type="primary"
            loading={submitting}
            disabled={options.length === 0}
            onClick={() => form.submit()}
          >
            加入
          </Button>,
        ]}
      >
        {playlists.isLoading ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        ) : options.length === 0 ? (
          <Empty description="你还没有歌单" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: '16px 0' }}>
            <Button
              type="primary"
              onClick={() => {
                setOpen(false);
                navigate('/user?tab=playlists');
              }}
            >
              去创建歌单
            </Button>
          </Empty>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleOk}>
            <Form.Item
              name="playlistIds"
              label="选择歌单（可多选）"
              rules={[{ required: true, message: '请选择至少一个歌单' }]}
            >
              <Select
                mode="multiple"
                placeholder="选择歌单"
                options={options}
                optionFilterProp="label"
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </>
  );
}
