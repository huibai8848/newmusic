import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { initTracking, trackPageView } from '@/services/track';

/**
 * 路由级埋点桥（模块 17 FR-26-002）。
 *
 * 只做两件事：首次挂载时启动 SDK（定时 flush / 关页 beacon），
 * 路径变化时补一次 `page_view`。`trackPageView` 内部会给上一页补
 * `page_leave`，所以这里不需要额外的 cleanup —— StrictMode 的双次挂载
 * 也不会产生重复 PV（同一路径直接返回）。
 */
export function TrackRoute(): null {
  const location = useLocation();

  useEffect(() => {
    initTracking();
  }, []);

  useEffect(() => {
    trackPageView(`${location.pathname}${location.search}`);
  }, [location.pathname, location.search]);

  return null;
}
