import { Skeleton, Spin } from 'antd';

/** 首屏用骨架屏，局部用 Spin（PRD §6.3） */

export function PageLoading({ tip = '加载中…' }: { tip?: string }): JSX.Element {
  return (
    <div style={{ padding: '48px 0', textAlign: 'center' }}>
      <Spin tip={tip} size="large">
        <div style={{ height: 120 }} />
      </Spin>
    </div>
  );
}

export function ListSkeleton({ rows = 6 }: { rows?: number }): JSX.Element {
  return (
    <div style={{ padding: '8px 0' }}>
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} active avatar paragraph={{ rows: 1 }} style={{ marginBottom: 12 }} />
      ))}
    </div>
  );
}

export function CardSkeleton({ cards = 6 }: { cards?: number }): JSX.Element {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 16 }}>
      {Array.from({ length: cards }).map((_, i) => (
        <Skeleton.Node key={i} active style={{ width: '100%', height: 180 }}>
          <span />
        </Skeleton.Node>
      ))}
    </div>
  );
}
