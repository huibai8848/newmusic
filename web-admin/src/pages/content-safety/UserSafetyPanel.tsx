import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { safetyApi, type RestrictPayload } from '@/api/contentSafety';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { userApi } from '@/api/endpoints';
import type { CUser, CreditLog, Id, UserRestriction } from '@/api/types';

const RESTRICT_TYPE: Record<number, string> = {
  1: '限制发言',
  2: '禁言',
  3: '封禁',
  4: '永久封禁',
  5: '限制上传',
};

const RESTRICT_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '已解除', color: 'default' },
  1: { text: '生效中', color: 'red' },
  2: { text: '已到期', color: 'orange' },
};

interface RestrictValues {
  type: number;
  level: number;
  days?: number;
  reason: string;
  reasonCategory?: string;
  remark?: string;
}

interface BanValues {
  reason: string;
  confirm: string;
  reasonCategory?: string;
}

interface CreditValues {
  changeScore: number;
  reason: string;
}

interface MinorValues {
  isMinor: boolean;
  dailyLimitMinutes: number;
  disableStartHour: number;
  disableEndHour: number;
  mode: boolean;
}

function hourOf(value: unknown): number {
  if (typeof value !== 'string' || !value) return 22;
  return Number(value.slice(0, 2)) || 0;
}

function MinorCard({ userId }: { userId: Id }): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<MinorValues>();
  const [guardian, setGuardian] = useState('');

  const query = useQuery({
    queryKey: ['safety-minor', String(userId)],
    queryFn: () => safetyApi.users.minor.status(userId),
  });

  useEffect(() => {
    if (query.data) {
      form.setFieldsValue({
        isMinor: query.data.isMinor === 1,
        dailyLimitMinutes: Number(query.data.dailyLimitMinutes ?? 40),
        disableStartHour: hourOf(query.data.disableStart),
        disableEndHour: hourOf(query.data.disableEnd),
        mode: query.data.mode === 1,
      });
    }
  }, [query.data, form]);

  const save = useMutation({
    mutationFn: (values: MinorValues) => safetyApi.users.minor.save(userId, values),
    onSuccess: () => {
      message.success('未成年人保护配置已保存');
      void queryClient.invalidateQueries({ queryKey: ['safety-minor', String(userId)] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const bind = useMutation({
    mutationFn: () => safetyApi.users.minor.bindGuardian(userId, guardian.trim()),
    onSuccess: () => {
      message.success('监护人已绑定');
      setGuardian('');
      void queryClient.invalidateQueries({ queryKey: ['safety-minor', String(userId)] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <Card size="small" title="未成年人保护">
      {!hasPerm('admin:user:edit') ? (
        <Alert type="info" showIcon message="需要 admin:user:edit 才能修改" style={{ marginBottom: 12 }} />
      ) : null}
      <Form form={form} layout="vertical" onFinish={(values) => save.mutate(values)}>
        <Row gutter={12}>
          <Col span={6}>
            <Form.Item name="isMinor" label="标记为未成年人" valuePropName="checked">
              <Switch disabled={!hasPerm('admin:user:edit')} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="mode" label="强制青少年模式" valuePropName="checked">
              <Switch disabled={!hasPerm('admin:user:edit')} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="dailyLimitMinutes" label="每日使用上限（分钟）">
              <InputNumber min={1} max={1440} style={{ width: '100%' }} disabled={!hasPerm('admin:user:edit')} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="disableStartHour" label="禁用开始小时">
              <InputNumber min={1} max={24} style={{ width: '100%' }} disabled={!hasPerm('admin:user:edit')} />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="disableEndHour" label="禁用结束小时（0=午夜）">
              <InputNumber min={0} max={23} style={{ width: '100%' }} disabled={!hasPerm('admin:user:edit')} />
            </Form.Item>
          </Col>
          <Col span={18} style={{ display: 'flex', alignItems: 'flex-end', paddingBottom: 24 }}>
            <Button type="primary" htmlType="submit" loading={save.isPending} disabled={!hasPerm('admin:user:edit')}>
              保存配置
            </Button>
          </Col>
        </Row>
      </Form>
      <Divider style={{ margin: '8px 0 12px' }} />
      <Space>
        <Input
          value={guardian}
          placeholder="监护人用户 ID"
          style={{ width: 220 }}
          onChange={(event) => setGuardian(event.target.value)}
        />
        <Button
          loading={bind.isPending}
          disabled={!hasPerm('admin:user:edit') || !guardian.trim()}
          onClick={() => bind.mutate()}
        >
          绑定监护人
        </Button>
        {query.data?.guardianUserId ? (
          <Tag color="blue">当前监护人：{String(query.data.guardianUserId)}</Tag>
        ) : (
          <Tag>未绑定监护人</Tag>
        )}
      </Space>
    </Card>
  );
}

export function UserSafetyPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<CUser | null>(null);
  const [restrictOpen, setRestrictOpen] = useState(false);
  const [banOpen, setBanOpen] = useState(false);
  const [creditOpen, setCreditOpen] = useState(false);
  const [restrictForm] = Form.useForm<RestrictValues>();
  const [banForm] = Form.useForm<BanValues>();
  const [creditForm] = Form.useForm<CreditValues>();

  const users = useQuery({
    queryKey: ['safety-user-search', keyword],
    queryFn: () => userApi.page({ keyword, pageNum: 1, pageSize: 10 }),
    enabled: keyword.trim().length > 0,
  });

  const userId = selected ? String(selected.id) : '';
  const credit = useQuery({
    queryKey: ['safety-credit', userId],
    queryFn: () => safetyApi.users.credit(userId),
    enabled: Boolean(userId),
  });
  const restrictions = useQuery({
    queryKey: ['safety-restrictions', userId],
    queryFn: () => safetyApi.users.restrictions(userId),
    enabled: Boolean(userId),
  });
  const history = useQuery({
    queryKey: ['safety-restriction-history', userId],
    queryFn: () => safetyApi.users.restrictionHistory(userId),
    enabled: Boolean(userId),
  });
  const creditLogs = useQuery({
    queryKey: ['safety-credit-logs', userId],
    queryFn: () => safetyApi.users.creditLogs(userId),
    enabled: Boolean(userId),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['safety-credit', userId] });
    void queryClient.invalidateQueries({ queryKey: ['safety-restrictions', userId] });
    void queryClient.invalidateQueries({ queryKey: ['safety-restriction-history', userId] });
    void queryClient.invalidateQueries({ queryKey: ['safety-credit-logs', userId] });
  };

  const restrict = useMutation({
    mutationFn: (values: RestrictValues) => safetyApi.users.restrict(userId, values as RestrictPayload),
    onSuccess: () => {
      message.success('处置已生效');
      setRestrictOpen(false);
      restrictForm.resetFields();
      refresh();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const ban = useMutation({
    mutationFn: (values: BanValues) => safetyApi.users.permanentBan(userId, values),
    onSuccess: () => {
      message.success('永久封禁已生效');
      setBanOpen(false);
      banForm.resetFields();
      refresh();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const adjust = useMutation({
    mutationFn: (values: CreditValues) => safetyApi.users.adjustCredit(userId, values),
    onSuccess: () => {
      message.success('信用分已调整');
      setCreditOpen(false);
      creditForm.resetFields();
      refresh();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const release = useMutation({
    mutationFn: (rid: Id) => safetyApi.users.release(userId, rid, '后台解除处置'),
    onSuccess: () => {
      message.success('处置已解除');
      refresh();
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const restrictionColumns: ColumnsType<UserRestriction> = [
    { title: '记录 ID', dataIndex: 'id', width: 190, render: (value) => String(value) },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (value: number) => RESTRICT_TYPE[value] ?? value,
    },
    { title: '级别', dataIndex: 'level', width: 70, render: (value) => `L${value}` },
    { title: '原因', dataIndex: 'reason', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) => <Tag color={RESTRICT_STATUS[value]?.color}>{RESTRICT_STATUS[value]?.text}</Tag>,
    },
    {
      title: '结束时间',
      dataIndex: 'endTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '永久'),
    },
    {
      title: '操作',
      width: 100,
      render: (_, row) =>
        row.status === 1 ? (
          <Popconfirm title="解除该处置？" onConfirm={() => release.mutate(row.id)}>
            <Button size="small" danger disabled={!hasPerm('admin:user:restrict')}>
              解除
            </Button>
          </Popconfirm>
        ) : (
          '-'
        ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card size="small" title="选择用户">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input.Search
            allowClear
            placeholder="输入用户 ID / 昵称 / 用户名搜索"
            style={{ maxWidth: 420 }}
            onSearch={(value) => setKeyword(value.trim())}
          />
          {keyword && users.data ? (
            <Table<CUser>
              rowKey={(row) => String(row.id)}
              size="small"
              pagination={false}
              dataSource={users.data.list}
              columns={[
                { title: '用户 ID', dataIndex: 'id', width: 190, render: (value) => String(value) },
                { title: '昵称', dataIndex: 'nickname' },
                { title: '用户名', dataIndex: 'username' },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 90,
                  render: (value?: number) => (value === 2 ? <Tag color="red">封禁</Tag> : <Tag color="green">正常</Tag>),
                },
                {
                  title: '操作',
                  width: 100,
                  render: (_, row) => (
                    <Button
                      size="small"
                      type={String(selected?.id) === String(row.id) ? 'primary' : 'default'}
                      onClick={() => setSelected(row)}
                    >
                      选择
                    </Button>
                  ),
                },
              ]}
            />
          ) : null}
        </Space>
      </Card>

      {!selected ? (
        <Alert type="info" showIcon message="先搜索并选择一个用户，再查看信用分与处置记录。" />
      ) : (
        <>
          <Card
            size="small"
            title={`用户 ${String(selected.id)} · ${selected.nickname}`}
            extra={
              <Space>
                <Button
                  onClick={() => {
                    restrictForm.resetFields();
                    restrictForm.setFieldsValue({ type: 1, level: 2, days: 1, reasonCategory: 'OTHER' });
                    setRestrictOpen(true);
                  }}
                  disabled={!hasPerm('admin:user:restrict')}
                >
                  用户处置
                </Button>
                <Button danger onClick={() => setBanOpen(true)} disabled={!hasPerm('admin:user:ban:permanent')}>
                  永久封禁
                </Button>
              </Space>
            }
          >
            <Row gutter={16}>
              <Col span={5}>
                <Statistic title="信用分" value={credit.data?.score ?? '-'} />
              </Col>
              <Col span={5}>
                <Statistic title="90 天违规次数" value={credit.data?.violationCount90d ?? 0} />
              </Col>
              <Col span={7}>
                <Descriptions size="small" column={1}>
                  <Descriptions.Item label="最近违规">
                    {credit.data?.lastViolationTime
                      ? dayjs(credit.data.lastViolationTime).format('YYYY-MM-DD HH:mm') : '-'}
                  </Descriptions.Item>
                </Descriptions>
              </Col>
              <Col span={7} style={{ textAlign: 'right' }}>
                <Button
                  disabled={!hasPerm('admin:user:credit:edit')}
                  onClick={() => {
                    creditForm.resetFields();
                    setCreditOpen(true);
                  }}
                >
                  调整信用分
                </Button>
              </Col>
            </Row>
          </Card>

          <Card size="small" title="生效中的处置">
            <Table<UserRestriction>
              rowKey={(row) => String(row.id)}
              size="small"
              loading={restrictions.isLoading}
              pagination={false}
              dataSource={restrictions.data ?? []}
              columns={restrictionColumns}
              locale={{ emptyText: '当前没有生效中的处置' }}
            />
          </Card>

          <Card size="small" title="处置历史">
            <Table<UserRestriction>
              rowKey={(row) => String(row.id)}
              size="small"
              loading={history.isLoading}
              pagination={{ pageSize: 10 }}
              dataSource={history.data ?? []}
              columns={restrictionColumns.filter((column) => column.key !== 'actions')}
            />
          </Card>

          <Card size="small" title="信用分流水">
            <Table<CreditLog>
              rowKey={(row) => String(row.id)}
              size="small"
              loading={creditLogs.isLoading}
              pagination={{ pageSize: 10 }}
              dataSource={creditLogs.data ?? []}
              columns={[
                {
                  title: '变动',
                  dataIndex: 'changeScore',
                  width: 90,
                  render: (value: number) => (
                    <Typography.Text type={value < 0 ? 'danger' : 'success'}>
                      {value > 0 ? `+${value}` : value}
                    </Typography.Text>
                  ),
                },
                { title: '变动前', dataIndex: 'beforeScore', width: 90 },
                { title: '变动后', dataIndex: 'afterScore', width: 90 },
                { title: '原因', dataIndex: 'reason', ellipsis: true },
                {
                  title: '时间',
                  dataIndex: 'createTime',
                  width: 170,
                  render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
                },
              ]}
            />
          </Card>

          <MinorCard userId={userId} />
        </>
      )}

      <Modal
        title="用户处置"
        open={restrictOpen}
        confirmLoading={restrict.isPending}
        onCancel={() => setRestrictOpen(false)}
        onOk={() => {
          void restrictForm.validateFields().then((values) => restrict.mutate(values));
        }}
      >
        <Form form={restrictForm} layout="vertical">
          <Form.Item name="type" label="处置类型" rules={[{ required: true }]}>
            <Select
              options={Object.entries(RESTRICT_TYPE)
                .filter(([value]) => value !== '4')
                .map(([value, label]) => ({ value: Number(value), label }))}
            />
          </Form.Item>
          <Form.Item name="level" label="处置等级（L1 警告会立即解除）" rules={[{ required: true }]}>
            <Select options={[1, 2, 3, 4, 5].map((value) => ({ value, label: `L${value}` }))} />
          </Form.Item>
          <Form.Item name="days" label="时长（天）">
            <InputNumber min={1} max={3650} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reasonCategory" label="原因分类">
            <Select
              allowClear
              options={['OTHER', 'PORN', 'POLITICS', 'AD', 'ABUSE', 'COPYRIGHT', 'FRAUD'].map((value) => ({
                value, label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="reason" label="处置原因" rules={[{ required: true }, { min: 2, max: 500 }]}>
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input maxLength={256} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="永久封禁"
        open={banOpen}
        confirmLoading={ban.isPending}
        okText="确认永久封禁"
        okButtonProps={{ danger: true }}
        onCancel={() => setBanOpen(false)}
        onOk={() => {
          void banForm.validateFields().then((values) => ban.mutate(values));
        }}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="不可逆操作"
          description="永久封禁会同步账号状态为封禁，只能由超级管理员解除。原因至少 10 字，并需输入 BAN 二次确认。"
        />
        <Form form={banForm} layout="vertical">
          <Form.Item name="reason" label="详细原因" rules={[{ required: true }, { min: 10, max: 500 }]}>
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Form.Item name="reasonCategory" label="原因分类">
            <Select
              allowClear
              options={['OTHER', 'PORN', 'POLITICS', 'AD', 'ABUSE', 'COPYRIGHT', 'FRAUD'].map((value) => ({
                value, label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="confirm" label="二次确认（输入 BAN）" rules={[{ required: true }]}>
            <Input placeholder="BAN" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="调整信用分"
        open={creditOpen}
        confirmLoading={adjust.isPending}
        onCancel={() => setCreditOpen(false)}
        onOk={() => {
          void creditForm.validateFields().then((values) => adjust.mutate(values));
        }}
      >
        <Form form={creditForm} layout="vertical">
          <Form.Item name="changeScore" label="变动值（正数加分，负数扣分）" rules={[{ required: true }]}>
            <InputNumber min={-100} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="原因" rules={[{ required: true }, { min: 2, max: 200 }]}>
            <Input.TextArea rows={2} maxLength={200} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
