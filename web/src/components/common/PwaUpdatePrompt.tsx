import { useEffect } from 'react';
import { App, Button } from 'antd';
import { registerSW } from 'virtual:pwa-register';

/**
 * PWA 更新提示（模块 12 AC-21-110）。
 *
 * `registerType: 'prompt'` 下 Service Worker 不会自动接管，因此这里拿到的是
 * 「已下载好新版本、等你刷新」的回调。刻意不自动刷新：刷新会中断正在播放的音频，
 * 由用户自己挑时机（BR-21-005 的播放连续性优先）。
 */
export function PwaUpdatePrompt(): null {
  const { notification } = App.useApp();

  useEffect(() => {
    const updateSW = registerSW({
      immediate: true,
      onNeedRefresh() {
        const key = 'ml-pwa-update';
        notification.info({
          key,
          message: '发现新版本',
          description: '当前播放不会被打断，选择合适的时间刷新即可。',
          duration: 0,
          placement: 'bottomRight',
          btn: (
            <Button
              type="primary"
              size="small"
              onClick={() => {
                notification.destroy(key);
                void updateSW(true);
              }}
            >
              立即刷新
            </Button>
          ),
        });
      },
      onOfflineReady() {
        notification.success({
          message: '应用已可离线打开',
          description: '页面外壳已缓存；音频仍需要网络（离线下载属 P2）。',
          placement: 'bottomRight',
        });
      },
    });
  }, [notification]);

  return null;
}
