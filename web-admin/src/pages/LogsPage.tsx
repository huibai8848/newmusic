import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { logApi } from '@/api/endpoints';
import { useAuth } from '@/auth/AuthContext';
import type { LoginLog, OperationLog, PageResult } from '@/api/types';

const OP_RESULT: Record<number, { text: string; color: string }> = {
  1: { text: '成功', color: 'green' },
  0: { text: '失败', color: 'red' },
};

const LOGIN_RESULT: Record<number, { text: string; color: string }> = {
  1: { text: '成功', color: 'green' },
  0: { text: '失败', color: 'red' },
  2: { text: '锁定', color: 'orange' },
};

const LOGIN_TYPE: Record<number, string> = { 1: 'C 端', 2: '后台' };

interface OperationFilters {
  keyword?: string;
  opType?: string;
  targetType?: string;
  result?: number;
  startDate?: string;
  endDate?: string;
}

interface LoginFilters {
  keyword?: string;
  ip?: string;
  loginType?: number;
  result?: number;
  suspicious?: boolean;
  startDate?: string;
  endDate?: string;
}

function rangeToDates(range: [Dayjs | null, Dayjs | null] | null): {
  startDate?: string;
  endDate?: string;
} {
  if (!range) return {};
  return {
    startDate: range[0]?.format('YYYY-MM-DD'),
    endDate: range[1]?.format('YYYY-MM-DD'),
  };
}

function OperationLogs(): JSX.Element {
  const { hasPerm } = useAuth();
  const [filters, setFilters] = useState<OperationFilters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [active, setActive] = useState<OperationLog | null>(null);

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<OperationLog>>({
    queryKey: ['admin-op-logs', query],
    queryFn: () => logApi.operations(query),
  });

  const columns: ColumnsType<OperationLog> = [
    {
      title: '操作人',
      key: 'operator',
      width: 160,
      render: (_, record) => (
        <div>
          <div>{record.operatorName || '—'}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {record.operatorId ? String(record.operatorId) : '—'}
          </Typography.Text>
        </div>
      ),
    },
    { title: '类型', dataIndex: 'opType', width: 170 },
    {
      title: '目标',
      key: 'target',
      width: 190,
      render: (_, record) =>
        record.targetType ? `${record.targetType}${record.targetId ? `#${record.targetId}` : ''}` : '—',
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
    },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (value: number) => {
        const result = OP_RESULT[value] ?? { text: String(value), color: 'default' };
        return <Tag color={result.color}>{result.text}</Tag>;
      },
    },
    { title: 'IP', dataIndex: 'ip', width: 140 },
    {
      title: '时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 90,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => setActive(record)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="操作人 / 描述"
          style={{ width: 200 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="操作类型（如 SONG_UPDATE）"
          style={{ width: 220 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, opType: value || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="目标类型"
          style={{ width: 160 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, targetType: value || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="结果"
          style={{ width: 110 }}
          options={Object.entries(OP_RESULT).map(([value, result]) => ({
            value: Number(value),
            label: result.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, result: value }));
          }}
        />
        <DatePicker.RangePicker
          onChange={(range) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, ...rangeToDates(range as [Dayjs | null, Dayjs | null] | null) }));
          }}
        />
        {hasPerm('admin:log:export') && (
          <Button
            icon={<DownloadOutlined />}
            onClick={() => window.open(logApi.exportUrl(query), '_blank', 'noopener')}
          >
            导出 CSV
          </Button>
        )}
      </Space>

      <Table<OperationLog>
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
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Drawer
        title={`操作日志 #${active ? String(active.id) : ''}`}
        width={620}
        open={active !== null}
        onClose={() => setActive(null)}
      >
        {active && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="操作人">
              {active.operatorName || '—'}（{active.operatorId ? String(active.operatorId) : '—'}）
            </Descriptions.Item>
            <Descriptions.Item label="操作类型">{active.opType}</Descriptions.Item>
            <Descriptions.Item label="目标">
              {active.targetType || '—'}
              {active.targetId ? `#${active.targetId}` : ''}
            </Descriptions.Item>
            <Descriptions.Item label="描述">{active.description || '—'}</Descriptions.Item>
            <Descriptions.Item label="结果">
              {OP_RESULT[active.result ?? -1]?.text ?? active.result}
            </Descriptions.Item>
            <Descriptions.Item label="错误信息">{active.errorMsg || '—'}</Descriptions.Item>
            <Descriptions.Item label="IP">{active.ip || '—'}</Descriptions.Item>
            <Descriptions.Item label="Trace ID">
              <Typography.Text copyable>{active.traceId || '—'}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="时间">
              {active.createTime ? dayjs(active.createTime).format('YYYY-MM-DD HH:mm:ss') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="变更前">
              <pre className="content-snapshot">{active.beforeSnapshot || '—'}</pre>
            </Descriptions.Item>
            <Descriptions.Item label="变更后">
              <pre className="content-snapshot">{active.afterSnapshot || '—'}</pre>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </>
  );
}

function LoginLogs(): JSX.Element {
  const [filters, setFilters] = useState<LoginFilters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<LoginLog>>({
    queryKey: ['admin-login-logs', query],
    queryFn: () => logApi.logins(query),
  });

  const columns: ColumnsType<LoginLog> = [
    { title: '账号', dataIndex: 'username', width: 160 },
    {
      title: '用户 ID',
      dataIndex: 'userId',
      width: 160,
      render: (value?: string) => (value ? String(value) : '—'),
    },
    {
      title: '端',
      dataIndex: 'loginType',
      width: 90,
      render: (value: number) => LOGIN_TYPE[value] ?? value,
    },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (value: number) => {
        const result = LOGIN_RESULT[value] ?? { text: String(value), color: 'default' };
        return <Tag color={result.color}>{result.text}</Tag>;
      },
    },
    {
      title: '失败原因',
      dataIndex: 'failReason',
      width: 180,
      render: (value?: string) => value || '—',
    },
    { title: 'IP', dataIndex: 'ip', width: 140 },
    { title: '设备', dataIndex: 'device', width: 90, render: (value?: string) => value || '—' },
    {
      title: '可疑',
      dataIndex: 'suspicious',
      width: 80,
      render: (value: number) => (value === 1 ? <Tag color="red">可疑</Tag> : '—'),
    },
    {
      title: '时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'),
    },
  ];

  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="账号"
          style={{ width: 180 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="IP"
          style={{ width: 160 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, ip: value || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="端"
          style={{ width: 110 }}
          options={Object.entries(LOGIN_TYPE).map(([value, label]) => ({
            value: Number(value),
            label,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, loginType: value }));
          }}
        />
        <Select
          allowClear
          placeholder="结果"
          style={{ width: 110 }}
          options={Object.entries(LOGIN_RESULT).map(([value, result]) => ({
            value: Number(value),
            label: result.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, result: value }));
          }}
        />
        <Space size={4}>
          <Switch
            size="small"
            onChange={(checked) => {
              setPageNum(1);
              setFilters((current) => ({ ...current, suspicious: checked || undefined }));
            }}
          />
          <Typography.Text>只看可疑</Typography.Text>
        </Space>
        <DatePicker.RangePicker
          onChange={(range) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, ...rangeToDates(range as [Dayjs | null, Dayjs | null] | null) }));
          }}
        />
      </Space>

      <Table<LoginLog>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1250 }}
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
    </>
  );
}

export function LogsPage(): JSX.Element {
  return (
    <Card>
      <Tabs
        items={[
          { key: 'operations', label: '操作日志', children: <OperationLogs /> },
          { key: 'logins', label: '登录日志', children: <LoginLogs /> },
        ]}
      />
    </Card>
  );
}
