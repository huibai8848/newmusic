import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Col, Empty, Row, Skeleton, Statistic, Table, Tag, Typography } from 'antd';
import { dashboardApi17, type RealtimeDashboard } from '@/api/data';
import { formatNumber } from './format';

/**
 * 实时看板（FR-26-004）。
 *
 * 12 张卡片的数值来自 Redis 实时键（缺失时回落 ADS T+1），所以每张卡都带
 * `source` 与 `available`：运营必须能一眼看出「这个数是实时还是昨天的」。
 */

const STATUS_COLOR: Record<string, string> = {
  good: 'green',
  warn: 'orange',
  bad: 'red',
  unknown: 'default',
};

const sourceLabel = (source: string): string =>
  source === 'REDIS' ? '实时' : source === 'ADS' ? 'T+1' : source === 'ODS' ? 'ODS' : source || '—';

export function RealtimePanel(): JSX.Element {
  const { data, isLoading, error } = useQuery<RealtimeDashboard>({
    queryKey: ['admin-data-realtime'],
    queryFn: dashboardApi17.realtime,
    refetchInterval: 30_000,
  });

  if (isLoading) return <Skeleton active paragraph={{ rows: 10 }} />;
  if (error || !data) return <Alert type="error" showIcon message="实时看板加载失败" />;

  const ingest = data.ingest ?? {};
  const received = Number(ingest.received ?? 0);
  const accepted = Number(ingest.accepted ?? 0);
  const ingestRows = ['received', 'accepted', 'duplicate', 'sampled', 'dirty', 'rate_limited', 'write_error']
    .filter((key) => ingest[key] != null)
    .map((key) => ({ key, label: INGEST_LABEL[key] ?? key, value: Number(ingest[key]) }));

  return (
    <div>
      <Alert
        type={data.redisAvailable ? 'success' : 'warning'}
        showIcon
        style={{ marginBottom: 16 }}
        message={`统计日期 ${data.date} · 更新于 ${data.updatedAt} · 延迟 ${data.delaySeconds}s · ${
          data.redisAvailable ? 'Redis 实时链路正常' : 'Redis 不可用，实时指标已回落 T+1'
        }`}
        description={data.compareNote}
      />

      <Row gutter={[16, 16]}>
        {data.cards.map((card) => (
          <Col xs={12} md={8} xl={6} key={card.metricId}>
            <Card size="small" style={{ height: '100%' }}>
              <Statistic
                title={
                  <span>
                    {card.name}
                    <Tag style={{ marginLeft: 6 }} color={sourceLabel(card.source) === '实时' ? 'blue' : 'default'}>
                      {sourceLabel(card.source)}
                    </Tag>
                  </span>
                }
                value={card.value == null ? '—' : card.value}
                suffix={card.unit}
                valueStyle={{ fontSize: 22 }}
              />
              <div className="stat-sub">
                {card.base == null ? (
                  <span>暂无环比</span>
                ) : (
                  <span style={{ color: card.direction === 'down' ? '#cf1322' : '#3f8600' }}>
                    环比 {card.deltaPct == null ? '—' : `${card.deltaPct > 0 ? '+' : ''}${card.deltaPct}%`}
                    {' '}({card.base})
                  </span>
                )}
                {card.status && card.status !== 'unknown' ? (
                  <Tag color={STATUS_COLOR[card.status] ?? 'default'} style={{ marginLeft: 8 }}>
                    {card.status}
                  </Tag>
                ) : null}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={8}>
          <Card
            size="small"
            title="今日接入质量"
            extra={<Typography.Text type="secondary">成功率 {received ? `${((accepted / received) * 100).toFixed(1)}%` : '—'}</Typography.Text>}
          >
            <Table
              rowKey="key"
              size="small"
              pagination={false}
              dataSource={ingestRows}
              columns={[
                { title: '环节', dataIndex: 'label' },
                { title: '条数', dataIndex: 'value', align: 'right' },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="热门歌曲 Top">
            {data.topSongs.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今日暂无播放" />
            ) : (
              <Table
                rowKey={(row) => row.songId}
                size="small"
                pagination={false}
                dataSource={data.topSongs.slice(0, 10)}
                columns={[
                  { title: '歌曲', dataIndex: 'name' },
                  { title: '播放', dataIndex: 'playCnt', align: 'right', width: 90 },
                ]}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="活跃房间">
            {data.topRooms.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今日暂无房间活跃" />
            ) : (
              <Table
                rowKey={(row) => row.roomId}
                size="small"
                pagination={false}
                dataSource={data.topRooms.slice(0, 10)}
                columns={[
                  { title: '房间', dataIndex: 'name' },
                  {
                    title: '进入/消息',
                    align: 'right',
                    width: 100,
                    render: (_value: unknown, row) => formatNumber(row.enterCnt ?? row.messageCnt ?? 0),
                  },
                ]}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}

const INGEST_LABEL: Record<string, string> = {
  received: '接收',
  accepted: '入库',
  duplicate: '幂等去重',
  sampled: '采样丢弃',
  dirty: '脏数据',
  rate_limited: '限流丢弃',
  write_error: '写库失败',
};
