import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, createBrowserRouter, useParams } from 'react-router-dom';
import { ForbiddenPage, RequireAuth, RequirePerm } from '@/router/guard';
import { MainLayout } from '@/layouts/MainLayout';
import { AdminLayout } from '@/layouts/AdminLayout';
import { BlankLayout } from '@/layouts/BlankLayout';
import { PageLoading } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';

/**
 * 路由表（模块 02 §5.1）。
 *
 * 页面级 `React.lazy`：首屏只加载首页/登录所需 chunk（PRD NFR 首屏 ≤ 2s）。
 * 播放器与 `<audio>` 都在布局之外，因此懒加载切页不会中断播放。
 */

const HomePage = lazy(() => import('@/pages/home/HomePage').then((m) => ({ default: m.HomePage })));
const RecommendPage = lazy(() =>
  import('@/pages/recommend/RecommendPage').then((m) => ({ default: m.RecommendPage })),
);
const LoginPage = lazy(() => import('@/pages/login/LoginPage').then((m) => ({ default: m.LoginPage })));
const RegisterPage = lazy(() => import('@/pages/login/RegisterPage').then((m) => ({ default: m.RegisterPage })));
const SearchPage = lazy(() => import('@/pages/search/SearchPage').then((m) => ({ default: m.SearchPage })));
const DemoRoomPage = lazy(() =>
  import('@/pages/live/DemoRoomPage').then((m) => ({ default: m.DemoRoomPage })),
);
const RoomListPage = lazy(() =>
  import('@/pages/room/RoomListPage').then((m) => ({ default: m.RoomListPage })),
);
const RoomPage = lazy(() =>
  import('@/pages/room/RoomPage').then((m) => ({ default: m.RoomPage })),
);
const SongDetailPage = lazy(() => import('@/pages/song/SongDetailPage').then((m) => ({ default: m.SongDetailPage })));
const ArtistDetailPage = lazy(() =>
  import('@/pages/artist/ArtistDetailPage').then((m) => ({ default: m.ArtistDetailPage })),
);
const AlbumDetailPage = lazy(() =>
  import('@/pages/album/AlbumDetailPage').then((m) => ({ default: m.AlbumDetailPage })),
);
const PlaylistDetailPage = lazy(() =>
  import('@/pages/playlist/PlaylistDetailPage').then((m) => ({ default: m.PlaylistDetailPage })),
);
const UserCenterPage = lazy(() => import('@/pages/user/UserCenterPage').then((m) => ({ default: m.UserCenterPage })));
const UserProfilePage = lazy(() =>
  import('@/pages/user/UserProfilePage').then((m) => ({ default: m.UserProfilePage })),
);
const FeedPage = lazy(() => import('@/pages/feed/FeedPage').then((m) => ({ default: m.FeedPage })));
const PostComposerPage = lazy(() =>
  import('@/pages/post/PostComposerPage').then((m) => ({ default: m.PostComposerPage })),
);
const PostDetailPage = lazy(() =>
  import('@/pages/post/PostDetailPage').then((m) => ({ default: m.PostDetailPage })),
);
const TopicPage = lazy(() => import('@/pages/topic/TopicPage').then((m) => ({ default: m.TopicPage })));
const NotificationCenterPage = lazy(() =>
  import('@/pages/notification/NotificationCenterPage').then((m) => ({ default: m.NotificationCenterPage })),
);
const MessagesPage = lazy(() =>
  import('@/pages/message/MessagesPage').then((m) => ({ default: m.MessagesPage })),
);
const SettingsPage = lazy(() =>
  import('@/pages/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })),
);
const AdminSongsPage = lazy(() => import('@/pages/admin/AdminSongsPage').then((m) => ({ default: m.AdminSongsPage })));
const AdminArtistsPage = lazy(() =>
  import('@/pages/admin/AdminArtistsPage').then((m) => ({ default: m.AdminArtistsPage })),
);
const NotFoundPage = lazy(() => import('@/pages/error/NotFoundPage').then((m) => ({ default: m.NotFoundPage })));

function Lazy({ children }: { children: ReactNode }): JSX.Element {
  return <Suspense fallback={<PageLoading />}>{children}</Suspense>;
}

/** 后端分享链接是 `/posts/{id}`，C 端详情路由为 `/post/{id}` */
function PostIdRedirect(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/post/${encodeURIComponent(id ?? '')}`} replace />;
}

/** 路由级兜底：chunk 加载失败（发版后旧 chunk 404）也走统一失败态 */
export function RouteError(): JSX.Element {
  return <ErrorState title="页面加载失败" error={new Error('资源加载失败，请刷新页面重试')} />;
}

export const router = createBrowserRouter([
  {
    element: (
      <Lazy>
        <BlankLayout />
      </Lazy>
    ),
    errorElement: <RouteError />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: (
      <Lazy>
        <MainLayout />
      </Lazy>
    ),
    errorElement: <RouteError />,
    children: [
      { path: '/', element: <HomePage /> },
      {
        // 模块 08：所有推荐接口都要求登录（游客没有画像）
        path: '/recommend',
        element: (
          <RequireAuth>
            <RecommendPage />
          </RequireAuth>
        ),
      },
      { path: '/rooms', element: <RoomListPage /> },
      {
        // 音乐房详情：路由用 6 位房间号（分享链接可读），页面内部再换雪花 ID
        path: '/room/:roomNo',
        element: (
          <RequireAuth>
            <RoomPage />
          </RequireAuth>
        ),
      },
      {
        // 模块 04 WS 联调页（保留，供 verify-04 与手工排查使用）
        path: '/live/:roomId',
        element: (
          <RequireAuth>
            <DemoRoomPage />
          </RequireAuth>
        ),
      },
      { path: '/search', element: <SearchPage /> },
      { path: '/song/:id', element: <SongDetailPage /> },
      { path: '/artist/:id', element: <ArtistDetailPage /> },
      { path: '/album/:id', element: <AlbumDetailPage /> },
      { path: '/playlist/:id', element: <PlaylistDetailPage /> },
      {
        // 模块 16：关注流 / 发现流（`/feed?tab=discover`）
        path: '/feed',
        element: (
          <RequireAuth>
            <FeedPage />
          </RequireAuth>
        ),
      },
      {
        path: '/discover',
        element: (
          <RequireAuth>
            <Navigate to="/feed?tab=discover" replace />
          </RequireAuth>
        ),
      },
      {
        // 必须排在 /post/:id 之前
        path: '/post/new',
        element: (
          <RequireAuth>
            <PostComposerPage />
          </RequireAuth>
        ),
      },
      { path: '/post/:id', element: <PostDetailPage /> },
      { path: '/posts/:id', element: <PostIdRedirect /> },
      { path: '/topic/:name', element: <TopicPage /> },
      { path: '/user/:userId', element: <UserProfilePage /> },
      {
        path: '/user',
        element: (
          <RequireAuth>
            <UserCenterPage />
          </RequireAuth>
        ),
      },
      {
        path: '/notifications',
        element: (
          <RequireAuth>
            <NotificationCenterPage />
          </RequireAuth>
        ),
      },
      {
        path: '/messages',
        element: (
          <RequireAuth>
            <MessagesPage />
          </RequireAuth>
        ),
      },
      {
        path: '/settings',
        element: (
          <RequireAuth>
            <SettingsPage />
          </RequireAuth>
        ),
      },
      { path: '/403', element: <ForbiddenPage /> },
      { path: '/404', element: <NotFoundPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  {
    path: '/admin',
    element: (
      <RequirePerm perms={['music:song:update', 'music:artist:update']}>
        <Lazy>
          <AdminLayout />
        </Lazy>
      </RequirePerm>
    ),
    errorElement: <RouteError />,
    children: [
      { index: true, element: <Navigate to="/admin/songs" replace /> },
      { path: 'songs', element: <AdminSongsPage /> },
      { path: 'artists', element: <AdminArtistsPage /> },
    ],
  },
]);
