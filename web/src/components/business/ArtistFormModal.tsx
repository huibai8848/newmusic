import { useEffect, useState } from 'react';
import { Alert, Col, Form, Input, Modal, Radio, Row, Select } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { artistApi, artistKeys } from '@/api/catalog';
import { ApiError } from '@/api/request';
import type { Artist, ArtistSaveReq } from '@/types/api';
import { toastSuccess } from '@/utils/feedback';

/**
 * 歌手新增 / 编辑弹窗（模块 02 T116 后台 / PRD 02 §3.1）。
 *
 * 编辑时回传 `version`；版本冲突（10008）提示重新打开，避免覆盖他人的修改。
 */

interface Props {
  open: boolean;
  artist?: Artist | null;
  onClose: () => void;
}

export function ArtistFormModal({ open, artist, onClose }: Props): JSX.Element {
  const [form] = Form.useForm<ArtistSaveReq>();
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const isEdit = Boolean(artist);

  useEffect(() => {
    if (!open) return;
    if (artist) {
      form.setFieldsValue({
        name: artist.name,
        alias: artist.alias ?? undefined,
        avatar: artist.avatar ?? undefined,
        gender: artist.gender ?? 0,
        country: artist.country ?? undefined,
        description: artist.description ?? undefined,
        styles: artist.styles ?? undefined,
        firstLetter: artist.firstLetter ?? undefined,
        status: artist.status,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ gender: 0, status: 1 });
    }
  }, [open, artist, form]);

  const handleOk = async (): Promise<void> => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (artist) {
        await artistApi.update(artist.id, { ...values, version: artist.version });
        toastSuccess('歌手已保存');
      } else {
        await artistApi.create(values);
        toastSuccess('歌手已创建');
      }
      await queryClient.invalidateQueries({ queryKey: artistKeys.all });
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === 10008) {
        form.setFields([{ name: 'name', errors: ['该歌手已被他人修改，请关闭后重新打开再改'] }]);
      }
      // 其它错误请求层已 Toast
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={isEdit ? `编辑歌手：${artist?.name}` : '新增歌手'}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      width={680}
      destroyOnHidden
    >
      {isEdit ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={`当前歌曲数：${artist?.songCount ?? 0}，专辑数：${artist?.albumCount ?? 0}（统计字段由后端维护，前端只读）`}
        />
      ) : null}
      <Form form={form} layout="vertical">
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label="歌手名" rules={[{ required: true, message: '请输入歌手名' }, { max: 100 }]}>
              <Input placeholder="必填" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="alias" label="别名 / 英文名">
              <Input placeholder="如：Jay Chou" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="gender" label="类型">
              <Radio.Group>
                <Radio value={1}>男</Radio>
                <Radio value={2}>女</Radio>
                <Radio value={3}>组合</Radio>
                <Radio value={0}>未知</Radio>
              </Radio.Group>
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="country" label="地区">
              <Input placeholder="如：中国台湾" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="firstLetter" label="首字母" tooltip="用于歌手索引，如 J">
              <Input maxLength={1} placeholder="A-Z" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 1, label: '正常' },
                  { value: 0, label: '停用' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="styles" label="风格标签" tooltip="英文逗号分隔，如：流行,华语">
              <Input placeholder="流行,华语" />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="avatar" label="头像地址">
              <Input placeholder="URL 或 /media/... 相对路径" />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="description" label="简介">
              <Input.TextArea rows={4} maxLength={500} showCount />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}
