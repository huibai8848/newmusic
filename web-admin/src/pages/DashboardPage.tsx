import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Col, Empty, Row, Skeleton, Statistic, Tag } from 'antd';
import {
  CustomerServiceOutlined,
  FileProtectOutlined,
  FlagOutlined,
  PlayCircleOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { dashboardApi } from '@/api/endpoints';
import type { Dashboard, TrendPoint } from '@/api/types';

function TrendBars({ title, points }: { title: string; points: TrendPoint[] }): JSX.Element {
  const max = Math.max(1, ...points.map((point) => point.value));
  return (
    <Card title={title} size="small" style={{ height: '100%' }}>
      {points.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
      ) : (
        <div className="trend-list">
          {points.map((point) => (
            <div className="trend-row" key={point.date}>
              <span className="trend-date">{point.date.slice(5)}</span>
              <div className="trend-track">
                <div
                  className="trend-bar"
                  style={{ width: `${Math.max(2, (point.value / max) * 100)}%` }}
                />
              </div>
              <span className="trend-value">{point.value}</span>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

export function DashboardPage(): JSX.Element {
  const { data, isLoading, error } = useQuery<Dashboard>({
    queryKey: ['admin-dashboard'],
    queryFn: dashboardApi.overview,
    refetchInterval: 60_000,
  });

  if (isLoading) {
    return <Skeleton active paragraph={{ rows: 8 }} />;
  }
  if (error || !data) {
    return <Alert type="error" showIcon message="看板数据加载失败" />;
  }

  return (
    <div>
      {(data.highPriorityReports > 0 || data.escalatedAuditTasks > 0) && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="待处理告警"
          description={
            <span>
              {data.highPriorityReports > 0 && <>高优先级举报 {data.highPriorityReports} 条；</>}
              {data.escalatedAuditTasks > 0 && <>超时未处理审核任务 {data.escalatedAuditTasks} 条。</>}
            </span>
          }
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="用户总数" value={data.totalUsers} prefix={<TeamOutlined />} />
            <div className="stat-sub">
              今日新增 {data.newUsersToday} · 今日活跃 {data.activeUsersToday}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="歌曲总数" value={data.totalSongs} prefix={<CustomerServiceOutlined />} />
            <div className="stat-sub">已上架 {data.onlineSongs}</div>
          </Card>
        </Col>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="待处理审核" value={data.pendingAuditTasks} prefix={<FileProtectOutlined />} />
            <div className="stat-sub">
              超时升级 {data.escalatedAuditTasks}
              {data.escalatedAuditTasks > 0 && <Tag color="red" style={{ marginLeft: 8 }}>需关注</Tag>}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="待处理举报" value={data.pendingReports} prefix={<FlagOutlined />} />
            <div className="stat-sub">高优先级 {data.highPriorityReports}</div>
          </Card>
        </Col>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="活跃房间" value={data.activeRooms} prefix={<ThunderboltOutlined />} />
            <div className="stat-sub">历史房间 {data.totalRooms}</div>
          </Card>
        </Col>
        <Col xs={12} md={8} xl={6}>
          <Card size="small">
            <Statistic title="今日播放" value={data.todayPlayCount} prefix={<PlayCircleOutlined />} />
            <div className="stat-sub">今日新增歌单 {data.todayNewPlaylists}</div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <TrendBars title="近 7 天新增用户" points={data.userTrend} />
        </Col>
        <Col xs={24} lg={12}>
          <TrendBars title="近 7 天播放量" points={data.playTrend} />
        </Col>
      </Row>
    </div>
  );
}
