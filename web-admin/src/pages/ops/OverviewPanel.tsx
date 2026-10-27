import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Col, Descriptions, Row, Space, Statistic, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { opsApi, type AlertRecord } from '@/api/ops';
import { fmtMs, fmtPercent, fmtTime, statusColor } from './format';

/**
 * 运维总览：把「活着 / 能接流量 / 依赖是否健康 / 现在是否在降级」放在第一屏。
 *
 * 5 秒轮询而不是 WebSocket：运维页通常只开一两个标签，轮询足够，也避免把
 * WS 连接数算进业务指标。401/网络错误由全局 request 层 Toast，这里只展示空态。
 */
export function OverviewPanel(): JSX.Element {
  const overview = useQuery({
    queryKey: ['ops-overview'],
    queryFn: opsApi.overview,
    refetchInterval: 5000,
  });
  const records = useQuery({
    queryKey: ['ops-overview-alerts'],
    queryFn: () => opsApi.alertRecords({ page: 1, size: 5 }),
    refetchInterval: 10000,
  });

  const data = overview.data;
  const metrics = data?.metrics;
  const health = data?.health;
  const degrade = data?.degrade;
  const disabled = degrade?.disabledFeatures ?? [];

  const columns: ColumnsType<AlertRecord> = [
    {
      title: '级别',
      dataIndex: 'severity',
      width: 70,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    { title: '告警', dataIndex: 'alertName', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    { title: '触发时间', dataIndex: 'fireTime', width: 150, render: (v: string) => fmtTime(v) },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {degrade && degrade.level > 0 && (
        <Alert
          type="warning"
          showIcon
          message={`当前处于${degrade.levelName}（level ${degrade.level}）`}
          description={
            <Space size={[4, 4]} wrap>
              <span>{degrade.banner}</span>
              {disabled.slice(0, 12).map((key) => (
                <Tag key={key}>{key}</Tag>
              ))}
              {disabled.length > 12 && <Tag>+{disabled.length - 12}</Tag>}
            </Space>
          }
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="服务状态"
              value={health?.status ?? '—'}
              valueStyle={{ color: health?.status === 'UP' ? '#389e0d' : '#cf1322' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="降级等级"
              value={degrade?.levelName ?? '—'}
              suffix={degrade ? `L${degrade.level}` : undefined}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="QPS（近 5s）" value={metrics?.qps ?? 0} precision={2} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="错误率（60s）"
              value={metrics ? fmtPercent(metrics.errorRate60s) : '—'}
              valueStyle={{ color: (metrics?.errorRate60s ?? 0) >= 5 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="P95 / P99" value={metrics ? `${fmtMs(metrics.p95Ms)} / ${fmtMs(metrics.p99Ms)}` : '—'} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="打开中的告警"
              value={data?.alerts.firing ?? 0}
              valueStyle={{ color: (data?.alerts.firing ?? 0) > 0 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="活跃 WS 连接" value={metrics?.wsConnections ?? 0} suffix={`/ ${metrics?.wsRooms ?? 0} 房`} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="熔断 / 混沌"
              value={`${metrics?.circuitOpen ?? 0} / ${metrics?.chaosRunning ? '运行中' : '无'}`}
              valueStyle={{ color: (metrics?.circuitOpen ?? 0) > 0 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card size="small" title="依赖健康" loading={overview.isLoading}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="数据库">
                <Tag color={statusColor(health?.checks.db)}>{health?.checks.db ?? '—'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Redis">
                <Tag color={statusColor(health?.checks.redis)}>{health?.checks.redis ?? '—'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="运维自检">
                <Tag color={statusColor(health?.checks.ops)}>{health?.checks.ops ?? '—'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="限流规则">
                {health?.rateLimitRules ?? 0} 条
              </Descriptions.Item>
              <Descriptions.Item label="已关闭功能">
                {health?.disabledFeatures ?? 0} 项
              </Descriptions.Item>
              <Descriptions.Item label="运行时长">
                {Math.floor((health?.uptimeSeconds ?? 0) / 3600)} 小时
              </Descriptions.Item>
            </Descriptions>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8 }}>
              Redis 不可用时只降级为 DEGRADED（限流走本地滑动窗口），数据库不可用才是 DOWN。
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card size="small" title="熔断器" loading={overview.isLoading}>
            <Table
              rowKey="name"
              size="small"
              pagination={false}
              dataSource={data?.circuits ?? []}
              columns={[
                { title: '名称', dataIndex: 'name' },
                {
                  title: '状态',
                  dataIndex: 'state',
                  width: 100,
                  render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
                },
                {
                  title: '失败率',
                  dataIndex: 'failureRate',
                  width: 100,
                  render: (value: number) => fmtPercent(value * 100),
                },
                { title: '样本', dataIndex: 'calls', width: 70 },
                { title: '打开次数', dataIndex: 'openTotal', width: 90 },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="最近告警" extra={<span style={{ color: '#8c8c8c' }}>近 5 条</span>}>
        <Table
          rowKey="id"
          size="small"
          loading={records.isLoading}
          dataSource={records.data?.list ?? []}
          columns={columns}
          pagination={false}
        />
      </Card>
    </Space>
  );
}
