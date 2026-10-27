import { useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Avatar, Badge, Button, Dropdown, Input, Menu, Space, Typography } from 'antd';
import type { MenuProps } from 'antd';
import {
  BellOutlined,
  CustomerServiceOutlined,
  DashboardOutlined,
  LogoutOutlined,
  MessageOutlined,
  ProfileOutlined,
  SearchOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { useUserStore } from '@/stores/userStore';
import { subscribeSocialMessageEvents } from '@/stores/wsStore';
import { useAnyPerm } from '@/hooks/usePerm';
import { useSystemThemeSync } from '@/hooks/useSystemThemeSync';
import { ThemeSwitch } from '@/components/common/ThemeSwitch';
import { TrackRoute } from '@/components/common/TrackRoute';
import { NotificationBell } from '@/components/common/NotificationBell';
import { DegradeBanner } from '@/components/common/DegradeBanner';
import { useDegradeStatus } from '@/hooks/useDegradeStatus';
import { visibleNavPaths } from '@/utils/degrade';
import { PlayerBar } from '@/components/player/PlayerBar';
import styles from './MainLayout.module.less';

/**
 * C 端主框架（模块 02 D-07）。
 *
 * 关键点：`<AudioEngine>` 挂在**应用根节点**（`main.tsx`，不在任何路由树下），
 * 播放条则由本布局渲染。这样切页、进出后台都不会销毁 `<audio>` 元素，
 * 音乐始终不中断（AC-11-005-2）；模块 07 的全屏播放页复用同一套播放状态。
 */

const NAV_ITEMS = [
  { path: '/', label: '首页' },
  { path: '/recommend', label: '推荐' },
  { path: '/feed', label: '动态' },
  { path: '/rooms', label: '音乐房' },
  { path: '/search', label: '搜索' },
  { path: '/user', label: '我的音乐' },
];

function useSelectedNavKey(): string {
  const { pathname } = useLocation();
  if (pathname === '/') return '/';
  if (pathname.startsWith('/recommend')) return '/recommend';
  if (
    pathname.startsWith('/feed') ||
    pathname.startsWith('/discover') ||
    pathname.startsWith('/post') ||
    pathname.startsWith('/topic')
  ) {
    return '/feed';
  }
  if (pathname.startsWith('/rooms') || pathname.startsWith('/room')) return '/rooms';
  if (pathname.startsWith('/live')) return '/rooms';
  if (pathname.startsWith('/search')) return '/search';
  // 公开主页（/user/:id）不属于「我的音乐」，不选中主导航
  if (pathname === '/user') return '/user';
  return '';
}

/** 头部私信入口：未读数走模块 16 `/conversations/unread-count`，WS 事件即时失效 */
function HeaderMessages(): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const unreadQuery = useQuery({
    queryKey: socialUpgradeKeys.conversationUnread,
    queryFn: conversationApi.unreadCount,
    refetchOnWindowFocus: true,
  });

  useEffect(() => {
    return subscribeSocialMessageEvents(() => {
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.conversationUnread });
      void queryClient.invalidateQueries({ queryKey: ['social-upgrade', 'conversations'] });
    });
  }, [queryClient]);

  return (
    <Badge count={unreadQuery.data ?? 0} size="small" overflowCount={99} offset={[-2, 2]}>
      <Button
        type="text"
        icon={<MessageOutlined />}
        aria-label="私信"
        onClick={() => navigate('/messages')}
      />
    </Badge>
  );
}

function HeaderUser(): JSX.Element {
  const navigate = useNavigate();
  const user = useUserStore((s) => s.user);
  const isLogin = useUserStore((s) => s.isLogin);
  const logout = useUserStore((s) => s.logout);
  const canAdmin = useAnyPerm(['music:song:update', 'music:artist:update']);

  if (!isLogin || !user) {
    return (
      <Space size={8}>
        <Button type="text" onClick={() => navigate('/login')}>
          登录
        </Button>
        <Button type="primary" onClick={() => navigate('/register')}>
          注册
        </Button>
      </Space>
    );
  }

  const items: MenuProps['items'] = [
    { key: 'profile', label: '我的主页', icon: <UserOutlined /> },
    { key: 'user', label: '个人中心', icon: <ProfileOutlined /> },
    { key: 'notifications', label: '消息中心', icon: <BellOutlined /> },
    { key: 'messages', label: '私信', icon: <MessageOutlined /> },
    { key: 'settings', label: '设置', icon: <SettingOutlined /> },
    ...(canAdmin
      ? [{ key: 'admin', label: '管理后台', icon: <DashboardOutlined /> }]
      : []),
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', icon: <LogoutOutlined /> },
  ];

  return (
    <Dropdown
      placement="bottomRight"
      menu={{
        items,
        onClick: async ({ key }) => {
          if (key === 'profile') navigate(`/user/${user.id}`);
          if (key === 'user') navigate('/user');
          if (key === 'notifications') navigate('/notifications');
          if (key === 'messages') navigate('/messages');
          if (key === 'settings') navigate('/settings');
          if (key === 'admin') navigate('/admin/songs');
          if (key === 'logout') {
            await logout();
            navigate('/');
          }
        },
      }}
    >
      <div className={styles.user}>
        <Avatar size={28} src={user.avatar ?? undefined} icon={<UserOutlined />} />
        <Typography.Text ellipsis style={{ maxWidth: 96 }}>
          {user.nickname || user.username}
        </Typography.Text>
      </div>
    </Dropdown>
  );
}

/** 顶部搜索框：提交后跳 `/search?keyword=…`（同一页面重复搜索也会刷新结果） */
function HeaderSearch(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { pathname } = useLocation();
  const urlKeyword = pathname.startsWith('/search') ? (searchParams.get('keyword') ?? '') : '';
  const [value, setValue] = useState(urlKeyword);

  useEffect(() => {
    setValue(urlKeyword);
  }, [urlKeyword]);

  const submit = (keyword: string): void => {
    const kw = keyword.trim();
    navigate(kw ? `/search?keyword=${encodeURIComponent(kw)}` : '/search');
  };

  return (
    <Input
      className={styles.search}
      allowClear
      size="middle"
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onPressEnter={() => submit(value)}
      placeholder="搜索歌曲、歌手、专辑"
      prefix={<SearchOutlined style={{ color: 'var(--color-text-secondary)' }} />}
      aria-label="搜索"
    />
  );
}

export function MainLayout(): JSX.Element {
  const selectedKey = useSelectedNavKey();
  const isLogin = useUserStore((s) => s.isLogin);
  useSystemThemeSync();
  // 模块 19：降级时隐藏依赖已关闭功能的入口（如推荐流），浏览与播放不受影响
  const degrade = useDegradeStatus();
  const navItems = useMemo<MenuProps['items']>(
    () =>
      visibleNavPaths(
        NAV_ITEMS.map((item) => item.path),
        degrade,
      ).map((path) => {
        const item = NAV_ITEMS.find((entry) => entry.path === path);
        return { key: path, label: <Link to={path}>{item?.label ?? path}</Link> };
      }),
    [degrade],
  );

  return (
    <div className={styles.content}>
      {/* 模块 17：页面浏览埋点跟着路由走，放在布局里比每个页面各调一次更不容易漏 */}
      <TrackRoute />
      <header className={styles.header}>
        <div className={`ml-container ${styles.inner}`}>
          <Link to="/" className={styles.logo}>
            <CustomerServiceOutlined />
            MusicLive
          </Link>
          <Menu
            className={styles.nav}
            mode="horizontal"
            selectedKeys={selectedKey ? [selectedKey] : []}
            items={navItems}
            disabledOverflow
          />
          <HeaderSearch />
          {/* 通知铃铛只在登录后出现：未登录时 WS 本就不会建立（模块 04） */}
          {isLogin && <HeaderMessages />}
          {isLogin && <NotificationBell />}
          <ThemeSwitch />
          <HeaderUser />
        </div>
      </header>

      {/* 降级公告：未登录也展示，让用户知道是系统限流而不是自己网络坏了 */}
      <DegradeBanner />

      <main className="ml-main">
        <div className="ml-container">
          <Outlet />
        </div>
      </main>

      <footer className={styles.footer}>
        MusicLive · 演示环境（模块 01/02 MVP）· 音频与图片资源仅用于本地联调
      </footer>

      {/* 播放条：常驻底部，不随路由卸载 */}
      <PlayerBar />
    </div>
  );
}
