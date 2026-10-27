#!/usr/bin/env node
/**
 * 模块 19 验收脚本 —— 高可用与运维（P0 + P1）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-19.mjs
 *
 * 前置：
 *   * 应用已启动（8081 业务端口 / 8082 管理端口），已执行 sql/19-ops-seed.sql；
 *   * 后台验证码回显开启（ADMIN_CAPTCHA_ECHO=true），与 verify-09/14/15/17 相同；
 *   * Redis 6379 / MySQL 3306 可连，Node 22+（全局 fetch），零第三方依赖。
 *
 * 覆盖：
 *   §1 健康检查：聚合健康、C 端公开状态、8082 liveness/readiness/prometheus
 *   §2 限流：8 条规则、热更新、429/Retry-After、白名单、USER 维度、总开关、计数
 *   §3 降级：18 个开关、手动开关、1/2/5 级、公开状态、恢复、非法等级、审计
 *   §4 告警：10 条规则、11 个预案、静默→触发→处理、非法静默/规则
 *   §5 混沌：非法故障、安全上限、单实验互斥、本地限流兜底、到期自动结束
 *   §6 指标/链路：快照、路由、线程池、Prometheus、内部抓取白名单、trace 检索
 *   §7 发布/备份：状态机、回滚记录、清单 SHA-256、校验与预览
 *   §8 成本：概况、唯一键、分类校验、明细
 *   §9 配置中心：8 个运维配置、热更新、变更历史、回滚、类型校验
 *   §10 缓存巡检：ml: 前缀、禁止 ranking:、两段前缀 + confirm、精确清理
 *   §11 种子与权限；§12 共享实例快照
 *
 * 共享实例安全：
 *   * MySQL 3306 上还有 emp_db / music_db，所有 SQL 都带 newmusic. 限定符；
 *   * Redis 6379 的 db0 有其它项目的 ranking:* 键，只读写 ml:*；
 *   * 口令只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给客户端，不进 argv、不落盘；
 *   * 开头/结尾比对 ranking:* 与 emp_db/music_db 表数，证明未破坏共享实例。
 *
 * 可重复执行：所有探针账号/IP/前缀/成本服务名每轮唯一；结束时恢复
 * 降级等级、SEARCH 限流规则、运维配置开关与采样率，并清理探针 Redis 键/成本行。
 * 发布、备份、告警、混沌记录属于审计证据，保留在库中。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

// ---------------------------------------------------------------- 配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ADMIN_BASE = process.env.ADMIN_API_BASE || `${API_BASE}/admin`;
const MANAGE_BASE = process.env.MANAGE_BASE || 'http://127.0.0.1:8082';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const SUPER_USER = process.env.M09_SUPER_USER || 'superadmin';
const SUPER_PASS = process.env.M09_SUPER_PASSWORD || 'admin123';
const SUPER_TOTP_SECRET = process.env.M09_SUPER_TOTP_SECRET || 'JBSWY3DPEHPK3PXP';
const USER_PASSWORD = 'Probe@123456';

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
const PROBE_SERVICE = `v19-probe-${RUN_TAG}`;
const CACHE_PREFIX = `ml:v19:probe:${RUN_TAG}:`;
const CACHE_KEY_1 = `${CACHE_PREFIX}one`;
const CACHE_KEY_2 = `${CACHE_PREFIX}two`;
const DECREASE_REASON = `v19 verify ${RUN_TAG}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.${Math.floor(Math.random() * 200) + 20}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

const IP_HOT = syntheticIp();
const IP_COUNTER = syntheticIp();
const IP_WHITELIST = syntheticIp();
const IP_USER_A = syntheticIp();
const IP_USER_B = syntheticIp();
const IP_SWITCH_A = syntheticIp();
const IP_SWITCH_B = syntheticIp();
const IP_CHAOS = syntheticIp();
const IP_FALLBACK = syntheticIp();
const IP_FOREIGN = syntheticIp();

const qs = (params) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
};

// ---------------------------------------------------------------- 断言输出

let pass = 0;
let fail = 0;
let skipped = 0;
const failures = [];
const skipNotes = [];

function check(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log(`  \x1b[32m✓\x1b[0m ${name}`);
  } else {
    fail += 1;
    failures.push(name);
    console.log(`  \x1b[31m✗\x1b[0m ${name}${extra === undefined ? ''
      : ` → ${typeof extra === 'string' ? extra : JSON.stringify(extra)}`}`);
  }
}

function skip(name, why) {
  skipped += 1;
  skipNotes.push(`${name}（${why}）`);
  console.log(`  \x1b[90m○\x1b[0m ${name} → 跳过：${why}`);
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

function note(message) {
  console.log(`  \x1b[33m•\x1b[0m ${message}`);
}

// ---------------------------------------------------------------- HTTP / Cookie

class Session {
  constructor(label) {
    this.label = label;
    this.jar = new Map();
  }
}

function setCookieLines(res) {
  if (typeof res.headers.getSetCookie === 'function') {
    const values = res.headers.getSetCookie();
    if (values && values.length) return values;
  }
  const raw = res.headers.get('set-cookie');
  if (!raw) return [];
  return raw.split(/,(?=\s*ml_admin_)/).map((s) => s.trim()).filter(Boolean);
}

function applySetCookies(session, res) {
  for (const line of setCookieLines(res)) {
    const parts = line.split(';').map((s) => s.trim());
    const pair = parts.shift() || '';
    const eq = pair.indexOf('=');
    if (eq < 0) continue;
    const name = pair.slice(0, eq);
    const value = pair.slice(eq + 1);
    const attrs = {};
    for (const attr of parts) {
      const i = attr.indexOf('=');
      if (i < 0) attrs[attr.toLowerCase()] = '';
      else attrs[attr.slice(0, i).toLowerCase()] = attr.slice(i + 1);
    }
    const maxAge = attrs['max-age'];
    if (value === '' || (maxAge !== undefined && Number(maxAge) === 0)) {
      session.jar.delete(name);
    } else {
      session.jar.set(name, { value, path: attrs.path || '/' });
    }
  }
}

function cookieHeader(session, pathname) {
  const parts = [];
  for (const [name, cookie] of session.jar.entries()) {
    if (pathname.startsWith(cookie.path)) parts.push(`${name}=${cookie.value}`);
  }
  return parts.join('; ');
}

async function http(method, url, { session, body, headers, xff, token } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (xff) h['X-Forwarded-For'] = xff;
  if (token) h.Authorization = `Bearer ${token}`;
  if (session) {
    const cookie = cookieHeader(session, new URL(url).pathname);
    if (cookie) h.Cookie = cookie;
  }
  const res = await fetch(url, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (session) applySetCookies(session, res);
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* Prometheus 纯文本 */
  }
  return { status: res.status, json, text, headers: res.headers };
}

const call = (method, path, opts = {}) => http(method, `${API_BASE}${path}`, opts);
const adminReq = (method, path, opts = {}) => http(method, `${ADMIN_BASE}${path}`, opts);
const codeOf = (res) => (res.json && typeof res.json.code === 'number' ? res.json.code : -1);
const dataOf = (res) => (res.json ? res.json.data : null);
const okOf = (res) => codeOf(res) === 0;
const msgOf = (res) => (res.json && typeof res.json.message === 'string' ? res.json.message : '');
const arr = (value) => (Array.isArray(value) ? value : []);

// ---------------------------------------------------------------- Redis / MySQL

function redis(...args) {
  const quote = (a) => {
    const s = String(a);
    return /[\s"]/.test(s) ? `"${s.replace(/"/g, '\\"')}"` : s;
  };
  const r = spawnSync(REDIS_CLI, [], { encoding: 'utf8', input: `${args.map(quote).join(' ')}\n` });
  if (r.error) throw new Error(`无法执行 redis-cli（${REDIS_CLI}）：${r.error.message}`);
  if (r.status !== 0) throw new Error(`redis-cli ${args.join(' ')} 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

// 本机 --scan 静默返回空，固定用 keys；只对 ml:/ranking: 前缀使用
const listKeys = (pattern) => {
  const out = redis('keys', pattern);
  return out === '' ? [] : out.replace(/\r/g, '').split('\n').map((s) => s.trim()).filter(Boolean);
};
const redisDel = (...keys) => {
  if (keys.length) redis('del', ...keys);
};

function mysql(sql) {
  const r = spawnSync(MYSQL_CLI, [
    '-u', MYSQL_USER, '-h', '127.0.0.1', '-P', process.env.MYSQL_PORT || '3306',
    '-D', 'newmusic', '-N', '-B', '--default-character-set=utf8mb4',
  ], { encoding: 'utf8', input: sql, env: { ...process.env, MYSQL_PWD } });
  if (r.error) throw new Error(`无法执行 mysql 客户端（${MYSQL_CLI}）：${r.error.message}`);
  if (r.status !== 0) throw new Error(`mysql 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

const mysqlScalar = (sql) => mysql(sql).split(/\r?\n/)[0] || '';
const mysqlCount = (sql) => Number(mysqlScalar(sql) || 0);
const q = (v) => `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，Redis 侧断言会跳过：${e.message}`);
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*').sort() : [];
const sharedTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- TOTP / 登录

function base32Decode(input) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = String(input).replace(/=+$/g, '').toUpperCase();
  let buffer = 0;
  let bits = 0;
  const out = [];
  for (const ch of clean) {
    const idx = alphabet.indexOf(ch);
    if (idx < 0) throw new Error(`非法 Base32 字符：${ch}`);
    buffer = (buffer << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((buffer >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

function totp(secret, when = Date.now()) {
  const counter = Math.floor(when / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary = ((digest[offset] & 0x7f) << 24)
    | ((digest[offset + 1] & 0xff) << 16)
    | ((digest[offset + 2] & 0xff) << 8)
    | (digest[offset + 3] & 0xff);
  return String(binary % 1000000).padStart(6, '0');
}

async function adminLogin(xff) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const session = new Session('v19-admin');
    const cap = await adminReq('GET', '/auth/captcha', { session, xff });
    const captcha = dataOf(cap);
    if (!captcha || !captcha.captchaId || !captcha.code) {
      throw new Error(`验证码回显不可用：请确认 ADMIN_CAPTCHA_ECHO=true（${cap.text}）`);
    }
    const res = await adminReq('POST', '/auth/login', {
      session,
      xff,
      body: {
        username: SUPER_USER,
        password: SUPER_PASS,
        captchaId: captcha.captchaId,
        captchaCode: captcha.code,
        totpCode: totp(SUPER_TOTP_SECRET),
      },
    });
    if (okOf(res)) return session;
    if (codeOf(res) !== 80016) throw new Error(`后台登录失败：${res.text}`);
    await sleep(1200);
  }
  throw new Error('后台登录失败（TOTP 重试后仍被拒绝）');
}

async function registerAndLogin() {
  const account = `v19probe${RUN_TAG}`;
  const reg = await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password: USER_PASSWORD, nickname: account },
  });
  if (!okOf(reg)) throw new Error(`注册 ${account} 失败：${reg.text}`);
  const login = await call('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account, password: USER_PASSWORD },
  });
  if (!okOf(login) || !dataOf(login)?.accessToken) {
    throw new Error(`登录 ${account} 失败：${login.text}`);
  }
  return {
    account,
    token: dataOf(login).accessToken,
    userId: String(dataOf(login).user.id),
  };
}

// ---------------------------------------------------------------- 状态

let admin = null;
let probe = null;
let originalSearch = null;
let originalConfigs = {};
let silenceId = null;
let alertRecordId = null;
let backupLocation = null;
let costAdded = false;
let traceId429 = '';

const num = (value) => (typeof value === 'number' ? value : Number(value));

// ---------------------------------------------------------------- 主流程

async function main() {
  if (!HAS_MYSQL) {
    throw new Error('缺少 MYSQL_PASSWORD，无法做数据库与共享实例断言');
  }
  note(`探针标签 ${RUN_TAG}，成本服务名 ${PROBE_SERVICE}`);

  admin = await adminLogin(syntheticIp());

  // 预检：把上一轮可能残留的降级/总开关/混沌恢复到正常，并记录原始值
  const configs0 = dataOf(await adminReq('GET', '/ops/configs', { session: admin })) || {};
  originalConfigs = { ...configs0 };
  await adminReq('POST', '/ops/chaos/stop', { session: admin, body: undefined });
  await adminReq('POST', '/ops/degrade/level', { session: admin, body: { level: 0, reason: 'v19 验收预检' } });
  const setConfig = (key, value, why) => adminReq('PUT', `/ops/configs/${key}`, {
    session: admin, body: { configValue: value, remark: why },
  });
  await setConfig('ops.rate.limit.enabled', 'true', 'v19 验收预检：打开限流总开关');
  await setConfig('ops.chaos.enabled', 'true', 'v19 验收预检：打开混沌开关');

  const rules0 = dataOf(await adminReq('GET', '/ops/rate-limits', { session: admin })) || [];
  originalSearch = rules0.find((r) => r.resource === 'SEARCH') || null;
  note(`SEARCH 原始阈值 ${originalSearch?.threshold}/${originalSearch?.windowSeconds}s 维度 ${originalSearch?.dimension}`);

  probe = await registerAndLogin();
  check('探针用户注册并登录（降级复位后）', Boolean(probe.token) && Boolean(probe.userId));

  // ================================================================ §1 健康检查
  section('§1 健康检查与公开状态');

  const healthRes = await adminReq('GET', '/ops/health', { session: admin });
  const health = dataOf(healthRes) || {};
  check('聚合健康检查可用', okOf(healthRes) && health.status === 'UP', healthRes.text);
  check('健康检查含 db/redis/ops 三项', health.checks?.db === 'UP'
    && (health.checks?.redis === 'UP' || health.checks?.redis === 'DEGRADED')
    && health.checks?.ops === 'UP', health.checks);
  check('健康检查带 8 条限流规则', num(health.rateLimitRules) === 8, health.rateLimitRules);
  check('健康检查带降级等级（0）', num(health.degradeLevel) === 0, health.degradeLevel);
  check('健康检查带熔断与运行时长',
    typeof health.circuitOpen === 'number' && typeof health.uptimeSeconds === 'number');

  const overviewRes = await adminReq('GET', '/ops/overview', { session: admin });
  const overview = dataOf(overviewRes) || {};
  check('运维总览一次返回 8 个块',
    okOf(overviewRes) && ['health', 'metrics', 'alerts', 'degrade', 'rateLimits', 'circuits', 'chaos', 'trace']
      .every((key) => key in overview), Object.keys(overview));

  const publicStatus = await call('GET', '/system/status');
  check('C 端存活探测公开可读', okOf(publicStatus) && dataOf(publicStatus)?.status === 'UP', publicStatus.text);

  const degradePublic = await call('GET', '/system/degrade-status');
  const dp = dataOf(degradePublic) || {};
  check('降级状态公开且不暴露内部字段', okOf(degradePublic)
    && dp.level === 0 && dp.degraded === false && dp.banner === null
    && arr(dp.disabledFeatures).length === 0
    && !('switches' in dp) && !('operatorId' in dp), degradePublic.text);

  const manageHealth = await http('GET', `${MANAGE_BASE}/actuator/health`);
  check('管理端口 /actuator/health 匿名可读', manageHealth.status === 200
    && manageHealth.json?.status === 'UP', manageHealth.text);
  const liveness = await http('GET', `${MANAGE_BASE}/actuator/health/liveness`);
  check('管理端口 liveness 探针', liveness.status === 200 && liveness.json?.status === 'UP', liveness.text);
  const readiness = await http('GET', `${MANAGE_BASE}/actuator/health/readiness`);
  check('管理端口 readiness 探针', readiness.status === 200 && readiness.json?.status === 'UP', readiness.text);
  const manageProm = await http('GET', `${MANAGE_BASE}/actuator/prometheus`,
    { headers: { Accept: 'text/plain' } });
  check('管理端口 prometheus 文本指标', manageProm.status === 200
    && manageProm.text.includes('ml_http_requests_total'));

  // ================================================================ §2 限流
  section('§2 接口限流（规则热更新 / 白名单 / 维度 / 总开关）');

  const rulesRes = await adminReq('GET', '/ops/rate-limits', { session: admin });
  const rules = arr(dataOf(rulesRes));
  check('限流规则共 8 条', okOf(rulesRes) && rules.length === 8, rules.length);
  const resources = rules.map((r) => r.resource).sort();
  check('规则资源覆盖 GLOBAL/PLAY/SEARCH/AI/COMMENT/POST/LOGIN',
    ['AI', 'COMMENT', 'GLOBAL', 'LOGIN', 'PLAY', 'PLAY_REPORT', 'POST', 'SEARCH']
      .every((name) => resources.includes(name)), resources);
  const searchRule = rules.find((r) => r.resource === 'SEARCH') || {};
  check('SEARCH 规则默认阈值 2400/60s、IP 维度', num(searchRule.threshold) === 2400
    && num(searchRule.windowSeconds) === 60 && searchRule.dimension === 'IP', searchRule);
  check('规则回显运行态（Redis 可用 + 本地兜底 + 计数）',
    searchRule.redisAvailable === true && searchRule.localFallbackEnabled === true
    && typeof searchRule.allowedTotal === 'number' && typeof searchRule.blockedTotal === 'number');

  const hotUpdate = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin,
    body: { threshold: 2, windowSeconds: 60, dimension: 'IP', whitelistIps: '' },
  });
  check('SEARCH 阈值热更新为 2/60s', okOf(hotUpdate) && num(dataOf(hotUpdate)?.threshold) === 2
    && num(dataOf(hotUpdate)?.version) > num(searchRule.version), hotUpdate.text);

  // 以上一次 update 已经 refresh()，无需等 3 秒
  const hot1 = await call('GET', '/search/hot', { xff: IP_HOT });
  const hot2 = await call('GET', '/search/hot', { xff: IP_HOT });
  const hot3 = await call('GET', '/search/hot', { xff: IP_HOT });
  check('窗口内前 2 次搜索放行', hot1.status === 200 && okOf(hot1)
    && hot2.status === 200 && okOf(hot2), `${hot1.status}/${hot2.status}`);
  check('第 3 次搜索被限流（HTTP 429 + 10004）',
    hot3.status === 429 && codeOf(hot3) === 10004, hot3.text);
  const retryAfter = Number(hot3.headers.get('retry-after') || 0);
  check('限流响应带 Retry-After 且 ≥1 秒', retryAfter >= 1, retryAfter);
  traceId429 = hot3.json?.traceId || '';

  const reset1 = await adminReq('POST', '/ops/rate-limits/counters/reset', { session: admin });
  check('限流命中计数可清零', okOf(reset1), reset1.text);
  await call('GET', '/search/hot', { xff: IP_COUNTER });
  const rulesAfterReset = arr(dataOf(await adminReq('GET', '/ops/rate-limits', { session: admin })));
  const searchAfterReset = rulesAfterReset.find((r) => r.resource === 'SEARCH') || {};
  check('放行计数随请求增长', num(searchAfterReset.allowedTotal) === 1
    && num(searchAfterReset.blockedTotal) === 0, searchAfterReset);

  const whitelistUpdate = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin,
    body: { threshold: 1, windowSeconds: 60, dimension: 'IP', whitelistIps: IP_WHITELIST },
  });
  check('规则级 IP 白名单写入', okOf(whitelistUpdate)
    && dataOf(whitelistUpdate)?.whitelistIps === IP_WHITELIST, whitelistUpdate.text);
  const white1 = await call('GET', '/search/hot', { xff: IP_WHITELIST });
  const white2 = await call('GET', '/search/hot', { xff: IP_WHITELIST });
  check('白名单 IP 超过阈值仍放行', white1.status === 200 && white2.status === 200
    && okOf(white1) && okOf(white2), `${white1.status}/${white2.status}`);

  const userDim = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin,
    body: { threshold: 1, windowSeconds: 60, dimension: 'USER', whitelistIps: '' },
  });
  check('SEARCH 切换为 USER 维度', okOf(userDim) && dataOf(userDim)?.dimension === 'USER', userDim.text);
  const user1 = await call('GET', '/search/hot', { token: probe.token, xff: IP_USER_A });
  const user2 = await call('GET', '/search/hot', { token: probe.token, xff: IP_USER_B });
  check('USER 维度跨 IP 累计（第 2 次 429）', user1.status === 200 && okOf(user1)
    && user2.status === 429 && codeOf(user2) === 10004, `${user1.status}/${user2.status}`);

  const backToIp = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin,
    body: { threshold: 2, windowSeconds: 60, dimension: 'IP', whitelistIps: '' },
  });
  check('SEARCH 恢复 IP 维度', okOf(backToIp), backToIp.text);

  const off = await adminReq('PUT', '/ops/configs/ops.rate.limit.enabled', {
    session: admin, body: { configValue: 'false', remark: 'v19 验收：关闭限流总开关' },
  });
  const off1 = await call('GET', '/search/hot', { xff: IP_SWITCH_A });
  const off2 = await call('GET', '/search/hot', { xff: IP_SWITCH_A });
  const off3 = await call('GET', '/search/hot', { xff: IP_SWITCH_A });
  check('总开关关闭后不拦截', okOf(off) && [off1, off2, off3].every((r) => r.status === 200 && okOf(r)),
    [off1.status, off2.status, off3.status]);

  const on = await adminReq('PUT', '/ops/configs/ops.rate.limit.enabled', {
    session: admin, body: { configValue: 'true', remark: 'v19 验收：恢复限流总开关' },
  });
  const on1 = await call('GET', '/search/hot', { xff: IP_SWITCH_B });
  const on2 = await call('GET', '/search/hot', { xff: IP_SWITCH_B });
  const on3 = await call('GET', '/search/hot', { xff: IP_SWITCH_B });
  check('总开关恢复后立即拦截', okOf(on) && on1.status === 200 && on2.status === 200
    && on3.status === 429 && codeOf(on3) === 10004,
  [on1.status, on2.status, on3.status]);

  const unknownRule = await adminReq('PUT', '/ops/rate-limits/NOT_A_RULE', {
    session: admin, body: { threshold: 1 },
  });
  check('未知限流规则返回 73001', codeOf(unknownRule) === 73001, unknownRule.text);
  const invalidRule = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin, body: { threshold: 0 },
  });
  check('非法阈值被参数校验拦截（10001）', codeOf(invalidRule) === 10001, invalidRule.text);

  // §3 之前恢复 SEARCH 原值，避免污染后续搜索探针
  const restoreSearchBody = {
    threshold: originalSearch?.threshold ?? 2400,
    windowSeconds: originalSearch?.windowSeconds ?? 60,
    dimension: originalSearch?.dimension ?? 'IP',
    httpMethod: originalSearch?.httpMethod ?? '',
    whitelistIps: originalSearch?.whitelistIps ?? '',
    description: originalSearch?.description ?? '搜索/联想/热搜',
  };
  const restoreSearch = await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin, body: restoreSearchBody,
  });
  check('SEARCH 规则恢复默认阈值', okOf(restoreSearch)
    && num(dataOf(restoreSearch)?.threshold) === num(restoreSearchBody.threshold), restoreSearch.text);
  await adminReq('POST', '/ops/rate-limits/counters/reset', { session: admin });

  // ================================================================ §3 降级
  section('§3 五级降级（开关 / 公开状态 / 恢复 / 审计）');

  const degrade0 = await adminReq('GET', '/ops/degrade', { session: admin });
  const d0 = dataOf(degrade0) || {};
  check('降级视图返回 18 个开关且等级为 0', okOf(degrade0)
    && num(d0.level) === 0 && arr(d0.switches).length === 18, `${d0.level}/${arr(d0.switches).length}`);
  const switchKeys = arr(d0.switches).map((s) => s.switchKey);
  check('开关包含只读兜底与合规例外', switchKeys.includes('feature.write')
    && switchKeys.includes('feature.safety'));

  const manualOff = await adminReq('PUT', '/ops/degrade-switches/feature.search.hot', {
    session: admin, body: { enabled: false, remark: 'v19 验收：手动关闭热搜' },
  });
  check('单个开关可独立关闭', okOf(manualOff) && num(dataOf(manualOff)?.enabled) === 0, manualOff.text);
  const manualHot = await call('GET', '/search/hot', { xff: syntheticIp() });
  check('关闭后热搜接口返回 90005（HTTP 200）',
    manualHot.status === 200 && codeOf(manualHot) === 90005, manualHot.text);
  const manualOn = await adminReq('PUT', '/ops/degrade-switches/feature.search.hot', {
    session: admin, body: { enabled: true, remark: 'v19 验收：恢复热搜' },
  });
  check('单个开关可独立恢复', okOf(manualOn) && num(dataOf(manualOn)?.enabled) === 1, manualOn.text);
  const restoredHot = await call('GET', '/search/hot', { xff: syntheticIp() });
  check('恢复后热搜接口可用', restoredHot.status === 200 && okOf(restoredHot), restoredHot.text);
  const missingSwitch = await adminReq('PUT', '/ops/degrade-switches/feature.nope', {
    session: admin, body: { enabled: false },
  });
  check('未知降级开关返回 73003', codeOf(missingSwitch) === 73003, missingSwitch.text);

  const level1 = await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 1, reason: DECREASE_REASON },
  });
  const l1 = dataOf(level1) || {};
  check('一键降级到 1 级并批量改写开关', okOf(level1) && num(l1.level) === 1
    && l1.levelName === '轻度降级' && num(l1.switchesChanged) >= 2, level1.text);
  const hotLevel1 = await call('GET', '/search/hot', { xff: syntheticIp() });
  check('1 级：热搜返回 90005 且带 X-Degrade-Level=1', codeOf(hotLevel1) === 90005
    && hotLevel1.headers.get('x-degrade-level') === '1', hotLevel1.text);
  const suggestLevel1 = await call('GET', '/search/suggest?keyword=jay', { xff: syntheticIp() });
  check('1 级：联想返回 90005', codeOf(suggestLevel1) === 90005, suggestLevel1.text);
  const public1 = dataOf(await call('GET', '/system/degrade-status')) || {};
  check('公开状态同步 1 级与关闭功能', public1.level === 1 && public1.degraded === true
    && arr(public1.disabledFeatures).includes('feature.search.hot')
    && arr(public1.disabledFeatures).includes('feature.search.suggest')
    && typeof public1.banner === 'string' && public1.banner.includes('轻度降级'), public1);
  const switches1 = arr(dataOf(await adminReq('GET', '/ops/degrade-switches', { session: admin })));
  const hotSwitch1 = switches1.find((s) => s.switchKey === 'feature.search.hot') || {};
  check('开关表同步 enabled=0 且标记当前等级', num(hotSwitch1.enabled) === 0
    && num(hotSwitch1.currentLevel) === 1, hotSwitch1);

  const level2 = await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 2, reason: `${DECREASE_REASON} L2` },
  });
  check('一键降级到 2 级', okOf(level2) && num(dataOf(level2)?.level) === 2, level2.text);
  const recommend2 = await call('GET', '/recommend/feed', { xff: syntheticIp() });
  check('2 级：推荐流返回 90005', codeOf(recommend2) === 90005, recommend2.text);
  const hotLevel2 = await call('GET', '/search/hot', { xff: syntheticIp() });
  check('2 级：1 级关闭的功能仍关闭', codeOf(hotLevel2) === 90005, hotLevel2.text);

  const level5 = await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 5, reason: `${DECREASE_REASON} L5` },
  });
  check('一键降级到 5 级（只读）', okOf(level5) && num(dataOf(level5)?.level) === 5, level5.text);
  const switches5 = arr(dataOf(await adminReq('GET', '/ops/degrade-switches', { session: admin })));
  const byKey = Object.fromEntries(switches5.map((s) => [s.switchKey, s]));
  check('5 级：全部写操作与注册关闭', num(byKey['feature.write']?.enabled) === 0
    && num(byKey['feature.user.register']?.enabled) === 0
    && num(byKey['feature.tracking']?.enabled) === 0, byKey['feature.write']);
  check('5 级：合规能力 feature.safety 永不关闭', num(byKey['feature.safety']?.enabled) === 1
    && num(byKey['feature.safety']?.degradeLevel) === 99, byKey['feature.safety']);

  const levelBack = await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 0, reason: `${DECREASE_REASON} 恢复` },
  });
  check('恢复 0 级正常', okOf(levelBack) && num(dataOf(levelBack)?.level) === 0, levelBack.text);
  const recommend0 = await call('GET', '/recommend/feed', { xff: syntheticIp() });
  check('恢复后推荐流可用', recommend0.status === 200 && okOf(recommend0), recommend0.text);
  const public0 = dataOf(await call('GET', '/system/degrade-status')) || {};
  check('公开状态恢复 normal（banner 置空）', public0.level === 0 && public0.degraded === false
    && public0.banner === null && arr(public0.disabledFeatures).length === 0, public0);

  const badLevel = await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 6, reason: 'v19 验收：非法等级' },
  });
  check('非法降级等级被参数校验拦截（10001）', codeOf(badLevel) === 10001, badLevel.text);

  check('降级事件落库（reason 可追溯）', mysqlCount(
    `SELECT COUNT(*) FROM newmusic.t_degrade_event WHERE reason LIKE ${q(`%${RUN_TAG}%`)} AND to_level IN (1,2,5,0)`) >= 4);

  // ================================================================ §4 告警
  section('§4 告警规则 / 静默 / 处理');

  const alertRulesRes = await adminReq('GET', '/ops/alerts/rules', { session: admin });
  const alertRules = arr(dataOf(alertRulesRes));
  check('告警规则共 10 条', okOf(alertRulesRes) && alertRules.length === 10, alertRules.length);
  check('规则覆盖 P0~P3 且带 runbook', ['P0', 'P1', 'P2'].every((s) => alertRules.some((r) => r.severity === s))
    && alertRules.every((r) => typeof r.runbookKey === 'string' && r.runbookKey.length > 0));
  const runbookKeys = new Set(arr(dataOf(await adminReq('GET', '/ops/runbooks', { session: admin })))
    .map((r) => r.runbookKey));
  check('规则引用的预案全部存在', alertRules.every((r) => runbookKeys.has(r.runbookKey)),
    alertRules.map((r) => r.runbookKey).filter((k) => !runbookKeys.has(k)));

  const runbooksRes = await adminReq('GET', '/ops/runbooks', { session: admin });
  check('应急预案共 11 个', okOf(runbooksRes) && arr(dataOf(runbooksRes)).length === 11);
  const p0Runbooks = arr(dataOf(await adminReq('GET', '/ops/runbooks?severity=P0', { session: admin })));
  check('预案支持按级别筛选（P0=5）', p0Runbooks.length === 5
    && p0Runbooks.every((r) => r.severity === 'P0'), p0Runbooks.map((r) => r.runbookKey));
  const appDown = dataOf(await adminReq('GET', '/ops/runbooks/app-all-down', { session: admin })) || {};
  check('预案详情含处置步骤', appDown.runbookKey === 'app-all-down'
    && typeof appDown.steps === 'string' && appDown.steps.includes('回滚'), appDown);
  const noRunbook = await adminReq('GET', '/ops/runbooks/nope', { session: admin });
  check('未知预案返回 73010', codeOf(noRunbook) === 73010, noRunbook.text);

  const alertOverview = dataOf(await adminReq('GET', '/ops/alerts/overview', { session: admin })) || {};
  check('告警概况字段完整', num(alertOverview.rules) === 10
    && typeof alertOverview.firing === 'number' && typeof alertOverview.last24h === 'number'
    && typeof alertOverview.silences === 'number', alertOverview);

  const recordsPage = dataOf(await adminReq('GET', '/ops/alerts/records?page=1&size=5', { session: admin })) || {};
  check('告警记录分页结构正确', Array.isArray(recordsPage.list)
    && typeof recordsPage.total === 'number' && num(recordsPage.pageSize) === 5, recordsPage);

  const badOperator = await adminReq('PUT', '/ops/alerts/rules/1000000000000019206', {
    session: admin,
    body: { thresholdValue: 1, operator: 'NOPE', durationSeconds: 5, severity: 'P1', enabled: 1, runbookKey: 'degrade' },
  });
  check('非法告警 operator 返回 73012', codeOf(badOperator) === 73012, badOperator.text);
  const badSeverity = await adminReq('PUT', '/ops/alerts/rules/1000000000000019206', {
    session: admin,
    body: { thresholdValue: 1, operator: 'GT', durationSeconds: 5, severity: 'P9', enabled: 1, runbookKey: 'degrade' },
  });
  check('非法告警级别返回 73012', codeOf(badSeverity) === 73012, badSeverity.text);

  const evaluated = await adminReq('POST', '/ops/alerts/evaluate', { session: admin });
  const evalData = dataOf(evaluated) || {};
  check('告警立即评估可用', okOf(evaluated) && num(evalData.evaluated) === 10
    && typeof evalData.fired === 'number' && typeof evalData.silenced === 'number', evalData);

  const badSilence1 = await adminReq('POST', '/ops/alerts/silences', {
    session: admin, body: { durationMinutes: 5, reason: 'v19 验收：无规则无级别' },
  });
  check('静默必须指定规则或级别（73012）', codeOf(badSilence1) === 73012, badSilence1.text);
  const badSilence2 = await adminReq('POST', '/ops/alerts/silences', {
    session: admin, body: { severity: 'P9', durationMinutes: 5 },
  });
  check('非法静默级别返回 73012', codeOf(badSilence2) === 73012, badSilence2.text);

  const silenceRes = await adminReq('POST', '/ops/alerts/silences', {
    session: admin,
    body: { ruleName: 'degrade_level_raised', durationMinutes: 2, reason: `v19 验收 ${RUN_TAG}` },
  });
  const silence = dataOf(silenceRes) || {};
  silenceId = silence.id;
  check('按规则名创建静默', okOf(silenceRes) && Boolean(silenceId)
    && silence.ruleName === 'degrade_level_raised' && Boolean(silence.untilTime), silenceRes.text);
  const silences = arr(dataOf(await adminReq('GET', '/ops/alerts/silences', { session: admin })));
  check('静默列表包含新建记录', silences.some((s) => String(s.id) === String(silenceId)));

  // degrade_level_raised: metric=degrade.level GT 1 for 5s → 等过防抖窗口。
  // 防抖起点是「第一次观察到指标超阈值的评估时刻」，不是改等级的时刻：调度器 5s 一轮，
  // 若只 sleep 6.2s，等到的那次评估可能才刚开始计时。先主动评估一次把起点钉在当下，
  // 再等一个完整的 duration，才能稳定触发。
  await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 2, reason: `${DECREASE_REASON} 告警探针` },
  });
  await adminReq('POST', '/ops/alerts/evaluate', { session: admin });
  await sleep(6200);
  await adminReq('POST', '/ops/alerts/evaluate', { session: admin });
  const silencedPage = dataOf(await adminReq(
    'GET', '/ops/alerts/records?status=silenced&size=100', { session: admin })) || {};
  const silencedRecord = arr(silencedPage.list)
    .find((r) => r.alertName === 'degrade_level_raised');
  alertRecordId = silencedRecord?.id || null;
  check('静默期产生 silenced 记录（可追溯）', Boolean(alertRecordId)
    && silencedRecord.severity === 'P1'
    && String(silencedRecord.summary || '').includes('静默中'), silencedRecord);

  const cancel = await adminReq('DELETE', `/ops/alerts/silences/${silenceId}`, { session: admin });
  check('静默可取消', okOf(cancel), cancel.text);
  silenceId = null;
  await adminReq('POST', '/ops/alerts/evaluate', { session: admin });
  await sleep(200);
  const firingPage = dataOf(await adminReq(
    'GET', '/ops/alerts/records?status=firing&size=100', { session: admin })) || {};
  const firingRecord = arr(firingPage.list).find((r) => String(r.id) === String(alertRecordId));
  check('静默取消后同一记录转为 firing', Boolean(firingRecord)
    && firingRecord.status === 'firing'
    && !String(firingRecord.summary || '').includes('静默中'), firingRecord);

  const handled = await adminReq('POST', `/ops/alerts/records/${alertRecordId}/handle`, {
    session: admin, body: { note: `v19 验收处理 ${RUN_TAG}` },
  });
  const handledData = dataOf(handled) || {};
  check('告警可处理为 resolved 并留痕', okOf(handled) && handledData.status === 'resolved'
    && String(handledData.handleNote || '').includes('v19 验收'), handledData);
  check('告警处理落库', mysqlCount(
    `SELECT COUNT(*) FROM newmusic.t_ops_alert_record WHERE id=${q(alertRecordId)} AND status='resolved'`) === 1);
  const handleMissing = await adminReq('POST', '/ops/alerts/records/999999999999999/handle', {
    session: admin, body: { note: 'x' },
  });
  check('处理不存在的告警返回 73007', codeOf(handleMissing) === 73007, handleMissing.text);

  await adminReq('POST', '/ops/degrade/level', {
    session: admin, body: { level: 0, reason: `${DECREASE_REASON} 告警探针恢复` },
  });
  await adminReq('POST', '/ops/alerts/evaluate', { session: admin });

  // ================================================================ §5 混沌
  section('§5 混沌实验（安全上限 / 互斥 / 本地兜底 / 自动结束）');

  const badFault = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-bad-${RUN_TAG}`, faultType: 'NOPE', blastRadius: 10, durationSeconds: 5 },
  });
  check('未知故障类型被拒绝（10001）', codeOf(badFault) === 10001, badFault.text);
  const badRadius = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-radius-${RUN_TAG}`, faultType: 'AI_TIMEOUT', blastRadius: 101, durationSeconds: 5 },
  });
  check('爆炸半径上限 100%（101 被拒绝）', codeOf(badRadius) === 10001, badRadius.text);

  const chaos1 = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: {
      experimentName: `v19-chaos-${RUN_TAG}`,
      faultType: 'AI_TIMEOUT',
      blastRadius: 100,
      durationSeconds: 9999,
      hypothesis: 'AI 超时不会打垮主链路',
    },
  });
  const chaos1Data = dataOf(chaos1) || {};
  check('启动混沌实验并按上限截断时长（120s）', okOf(chaos1) && chaos1Data.status === 'RUNNING'
    && num(chaos1Data.blastRadius) === 100 && num(chaos1Data.durationSeconds) === 120, chaos1Data);
  const current1 = dataOf(await adminReq('GET', '/ops/chaos/current', { session: admin })) || {};
  check('当前实验可查', String(current1.id) === String(chaos1Data.id)
    && current1.faultType === 'AI_TIMEOUT', current1);
  const secondChaos = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-second-${RUN_TAG}`, faultType: 'SLOW_DB', blastRadius: 1, durationSeconds: 10 },
  });
  check('同一时刻只允许一个实验（73005）', codeOf(secondChaos) === 73005, secondChaos.text);
  const stopped1 = await adminReq('POST', `/ops/chaos/stop${qs({ reason: 'v19 验收人工停止' })}`, {
    session: admin, body: undefined,
  });
  check('人工停止实验', okOf(stopped1) && num(dataOf(stopped1)?.stopped) >= 1, stopped1.text);
  const currentAfterStop = await adminReq('GET', '/ops/chaos/current', { session: admin });
  check('停止后无当前实验', okOf(currentAfterStop) && dataOf(currentAfterStop) === null,
    currentAfterStop.text);

  const chaosOff = await adminReq('PUT', '/ops/configs/ops.chaos.enabled', {
    session: admin, body: { configValue: 'false', remark: 'v19 验收：关闭混沌开关' },
  });
  const disabledStart = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-off-${RUN_TAG}`, faultType: 'AI_TIMEOUT', blastRadius: 1, durationSeconds: 5 },
  });
  check('混沌开关关闭时拒绝启动（73004）', okOf(chaosOff) && codeOf(disabledStart) === 73004, disabledStart.text);
  await adminReq('PUT', '/ops/configs/ops.chaos.enabled', {
    session: admin, body: { configValue: 'true', remark: 'v19 验收：恢复混沌开关' },
  });

  const slowDb = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-slowdb-${RUN_TAG}`, faultType: 'SLOW_DB', blastRadius: 1, durationSeconds: 20 },
  });
  check('SLOW_DB 故障类型可用', okOf(slowDb) && dataOf(slowDb)?.faultType === 'SLOW_DB', slowDb.text);
  await adminReq('POST', `/ops/chaos/stop${qs({ reason: 'v19 SlowDB 探针' })}`, { session: admin, body: undefined });

  // Redis 限流器故障 → 必须走单机滑动窗口（而不是放行）
  const redisChaos = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: {
      experimentName: `v19-redis-${RUN_TAG}`,
      faultType: 'REDIS_LIMITER_UNAVAILABLE',
      blastRadius: 100,
      durationSeconds: 60,
    },
  });
  check('REDIS_LIMITER_UNAVAILABLE 实验启动', okOf(redisChaos)
    && dataOf(redisChaos)?.faultType === 'REDIS_LIMITER_UNAVAILABLE', redisChaos.text);
  await adminReq('PUT', '/ops/rate-limits/SEARCH', {
    session: admin, body: { threshold: 2, windowSeconds: 60, dimension: 'IP', whitelistIps: '' },
  });
  const fb1 = await call('GET', '/search/hot', { xff: IP_FALLBACK });
  const fb2 = await call('GET', '/search/hot', { xff: IP_FALLBACK });
  const fb3 = await call('GET', '/search/hot', { xff: IP_FALLBACK });
  check('Redis 故障时单机窗口仍限流（200/200/429）',
    fb1.status === 200 && fb2.status === 200 && fb3.status === 429 && codeOf(fb3) === 10004,
    [fb1.status, fb2.status, fb3.status]);
  const rulesUnderChaos = arr(dataOf(await adminReq('GET', '/ops/rate-limits', { session: admin })));
  const searchUnderChaos = rulesUnderChaos.find((r) => r.resource === 'SEARCH') || {};
  check('Redis 本身仍可用（证明走的是混沌降级分支）', searchUnderChaos.redisAvailable === true,
    searchUnderChaos.redisAvailable);
  await adminReq('PUT', '/ops/rate-limits/SEARCH', { session: admin, body: restoreSearchBody });
  await adminReq('POST', `/ops/chaos/stop${qs({ reason: 'v19 Redis 兜底探针结束' })}`, {
    session: admin, body: undefined,
  });

  // 到期自动结束
  const autoChaos = await adminReq('POST', '/ops/chaos/experiments', {
    session: admin,
    body: { experimentName: `v19-auto-${RUN_TAG}`, faultType: 'AI_TIMEOUT', blastRadius: 1, durationSeconds: 1 },
  });
  const autoId = dataOf(autoChaos)?.id;
  await sleep(2800);
  const currentExpired = await adminReq('GET', '/ops/chaos/current', { session: admin });
  check('实验到点后不再作为当前实验', okOf(currentExpired) && dataOf(currentExpired) === null,
    currentExpired.text);
  const finishedPage = dataOf(await adminReq('GET', '/ops/chaos/experiments?status=FINISHED&size=50',
    { session: admin })) || {};
  const finished = arr(finishedPage.list).find((e) => String(e.id) === String(autoId));
  check('到期实验由调度落库为 FINISHED', Boolean(finished)
    && finished.status === 'FINISHED' && String(finished.result || '').includes('自动'), finished);
  check('混沌实验列表可查', Array.isArray(finishedPage.list) && typeof finishedPage.total === 'number');

  // ================================================================ §6 指标 / 链路
  section('§6 指标与链路（快照 / Prometheus / trace 检索）');

  const metrics = dataOf(await adminReq('GET', '/ops/metrics', { session: admin })) || {};
  check('指标快照包含 RED 与运行时字段',
    typeof metrics.requestsTotal === 'number' && metrics.requestsTotal > 0
    && typeof metrics.errorRate60s === 'number'
    && typeof metrics.p95Ms === 'number' && typeof metrics.p99Ms === 'number', metrics);
  check('指标快照包含 JVM/DB 池/磁盘', typeof metrics.jvm?.heapUsed === 'number'
    && typeof metrics.jvm?.heapUsageRatio === 'number'
    && metrics.dbPool?.type === 'Druid' && typeof metrics.dbPool?.max === 'number'
    && typeof metrics.disk?.availableRatio === 'number', metrics.dbPool);
  check('限流与降级拦截量已累计', num(metrics.rateLimitedTotal) >= 2
    && num(metrics.degradeBlockedTotal) >= 3, `${metrics.rateLimitedTotal}/${metrics.degradeBlockedTotal}`);
  check('混沌停止后 chaosRunning=false', metrics.chaosRunning === false, metrics.chaosRunning);

  const routes = arr(dataOf(await adminReq('GET', '/ops/metrics/routes?limit=100', { session: admin })));
  const hotRoute = routes.find((r) => r.route === '/api/v1/search/hot');
  check('路由维度指标覆盖搜索热点', Boolean(hotRoute)
    && num(hotRoute.requests) >= 5 && typeof hotRoute.p95Ms === 'number', hotRoute);
  const pools = arr(dataOf(await adminReq('GET', '/ops/metrics/threadpools', { session: admin })));
  check('线程池水位可读', Array.isArray(pools)
    && (pools.length === 0 || pools.every((p) => typeof p.name === 'string'
      && typeof p.poolSize === 'number' && typeof p.usageRatio === 'number')), pools.length);

  const prom = dataOf(await adminReq('GET', '/ops/metrics/prometheus', { session: admin }));
  check('后台 Prometheus 文本含标准指标', typeof prom === 'string'
    && prom.includes('ml_http_requests_total{status="4xx"}')
    && prom.includes('ml_rate_limited_total')
    && prom.includes('jvm_memory_used_bytes'), String(prom).slice(0, 120));
  // 接口 produces=text/plain：Accept 只写 application/json 会走到不可接受分支（拿不到正文），
  // 而拒绝分支返回的是 JSON 错误体，所以这里用 */* 让两种响应都能正常协商。
  const internal = await http('GET', `${API_BASE}/internal/ops/metrics`,
    { headers: { Accept: '*/*' } });
  check('内部抓取端点（本机回环）返回文本', internal.status === 200
    && internal.text.includes('ml_http_requests_total'));
  const foreign = await http('GET', `${API_BASE}/internal/ops/metrics`,
    { headers: { Accept: '*/*' }, xff: IP_FOREIGN });
  check('内部抓取端点拒绝非白名单来源（403）', foreign.status === 403, foreign.status);

  const history = dataOf(await adminReq('GET', '/ops/metrics/history?minutes=60', { session: admin })) || {};
  check('指标趋势聚合为数组', Array.isArray(history.aggregate), history);
  const historyWithMetric = dataOf(await adminReq(
    'GET', '/ops/metrics/history?metric=p95_ms&minutes=60&limit=20', { session: admin })) || {};
  check('指标趋势可按指标名取历史', Array.isArray(historyWithMetric.items), historyWithMetric);

  const traces429 = dataOf(await adminReq('GET', '/ops/traces?status=429&limit=20', { session: admin })) || {};
  const traceHit = arr(traces429.items).find((t) => t.traceId === traceId429)
    || arr(traces429.items).find((t) => t.path === '/api/v1/search/hot');
  check('429 请求进入链路缓冲', Boolean(traceHit) && traceHit.status === 429
    && traceHit.path === '/api/v1/search/hot', traceHit);
  const traceByPrefix = dataOf(await adminReq(
    'GET', `/ops/traces?traceId=${encodeURIComponent(String(traceId429).slice(0, 8))}&limit=20`,
    { session: admin })) || {};
  check('traceId 前缀检索命中', arr(traceByPrefix.items).some((t) => t.traceId === traceId429),
    arr(traceByPrefix.items).length);
  const traceByPath = dataOf(await adminReq(
    'GET', '/ops/traces?path=search/hot&limit=50', { session: admin })) || {};
  check('路径检索命中且状态为 429', arr(traceByPath.items).length >= 1
    && arr(traceByPath.items).every((t) => t.path.includes('search/hot')), arr(traceByPath.items).length);
  check('链路缓冲统计有容量上限', num(traces429.stats?.capacity) >= 1000
    && num(traces429.stats?.filled) > 0 && num(traces429.stats?.recorded) > 0, traces429.stats);

  // ================================================================ §7 发布 / 备份
  section('§7 发布状态机与备份清单');

  const failedPublish = await adminReq('POST', '/ops/publish', {
    session: admin,
    body: {
      version: `v19-fail-${RUN_TAG}`,
      service: 'newmusic',
      environment: 'local',
      publishType: 'GRAY',
      remark: `v19 验收 ${RUN_TAG}`,
    },
  });
  const failedId = dataOf(failedPublish)?.id;
  check('创建发布记录为 IN_PROGRESS', okOf(failedPublish)
    && dataOf(failedPublish)?.status === 'IN_PROGRESS' && Boolean(failedId), failedPublish.text);
  const finishFail = await adminReq('POST', `/ops/publish/${failedId}/finish${qs({ success: false, message: 'v19 模拟失败' })}`,
    { session: admin, body: undefined });
  check('发布可结束为 FAILED', okOf(finishFail) && dataOf(finishFail)?.status === 'FAILED'
    && Boolean(dataOf(finishFail)?.endTime), finishFail.text);
  const finishAgain = await adminReq('POST', `/ops/publish/${failedId}/finish${qs({ success: true })}`,
    { session: admin, body: undefined });
  check('非 IN_PROGRESS 的发布不能再次完成（10001）', codeOf(finishAgain) === 10001, finishAgain.text);

  const okPublish = await adminReq('POST', '/ops/publish', {
    session: admin,
    body: {
      version: `v19-ok-${RUN_TAG}`,
      service: 'newmusic',
      environment: 'local',
      publishType: 'GRAY',
      gitCommit: 'v19probe',
      remark: `v19 验收 ${RUN_TAG}`,
    },
  });
  const okId = dataOf(okPublish)?.id;
  const finishOk = await adminReq('POST', `/ops/publish/${okId}/finish${qs({ success: true, message: 'v19 模拟成功' })}`,
    { session: admin, body: undefined });
  check('发布可结束为 SUCCESS', okOf(finishOk) && dataOf(finishOk)?.status === 'SUCCESS', finishOk.text);
  const rollbackRes = await adminReq('POST', `/ops/publish/${okId}/rollback`, {
    session: admin, body: { targetVersion: 'v0.19.0', reason: 'v19 验收回滚' },
  });
  const rollback = dataOf(rollbackRes) || {};
  check('回滚返回原记录与回滚记录', okOf(rollbackRes)
    && rollback.rolledBack?.status === 'ROLLED_BACK'
    && rollback.rolledBack?.rollbackVersion === 'v0.19.0'
    && rollback.targetVersion === 'v0.19.0', rollbackRes.text);
  check('回滚生成独立 ROLLBACK 审计记录', rollback.rollbackRecord?.publishType === 'ROLLBACK'
    && rollback.rollbackRecord?.version === 'v0.19.0'
    && rollback.rollbackRecord?.rollbackVersion === `v19-ok-${RUN_TAG}`
    && rollback.rollbackRecord?.status === 'SUCCESS', rollback.rollbackRecord);
  const rollbackAgain = await adminReq('POST', `/ops/publish/${okId}/rollback`, {
    session: admin, body: { targetVersion: 'v0.19.0' },
  });
  check('重复回滚被拒绝（10001）', codeOf(rollbackAgain) === 10001, rollbackAgain.text);

  const inProgressPublish = await adminReq('POST', '/ops/publish', {
    session: admin,
    body: { version: `v19-progress-${RUN_TAG}`, service: 'newmusic', environment: 'local', publishType: 'FULL' },
  });
  const inProgressId = dataOf(inProgressPublish)?.id;
  const rollbackInProgress = await adminReq('POST', `/ops/publish/${inProgressId}/rollback`, {
    session: admin, body: {},
  });
  check('进行中的发布不能回滚（10001）', codeOf(rollbackInProgress) === 10001, rollbackInProgress.text);
  await adminReq('POST', `/ops/publish/${inProgressId}/finish${qs({ success: false })}`, {
    session: admin, body: undefined,
  });
  const publishList = dataOf(await adminReq(
    'GET', `/ops/publish${qs({ service: 'newmusic', page: 1, size: 100 })}`, { session: admin })) || {};
  check('发布列表包含本轮记录', arr(publishList.list).some((p) => String(p.id) === String(okId))
    && arr(publishList.list).some((p) => p.publishType === 'ROLLBACK'), publishList.total);
  const badPublishType = await adminReq('POST', '/ops/publish', {
    session: admin, body: { version: `v19-bad-${RUN_TAG}`, publishType: 'NOPE' },
  });
  check('非法发布类型被拒绝（10001）', codeOf(badPublishType) === 10001, badPublishType.text);

  const backupRes = await adminReq('POST', '/ops/backups', {
    session: admin, body: { backupType: 'FULL', message: `v19 验收 ${RUN_TAG}` },
  });
  const backup = dataOf(backupRes) || {};
  backupLocation = backup.location || null;
  check('备份清单生成成功', okOf(backupRes) && backup.status === 'SUCCESS'
    && num(backup.tableCount) >= 12 && num(backup.rowCount) > 0, backupRes.text);
  check('备份清单带 SHA-256 校验和', /^[0-9a-f]{64}$/.test(String(backup.checksum || '')), backup.checksum);
  check('备份初始校验状态 VERIFIED', backup.verifyStatus === 'VERIFIED', backup.verifyStatus);
  const backupFile = backupLocation ? path.resolve('.tmp/backups', backupLocation) : '';
  check('备份清单文件落盘且大小一致', Boolean(backupLocation)
    && fs.existsSync(backupFile) && fs.statSync(backupFile).size === num(backup.sizeBytes),
  { location: backupLocation, exists: backupLocation ? fs.existsSync(backupFile) : false });

  const verifyRes = await adminReq('POST', `/ops/backups/${backup.id}/verify`, { session: admin, body: undefined });
  const verify = dataOf(verifyRes) || {};
  check('备份二次校验一致', okOf(verifyRes) && verify.verifyStatus === 'VERIFIED'
    && verify.expected === backup.checksum && verify.actual === backup.checksum, verify);
  const preview = arr(dataOf(await adminReq('GET', `/ops/backups/${backup.id}/preview?limit=5`,
    { session: admin })));
  check('备份清单可预览（前 5 行）', preview.length === 5
    && preview[0].startsWith('# newmusic logical backup manifest'), preview);
  const missingBackup = await adminReq('POST', '/ops/backups/999999999999999/verify', {
    session: admin, body: undefined,
  });
  check('校验不存在的备份返回 73009', codeOf(missingBackup) === 73009, missingBackup.text);
  const backupList = dataOf(await adminReq('GET', '/ops/backups?status=SUCCESS&size=100',
    { session: admin })) || {};
  check('备份列表包含本轮记录', arr(backupList.list).some((b) => String(b.id) === String(backup.id)),
    backupList.total);

  // ================================================================ §8 成本
  section('§8 成本核算（分 / 唯一键 / 分类）');

  const cost0 = dataOf(await adminReq('GET', '/ops/costs', { session: admin })) || {};
  check('成本概览字段完整', cost0.currency === 'CNY' && cost0.unit === 'cent'
    && typeof cost0.todayAmountCent === 'number' && num(cost0.todayAmountCent) > 0
    && typeof cost0.todayBudgetCent === 'number' && num(cost0.todayBudgetCent) > 0
    && typeof cost0.todayUsageRatio === 'number' && typeof cost0.alert === 'boolean'
    && typeof cost0.last7DaysCent === 'number' && Array.isArray(cost0.daily)
    && Array.isArray(cost0.byCategory) && Array.isArray(cost0.byService), cost0);
  check('成本今日明细按分类落库（≥9 类）', arr(cost0.todayRecords).length >= 9,
    arr(cost0.todayRecords).length);

  const addCost = await adminReq('POST', '/ops/costs/records', {
    session: admin,
    body: {
      category: 'OTHER', service: PROBE_SERVICE, amountCent: 1234, budgetCent: 2000,
      detail: `v19 验收 ${RUN_TAG}`,
    },
  });
  const addedCost = dataOf(addCost) || {};
  check('补录成本成功（整数分）', okOf(addCost) && addedCost.category === 'OTHER'
    && num(addedCost.amountCent) === 1234 && num(addedCost.budgetCent) === 2000, addCost.text);
  costAdded = Boolean(addedCost.id);
  const duplicateCost = await adminReq('POST', '/ops/costs/records', {
    session: admin,
    body: { category: 'OTHER', service: PROBE_SERVICE, amountCent: 1, budgetCent: 1 },
  });
  check('同日同分类同服务唯一（10010）', codeOf(duplicateCost) === 10010, duplicateCost.text);
  const badCategory = await adminReq('POST', '/ops/costs/records', {
    session: admin, body: { category: 'NOPE', service: PROBE_SERVICE, amountCent: 1 },
  });
  check('未知成本分类被拒绝（10001）', codeOf(badCategory) === 10001, badCategory.text);
  const negativeCost = await adminReq('POST', '/ops/costs/records', {
    session: admin, body: { category: 'OTHER', service: `${PROBE_SERVICE}-n`, amountCent: -1 },
  });
  check('负数成本被参数校验拦截（10001）', codeOf(negativeCost) === 10001, negativeCost.text);

  const cost1 = dataOf(await adminReq('GET', '/ops/costs', { session: admin })) || {};
  check('成本概览金额随补录增长', num(cost1.todayAmountCent) === num(cost0.todayAmountCent) + 1234,
    `${cost0.todayAmountCent} → ${cost1.todayAmountCent}`);
  const probeByService = arr(cost1.byService).find((s) => s.service === PROBE_SERVICE);
  check('按服务聚合包含探针成本', Boolean(probeByService) && num(probeByService.amount) === 1234,
    probeByService);
  const otherRecords = arr(dataOf(await adminReq(
    'GET', `/ops/costs/records${qs({ category: 'OTHER', days: 7, limit: 100 })}`, { session: admin })));
  check('成本明细可按分类过滤', otherRecords.some((r) => r.service === PROBE_SERVICE
    && num(r.amountCent) === 1234), otherRecords.length);

  // ================================================================ §9 配置中心
  section('§9 运维配置中心（热更新 / 历史 / 回滚 / 类型）');

  const configs = dataOf(await adminReq('GET', '/ops/configs', { session: admin })) || {};
  const configKeys = Object.keys(configs).sort();
  check('运维配置共 8 项', configKeys.length === 8, configKeys);
  check('配置项齐全', ['ops.degrade.level', 'ops.rate.limit.enabled', 'ops.trace.sample-rate',
    'ops.alert.eval.enabled', 'ops.metrics.snapshot.enabled', 'ops.cost.daily-budget-cent',
    'ops.backup.retention-days', 'ops.chaos.enabled'].every((k) => k in configs), configKeys);

  const originalSampleRate = String(configs['ops.trace.sample-rate'] ?? '0.1');
  const updateSample = await adminReq('PUT', '/ops/configs/ops.trace.sample-rate', {
    session: admin, body: { configValue: '0.2', remark: `v19 验收 ${RUN_TAG}` },
  });
  check('运维配置热更新成功', okOf(updateSample), updateSample.text);
  const configsAfter = dataOf(await adminReq('GET', '/ops/configs', { session: admin })) || {};
  check('热更新立即可读（0.2）', configsAfter['ops.trace.sample-rate'] === '0.2',
    configsAfter['ops.trace.sample-rate']);

  const historyRes = await adminReq('GET', '/configs/ops.trace.sample-rate/history', { session: admin });
  const historyList = arr(dataOf(historyRes));
  // 历史是累积的，其它轮次也可能把 0.2 改回来又改回去；用本轮 remark 定位，
  // 否则回滚的是上一轮的旧记录，断言会随执行次数漂移。
  const sampleHistory = historyList.find((h) => h.newValue === '0.2'
    && h.oldValue === originalSampleRate && String(h.remark || '').includes(RUN_TAG))
    || historyList.find((h) => h.newValue === '0.2' && h.oldValue === originalSampleRate);
  check('配置变更历史含本轮记录', okOf(historyRes) && Boolean(sampleHistory)
    && sampleHistory.oldValue === originalSampleRate, historyList.slice(0, 3));
  const rollbackConfig = await adminReq('POST', '/configs/history/199999999999999/rollback', {
    session: admin, body: undefined,
  });
  check('回滚不存在的历史被拒绝（10001）', codeOf(rollbackConfig) === 10001, rollbackConfig.text);
  const rollbackSample = await adminReq('POST', `/configs/history/${sampleHistory?.id}/rollback`, {
    session: admin, body: undefined,
  });
  const configsRolled = dataOf(await adminReq('GET', '/ops/configs', { session: admin })) || {};
  check('配置可回滚到历史值', okOf(rollbackSample)
    && configsRolled['ops.trace.sample-rate'] === originalSampleRate,
  { status: codeOf(rollbackSample), value: configsRolled['ops.trace.sample-rate'] });

  const invalidConfig = await adminReq('PUT', '/ops/configs/ops.trace.sample-rate', {
    session: admin, body: { configValue: 'abc', remark: 'v19 验收：非法数字' },
  });
  // 类型校验来自模块 09 的 SysConfigService：数字型写入 'abc' 返回 80023（配置值类型不符），
  // 不是参数校验的 10001
  check('数字型配置拒绝非法值（80023）', codeOf(invalidConfig) === 80023, invalidConfig.text);
  const unknownConfig = await adminReq('PUT', '/ops/configs/ops.nope', {
    session: admin, body: { configValue: 'x' },
  });
  check('未知配置键返回 10001', codeOf(unknownConfig) === 10001, unknownConfig.text);
  const configsFinal = dataOf(await adminReq('GET', '/ops/configs', { session: admin })) || {};
  check('非法写入未改动配置', configsFinal['ops.trace.sample-rate'] === originalSampleRate,
    configsFinal['ops.trace.sample-rate']);

  // ================================================================ §10 缓存巡检
  section('§10 缓存巡检与按前缀清理（ml: 红线）');

  if (REDIS_OK) {
    redis('set', CACHE_KEY_1, '1', 'ex', '300');
    redis('set', CACHE_KEY_2, '2', 'ex', '300');
    const inspect = dataOf(await adminReq(
      'GET', `/ops/cache/keys${qs({ prefix: CACHE_PREFIX, limit: 100 })}`, { session: admin })) || {};
    const inspectedKeys = arr(inspect.keys).map((k) => k.key);
    check('巡检结果只含匹配前缀的键', inspect.prefix === CACHE_PREFIX
      && num(inspect.count) >= 2 && inspectedKeys.includes(CACHE_KEY_1)
      && inspectedKeys.includes(CACHE_KEY_2)
      && inspectedKeys.every((k) => k.startsWith(CACHE_PREFIX)), inspect.keys);
    check('巡检回显类型与 TTL', arr(inspect.keys).every((k) => typeof k.type === 'string'
      && typeof k.ttlSeconds === 'number'));

    const forbidRanking = await adminReq(
      'GET', `/ops/cache/keys${qs({ prefix: 'ranking:' })}`, { session: admin });
    check('拒绝巡检 ranking:（非 ml: 命名空间）', codeOf(forbidRanking) === 73008, forbidRanking.text);
    const wildcard = await adminReq('GET', `/ops/cache/keys${qs({ prefix: 'ml:*' })}`, { session: admin });
    check('拒绝通配符前缀', codeOf(wildcard) === 73008, wildcard.text);
    const broadDelete = await adminReq(
      'DELETE', `/ops/cache/keys${qs({ prefix: 'ml:', confirm: 'ml:' })}`, { session: admin });
    check('拒绝清理一段前缀 ml:（73008）', codeOf(broadDelete) === 73008, broadDelete.text);
    const wrongConfirm = await adminReq(
      'DELETE', `/ops/cache/keys${qs({ prefix: CACHE_PREFIX, confirm: 'ml:v19:other:' })}`, { session: admin });
    check('confirm 与前缀不一致时拒绝（73008）', codeOf(wrongConfirm) === 73008, wrongConfirm.text);
    check('被拒绝的清理没有删除探针键', redis('exists', CACHE_KEY_1) === '1');

    const deleted = await adminReq(
      'DELETE', `/ops/cache/keys${qs({ prefix: CACHE_PREFIX, confirm: CACHE_PREFIX })}`, { session: admin });
    check('两段前缀 + 精确 confirm 可清理', okOf(deleted) && num(dataOf(deleted)?.deleted) >= 2,
      deleted.text);
    check('探针键已被删除', redis('exists', CACHE_KEY_1) === '0' && redis('exists', CACHE_KEY_2) === '0');
    const inspectAfter = dataOf(await adminReq(
      'GET', `/ops/cache/keys${qs({ prefix: CACHE_PREFIX })}`, { session: admin })) || {};
    check('清理后巡检为空', num(inspectAfter.count) === 0, inspectAfter);
  } else {
    skip('缓存巡检与按前缀清理', 'redis-cli 不可用');
  }

  // ================================================================ §11 种子与权限
  section('§11 种子数据与权限点');

  check('限流规则种子 8 条', mysqlCount('SELECT COUNT(*) FROM newmusic.t_ops_rate_limit_rule') === 8);
  check('降级开关种子 18 条', mysqlCount('SELECT COUNT(*) FROM newmusic.t_degrade_switch') === 18);
  check('告警规则种子 10 条', mysqlCount('SELECT COUNT(*) FROM newmusic.t_ops_alert_rule') === 10);
  check('应急预案种子 11 条', mysqlCount('SELECT COUNT(*) FROM newmusic.t_ops_runbook') === 11);
  check('运维配置种子 8 条', mysqlCount(
    "SELECT COUNT(*) FROM newmusic.t_sys_config WHERE config_key LIKE 'ops.%'") === 8);
  check('模块 19 权限点 9 个', mysqlCount(
    "SELECT COUNT(*) FROM newmusic.t_admin_permission WHERE perm_code LIKE 'admin:ops:%' OR perm_code='admin:cost:view'") === 9);
  check('超管角色被授予 9 个新权限', mysqlCount(
    'SELECT COUNT(*) FROM newmusic.t_admin_role_permission WHERE role_id=1 AND perm_id BETWEEN 9230 AND 9238') === 9);
  check('ADMIN 角色被授予 8 个（无混沌）', mysqlCount(
    'SELECT COUNT(*) FROM newmusic.t_admin_role_permission WHERE role_id=2 AND perm_id BETWEEN 9230 AND 9238') === 8);
  check('OPERATOR 只读 3 个', mysqlCount(
    'SELECT COUNT(*) FROM newmusic.t_admin_role_permission WHERE role_id=3 AND perm_id BETWEEN 9230 AND 9238') === 3);
  check('模块 19 表全部存在', mysqlCount(
    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='newmusic' AND table_name IN ("
    + "'t_ops_rate_limit_rule','t_degrade_switch','t_degrade_event','t_ops_alert_rule',"
    + "'t_ops_alert_silence','t_ops_alert_record','t_publish_record','t_backup_record',"
    + "'t_chaos_experiment','t_cost_record','t_ops_runbook','t_ops_metric_snapshot')") === 12);
  check('发布基线 v0.19.0 存在', mysqlCount(
    "SELECT COUNT(*) FROM newmusic.t_publish_record WHERE version='v0.19.0' AND status='SUCCESS'") >= 1);
  check('降级事件审计表可写', mysqlCount(
    'SELECT COUNT(*) FROM newmusic.t_degrade_event') >= 5);
}

// ---------------------------------------------------------------- 清理与快照

async function cleanup() {
  if (admin) {
    try {
      await adminReq('POST', '/ops/chaos/stop', { session: admin, body: undefined });
    } catch (e) {
      note(`停止残留混沌实验失败：${e.message}`);
    }
    try {
      if (silenceId) {
        await adminReq('DELETE', `/ops/alerts/silences/${silenceId}`, { session: admin });
        silenceId = null;
      }
    } catch (e) {
      note(`取消告警静默失败：${e.message}`);
    }
    try {
      if (originalSearch) {
        await adminReq('PUT', '/ops/rate-limits/SEARCH', {
          session: admin,
          body: {
            threshold: originalSearch.threshold,
            windowSeconds: originalSearch.windowSeconds,
            dimension: originalSearch.dimension,
            httpMethod: originalSearch.httpMethod ?? '',
            whitelistIps: originalSearch.whitelistIps ?? '',
            description: originalSearch.description ?? '搜索/联想/热搜',
            enabled: originalSearch.enabled,
          },
        });
      }
    } catch (e) {
      note(`恢复 SEARCH 限流规则失败：${e.message}`);
    }
    try {
      const restore = {
        'ops.rate.limit.enabled': originalConfigs['ops.rate.limit.enabled'] ?? 'true',
        'ops.chaos.enabled': originalConfigs['ops.chaos.enabled'] ?? 'true',
        'ops.trace.sample-rate': originalConfigs['ops.trace.sample-rate'] ?? '0.1',
      };
      for (const [key, value] of Object.entries(restore)) {
        await adminReq('PUT', `/ops/configs/${key}`, {
          session: admin, body: { configValue: value, remark: 'v19 验收收尾恢复' },
        });
      }
    } catch (e) {
      note(`恢复运维配置失败：${e.message}`);
    }
    try {
      await adminReq('POST', '/ops/degrade/level', {
        session: admin, body: { level: 0, reason: 'v19 验收收尾：恢复正常' },
      });
    } catch (e) {
      note(`恢复降级等级失败：${e.message}`);
    }
    try {
      // 若告警探针未走到「处理」，0 级后评估一次让告警自动 resolved
      await adminReq('POST', '/ops/alerts/evaluate', { session: admin });
    } catch (e) {
      /* 不影响结论 */
    }
    try {
      await adminReq('POST', '/ops/rate-limits/counters/reset', { session: admin });
    } catch (e) {
      /* 不影响结论 */
    }
  }

  if (REDIS_OK) {
    try {
      const keys = [CACHE_KEY_1, CACHE_KEY_2,
        `${CACHE_PREFIX}one`, `${CACHE_PREFIX}two`];
      redisDel(...new Set(keys));
    } catch (e) {
      note(`清理 Redis 探针键失败：${e.message}`);
    }
  }

  if (HAS_MYSQL && costAdded) {
    try {
      mysql(`DELETE FROM newmusic.t_cost_record WHERE service=${q(PROBE_SERVICE)} AND category='OTHER'`);
    } catch (e) {
      note(`清理成本探针行失败：${e.message}`);
    }
  }
}

function snapshots() {
  section('共享实例快照');
  if (REDIS_OK) {
    const rankingAfter = listKeys('ranking:*').sort();
    check('Redis ranking:* 键未被改动',
      JSON.stringify(rankingAfter) === JSON.stringify(rankingBefore),
      `before=${rankingBefore.length} after=${rankingAfter.length}`);
  } else {
    skip('Redis ranking:* 快照', 'redis-cli 不可用');
  }
  if (HAS_MYSQL) {
    const sharedAfter = mysqlScalar(
      "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('MySQL emp_db/music_db 表数未变', sharedAfter === sharedTablesBefore,
      `before=${sharedTablesBefore} after=${sharedAfter}`);
  }
}

function summary() {
  console.log(`\n\x1b[36m模块 19 验收：${pass} 通过 / ${fail} 失败 / ${skipped} 跳过\x1b[0m`);
  if (fail > 0) {
    console.log('\n失败项：');
    for (const item of failures) console.log(`  - ${item}`);
  }
  if (skipNotes.length) {
    console.log('\n跳过项：');
    for (const item of skipNotes) console.log(`  - ${item}`);
  }
}

(async () => {
  try {
    await main();
  } catch (e) {
    fail += 1;
    failures.push(`脚本异常：${e.message}`);
    console.error(`\n\x1b[31m脚本异常\x1b[0m：${e.stack || e.message}`);
  } finally {
    await cleanup();
    snapshots();
    summary();
  }
  process.exit(fail > 0 ? 1 : 0);
})();
