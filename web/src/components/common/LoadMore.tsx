import { useEffect, useRef } from 'react';
import { Button, Spin } from 'antd';

/**
 * 触底加载（模块 02 D-11）。
 *
 * 是否还有下一页完全由后端 `PageResult.hasNext` 决定，前端不自算页数
 * （`pageSize` 超过 100 时后端会截断并置 `truncated=true`，由页面另行提示）。
 * 哨兵进入视口即触发；同时保留按钮，键盘/读屏用户也能主动加载。
 */

interface Props {
  hasNext: boolean;
  loading: boolean;
  onLoadMore: () => void;
  total?: number;
}

export function LoadMore({ hasNext, loading, onLoadMore, total }: Props): JSX.Element {
  const sentinel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasNext) return undefined;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !loading) {
          onLoadMore();
        }
      },
      { rootMargin: '160px' },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasNext, loading, onLoadMore]);

  if (!hasNext) {
    return (
      <div style={{ textAlign: 'center', padding: '16px 0', fontSize: 12, color: 'var(--color-text-secondary)' }}>
        {total === undefined ? '已加载全部' : `已加载全部 ${total} 条`}
      </div>
    );
  }

  return (
    <div ref={sentinel} style={{ textAlign: 'center', padding: '16px 0' }}>
      {loading ? <Spin size="small" /> : <Button onClick={onLoadMore}>加载更多</Button>}
    </div>
  );
}
