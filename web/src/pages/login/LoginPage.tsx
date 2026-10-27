import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError } from '@/api/request';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import type { LoginReq } from '@/types/api';

/**
 * 登录页（模块 02 T111）。
 *
 * 表单字段与后端 `LoginReq` 一致：`account`（用户名或手机号）+ `password`。
 * 登录失败在页面内联展示（请求层对该接口关闭了全局 Toast，见 `api/auth.ts`）。
 */

export function LoginPage(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm<LoginReq>();
  const login = useUserStore((s) => s.login);
  const logging = useUserStore((s) => s.logging);
  const isLogin = useUserStore((s) => s.isLogin);
  const [error, setError] = useState<string | null>(null);

  const redirect = searchParams.get('redirect');

  if (isLogin) {
    return <Navigate to={redirect ?? '/'} replace />;
  }

  const handleSubmit = async (values: LoginReq): Promise<void> => {
    setError(null);
    try {
      await login(values);
      track('user_login_success', { method: 'password', redirect: redirect ?? '/' });
      navigate(redirect ?? '/', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '登录失败，请稍后重试');
    }
  };

  return (
    <Card title="登录 MusicLive">
      <Form form={form} layout="vertical" onFinish={handleSubmit} autoComplete="on" requiredMark={false}>
        {error && (
          <Form.Item>
            <Alert type="error" message={error} showIcon />
          </Form.Item>
        )}
        <Form.Item name="account" label="账号" rules={[{ required: true, message: '请输入用户名或手机号' }]}>
          <Input size="large" prefix={<UserOutlined />} placeholder="用户名 / 手机号" autoComplete="username" />
        </Form.Item>
        <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
          <Input.Password size="large" prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={logging}>
            登录
          </Button>
        </Form.Item>
      </Form>

      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <span style={{ color: 'var(--color-text-secondary)', fontSize: 13 }}>还没有账号？</span>
        <Link to="/register">立即注册</Link>
      </Space>

      <div
        style={{
          marginTop: 16,
          padding: 12,
          borderRadius: 8,
          background: 'var(--color-bg-elevated)',
          fontSize: 12,
          color: 'var(--color-text-secondary)',
        }}
      >
        <Typography.Text strong style={{ fontSize: 12 }}>
          演示账号（种子数据）
        </Typography.Text>
        <div>管理员 admin / admin123（含曲库管理与删除权限）</div>
        <div>运营 operator / operator123（无歌曲删除权限）</div>
        <div>普通用户 demo / demo123456</div>
      </div>
    </Card>
  );
}
