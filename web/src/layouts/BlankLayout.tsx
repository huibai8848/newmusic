import { Link, Outlet } from 'react-router-dom';
import { TrackRoute } from '@/components/common/TrackRoute';

/** 空白框架：登录 / 注册等无导航页面（模块 02 §3） */
export function BlankLayout(): JSX.Element {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '48px 16px',
        background: 'var(--color-bg-layout)',
      }}
    >
      <TrackRoute />
      <div style={{ width: '100%', maxWidth: 420 }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Link to="/" style={{ fontSize: 22, fontWeight: 700, color: 'var(--color-primary)' }}>
            MusicLive
          </Link>
        </div>
        <Outlet />
      </div>
    </div>
  );
}
