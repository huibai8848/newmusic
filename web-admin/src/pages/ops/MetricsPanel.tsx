import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { opsApi, type MetricAggregate, type RouteStat, type ThreadPoolStat } from '@/api/ops';
import { fmtBytes, fmtMs, fmtNumber, fmtPercent, fmtRatio } from './format';

/**
 * 指标面板：应用内 RED 指标 + JVM/连接池/磁盘 + 路由与线程池水位。
 *
 * 不依赖 Prometheus：本机没有采集端，所以指标在进程内聚合，Prometheus 文本作为
 * 「以后接采集端」的出口。趋势来自每分钟落库的 t_ops_metric_snapshot。
 */
export function MetricsPanel(): JSX.Element {
  const [prometheusOpen, setPrometheusOpen] = useState(false);
  const metrics = useQuery({ queryKey: ['ops-metrics'], queryFn: opsApi.metrics, refetchInterval: 5000 });
  const routes = useQuery({ queryKey: ['ops-routes'], queryFn: () => opsApi.routes(50), refetchInterval: 10000 });
  const pools = useQuery({ queryKey: ['ops-threadpools'], queryFn: opsApi.threadPools, refetchInterval: 10000 });
  const history = useQuery({
    queryKey: ['ops-metric-history'],
    queryFn: () => opsApi.metricHistory({ minutes: 60 }),
    refetchInterval: 30000,
  });
  const prometheus = useQuery({
    queryKey: ['ops-prometheus'],
    queryFn: opsApi.prometheus,
    enabled: prometheusOpen,
  });

  const data = metrics.data;
  const dbPool = data?.dbPool ?? {};
  const disk = data?.disk ?? {};

  const routeColumns: ColumnsType<RouteStat> = [
    { title: '路由', dataIndex: 'route', ellipsis: true },
    { title: '请求数', dataIndex: 'requests', width: 90, render: (v: number) => fmtNumber(v) },
    {
      title: '错误',
      dataIndex: 'errors',
      width: 90,
      render: (v: number) => <span style={{ color: v > 0 ? '#cf1322' : undefined }}>{fmtNumber(v)}</span>,
    },
    { title: '错误率', dataIndex: 'errorRate', width: 90, render: (v: number) => fmtRatio(v) },
    { title: 'P95', dataIndex: 'p95Ms', width: 100, render: (v: number) => fmtMs(v) },
    { title: '平均', dataIndex: 'avgMs', width: 100, render: (v: number) => fmtMs(v) },
    { title: '最后状态', dataIndex: 'lastStatus', width: 100 },
  ];

  const poolColumns: ColumnsType<ThreadPoolStat> = [
    { title: '执行器', dataIndex: 'name' },
    { title: '活跃 / 池', key: 'active', width: 110, render: (_, row) => `${row.active} / ${row.poolSize}` },
    { title: '核心 / 最大', key: 'size', width: 110, render: (_, row) => `${row.core} / ${row.max}` },
    { title: '队列', key: 'queue', width: 110, render: (_, row) => `${row.queueSize} / ${row.queueSize + row.queueRemaining}` },
    { title: '已完成', dataIndex: 'completed', width: 100, render: (v: number) => fmtNumber(v) },
    {
      title: '水位',
      dataIndex: 'usageRatio',
      width: 140,
      render: (v: number) => <Progress percent={Math.round(v * 100)} size="small" status={v > 0.9 ? 'exception' : 'normal'} />,
    },
  ];

  const aggregateColumns: ColumnsType<MetricAggregate> = [
    { title: '指标', dataIndex: 'metricName' },
    { title: '平均值', dataIndex: 'avgValue', width: 120, render: (v: number) => v?.toFixed(3) },
    { title: '最大值', dataIndex: 'maxValue', width: 120, render: (v: number) => v?.toFixed(3) },
    { title: '样本数', dataIndex: 'samples', width: 100 },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="QPS（近 5s）" value={data?.qps ?? 0} precision={2} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="错误率（60s）"
              value={data ? fmtPercent(data.errorRate60s) : '—'}
              valueStyle={{ color: (data?.errorRate60s ?? 0) >= 5 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="P95" value={data ? fmtMs(data.p95Ms) : '—'} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="P99" value={data ? fmtMs(data.p99Ms) : '—'} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="限流拒绝（60s）" value={data?.rateLimited60s ?? 0} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="慢 SQL（60s）" value={data?.slowSql60s ?? 0} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={8}>
          <Card size="small" title="JVM" loading={metrics.isLoading}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Progress
                percent={Math.round((data?.jvm.heapUsageRatio ?? 0) * 100)}
                status={(data?.jvm.heapUsageRatio ?? 0) > 0.9 ? 'exception' : 'normal'}
                format={(percent) => `堆 ${percent}%`}
              />
              <Descriptions column={1} size="small">
                <Descriptions.Item label="堆内存">
                  {fmtBytes(data?.jvm.heapUsed)} / {fmtBytes(data?.jvm.heapMax)}
                </Descriptions.Item>
                <Descriptions.Item label="线程">
                  {data?.jvm.threads ?? 0}（峰值 {data?.jvm.peakThreads ?? 0}）
                </Descriptions.Item>
                <Descriptions.Item label="GC">
                  {data?.jvm.gcCount ?? 0} 次 / {fmtMs(data?.jvm.gcTimeMs ?? 0)}
                </Descriptions.Item>
              </Descriptions>
            </Space>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="数据库连接池（Druid）" loading={metrics.isLoading}>
            <Descriptions column={1} size="small">
              {Object.entries(dbPool).map(([key, value]) => (
                <Descriptions.Item key={key} label={key}>
                  {String(value ?? '—')}
                </Descriptions.Item>
              ))}
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="磁盘与运行态" loading={metrics.isLoading}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label="可用比例">
                <Tag color={(Number(disk.availableRatio ?? 0) ?? 0) > 0.15 ? 'green' : 'red'}>
                  {fmtRatio(Number(disk.availableRatio ?? 0))}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="总容量">{fmtBytes(Number(disk.totalBytes ?? 0))}</Descriptions.Item>
              <Descriptions.Item label="可用">{fmtBytes(Number(disk.usableBytes ?? 0))}</Descriptions.Item>
              <Descriptions.Item label="Redis">
                <Tag color={data?.redisAvailable ? 'green' : 'orange'}>
                  {data?.redisAvailable ? '可用' : '不可用（本地兜底）'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="降级等级">{data?.degradeLevelName ?? '—'}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="路由指标（Top 50）"
        extra={
          <Button size="small" onClick={() => setPrometheusOpen(true)}>
            查看 Prometheus 文本
          </Button>
        }
      >
        <Table
          rowKey="route"
          size="small"
          loading={routes.isLoading}
          dataSource={routes.data ?? []}
          columns={routeColumns}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          scroll={{ x: 900 }}
        />
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card size="small" title="线程池水位" loading={pools.isLoading}>
            <Table
              rowKey="name"
              size="small"
              dataSource={pools.data ?? []}
              columns={poolColumns}
              pagination={false}
              scroll={{ x: 760 }}
            />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card size="small" title="近 60 分钟指标快照" loading={history.isLoading}>
            <Table
              rowKey="metricName"
              size="small"
              dataSource={history.data?.aggregate ?? []}
              columns={aggregateColumns}
              pagination={false}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
              每分钟落库一次，保留 7 天；趋势数据用于事后复盘，不参与实时告警。
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>

      <Drawer
        open={prometheusOpen}
        title="Prometheus 文本指标"
        width={860}
        onClose={() => setPrometheusOpen(false)}
      >
        <Typography.Paragraph type="secondary">
          也可从管理端口抓取：<Typography.Text code>http://127.0.0.1:8082/actuator/prometheus</Typography.Text>
          （仅监听回环地址）；跨机抓取走 <Typography.Text code>/api/v1/internal/ops/metrics</Typography.Text>（IP 白名单）。
        </Typography.Paragraph>
        <pre style={{ fontSize: 12, lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>
          {prometheus.isLoading ? '加载中…' : prometheus.data || '暂无数据'}
        </pre>
      </Drawer>
    </Space>
  );
}
