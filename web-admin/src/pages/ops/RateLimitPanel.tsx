import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { opsApi, type RateLimitRule } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { fmtNumber, fmtTime } from './format';

interface EditValues {
  threshold: number;
  windowSeconds: number;
  dimension: string;
  httpMethod?: string;
  enabled: boolean;
  whitelistIps?: string;
  description?: string;
}

/**
 * 限流规则：阈值热更新（3 秒内生效）+ 本进程命中计数。
 *
 * 规则来自数据库，不是写死在 yml：运维改阈值不需要发版。超管在服务端被豁免，
 * 所以这里改小阈值不会把管理员自己锁在门外——验收脚本正是利用这一点做真实拒绝测试。
 */
export function RateLimitPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:ops:edit');
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<RateLimitRule | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<EditValues>();

  const query = useQuery({
    queryKey: ['ops-rate-limits'],
    queryFn: opsApi.rateLimits,
    refetchInterval: 5000,
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-rate-limits'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-overview'] });
  };

  const openEdit = (rule: RateLimitRule): void => {
    form.resetFields();
    form.setFieldsValue({
      threshold: rule.threshold,
      windowSeconds: rule.windowSeconds,
      dimension: rule.dimension,
      httpMethod: rule.httpMethod ?? undefined,
      enabled: rule.enabled === 1,
      whitelistIps: rule.whitelistIps ?? undefined,
      description: rule.description ?? undefined,
    });
    setEditing(rule);
  };

  const submit = async (): Promise<void> => {
    if (!editing) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await opsApi.updateRateLimit(editing.resource, {
        threshold: values.threshold,
        windowSeconds: values.windowSeconds,
        dimension: values.dimension,
        httpMethod: values.httpMethod ?? '',
        enabled: values.enabled ? 1 : 0,
        whitelistIps: values.whitelistIps ?? '',
        description: values.description ?? '',
      });
      message.success('限流规则已更新，下一次刷新生效（≤3 秒）');
      setEditing(null);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const resetCounters = async (): Promise<void> => {
    try {
      await opsApi.resetRateLimitCounters();
      message.success('本进程命中计数已清零');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<RateLimitRule> = [
    { title: '资源', dataIndex: 'resource', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: '路径前缀', dataIndex: 'matchPattern', ellipsis: true },
    {
      title: '方法',
      dataIndex: 'httpMethod',
      width: 80,
      render: (v: string | null) => v || '全部',
    },
    { title: '维度', dataIndex: 'dimension', width: 90 },
    {
      title: '阈值',
      dataIndex: 'threshold',
      width: 150,
      render: (v: number, row) => `${fmtNumber(v)} 次 / ${row.windowSeconds}s`,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (v: number) => <Tag color={v === 1 ? 'green' : 'default'}>{v === 1 ? '启用' : '停用'}</Tag>,
    },
    {
      title: '本进程命中',
      key: 'counts',
      width: 180,
      render: (_, row) => (
        <Space size={4} wrap>
          <Tag color="blue">放行 {fmtNumber(row.allowedTotal)}</Tag>
          <Tag color={row.blockedTotal > 0 ? 'red' : 'default'}>拒绝 {fmtNumber(row.blockedTotal)}</Tag>
        </Space>
      ),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    { title: '更新时间', dataIndex: 'updateTime', width: 150, render: (v: string) => fmtTime(v) },
    ...(canEdit
      ? [
          {
            title: '操作',
            key: 'action',
            width: 80,
            render: (_: unknown, row: RateLimitRule) => (
              <Button type="link" size="small" onClick={() => openEdit(row)}>
                编辑
              </Button>
            ),
          },
        ]
      : []),
  ];

  const redisAvailable = query.data?.[0]?.redisAvailable;
  const localFallback = query.data?.[0]?.localFallbackEnabled;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="阈值改动 3 秒内热生效；拒绝时返回 HTTP 429 + Retry-After"
        description={
          <Space size={8} wrap>
            <span>
              Redis 限流：<Tag color={redisAvailable ? 'green' : 'orange'}>{redisAvailable ? '可用' : '不可用'}</Tag>
            </span>
            <span>
              本地兜底：<Tag color={localFallback ? 'green' : 'default'}>{localFallback ? '开启' : '关闭'}</Tag>
            </span>
            <Typography.Text type="secondary">
              Redis 故障时切到单机滑动窗口，不会退化成「不限流」。
            </Typography.Text>
          </Space>
        }
      />

      <Card
        size="small"
        title="限流规则"
        extra={
          canEdit && (
            <Space>
              <Popconfirm title="清零本进程的放行/拒绝计数？" onConfirm={resetCounters}>
                <Button size="small">清零计数</Button>
              </Popconfirm>
              <Button size="small" onClick={refresh}>
                刷新
              </Button>
            </Space>
          )
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={query.isLoading}
          dataSource={query.data ?? []}
          columns={columns}
          pagination={false}
          scroll={{ x: 1200 }}
        />
      </Card>

      <Modal
        open={editing !== null}
        title={`编辑限流规则：${editing?.resource ?? ''}`}
        okText="保存"
        confirmLoading={saving}
        onOk={submit}
        onCancel={() => setEditing(null)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="threshold" label="窗口内最大请求数" rules={[{ required: true }]}>
            <InputNumber min={1} max={10000000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="windowSeconds" label="窗口（秒）" rules={[{ required: true }]}>
            <InputNumber min={1} max={86400} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dimension" label="限流维度" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'IP', label: '按 IP' },
                { value: 'USER', label: '按用户（游客回退 IP）' },
                { value: 'GLOBAL', label: '全站' },
              ]}
            />
          </Form.Item>
          <Form.Item name="httpMethod" label="HTTP 方法（留空=全部）">
            <Select
              allowClear
              options={['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map((value) => ({ value, label: value }))}
            />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="whitelistIps" label="IP 白名单（逗号分隔）">
            <Input.TextArea rows={2} placeholder="127.0.0.1,10.0.0.1" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input maxLength={255} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
