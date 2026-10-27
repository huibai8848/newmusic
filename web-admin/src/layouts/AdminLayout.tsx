import { useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppstoreOutlined,
  BookOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FileSearchOutlined,
  FlagOutlined,
  FolderOpenOutlined,
  FormOutlined,
  FundOutlined,
  IdcardOutlined,
  KeyOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SoundOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Alert, Avatar, Button, Dropdown, Form, Input, Layout, Menu, Modal, Space, Typography, message } from 'antd';
import type { MenuProps } from 'antd';
import { useAuth } from '@/auth/AuthContext';
import { authApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';

const { Header, Sider, Content } = Layout;

const SIDEBAR_WIDTH = 220;

interface MenuEntry {
  path: string;
  label: string;
  icon: JSX.Element;
  perms: string[];
}

const MENU: MenuEntry[] = [
  { path: '/dashboard', label: '数据看板', icon: <DashboardOutlined />, perms: ['admin:dashboard:view'] },
  { path: '/users', label: '用户管理', icon: <TeamOutlined />, perms: ['admin:user:list'] },
  { path: '/songs', label: '歌曲管理', icon: <SoundOutlined />, perms: ['admin:song:list'] },
  { path: '/artists', label: '歌手管理', icon: <IdcardOutlined />, perms: ['admin:artist:manage'] },
  { path: '/albums', label: '专辑管理', icon: <FolderOpenOutlined />, perms: ['admin:album:manage'] },
  { path: '/rooms', label: '房间管理', icon: <AppstoreOutlined />, perms: ['admin:room:list'] },
  { path: '/audits', label: '审核工作台', icon: <FormOutlined />, perms: ['admin:audit:list'] },
  {
    path: '/content-safety',
    label: '内容安全',
    icon: <SafetyCertificateOutlined />,
    perms: ['admin:audit:view', 'admin:audit:word:view'],
  },
  { path: '/reports', label: '举报处理', icon: <FlagOutlined />, perms: ['admin:report:list'] },
  {
    path: '/logs',
    label: '日志审计',
    icon: <FileSearchOutlined />,
    perms: ['admin:log:view', 'admin:log:view:self'],
  },
  { path: '/dicts', label: '搜索词典', icon: <BookOutlined />, perms: ['admin:dict:view'] },
  { path: '/data-platform', label: '数据平台', icon: <FundOutlined />, perms: ['admin:data:view'] },
  {
    path: '/ops',
    label: '高可用与运维',
    icon: <DeploymentUnitOutlined />,
    perms: ['admin:ops:view', 'admin:ops:metrics:view', 'admin:ops:trace:view', 'admin:cost:view'],
  },
  { path: '/configs', label: '系统配置', icon: <SettingOutlined />, perms: ['admin:sysconfig:view'] },
  { path: '/accounts', label: '管理员账号', icon: <KeyOutlined />, perms: ['admin:adminuser:manage'] },
];

export function AdminLayout(): JSX.Element {
  const { admin, hasPerm, logout, reload } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordForm] = Form.useForm<{ oldPassword: string; newPassword: string; confirm: string }>();

  const mustChangePassword = admin?.forceChangePassword === 1;

  useEffect(() => {
    if (mustChangePassword) {
      setPasswordOpen(true);
    }
  }, [mustChangePassword]);

  const items = useMemo<NonNullable<MenuProps['items']>>(
    () =>
      MENU.filter((entry) => entry.perms.some(hasPerm)).map((entry) => ({
        key: entry.path,
        icon: entry.icon,
        label: <Link to={entry.path}>{entry.label}</Link>,
      })),
    [hasPerm],
  );

  const selected = MENU.map((entry) => entry.path).find((path) => pathname.startsWith(path));

  const userMenu: MenuProps['items'] = [
    {
      key: 'password',
      label: '修改密码',
      icon: <KeyOutlined />,
      onClick: () => setPasswordOpen(true),
    },
    { type: 'divider' },
    {
      key: 'logout',
      label: '退出登录',
      icon: <LogoutOutlined />,
      onClick: async () => {
        await logout();
        navigate('/login', { replace: true });
      },
    },
  ];

  const submitPassword = async (): Promise<void> => {
    const values = await passwordForm.validateFields();
    if (values.newPassword !== values.confirm) {
      message.error('两次输入的新密码不一致');
      return;
    }
    setPasswordSaving(true);
    try {
      await authApi.changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
      message.success('密码已修改，请使用新密码继续操作');
      passwordForm.resetFields();
      setPasswordOpen(false);
      await reload();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setPasswordSaving(false);
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={SIDEBAR_WIDTH} theme="light" collapsible collapsed={collapsed} trigger={null}>
        <div className="admin-logo">
          <SoundOutlined />
          {!collapsed && <span>MusicLive 后台</span>}
        </div>
        <Menu mode="inline" selectedKeys={selected ? [selected] : []} items={items} />
      </Sider>

      <Layout>
        <Header className="admin-header">
          <Button
            type="text"
            aria-label={collapsed ? '展开菜单' : '收起菜单'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((value) => !value)}
          />
          <Typography.Title level={4} style={{ margin: 0, fontSize: 18 }}>
            {MENU.find((entry) => entry.path === selected)?.label ?? '管理后台'}
          </Typography.Title>
          <div style={{ flex: 1 }} />
          <Dropdown menu={{ items: userMenu }} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size={30} icon={<UserOutlined />} />
              <Typography.Text>{admin?.realName || admin?.username}</Typography.Text>
            </Space>
          </Dropdown>
        </Header>

        <Content style={{ margin: 20 }}>
          {admin && admin.passwordUpdateTime == null && (
            <Alert
              type="info"
              showIcon
              closable
              style={{ marginBottom: 16 }}
              message="建议尽快修改初始密码：右上角账号菜单 → 修改密码"
            />
          )}
          <Outlet />
        </Content>
      </Layout>

      <Modal
        title="修改登录密码"
        open={passwordOpen}
        onCancel={() => {
          if (!mustChangePassword) setPasswordOpen(false);
        }}
        closable={!mustChangePassword}
        maskClosable={!mustChangePassword}
        keyboard={!mustChangePassword}
        onOk={() => void submitPassword()}
        confirmLoading={passwordSaving}
        okText="确认修改"
      >
        {mustChangePassword && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="管理员已重置您的密码或密码超过 90 天，请先设置新密码"
          />
        )}
        <Form form={passwordForm} layout="vertical">
          <Form.Item
            name="oldPassword"
            label="当前密码"
            rules={[{ required: true, message: '请输入当前密码' }]}
          >
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, max: 64, message: '密码长度需为 8~64 位' },
              {
                pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
                message: '至少包含字母与数字',
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[{ required: true, message: '请再次输入新密码' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
}
