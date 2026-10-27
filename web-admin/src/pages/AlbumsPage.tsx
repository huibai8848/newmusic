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
import { CustomerServiceOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { albumApi, type AlbumPayload } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { Album, PageResult } from '@/api/types';

interface Filters {
  keyword?: string;
  artistId?: string;
  status?: number;
}

interface FormValues extends Omit<AlbumPayload, 'publishDate'> {
  publishDate?: Dayjs;
}

export function AlbumsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Album | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<FormValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<Album>>({
    queryKey: ['admin-albums', query],
    queryFn: () => albumApi.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-albums'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ status: 1 });
    setCreating(true);
  };

  const openEdit = async (record: Album): Promise<void> => {
    try {
      const detail = await albumApi.detail(record.id);
      form.resetFields();
      form.setFieldsValue({
        name: detail.name,
        artistId: detail.artistId,
        cover: detail.cover,
        publishDate: detail.publishDate ? dayjs(detail.publishDate) : undefined,
        company: detail.company,
        description: detail.description,
        language: detail.language,
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
    const payload: AlbumPayload = {
      ...values,
      name: values.name.trim(),
      publishDate: values.publishDate?.format('YYYY-MM-DD'),
    };
    setSaving(true);
    try {
      if (creating) {
        await albumApi.create({ ...payload, version: undefined });
        message.success('专辑已创建');
        setCreating(false);
      } else if (editing) {
        await albumApi.update(editing.id, payload);
        message.success('专辑已更新');
        setEditing(null);
      }
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (record: Album): Promise<void> => {
    try {
      await albumApi.remove(record.id);
      message.success('专辑已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<Album> = [
    {
      title: '专辑',
      key: 'name',
      width: 280,
      render: (_, record) => (
        <Space>
          <Avatar shape="square" src={record.cover} icon={<CustomerServiceOutlined />} />
          <div>
            <div>{record.name}</div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {record.language || '—'}
            </Typography.Text>
          </div>
        </Space>
      ),
    },
    {
      title: '歌手',
      key: 'artist',
      width: 200,
      render: (_, record) => `${record.artistName || '—'}（${String(record.artistId)}）`,
    },
    {
      title: '发行日期',
      dataIndex: 'publishDate',
      width: 130,
      render: (value?: string) => value || '—',
    },
    { title: '发行公司', dataIndex: 'company', width: 180, render: (value?: string) => value || '—' },
    { title: '歌曲数', dataIndex: 'songCount', width: 90, render: (value?: number) => value ?? 0 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value?: number) => (value === 1 ? <Tag color="green">上架</Tag> : <Tag>下架</Tag>),
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
          {hasPerm('admin:album:manage') && (
            <Popconfirm
              title="删除该专辑？"
              description="有关联歌曲时后端会拒绝删除（BR-18-071）。"
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
          placeholder="专辑名"
          style={{ width: 220 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="歌手 ID"
          style={{ width: 180 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, artistId: value || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 110 }}
          options={[
            { value: 1, label: '上架' },
            { value: 0, label: '下架' },
          ]}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        {hasPerm('admin:album:manage') && (
          <Button type="primary" onClick={openCreate}>
            新增专辑
          </Button>
        )}
      </Space>

      <Table<Album>
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
          showTotal: (total) => `共 ${total} 张专辑`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Modal
        title={creating ? '新增专辑' : `编辑专辑：${editing?.name ?? ''}`}
        open={creating || editing !== null}
        onCancel={() => {
          setCreating(false);
          setEditing(null);
        }}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="专辑名"
            rules={[{ required: true, message: '请输入专辑名' }, { max: 200 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="artistId"
            label="主歌手 ID"
            rules={[{ required: true, message: '请输入主歌手 ID' }]}
            extra="从歌手管理页获取歌手 ID"
          >
            <InputNumber style={{ width: '100%' }} min={1} precision={0} stringMode />
          </Form.Item>
          <Form.Item name="cover" label="封面 URL">
            <Input placeholder="可通过文件上传接口取得 /media/uploads/... 地址" />
          </Form.Item>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item name="publishDate" label="发行日期" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="language" label="语种" style={{ flex: 1 }}>
              <Input placeholder="国语 / 粤语 / 英语…" />
            </Form.Item>
          </Space>
          <Form.Item name="company" label="发行公司">
            <Input />
          </Form.Item>
          <Form.Item name="description" label="专辑简介">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 1, label: '上架' },
                { value: 0, label: '下架' },
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
