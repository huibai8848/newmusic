import { useState } from 'react';
import { Alert, Button, Card, Col, DatePicker, Empty, Row, Select, Skeleton, Space, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { dashboardApi17, type DrillVO, type TrendVO } from '@/api/data';
import { formatNumber, barWidth } from './format';

/** 指标趋势与下钻（FR-26-004）。图表不引第三方库：CSS 条形图足够看趋势。 */

const METRICS: Array<{ value: string; label: string }> = [
  { value: 'metric_dau', label: 'DAU' },
  { value: 'metric_dau_play', label: 'DAU-Play（北极星）' },
  { value: 'metric_play_cnt', label: '播放次数' },
  { value: 'metric_valid_play_cnt', label: '有效播放次数' },
  { value: 'metric_play_duration', label: '播放时长' },
  { value: 'metric_avg_play_per_user', label: '人均播放次数' },
  { value: 'metric_new_user_cnt', label: '新增用户' },
  { value: 'metric_retention_d1', label: '次日留存率' },
  { value: 'metric_retention_d7', label: '7 日留存率' },
  { value: 'metric_completion_rate', label: '播放完成率' },
  { value: 'metric_song_coverage', label: '曲库有效播放覆盖率' },
  { value: 'metric_recommend_ctr', label: '推荐点击率' },
  { value: 'metric_search_success_rate', label: '搜索成功率' },
  { value: 'metric_room_active_user', label: '房间活跃用户' },
  { value: 'metric_interaction_user', label: '互动用户' },
  { value: 'metric_content_produce', label: '内容生产量' },
  { value: 'metric_ai_cost', label: 'AI 调用成本（元）' },
  { value: 'metric_track_success_rate', label: '埋点上报成功率' },
  { value: 'metric_funnel_activation', label: '激活漏斗转化率' },
  { value: 'metric_data_quality_score', label: '数据质量得分' },
];

const DIMENSIONS = [
  { value: '', label: '不分维度' },
  { value: 'platform', label: '平台' },
  { value: 'os', label: '操作系统' },
  { value: 'network', label: '网络' },
  { value: 'region', label: '地区' },
];

export function TrendPanel(): JSX.Element {
  const [metricId, setMetricId] = useState('metric_dau');
  const [dimension, setDimension] = useState('');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(6, 'day'), dayjs()]);
  const [applied, setApplied] = useState({ metricId: 'metric_dau', dimension: '', start: '', end: '' });
  const [drill, setDrill] = useState(false);

  const trend = useQuery<TrendVO>({
    queryKey: ['admin-data-trend', applied],
    queryFn: () =>
      dashboardApi17.trend({
        metricId: applied.metricId,
        dimension: applied.dimension || undefined,
        start: applied.start || undefined,
        end: applied.end || undefined,
      }),
  });

  const drillQuery = useQuery<DrillVO>({
    queryKey: ['admin-data-drill', applied.metricId, applied.dimension, applied.end],
    queryFn: () =>
      dashboardApi17.drill({
        metricId: applied.metricId,
        dimension: applied.dimension || 'platform',
        date: applied.end || undefined,
      }),
    enabled: drill && Boolean(applied.metricId),
  });

  const points = trend.data?.points ?? [];
  const max = Math.max(1, ...points.map((point) => Number(point.value) || 0));

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 220 }}
            options={METRICS}
            value={metricId}
            onChange={setMetricId}
            showSearch
            optionFilterProp="label"
          />
          <Select style={{ width: 140 }} options={DIMENSIONS} value={dimension} onChange={setDimension} />
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
                metricId,
                dimension,
                start: range[0].format('YYYY-MM-DD'),
                end: range[1].format('YYYY-MM-DD'),
              })
            }
          >
            查询
          </Button>
          <Button disabled={!dimension} onClick={() => setDrill(true)}>
            按维度下钻
          </Button>
        </Space>
      </Card>

      {trend.isError ? <Alert type="error" showIcon message="趋势加载失败" style={{ marginBottom: 16 }} /> : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          <Card
            size="small"
            title={trend.data ? `${trend.data.metricName} · ${trend.data.start} ~ ${trend.data.end}` : '指标趋势'}
            extra={trend.data ? <Tag color={trend.data.source === 'ADS' ? 'blue' : 'default'}>{trend.data.source}</Tag> : null}
          >
            {trend.isLoading ? (
              <Skeleton active paragraph={{ rows: 6 }} />
            ) : points.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
            ) : (
              <div className="trend-list">
                {points.map((point) => (
                  <div className="trend-row" key={`${point.date}-${point.dimensionValue ?? ''}`}>
                    <span className="trend-date">{point.date.slice(5)}</span>
                    <div className="trend-track">
                      <div className="trend-bar" style={{ width: barWidth(Number(point.value), max) }} />
                    </div>
                    <span className="trend-value">{formatNumber(Number(point.value))}</span>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card size="small" title="分维度序列">
            {(trend.data?.series ?? []).length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未选择维度" />
            ) : (
              <Table
                rowKey="dimensionValue"
                size="small"
                pagination={false}
                dataSource={trend.data?.series ?? []}
                columns={[
                  { title: '维度值', dataIndex: 'dimensionValue' },
                  { title: '天数', render: (_v, row) => row.points.length, width: 80 },
                  {
                    title: '最新值',
                    width: 100,
                    align: 'right',
                    render: (_v, row) => formatNumber(Number(row.points[row.points.length - 1]?.value ?? 0)),
                  },
                ]}
              />
            )}
          </Card>
        </Col>
      </Row>

      {drill ? (
        <Card
          size="small"
          title={`下钻 · ${applied.dimension || 'platform'} · ${drillQuery.data?.date ?? ''}`}
          style={{ marginTop: 16 }}
          extra={<Button size="small" onClick={() => setDrill(false)}>收起</Button>}
        >
          {drillQuery.isError ? (
            <Alert type="error" showIcon message="下钻失败（该指标可能没有维度数据）" />
          ) : (
            <Table
              rowKey="dimensionValue"
              size="small"
              loading={drillQuery.isLoading}
              pagination={false}
              dataSource={drillQuery.data?.values ?? []}
              columns={[
                { title: '维度值', dataIndex: 'dimensionValue' },
                { title: '数值', dataIndex: 'value', align: 'right', render: (value: number) => formatNumber(value) },
                {
                  title: '占比',
                  width: 100,
                  align: 'right',
                  render: (_v, row) =>
                    drillQuery.data?.total
                      ? `${((Number(row.value) / Number(drillQuery.data.total)) * 100).toFixed(1)}%`
                      : '—',
                },
              ]}
            />
          )}
        </Card>
      ) : null}
    </div>
  );
}
