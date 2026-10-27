import { api } from '@/api/request';
import type { LoginReq, LoginVO, RegisterReq, UserUpdateReq, UserVO } from '@/types/api';

export const authApi = {
  login: (data: LoginReq) => api.post<LoginVO>('/auth/login', data, { skipErrorToast: true }),
  register: (data: RegisterReq) => api.post<UserVO>('/auth/register', data, { skipErrorToast: true }),
  logout: () => api.post<void>('/auth/logout'),
  me: () => api.get<UserVO>('/users/me', undefined, { skipErrorToast: true }),
  updateMe: (data: UserUpdateReq) => api.put<UserVO>('/users/me', data),
};
