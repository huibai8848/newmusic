import { useState } from 'react';
import { Alert, Button, Card, Checkbox, Form, Input } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { ApiError } from '@/api/request';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';

/**
 * 注册页（模块 02 T111）。
 *
 * 注册成功后自动登录（用同一份账号密码调 `/auth/login`），
 * 省掉「注册完再登录一次」的多余步骤。
 */

interface FormValues {
  username: string;
  nickname: string;
  password: string;
  confirm: string;
  agreement: boolean;
}

export function RegisterPage(): JSX.Element {
  const navigate = useNavigate();
  const [form] = Form.useForm<FormValues>();
  const login = useUserStore((s) => s.login);
  const isLogin = useUserStore((s) => s.isLogin);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (isLogin) return <Navigate to="/" replace />;

  const handleSubmit = async (values: FormValues): Promise<void> => {
    setError(null);
    setSubmitting(true);
    try {
      await authApi.register({ username: values.username, password: values.password, nickname: values.nickname });
      // 注册成功即首登：`is_new_user` 让埋点链路能直接识别新用户，不必回查 user 表
      track('user_register_success', { channel: 'web', is_new_user: true });
      await login({ account: values.username, password: values.password });
      track('user_login_success', { method: 'password', auto: true });
      navigate('/', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '注册失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="注册 MusicLive">
      <Form form={form} layout="vertical" onFinish={handleSubmit} requiredMark={false}>
        {error && (
          <Form.Item>
            <Alert type="error" message={error} showIcon />
          </Form.Item>
        )}
        <Form.Item
          name="username"
          label="用户名"
          rules={[
            { required: true, message: '请输入用户名' },
            { pattern: /^[a-zA-Z0-9_]{4,20}$/, message: '4-20 位字母、数字或下划线' },
          ]}
        >
          <Input size="large" prefix={<UserOutlined />} placeholder="登录时使用" autoComplete="username" />
        </Form.Item>
        <Form.Item name="nickname" label="昵称" rules={[{ required: true, message: '请输入昵称' }, { max: 20 }]}>
          <Input size="large" placeholder="展示用昵称" />
        </Form.Item>
        <Form.Item
          name="password"
          label="密码"
          rules={[
            { required: true, message: '请输入密码' },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/, message: '8-20 位，需同时包含字母和数字' },
          ]}
        >
          <Input.Password size="large" prefix={<LockOutlined />} placeholder="8-20 位，含字母和数字" autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirm"
          label="确认密码"
          dependencies={['password']}
          rules={[
            { required: true, message: '请再次输入密码' },
            ({ getFieldValue }) => ({
              validator: (_rule, value) =>
                !value || getFieldValue('password') === value
                  ? Promise.resolve()
                  : Promise.reject(new Error('两次输入的密码不一致')),
            }),
          ]}
        >
          <Input.Password size="large" prefix={<LockOutlined />} placeholder="再次输入密码" autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="agreement"
          valuePropName="checked"
          rules={[
            {
              validator: (_rule, value) => (value ? Promise.resolve() : Promise.reject(new Error('请阅读并同意用户协议'))),
            },
          ]}
        >
          <Checkbox>
            <span style={{ fontSize: 13 }}>我已阅读并同意用户协议与隐私政策，且承诺年满 14 周岁</span>
          </Checkbox>
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={submitting}>
            注册并登录
          </Button>
        </Form.Item>
      </Form>

      <div style={{ textAlign: 'center', fontSize: 13 }}>
        <span style={{ color: 'var(--color-text-secondary)' }}>已有账号？</span> <Link to="/login">返回登录</Link>
      </div>
    </Card>
  );
}
