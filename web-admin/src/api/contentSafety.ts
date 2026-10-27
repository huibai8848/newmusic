import { del, get, post, put } from './client';
import type {
  AppealStats,
  AuditRule,
  ContentAppeal,
  CopyrightComplaint,
  CopyrightInfo,
  CreditLog,
  Id,
  ImportPreview,
  PageResult,
  SensitiveWord,
  UserCredit,
  UserRestriction,
  WhiteWord,
  WordImportResult,
  WordTestResult,
  WordVersion,
} from './types';

/** 模块 18：内容安全 / 合规后台接口（全部挂在 /api/v1/admin 下）。 */

export interface WordQuery {
  pageNum?: number;
  pageSize?: number;
  level?: number;
  category?: string;
  source?: string;
  status?: number;
  keyword?: string;
}

export interface WordPayload {
  word: string;
  level?: number;
  category?: string;
  source?: string;
  remark?: string;
  status?: number;
}

export interface WordImportPayload {
  content: string;
  level?: number;
  category?: string;
  source?: string;
  remark?: string;
}

export interface RulePayload {
  ruleName: string;
  ruleType?: string;
  pattern: string;
  scene?: string;
  action?: string;
  priority?: number;
  status?: number;
  remark?: string;
}

export interface RestrictPayload {
  type?: number;
  level?: number;
  reason: string;
  reasonCategory?: string;
  days?: number;
  relatedContentType?: string;
  relatedContentId?: Id;
  relatedReportId?: Id;
  remark?: string;
}

function exportUrl(path: string, params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  });
  const suffix = query.toString();
  return suffix ? `${path}?${suffix}` : path;
}

export const safetyApi = {
  words: {
    page: (params: WordQuery) => get<PageResult<SensitiveWord>>('/sensitive-words', params),
    create: (body: WordPayload) => post<SensitiveWord>('/sensitive-words', body),
    update: (id: Id, body: WordPayload) => put<SensitiveWord>(`/sensitive-words/${id}`, body),
    remove: (id: Id) => del<void>(`/sensitive-words/${id}`),
    previewImport: (body: WordImportPayload) =>
      post<ImportPreview>('/sensitive-words/import/preview', body),
    importWords: (body: WordImportPayload) =>
      post<WordImportResult>('/sensitive-words/import', body),
    exportUrl: (params: { level?: number; confirm?: string }) =>
      exportUrl('/api/v1/admin/sensitive-words/export', params),
    test: (body: { text: string; scene?: string }) =>
      post<WordTestResult>('/sensitive-words/test', body),
    reload: () => post<{ success: boolean; wordCount: number; version?: number }>('/sensitive-words/reload'),
    versions: () => get<WordVersion[]>('/sensitive-words/versions'),
    rollback: (version: number) =>
      post<{ rolledBackTo: number; wordCount: number }>(`/sensitive-words/versions/${version}/rollback`),
    stats: () => get<Record<string, unknown>>('/sensitive-words/stats'),
  },

  whites: {
    list: () => get<WhiteWord[]>('/white-words'),
    create: (body: { word: string; reason?: string }) => post<WhiteWord>('/white-words', body),
    remove: (id: Id) => del<void>(`/white-words/${id}`),
  },

  rules: {
    list: () => get<AuditRule[]>('/audit-rules'),
    create: (body: RulePayload) => post<AuditRule>('/audit-rules', body),
    update: (id: Id, body: RulePayload) => put<AuditRule>(`/audit-rules/${id}`, body),
    remove: (id: Id) => del<void>(`/audit-rules/${id}`),
  },

  textCheck: (body: {
    text: string;
    contentType?: string;
    contentId?: Id;
    userId?: Id;
    scene?: string;
  }) => post<WordTestResult>('/audit/text-check', body),

  appeals: {
    page: (params: { pageNum?: number; pageSize?: number; status?: number; targetType?: string; overdue?: boolean }) =>
      get<PageResult<ContentAppeal>>('/appeals', params),
    detail: (id: Id) => get<ContentAppeal>(`/appeals/${id}`),
    stats: (days = 30) => get<AppealStats>('/appeals/stats', { days }),
    handle: (id: Id, body: { decision: 'APPROVE' | 'REJECT'; result: string; restoreCredit?: boolean }) =>
      post<ContentAppeal>(`/appeals/${id}/handle`, body),
  },

  users: {
    restrictions: (id: Id) => get<UserRestriction[]>(`/users/${id}/restrictions`),
    restrictionHistory: (id: Id) => get<UserRestriction[]>(`/users/${id}/restrictions/history`),
    restrict: (id: Id, body: RestrictPayload) => post<UserRestriction>(`/users/${id}/restrict`, body),
    release: (id: Id, rid: Id, reason: string) =>
      del<UserRestriction>(`/users/${id}/restrict/${rid}`, { reason }),
    permanentBan: (id: Id, body: { reason: string; confirm: string; reasonCategory?: string }) =>
      post<UserRestriction>(`/users/${id}/ban-permanent`, body),
    credit: (id: Id) => get<UserCredit>(`/users/${id}/credit`),
    creditLogs: (id: Id) => get<CreditLog[]>(`/users/${id}/credit/logs`),
    adjustCredit: (id: Id, body: { changeScore: number; reason: string }) =>
      post<UserCredit>(`/users/${id}/credit/adjust`, body),
    minor: {
      status: (id: Id) => get<Record<string, unknown>>(`/users/${id}/minor-protection`),
      save: (id: Id, body: {
        isMinor?: boolean;
        dailyLimitMinutes?: number;
        disableStartHour?: number;
        disableEndHour?: number;
        guardianUserId?: Id;
        mode?: boolean;
      }) => put<Record<string, unknown>>(`/users/${id}/minor-protection`, body),
      // 后台绑定监护人走 query 参数，不是 JSON body
      bindGuardian: (id: Id, guardianUserId: Id) =>
        post<Record<string, unknown>>(
          `/users/${id}/minor-protection/guardian?guardianUserId=${encodeURIComponent(String(guardianUserId))}`,
        ),
      wards: (id: Id) => get<Record<string, unknown>[]>(`/users/${id}/wards`),
    },
  },

  copyrights: {
    page: (params: { pageNum?: number; pageSize?: number; status?: number; keyword?: string }) =>
      get<PageResult<CopyrightInfo>>('/copyrights', params),
    save: (body: Record<string, unknown>) => post<CopyrightInfo>('/copyrights', body),
    update: (body: Record<string, unknown>) => put<CopyrightInfo>('/copyrights', body),
    expiring: (days = 30) => get<CopyrightInfo[]>('/copyrights/expiring', { days }),
    stats: () => get<Record<string, unknown>>('/copyrights/stats'),
    check: () => post<Record<string, unknown>>('/copyrights/check'),
  },

  complaints: {
    page: (params: { pageNum?: number; pageSize?: number; status?: number }) =>
      get<PageResult<CopyrightComplaint>>('/complaints', params),
    detail: (id: Id) => get<CopyrightComplaint>(`/complaints/${id}`),
    handle: (id: Id, body: { action: string; result: string; notify?: boolean }) =>
      post<CopyrightComplaint>(`/complaints/${id}/handle`, body),
  },

  audit: {
    queueStats: () => get<Record<string, unknown>>('/audit/queue-stats'),
    stats: (days = 7) => get<Record<string, unknown>>('/audit/stats', { days }),
    slaStats: () => get<Record<string, unknown>>('/audit/sla-stats'),
    dailyReport: () => get<Record<string, unknown>>('/audit/daily-report'),
  },
};
