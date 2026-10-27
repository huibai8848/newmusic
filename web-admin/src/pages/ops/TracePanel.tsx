import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { opsApi, type TraceQuery, type TraceRecord } from '@/api/ops';
import { fmtMs, fmtTime } from './format';

/**
 * 链路检索：内存环形缓冲，错误/4xx 全量、2xx 按采样率保留。
 *
 * 为什么不是 SkyWalking 查询页：本机没有 OAP；先把「按 traceId / userId / 路径 / 状态 /
 * 耗时」检索这条排障路径做通，接 SkyWalking 时把数据源换掉即可，交互不用变。
 */
export function TracePanel(): JSX.Element {
  const [filters, setFilters] = useState<TraceQuery>({ limit: 50 });
  const [form] = Form.useForm<TraceQuery>();

  const query = useQuery({
    queryKey: ['ops-traces', filters],
    queryFn: () => opsApi.traces(filters),
    refetchInterval: 10000,
  });

  const submit = (): void => {
    const values = form.getFieldsValue();
    setFilters({
      traceId: values.traceId?.trim() || undefined,
      userId: values.userId ? String(values.userId) : undefined,
      path: values.path?.trim() || undefined,
      status: values.status || undefined,
      minDurationMs: values.minDurationMs || undefined,
      limit: values.limit || 50,
    });
  };

  const reset = (): void => {
    form.resetFields();
    setFilters({ limit: 50 });
  };

  const stats = query.data?.stats;

  const columns: ColumnsType<TraceRecord> = [
    {
      title: 'traceId',
      dataIndex: 'traceId',
      width: 210,
      render: (value: string) => <Typography.Text copyable style={{ fontSize: 12 }}>{value}</Typography.Text>,
    },
    { title: '方法', dataIndex: 'method', width: 80 },
    { title: '路径', dataIndex: 'path', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (value: number) => <Tag color={value >= 500 ? 'red' : value >= 400 ? 'gold' : 'green'}>{value}</Tag>,
    },
    { title: '耗时', dataIndex: 'durationMs', width: 100, render: (value: number) => fmtMs(value) },
    { title: '用户', dataIndex: 'userId', width: 100, render: (value: string | null) => value || '游客' },
    { title: 'IP', dataIndex: 'ip', width: 130 },
    { title: '时间', dataIndex: 'timestamp', width: 150, render: (value: number) => fmtTime(new Date(value).toISOString()) },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message={`缓冲区 ${stats?.filled ?? 0}/${stats?.capacity ?? 0} 条 · 已记录 ${stats?.recorded ?? 0} · 采样丢弃 ${stats?.dropped ?? 0} · 2xx 采样率 ${Math.round((stats?.sampleRate ?? 0) * 100)}%`}
        description="4xx/5xx 与慢请求始终记录；正常 2xx 按 ops.trace.sample-rate 抽样，避免缓冲被闲聊流量冲掉。"
      />

      <Card size="small" title="检索条件">
        <Form form={form} layout="inline" onFinish={submit}>
          <Form.Item name="traceId" label="traceId 前缀">
            <Input allowClear style={{ width: 190 }} placeholder="支持前缀" />
          </Form.Item>
          <Form.Item name="userId" label="用户 ID">
            <InputNumber style={{ width: 150 }} min={1} controls={false} />
          </Form.Item>
          <Form.Item name="path" label="路径包含">
            <Input allowClear style={{ width: 180 }} placeholder="/api/v1/" />
          </Form.Item>
          <Form.Item name="status" label="状态码">
            <InputNumber style={{ width: 110 }} min={100} max={599} controls={false} />
          </Form.Item>
          <Form.Item name="minDurationMs" label="最小耗时(ms)">
            <InputNumber style={{ width: 130 }} min={0} controls={false} />
          </Form.Item>
          <Form.Item name="limit" label="条数">
            <InputNumber style={{ width: 90 }} min={1} max={200} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button onClick={reset}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card size="small" title="链路记录">
        <Table
          rowKey={(row) => `${row.traceId}-${row.timestamp}-${row.path}`}
          size="small"
          loading={query.isLoading}
          dataSource={query.data?.items ?? []}
          columns={columns}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          scroll={{ x: 1100 }}
          expandable={{
            expandedRowRender: (row) => (
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="错误">{row.error || '—'}</Descriptions.Item>
                <Descriptions.Item label="完整路径">{row.path}</Descriptions.Item>
                <Descriptions.Item label="时间戳">{new Date(row.timestamp).toLocaleString('zh-CN')}</Descriptions.Item>
              </Descriptions>
            ),
            rowExpandable: (row) => Boolean(row.error),
          }}
        />
      </Card>
    </Space>
  );
}
