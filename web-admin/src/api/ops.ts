import { del, get, post, put } from './client';
import type { Id, PageResult } from './types';

/**
 * 高可用与运维后台接口（模块 19）。
 *
 * baseURL 已是 `/api/v1/admin`，这里只写相对路径；所有运维能力都挂在 `/ops/**` 下，
 * 避免和模块 17 的 `/admin/metrics`（数据平台）撞车。
 *
 * 时间统一是后端 `LocalDateTime`（`2026-10-25T21:31:40.66`），页面用 dayjs 直接格式化。
 */

export interface OpsHealth {
  status: 'UP' | 'DOWN' | string;
  checks: Record<string, string>;
  degradeLevel: number;
  degradeLevelName: string;
  disabledFeatures: number;
  rateLimitRules: number;
  chaosRunning: boolean;
  circuitOpen: number;
  uptimeSeconds: number;
  timestamp: number;
}

export interface JvmMetrics {
  heapUsed: number;
  heapMax: number;
  heapUsageRatio: number;
  threads: number;
  peakThreads: number;
  gcCount: number;
  gcTimeMs: number;
}

export interface OpsMetrics {
  requestsTotal: number;
  errors5xxTotal: number;
  rateLimitedTotal: number;
  degradeBlockedTotal: number;
  chaosInjectedTotal: number;
  slowSqlTotal: number;
  qps: number;
  errorRate60s: number;
  p95Ms: number;
  p99Ms: number;
  requests60s: number;
  errors60s: number;
  rateLimited60s: number;
  slowSql60s: number;
  redisAvailable: boolean;
  degradeLevel: number;
  degradeLevelName: string;
  chaosRunning: boolean;
  circuitOpen: number;
  wsConnections: number;
  wsRooms: number;
  uptimeSeconds: number;
  jvm: JvmMetrics;
  dbPool: Record<string, number | string>;
  disk: Record<string, number | string>;
  routes: RouteStat[];
  timestamp: number;
}

export interface CircuitBreaker {
  name: string;
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | string;
  calls: number;
  failures: number;
  failureRate: number;
  failureRateThreshold: number;
  minimumCalls: number;
  openUntil: number;
  openMillis: number;
  openTotal: number;
  halfOpenCalls: number;
  lastTransition: string;
  lastTransitionAt: number | null;
}

export interface RateLimitRule {
  id: Id;
  resource: string;
  matchPattern: string;
  httpMethod?: string | null;
  dimension: 'GLOBAL' | 'IP' | 'USER' | string;
  threshold: number;
  windowSeconds: number;
  enabled: number;
  whitelistIps?: string | null;
  description?: string | null;
  operatorId?: Id | null;
  version?: number;
  createTime?: string;
  updateTime?: string;
  blockedTotal: number;
  allowedTotal: number;
  redisAvailable: boolean;
  localFallbackEnabled: boolean;
}

export interface RateLimitUpdate {
  threshold?: number;
  windowSeconds?: number;
  dimension?: string;
  httpMethod?: string;
  enabled?: number;
  whitelistIps?: string;
  description?: string;
}

export interface DegradeSwitch {
  switchKey: string;
  switchName: string;
  enabled: number;
  degradeLevel: number;
  description?: string | null;
  operatorId?: Id | null;
  version?: number;
  updateTime?: string;
  activeAtCurrentLevel?: boolean;
  currentLevel?: number;
}

export interface DegradeStatus {
  level: number;
  levelName: string;
  switches: DegradeSwitch[];
}

export interface PublicDegradeStatus {
  level: number;
  levelName: string;
  degraded: boolean;
  disabledFeatures: string[];
  banner: string | null;
  updatedAt: number;
}

export interface AlertRule {
  id: Id;
  ruleName: string;
  metric: string;
  operator: 'GT' | 'GE' | 'LT' | 'LE' | string;
  thresholdValue: number;
  durationSeconds: number;
  severity: 'P0' | 'P1' | 'P2' | 'P3' | string;
  enabled: number;
  runbookKey?: string | null;
  description?: string | null;
  createTime?: string;
  updateTime?: string;
}

export interface AlertRecord {
  id: Id;
  ruleId: Id;
  alertName: string;
  severity: string;
  instance?: string;
  summary?: string;
  metricValue: number;
  thresholdValue: number;
  status: 'firing' | 'resolved' | 'silenced' | string;
  fireTime: string;
  resolveTime?: string | null;
  handlerId?: Id | null;
  handlerName?: string | null;
  handleNote?: string | null;
  dedupKey?: string;
  createTime?: string;
}

export interface AlertSilence {
  id: Id;
  ruleName?: string | null;
  severity?: string | null;
  untilTime: string;
  reason?: string | null;
  operatorId?: Id | null;
  createTime?: string;
}

export interface AlertOverview {
  firing: number;
  last24h: number;
  rules: number;
  silences: number;
  timestamp: number;
}

export interface AlertEvaluation {
  evaluated: number;
  fired: number;
  silenced: number;
  resolved: number;
  records: AlertRecord[];
  timestamp: number;
}

export interface RouteStat {
  route: string;
  requests: number;
  errors: number;
  errorRate: number;
  p95Ms: number;
  avgMs: number;
  lastStatus: number;
}

export interface ThreadPoolStat {
  name: string;
  active: number;
  poolSize: number;
  core: number;
  max: number;
  queueSize: number;
  queueRemaining: number;
  completed: number;
  largestPoolSize: number;
  rejectedPolicy: string;
  usageRatio: number;
}

export interface MetricAggregate {
  metricName: string;
  avgValue: number;
  maxValue: number;
  samples: number;
}

export interface MetricSnapshot {
  id: Id;
  metricName: string;
  metricValue: number;
  tags?: string;
  snapshotTime: string;
}

export interface TraceRecord {
  traceId: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  userId?: Id | null;
  ip?: string | null;
  error?: string | null;
  timestamp: number;
}

export interface TraceResult {
  items: TraceRecord[];
  stats: { capacity: number; filled: number; recorded: number; dropped: number; sampleRate: number };
}

export interface TraceQuery {
  traceId?: string;
  userId?: string;
  path?: string;
  status?: number;
  minDurationMs?: number;
  limit?: number;
}

export interface PublishRecord {
  id: Id;
  version: string;
  service: string;
  environment: string;
  publishType: string;
  gitCommit?: string | null;
  status: 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK' | string;
  publisher?: string | null;
  startTime?: string;
  endTime?: string | null;
  remark?: string | null;
  rollbackVersion?: string | null;
  createTime?: string;
}

export interface BackupRecord {
  id: Id;
  backupType: string;
  status: string;
  sizeBytes?: number | null;
  location?: string | null;
  checksum?: string | null;
  verifyStatus?: string | null;
  tableCount?: number | null;
  rowCount?: number | null;
  startedAt?: string;
  finishedAt?: string | null;
  operatorId?: Id | null;
  message?: string | null;
  createTime?: string;
}

export interface CostRecord {
  id: Id;
  costDate: string;
  category: string;
  service: string;
  amountCent: number;
  budgetCent: number;
  detail?: string | null;
  createTime?: string;
}

export interface CostOverview {
  currency: string;
  unit: string;
  todayAmountCent: number;
  todayBudgetCent: number;
  todayRemainingCent: number;
  todayUsageRatio: number;
  alert: boolean;
  last7DaysCent: number;
  last30DaysCent: number;
  daily: Array<{ date: string; amount: number; budget: number }>;
  byCategory: Array<{ category: string; amount: number }>;
  byService: Array<{ service: string; amount: number }>;
  todayRecords: CostRecord[];
  timestamp: number;
}

export interface ChaosExperiment {
  id: Id;
  experimentName: string;
  target: string;
  faultType: string;
  hypothesis?: string | null;
  blastRadius: number;
  durationSeconds: number;
  status: 'RUNNING' | 'FINISHED' | 'STOPPED' | string;
  result?: string | null;
  findings?: string | null;
  operatorId?: Id | null;
  startTime?: string;
  endTime?: string | null;
  createTime?: string;
}

export interface CacheKeyItem {
  key: string;
  type: string;
  ttlSeconds: number;
}

export interface CacheInspection {
  prefix: string;
  count: number;
  truncated: boolean;
  redisAvailable: boolean;
  keys: CacheKeyItem[];
}

export interface OpsRunbook {
  id: Id;
  runbookKey: string;
  title: string;
  severity: string;
  symptom: string;
  steps: string;
  owner?: string | null;
  createTime?: string;
  updateTime?: string;
}

export interface OpsOverview {
  health: OpsHealth;
  metrics: OpsMetrics;
  alerts: AlertOverview;
  degrade: PublicDegradeStatus;
  rateLimits: RateLimitRule[];
  circuits: CircuitBreaker[];
  chaos: ChaosExperiment | null;
  trace: TraceResult['stats'];
}

export const opsApi = {
  overview: () => get<OpsOverview>('/ops/overview'),
  health: () => get<OpsHealth>('/ops/health'),

  rateLimits: () => get<RateLimitRule[]>('/ops/rate-limits'),
  updateRateLimit: (resource: string, body: RateLimitUpdate) =>
    put<RateLimitRule>(`/ops/rate-limits/${encodeURIComponent(resource)}`, body),
  resetRateLimitCounters: () => post<void>('/ops/rate-limits/counters/reset'),
  circuits: () => get<CircuitBreaker[]>('/ops/circuit-breakers'),

  degrade: () => get<DegradeStatus>('/ops/degrade'),
  setDegradeLevel: (level: number, reason?: string) =>
    post<{ level: number; levelName: string; switchesChanged: number }>('/ops/degrade/level', { level, reason }),
  degradeSwitches: () => get<DegradeSwitch[]>('/ops/degrade-switches'),
  updateDegradeSwitch: (key: string, enabled: boolean, remark?: string) =>
    put<DegradeSwitch>(`/ops/degrade-switches/${encodeURIComponent(key)}`, { enabled, remark }),

  alertRules: () => get<AlertRule[]>('/ops/alerts/rules'),
  updateAlertRule: (id: Id, body: Partial<AlertRule>) => put<AlertRule>(`/ops/alerts/rules/${id}`, body),
  alertRecords: (params: { status?: string; severity?: string; page?: number; size?: number }) =>
    get<PageResult<AlertRecord>>('/ops/alerts/records', params),
  handleAlert: (id: Id, note?: string) => post<AlertRecord>(`/ops/alerts/records/${id}/handle`, { note }),
  evaluateAlerts: () => post<AlertEvaluation>('/ops/alerts/evaluate'),
  alertOverview: () => get<AlertOverview>('/ops/alerts/overview'),
  alertSilences: () => get<AlertSilence[]>('/ops/alerts/silences'),
  createSilence: (body: { ruleName?: string; severity?: string; durationMinutes: number; reason?: string }) =>
    post<AlertSilence>('/ops/alerts/silences', body),
  cancelSilence: (id: Id) => del<void>(`/ops/alerts/silences/${id}`),

  metrics: () => get<OpsMetrics>('/ops/metrics'),
  prometheus: () => get<string>('/ops/metrics/prometheus'),
  routes: (limit = 50) => get<RouteStat[]>('/ops/metrics/routes', { limit }),
  threadPools: () => get<ThreadPoolStat[]>('/ops/metrics/threadpools'),
  metricHistory: (params: { minutes?: number; metric?: string; limit?: number }) =>
    get<{ aggregate: MetricAggregate[]; items?: MetricSnapshot[] }>('/ops/metrics/history', params),
  traces: (params: TraceQuery) => get<TraceResult>('/ops/traces', params),

  publishRecords: (params: { status?: string; service?: string; page?: number; size?: number }) =>
    get<PageResult<PublishRecord>>('/ops/publish', params),
  createPublish: (body: {
    version: string;
    service?: string;
    environment?: string;
    publishType?: string;
    gitCommit?: string;
    remark?: string;
  }) => post<PublishRecord>('/ops/publish', body),
  // 后端 finish/stop/cache 用的是 @RequestParam，参数必须拼在 URL 上（post 辅助函数只收 body）
  finishPublish: (id: Id, success: boolean, message?: string) =>
    post<PublishRecord>(
      `/ops/publish/${id}/finish?success=${success}${message ? `&message=${encodeURIComponent(message)}` : ''}`,
    ),
  rollbackPublish: (id: Id, body: { targetVersion?: string; reason?: string }) =>
    post<{ rolledBack: PublishRecord; rollbackRecord: PublishRecord; targetVersion: string }>(
      `/ops/publish/${id}/rollback`,
      body,
    ),

  backups: (params: { status?: string; page?: number; size?: number }) =>
    get<PageResult<BackupRecord>>('/ops/backups', params),
  createBackup: (body: { backupType?: string; message?: string }) => post<BackupRecord>('/ops/backups', body),
  verifyBackup: (id: Id) =>
    post<{ id: Id; verifyStatus: string; expected?: string; actual?: string; error?: string }>(
      `/ops/backups/${id}/verify`,
    ),
  previewBackup: (id: Id, limit = 50) => get<string[]>(`/ops/backups/${id}/preview`, { limit }),

  costs: () => get<CostOverview>('/ops/costs'),
  costRecords: (params: { days?: number; category?: string; limit?: number }) =>
    get<CostRecord[]>('/ops/costs/records', params),
  addCost: (body: { category: string; service: string; amountCent: number; budgetCent?: number; detail?: string }) =>
    post<CostRecord>('/ops/costs/records', body),

  chaosExperiments: (params: { status?: string; page?: number; size?: number }) =>
    get<PageResult<ChaosExperiment>>('/ops/chaos/experiments', params),
  chaosCurrent: () => get<ChaosExperiment | null>('/ops/chaos/current'),
  startChaos: (body: {
    experimentName: string;
    faultType: string;
    target?: string;
    blastRadius?: number;
    durationSeconds?: number;
    hypothesis?: string;
  }) => post<ChaosExperiment>('/ops/chaos/experiments', body),
  stopChaos: (reason?: string) =>
    post<{ stopped: number }>(`/ops/chaos/stop${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`),

  runbooks: (severity?: string) => get<OpsRunbook[]>('/ops/runbooks', { severity }),
  runbook: (key: string) => get<OpsRunbook>(`/ops/runbooks/${encodeURIComponent(key)}`),

  cacheKeys: (prefix = 'ml:', limit = 100) => get<CacheInspection>('/ops/cache/keys', { prefix, limit }),
  deleteCacheKeys: (prefix: string, confirm: string) =>
    del<{ deleted: number }>(
      `/ops/cache/keys?prefix=${encodeURIComponent(prefix)}&confirm=${encodeURIComponent(confirm)}`,
    ),

  configs: () => get<Record<string, string>>('/ops/configs'),
  updateConfig: (key: string, configValue: string, remark?: string) =>
    put<void>(`/ops/configs/${encodeURIComponent(key)}`, { configValue, remark }),
};
