#!/usr/bin/env node
/**
 * 模块 17 验收脚本 —— 数据平台与运营分析（P0 + P1）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-17.mjs
 *
 * 前置：
 *   * 应用已启动（8081），已执行 sql/17-data-platform-seed.sql；
 *   * 后台验证码回显开启（ADMIN_CAPTCHA_ECHO=true），与 verify-09/14/15 相同；
 *   * Redis 6379 / MySQL 3306 可连，Node 22+（全局 fetch），零第三方依赖。
 *
 * 覆盖：
 *   §1 埋点采集：单品/批量、幂等、脏数据、脱敏、时间漂移、限流、采样
 *   §2 实时看板、趋势、下钻
 *   §3 指标定义、口径变更审批、下线；埋点文档与校验接口
 *   §4 漏斗预置与自定义漏斗、留存分析
 *   §5 用户画像标签：目录、自助查看、单用户重算
 *   §6 人群圈选：预览、创建、精度保护、删除
 *   §7 报表：12 类、生成幂等、详情、订阅与分发、CSV 导出
 *   §8 数仓 ETL 幂等、回溯、质量校验、对账、血缘
 *
 * 共享实例安全：
 *   * MySQL 3306 上还有 emp_db / music_db，所有 SQL 都带 newmusic. 限定符；
 *   * Redis 6379 的 db0 有其它项目的 ranking:* 键，只读写 ml:*；
 *   * 口令只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给客户端，不进 argv、不落盘；
 *   * 开头/结尾比对 ranking:* 与 emp_db/music_db 表数，证明未破坏共享实例。
 *
 * 可重复执行：账号、deviceId、eventId、指标 ID、人群名每轮唯一；
 * 埋点为探针数据保留在 ODS 中（本身即真实链路产物），只清理本轮的
 * Redis 幂等/限流键与报表订阅。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- 配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ADMIN_BASE = process.env.ADMIN_API_BASE || `${API_BASE}/admin`;
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
const DEVICE = `v17-dev-${RUN_TAG}`;
const RATE_DEVICE = `v17-rate-${RUN_TAG}`;
const SAMPLE_DEVICE = `v17-sample-${RUN_TAG}`;
const METRIC_ID = `metric_probe_${RUN_TAG}`.slice(0, 60);

const now = () => Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.${Math.floor(Math.random() * 200) + 20}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

function dayOffset(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
const TODAY = dayOffset(0);
const YESTERDAY = dayOffset(-1);
const DAY_KEY = TODAY.replace(/-/g, '');

const newId = (prefix) => {
  const suffix = Math.floor(Math.random() * 0x100000000).toString(36);
  return `${prefix}_${Date.now().toString(36)}_${suffix}`;
};
const addEventId = (set, id) => {
  set.add(id);
  return id;
};

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

async function http(method, url, { session, body, headers, xff, token, bytes } = {}) {
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
  // CSV 需要看原始字节判断 BOM，Response.text() 会按 UTF-8 解码时吞掉 BOM
  const raw = bytes ? Buffer.from(await res.arrayBuffer()) : null;
  const text = bytes ? raw.toString('utf8') : await res.text();
  if (session) applySetCookies(session, res);
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* CSV / 纯文本 */
  }
  return { status: res.status, json, text, headers: res.headers, raw };
}

const call = (method, path, opts = {}) => http(method, `${API_BASE}${path}`, opts);
const adminReq = (method, path, opts = {}) => http(method, `${ADMIN_BASE}${path}`, opts);
const codeOf = (res) => (res.json && typeof res.json.code === 'number' ? res.json.code : -1);
const dataOf = (res) => (res.json ? res.json.data : null);
const msgOf = (res) => (res.json && typeof res.json.message === 'string' ? res.json.message : '');
const okOf = (res) => codeOf(res) === 0;

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

const listKeys = (pattern) => {
  // 本机 --scan 静默返回空，固定用 keys；只对 ml:/ranking: 前缀使用
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
const idList = (ids) => ids.map((id) => q(id)).join(',');

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

// ---------------------------------------------------------------- TOTP / 后台登录

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
    const session = new Session('v17-admin');
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

// ---------------------------------------------------------------- C 端账号

async function registerAndLogin() {
  const account = `v17probe${RUN_TAG}`;
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

// ---------------------------------------------------------------- 埋点构造

function eventBody(event, properties, { eventId, eventTime, deviceId } = {}) {
  return {
    eventId,
    event,
    eventTime: eventTime === undefined ? now() : eventTime,
    common: {
      deviceId,
      sessionId: `sess-${RUN_TAG}`,
      platform: 'web',
      appVersion: 'web-1.0.0',
      os: 'Windows 11',
      browser: 'Chrome',
      screen: '1920x1080',
      network: 'wifi',
      pageId: 'home',
      pageUrl: '/',
      referrer: '',
    },
    properties: properties || {},
    isNewUser: false,
  };
}

async function sendOne(body, token) {
  return call('POST', '/track/events', { token, body, xff: syntheticIp() });
}

// ---------------------------------------------------------------- 主流程

const createdEventIds = new Set();
const createdDirtyIds = new Set();
let probe = null;
let admin = null;
let songId = '';
let priorSubscription = null;
let audienceId = null;

async function main() {
  if (!HAS_MYSQL) {
    throw new Error('缺少 MYSQL_PASSWORD，无法做数据库与共享实例断言');
  }
  songId = mysqlScalar('SELECT id FROM newmusic.t_song ORDER BY id LIMIT 1') || '1001';
  note(`探针账号标签 ${RUN_TAG}，歌曲 ID ${songId}`);

  // ================================================================ §1 埋点采集

  section('§1 埋点采集（幂等 / 脏数据 / 脱敏 / 限流 / 采样）');

  probe = await registerAndLogin();
  check('探针用户注册并登录', Boolean(probe.token) && Boolean(probe.userId));

  // 匿名上报：未登录也要能进 ODS
  const anonId = addEventId(createdEventIds, newId('v17anon'));
  const anon = await sendOne(eventBody('page_view', { page_id: 'home' }, {
    eventId: anonId, deviceId: DEVICE,
  }));
  check('匿名 page_view 被接收', okOf(anon) && dataOf(anon).outcome === 'ACCEPTED', anon.text);
  check('匿名事件 user_id 为空',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id=${q(anonId)} AND user_id IS NULL`) === 1);

  // 登录状态批量：两段事件 page_view / search_submit 也必须能入库
  const baseTime = now();
  const batchEvents = [
    { event: 'page_view', props: { page_id: 'home', page_url: '/' } },
    { event: 'search_submit', props: { keyword: '周杰伦', scene: 'web' } },
    { event: 'player_song_play', props: { song_id: songId, from_source: 'home' } },
    { event: 'player_song_play', props: { song_id: songId, from_source: 'playlist' } },
    { event: 'player_song_end', props: { song_id: songId, play_duration: 45, song_duration: 50, completion_rate: 0.8 } },
    { event: 'player_song_end', props: { song_id: songId, play_duration: 46, song_duration: 50, completion_rate: 0.92 } },
    { event: 'player_song_end', props: { song_id: songId, play_duration: 47, song_duration: 50, completion_rate: 0.94 } },
  ].map((item, index) => {
    const eventId = addEventId(createdEventIds, newId('v17evt'));
    return eventBody(item.event, item.props, {
      eventId,
      eventTime: baseTime + index,
      deviceId: DEVICE,
      userId: probe.userId,
    });
  });

  const batch = await call('POST', '/track/batch', {
    token: probe.token,
    body: { events: batchEvents, deviceId: DEVICE, appVersion: 'web-1.0.0' },
  });
  check('批量上报 7 条全部接收',
    okOf(batch) && dataOf(batch).received === 7 && dataOf(batch).accepted === 7, batch.text);
  check('登录态事件写入 user_id',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE user_id=${q(probe.userId)} AND event_id IN (${idList(batchEvents.map((e) => e.eventId))})`) === 7);

  // 两段事件名不再被误判为非法
  check('两段事件 search_submit 已入库',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id=${q(batchEvents[1].eventId)}`) === 1);

  // 5 分钟幂等
  const dupRes = await sendOne(batchEvents[0], probe.token);
  check('重复 eventId 返回 DUPLICATE',
    okOf(dupRes) && dataOf(dupRes).outcome === 'DUPLICATE', dupRes.text);
  check('重复事件只落一行',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id=${q(batchEvents[0].eventId)}`) === 1);

  // 脱敏
  const sanitizeId = addEventId(createdEventIds, newId('v17san'));
  const sanitizeRes = await sendOne(eventBody('user_login_success', {
    password: 'P@ssw0rd!',
    accessToken: 'eyJhbGciOiJIUzI1NiJ9',
    phone: '13800138000',
    keyword: '13800138000',
    login_type: 'password',
  }, { eventId: sanitizeId, deviceId: DEVICE }), probe.token);
  check('含敏感字段的事件仍被接收（字段脱敏而非拒收）',
    okOf(sanitizeRes) && dataOf(sanitizeRes).outcome === 'ACCEPTED', sanitizeRes.text);
  const sanitizedProps = mysqlScalar(`SELECT props FROM newmusic.t_track_event WHERE event_id=${q(sanitizeId)}`);
  let sanitized = {};
  try {
    sanitized = JSON.parse(sanitizedProps);
  } catch {
    /* 断言会给出原始文本 */
  }
  // 注意不能直接搜 "password"：正常属性 login_type=password 是合法值
  check('密码/token 整键删除',
    !('password' in sanitized) && !('accessToken' in sanitized)
    && !sanitizedProps.includes('P@ssw0rd') && !sanitizedProps.includes('eyJhbGci'), sanitizedProps);
  check('手机号按掩码保留', sanitizedProps.includes('138****8000'), sanitizedProps);

  // 客户端时间漂移
  const driftId = addEventId(createdEventIds, newId('v17drift'));
  const driftRes = await sendOne(eventBody('page_leave', { page_id: 'home', stay_ms: 1234 }, {
    eventId: driftId, eventTime: 0, deviceId: DEVICE,
  }), probe.token);
  check('eventTime=0 仍被接收', okOf(driftRes) && dataOf(driftRes).outcome === 'ACCEPTED', driftRes.text);
  check('不可信客户端时间被服务端时间替换',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id=${q(driftId)} AND ABS(event_time - UNIX_TIMESTAMP(NOW(3))*1000) < 600000`) === 1);

  // 未登记事件 → 脏数据
  const unknownId = addEventId(createdDirtyIds, newId('v17unk'));
  const unknownRes = await sendOne(eventBody('probe_unknown_event', { a: 1 }, {
    eventId: unknownId, deviceId: DEVICE,
  }), probe.token);
  check('未登记事件返回 DIRTY', okOf(unknownRes) && dataOf(unknownRes).outcome === 'DIRTY', unknownRes.text);
  check('未登记事件写入脏数据表',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_dirty WHERE event_id=${q(unknownId)} AND reason LIKE 'UNDOCUMENTED_EVENT%'`) === 1);
  check('未登记事件不写 ODS',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id=${q(unknownId)}`) === 0);

  // 非法事件名 → 脏数据
  const badNameId = addEventId(createdDirtyIds, newId('v17bad'));
  const badNameRes = await sendOne(eventBody('Probe_bad_name', { a: 1 }, {
    eventId: badNameId, deviceId: DEVICE,
  }), probe.token);
  check('非法事件名返回 DIRTY', okOf(badNameRes) && dataOf(badNameRes).outcome === 'DIRTY', badNameRes.text);
  check('非法事件名原因可追溯',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_dirty WHERE event_id=${q(badNameId)} AND reason LIKE 'INVALID_EVENT_NAME%'`) === 1);

  // 属性过大 → 脏数据
  const largeId = addEventId(createdDirtyIds, newId('v17large'));
  const largeRes = await sendOne(eventBody('page_view', { blob: 'x'.repeat(9000) }, {
    eventId: largeId, deviceId: DEVICE,
  }), probe.token);
  check('超 8KB 属性返回 DIRTY', okOf(largeRes) && dataOf(largeRes).outcome === 'DIRTY', largeRes.text);
  check('超限属性原因带 PROPS_TOO_LARGE',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_dirty WHERE event_id=${q(largeId)} AND reason LIKE '%PROPS_TOO_LARGE%'`) === 1);

  // 批量上限
  const tooMany = await call('POST', '/track/batch', {
    token: probe.token,
    body: {
      deviceId: `v17-over-${RUN_TAG}`,
      events: Array.from({ length: 201 }, (_, i) => eventBody('page_view', { page_id: 'home', i }, {
        eventId: `over_${RUN_TAG}_${i}`, deviceId: `v17-over-${RUN_TAG}`,
      })),
    },
  });
  check('单批超过 200 条被拒', codeOf(tooMany) !== 0 && msgOf(tooMany).includes('最多'), tooMany.text);

  // 每设备 100 条/分钟限流
  const rateEvents = Array.from({ length: 101 }, (_, i) => {
    const eventId = addEventId(createdEventIds, newId('v17rate'));
    return eventBody('page_view', { page_id: 'home', seq: i }, {
      eventId, eventTime: baseTime + 1000 + i, deviceId: RATE_DEVICE,
    });
  });
  const rateRes = await call('POST', '/track/batch', {
    token: probe.token,
    body: { events: rateEvents, deviceId: RATE_DEVICE },
  });
  const rateData = dataOf(rateRes) || {};
  check('同一设备第 101 条被限流',
    okOf(rateRes) && rateData.accepted + rateData.duplicate <= 100 && rateData.dropped >= 1, rateRes.text);

  // 50% 采样事件
  const sampleEvents = Array.from({ length: 40 }, (_, i) => {
    const eventId = addEventId(createdEventIds, newId('v17sample'));
    return eventBody('player_speed_change', { song_id: songId, speed: '1.25' }, {
      eventId, eventTime: baseTime + 2000 + i, deviceId: SAMPLE_DEVICE,
    });
  });
  const sampleRes = await call('POST', '/track/batch', {
    token: probe.token,
    body: { events: sampleEvents, deviceId: SAMPLE_DEVICE },
  });
  const sampleData = dataOf(sampleRes) || {};
  check('采样事件的接收数落在 (0, 40) 之间',
    okOf(sampleRes) && sampleData.accepted > 0 && sampleData.accepted < 40
    && sampleData.accepted + sampleData.sampled === 40, sampleRes.text);
  const sampleRows = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_track_event WHERE event_id IN (${idList(sampleEvents.map((e) => e.eventId))})`);
  check('采样未命中的事件不落库', sampleRows === sampleData.accepted, `db=${sampleRows} api=${JSON.stringify(sampleData)}`);

  // 有效播放由服务端推断
  const validInferId = batchEvents[4].eventId;
  check('player_song_end 未传 is_valid_play 时按完成度推断为 true',
    mysqlScalar(`SELECT JSON_UNQUOTE(JSON_EXTRACT(props, '$.is_valid_play')) FROM newmusic.t_track_event WHERE event_id=${q(validInferId)}`) === 'true');

  // 事件文档与校验
  admin = await adminLogin(syntheticIp());
  check('后台登录成功', Boolean(admin));

  const eventList = await adminReq('GET', `/events${qs({ domain: 'player', pageNum: 1, pageSize: 100 })}`, { session: admin });
  const playerDefs = dataOf(eventList)?.list || [];
  check('埋点文档列表可按域筛选',
    okOf(eventList) && playerDefs.length >= 5 && playerDefs.every((d) => d.domain === 'player'), eventList.text);
  check('文档中包含 player_song_play',
    playerDefs.some((d) => d.eventName === 'player_song_play' && d.status === 1));

  const validPageView = await adminReq('POST', '/events/validate', {
    session: admin,
    body: eventBody('page_view', { page_id: 'home' }, { eventId: 'validate-page-view', deviceId: DEVICE }),
  });
  check('校验接口认可两段事件 page_view',
    okOf(validPageView) && dataOf(validPageView).valid === true
    && dataOf(validPageView).documented === true, validPageView.text);

  const validUnknown = await adminReq('POST', '/events/validate', {
    session: admin,
    body: eventBody('probe_unknown_event', { a: 1 }, { eventId: 'validate-unknown', deviceId: DEVICE }),
  });
  check('校验接口识别未登记事件',
    okOf(validUnknown) && dataOf(validUnknown).valid === false
    && dataOf(validUnknown).documented === false, validUnknown.text);

  const validateSanitize = await adminReq('POST', '/events/validate', {
    session: admin,
    body: eventBody('user_login_success', { password: 'x', phone: '13800138000' }, { eventId: 'validate-sanitize', deviceId: DEVICE }),
  });
  check('校验接口返回脱敏后的属性',
    okOf(validateSanitize) && !('password' in (dataOf(validateSanitize).strippedProperties || {}))
    && dataOf(validateSanitize).strippedProperties.phone === '138****8000', validateSanitize.text);

  // ================================================================ §2 实时看板

  section('§2 实时看板、趋势、下钻');

  const realtime = await adminReq('GET', '/dashboard/realtime', { session: admin });
  const board = dataOf(realtime) || {};
  const cards = board.cards || [];
  const cardOf = (id) => cards.find((c) => c.metricId === id) || {};
  check('实时看板返回 12 张指标卡', okOf(realtime) && cards.length === 12, `cards=${cards.length}`);
  check('看板包含 12 个唯一指标 ID', new Set(cards.map((c) => c.metricId)).size === 12);
  check('实时看板日期为今天', board.date === TODAY);
  check('接收计数器已累计', Number(board.ingest?.accepted || 0) >= 10, JSON.stringify(board.ingest));
  check('播放次数卡片有值', Number(cardOf('metric_play_cnt').value || 0) >= 2, JSON.stringify(cardOf('metric_play_cnt')));
  check('DAU-Play 卡片有值（本用户 3 次有效播放）',
    cardOf('metric_dau_play').available === true && Number(cardOf('metric_dau_play').value || 0) >= 1,
    JSON.stringify(cardOf('metric_dau_play')));
  check('埋点成功率在 0~100 之间',
    typeof cardOf('metric_track_success_rate').value === 'number'
    && cardOf('metric_track_success_rate').value >= 0
    && cardOf('metric_track_success_rate').value <= 100);
  check('看板给出环比基准说明', typeof board.compareNote === 'string' && board.compareNote.length > 0);

  if (REDIS_OK) {
    const zscore = Number(redis('zscore', `ml:stat:song:play:${DAY_KEY}`, songId) || 0);
    check('Redis 歌曲播放榜记录到探针歌曲', zscore >= 2, `zscore=${zscore}`);
    const playCnt = Number(redis('get', `ml:stat:play_cnt:${DAY_KEY}`) || 0);
    check('Redis 播放计数已累加', playCnt >= 2, `play_cnt=${playCnt}`);
  } else {
    skip('Redis 歌曲播放榜', 'redis-cli 不可用');
    skip('Redis 播放计数', 'redis-cli 不可用');
  }

  const trend = await adminReq('GET', `/dashboard/trend${qs({ metricId: 'metric_play_cnt', start: TODAY, end: TODAY })}`, { session: admin });
  const trendData = dataOf(trend) || {};
  const todayPoint = (trendData.points || []).find((p) => p.date === TODAY);
  check('趋势返回今日播放点', okOf(trend) && Boolean(todayPoint) && Number(todayPoint.value) >= 2, trend.text);
  check('趋势带指标名与来源', trendData.metricName === '播放次数' && typeof trendData.source === 'string');

  const drill = await adminReq('GET', `/dashboard/drill${qs({ metricId: 'metric_play_cnt', dimension: 'platform', date: TODAY })}`, { session: admin });
  const drillData = dataOf(drill) || {};
  const webValue = (drillData.values || []).find((v) => v.dimensionValue === 'web') || {};
  check('下钻返回平台维度拆分', okOf(drill) && drillData.dimension === 'platform' && (drillData.values || []).length >= 1, drill.text);
  check('下钻 web 平台播放次数 >= 2', Number(webValue.value || 0) >= 2, JSON.stringify(webValue));

  const badDrill = await adminReq('GET', `/dashboard/drill${qs({ metricId: 'metric_play_cnt', dimension: 'not_a_dim' })}`, { session: admin });
  check('不支持的下钻维度被拒', codeOf(badDrill) !== 0 && msgOf(badDrill).includes('维度'), badDrill.text);

  // ================================================================ §3 指标

  section('§3 指标体系与口径审批');

  const metricList = await adminReq('GET', `/metrics${qs({ status: 1, pageNum: 1, pageSize: 100 })}`, { session: admin });
  const metricRecords = dataOf(metricList)?.list || [];
  check('指标列表可查', okOf(metricList) && metricRecords.length >= 20, `count=${metricRecords.length}`);
  check('种子指标 metric_dau 存在', metricRecords.some((m) => m.metricId === 'metric_dau' && m.status === 1));

  const dauDetail = await adminReq('GET', '/metrics/metric_dau', { session: admin });
  check('指标详情含口径与变更记录',
    okOf(dauDetail) && dataOf(dauDetail).metric?.metricId === 'metric_dau'
    && Array.isArray(dataOf(dauDetail).changeLogs)
    && dataOf(dauDetail).pendingChange === null, dauDetail.text);

  const createMetric = await adminReq('POST', '/metrics', {
    session: admin,
    body: {
      metricId: METRIC_ID,
      metricName: '验收探针指标',
      category: '技术',
      definition: '验收探针口径 v1',
      calculation: 'COUNT(1)',
      dataSource: 't_track_event',
      dimensions: '["date","platform"]',
      updateFreq: 'T+1',
      owner: 'qa',
    },
  });
  check('新建指标成功', okOf(createMetric) && dataOf(createMetric).metric?.metricId === METRIC_ID, createMetric.text);
  check('新建指标无待审批', okOf(createMetric) && dataOf(createMetric).pendingApproval === false);

  const dupMetric = await adminReq('POST', '/metrics', {
    session: admin,
    body: { metricId: METRIC_ID, metricName: '重复指标', definition: 'x' },
  });
  check('重复指标 ID 被拒', codeOf(dupMetric) !== 0 && msgOf(dupMetric).includes('已存在'), dupMetric.text);

  const updateNoReason = await adminReq('PUT', `/metrics/${METRIC_ID}`, {
    session: admin,
    body: { metricId: METRIC_ID, metricName: '验收探针指标', definition: '验收探针口径 v2' },
  });
  check('修改已生效口径但不填原因被拒',
    codeOf(updateNoReason) !== 0 && msgOf(updateNoReason).includes('原因'), updateNoReason.text);

  const updateReason = await adminReq('PUT', `/metrics/${METRIC_ID}`, {
    session: admin,
    body: {
      metricId: METRIC_ID,
      metricName: '验收探针指标（改名）',
      definition: '验收探针口径 v2',
      reason: '验收：验证口径变更需审批',
    },
  });
  check('提交口径变更后进入待审批',
    okOf(updateReason) && dataOf(updateReason).pendingApproval === true
    && dataOf(updateReason).changeLog?.status === 0, updateReason.text);
  check('审批前指标仍使用旧口径',
    dataOf(updateReason).metric?.definition === '验收探针口径 v1', JSON.stringify(dataOf(updateReason).metric));

  const pendingDetail = await adminReq('GET', `/metrics/${METRIC_ID}`, { session: admin });
  check('详情可看到待审批变更', dataOf(pendingDetail)?.pendingChange?.newDefinition === '验收探针口径 v2', pendingDetail.text);
  const pendingHistory = await adminReq('GET', `/metrics/${METRIC_ID}/history?status=0`, { session: admin });
  check('变更历史可按待审状态筛选',
    okOf(pendingHistory) && (dataOf(pendingHistory) || []).length >= 1, pendingHistory.text);

  const approve = await adminReq('POST', `/metrics/${METRIC_ID}/approve`, {
    session: admin,
    body: { approve: true, comment: '验收通过', effectiveDate: TODAY },
  });
  check('审批通过并记录审批人', okOf(approve) && dataOf(approve).status === 1 && Boolean(dataOf(approve).approver), approve.text);
  check('审批生效日期为今天', dataOf(approve)?.effectiveDate === TODAY, JSON.stringify(dataOf(approve)));

  const approvedDetail = await adminReq('GET', `/metrics/${METRIC_ID}`, { session: admin });
  check('审批后口径更新且版本 +0.1',
    dataOf(approvedDetail)?.metric?.definition === '验收探针口径 v2'
    && dataOf(approvedDetail)?.metric?.version === 'v1.1', approvedDetail.text);
  check('审批后无待审变更', dataOf(approvedDetail)?.pendingChange === null);

  const approveAgain = await adminReq('POST', `/metrics/${METRIC_ID}/approve`, {
    session: admin,
    body: { approve: true },
  });
  check('没有待审变更时审批被拒', codeOf(approveAgain) !== 0 && msgOf(approveAgain).includes('没有待审批'), approveAgain.text);

  const retire = await adminReq('DELETE', `/metrics/${METRIC_ID}`, { session: admin });
  check('下线指标成功', okOf(retire), retire.text);
  const retiredDetail = await adminReq('GET', `/metrics/${METRIC_ID}`, { session: admin });
  check('下线后指标状态为 2', dataOf(retiredDetail)?.metric?.status === 2, retiredDetail.text);

  // ================================================================ §4 漏斗与留存

  section('§4 漏斗与留存分析');

  const presets = await adminReq('GET', '/analysis/funnel/presets', { session: admin });
  const presetList = dataOf(presets) || [];
  check('7 个预置漏斗', okOf(presets) && presetList.length === 7, `count=${presetList.length}`);
  check('预置漏斗含 activation/register/search',
    ['activation', 'register', 'search'].every((key) => presetList.some((p) => p.key === key)));

  const funnel = await adminReq('POST', '/analysis/funnel', {
    session: admin,
    body: {
      name: '验收探针漏斗',
      start: TODAY,
      end: TODAY,
      subject: 'user',
      steps: [
        { eventName: 'page_view', label: '进入页面' },
        { eventName: 'player_song_play', label: '开始播放' },
        { eventName: 'player_song_end', label: '播放结束' },
      ],
    },
  });
  const funnelData = dataOf(funnel) || {};
  check('自定义漏斗三步均有转化',
    okOf(funnel) && funnelData.totalUsers >= 1 && funnelData.steps?.length === 3
    && funnelData.steps.every((s) => s.users >= 1), funnel.text);
  check('漏斗给出最大流失步骤', funnelData.highlightStep >= 0 && funnelData.highlightStep < 3);
  check('漏斗返回事件口径定义', Array.isArray(funnelData.definitions) && funnelData.definitions.length === 3);

  const funnelByDim = await adminReq('POST', '/analysis/funnel', {
    session: admin,
    body: {
      start: TODAY,
      end: TODAY,
      subject: 'user',
      dimension: 'platform',
      steps: [{ eventName: 'page_view' }, { eventName: 'player_song_play' }],
    },
  });
  const dimGroups = dataOf(funnelByDim)?.groups || [];
  check('漏斗可按平台分维度',
    okOf(funnelByDim) && dimGroups.some((g) => g.dimensionValue === 'web' && g.totalUsers >= 1), funnelByDim.text);

  const badFunnel = await adminReq('POST', '/analysis/funnel', {
    session: admin,
    body: { steps: [{ eventName: 'page_view' }] },
  });
  check('少于两步的漏斗被拒', codeOf(badFunnel) !== 0 && msgOf(badFunnel).includes('至少'), badFunnel.text);

  const retention = await adminReq('GET', `/analysis/retention${qs({ type: 'active', start: TODAY, end: TODAY })}`, { session: admin });
  const retentionData = dataOf(retention) || {};
  check('留存分析返回结构完整',
    okOf(retention) && retentionData.type === 'active'
    && Array.isArray(retentionData.rows) && Array.isArray(retentionData.curve)
    && retentionData.curve.length === 6 && Array.isArray(retentionData.groups), retention.text);
  check('今日活跃同期群命中探针用户',
    (retentionData.rows || []).some((r) => r.date === TODAY && r.cohortSize >= 1), JSON.stringify(retentionData.rows));
  check('留存分析带口径说明', typeof retentionData.note === 'string' && retentionData.note.includes('回访'));
  check('留存按分群返回对比', Array.isArray(retentionData.groups) && retentionData.groups.length >= 1);

  const badRetention = await adminReq('GET', '/analysis/retention?type=unknown', { session: admin });
  check('非法留存类型被拒', codeOf(badRetention) !== 0 && msgOf(badRetention).includes('new/active/play'), badRetention.text);

  // ================================================================ §5 用户画像

  section('§5 用户画像标签');

  const catalog = await adminReq('GET', '/profile-tags/catalog', { session: admin });
  const catalogTypes = new Set((dataOf(catalog) || []).map((c) => c.tagType));
  check('标签目录 10 类',
    okOf(catalog) && catalogTypes.size === 10 && ['基础属性', '生命周期', '渠道来源'].every((t) => catalogTypes.has(t)),
    catalog.text);

  const mine = await call('GET', '/users/me/profile-tags?refresh=true', { token: probe.token });
  const mineData = dataOf(mine) || {};
  check('用户可自助查看画像',
    okOf(mine) && mineData.userId === probe.userId
    && Array.isArray(mineData.catalog)
    && new Set(mineData.catalog.map((c) => c.tagType)).size === 10, mine.text);
  check('画像返回按类别分组的标签', mineData.categories && typeof mineData.categories === 'object');
  check('画像说明包含隐私提示', typeof mineData.notice === 'string' && mineData.notice.includes('敏感'));

  const recompute = await adminReq('POST', `/profile-tags/recompute${qs({ userId: probe.userId, date: TODAY })}`, { session: admin });
  const recomputeData = dataOf(recompute) || {};
  check('单用户重算画像成功',
    okOf(recompute) && recomputeData.userId === probe.userId
    && Array.isArray(recomputeData.tags) && recomputeData.tags.length > 0, recompute.text);
  const firstTag = (recomputeData.tags || [])[0] || {};
  check('重算结果带标签名、值与版本',
    Boolean(firstTag.tagName) && firstTag.tagValue !== undefined && Boolean(firstTag.tagVersion), JSON.stringify(firstTag));

  const adminView = await adminReq('GET', `/users/${probe.userId}/profile-tags`, { session: admin });
  check('管理端可查看指定用户画像',
    okOf(adminView) && dataOf(adminView).userId === probe.userId
    && dataOf(adminView).categories && typeof dataOf(adminView).categories === 'object', adminView.text);

  // ================================================================ §6 人群圈选

  section('§6 人群圈选与精度保护');

  const audienceConditions = {
    logic: 'AND',
    conditions: [{ tagName: 'lifecycle', op: 'eq', value: '新用户' }],
  };
  const preview = await adminReq('POST', '/audience/preview', {
    session: admin,
    body: { name: '预览', conditions: audienceConditions },
  });
  const previewData = dataOf(preview) || {};
  check('人群预览返回命中人数与精度阈值',
    okOf(preview) && typeof previewData.userCount === 'number'
    && previewData.minPrecision === 100 && typeof previewData.precise === 'boolean', preview.text);

  const audienceName = `验收探针人群-${RUN_TAG}`;
  const createAudience = await adminReq('POST', '/audience', {
    session: admin,
    body: { name: audienceName, conditions: audienceConditions },
  });
  const createdAudience = dataOf(createAudience) || {};
  audienceId = createdAudience.id;
  check('创建人群成功', okOf(createAudience) && Boolean(createdAudience.id) && createdAudience.name === audienceName, createAudience.text);
  check('创建人群返回命中人数', typeof createdAudience.userCount === 'number');
  check('人群精度保护标记正确',
    createdAudience.userCount < 100 ? createdAudience.precise === false : createdAudience.precise === true,
    JSON.stringify(createdAudience));

  if (audienceId) {
    const audienceDetail = await adminReq('GET', `/audience/${audienceId}`, { session: admin });
    const detailData = dataOf(audienceDetail) || {};
    check('人群详情可查', okOf(audienceDetail) && detailData.name === audienceName, audienceDetail.text);
    check('少于 100 人不返回用户明细',
      detailData.userCount >= 100 ? Array.isArray(detailData.userIds) : !Array.isArray(detailData.userIds),
      JSON.stringify({ userCount: detailData.userCount, hasUserIds: Array.isArray(detailData.userIds) }));

    const audienceList = await adminReq('GET', `/audience${qs({ pageNum: 1, pageSize: 100 })}`, { session: admin });
    check('人群列表包含新建人群',
      okOf(audienceList) && (dataOf(audienceList)?.list || []).some((a) => a.id === audienceId), audienceList.text);

    const deleteAudience = await adminReq('DELETE', `/audience/${audienceId}`, { session: admin });
    check('删除人群成功', okOf(deleteAudience) && dataOf(deleteAudience).deleted === true, deleteAudience.text);
    const gone = await adminReq('GET', `/audience/${audienceId}`, { session: admin });
    check('删除后人群不可查', codeOf(gone) !== 0, gone.text);
    audienceId = null;
  }

  const badAudience = await adminReq('POST', '/audience', {
    session: admin,
    body: { name: '空条件人群', conditions: {} },
  });
  check('空条件人群被拒', codeOf(badAudience) !== 0, badAudience.text);

  // ================================================================ §7 报表

  section('§7 运营报表、订阅、分发与导出');

  const types = await adminReq('GET', '/data-reports/types', { session: admin });
  check('12 类报表全部登记',
    okOf(types) && Object.keys(dataOf(types) || {}).length === 12
    && dataOf(types).DAILY === '日报', types.text);

  const generated = await adminReq('POST', `/data-reports/DAILY/generate${qs({ date: YESTERDAY })}`, { session: admin });
  const generatedData = dataOf(generated) || {};
  check('生成日报成功',
    okOf(generated) && generatedData.reportType === 'DAILY'
    && generatedData.reportDate === YESTERDAY && Boolean(generatedData.id), generated.text);
  check('日报含核心指标与内容',
    generatedData.content && typeof generatedData.content === 'object'
    && Boolean(generatedData.content.core || generatedData.content.sections), Object.keys(generatedData.content || {}));
  check('日报标题可读', typeof generatedData.title === 'string' && generatedData.title.includes('日报'));

  const generatedAgain = await adminReq('POST', `/data-reports/DAILY/generate${qs({ date: YESTERDAY })}`, { session: admin });
  check('重复生成同一天报告幂等（ID 不变）',
    okOf(generatedAgain) && dataOf(generatedAgain).id === generatedData.id, generatedAgain.text);
  check('同类型同日只有一份报告',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_data_report WHERE report_type='DAILY' AND report_date=${q(YESTERDAY)}`) === 1);

  const reportDetail = await adminReq('GET', `/data-reports/DAILY${qs({ date: YESTERDAY })}`, { session: admin });
  check('报表详情可查', okOf(reportDetail) && dataOf(reportDetail).id === generatedData.id, reportDetail.text);

  const reportList = await adminReq('GET', `/data-reports${qs({ type: 'DAILY', start: YESTERDAY, end: YESTERDAY })}`, { session: admin });
  check('报表列表包含日报',
    okOf(reportList) && (dataOf(reportList) || []).some((r) => r.reportType === 'DAILY' && r.reportDate === YESTERDAY), reportList.text);

  priorSubscription = dataOf(await adminReq('GET', '/data-reports/subscription', { session: admin })) || {};
  const subscribe = await adminReq('POST', `/data-reports/subscription${qs({
    reportTypes: 'DAILY,USER', frequency: 'DAILY', channel: 'IN_APP',
  })}`, { session: admin });
  check('保存日报订阅成功',
    okOf(subscribe) && dataOf(subscribe).reportTypes.includes('DAILY')
    && dataOf(subscribe).frequency === 'DAILY', subscribe.text);

  const mySub = await adminReq('GET', '/data-reports/subscription', { session: admin });
  check('我的订阅显示已订阅', okOf(mySub) && dataOf(mySub).subscribed === true, mySub.text);

  const dispatch = await adminReq('POST', `/data-reports/DAILY/dispatch${qs({ date: YESTERDAY })}`, { session: admin });
  const dispatchData = dataOf(dispatch) || {};
  check('报表分发命中订阅',
    okOf(dispatch) && dispatchData.matchedSubscriptions >= 1
    && dispatchData.delivered >= 1 && dispatchData.simulated === true, dispatch.text);
  check('分发明细标记投递成功',
    Array.isArray(dispatchData.deliveries) && dispatchData.deliveries.length >= 1
    && dispatchData.deliveries.every((d) => d.success === true), JSON.stringify(dispatchData.deliveries));

  const exportRes = await adminReq('GET', `/data-reports/DAILY/export${qs({ date: YESTERDAY })}`, {
    session: admin, bytes: true,
  });
  const csv = exportRes.text || '';
  check('CSV 导出返回成功',
    exportRes.status === 200 && (exportRes.headers.get('content-type') || '').includes('text/csv'), exportRes.status);
  check('CSV 带 UTF-8 BOM 与三列表头',
    exportRes.raw?.[0] === 0xef && exportRes.raw?.[1] === 0xbb && exportRes.raw?.[2] === 0xbf
    && csv.includes('分区,指标,数值'), csv.slice(0, 40));
  check('CSV 含报表数据行', csv.split('\n').filter((line) => line.trim()).length > 2);

  const cancelSub = await adminReq('DELETE', '/data-reports/subscription', { session: admin });
  check('取消订阅成功', okOf(cancelSub) && dataOf(cancelSub).subscribed === false, cancelSub.text);

  const badReport = await adminReq('GET', '/data-reports/NOT_A_TYPE', { session: admin });
  check('未知报表类型被拒', codeOf(badReport) !== 0 && msgOf(badReport).includes('报表类型'), badReport.text);

  // ================================================================ §8 数仓与治理

  section('§8 数仓 ETL、回溯、质量、对账与血缘');

  const etl = await adminReq('POST', `/data-platform/etl${qs({ date: TODAY })}`, { session: admin });
  const etlData = dataOf(etl) || {};
  check('ETL 加工今日数仓成功',
    okOf(etl) && etlData.date === TODAY && etlData.idempotent === true
    && Array.isArray(etlData.metrics) && etlData.metrics.length >= 10, etl.text);
  check('ETL 产出 DWS 用户/歌曲与 ADS 指标',
    etlData.dwsUsers >= 1 && etlData.dwsSongs >= 1 && etlData.adsRows >= 10,
    JSON.stringify({ dwsUsers: etlData.dwsUsers, dwsSongs: etlData.dwsSongs, adsRows: etlData.adsRows }));
  check('DWD 播放明细已生成',
    mysqlCount(`SELECT COUNT(*) FROM newmusic.t_dwd_play_detail WHERE dt=${q(TODAY)}`) >= 2);

  const etlAgain = await adminReq('POST', `/data-platform/etl${qs({ date: TODAY })}`, { session: admin });
  check('ETL 重跑幂等（DWD 不重复插入）',
    okOf(etlAgain) && dataOf(etlAgain).dwdInserted === 0, etlAgain.text);
  check('ETL 重跑 ADS 行数稳定',
    dataOf(etlAgain).adsRows === etlData.adsRows,
    `first=${etlData.adsRows} second=${dataOf(etlAgain).adsRows}`);

  const trendAfterEtl = await adminReq('GET', `/dashboard/trend${qs({ metricId: 'metric_play_cnt', start: TODAY, end: TODAY })}`, { session: admin });
  const adsPoint = (dataOf(trendAfterEtl)?.points || []).find((p) => p.date === TODAY);
  check('ETL 后趋势优先读 ADS',
    dataOf(trendAfterEtl)?.source === 'ADS' && Number(adsPoint?.value || 0) >= 2, trendAfterEtl.text);

  const backfill = await adminReq('POST', `/data-platform/etl/backfill${qs({ start: dayOffset(-1), end: TODAY })}`, { session: admin });
  const backfillData = dataOf(backfill) || {};
  check('回溯重算两天成功',
    okOf(backfill) && backfillData.days === 2 && (backfillData.results || []).length === 2, backfill.text);
  check('回溯结果逐日返回幂等标记',
    (backfillData.results || []).every((r) => r.idempotent === true));

  const badBackfill = await adminReq('POST', `/data-platform/etl/backfill${qs({ start: dayOffset(-100), end: TODAY })}`, { session: admin });
  check('回溯超过 90 天被拒', codeOf(badBackfill) !== 0 && msgOf(badBackfill).includes('90'), badBackfill.text);

  const qualityRun = await adminReq('POST', `/data-quality/run${qs({ date: TODAY })}`, { session: admin });
  const qualityData = dataOf(qualityRun) || {};
  check('质量校验执行成功',
    okOf(qualityRun) && qualityData.checkDate === TODAY
    && qualityData.total >= 1 && qualityData.idempotent === true, qualityRun.text);
  check('质量结果统计自洽',
    qualityData.passed + qualityData.failed === qualityData.total
    && typeof qualityData.score === 'number'
    && ['healthy', 'warning', 'degraded', 'blocked'].includes(qualityData.health), qualityRun.text);
  check('质量校验返回阻塞标记与问题列表',
    typeof qualityData.blocked === 'boolean' && Array.isArray(qualityData.issues));

  const qualityRunAgain = await adminReq('POST', `/data-quality/run${qs({ date: TODAY })}`, { session: admin });
  check('质量校验重跑幂等（规则数不变）',
    okOf(qualityRunAgain) && dataOf(qualityRunAgain).total === qualityData.total
    && dataOf(qualityRunAgain).passed === qualityData.passed, qualityRunAgain.text);

  const qualityReport = await adminReq('GET', `/data-quality${qs({ date: TODAY })}`, { session: admin });
  check('质量报告可查',
    okOf(qualityReport) && dataOf(qualityReport).checkDate === TODAY
    && Array.isArray(dataOf(qualityReport).items)
    && dataOf(qualityReport).items.length === qualityData.total, qualityReport.text);
  check('质量报告包含 ODS/DWD 表级检查',
    (dataOf(qualityReport).items || []).some((i) => i.table === 't_track_event')
    && (dataOf(qualityReport).items || []).some((i) => i.table === 't_dwd_play_detail'));

  const reconciliationRun = await adminReq('POST', `/reconciliation/run${qs({ date: TODAY })}`, { session: admin });
  const recData = dataOf(reconciliationRun) || {};
  check('对账执行成功并返回明细',
    okOf(reconciliationRun) && recData.checkDate === TODAY
    && recData.total >= 1 && Array.isArray(recData.items)
    && recData.items.length === recData.total && recData.idempotent === true, reconciliationRun.text);
  check('对账项包含数据仓库与业务库取值',
    (recData.items || []).every((i) => i.dwValue !== undefined && i.bizValue !== undefined && typeof i.passed === 'boolean'));

  const reconciliationList = await adminReq('GET', `/reconciliation${qs({ date: TODAY })}`, { session: admin });
  check('对账结果列表与执行结果一致',
    okOf(reconciliationList) && (dataOf(reconciliationList) || []).length === recData.total, reconciliationList.text);

  const lineage = await adminReq('GET', `/lineage${qs({ table: 't_dwd_play_detail' })}`, { session: admin });
  const lineageRows = dataOf(lineage) || [];
  check('血缘可按表筛选', okOf(lineage) && lineageRows.length >= 3 && lineageRows.every((r) => r.tableName === 't_dwd_play_detail'), lineage.text);
  check('血缘包含字段级上下游',
    lineageRows.some((r) => r.columnName === 'song_id' && r.upstreamTable === 't_track_event' && r.jobName === 'dwd_play_detail'));

  const lineageAll = await adminReq('GET', '/lineage', { session: admin });
  check('全量血缘覆盖 ODS→DWD→DWS→ADS',
    okOf(lineageAll) && (dataOf(lineageAll) || []).length >= 10
    && ['t_dwd_play_detail', 't_dws_user_daily', 't_ads_daily_metrics', 't_user_profile_tag']
      .every((table) => (dataOf(lineageAll) || []).some((r) => r.tableName === table)), lineageAll.text);
}

// ---------------------------------------------------------------- 清理与快照

async function cleanup() {
  try {
    if (admin && priorSubscription && priorSubscription.subscribed) {
      await adminReq('POST', `/data-reports/subscription${qs({
        reportTypes: priorSubscription.reportTypes,
        frequency: priorSubscription.frequency,
        channel: priorSubscription.channel,
      })}`, { session: admin });
    }
    if (admin && audienceId) {
      await adminReq('DELETE', `/audience/${audienceId}`, { session: admin });
    }
  } catch (e) {
    note(`恢复订阅/人群失败（不影响验收结论）：${e.message}`);
  }

  if (REDIS_OK) {
    const keys = [];
    for (const id of createdEventIds) keys.push(`ml:track:dedup:${id}`);
    for (const id of createdDirtyIds) keys.push(`ml:track:dedup:${id}`);
    const minute = Math.floor(Date.now() / 1000 / 60);
    for (const device of [DEVICE, RATE_DEVICE, SAMPLE_DEVICE]) {
      keys.push(`ml:track:rate:${device}:${minute}`);
      keys.push(`ml:track:rate:${device}:${minute - 1}`);
    }
    try {
      redisDel(...keys);
    } catch (e) {
      note(`清理 Redis 探针键失败：${e.message}`);
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
  console.log(`\n\x1b[36m模块 17 验收：${pass} 通过 / ${fail} 失败 / ${skipped} 跳过\x1b[0m`);
  if (fail > 0) {
    console.log(`\n失败项：`);
    for (const item of failures) console.log(`  - ${item}`);
  }
  if (skipNotes.length) {
    console.log(`\n跳过项：`);
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
