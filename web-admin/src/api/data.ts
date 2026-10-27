import { del, get, post } from './client';
import type { Id, PageResult } from './types';

/**
 * 数据平台后台接口（模块 17）。
 *
 * baseURL 已经是 `/api/v1/admin`，所以这里只写相对路径；
 * 报表走 `/data-reports` 而不是 PRD 的 `/reports`——`/admin/reports` 已被模块 09
 * 的「举报处理」占用，两套接口不能共用一个前缀。
 */

export interface MetricCard {
  metricId: string;
  name: string;
  value: number | null;
  unit: string;
  source: string;
  available: boolean;
  base: number | null;
  delta: number | null;
  deltaPct: number | null;
  direction: string;
  status: string;
}

export interface RealtimeDashboard {
  date: string;
  updatedAt: string;
  delaySeconds: number;
  redisAvailable: boolean;
  cards: MetricCard[];
  ingest: Record<string, number>;
  topSongs: Array<{ songId: string; name: string; playCnt: number }>;
  topRooms: Array<{ roomId: string; name: string; messageCnt?: number; enterCnt?: number }>;
  compareNote: string;
}

export interface TrendPoint {
  date: string;
  value: number;
  dimensionValue?: string;
}

export interface TrendSeries {
  dimensionValue: string;
  points: TrendPoint[];
}

export interface TrendVO {
  metricId: string;
  metricName: string;
  start: string;
  end: string;
  dimension: string;
  source: string;
  points: TrendPoint[];
  series: TrendSeries[];
}

export interface DrillVO {
  metricId: string;
  metricName: string;
  date: string;
  dimension: string;
  total: number;
  values: Array<{ dimensionValue: string; value: number }>;
}

export interface MetricDef {
  id: Id;
  metricId: string;
  metricName: string;
  category: string;
  definition: string;
  calculation: string;
  dataSource: string;
  dimensions: string;
  updateFreq: string;
  owner: string;
  version: string;
  status: number;
  updateTime?: string;
}

export interface EventDef {
  id: Id;
  eventName: string;
  eventCnName: string;
  domain: string;
  triggerDesc: string;
  properties: string;
  priority: number;
  isSampled: number;
  sampleRate: number;
  owner: string;
  version: string;
  status: number;
  updateTime?: string;
}

export interface FunnelStepDef {
  eventName: string;
  label: string;
  filters: Record<string, unknown> | null;
  minCount: number;
}

export interface FunnelPreset {
  key: string;
  name: string;
  windowHours: number;
  description: string;
  steps: FunnelStepDef[];
}

export interface FunnelStepResult {
  index: number;
  eventName: string;
  label: string;
  users: number;
  stepRate: number;
  overallRate: number;
  dropOffUsers: number;
  dropOffRate: number;
  highlight?: boolean;
}

export interface FunnelVO {
  name: string;
  start: string;
  end: string;
  subject: string;
  windowHours: number;
  dimension: string | null;
  dimensionValue: string | null;
  scannedRows: number;
  truncated: boolean;
  totalUsers: number;
  overallConversion: number;
  steps: FunnelStepResult[];
  highlightStep: number;
  groups: Array<{ dimensionValue: string; steps: FunnelStepResult[]; totalUsers: number; overallConversion: number }>;
  sampleUsers: Array<Record<string, unknown>>;
  definitions: FunnelStepDef[];
}

export interface RetentionRow {
  date: string;
  cohortSize: number;
  retained: Record<string, number | null>;
  values: Record<string, number | null>;
}

export interface RetentionVO {
  type: string;
  typeLabel: string;
  start: string;
  end: string;
  group: string | null;
  groupLabel: string;
  rows: RetentionRow[];
  curve: Array<{ day: number; retained: number; sample: number; rate: number | null }>;
  groups: Array<Record<string, unknown>>;
  alerts: Array<{ metric: string; message: string; severity: string }>;
  scannedRows: number;
  truncated: boolean;
  note: string;
}

export interface ReportSummary {
  id: string;
  reportType: string;
  reportTypeLabel: string;
  reportDate: string;
  title: string;
  status: number;
  sentCount: number;
  generatedAt: string;
  content?: Record<string, unknown>;
}

export interface ReportDetail {
  reportType: string;
  reportTypeLabel: string;
  reportDate: string;
  start: string;
  end: string;
  title: string;
  content: Record<string, unknown>;
  status: number;
  id?: string;
}

export interface QualityReport {
  checkDate: string;
  total: number;
  passed: number;
  failed: number;
  score: number;
  blocked: boolean;
  criticalFailed: number;
  health: string;
  items?: Array<Record<string, unknown>>;
  issues: Array<{ table: string; ruleType: string; ruleExpr: string; actualValue: unknown; severity: string }>;
  checkTime?: string;
  costMs?: number;
}

export interface ReconciliationItem {
  item: string;
  dwValue: number;
  bizValue: number;
  deviation: number;
  threshold: number;
  passed: boolean;
  checkDate?: string;
}

export interface ReconciliationVO {
  checkDate: string;
  total: number;
  passed: number;
  failed: number;
  items: ReconciliationItem[];
  alerts: Array<{ item: string; deviation: number; threshold: number; message: string }>;
  costMs: number;
  idempotent: boolean;
}

export interface DataLineage {
  id: Id;
  tableName: string;
  columnName: string;
  upstreamTable: string;
  upstreamColumn: string;
  transform: string;
  owner?: string;
}

export const dashboardApi17 = {
  realtime: () => get<RealtimeDashboard>('/dashboard/realtime'),
  trend: (params: { metricId: string; start?: string; end?: string; dimension?: string }) =>
    get<TrendVO>('/dashboard/trend', params),
  drill: (params: { metricId: string; dimension?: string; value?: string; date?: string }) =>
    get<DrillVO>('/dashboard/drill', params),
};

export const metricApi = {
  list: (params: { pageNum?: number; pageSize?: number; category?: string; status?: number }) =>
    get<PageResult<MetricDef>>('/metrics', params),
  detail: (metricId: string) => get<Record<string, unknown>>(`/metrics/${metricId}`),
  approve: (metricId: string, body: { approve: boolean; comment?: string; effectiveDate?: string }) =>
    post<Record<string, unknown>>(`/metrics/${metricId}/approve`, body),
  retire: (metricId: string) => del<void>(`/metrics/${metricId}`),
  history: (metricId: string) => get<Array<Record<string, unknown>>>(`/metrics/${metricId}/history`),
};

export const eventApi = {
  list: (params: { pageNum?: number; pageSize?: number; keyword?: string; domain?: string; status?: number }) =>
    get<PageResult<EventDef>>('/events', params),
  validate: (body: { event: string; properties?: Record<string, unknown> }) =>
    post<Record<string, unknown>>('/events/validate', body),
};

export const analysisApi = {
  presets: () => get<FunnelPreset[]>('/analysis/funnel/presets'),
  funnel: (body: Record<string, unknown>) => post<FunnelVO>('/analysis/funnel', body),
  retention: (params: { type?: string; start?: string; end?: string; group?: string }) =>
    get<RetentionVO>('/analysis/retention', params),
};

export const dataReportApi = {
  types: () => get<Record<string, string>>('/data-reports/types'),
  list: (params: { type?: string; start?: string; end?: string }) =>
    get<ReportSummary[]>('/data-reports', params),
  detail: (type: string, date?: string) =>
    get<ReportDetail>(`/data-reports/${type}`, date ? { date } : undefined),
  generate: (type: string, date?: string) =>
    post<ReportDetail>(`/data-reports/${type}/generate${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  dispatch: (type: string, date?: string) =>
    post<Record<string, unknown>>(`/data-reports/${type}/dispatch${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  exportUrl: (type: string, date?: string) =>
    `/api/v1/admin/data-reports/${type}/export${date ? `?date=${encodeURIComponent(date)}` : ''}`,
  subscription: () => get<Record<string, unknown>>('/data-reports/subscription'),
  subscribe: (params: { reportTypes: string; frequency?: string; channel?: string }) => {
    const query = [
      `reportTypes=${encodeURIComponent(params.reportTypes)}`,
      `frequency=${encodeURIComponent(params.frequency ?? 'DAILY')}`,
      `channel=${encodeURIComponent(params.channel ?? 'IN_APP')}`,
    ].join('&');
    return post<Record<string, unknown>>(`/data-reports/subscription?${query}`);
  },
  unsubscribe: () => del<Record<string, unknown>>('/data-reports/subscription'),
};

export const qualityApi = {
  report: (date?: string) => get<QualityReport>('/data-quality', date ? { date } : undefined),
  run: (date?: string) =>
    post<QualityReport>(`/data-quality/run${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  reconciliation: (date?: string) =>
    get<ReconciliationItem[]>('/reconciliation', date ? { date } : undefined),
  runReconciliation: (date?: string) =>
    post<ReconciliationVO>(`/reconciliation/run${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  lineage: (table?: string) => get<DataLineage[]>('/lineage', table ? { table } : undefined),
  etl: (date?: string) =>
    post<Record<string, unknown>>(`/data-platform/etl${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  backfill: (start: string, end?: string) =>
    post<Record<string, unknown>>(
      `/data-platform/etl/backfill?start=${encodeURIComponent(start)}${end ? `&end=${encodeURIComponent(end)}` : ''}`,
    ),
};
