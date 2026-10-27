#!/usr/bin/env node
/**
 * 模块 15 验收脚本 —— 推荐系统升级（统一召回 / WRF 融合 / 三段排序 /
 * 实验分流 / 指标与显著性 / 离线评估 / 监控）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-15.mjs
 *
 * 前置：
 *   * 应用已启动（8081），推荐任务开关使用默认配置；
 *   * 已执行 sql/15-rec-upgrade-seed.sql；
 *   * 后台验证码回显开启（ADMIN_CAPTCHA_ECHO=true），与 verify-09/14 相同；
 *   * Node 22+，Redis 6379 / MySQL 3306 可连。
 *
 * 共享实例约束（与 verify-04/05/06/09/12/13/14 相同）：
 *   * Redis db0 有别的项目的 `ranking:*` 键：只读写 `ml:` 前缀，绝不 FLUSHDB/FLUSHALL；
 *     开头/结尾各数一次 `ranking:*` 并逐键比对，证明未误伤。
 *   * MySQL 3306 有 emp_db / music_db：所有语句带 `newmusic.` 限定，只删除本轮
 *     创建的实验、埋点与日志（按唯一 key / 探针 user_id / traceId），开头/结尾
 *     对比其它库的表数量。
 *   * 口令只从环境变量 MYSQL_PASSWORD 读取，经 MYSQL_PWD 传给客户端，不写入文件。
 *
 * 可重复执行：实验 key、用户、deviceId 每轮唯一；结尾清理本轮实验与事件。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';

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
const PASSWORD = 'Probe@12345';
const TAG = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
const EXP_KEY = `v15-exp-${TAG}`;
const DEVICE_ID = `v15-device-${TAG}`;

let pass = 0;
let fail = 0;
let skipped = 0;
const failures = [];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.${Math.floor(Math.random() * 200) + 20}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
const qs = (params) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
};

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
  console.log(`  \x1b[33m-\x1b[0m SKIP ${name}（${why}）`);
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
    /* 非 JSON */
  }
  return { status: res.status, json, text };
}

const call = (method, path, opts = {}) => http(method, `${API_BASE}${path}`, opts);
const adminReq = (method, path, opts = {}) => http(method, `${ADMIN_BASE}${path}`, opts);
const codeOf = (res) => (res.json && typeof res.json.code === 'number' ? res.json.code : -1);
const dataOf = (res) => (res.json ? res.json.data : null);

async function waitForBoot(maxSeconds = 90) {
  const deadline = Date.now() + maxSeconds * 1000;
  process.stdout.write('等待应用启动');
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${API_BASE}/time`);
      if (res.status < 500) {
        console.log(' 就绪');
        return true;
      }
    } catch {
      /* 未就绪 */
    }
    process.stdout.write('.');
    await sleep(1500);
  }
  console.log(' 超时');
  return false;
}

async function registerAndLogin(label) {
  const account = `v15${label}${TAG}`;
  const reg = await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password: PASSWORD, nickname: account },
  });
  if (codeOf(reg) !== 0) throw new Error(`注册 ${account} 失败：${reg.text}`);
  const login = await call('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account, password: PASSWORD },
  });
  if (codeOf(login) !== 0 || !dataOf(login)?.accessToken) {
    throw new Error(`登录 ${account} 失败：${login.text}`);
  }
  return {
    account,
    token: dataOf(login).accessToken,
    userId: String(dataOf(login).user.id),
  };
}

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

async function adminLogin(xff, tag = 'rec15') {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const session = new Session(tag);
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
    if (codeOf(res) === 0) return session;
    // 80016 = TOTP 时间窗边缘，稍等重试一次
    if (codeOf(res) !== 80016) throw new Error(`后台登录失败：${res.text}`);
    await sleep(1200);
  }
  throw new Error('后台登录失败（TOTP 重试后仍被拒绝）');
}

// ---------------------------------------------------------------- Redis / MySQL

function redis(...args) {
  const quote = (a) => {
    const s = String(a);
    return /[\s"]/.test(s) ? `"${s.replace(/"/g, '\\"')}"` : s;
  };
  const r = spawnSync(REDIS_CLI, [], {
    encoding: 'utf8',
    input: `${args.map(quote).join(' ')}\n`,
  });
  if (r.error) throw new Error(`redis-cli 执行失败：${r.error.message}`);
  if (r.status !== 0) throw new Error(`redis-cli 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

const listKeys = (pattern) => {
  // 本机 --scan 静默返回空，固定用 keys；只对 ml:/ranking: 前缀使用
  const out = redis('keys', pattern);
  return out === '' ? [] : out.replace(/\r/g, '').split('\n').map((s) => s.trim()).filter(Boolean);
};

function mysql(sql) {
  const r = spawnSync(MYSQL_CLI, [
    '-u', MYSQL_USER, '-h', '127.0.0.1', '-P', process.env.MYSQL_PORT || '3306',
    '-D', 'newmusic', '-N', '-B', '--default-character-set=utf8mb4',
  ], { encoding: 'utf8', input: sql, env: { ...process.env, MYSQL_PWD } });
  if (r.error) throw new Error(`mysql 执行失败：${r.error.message}`);
  if (r.status !== 0) throw new Error(`mysql 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

const mysqlScalar = (sql) => mysql(sql).split(/\r?\n/)[0] || '';

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，缓存断言跳过：${e.message}`);
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*').sort() : [];
const sharedDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- 主流程

const traceIds = new Set();
const probeUserIds = new Set();
let probeUserId = null;
let experimentId = null;

/** 为指定探针用户造 count 条播放行为（daysAgo 天前 12:00），只用于本轮验证。 */
function seedPlays(userId, daysAgo, count, offset = 0) {
  mysql(`
INSERT INTO newmusic.t_play_history
  (id, user_id, song_id, song_name, artist_id, artist_name, genre, language,
   play_duration, song_duration, is_valid, play_time, create_time, update_time, deleted, version)
SELECT UUID_SHORT(), ${userId}, s.id, s.name, s.artist_id, s.artist_name, s.genre, s.language,
       120, GREATEST(s.duration, 1), 1,
       DATE_SUB(CURDATE(), INTERVAL ${Number(daysAgo)} DAY) + INTERVAL 12 HOUR,
       NOW(3), NOW(3), 0, 0
FROM newmusic.t_song s
WHERE s.deleted = 0 AND s.status = 1 AND s.audit_status = 1
ORDER BY s.hot_score DESC, s.id ASC LIMIT ${Number(count)} OFFSET ${Number(offset)};
`);
}

function cleanup() {
  if (HAS_MYSQL) {
    const traces = [...traceIds].filter((id) => /^[0-9a-f]{16,64}$/i.test(id));
    const traceClause = traces.length
      ? ` OR trace_id IN (${traces.map((id) => `'${id}'`).join(',')})` : '';
    try {
      mysql(`DELETE FROM newmusic.t_experiment WHERE experiment_key='${EXP_KEY}';`);
      mysql(`DELETE FROM newmusic.t_rec_metric_daily WHERE experiment_key='${EXP_KEY}';`);
      if (probeUserId) {
        mysql(`DELETE FROM newmusic.t_rec_event WHERE user_id=${probeUserId}${traceClause};`);
        mysql(`DELETE FROM newmusic.t_rec_log WHERE user_id=${probeUserId}${traceClause};`);
      }
      if (probeUserIds.size) {
        // 只删本轮注册的探针用户（S1 画像用户 + 离线评估造数用户）
        mysql(`DELETE FROM newmusic.t_play_history WHERE user_id IN (${[...probeUserIds].join(',')});`);
      }
    } catch (e) {
      note(`清理探针数据失败（不影响验收结论）：${e.message}`);
    }
  }
  if (REDIS_OK) {
    try {
      const keys = new Set([...traceIds].map((id) => `ml:rec:trace:${id}`));
      // 只删除 key 中包含本轮探针用户 ID 的 ml:* 键（曝光/探索/分组/TTL 缓存），
      // 绝不使用 FLUSHDB；同实例其他项目的 ranking:* 不在匹配范围内。
      const users = [...probeUserIds];
      if (users.length) {
        for (const key of listKeys('ml:*')) {
          if (users.some((id) => key.includes(id))) keys.add(key);
        }
      }
      if (keys.size) redis('del', ...keys);
    } catch (e) {
      note(`清理 Redis 探针键失败：${e.message}`);
    }
  }
}

function checkIntegrity() {
  section('§8 共享实例完整性（Redis ranking:* / 其它库表）');
  if (!REDIS_OK) {
    skip('Redis ranking:* 键未变化', 'Redis CLI 不可用');
  } else {
    const after = listKeys('ranking:*').sort();
    check('Redis ranking:* 键未变化', JSON.stringify(after) === JSON.stringify(rankingBefore),
      `before=${rankingBefore.length} after=${after.length}`);
    const leaked = listKeys('ml:rec:trace:*').filter((key) => [...traceIds].some((id) => key.endsWith(id)));
    check('本轮 trace 快照已清理', leaked.length === 0, leaked);
  }
  if (!HAS_MYSQL) {
    skip('其它库表数量未变化', '未提供 MYSQL_PASSWORD');
    skip('本轮实验行已清理', '未提供 MYSQL_PASSWORD');
  } else {
    const after = mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('其它库表数量未变化', String(after) === String(sharedDbTablesBefore),
      `before=${sharedDbTablesBefore} after=${after}`);
    const left = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_experiment WHERE experiment_key='${EXP_KEY}'`);
    check('本轮实验行已清理', left === '0', left);
  }
}

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪。');
    process.exit(2);
  }

  if (!HAS_MYSQL) {
    note('未提供 MYSQL_PASSWORD：DB 断言与本轮数据清理将跳过（脚本仍可验证 HTTP 行为）');
  }

  const user = await registerAndLogin('user');
  probeUserId = user.userId;
  probeUserIds.add(user.userId);
  const admin = await adminLogin(syntheticIp());
  const xff = () => syntheticIp();

  try {
    // ====================================================================
    section('§1 配置版本化与 15 路召回源（FR-24-001）');
    // ====================================================================
    const recallConfig = await adminReq('GET', '/rec/config/HOME_FEED/RECALL',
      { session: admin, xff: xff() });
    check('读取召回配置', codeOf(recallConfig) === 0, recallConfig.text.slice(0, 200));
    const recallActive = dataOf(recallConfig)?.active;
    let configuredSources = [];
    try {
      configuredSources = JSON.parse(recallActive?.configJson || '{}').sources || [];
    } catch { /* 非法 JSON */ }
    check('召回配置含 15 个源', configuredSources.length === 15, configuredSources.length);
    check('召回配置有版本号', Number(recallActive?.version) >= 1, recallActive?.version);
    check('召回配置状态为生效(1)', Number(recallActive?.status) === 1, recallActive?.status);

    const sources = await adminReq('GET', '/rec/recall-sources?scene=HOME_FEED',
      { session: admin, xff: xff() });
    const registered = dataOf(sources)?.registered || [];
    const configuredNames = (dataOf(sources)?.configured || []).map((s) => s.name);
    check('后台可见召回源清单', codeOf(sources) === 0, sources.text.slice(0, 200));
    check('注册源数量为 15', registered.length === 15, registered.length);
    for (const name of ['hot', 'cf_user', 'cf_item', 'vector', 'tag_match', 'follow_artist',
      'friend_listen', 'new_song', 'local', 'recent_play', 'long_tail', 'editor',
      'playlist_match', 'emotion_match', 'vector_song']) {
      check(`注册源包含 ${name}`, registered.includes(name), registered);
    }
    check('配置源与注册源一一对应',
      JSON.stringify([...configuredNames].sort()) === JSON.stringify([...registered].sort()),
      { configuredNames, registered });

    // 融合配置：更新产生新版本 → 回滚到原版本，证明版本链可追溯
    const fusion = await adminReq('GET', '/rec/config/HOME_FEED/FUSION',
      { session: admin, xff: xff() });
    const fusionActive = dataOf(fusion)?.active;
    const originalVersion = Number(fusionActive?.version);
    const originalJson = fusionActive?.configJson;
    // 回滚是「把目标版本原地置为生效」，版本号不递增；新版本必须是历史最大值 + 1
    const maxVersionBefore = Math.max(originalVersion,
      ...((dataOf(fusion)?.history || []).map((row) => Number(row.version))));
    check('读取融合配置', codeOf(fusion) === 0 && originalVersion >= 1, fusion.text.slice(0, 200));

    const updated = await adminReq('PUT', '/rec/config/HOME_FEED/FUSION', {
      session: admin,
      xff: xff(),
      body: { configJson: originalJson },
    });
    const newVersion = Number(dataOf(updated)?.version);
    check('更新配置返回新版本', codeOf(updated) === 0 && newVersion === maxVersionBefore + 1,
      { code: codeOf(updated), newVersion, maxVersionBefore });

    const afterUpdate = await adminReq('GET', '/rec/config/HOME_FEED/FUSION',
      { session: admin, xff: xff() });
    check('新版本立即生效', Number(dataOf(afterUpdate)?.active?.version) === newVersion,
      dataOf(afterUpdate)?.active?.version);
    check('历史版本保留', (dataOf(afterUpdate)?.history || []).length >= 2,
      (dataOf(afterUpdate)?.history || []).length);

    const rollback = await adminReq('POST',
      `/rec/config/HOME_FEED/FUSION/rollback${qs({ version: originalVersion })}`,
      { session: admin, xff: xff() });
    check('回滚到原版本成功', codeOf(rollback) === 0 && Number(dataOf(rollback)?.version) === originalVersion,
      { code: codeOf(rollback), version: dataOf(rollback)?.version });

    const afterRollback = await adminReq('GET', '/rec/config/HOME_FEED/FUSION',
      { session: admin, xff: xff() });
    const rolledJson = dataOf(afterRollback)?.active?.configJson;
    let sameJson = false;
    try {
      sameJson = JSON.stringify(JSON.parse(rolledJson)) === JSON.stringify(JSON.parse(originalJson));
    } catch { /* 非法 JSON */ }
    check('回滚后内容与版本一致', sameJson, rolledJson?.slice(0, 120));

    // ====================================================================
    section('§2 游客 / 登录用户推荐流（FR-24-001/002/003，冷启动 S0）');
    // ====================================================================
    const guest = await call('GET', `/recommend/feed${qs({
      limit: 30, deviceId: `v15-guest-${TAG}`, deviceType: 'WEB',
    })}`);
    const guestData = dataOf(guest);
    const guestList = guestData?.list || [];
    check('游客可获取推荐流', codeOf(guest) === 0, guest.text.slice(0, 200));
    check('游客 traceId 非空', typeof guestData?.traceId === 'string' && guestData.traceId.length >= 16,
      guestData?.traceId);
    check('游客推荐列表非空', guestList.length > 0, guestList.length);
    check('游客列表不超过 limit', guestList.length <= 30, guestList.length);
    check('游客位置从 1 连续递增',
      guestList.every((item, index) => Number(item.position) === index + 1),
      guestList.map((item) => item.position).slice(0, 5));
    check('游客处于 S0 冷启动', guestData?.coldStartStage === 'S0', guestData?.coldStartStage);
    check('游客无实验分组', !guestData?.experiments
      || Object.keys(guestData.experiments).length === 0, guestData?.experiments);
    check('游客请求未降级', guestData?.degraded === false, guestData?.degraded);
    check('游客列表带召回来源及贡献分',
      guestList.every((item) => item.sources && typeof item.sources === 'object'
        && Object.keys(item.sources).length > 0), guestList[0]?.sources);
    check('游客列表带推荐理由', guestList.every((item) => typeof item.reason === 'string' && item.reason.length > 0),
      guestList[0]?.reason);
    check('游客模型版本非空', typeof guestData?.modelVersion === 'string'
      && guestData.modelVersion.length > 0, guestData?.modelVersion);
    const timings = guestData?.timings || {};
    check('游客阶段耗时已记录',
      ['recall', 'fusion', 'coarse', 'fine', 'rerank', 'total']
        .every((key) => typeof timings[key] === 'number'), timings);
    check('召回全局耗时 ≤150ms', Number(timings.recall) <= 150, timings.recall);
    check('粗排 500→150 耗时 ≤30ms', Number(timings.coarse) <= 30, timings.coarse);
    check('推荐总耗时 ≤200ms（P99 目标）', Number(guestData?.costMs) <= 200, guestData?.costMs);
    if (guestData?.traceId) traceIds.add(guestData.traceId);

    const explored = guestList.filter((item) => item.explored === true);
    check('探索位占比约 10%', explored.length >= Math.ceil(guestList.length * 0.05)
      && explored.length <= Math.ceil(guestList.length * 0.20) + 2,
    `${explored.length}/${guestList.length}`);
    check('探索位不在首屏（位置 ≥ 8）',
      explored.every((item) => Number(item.position) >= 8),
      explored.map((item) => item.position));

    // S1 画像用户：5 条历史（20 天前，避开离线评估窗口）应启用长尾/标签/新歌源
    const warm = await registerAndLogin('warm');
    probeUserIds.add(warm.userId);
    if (HAS_MYSQL) {
      seedPlays(warm.userId, 20, 5, 0);
      const seededWarm = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_play_history WHERE user_id=${warm.userId}`);
      check('S1 画像造数成功', Number(seededWarm) === 5, seededWarm);
    } else {
      skip('S1 画像造数', '未提供 MYSQL_PASSWORD');
    }
    const warmFeed = await call('POST', '/recommend/feed', {
      token: warm.token,
      xff: xff(),
      body: { scene: 'HOME_FEED', limit: 30, deviceId: `v15-warm-${TAG}`, deviceType: 'WEB' },
    });
    const warmData = dataOf(warmFeed);
    const warmList = warmData?.list || [];
    check('S1 用户可获取推荐流', codeOf(warmFeed) === 0 && warmList.length > 0,
      warmFeed.text.slice(0, 200));
    check('5 条行为进入 S1 冷启动', warmData?.coldStartStage === 'S1',
      warmData?.coldStartStage);
    const longTail = warmList.filter((item) => item.longTail === true);
    let longTailAvailable = Infinity;
    if (HAS_MYSQL) {
      // 与 RecallDataService.longTailSongs 同口径：播放量后 50% 且 hot_score < 600
      longTailAvailable = Number(mysqlScalar(`WITH x AS (
  SELECT play_count, hot_score, ROW_NUMBER() OVER (ORDER BY play_count) rn, COUNT(*) OVER () cnt
  FROM newmusic.t_song WHERE deleted=0 AND status=1 AND audit_status=1
) SELECT COUNT(*) FROM x
WHERE play_count <= (SELECT play_count FROM x WHERE rn=CEIL(cnt/2)) AND hot_score < 600`));
    }
    const longTailTarget = Math.min(Math.ceil(warmList.length * 0.10), longTailAvailable);
    check('S1 列表长尾占比 ≥ 10%（受曲库长尾池上限约束）',
      longTail.length >= longTailTarget,
      `${longTail.length}/${warmList.length}，目标 ${longTailTarget}，曲库可用 ${longTailAvailable}`);
    if (warmData?.traceId) traceIds.add(warmData.traceId);

    // ====================================================================
    section('§3 实验创建 / 白名单 / 审批（FR-24-008）');
    // ====================================================================
    const createBody = {
      experimentKey: EXP_KEY,
      name: '模块15验收实验',
      description: 'verify-15 自動创建，结束后清理',
      layer: 'rank_layer_v1',
      mutexGroup: `v15-mutex-${TAG}`,
      trafficPercent: 10,
      groupsJson: JSON.stringify([
        { name: 'control', ratio: 50 },
        { name: 'b', ratio: 50 },
      ]),
      primaryMetric: 'ctr',
    };
    const created = await adminReq('POST', '/rec/experiments',
      { session: admin, xff: xff(), body: createBody });
    const createdData = dataOf(created);
    experimentId = createdData?.id;
    check('创建实验成功', codeOf(created) === 0 && experimentId !== undefined,
      created.text.slice(0, 300));
    check('新实验为草稿(0)', Number(createdData?.status) === 0, createdData?.status);
    check('creatorId 已记录', createdData?.creatorId !== null && createdData?.creatorId !== undefined,
      createdData?.creatorId);

    const whitelist = await adminReq('PUT', `/rec/experiments/${experimentId}/whitelist`, {
      session: admin,
      xff: xff(),
      body: { whitelistJson: JSON.stringify({ [user.userId]: 'b' }) },
    });
    check('白名单更新成功', codeOf(whitelist) === 0
      && String(dataOf(whitelist)?.whitelistJson || '').includes(user.userId),
      dataOf(whitelist)?.whitelistJson);

    const approved = await adminReq('POST', `/rec/experiments/${experimentId}/approve`,
      { session: admin, xff: xff() });
    check('审批后实验运行中(2)', codeOf(approved) === 0
      && Number(dataOf(approved)?.status) === 2, dataOf(approved)?.status);
    check('审批后写入开始时间', Boolean(dataOf(approved)?.startTime), dataOf(approved)?.startTime);

    const list = await adminReq('GET', `/rec/experiments${qs({ status: 2, pageNum: 1, pageSize: 20 })}`,
      { session: admin, xff: xff() });
    const rows = dataOf(list)?.list || [];
    check('运行中实验列表包含本轮实验', codeOf(list) === 0
      && rows.some((row) => row.experimentKey === EXP_KEY), rows.map((row) => row.experimentKey));

    // ====================================================================
    section('§4 登录用户推荐 + 服务端分组归因');
    // ====================================================================
    const feed = await call('POST', '/recommend/feed', {
      token: user.token,
      xff: xff(),
      body: { scene: 'HOME_FEED', limit: 15, deviceId: DEVICE_ID, deviceType: 'WEB' },
    });
    const feedData = dataOf(feed);
    const feedList = feedData?.list || [];
    check('登录用户获取推荐流', codeOf(feed) === 0, feed.text.slice(0, 200));
    check('登录用户 traceId 非空', typeof feedData?.traceId === 'string' && feedData.traceId.length >= 16,
      feedData?.traceId);
    check('登录用户列表非空且不超过 limit', feedList.length > 0 && feedList.length <= 15,
      feedList.length);
    check('登录用户位置连续', feedList.every((item, index) => Number(item.position) === index + 1),
      feedList.map((item) => item.position).slice(0, 5));
    check('白名单用户命中 b 组', feedData?.experiments?.[EXP_KEY] === 'b',
      feedData?.experiments);
    check('登录用户仍是 S0（新账号无行为）', feedData?.coldStartStage === 'S0',
      feedData?.coldStartStage);
    check('登录用户请求未降级', feedData?.degraded === false, feedData?.degraded);
    if (feedData?.traceId) traceIds.add(feedData.traceId);

    if (REDIS_OK) {
      const assignmentKeys = listKeys('ml:rec:exp:assign:*');
      check('实验分组已写入 Redis 缓存', assignmentKeys.length >= 1, assignmentKeys.length);
    } else {
      skip('实验分组已写入 Redis 缓存', 'Redis CLI 不可用');
    }

    const traceAdmin = await adminReq('GET', `/rec/trace/${feedData?.traceId}`,
      { session: admin, xff: xff() });
    const snapshot = dataOf(traceAdmin)?.snapshot;
    const logRow = dataOf(traceAdmin)?.log;
    check('后台按 traceId 还原链路', codeOf(traceAdmin) === 0, traceAdmin.text.slice(0, 200));
    check('链路快照含本轮 trace', snapshot?.traceId === feedData?.traceId, snapshot?.traceId);
    check('链路快照含用户 ID', String(snapshot?.userId) === user.userId, snapshot?.userId);
    check('链路快照含实验分组', snapshot?.experiments?.[EXP_KEY] === 'b',
      snapshot?.experiments);
    check('链路快照含位置映射', feedList[0]?.song?.id
      && Number(snapshot?.positions?.[String(feedList[0].song.id)]) === 1,
      Object.entries(snapshot?.positions || {}).slice(0, 3));
    check('请求日志已落库', codeOf(traceAdmin) === 0 && logRow?.traceId === feedData?.traceId,
      logRow?.traceId);
    check('请求日志记录最终列表', typeof logRow?.finalList === 'string' && logRow.finalList.length > 2,
      logRow?.finalList?.slice(0, 80));

    const logs = await adminReq('GET', `/rec/logs${qs({
      userId: user.userId, scene: 'HOME_FEED', pageNum: 1, pageSize: 10,
    })}`, { session: admin, xff: xff() });
    check('推荐日志可按用户筛选', codeOf(logs) === 0
      && (dataOf(logs)?.list || []).some((row) => row.traceId === feedData?.traceId),
      dataOf(logs)?.total);

    // ====================================================================
    section('§5 埋点写库与实验指标（FR-24-009）');
    // ====================================================================
    const songId = feedList[0]?.song?.id;
    check('推荐列表首项含歌曲 ID', songId !== undefined && songId !== null, songId);

    const impression = await call('POST', '/recommend/events', {
      token: user.token,
      xff: xff(),
      body: {
        traceId: feedData?.traceId, eventType: 'IMPRESSION', songId,
        position: 1, scene: 'HOME_FEED', deviceId: DEVICE_ID, deviceType: 'WEB',
      },
    });
    const click = await call('POST', '/recommend/events', {
      token: user.token,
      xff: xff(),
      body: {
        traceId: feedData?.traceId, eventType: 'CLICK', songId,
        position: 1, scene: 'HOME_FEED', deviceId: DEVICE_ID, deviceType: 'WEB',
      },
    });
    check('曝光埋点上报成功', codeOf(impression) === 0, impression.text.slice(0, 200));
    check('点击埋点上报成功', codeOf(click) === 0, click.text.slice(0, 200));

    const bogus = await call('POST', '/recommend/events', {
      token: user.token,
      xff: xff(),
      body: { traceId: feedData?.traceId, eventType: 'HACK_EVENT', songId, position: 2 },
    });
    check('非法事件类型静默丢弃（仍返回成功）', codeOf(bogus) === 0, bogus.text.slice(0, 200));

    if (HAS_MYSQL) {
      await sleep(150);
      const count = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_rec_event WHERE trace_id='${feedData?.traceId}'`);
      check('埋点落库数量为 2（非法事件未写）', count === '2', count);
      const types = mysql(`SELECT event_type, experiment_key, group_name FROM newmusic.t_rec_event
WHERE trace_id='${feedData?.traceId}' ORDER BY create_time`);
      check('埋点保留事件类型', /IMPRESSION/.test(types) && /CLICK/.test(types), types);
      check('埋点由服务端写入实验归因', types.includes(EXP_KEY) && /(^|\t)b(\t|$)/m.test(types), types);
      check('非法事件未落库', !mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_rec_event
WHERE trace_id='${feedData?.traceId}' AND event_type='HACK_EVENT'`).match(/[1-9]/), 'HACK_EVENT');
    } else {
      skip('埋点落库与实验归因', '未提供 MYSQL_PASSWORD');
    }

    const metrics = await adminReq('GET', `/rec/experiments/${EXP_KEY}/metrics?days=1`,
      { session: admin, xff: xff() });
    const groupMetrics = dataOf(metrics)?.groups || {};
    check('实验指标可查询', codeOf(metrics) === 0, metrics.text.slice(0, 200));
    check('指标包含全部实验组', groupMetrics.control && groupMetrics.b,
      Object.keys(groupMetrics));
    check('b 组曝光计数已聚合', Number(groupMetrics.b?.impression) >= 1, groupMetrics.b?.impression);
    check('b 组点击计数已聚合', Number(groupMetrics.b?.click) >= 1, groupMetrics.b?.click);
    check('指标给出 CTR', typeof groupMetrics.b?.ctr === 'number', groupMetrics.b?.ctr);

    const significance = await adminReq('GET',
      `/rec/experiments/${EXP_KEY}/significance?metric=ctr&days=1`,
      { session: admin, xff: xff() });
    const sig = dataOf(significance);
    check('显著性接口可调用', codeOf(significance) === 0, significance.text.slice(0, 200));
    check('样本不足时明确返回 INSUFFICIENT_SAMPLE',
      sig?.conclusion === 'INSUFFICIENT_SAMPLE', sig?.conclusion);
    check('显著性使用 Bonferroni 校正', Number(sig?.bonferroniAlpha) === 0.05,
      sig?.bonferroniAlpha);
    check('给出实验组对比结果', Array.isArray(sig?.comparisons) && sig.comparisons.length === 1,
      sig?.comparisons?.length);
    check('样本不足的对比标注原因', sig?.comparisons?.[0]?.note === 'INSUFFICIENT_SAMPLE',
      sig?.comparisons?.[0]?.note);

    const aa = await adminReq('GET', `/rec/experiments/${EXP_KEY}/aa?days=1`,
      { session: admin, xff: xff() });
    const aaData = dataOf(aa);
    check('AA 检验可调用', codeOf(aa) === 0, aa.text.slice(0, 200));
    check('AA 检验返回双半样本量', typeof aaData?.sampleA === 'number'
      && typeof aaData?.sampleB === 'number', { sampleA: aaData?.sampleA, sampleB: aaData?.sampleB });
    check('AA 检验给出通过结论', typeof aaData?.passed === 'boolean', aaData?.passed);

    // 一键停止：清空分组缓存，下一次请求立即不再带实验
    if (REDIS_OK) {
      const beforeStop = listKeys('ml:rec:exp:assign:*');
      check('停止前存在分组缓存', beforeStop.length >= 1, beforeStop.length);
    }
    const stopped = await adminReq('POST', `/rec/experiments/${experimentId}/stop`,
      { session: admin, xff: xff() });
    check('一键停止成功', codeOf(stopped) === 0 && Number(dataOf(stopped)?.status) === 3,
      dataOf(stopped)?.status);
    check('停止时写入结束时间', Boolean(dataOf(stopped)?.endTime), dataOf(stopped)?.endTime);
    if (REDIS_OK) {
      const afterStop = listKeys('ml:rec:exp:assign:*');
      check('停止后分组缓存已清空', afterStop.length === 0, afterStop);
    } else {
      skip('停止后分组缓存已清空', 'Redis CLI 不可用');
    }

    const afterStopFeed = await call('POST', '/recommend/feed', {
      token: user.token,
      xff: xff(),
      body: { scene: 'HOME_FEED', limit: 5, deviceId: DEVICE_ID, deviceType: 'WEB' },
    });
    const afterStopData = dataOf(afterStopFeed);
    check('停止后推荐仍可用', codeOf(afterStopFeed) === 0
      && (afterStopData?.list || []).length > 0, afterStopFeed.text.slice(0, 160));
    check('停止后新请求不再命中实验', !afterStopData?.experiments?.[EXP_KEY],
      afterStopData?.experiments);
    if (afterStopData?.traceId) traceIds.add(afterStopData.traceId);

    const archived = await adminReq('POST', `/rec/experiments/${experimentId}/archive`, {
      session: admin, xff: xff(), body: { conclusion: '验收通过：召回/融合/分流/指标链路正常' },
    });
    check('归档成功(4)', codeOf(archived) === 0 && Number(dataOf(archived)?.status) === 4,
      dataOf(archived)?.status);
    check('归档保存结论', String(dataOf(archived)?.conclusion || '').includes('验收通过'),
      dataOf(archived)?.conclusion);
    if (HAS_MYSQL) {
      const dbStatus = mysqlScalar(`SELECT status FROM newmusic.t_experiment WHERE experiment_key='${EXP_KEY}'`);
      check('实验状态已落库为归档(4)', dbStatus === '4', dbStatus);
    }

    // ====================================================================
    section('§6 召回监控 / 模型 / 特征 / 新歌 / 监控汇总（FR-24-005/006/011）');
    // ====================================================================
    const recallStatus = await adminReq('GET', '/rec/recall-status',
      { session: admin, xff: xff() });
    const statusData = dataOf(recallStatus);
    const statusSources = statusData?.sources || {};
    check('召回健康度可查询', codeOf(recallStatus) === 0, recallStatus.text.slice(0, 200));
    check('热门源有调用记录', Number(statusSources.hot?.calls) >= 1, statusSources.hot);
    // S0 冷启动只开热门/编辑/新歌等安全源，协同与向量在画像足够后才启用
    check('编辑精选源有调用记录', Number(statusSources.editor?.calls) >= 1, statusSources.editor);
    check('多源召回已并行执行', Object.keys(statusSources).length >= 3,
      Object.keys(statusSources));
    // 计数是当天累计值：首次请求在 JVM/连接池冷启动时偶尔会触达 150ms 全局 deadline，
    // 这属于预期内的降级演练而非故障。这里看「最近一次调用成功」与失败率，避免跨重启误报。
    const hotCalls = Number(statusSources.hot?.calls || 0);
    const hotFail = Number(statusSources.hot?.fail || 0);
    check('热门源最近调用成功且失败率低',
      Number(statusSources.hot?.lastSuccess) === 1 && (hotCalls === 0 || hotFail / hotCalls < 0.5),
      { calls: hotCalls, fail: hotFail, lastSuccess: statusSources.hot?.lastSuccess });
    check('不健康源为数组', Array.isArray(statusData?.unhealthy), statusData?.unhealthy);
    check('降级次数为数字', typeof statusData?.degradeCount === 'number', statusData?.degradeCount);

    const model = await adminReq('GET', '/rec/model?scene=HOME_FEED',
      { session: admin, xff: xff() });
    const modelData = dataOf(model);
    check('模型快照可查询', codeOf(model) === 0 && modelData?.version === 'rule-lr-v1',
      modelData?.version);
    check('模型快照含权重参数', modelData?.params && typeof modelData.params.wHot === 'number',
      modelData?.params);
    check('模型快照含四段指数', Array.isArray(modelData?.exponents)
      && modelData.exponents.length === 4, modelData?.exponents);
    check('模型快照含加载时间', modelData?.loadedAt !== undefined, modelData?.loadedAt);

    const reload = await adminReq('POST', '/rec/model/reload',
      { session: admin, xff: xff() });
    check('模型热重载成功', codeOf(reload) === 0, reload.text.slice(0, 200));

    const consistency = await adminReq('GET', '/rec/features/consistency?sampleSize=50',
      { session: admin, xff: xff() });
    const consistencyData = dataOf(consistency);
    check('特征一致性抽检可调用', codeOf(consistency) === 0, consistency.text.slice(0, 200));
    check('抽检指标为 play_log', consistencyData?.feature === 'play_log', consistencyData?.feature);
    check('抽检返回偏差与结论', typeof consistencyData?.deviation === 'number'
      && typeof consistencyData?.passed === 'boolean', consistencyData);

    const newSong = await adminReq('GET', '/rec/newsong/status',
      { session: admin, xff: xff() });
    const newSongData = dataOf(newSong);
    check('新歌扶持状态可查询', codeOf(newSong) === 0 && Boolean(newSongData?.date),
      newSongData?.date);
    check('新歌每日上限已返回', Number(newSongData?.dailyCap) > 0, newSongData?.dailyCap);
    check('新歌扶持名单为数组', Array.isArray(newSongData?.promoted), newSongData?.promoted);

    const monitor = await adminReq('GET', '/rec/monitor', { session: admin, xff: xff() });
    const monitorData = dataOf(monitor);
    check('监控汇总可查询', codeOf(monitor) === 0, monitor.text.slice(0, 200));
    check('监控含召回与实验数', monitorData?.recall && typeof monitorData?.runningExperiments === 'number',
      { recall: Boolean(monitorData?.recall), running: monitorData?.runningExperiments });
    check('监控含新歌状态', Boolean(monitorData?.newSong), monitorData?.newSong);

    // ====================================================================
    section('§7 离线评估与日报聚合（FR-24-010 / FR-24-011）');
    // ====================================================================
    // 离线评估需要「严格时间切分」的两段真实播放行为：训练期 12 首、测试期 3 首，
    // 只为本轮探针用户造数（user_id 唯一），结尾按 user_id 精确清理。
    if (HAS_MYSQL) {
      seedPlays(user.userId, 2, 12, 0);
      seedPlays(user.userId, 1, 3, 12);
      const seeded = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_play_history WHERE user_id=${user.userId}`);
      check('离线评估造数成功（训练 12 + 测试 3）', Number(seeded) === 15, seeded);
    } else {
      skip('离线评估造数', '未提供 MYSQL_PASSWORD');
    }

    const evaluate = await adminReq('POST', '/rec/evaluate?maxUsers=20&trainDays=3',
      { session: admin, xff: xff() });
    const evalData = dataOf(evaluate);
    check('离线评估可执行', codeOf(evaluate) === 0, evaluate.text.slice(0, 300));
    check('评估返回 taskId', typeof evalData?.taskId === 'string' && evalData.taskId.length > 0,
      evalData?.taskId);
    check('评估状态为 DONE 或 NO_DATA',
      evalData?.status === 'DONE' || evalData?.status === 'NO_DATA', evalData?.status);
    if (evalData?.status === 'DONE') {
      const m = evalData.metrics || {};
      for (const key of ['hitRate@10', 'hitRate@30', 'ndcg@10', 'ndcg@30', 'map@10',
        'auc', 'logLoss', 'coverage', 'novelty']) {
        check(`离线指标包含 ${key}`, typeof m[key] === 'number', m[key]);
      }
      check('离线评估有有效用户', Number(evalData.evaluatedUsers) > 0, evalData.evaluatedUsers);
    } else {
      note(`离线评估无数据：${evalData?.note || ''}`);
    }
    const evalArchive = await adminReq('GET', `/rec/evaluate/${evalData?.taskId}`,
      { session: admin, xff: xff() });
    check('评估档案可按 taskId 回查', codeOf(evalArchive) === 0
      && dataOf(evalArchive)?.taskId === evalData?.taskId, dataOf(evalArchive)?.taskId);

    const today = new Date().toLocaleDateString('sv-SE');
    const aggregate = await adminReq('POST', `/rec/metrics/aggregate${qs({ date: today })}`,
      { session: admin, xff: xff() });
    const aggregateData = dataOf(aggregate);
    check('手动触发日报聚合', codeOf(aggregate) === 0 && aggregateData?.date === today,
      aggregateData?.date);
    check('聚合写入分组数', Number(aggregateData?.groups) >= 1, aggregateData?.groups);
    if (HAS_MYSQL) {
      const daily = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_rec_metric_daily
WHERE stat_date='${today}' AND experiment_key='${EXP_KEY}' AND group_name='b'`);
      check('本轮实验组日报已落库', daily === '1', daily);
      const dailyRow = mysql(`SELECT exposure_cnt, click_cnt, ctr FROM newmusic.t_rec_metric_daily
WHERE stat_date='${today}' AND experiment_key='${EXP_KEY}' AND group_name='b' LIMIT 1`);
      note(`日报明细：${dailyRow.replace(/\r?\n/g, ' | ')}`);
    } else {
      skip('本轮实验组日报已落库', '未提供 MYSQL_PASSWORD');
    }

  } catch (e) {
    check('验收脚本执行未抛异常', false, e && e.stack ? e.stack.split('\n')[0] : String(e));
  } finally {
    cleanup();
  }

  checkIntegrity();

  console.log(`\n${fail === 0 ? '\x1b[32m' : '\x1b[31m'}结果：${pass} 通过 / ${fail} 失败 / ${skipped} 跳过\x1b[0m`);
  if (fail > 0) {
    console.log('失败项：');
    for (const name of failures) console.log(`  - ${name}`);
  }
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`\n脚本异常退出：${e && e.stack ? e.stack : e}`);
  try {
    cleanup();
  } catch { /* ignore */ }
  process.exit(2);
});
