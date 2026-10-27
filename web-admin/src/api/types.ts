/** 后端 Long 超过 JS 安全整数时会序列化为字符串 */
export type Id = string | number;

export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
  pages: number;
  hasNext: boolean;
  truncated: boolean;
}

export interface AdminUser {
  id: Id;
  username: string;
  realName?: string;
  email?: string;
  phoneMasked?: string;
  status: number;
  totpEnabled: number;
  forceChangePassword?: number;
  passwordUpdateTime?: string;
  lastLoginTime?: string;
  lastLoginIp?: string;
  allowIpList?: string;
  remark?: string;
  createTime?: string;
  roles: string[];
  perms: string[];
}

export interface LoginResult {
  admin: AdminUser;
  expiresIn: number;
  refreshExpiresIn: number;
  forceChangePassword: boolean;
  passwordExpired: boolean;
  totpEnabled: boolean;
}

export interface Captcha {
  captchaId: string;
  image: string;
  expiresIn: number;
  /** 仅演示/验收环境（ADMIN_CAPTCHA_ECHO=true）返回 */
  code?: string;
}

export interface TrendPoint {
  date: string;
  value: number;
}

export interface Dashboard {
  totalUsers: number;
  newUsersToday: number;
  activeUsersToday: number;
  totalSongs: number;
  onlineSongs: number;
  pendingAuditTasks: number;
  escalatedAuditTasks: number;
  pendingReports: number;
  highPriorityReports: number;
  activeRooms: number;
  totalRooms: number;
  todayPlayCount: number;
  todayNewPlaylists: number;
  userTrend: TrendPoint[];
  playTrend: TrendPoint[];
}

export interface CUser {
  id: Id;
  username: string;
  nickname: string;
  avatar?: string;
  phoneMasked?: string;
  email?: string;
  gender?: number;
  userType?: number;
  status?: number;
  banReason?: string;
  banUntil?: string;
  registerIp?: string;
  lastLoginTime?: string;
  lastLoginIp?: string;
  vipLevel?: number;
  forceChangePassword?: number;
  createTime?: string;
}

export interface Song {
  id: Id;
  name: string;
  subtitle?: string;
  artistId?: Id;
  artistName?: string;
  albumId?: Id;
  albumName?: string;
  cover?: string;
  language?: string;
  genre?: string;
  duration?: number;
  releaseDate?: string;
  audioStandard?: string;
  audioHigh?: string;
  audioLossless?: string;
  copyrightStatus?: number;
  auditStatus?: number;
  status?: number;
  playCount?: number;
  version?: number;
  createTime?: string;
}

export interface Artist {
  id: Id;
  name: string;
  alias?: string;
  avatar?: string;
  cover?: string;
  gender?: number;
  country?: string;
  birthday?: string;
  debutDate?: string;
  description?: string;
  styles?: string;
  songCount?: number;
  albumCount?: number;
  firstLetter?: string;
  status?: number;
  createTime?: string;
  version?: number;
}

export interface Album {
  id: Id;
  name: string;
  artistId: Id;
  artistName?: string;
  cover?: string;
  publishDate?: string;
  company?: string;
  description?: string;
  language?: string;
  songCount?: number;
  status?: number;
  createTime?: string;
  version?: number;
}

export interface Room {
  id: Id;
  roomNo: string;
  name: string;
  cover?: string;
  ownerId: Id;
  ownerNickname?: string;
  roomType: number;
  status: number;
  onlineCount: number;
  hotScore?: number;
  maxMembers?: number;
  notice?: string;
  themeTags?: string;
  currentSongName?: string;
  currentArtistName?: string;
  lastActiveTime?: string;
  createTime?: string;
}

export interface RoomMessage {
  id: Id;
  roomId: Id;
  userId?: Id;
  content?: string;
  messageType?: number;
  auditStatus?: number;
  status?: number;
  createTime?: string;
}

export interface AuditTask {
  id: Id;
  taskType: string;
  targetType: string;
  targetId: string;
  priority: number;
  status: number;
  submitterId?: Id;
  title?: string;
  contentSnapshot?: string;
  machineResult?: string;
  machineScore?: number;
  claimedBy?: Id;
  claimedAt?: string;
  claimExpireAt?: string;
  handledAt?: string;
  handleResult?: string;
  rejectReason?: string;
  escalated?: number;
  createTime?: string;
  version?: number;
}

export interface AuditRecord {
  id: Id;
  taskId: Id;
  auditorId?: Id;
  auditorName?: string;
  action: string;
  beforeStatus?: number;
  afterStatus?: number;
  reason?: string;
  createTime?: string;
}

export interface Report {
  id: Id;
  reporterId: Id;
  reporterName?: string;
  targetType: string;
  targetId: string;
  targetName?: string;
  reasonType?: string;
  reason?: string;
  evidenceUrls?: string;
  priority: number;
  status: number;
  handledBy?: Id;
  handledAt?: string;
  handleResult?: string;
  handleReason?: string;
  actionTaken?: string;
  mergedIntoId?: Id;
  createTime?: string;
}

export interface OperationLog {
  id: Id;
  operatorId?: Id;
  operatorName?: string;
  opType: string;
  targetType?: string;
  targetId?: string;
  description?: string;
  beforeSnapshot?: string;
  afterSnapshot?: string;
  ip?: string;
  result?: number;
  errorMsg?: string;
  traceId?: string;
  createTime?: string;
}

export interface LoginLog {
  id: Id;
  userId?: Id;
  username?: string;
  loginType: number;
  ip?: string;
  userAgent?: string;
  device?: string;
  result: number;
  failReason?: string;
  suspicious: number;
  createTime?: string;
}

export interface SysConfig {
  id: Id;
  configKey: string;
  configValue?: string;
  valueType: number;
  configGroup?: string;
  description?: string;
  isPublic?: number;
  status?: number;
  updateTime?: string;
  updateBy?: Id;
}

export interface SysConfigHistory {
  id: Id;
  configKey: string;
  oldValue?: string;
  newValue?: string;
  valueType: number;
  changeType: string;
  changedBy?: Id;
  changedByName?: string;
  remark?: string;
  createTime?: string;
}

export interface AdminRole {
  id: Id;
  roleCode: string;
  roleName: string;
  description?: string;
  status?: number;
}

/** ---------------- 模块 11：搜索词典 ---------------- */

export interface DictWord {
  id: Id;
  word: string;
  frequency: number;
  pos: string;
  source: 'MANUAL' | 'AUTO_ARTIST' | 'AUTO_SONG' | string;
  refId?: Id;
  enabled: number;
  dictType: 'MAIN' | 'NET_SLANG' | 'REGION' | string;
  createTime?: string;
  updateTime?: string;
}

export interface DictStopword {
  id: Id;
  word: string;
  enabled: number;
  createTime?: string;
  updateTime?: string;
}

export interface DictPinyin {
  id: Id;
  word: string;
  pinyin: string;
  enabled: number;
  createTime?: string;
  updateTime?: string;
}

export interface DictSynonymWord {
  id: Id;
  word: string;
  isPrimary: number;
  enabled: number;
  sortOrder: number;
}

export interface DictSynonymGroup {
  groupNo: number;
  words: DictSynonymWord[];
  primaryWord: string;
  synonymType: 'EQUIVALENT' | 'ONE_WAY' | string;
  category: string;
  enabled: number;
  updateTime?: string;
}

export interface DictAnalyzeToken {
  token: string;
  startOffset: number;
  endOffset: number;
  type: string;
}

export interface DictAnalyzeResult {
  text: string;
  analyzer: string;
  source: 'ES' | 'LOCAL' | string;
  notice?: string;
  tokens: DictAnalyzeToken[];
}

export interface DictImportLineError {
  line: number;
  value: string;
  reason: string;
}

export interface DictImportResult {
  total: number;
  inserted: number;
  updated: number;
  skipped: number;
  failed: number;
  errors: DictImportLineError[];
}

export interface DictStatusStat {
  type: string;
  count: number;
  version?: string;
  lastModified?: string;
  lastPullTime?: string;
}

export interface DictSyncLog {
  syncType: string;
  status: string;
  itemCount?: number;
  costMs?: number;
  message?: string;
  createTime?: string;
}

export interface DictStatus {
  esEnabled: boolean;
  esAvailable: boolean;
  esUrl: string;
  esVersion?: string;
  searchEngine: string;
  pinyinPlugin: boolean;
  stconvertPlugin: boolean;
  warnings: string[];
  lastPullTime?: string;
  lastSync?: DictSyncLog;
  dicts: DictStatusStat[];
  effectiveStatus: string;
  notice: string;
}

export interface DictSyncResult {
  scanned: number;
  created: number;
  updated: number;
  skipped: number;
  costMs: number;
  message: string;
}

export interface DictChangeLog {
  id: Id;
  dictType: string;
  changeType: string;
  targetId?: Id;
  beforeValue?: string;
  afterValue?: string;
  operatorId?: Id;
  operatorName?: string;
  remark?: string;
  result: string;
  createTime?: string;
}

// ---------------------------------------------------------------- 模块 18：内容安全

export interface SensitiveWord {
  id: Id;
  word: string;
  rawWord?: string;
  level: number;
  category?: string;
  source?: string;
  hitCount?: number;
  lastHitTime?: string;
  status: number;
  remark?: string;
  creatorId?: Id;
  version?: number;
  createTime?: string;
  updateTime?: string;
}

export interface WhiteWord {
  id: Id;
  word: string;
  reason?: string;
  creatorId?: Id;
  createTime?: string;
}

export interface AuditRule {
  id: Id;
  ruleName: string;
  ruleType?: string;
  pattern: string;
  scene?: string;
  action?: string;
  priority?: number;
  status: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface WordVersion {
  id: Id;
  version: number;
  wordCount: number;
  operatorId?: Id;
  changeDesc?: string;
  createTime?: string;
}

export interface WordMatch {
  word: string;
  level: number;
  category?: string;
  start: number;
  end: number;
}

export interface RuleHit {
  ruleName: string;
  action: string;
  pattern?: string;
}

export interface WordTestResult {
  text: string;
  normalized: string;
  matches: WordMatch[];
  rules: RuleHit[];
  wordVersion?: number;
  fallback?: boolean;
  decision: 'PASS' | 'REVIEW' | 'REJECT';
  riskLevel?: number;
}

export interface ImportPreview {
  total: number;
  newCount: number;
  duplicateCount: number;
  invalidCount: number;
  invalidLines?: string[];
}

export interface WordImportResult {
  inserted: number;
  skipped: number;
  version?: number;
  wordCount?: number;
  invalidLines?: string[];
}

export interface ContentAppeal {
  id: Id;
  userId: Id;
  targetType: 'CONTENT' | 'ACCOUNT';
  targetId: string;
  reason: string;
  evidenceImages?: string;
  status: number;
  result?: string;
  reply?: string;
  handledBy?: Id;
  handledAt?: string;
  originalOperatorId?: Id;
  slaDeadline?: string;
  createTime?: string;
  updateTime?: string;
}

export interface AppealStats {
  total: number;
  approved: number;
  rejected: number;
  pending: number;
  misjudgeRate: number;
  overdue: number;
}

export interface UserRestriction {
  id: Id;
  userId: Id;
  type: number;
  level: number;
  reason: string;
  reasonCategory?: string;
  relatedContentType?: string;
  relatedContentId?: Id;
  relatedReportId?: Id;
  startTime?: string;
  endTime?: string;
  status: number;
  operatorId?: Id;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

export interface UserCredit {
  userId: Id;
  score: number;
  violationCount90d?: number;
  lastViolationTime?: string;
  lastRecoverTime?: string;
  updateTime?: string;
}

export interface CreditLog {
  id: Id;
  userId: Id;
  changeScore: number;
  beforeScore: number;
  afterScore: number;
  reason?: string;
  relatedType?: string;
  relatedId?: Id;
  createTime?: string;
}

export interface CopyrightInfo {
  id: Id;
  songId: Id;
  copyrightOwner: string;
  licenseType?: string;
  licenseRegion?: string;
  licenseStart?: string;
  licenseEnd?: string;
  licenseScope?: string;
  contractNo?: string;
  contractFile?: string;
  status: number;
  createTime?: string;
  updateTime?: string;
}

export interface CopyrightComplaint {
  id: Id;
  complaintNo: string;
  complainantName?: string;
  complainantType?: string;
  songIds?: string;
  description?: string;
  statement?: number;
  status: number;
  result?: string;
  uploaderNotified?: number;
  createTime?: string;
  handleTime?: string;
}

export interface MinorStatus {
  userId: string;
  minor: boolean;
  ageUnknown: boolean;
  defaultProtected: boolean;
  mode: boolean;
  forced: boolean;
  passwordSet: boolean;
  dailyLimitMinutes: number;
  usedMinutes: number;
  remainingMinutes: number;
  disableStart: string;
  disableEnd: string;
  inDisableWindow: boolean;
  allowedNow: boolean;
  socialAllowed: boolean;
  marketingAllowed: boolean;
  locationAllowed: boolean;
  guardianUserId?: string | null;
}
