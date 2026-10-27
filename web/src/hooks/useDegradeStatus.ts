import { useQuery } from '@tanstack/react-query';
import { systemApi } from '@/api/system';
import type { PublicDegradeStatus } from '@/utils/degrade';

/** 降级状态轮询键：公告与导航过滤共用一份缓存 */
export const degradeStatusKey = ['system', 'degrade-status'] as const;

/**
 * 拉取 C 端降级状态。
 *
 * 30 秒轮询 + 窗口聚焦刷新：运维在后台把等级切回来后，用户最多等半分钟就能看到功能恢复。
 * 请求失败不重试、不弹 Toast——状态接口不可用时按「未降级」处理，正常浏览不被拦住。
 */
export function useDegradeStatus(): PublicDegradeStatus | undefined {
  const query = useQuery({
    queryKey: degradeStatusKey,
    queryFn: systemApi.degradeStatus,
    staleTime: 20_000,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    retry: false,
  });
  return query.data;
}
