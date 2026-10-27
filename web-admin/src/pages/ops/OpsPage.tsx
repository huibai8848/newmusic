import { useMemo } from 'react';
import { Empty, Tabs, Typography } from 'antd';
import {
  AccountBookOutlined,
  AlertOutlined,
  CloudUploadOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  FallOutlined,
  LineChartOutlined,
  NodeIndexOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/auth/AuthContext';
import { OverviewPanel } from './OverviewPanel';
import { RateLimitPanel } from './RateLimitPanel';
import { DegradePanel } from './DegradePanel';
import { AlertPanel } from './AlertPanel';
import { MetricsPanel } from './MetricsPanel';
import { TracePanel } from './TracePanel';
import { ReleasePanel } from './ReleasePanel';
import { CostPanel } from './CostPanel';
import { ChaosPanel } from './ChaosPanel';
import { RunbookPanel } from './RunbookPanel';
import { ConfigPanel } from './ConfigPanel';

/**
 * 高可用与运维（模块 19）。
 *
 * 一个页面十个 Tab，覆盖「看得见（总览/指标/链路）→ 拦得住（限流/降级/告警）
 * → 改得动（发布/配置）→ 算得清（成本）→ 练得熟（演练/预案）」。
 * 按权限过滤 Tab：只有成本权限的账号看不到限流面板，避免误点写操作。
 */
export function OpsPage(): JSX.Element {
  const { hasPerm } = useAuth();

  const items = useMemo(() => {
    const all = [
      {
        key: 'overview',
        label: (
          <span>
            <DashboardOutlined /> 总览
          </span>
        ),
        children: <OverviewPanel />,
        visible: hasPerm('admin:ops:view') || hasPerm('admin:ops:metrics:view'),
      },
      {
        key: 'rate-limit',
        label: (
          <span>
            <ThunderboltOutlined /> 限流
          </span>
        ),
        children: <RateLimitPanel />,
        visible: hasPerm('admin:ops:view'),
      },
      {
        key: 'degrade',
        label: (
          <span>
            <FallOutlined /> 降级
          </span>
        ),
        children: <DegradePanel />,
        visible: hasPerm('admin:ops:view'),
      },
      {
        key: 'alerts',
        label: (
          <span>
            <AlertOutlined /> 告警
          </span>
        ),
        children: <AlertPanel />,
        visible: hasPerm('admin:ops:view'),
      },
      {
        key: 'metrics',
        label: (
          <span>
            <LineChartOutlined /> 指标
          </span>
        ),
        children: <MetricsPanel />,
        visible: hasPerm('admin:ops:metrics:view') || hasPerm('admin:ops:view'),
      },
      {
        key: 'traces',
        label: (
          <span>
            <NodeIndexOutlined /> 链路
          </span>
        ),
        children: <TracePanel />,
        visible: hasPerm('admin:ops:trace:view') || hasPerm('admin:ops:view'),
      },
      {
        key: 'release',
        label: (
          <span>
            <CloudUploadOutlined /> 发布与备份
          </span>
        ),
        children: <ReleasePanel />,
        visible:
          hasPerm('admin:ops:publish') || hasPerm('admin:ops:backup') || hasPerm('admin:ops:view'),
      },
      {
        key: 'cost',
        label: (
          <span>
            <AccountBookOutlined /> 成本
          </span>
        ),
        children: <CostPanel />,
        visible: hasPerm('admin:cost:view'),
      },
      {
        key: 'drill',
        label: (
          <span>
            <ExperimentOutlined /> 演练与预案
          </span>
        ),
        children: (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <ChaosPanel />
            <RunbookPanel />
          </div>
        ),
        visible: hasPerm('admin:ops:view'),
      },
      {
        key: 'config',
        label: (
          <span>
            <SettingOutlined /> 配置
          </span>
        ),
        children: <ConfigPanel />,
        visible: hasPerm('admin:ops:view'),
      },
    ];
    return all.filter((item) => item.visible).map(({ key, label, children }) => ({ key, label, children }));
  }, [hasPerm]);

  return (
    <div>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        高可用与运维
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
        实时观测走内存滑动窗口，指标每分钟落库；限流、降级、告警规则热更新，无需重启。
        所有清理类操作只作用于 <Typography.Text code>ml:</Typography.Text> 前缀，不会影响共享实例上的其他项目。
      </Typography.Paragraph>

      {items.length === 0 ? (
        <Empty description="当前账号没有运维模块权限（admin:ops:view / admin:cost:view）" />
      ) : (
        <Tabs destroyInactiveTabPane items={items} />
      )}
    </div>
  );
}
