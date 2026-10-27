import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Card,
  DatePicker,
  Form,
  Input,
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
import dayjs, { type Dayjs } from 'dayjs';
import { userApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { CUser, PageResult } from '@/api/types';

interface Filters {
  keyword?: string;
  status?: number;
  userType?: number;
  banned?: boolean;
}

interface EditValues {
  nickname?: string;
  email?: string;
  userType?: number;
}

interface BanValues {
  reason: string;
  banUntil?: Dayjs;
}

const USER_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待验证', color: 'default' },
  1: { text: '正常', color: 'green' },
  2: { text: '封禁', color: 'red' },
  3: { text: '注销中', color: 'orange' },
  4: { text: '已注销', color: 'default' },
};

export function UsersPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<CUser | null>(null);
  const [banning, setBanning] = useState<CUser | null>(null);
  const [saving, setSaving] = useState(false);
  const [editForm] = Form.useForm<EditValues>();
  const [banForm] = Form.useForm<BanValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<CUser>>({
    queryKey: ['admin-users', query],
    queryFn: () => userApi.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-users'] });
  };

  const openEdit = (record: CUser): void => {
    setEditing(record);
    editForm.setFieldsValue({
      nickname: record.nickname,
      email: record.email,
      userType: record.userType,
    });
  };

  const saveEdit = async (): Promise<void> => {
    if (!editing) return;
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      await userApi.edit(editing.id, values);
      message.success('用户资料已更新');
      setEditing(null);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const viewPhone = async (record: CUser): Promise<void> => {
    try {
      const result = await userApi.phone(record.id);
      Modal.info({
        title: `用户 ${record.username} 的手机号`,
        content: <Typography.Text copyable>{result.phone}</Typography.Text>,
        okText: '关闭',
      });
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const resetPassword = async (record: CUser): Promise<void> => {
    try {
      const password = await userApi.resetPassword(record.id);
      Modal.success({
        title: '临时密码已生成',
        content: (
          <div>
            <Typography.Paragraph>
              请通过安全渠道交给用户。用户下次登录后必须修改密码。
            </Typography.Paragraph>
            <Typography.Text copyable strong style={{ fontSize: 18 }}>
              {password}
            </Typography.Text>
          </div>
        ),
        okText: '我已记录',
      });
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const submitBan = async (): Promise<void> => {
    if (!banning) return;
    const values = await banForm.validateFields();
    setSaving(true);
    try {
      await userApi.ban(banning.id, {
        reason: values.reason.trim(),
        banUntil: values.banUntil?.format('YYYY-MM-DDTHH:mm:ss'),
      });
      message.success('已封禁该用户');
      setBanning(null);
      banForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const unban = async (record: CUser): Promise<void> => {
    try {
      await userApi.unban(record.id);
      message.success('已解封');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<CUser> = [
    { title: 'ID', dataIndex: 'id', width: 180, render: (value) => String(value) },
    { title: '用户名', dataIndex: 'username', width: 140 },
    { title: '昵称', dataIndex: 'nickname', width: 140 },
    {
      title: '手机号',
      dataIndex: 'phoneMasked',
      width: 140,
      render: (value) => value || <Typography.Text type="secondary">未绑定</Typography.Text>,
    },
    {
      title: '类型',
      dataIndex: 'userType',
      width: 90,
      render: (value: number) => (value === 2 ? <Tag color="blue">运营</Tag> : '普通'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: number) => {
        const status = USER_STATUS[value] ?? { text: `未知(${value})`, color: 'default' };
        return <Tag color={status.color}>{status.text}</Tag>;
      },
    },
    {
      title: '封禁信息',
      key: 'ban',
      width: 220,
      render: (_, record) =>
        record.status === 2 ? (
          <span>
            {record.banReason || '违反社区规范'}
            <br />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              至 {record.banUntil ? dayjs(record.banUntil).format('YYYY-MM-DD HH:mm') : '永久'}
            </Typography.Text>
          </span>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '最近登录',
      dataIndex: 'lastLoginTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '注册时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 300,
      render: (_, record) => (
        <Space size={4} wrap>
          {hasPerm('admin:user:edit') && (
            <Button type="link" size="small" onClick={() => openEdit(record)}>
              编辑
            </Button>
          )}
          {hasPerm('admin:user:phone:view') && (
            <Button type="link" size="small" onClick={() => void viewPhone(record)}>
              查看手机号
            </Button>
          )}
          {hasPerm('admin:user:edit') && (
            <Popconfirm
              title="重置密码"
              description="将生成一次性临时密码，并强制用户下次登录改密。"
              onConfirm={() => void resetPassword(record)}
            >
              <Button type="link" size="small">
                重置密码
              </Button>
            </Popconfirm>
          )}
          {hasPerm('admin:user:ban') && record.status !== 2 && (
            <Button type="link" size="small" danger onClick={() => setBanning(record)}>
              封禁
            </Button>
          )}
          {hasPerm('admin:user:unban') && record.status === 2 && (
            <Popconfirm title="确认解封该用户？" onConfirm={() => void unban(record)}>
              <Button type="link" size="small">
                解封
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
          placeholder="用户名 / 昵称 / 邮箱"
          style={{ width: 240 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 130 }}
          options={Object.entries(USER_STATUS).map(([value, status]) => ({
            value: Number(value),
            label: status.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        <Select
          allowClear
          placeholder="用户类型"
          style={{ width: 130 }}
          options={[
            { value: 1, label: '普通用户' },
            { value: 2, label: '运营' },
          ]}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, userType: value }));
          }}
        />
        <Select
          allowClear
          placeholder="是否封禁"
          style={{ width: 130 }}
          options={[
            { value: true, label: '已封禁' },
            { value: false, label: '未封禁' },
          ]}
          onChange={(value?: boolean) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, banned: value }));
          }}
        />
      </Space>

      <Table<CUser>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1600 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Modal
        title={`编辑用户：${editing?.username ?? ''}`}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={() => void saveEdit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            name="nickname"
            label="昵称"
            rules={[{ required: true, message: '请输入昵称' }, { max: 50 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="userType"
            label="用户类型"
            extra="后台身份与 C 端身份相互独立；这里只能设为普通用户或运营"
          >
            <Select
              options={[
                { value: 1, label: '普通用户' },
                { value: 2, label: '运营' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`封禁用户：${banning?.username ?? ''}`}
        open={banning !== null}
        onCancel={() => setBanning(null)}
        onOk={() => void submitBan()}
        confirmLoading={saving}
        okText="确认封禁"
        okButtonProps={{ danger: true }}
      >
        <Form form={banForm} layout="vertical">
          <Form.Item
            name="reason"
            label="封禁原因"
            rules={[
              { required: true, message: '请填写封禁原因' },
              { min: 5, max: 500, message: '原因需为 5-500 个字符' },
            ]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
          <Form.Item name="banUntil" label="解封时间" extra="留空表示永久封禁">
            <DatePicker showTime style={{ width: '100%' }} disabledDate={(date) => date.isBefore(dayjs())} />
          </Form.Item>
        </Form>
      </Modal>

    </Card>
  );
}
