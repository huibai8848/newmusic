import { Navigate, Route, Routes } from 'react-router-dom';
import { Result } from 'antd';
import { RequireAuth, RequirePerm } from '@/components/RequireAuth';
import { AdminLayout } from '@/layouts/AdminLayout';
import { LoginPage } from '@/pages/LoginPage';
import { DashboardPage } from '@/pages/DashboardPage';
import { UsersPage } from '@/pages/UsersPage';
import { SongsPage } from '@/pages/SongsPage';
import { ArtistsPage } from '@/pages/ArtistsPage';
import { AlbumsPage } from '@/pages/AlbumsPage';
import { RoomsPage } from '@/pages/RoomsPage';
import { AuditsPage } from '@/pages/AuditsPage';
import { ReportsPage } from '@/pages/ReportsPage';
import { LogsPage } from '@/pages/LogsPage';
import { ConfigsPage } from '@/pages/ConfigsPage';
import { AccountsPage } from '@/pages/AccountsPage';
import { DictsPage } from '@/pages/DictsPage';
import { DataPlatformPage } from '@/pages/data/DataPlatformPage';
import { ContentSafetyPage } from '@/pages/content-safety/ContentSafetyPage';
import { OpsPage } from '@/pages/ops/OpsPage';

const OPS_PERMS = ['admin:ops:view', 'admin:ops:metrics:view', 'admin:ops:trace:view', 'admin:cost:view'];

function NotFound(): JSX.Element {
  return (
    <Result
      status="404"
      title="页面不存在"
      subTitle="请从左侧菜单进入已授权的功能页面。"
    />
  );
}

export default function App(): JSX.Element {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        element={
          <RequireAuth>
            <AdminLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route
          path="/dashboard"
          element={
            <RequirePerm code="admin:dashboard:view">
              <DashboardPage />
            </RequirePerm>
          }
        />
        <Route
          path="/users"
          element={
            <RequirePerm code="admin:user:list">
              <UsersPage />
            </RequirePerm>
          }
        />
        <Route
          path="/songs"
          element={
            <RequirePerm code="admin:song:list">
              <SongsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/artists"
          element={
            <RequirePerm code="admin:artist:manage">
              <ArtistsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/albums"
          element={
            <RequirePerm code="admin:album:manage">
              <AlbumsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/rooms"
          element={
            <RequirePerm code="admin:room:list">
              <RoomsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/audits"
          element={
            <RequirePerm code="admin:audit:list">
              <AuditsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/reports"
          element={
            <RequirePerm code="admin:report:list">
              <ReportsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/content-safety"
          element={
            <RequirePerm code={['admin:audit:view', 'admin:audit:word:view']}>
              <ContentSafetyPage />
            </RequirePerm>
          }
        />
        <Route
          path="/logs"
          element={
            <RequirePerm code={['admin:log:view', 'admin:log:view:self']}>
              <LogsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/configs"
          element={
            <RequirePerm code="admin:sysconfig:view">
              <ConfigsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/accounts"
          element={
            <RequirePerm code="admin:adminuser:manage">
              <AccountsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/dicts"
          element={
            <RequirePerm code="admin:dict:view">
              <DictsPage />
            </RequirePerm>
          }
        />
        <Route
          path="/data-platform"
          element={
            <RequirePerm code="admin:data:view">
              <DataPlatformPage />
            </RequirePerm>
          }
        />
        <Route
          path="/ops"
          element={
            <RequirePerm code={OPS_PERMS}>
              <OpsPage />
            </RequirePerm>
          }
        />
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}
