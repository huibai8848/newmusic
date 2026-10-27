import { Tabs, Typography } from 'antd';
import {
  AreaChartOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  FundOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { RealtimePanel } from './RealtimePanel';
import { TrendPanel } from './TrendPanel';
import { FunnelPanel } from './FunnelPanel';
import { ReportPanel } from './ReportPanel';
import { QualityPanel } from './QualityPanel';
import { MetricPanel } from './MetricPanel';

/**
 * 数据平台（模块 17）。
 *
 * 一个页面装下 PRD 的五个能力域：实时看板、趋势下钻、漏斗留存、报表、质量治理。
 * 分成 Tab 而不是五个路由：这些页面共享同一个日期口径，运营排查问题时来回切换
 * 不应该每次都重新选日期（P2 再做状态外提）。
 */
export function DataPlatformPage(): JSX.Element {
  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        数据平台
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        实时指标来自 Redis 实时键，T+1 指标来自 ADS（ODS → DWD → DWS → ADS）；口径与定义以「指标体系」为准。
      </Typography.Paragraph>

      <Tabs
        destroyInactiveTabPane
        items={[
          {
            key: 'realtime',
            label: (
              <span>
                <FundOutlined /> 实时看板
              </span>
            ),
            children: <RealtimePanel />,
          },
          {
            key: 'trend',
            label: (
              <span>
                <AreaChartOutlined /> 趋势下钻
              </span>
            ),
            children: <TrendPanel />,
          },
          {
            key: 'analysis',
            label: (
              <span>
                <DatabaseOutlined /> 漏斗与留存
              </span>
            ),
            children: <FunnelPanel />,
          },
          {
            key: 'reports',
            label: (
              <span>
                <FileTextOutlined /> 报表中心
              </span>
            ),
            children: <ReportPanel />,
          },
          {
            key: 'quality',
            label: (
              <span>
                <SafetyCertificateOutlined /> 数据质量
              </span>
            ),
            children: <QualityPanel />,
          },
          {
            key: 'metrics',
            label: (
              <span>
                <FundOutlined /> 指标与埋点
              </span>
            ),
            children: <MetricPanel />,
          },
        ]}
      />
    </div>
  );
}
