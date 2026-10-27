import { useEffect, useState } from 'react';
import styles from './OfflineBanner.module.less';

/**
 * 离线提示条（模块 12 FR-21-009）。
 *
 * `navigator.onLine` 只能判断「有没有网络接口」，不能判断真的能上网；
 * 这里只做「断网了」的提醒，真正的网络错误仍由播放器/请求层处理。
 */
export function OfflineBanner(): JSX.Element | null {
  const [online, setOnline] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine));

  useEffect(() => {
    const goOnline = (): void => setOnline(true);
    const goOffline = (): void => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, []);

  if (online) return null;
  return (
    <div className={styles.banner} role="status" aria-live="polite">
      网络连接已断开：已缓存的页面仍可浏览，网络恢复后可继续播放
    </div>
  );
}
