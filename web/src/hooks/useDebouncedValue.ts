import { useEffect, useState } from 'react';

/**
 * 防抖值（PRD BR-11-020：搜索输入 300ms 后才触发请求）。
 *
 * 只做「值延迟」，请求取消交给 TanStack Query 的 queryKey 变化自动处理。
 */
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);

  return debounced;
}
