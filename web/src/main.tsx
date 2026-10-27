import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { App as AntdApp, ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import 'antd/dist/reset.css';
import '@/styles/global.less';

import { router } from '@/router';
import { useThemeStore } from '@/stores/themeStore';
import { useUserStore } from '@/stores/userStore';
import { useWsStore } from '@/stores/wsStore';
import { registerFeedback } from '@/utils/feedback';
import { AudioEngine } from '@/components/player/AudioEngine';
import { OfflineBanner } from '@/components/common/OfflineBanner';
import { PwaUpdatePrompt } from '@/components/common/PwaUpdatePrompt';

/**
 * 应用入口（模块 02 §4）。
 *
 * 结构：ConfigProvider(主题/中文) → AntD App(静态方法上下文) → QueryClientProvider → RouterProvider。
 * `<AudioEngine>` 挂在路由树**之外**，切页/懒加载都不会销毁 `<audio>`（AC-11-005-2）。
 */

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 曲库数据变化不频繁：1 分钟内复用缓存，减少重复请求
      staleTime: 60_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

/** 把 AntD 的 message/notification/modal 实例交给非组件代码（请求层）使用 */
function FeedbackBridge(): null {
  const { message, notification, modal } = AntdApp.useApp();
  useEffect(() => {
    registerFeedback({ message, notification, modal });
  }, [message, notification, modal]);
  return null;
}

function Root(): JSX.Element {
  const resolved = useThemeStore((s) => s.resolved);
  const refreshUser = useUserStore((s) => s.refreshUser);
  const accessToken = useUserStore((s) => s.accessToken);
  const startWs = useWsStore((s) => s.start);
  const stopWs = useWsStore((s) => s.stop);

  useEffect(() => {
    // 启动时用本地 Token 拉一次最新资料（角色/权限码可能已被后台调整）
    void refreshUser();
  }, [refreshUser]);

  useEffect(() => {
    // 登录态驱动实时连接：登出即断开，登录/注册后自动建立（模块 04）
    if (accessToken) startWs();
    else stopWs();
  }, [accessToken, startWs, stopWs]);

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: resolved === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: '#1677ff',
          borderRadius: 8,
        },
      }}
    >
      <AntdApp>
        <FeedbackBridge />
        <OfflineBanner />
        <PwaUpdatePrompt />
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
          <AudioEngine />
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  );
}

const container = document.getElementById('root');
if (!container) {
  throw new Error('#root 节点不存在，请检查 index.html');
}

createRoot(container).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
