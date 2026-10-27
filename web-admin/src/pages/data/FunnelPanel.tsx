import { useState } from 'react';
import { Alert, Button, Card, Col, DatePicker, Empty, Row, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { analysisApi, type FunnelPreset, type FunnelVO, type RetentionVO } from '@/api/data';
import { formatNumber, formatRate } from './format';

/**
 * 漏斗与留存（FR-26-005 / FR-26-006）。
 *
 * 漏斗步骤用横向条 + 流失标注：运营看漏斗只关心「哪一步掉得最多」，
 * 绝对人数反而次要，所以最大流失步骤会带高亮标记。
 */

const RETENTION_DAYS = ['1', '2', '3', '7', '14', '30'];

export function FunnelPanel(): JSX.Element {
  return (
    <Tabs
      items={[
        { key: 'funnel', label: '转化漏斗', children: <FunnelTab /> },
        { key: 'retention', label: '留存分析', children: <RetentionTab /> },
      ]}
    />
  );
}

function FunnelTab(): JSX.Element {
  const presets = useQuery<FunnelPreset[]>({
    queryKey: ['admin-funnel-presets'],
    queryFn: analysisApi.presets,
  });
  const [presetKey, setPresetKey] = useState<string>('activation');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(6, 'day'), dayjs()]);
  const [dimension, setDimension] = useState('');
  const [applied, setApplied] = useState<{ presetKey: string; start: string; end: string; dimension: string } | null>(null);

  const preset = (presets.data ?? []).find((item) => item.key === presetKey);

  const funnel = useQuery<FunnelVO>({
    queryKey: ['admin-funnel', applied],
    queryFn: () =>
      analysisApi.funnel({
        name: preset?.name ?? '自定义漏斗',
        start: applied!.start,
        end: applied!.end,
        windowHours: preset?.windowHours ?? 168,
        dimension: applied!.dimension || undefined,
        steps: preset?.steps ?? [],
      }),
    enabled: Boolean(applied && preset),
  });

  const steps = funnel.data?.steps ?? [];
  const maxUsers = Math.max(1, ...steps.map((step) => step.users));

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 240 }}
            value={presetKey}
            onChange={setPresetKey}
            options={(presets.data ?? []).map((item) => ({ value: item.key, label: item.name }))}
          />
          <DatePicker.RangePicker
            value={range}
            onChange={(value) => {
              if (value?.[0] && value[1]) setRange([value[0], value[1]]);
            }}
          />
          <Select
            style={{ width: 140 }}
            value={dimension}
            onChange={setDimension}
            options={[
              { value: '', label: '不分维度' },
              { value: 'platform', label: '平台' },
              { value: 'os', label: '操作系统' },
              { value: 'region', label: '地区' },
            ]}
          />
          <Button
            type="primary"
            disabled={!preset}
            onClick={() =>
              setApplied({
                presetKey,
                start: range[0].format('YYYY-MM-DD'),
                end: range[1].format('YYYY-MM-DD'),
                dimension,
              })
            }
          >
            计算
          </Button>
          {preset ? <Typography.Text type="secondary">{preset.description}</Typography.Text> : null}
        </Space>
      </Card>

      {funnel.isError ? <Alert type="error" showIcon message="漏斗计算失败" style={{ marginBottom: 16 }} /> : null}
      {!applied ? (
        <Empty description="选择预置漏斗后点击「计算」" />
      ) : funnel.isLoading ? (
        <Card size="small" loading />
      ) : funnel.data ? (
        <>
          <Row gutter={[16, 16]}>
            <Col span={8}>
              <Card size="small">
                <Typography.Text type="secondary">进入人数</Typography.Text>
                <div style={{ fontSize: 28, fontWeight: 600 }}>{formatNumber(funnel.data.totalUsers)}</div>
                <Typography.Text type="secondary">
                  {funnel.data.start} ~ {funnel.data.end} · 窗口 {funnel.data.windowHours}h · 主体{' '}
                  {funnel.data.subject === 'user' ? '用户' : '设备'}
                </Typography.Text>
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Typography.Text type="secondary">整体转化率</Typography.Text>
                <div style={{ fontSize: 28, fontWeight: 600 }}>{formatRate(funnel.data.overallConversion)}</div>
                {funnel.data.truncated ? <Tag color="orange">结果已截断</Tag> : null}
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Typography.Text type="secondary">扫描行数</Typography.Text>
                <div style={{ fontSize: 28, fontWeight: 600 }}>{formatNumber(funnel.data.scannedRows)}</div>
              </Card>
            </Col>
          </Row>

          <Card size="small" title="步骤转化" style={{ marginTop: 16 }}>
            {steps.map((step) => (
              <div key={step.index} style={{ marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span>
                    {step.index}. {step.label}
                    {step.highlight ? <Tag color="red" style={{ marginLeft: 8 }}>最大流失</Tag> : null}
                  </span>
                  <span>
                    {formatNumber(step.users)} 人 · 步骤转化 {formatRate(step.stepRate)} · 整体{' '}
                    {formatRate(step.overallRate)}
                    {step.index > 1 ? (
                      <Typography.Text type="danger" style={{ marginLeft: 8 }}>
                        -{formatNumber(step.dropOffUsers)}
                      </Typography.Text>
                    ) : null}
                  </span>
                </div>
                <div style={{ height: 12, background: '#f0f0f0', borderRadius: 6 }}>
                  <div
                    style={{
                      height: 12,
                      width: `${Math.max(2, (step.users / maxUsers) * 100)}%`,
                      borderRadius: 6,
                      background: step.highlight ? '#ff7875' : '#1677ff',
                    }}
                  />
                </div>
              </div>
            ))}
          </Card>

          {(funnel.data.groups ?? []).length > 0 ? (
            <Card size="small" title="分维度" style={{ marginTop: 16 }}>
              <Table
                rowKey="dimensionValue"
                size="small"
                pagination={false}
                dataSource={funnel.data.groups}
                columns={[
                  { title: '维度值', dataIndex: 'dimensionValue' },
                  { title: '进入人数', dataIndex: 'totalUsers', align: 'right' },
                  {
                    title: '整体转化',
                    align: 'right',
                    render: (_v, row) => `${Number(row.overallConversion ?? 0).toFixed(2)}%`,
                  },
                ]}
              />
            </Card>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function RetentionTab(): JSX.Element {
  const [type, setType] = useState('new');
  const [group, setGroup] = useState('');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(13, 'day'), dayjs().subtract(1, 'day')]);
  const [applied, setApplied] = useState<{ type: string; group: string; start: string; end: string } | null>(null);

  const retention = useQuery<RetentionVO>({
    queryKey: ['admin-retention', applied],
    queryFn: () =>
      analysisApi.retention({
        type: applied!.type,
        group: applied!.group || undefined,
        start: applied!.start,
        end: applied!.end,
      }),
    enabled: Boolean(applied),
  });

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 160 }}
            value={type}
            onChange={setType}
            options={[
              { value: 'new', label: '新增留存' },
              { value: 'active', label: '活跃留存' },
              { value: 'play', label: '播放留存' },
            ]}
          />
          <Select
            style={{ width: 160 }}
            value={group}
            onChange={setGroup}
            options={[
              { value: '', label: '全部分群' },
              { value: 'social', label: '有社交行为' },
              { value: 'room', label: '使用过音乐房' },
              { value: 'ai', label: '使用过 AI' },
              { value: 'heavy', label: '首日播放 ≥ 10' },
              { value: 'light', label: '首日播放 ≤ 2' },
            ]}
          />
          <DatePicker.RangePicker
            value={range}
            onChange={(value) => {
              if (value?.[0] && value[1]) setRange([value[0], value[1]]);
            }}
          />
          <Button
            type="primary"
            onClick={() =>
              setApplied({
                type,
                group,
                start: range[0].format('YYYY-MM-DD'),
                end: range[1].format('YYYY-MM-DD'),
              })
            }
          >
            查询
          </Button>
        </Space>
      </Card>

      {retention.isError ? <Alert type="error" showIcon message="留存计算失败" style={{ marginBottom: 16 }} /> : null}
      {!applied ? (
        <Empty description="选择口径后点击「查询」" />
      ) : (
        <>
          {(retention.data?.alerts ?? []).length > 0 ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="留存告警"
              description={retention.data?.alerts.map((item) => <div key={item.metric}>{item.message}</div>)}
            />
          ) : null}
          <Card
            size="small"
            title={`${retention.data?.typeLabel ?? ''} · ${retention.data?.groupLabel ?? ''}`}
            extra={
              retention.data ? (
                <Typography.Text type="secondary">
                  {retention.data.start} ~ {retention.data.end} · 扫描 {formatNumber(retention.data.scannedRows)} 行
                </Typography.Text>
              ) : null
            }
          >
            <Table
              rowKey="date"
              size="small"
              loading={retention.isLoading}
              pagination={{ pageSize: 15, size: 'small' }}
              dataSource={retention.data?.rows ?? []}
              columns={[
                { title: '同期群', dataIndex: 'date', fixed: 'left', width: 110 },
                { title: '人数', dataIndex: 'cohortSize', align: 'right', width: 80 },
                ...RETENTION_DAYS.map((day) => ({
                  title: `D${day}`,
                  key: day,
                  align: 'right' as const,
                  width: 80,
                  render: (_v: unknown, row: RetentionVO['rows'][number]) =>
                    formatRate(row.values[day] ?? null),
                })),
              ]}
            />
            {retention.data ? (
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                {retention.data.note}
                {retention.data.truncated ? '（样本已截断）' : ''}
              </Typography.Text>
            ) : null}
          </Card>
          {(retention.data?.groups ?? []).length > 0 ? (
            <Card size="small" title="分群对比" style={{ marginTop: 16 }}>
              <Table
                rowKey={(row) => String(row.key ?? row.label ?? Math.random())}
                size="small"
                pagination={false}
                dataSource={retention.data?.groups ?? []}
                columns={[
                  { title: '分群', render: (_v, row) => String(row.label ?? row.key ?? '—') },
                  { title: '人数', align: 'right', render: (_v, row) => formatNumber(Number(row.cohortUsers ?? 0)) },
                  { title: 'D1', align: 'right', render: (_v, row) => formatRate(row.d1 as number | null) },
                  { title: 'D7', align: 'right', render: (_v, row) => formatRate(row.d7 as number | null) },
                  { title: '说明', render: (_v, row) => String(row.note ?? '—') },
                ]}
              />
            </Card>
          ) : null}
        </>
      )}
    </div>
  );
}
