import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Avatar,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { UserOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { artistApi, type ArtistPayload } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { ImageUploadField } from '@/components/ImageUploadField';
import { useAuth } from '@/auth/AuthContext';
import type { Artist, PageResult } from '@/api/types';

interface Filters {
  keyword?: string;
  country?: string;
  firstLetter?: string;
  status?: number;
}

interface FormValues extends Omit<ArtistPayload, 'birthday' | 'debutDate'> {
  birthday?: Dayjs;
  debutDate?: Dayjs;
}

const GENDER: Record<number, string> = { 0: '未知', 1: '男', 2: '女', 3: '组合' };

export function ArtistsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Artist | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<FormValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<Artist>>({
    queryKey: ['admin-artists', query],
    queryFn: () => artistApi.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-artists'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ status: 1, gender: 0 });
    setCreating(true);
  };

  const openEdit = async (record: Artist): Promise<void> => {
    try {
      const detail = await artistApi.detail(record.id);
      form.resetFields();
      form.setFieldsValue({
        name: detail.name,
        alias: detail.alias,
        avatar: detail.avatar,
        cover: detail.cover,
        gender: detail.gender,
        country: detail.country,
        birthday: detail.birthday ? dayjs(detail.birthday) : undefined,
        debutDate: detail.debutDate ? dayjs(detail.debutDate) : undefined,
        description: detail.description,
        styles: detail.styles,
        firstLetter: detail.firstLetter,
        status: detail.status,
        version: detail.version,
      });
      setEditing(record);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    const payload: ArtistPayload = {
      ...values,
      name: values.name.trim(),
      alias: values.alias?.trim() || undefined,
      birthday: values.birthday?.format('YYYY-MM-DD'),
      debutDate: values.debutDate?.format('YYYY-MM-DD'),
    };
    setSaving(true);
    try {
      if (creating) {
        await artistApi.create({ ...payload, version: undefined });
        message.success('歌手已创建');
        setCreating(false);
      } else if (editing) {
        await artistApi.update(editing.id, payload);
        message.success('歌手已更新');
        setEditing(null);
      }
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (record: Artist): Promise<void> => {
    try {
      await artistApi.remove(record.id);
      message.success('歌手已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<Artist> = [
    {
      title: '歌手',
      key: 'name',
      width: 240,
      render: (_, record) => (
        <Space>
          <Avatar src={record.avatar} icon={<UserOutlined />} />
          <div>
            <div>{record.name}</div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {record.alias || record.firstLetter || '—'}
            </Typography.Text>
          </div>
        </Space>
      ),
    },
    { title: '国家/地区', dataIndex: 'country', width: 120, render: (value?: string) => value || '—' },
    {
      title: '性别/类型',
      dataIndex: 'gender',
      width: 100,
      render: (value?: number) => (value == null ? '—' : GENDER[value] ?? value),
    },
    { title: '风格', dataIndex: 'styles', width: 200, render: (value?: string) => value || '—' },
    { title: '歌曲数', dataIndex: 'songCount', width: 90, render: (value?: number) => value ?? 0 },
    { title: '专辑数', dataIndex: 'albumCount', width: 90, render: (value?: number) => value ?? 0 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value?: number) => (value === 1 ? <Tag color="green">正常</Tag> : <Tag>禁用</Tag>),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 150,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => void openEdit(record)}>
            编辑
          </Button>
          {hasPerm('admin:artist:manage') && (
            <Popconfirm
              title="删除该歌手？"
              description="有关联歌曲时后端会拒绝删除（BR-18-069）。"
              onConfirm={() => void remove(record)}
            >
              <Button type="link" size="small" danger>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="歌手名 / 别名"
          style={{ width: 220 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="国家/地区"
          style={{ width: 140 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, country: value || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="首字母 A-Z"
          maxLength={1}
          style={{ width: 120 }}
          onChange={(event) => {
            const value = event.target.value.trim().toUpperCase();
            setPageNum(1);
            setFilters((current) => ({ ...current, firstLetter: value || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 110 }}
          options={[
            { value: 1, label: '正常' },
            { value: 0, label: '禁用' },
          ]}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        {hasPerm('admin:artist:manage') && (
          <Button type="primary" onClick={openCreate}>
            新增歌手
          </Button>
        )}
      </Space>

      <Table<Artist>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1200 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 位歌手`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Modal
        title={creating ? '新增歌手' : `编辑歌手：${editing?.name ?? ''}`}
        open={creating || editing !== null}
        onCancel={() => {
          setCreating(false);
          setEditing(null);
        }}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        width={620}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="歌手名"
            rules={[{ required: true, message: '请输入歌手名' }, { max: 100 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="alias" label="别名 / 英文名">
            <Input />
          </Form.Item>
          <Form.Item name="avatar" label="头像">
            <ImageUploadField placeholder="上传头像，或填写图片 URL" />
          </Form.Item>
          <Form.Item name="cover" label="封面">
            <ImageUploadField placeholder="上传封面，或填写图片 URL" />
          </Form.Item>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item name="gender" label="性别/类型" style={{ flex: 1 }}>
              <Select
                options={Object.entries(GENDER).map(([value, label]) => ({
                  value: Number(value),
                  label,
                }))}
              />
            </Form.Item>
            <Form.Item name="country" label="国家/地区" style={{ flex: 1 }}>
              <Input />
            </Form.Item>
          </Space>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item name="birthday" label="生日" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="debutDate" label="出道日期" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item name="styles" label="风格标签" extra="逗号分隔，如：流行,民谣">
            <Input />
          </Form.Item>
          <Form.Item name="firstLetter" label="首字母" extra="留空时后端按名称自动截取">
            <Input maxLength={1} />
          </Form.Item>
          <Form.Item name="description" label="简介">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 1, label: '正常' },
                { value: 0, label: '禁用' },
              ]}
            />
          </Form.Item>
          {editing && (
            <Form.Item name="version" hidden>
              <InputNumber />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Card>
  );
}
