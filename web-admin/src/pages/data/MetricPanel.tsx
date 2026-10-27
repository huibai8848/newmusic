import { useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Input,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PageResult } from '@/api/types';
import {
  eventApi,
  metricApi,
  type EventDef,
  type MetricDef,
} from '@/api/data';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';

/**
 * 指标与埋点文档（FR-26-003 / FR-26-001）。
 *
 * 指标口径变更不能直接改：必须走审批。这里只做「看变更 + 批/驳」，
 * 新建与改口径仍走接口（前端表单会随 P2 一起补）。
 */
export function MetricPanel(): JSX.Element {
  return (
    <Tabs
      items={[
        { key: 'metrics', label: '指标体系', children: <MetricsTab /> },
        { key: 'events', label: '埋点文档', children: <EventsTab /> },
      ]}
    />
  );
}

function MetricsTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState<number | undefined>(1);
  const [selected, setSelected] = useState<MetricDef | null>(null);
  const [comment, setComment] = useState('');

  const list = useQuery<PageResult<MetricDef>>({
    queryKey: ['admin-metrics', category, status],
    queryFn: () => metricApi.list({ pageNum: 1, pageSize: 100, category: category || undefined, status }),
  });

  const history = useQuery<Array<Record<string, unknown>>>({
    queryKey: ['admin-metric-history', selected?.metricId],
    queryFn: () => metricApi.history(selected!.metricId),
    enabled: Boolean(selected),
  });

  const pending = (history.data ?? []).find((row) => Number(row.status) === 0);

  const approve = useMutation({
    mutationFn: (ok: boolean) =>
      metricApi.approve(selected!.metricId, { approve: ok, comment: comment || undefined }),
    onSuccess: (_result, ok) => {
      message.success(ok ? '口径变更已通过' : '口径变更已驳回');
      setComment('');
      void queryClient.invalidateQueries({ queryKey: ['admin-metric-history'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-metrics'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const categories = Array.from(new Set((list.data?.list ?? []).map((row) => row.category).filter(Boolean)));

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 180 }}
            value={category}
            onChange={setCategory}
            placeholder="全部类目"
            allowClear
            options={[{ value: '', label: '全部类目' }, ...categories.map((item) => ({ value: item, label: item }))]}
          />
          <Select
            style={{ width: 140 }}
            value={status}
            onChange={setStatus}
            options={[
              { value: 1, label: '生效中' },
              { value: 0, label: '草稿' },
              { value: 2, label: '已废弃' },
            ]}
          />
          <Typography.Text type="secondary">指标口径变更需审批后生效；版本号只增不减。</Typography.Text>
        </Space>
      </Card>

      <Card size="small">
        <Table
          rowKey="id"
          size="small"
          loading={list.isLoading}
          pagination={{ pageSize: 20, size: 'small' }}
          dataSource={list.data?.list ?? []}
          onRow={(row) => ({ onClick: () => setSelected(row), style: { cursor: 'pointer' } })}
          columns={[
            { title: '指标 ID', dataIndex: 'metricId', width: 190 },
            { title: '名称', dataIndex: 'metricName', width: 160 },
            { title: '类目', dataIndex: 'category', width: 110 },
            { title: '口径', dataIndex: 'definition', ellipsis: true },
            { title: '版本', dataIndex: 'version', width: 80 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (value: number) =>
                value === 1 ? <Tag color="green">生效</Tag> : value === 0 ? <Tag>草稿</Tag> : <Tag color="red">废弃</Tag>,
            },
          ]}
        />
      </Card>

      <Drawer
        width={640}
        open={Boolean(selected)}
        onClose={() => {
          setSelected(null);
          setComment('');
        }}
        title={selected ? `${selected.metricName}（${selected.metricId}）` : '指标详情'}
      >
        {selected ? (
          <>
            <Descriptions size="small" column={1} bordered>
              <Descriptions.Item label="口径">{selected.definition}</Descriptions.Item>
              <Descriptions.Item label="计算方式">{selected.calculation}</Descriptions.Item>
              <Descriptions.Item label="数据源">{selected.dataSource}</Descriptions.Item>
              <Descriptions.Item label="可用维度">{selected.dimensions}</Descriptions.Item>
              <Descriptions.Item label="更新频率">{selected.updateFreq}</Descriptions.Item>
              <Descriptions.Item label="负责人">{selected.owner}</Descriptions.Item>
              <Descriptions.Item label="版本 / 状态">
                v{selected.version} · {selected.status === 1 ? '生效' : selected.status === 0 ? '草稿' : '废弃'}
              </Descriptions.Item>
            </Descriptions>

            <Typography.Title level={5} style={{ marginTop: 20 }}>
              变更历史
            </Typography.Title>
            <Table
              rowKey={(row) => String(row.id ?? row.createTime ?? Math.random())}
              size="small"
              pagination={false}
              dataSource={history.data ?? []}
              columns={[
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 90,
                  render: (value: number) =>
                    value === 0 ? <Tag color="orange">待审批</Tag> : value === 1 ? <Tag color="green">已通过</Tag> : <Tag color="red">已驳回</Tag>,
                },
                { title: '新口径', dataIndex: 'newDefinition', ellipsis: true },
                { title: '申请人', dataIndex: 'applicant', width: 100 },
                { title: '时间', dataIndex: 'createTime', width: 160 },
              ]}
            />

            {pending && hasPerm('admin:data:approve') ? (
              <Card size="small" type="inner" title="审批" style={{ marginTop: 16 }}>
                <Input.TextArea
                  rows={2}
                  value={comment}
                  onChange={(event) => setComment(event.target.value)}
                  placeholder="审批意见（可选）"
                />
                <Space style={{ marginTop: 12 }}>
                  <Button type="primary" loading={approve.isPending} onClick={() => approve.mutate(true)}>
                    通过
                  </Button>
                  <Button danger loading={approve.isPending} onClick={() => approve.mutate(false)}>
                    驳回
                  </Button>
                </Space>
              </Card>
            ) : null}
          </>
        ) : null}
      </Drawer>
    </>
  );
}

function EventsTab(): JSX.Element {
  const [keyword, setKeyword] = useState('');
  const [domain, setDomain] = useState('');

  const list = useQuery<PageResult<EventDef>>({
    queryKey: ['admin-events', keyword, domain],
    queryFn: () =>
      eventApi.list({
        pageNum: 1,
        pageSize: 100,
        keyword: keyword || undefined,
        domain: domain || undefined,
        status: 1,
      }),
  });

  const domains = Array.from(new Set((list.data?.list ?? []).map((row) => row.domain).filter(Boolean)));

  return (
    <Card
      size="small"
      title="埋点文档"
      extra={
        <Space>
          <Select
            style={{ width: 160 }}
            value={domain}
            onChange={setDomain}
            allowClear
            placeholder="全部域"
            options={domains.map((item) => ({ value: item, label: item }))}
          />
          <Input.Search
            allowClear
            placeholder="按事件名搜索"
            style={{ width: 220 }}
            onSearch={setKeyword}
          />
        </Space>
      }
    >
      <Table
        rowKey="id"
        size="small"
        loading={list.isLoading}
        pagination={{ pageSize: 20, size: 'small' }}
        dataSource={list.data?.list ?? []}
        columns={[
          { title: '事件名', dataIndex: 'eventName', width: 220 },
          { title: '中文名', dataIndex: 'eventCnName', width: 150 },
          { title: '域', dataIndex: 'domain', width: 100 },
          { title: '触发时机', dataIndex: 'triggerDesc', ellipsis: true },
          {
            title: '优先级',
            dataIndex: 'priority',
            width: 90,
            render: (value: number) => (value === 1 ? <Tag color="red">核心</Tag> : value === 2 ? <Tag color="blue">重要</Tag> : <Tag>辅助</Tag>),
          },
          {
            title: '采样',
            dataIndex: 'isSampled',
            width: 110,
            render: (value: number, row) => (value === 1 ? `${(Number(row.sampleRate) * 100).toFixed(0)}%` : '全量'),
          },
          { title: '版本', dataIndex: 'version', width: 70 },
          { title: '负责人', dataIndex: 'owner', width: 100 },
        ]}
      />
    </Card>
  );
}
