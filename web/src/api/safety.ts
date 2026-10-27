import { api } from '@/api/request';
import type { PageResult } from '@/types/api';
import type {
  Appeal,
  AppealCreateReq,
  ContentLabel,
  MinorStatus,
  Ward,
} from '@/types/safety';

/** 模块 18：C 端青少年模式、申诉与 AI 标识。 */
export const safetyApi = {
  minor: {
    status: () => api.get<MinorStatus>('/users/me/minor-mode', undefined, { skipErrorToast: true }),
    toggle: (body: { enabled: boolean; password?: string; userId?: string }) =>
      api.put<MinorStatus>('/users/me/minor-mode', body),
    heartbeat: () => api.post<{ usedMinutes: number; remainingMinutes: number }>(
      '/users/me/minor-mode/heartbeat', undefined, { skipErrorToast: true },
    ),
    wards: () => api.get<Ward[]>('/users/me/wards'),
    bind: (minorUserId: string) => api.post<Ward>(`/users/me/wards/${encodeURIComponent(minorUserId)}`),
  },

  appeals: {
    create: (body: AppealCreateReq) => api.post<Appeal>('/appeals', body),
    my: (pageNum = 1, pageSize = 10) =>
      api.get<PageResult<Appeal>>('/appeals/my', { pageNum, pageSize }),
    detail: (id: string) => api.get<Appeal>(`/appeals/${encodeURIComponent(id)}`),
  },

  labels: {
    get: (contentType: string, contentId: string) =>
      api.get<ContentLabel>('/content/labels', { contentType, contentId }),
  },
};
