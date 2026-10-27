import { Button, Empty } from 'antd';
import type { ReactNode } from 'react';

/** 空态（PRD §6.3：插画 + 文案 + 引导按钮） */

interface Props {
  description?: ReactNode;
  actionText?: string;
  onAction?: () => void;
  compact?: boolean;
}

export function EmptyState({ description = '暂无数据', actionText, onAction, compact = false }: Props): JSX.Element {
  return (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={description}
      style={compact ? { padding: '24px 0', margin: 0 } : { padding: '48px 0', margin: 0 }}
    >
      {actionText && onAction ? (
        <Button type="primary" onClick={onAction}>
          {actionText}
        </Button>
      ) : null}
    </Empty>
  );
}
