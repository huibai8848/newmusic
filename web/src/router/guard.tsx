import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Result, Button } from 'antd';
import { useUserStore } from '@/stores/userStore';

/**
 * 路由守卫（模块 02 §5.3）。
 *
 * 前端守卫只负责「体验」——不让人点到必然会 403 的页面；
 * 真正的权限判定仍在服务端 `@PreAuthorize`（AC-10-003-x 由后端用例保证）。
 */

interface Props {
  children: ReactNode;
}

/** 需要登录；未登录跳登录页并记住来源（登录后回跳） */
export function RequireAuth({ children }: Props): JSX.Element {
  const isLogin = useUserStore((s) => s.isLogin);
  const location = useLocation();

  if (!isLogin) {
    const redirect = encodeURIComponent(`${location.pathname}${location.search}`);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return <>{children}</>;
}

/**
 * 需要权限码（支持 `music:artist:*` 这类资源级通配，见 D-06）。
 * 已登录但无权限 → 跳 403 页，而不是静默跳首页。
 */
export function RequirePerm({ perms, children }: Props & { perms: string[] }): JSX.Element {
  const isLogin = useUserStore((s) => s.isLogin);
  const hasAnyPerm = useUserStore((s) => s.hasAnyPerm);
  const location = useLocation();

  if (!isLogin) {
    const redirect = encodeURIComponent(`${location.pathname}${location.search}`);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  if (!hasAnyPerm(perms)) {
    return <Navigate to="/403" replace />;
  }
  return <>{children}</>;
}

/** 403 页（PRD §6.3 错误页规范） */
export function ForbiddenPage(): JSX.Element {
  return (
    <Result
      status="403"
      title="403"
      subTitle="抱歉，你没有访问该页面的权限。"
      extra={
        <Button type="primary" href="/">
          回到首页
        </Button>
      }
    />
  );
}
