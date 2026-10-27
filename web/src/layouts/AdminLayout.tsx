import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Avatar, Dropdown, Layout, Menu, Space, Typography } from 'antd';
import type { MenuProps } from 'antd';
import { DashboardOutlined, HomeOutlined, LogoutOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { useUserStore } from '@/stores/userStore';
import { ThemeSwitch } from '@/components/common/ThemeSwitch';
import { PlayerBar } from '@/components/player/PlayerBar';
import styles from './AdminLayout.module.less';

/**
 * 管理后台框架（模块 02 §5.1 后台骨架）。
 *
 * 模块 01 只提供了曲库相关接口，因此 MVP 后台仅「歌曲管理 / 艺人管理」两项；
 * 用户管理、分类管理等页面依赖后续模块的接口，届时在此追加菜单即可。
 */

/** 与 variables.less 的 `--sidebar-width` 保持一致（Sider 需要数字，无法用 var()） */
const SIDEBAR_WIDTH = 200;

/** `MenuProps['items']` 本身可空，这里加一层 `NonNullable` 才能直接 `.map` */
const MENU_ITEMS: NonNullable<MenuProps['items']> = [
  { key: '/admin/songs', label: <Link to="/admin/songs">歌曲管理</Link>, icon: <DashboardOutlined /> },
  { key: '/admin/artists', label: <Link to="/admin/artists">艺人管理</Link>, icon: <TeamOutlined /> },
];

export function AdminLayout(): JSX.Element {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const user = useUserStore((s) => s.user);
  const logout = useUserStore((s) => s.logout);

  const selected = MENU_ITEMS.map((it) => String(it?.key)).find((key) => pathname.startsWith(key));

  const userMenu: MenuProps['items'] = [
    { key: 'front', label: '返回前台', icon: <HomeOutlined /> },
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', icon: <LogoutOutlined /> },
  ];

  return (
    <Layout className={styles.shell}>
      <Layout.Sider className={styles.sider} width={SIDEBAR_WIDTH} theme="light">
        <div className={styles.logo}>
          <DashboardOutlined />
          管理后台
        </div>
        <Menu mode="inline" selectedKeys={selected ? [selected] : []} items={MENU_ITEMS} />
      </Layout.Sider>

      <Layout>
        <div className={styles.header}>
          <Typography.Text className={styles.title}>曲库管理</Typography.Text>
          <div className={styles.spacer} />
          <ThemeSwitch />
          <Dropdown
            placement="bottomRight"
            menu={{
              items: userMenu,
              onClick: async ({ key }) => {
                if (key === 'front') navigate('/');
                if (key === 'logout') {
                  await logout();
                  navigate('/login');
                }
              },
            }}
          >
            <Space size={8} style={{ cursor: 'pointer' }}>
              <Avatar size={28} src={user?.avatar ?? undefined} icon={<UserOutlined />} />
              <Typography.Text>{user?.nickname ?? user?.username ?? '未登录'}</Typography.Text>
            </Space>
          </Dropdown>
        </div>

        <Layout.Content className={styles.content}>
          <Outlet />
        </Layout.Content>
      </Layout>

      <PlayerBar />
    </Layout>
  );
}
