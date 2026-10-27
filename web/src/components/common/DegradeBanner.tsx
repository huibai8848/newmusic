import { Alert, Space, Tag, Typography } from 'antd';
import { useDegradeStatus } from '@/hooks/useDegradeStatus';
import { degradeBannerText, degradeFeatureLabel } from '@/utils/degrade';
import styles from './DegradeBanner.module.less';

/**
 * 降级公告（模块 19 FR-28-011）。
 *
 * 只在等级 > 0 时出现：告诉用户「不是你网坏了」以及哪些功能暂时关了。
 * 未登录也要显示，所以数据来自公开的 `/system/degrade-status`。
 */
export function DegradeBanner(): JSX.Element | null {
  const status = useDegradeStatus();
  const text = degradeBannerText(status);
  if (!text) return null;

  const features = status?.disabledFeatures ?? [];

  return (
    <div className={`ml-container ${styles.wrap}`}>
      <Alert
        type="warning"
        showIcon
        message={text}
        description={
          features.length > 0 ? (
            <Space size={[6, 6]} wrap>
              <Typography.Text type="secondary">暂时不可用：</Typography.Text>
              {features.map((key) => (
                <Tag key={key} color="orange">
                  {degradeFeatureLabel(key)}
                </Tag>
              ))}
            </Space>
          ) : undefined
        }
      />
    </div>
  );
}
