import { api } from '@/api/request';
import type { PublicDegradeStatus } from '@/utils/degrade';

/**
 * 模块 19：C 端系统状态（公开接口，未登录也可调用）。
 *
 * 降级状态只暴露等级、公告与关闭的功能列表，不包含内部阈值；失败时静默，
 * 由页面保持正常形态——状态接口抖动不应该让用户看到一个错误 Toast。
 */
export const systemApi = {
  degradeStatus: () =>
    api.get<PublicDegradeStatus>('/system/degrade-status', undefined, { skipErrorToast: true }),
};
