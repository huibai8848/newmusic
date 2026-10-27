import { useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, Card, Form, Input, Space, Spin, Tag, Typography, message } from 'antd';
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons';
import { authApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { Captcha } from '@/api/types';

interface LoginForm {
  username: string;
  password: string;
  captchaCode: string;
  totpCode?: string;
}

export function LoginPage(): JSX.Element {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [form] = Form.useForm<LoginForm>();
  const [captcha, setCaptcha] = useState<Captcha | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const refreshCaptcha = useCallback(async () => {
    setCaptchaLoading(true);
    try {
      setCaptcha(await authApi.captcha());
      form.setFieldValue('captchaCode', '');
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setCaptchaLoading(false);
    }
  }, [form]);

  useEffect(() => {
    void refreshCaptcha();
  }, [refreshCaptcha]);

  const submit = async (values: LoginForm): Promise<void> => {
    if (!captcha) {
      message.error('验证码尚未加载，请点击图片重试');
      return;
    }
    setSubmitting(true);
    try {
      const result = await login({
        username: values.username.trim(),
        password: values.password,
        captchaId: captcha.captchaId,
        captchaCode: values.captchaCode.trim(),
        totpCode: values.totpCode?.trim() || undefined,
      });
      message.success(`欢迎回来，${result.admin.realName || result.admin.username}`);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/dashboard', { replace: true });
    } catch (error) {
      message.error(errorMessage(error));
      await refreshCaptcha();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-shell">
      <Card className="login-card" bordered={false}>
        <div className="login-title">
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            MusicLive 管理后台
          </Typography.Title>
          <Typography.Text type="secondary">
            仅限授权运营人员使用；登录行为会被完整记录
          </Typography.Text>
        </div>

        <Form form={form} layout="vertical" onFinish={(values) => void submit(values)} requiredMark={false}>
          <Form.Item
            name="username"
            label="登录名"
            rules={[{ required: true, message: '请输入登录名' }]}
          >
            <Input prefix={<UserOutlined />} autoComplete="username" placeholder="superadmin" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
          </Form.Item>

          <Form.Item label="图形验证码" required>
            <Space align="start" style={{ width: '100%' }}>
              <Form.Item
                name="captchaCode"
                noStyle
                rules={[{ required: true, message: '请输入验证码' }]}
              >
                <Input prefix={<SafetyCertificateOutlined />} maxLength={4} placeholder="4 位验证码" />
              </Form.Item>
              <button type="button" className="captcha-button" onClick={() => void refreshCaptcha()}>
                {captchaLoading || !captcha ? (
                  <Spin size="small" />
                ) : (
                  <img src={captcha.image} alt="图形验证码，点击刷新" />
                )}
              </button>
            </Space>
            {captcha?.code && (
              <div style={{ marginTop: 8 }}>
                <Tag color="orange">演示环境验证码：{captcha.code}</Tag>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  生产环境设置 ADMIN_CAPTCHA_ECHO=false 后不再回显
                </Typography.Text>
              </div>
            )}
          </Form.Item>

          <Form.Item
            name="totpCode"
            label="动态验证码（未开启 TOTP 可留空）"
            rules={[{ pattern: /^\d{6}$/, message: 'TOTP 为 6 位数字' }]}
          >
            <Input maxLength={6} inputMode="numeric" placeholder="6 位数字" />
          </Form.Item>

          <Button type="primary" htmlType="submit" block loading={submitting} size="large">
            登录
          </Button>
        </Form>

        <Typography.Paragraph type="secondary" style={{ marginTop: 20, marginBottom: 0, fontSize: 12 }}>
          演示账号：superadmin / admin123（已开启 TOTP，演示密钥 JBSWY3DPEHPK3PXP）；生产必须重置。
        </Typography.Paragraph>
      </Card>
    </div>
  );
}
