import { Card, Tabs, Typography } from 'antd';
import { WordsPanel } from './WordsPanel';
import { AppealsPanel } from './AppealsPanel';
import { UserSafetyPanel } from './UserSafetyPanel';
import { CompliancePanel } from './CompliancePanel';

/**
 * 模块 18：内容安全与合规。
 *
 * <p>词库/规则/测试工具、申诉复核、用户处置与信用分、版权与投诉/AI 标识统计
 * 分四个一级页签；每个页签内部再做二级划分，避免一个页面塞入过多表格。
 */
export function ContentSafetyPage(): JSX.Element {
  return (
    <Card
      title="内容安全与合规"
      bordered={false}
      extra={
        <Typography.Text type="secondary">
          词库变更立即热加载，所有处置与申诉操作都会写审计日志
        </Typography.Text>
      }
    >
      <Tabs
        destroyInactiveTabPane
        items={[
          { key: 'words', label: '词库与规则', children: <WordsPanel /> },
          { key: 'appeals', label: '申诉复核', children: <AppealsPanel /> },
          { key: 'users', label: '用户处置', children: <UserSafetyPanel /> },
          { key: 'compliance', label: '版权与合规', children: <CompliancePanel /> },
        ]}
      />
    </Card>
  );
}
