import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
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
import dayjs from 'dayjs';
import { accountApi, type AccountPayload } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { AdminRole, AdminUser, PageResult } from '@/api/types';

interface AccountFormValues {
  username: string;
  password?: string;
  realName?: string;
  email?: string;
  phone?: string;
  roleCode: string;
  allowIpList?: string;
  remark?: string;
}

export function AccountsPage(): JSX.Element {
  const { admin } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<{ keyword?: string; status?: number }>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [secret, setSecret] = useState<{ title: string; content: string; hint: string } | null>(null);
  const [form] = Form.useForm<AccountFormValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<AdminUser>>({
    queryKey: ['admin-accounts', query],
    queryFn: () => accountApi.page(query),
  });

  const rolesQuery = useQuery<AdminRole[]>({
    queryKey: ['admin-roles'],
    queryFn: accountApi.roles,
  });

  const roleOptions = (rolesQuery.data ?? []).map((role) => ({
    value: role.roleCode,
    label: `${role.roleName}（${role.roleCode}）`,
  }));

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-accounts'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ roleCode: 'OPERATOR' });
    setCreating(true);
  };

  const openEdit = (record: AdminUser): void => {
    form.resetFields();
    form.setFieldsValue({
      username: record.username,
      password: '',
      realName: record.realName,
      email: record.email,
      roleCode: record.roles[0],
      allowIpList: record.allowIpList,
      remark: record.remark,
    });
    setEditing(record);
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (creating) {
        const payload: AccountPayload = {
          username: values.username.trim(),
          password: values.password,
          realName: values.realName?.trim() || undefined,
          email: values.email?.trim() || undefined,
          phone: values.phone?.trim() || undefined,
          roleCode: values.roleCode,
          totpEnabled: false,
          allowIpList: values.allowIpList?.trim() || undefined,
          remark: values.remark?.trim() || undefined,
        };
        await accountApi.create(payload);
        message.success('管理员已创建，初始密码仅可使用一次，首次登录需改密');
        setCreating(false);
      } else if (editing) {
        const payload: AccountPayload = {
          username: values.username.trim(),
          password: values.password?.trim() || undefined,
          realName: values.realName?.trim() || undefined,
          email: values.email?.trim() || undefined,
          roleCode: values.roleCode,
          // 保持当前 TOTP 状态；开启/重置走「重置 TOTP」入口
          totpEnabled: editing.totpEnabled === 1,
          allowIpList: values.allowIpList?.trim() || undefined,
          remark: values.remark?.trim() || undefined,
        };
        await accountApi.update(editing.id, payload);
        message.success('管理员已更新；改密或变更角色会立即使该账号会话失效');
        setEditing(null);
      }
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const changeStatus = async (record: AdminUser): Promise<void> => {
    const next = record.status === 1 ? 2 : 1;
    try {
      await accountApi.changeStatus(record.id, next);
      message.success(next === 1 ? '账号已启用' : '账号已禁用');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const resetPassword = async (record: AdminUser): Promise<void> => {
    try {
      const password = await accountApi.resetPassword(record.id);
      setSecret({
        title: `临时密码：${record.username}`,
        content: password,
        hint: '该账号所有会话已失效，下次登录必须修改密码。请通过安全渠道转交。',
      });
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const resetTotp = async (record: AdminUser): Promise<void> => {
    try {
      const key = await accountApi.resetTotp(record.id);
      setSecret({
        title: `TOTP 新密钥：${record.username}`,
        content: key,
        hint: 'TOTP 已立即启用，该账号所有会话已失效。请让账号本人在验证器中使用该 Base32 密钥重新绑定后再登录。',
      });
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<AdminUser> = [
    { title: '登录名', dataIndex: 'username', width: 150 },
    { title: '姓名', dataIndex: 'realName', width: 130, render: (value?: string) => value || '—' },
    { title: '邮箱', dataIndex: 'email', width: 190, render: (value?: string) => value || '—' },
    {
      title: '手机号',
      dataIndex: 'phoneMasked',
      width: 140,
      render: (value?: string) => value || '—',
    },
    {
      title: '角色',
      dataIndex: 'roles',
      width: 190,
      render: (value: string[]) =>
        value.length === 0 ? '—' : value.map((role) => <Tag key={role}>{role}</Tag>),
    },
    {
      title: 'TOTP',
      dataIndex: 'totpEnabled',
      width: 90,
      render: (value?: number) =>
        value === 1 ? <Tag color="green">已开启</Tag> : <Tag>未开启</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) =>
        value === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">禁用</Tag>,
    },
    {
      title: '最近登录',
      dataIndex: 'lastLoginTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 280,
      render: (_, record) => {
        const isSelf = String(record.id) === String(admin?.id);
        return (
          <Space size={4} wrap>
            <Button type="link" size="small" onClick={() => openEdit(record)}>
              编辑
            </Button>
            {!isSelf && (
              <Popconfirm
                title={record.status === 1 ? '确认禁用该管理员？' : '确认启用该管理员？'}
                onConfirm={() => void changeStatus(record)}
              >
                <Button type="link" size="small" danger={record.status === 1}>
                  {record.status === 1 ? '禁用' : '启用'}
                </Button>
              </Popconfirm>
            )}
            <Popconfirm
              title="重置该管理员密码？"
              description="将生成一次性临时密码并踢出所有会话。"
              onConfirm={() => void resetPassword(record)}
            >
              <Button type="link" size="small">
                重置密码
              </Button>
            </Popconfirm>
            <Popconfirm
              title="重置该管理员 TOTP？"
              description="将生成新密钥并立即启用 TOTP，原验证器绑定失效。"
              onConfirm={() => void resetTotp(record)}
            >
              <Button type="link" size="small">
                重置 TOTP
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="登录名 / 姓名 / 邮箱"
          style={{ width: 220 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 120 }}
          options={[
            { value: 1, label: '启用' },
            { value: 2, label: '禁用' },
          ]}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        <Button type="primary" onClick={openCreate}>
          新增管理员
        </Button>
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="账号安全约束"
        description="不能禁用自己；系统始终保留至少一个可用超管。TOTP 只能通过「重置 TOTP」生成密钥并启用，创建/编辑表单不会直接开启，避免出现没有密钥却要求动态码的死锁账号。"
      />

      <Table<AdminUser>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1400 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 个管理员`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Modal
        title={creating ? '新增管理员' : `编辑管理员：${editing?.username ?? ''}`}
        open={creating || editing !== null}
        onCancel={() => {
          setCreating(false);
          setEditing(null);
        }}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="username"
            label="登录名"
            rules={[
              { required: true, message: '请输入登录名' },
              { min: 3, max: 64, message: '登录名长度需为 3-64 位' },
            ]}
          >
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="password"
            label={creating ? '初始密码' : '新密码（留空不修改）'}
            rules={
              creating
                ? [
                    { required: true, message: '请输入初始密码' },
                    { min: 8, max: 64, message: '密码长度需为 8-64 位' },
                    { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '至少包含字母与数字' },
                  ]
                : [
                    { min: 8, max: 64, message: '密码长度需为 8-64 位' },
                    { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '至少包含字母与数字' },
                  ]
            }
            extra={creating ? '初始密码只能使用一次，首次登录会强制改密' : undefined}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="realName" label="姓名" rules={[{ max: 64 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
            <Input />
          </Form.Item>
          {creating && (
            <Form.Item name="phone" label="手机号">
              <Input />
            </Form.Item>
          )}
          <Form.Item name="roleCode" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={roleOptions} loading={rolesQuery.isLoading} />
          </Form.Item>
          <Form.Item
            name="allowIpList"
            label="IP 白名单"
            extra="逗号分隔，支持单个 IP；留空表示不限制"
          >
            <Input placeholder="127.0.0.1,10.0.0.0/8" />
          </Form.Item>
          <Form.Item name="remark" label="备注" rules={[{ max: 200 }]}>
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={secret?.title ?? '敏感信息'}
        open={secret !== null}
        onCancel={() => setSecret(null)}
        footer={
          <Button type="primary" onClick={() => setSecret(null)}>
            我已记录
          </Button>
        }
      >
        <Typography.Paragraph>{secret?.hint}</Typography.Paragraph>
        <Typography.Paragraph copyable strong style={{ fontSize: 18 }}>
          {secret?.content}
        </Typography.Paragraph>
        <Alert
          type="warning"
          showIcon
          message="该值只显示这一次，关闭后无法再次查看"
        />
      </Modal>
    </Card>
  );
}
