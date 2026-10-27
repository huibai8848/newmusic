import { Spin } from 'antd';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import type { ReactNode } from 'react';

export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const { admin, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}>
        <Spin size="large" tip="正在确认登录状态…" />
      </div>
    );
  }
  if (!admin) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

/** code 传入多个时按「任一权限即可」判断 */
export function RequirePerm({
  code,
  children,
}: {
  code: string | string[];
  children: ReactNode;
}): JSX.Element {
  const { hasPerm } = useAuth();
  const codes = Array.isArray(code) ? code : [code];
  if (!codes.some(hasPerm)) {
    return (
      <div style={{ padding: 48, color: '#8c8c8c', textAlign: 'center' }}>
        当前账号没有权限访问该页面（{codes.join(' / ')}）
      </div>
    );
  }
  return <>{children}</>;
}
