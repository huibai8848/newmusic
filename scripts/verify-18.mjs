#!/usr/bin/env node
/**
 * 模块 18 验收脚本 —— 内容安全与合规（P0 + P1）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-18.mjs
 *
 * 前置：
 *   * 应用已启动（8081），已执行 sql/18-content-safety-seed.sql；
 *   * 后台验证码回显开启（NEWMUSIC_ADMIN_CAPTCHA_ECHO=true）；
 *   * MySQL 3306 / Redis 6379 可连，Node 22+（全局 fetch），零第三方依赖。
 *
 * 覆盖：
 *   §1 账号与前置数据
 *   §2 敏感词库：测试/CRUD/导入/导出/热加载/版本回滚 + L3 权限边界
 *   §3 机审：L1/L2/L3、规则、风险阈值、审核记录与统计
 *   §4 人工工作台：队列、领取/释放、处置联动、批量二次确认
 *   §5 用户处置与信用分：L1/L2、主动解除、信用分与申诉返还
 *   §6 申诉：内容申诉恢复、账号申诉解限、原审核员回避、7 天/每日上限
 *   §7 举报：合并计数、≥3 提优先级、≥50 自动隐藏、监管转办与 SLA
 *   §8 版权：即将到期、到期自动下架、侵权投诉与联系方式加密
 *   §9 未成年人：默认保护、青少年模式、时长心跳、监护人、不可关闭
 *   §10 AI 生成内容标识：标记、读取、不可移除
 *   §11 角色权限与共享实例安全
 *
 * 共享实例安全：
 *   * MySQL 3306 上还有 emp_db / music_db，所有 SQL 都带 newmusic. 限定符；
 *   * Redis 6379 的 db0 有其它项目的 ranking:* 键，只读写 ml:*；
 *   * 口令只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给客户端，不进 argv、不落盘；
 *   * 开头/结尾比对 ranking:* 与 emp_db/music_db 表数，证明未破坏共享实例。
 *
 * 可重复执行：账号、词条、歌单名、举报人都带本轮 RUN_TAG；
 * 只回滚本轮改过的演示歌曲状态与版权日期，不删除任何历史数据。
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
const ADMIN_USER = process.env.M09_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.M09_ADMIN_PASSWORD || 'admin123';
const OPERATOR_USER = process.env.M09_OPERATOR_USER || 'operator';
const OPERATOR_PASS = process.env.M09_OPERATOR_PASSWORD || 'operator123';
const USER_PASSWORD = 'Probe@123456';

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
// 会进入机审/规则扫描的文本只用字母后缀：避免随机数字串被 QQ/手机号规则误命中。
const WORD_TAG = Array.from({ length: 8 }, () =>
  String.fromCharCode(97 + Math.floor(Math.random() * 26))).join('');
const TODAY = dayOffset(0);

const now = () => Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.${Math.floor(Math.random() * 180) + 40}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

function dayOffset(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

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
const listOf = (res) => {
  const data = dataOf(res);
  if (Array.isArray(data)) return data;
  if (data && Array.isArray(data.list)) return data.list;
  if (data && Array.isArray(data.records)) return data.records;
  return [];
};

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

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，Redis 侧断言会跳过：${e.message}`);
}
if (!HAS_MYSQL) {
  note('未设置 MYSQL_PASSWORD：依赖库内查证的断言会逐条标记为「跳过」，不会静默略过。');
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*').sort() : [];
const sharedTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';
const probeRedisKeys = [];

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

async function adminLogin(username, password, { totpSecret, label } = {}) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const xff = syntheticIp();
    const session = new Session(label || `v18-admin-${username}`);
    const cap = await adminReq('GET', '/auth/captcha', { session, xff });
    const captcha = dataOf(cap);
    if (!captcha || !captcha.captchaId || !captcha.code) {
      throw new Error(`验证码回显不可用：请确认 NEWMUSIC_ADMIN_CAPTCHA_ECHO=true（${cap.text}）`);
    }
    const body = {
      username, password,
      captchaId: captcha.captchaId,
      captchaCode: captcha.code,
    };
    if (totpSecret) body.totpCode = totp(totpSecret);
    const res = await adminReq('POST', '/auth/login', { session, xff, body });
    if (okOf(res)) return session;
    if (codeOf(res) !== 80016) throw new Error(`后台登录 ${username} 失败：${res.text}`);
    await sleep(1200);
  }
  throw new Error(`后台登录 ${username} 失败（TOTP 重试后仍被拒绝）`);
}

// ---------------------------------------------------------------- C 端账号

async function registerAndLogin(prefix) {
  const account = `v18${prefix}${RUN_TAG}`.slice(0, 30);
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

function ageUser(userId, days = 30) {
  if (!HAS_MYSQL) return false;
  mysql(`UPDATE newmusic.t_user SET create_time = DATE_SUB(NOW(3), INTERVAL ${days} DAY) `
    + `WHERE id = ${q(userId)};`);
  return true;
}

// ---------------------------------------------------------------- 主流程

async function main() {
  console.log(`\n\x1b[1m模块 18 内容安全验收\x1b[0m  RUN_TAG=${RUN_TAG}`);
  console.log(`API=${API_BASE}  MySQL=${HAS_MYSQL ? 'on' : 'off'}  Redis=${REDIS_OK ? 'on' : 'off'}`);

  // ================================================================ §1 账号与前置
  section('§1 账号与前置数据');

  const superSession = await adminLogin(SUPER_USER, SUPER_PASS, {
    totpSecret: SUPER_TOTP_SECRET, label: 'v18-super',
  });
  const adminSession = await adminLogin(ADMIN_USER, ADMIN_PASS, { label: 'v18-admin' });
  const operatorSession = await adminLogin(OPERATOR_USER, OPERATOR_PASS, { label: 'v18-operator' });
  check('超管 / 平台管理员 / 运营均能登录后台', Boolean(superSession && adminSession && operatorSession));

  const author = await registerAndLogin('a');
  const r1 = await registerAndLogin('b');
  const r2 = await registerAndLogin('c');
  const r3 = await registerAndLogin('d');
  const r4 = await registerAndLogin('e');
  const guardian = await registerAndLogin('f');
  check('注册并登录 6 个 C 端探针账号', [author, r1, r2, r3, r4, guardian]
    .every((u) => u.token && u.userId));

  let songId = '1000000000000005001';
  let copyrightSongId = '1000000000000005010';
  let complaintSongId = '1000000000000005005';
  if (HAS_MYSQL) {
    const row = mysqlScalar("SELECT id FROM newmusic.t_song WHERE status = 1 ORDER BY id LIMIT 1");
    if (row) songId = row;
    // 优先选没有版权记录的歌曲，避免覆盖演示版权数据；不足时退回任意在线歌曲。
    const free = mysql("SELECT s.id FROM newmusic.t_song s "
      + "LEFT JOIN newmusic.t_copyright c ON c.song_id = s.id "
      + "WHERE s.status = 1 AND c.id IS NULL ORDER BY s.id LIMIT 2")
      .split(/\r?\n/).filter(Boolean);
    if (free.length >= 2) {
      copyrightSongId = free[0];
      complaintSongId = free[1];
    } else {
      const rows = mysql("SELECT id FROM newmusic.t_song WHERE status = 1 ORDER BY id LIMIT 20")
        .split(/\r?\n/).filter(Boolean);
      if (rows.length >= 3) {
        copyrightSongId = rows[rows.length - 2];
        complaintSongId = rows[rows.length - 1];
      }
    }
    check('演示歌曲存在（评论/版权/投诉目标）', Boolean(songId));
    note(`版权探针歌曲=${copyrightSongId}，投诉探针歌曲=${complaintSongId}`);
  } else {
    skip('演示歌曲存在（评论/版权/投诉目标）', '无 MYSQL_PASSWORD');
  }

  // 新用户评论先审后发：第一条干净评论应为待审
  const commentA = await call('POST', '/comments', {
    token: author.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `新用户评论-${WORD_TAG}` },
  });
  check('新用户干净评论落为待审（status=0）',
    okOf(commentA) && dataOf(commentA)?.status === 0, commentA.text);
  const commentAId = dataOf(commentA)?.id ? String(dataOf(commentA).id) : null;

  const aged = ageUser(author.userId);
  if (aged) note('已将 author 注册时间回拨 30 天，用于验证「老用户直接发布」');
  else skip('回拨 author 注册时间', '无 MYSQL_PASSWORD');

  const commentB = await call('POST', '/comments', {
    token: author.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `老用户正常评论-${WORD_TAG}` },
  });
  check('老用户干净评论直接发布（status=1）',
    okOf(commentB) && dataOf(commentB)?.status === 1, commentB.text);
  const commentBId = dataOf(commentB)?.id ? String(dataOf(commentB).id) : null;

  // 用 L1 制造「落库待审」：L2 风险分 75 已达到机审的拒绝阈值，会被直接拒绝而不进队列。
  const commentC = await call('POST', '/comments', {
    token: author.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `全网最低价 ${WORD_TAG}` },
  });
  check('命中 L1 的评论发布但进入人工审核（status=0）',
    okOf(commentC) && dataOf(commentC)?.status === 0, commentC.text);
  const commentCId = dataOf(commentC)?.id ? String(dataOf(commentC).id) : null;

  if (HAS_MYSQL && commentBId) {
    const st = mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id = ${q(commentBId)}`);
    check('库内确认评论 B 已发布', st === '1', `status=${st}`);
  } else {
    skip('库内确认评论 B 已发布', '无 MYSQL_PASSWORD');
  }
  if (HAS_MYSQL && commentCId) {
    const st = mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id = ${q(commentCId)}`);
    check('库内确认评论 C 待审', st === '0', `status=${st}`);
  } else {
    skip('库内确认评论 C 待审', '无 MYSQL_PASSWORD');
  }
  void commentAId;

  // ================================================================ §2 词库
  section('§2 敏感词库与热加载');

  const wordStats = await adminReq('GET', '/sensitive-words/stats', { session: superSession });
  const statsData = dataOf(wordStats) || {};
  check('词库已从 DB 加载（非内置兜底）', statsData.fallback === false, wordStats.text);
  check('加载词条数 ≥ 11', Number(statsData.loadedWords) >= 11, statsData.loadedWords);
  check('词库版本号 ≥ 1', Number(statsData.wordVersion) >= 1, statsData.wordVersion);

  const wordPage = await adminReq('GET', '/sensitive-words?pageSize=100', { session: superSession });
  check('超管可见完整词条（含 L3 原文）',
    listOf(wordPage).some((w) => String(w.word) === '敏感词' && w.level === 3), wordPage.text);
  check('词库响应禁止缓存（Cache-Control: no-store）',
    (wordPage.headers.get('cache-control') || '').includes('no-store'));

  const testL3 = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '来玩赌博网站', scene: 'COMMENT' },
  });
  check('L3 命中判定 REJECT', dataOf(testL3)?.decision === 'REJECT', testL3.text);
  check('L3 测试返回命中词与位置',
    (dataOf(testL3)?.matches || []).some((m) => m.word === '赌博网站' && m.level === 3),
    dataOf(testL3)?.matches);

  const testL2 = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '想加微信聊', scene: 'COMMENT' },
  });
  check('L2 命中判定 REVIEW', dataOf(testL2)?.decision === 'REVIEW', testL2.text);
  const testL1 = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '全网最低价', scene: 'COMMENT' },
  });
  check('L1 命中判定 REVIEW', dataOf(testL1)?.decision === 'REVIEW', testL1.text);
  const testBypass = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '加 微 信', scene: 'COMMENT' },
  });
  check('空格绕过被归一化后命中', dataOf(testBypass)?.decision === 'REVIEW', testBypass.text);
  const testWhite = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '加微信支付官方', scene: 'COMMENT' },
  });
  check('白名单整词豁免', (dataOf(testWhite)?.matches || []).length === 0, testWhite.text);
  const testPhone = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '联系 13812345678', scene: 'COMMENT' },
  });
  check('正则规则命中手机号', dataOf(testPhone)?.decision === 'REVIEW'
    && (dataOf(testPhone)?.rules || []).some((r) => r.ruleName === '手机号'), testPhone.text);
  const testClean = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: '今天天气不错，适合散步', scene: 'COMMENT' },
  });
  check('干净文本 PASS', dataOf(testClean)?.decision === 'PASS', testClean.text);

  const wordNew = `验证词${WORD_TAG}`;
  const createWord = await adminReq('POST', '/sensitive-words', {
    session: superSession,
    body: { word: wordNew, level: 1, category: 'CUSTOM', remark: 'verify-18' },
  });
  check('新增 L1 词条成功', okOf(createWord), createWord.text);
  const createdVersion = Number(dataOf(createWord)?.version || 0);
  check('新增词条产生新版本号', createdVersion > 1, createdVersion);

  const dupWord = await adminReq('POST', '/sensitive-words', {
    session: superSession, body: { word: wordNew, level: 1 },
  });
  check('重复词条返回 72001', codeOf(dupWord) === 72001, dupWord.text);

  const adminCreateL3 = await adminReq('POST', '/sensitive-words', {
    session: adminSession, body: { word: `平台管理员L3${WORD_TAG}`, level: 3 },
  });
  check('平台管理员不能新增 L3（72005）', codeOf(adminCreateL3) === 72005, adminCreateL3.text);

  const importedWord = `导入词${WORD_TAG}`;
  const preview = await adminReq('POST', '/sensitive-words/import/preview', {
    session: superSession,
    body: { content: `${importedWord}\n${importedWord}\n# 注释\n\n`, level: 1 },
  });
  check('导入预览正确统计新增/重复（1/1）',
    dataOf(preview)?.newCount === 1 && dataOf(preview)?.duplicateCount === 1, preview.text);

  const imported = await adminReq('POST', '/sensitive-words/import', {
    session: superSession,
    body: { content: `${importedWord}\n`, level: 1, category: 'IMPORT', remark: 'verify-18' },
  });
  check('批量导入成功 1 条', dataOf(imported)?.inserted === 1, imported.text);

  const reload = await adminReq('POST', '/sensitive-words/reload', { session: superSession });
  check('一键热加载成功且词条数增加',
    dataOf(reload)?.success === true && Number(dataOf(reload)?.wordCount) >= 13, reload.text);

  const testImported = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: importedWord, scene: 'COMMENT' },
  });
  check('热加载后新词立即生效', dataOf(testImported)?.decision === 'REVIEW', testImported.text);

  const versions = await adminReq('GET', '/sensitive-words/versions', { session: superSession });
  check('版本列表存在且不回传快照内容',
    listOf(versions).length >= 2 && listOf(versions).every((v) => v.snapshotData == null),
    versions.text);

  const rollback = await adminReq('POST',
    `/sensitive-words/versions/${createdVersion}/rollback`, { session: superSession });
  check('回滚到新增词条后的版本成功',
    dataOf(rollback)?.rolledBackTo === createdVersion, rollback.text);
  const afterRollback = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: importedWord, scene: 'COMMENT' },
  });
  check('回滚后不在快照里的导入词被停用',
    dataOf(afterRollback)?.decision === 'PASS', afterRollback.text);
  const keptWord = await adminReq('POST', '/sensitive-words/test', {
    session: superSession, body: { text: wordNew, scene: 'COMMENT' },
  });
  check('回滚后快照内的词仍生效', dataOf(keptWord)?.decision === 'REVIEW', keptWord.text);

  const adminExport = await adminReq('GET', '/sensitive-words/export', { session: adminSession });
  check('平台管理员导出含 L3 的词库被拒（72005）',
    codeOf(adminExport) === 72005, adminExport.text);

  const superNoConfirm = await adminReq('GET', '/sensitive-words/export', { session: superSession });
  check('超管导出 L3 未输入 EXPORT 被拒（72006）',
    codeOf(superNoConfirm) === 72006, superNoConfirm.text);

  const exportCsv = await adminReq('GET', '/sensitive-words/export?confirm=EXPORT',
    { session: superSession, bytes: true });
  check('超管二次确认后导出 CSV 成功', exportCsv.status === 200 && Boolean(exportCsv.raw),
    `${exportCsv.status}/${exportCsv.text.slice(0, 80)}`);
  check('CSV 带 UTF-8 BOM',
    exportCsv.raw && exportCsv.raw[0] === 0xef && exportCsv.raw[1] === 0xbb
    && exportCsv.raw[2] === 0xbf);
  check('CSV 表头正确', (exportCsv.text || '').includes('word,raw_word,level,category'));

  // ================================================================ §3 机审
  section('§3 文本机审与风险阈值');

  const textL3 = await adminReq('POST', '/audit/text-check', {
    session: superSession,
    body: { text: '出售赌博网站账号', contentType: 'COMMENT', userId: author.userId, scene: 'COMMENT' },
  });
  check('机审 L3 → REJECT / risk 100',
    dataOf(textL3)?.decision === 'REJECT' && Number(dataOf(textL3)?.riskLevel) === 100,
    textL3.text);
  check('机审返回命中词掩码', (dataOf(textL3)?.hitWords || []).length >= 1, dataOf(textL3)?.hitWords);

  const textL2 = await adminReq('POST', '/audit/text-check', {
    session: superSession,
    body: { text: '加微信领取福利', contentType: 'COMMENT', userId: author.userId, scene: 'COMMENT' },
  });
  // L2 风险分 75，已达到 70 分的拒绝阈值（新用户更严：60），因此机审直接拒绝。
  check('机审 L2 → 风险 75 达阈值转 REJECT',
    dataOf(textL2)?.decision === 'REJECT' && Number(dataOf(textL2)?.riskLevel) === 75,
    textL2.text);

  const textL1 = await adminReq('POST', '/audit/text-check', {
    session: superSession,
    body: { text: '全网最低价', contentType: 'COMMENT', userId: author.userId, scene: 'COMMENT' },
  });
  check('机审 L1 → REVIEW / risk 35',
    dataOf(textL1)?.decision === 'REVIEW' && Number(dataOf(textL1)?.riskLevel) === 35,
    textL1.text);

  const textRule = await adminReq('POST', '/audit/text-check', {
    session: superSession,
    body: { text: '电话 13812345678', contentType: 'COMMENT', userId: author.userId, scene: 'COMMENT' },
  });
  check('正则规则命中 → REVIEW / risk 50',
    dataOf(textRule)?.decision === 'REVIEW' && Number(dataOf(textRule)?.riskLevel) === 50,
    textRule.text);

  const textClean = await adminReq('POST', '/audit/text-check', {
    session: superSession,
    body: { text: `这是一条普通评论 ${WORD_TAG}`, contentType: 'COMMENT', userId: author.userId },
  });
  check('干净文本 → PASS / risk 0',
    dataOf(textClean)?.decision === 'PASS' && Number(dataOf(textClean)?.riskLevel) === 0,
    textClean.text);
  check('机审记录已落库（recordId 存在）', Boolean(dataOf(textClean)?.recordId), textClean.text);

  const auditStats = await adminReq('GET', '/audit/stats?days=1', { session: superSession });
  const decisions = (dataOf(auditStats)?.byDecision || []).map((r) => Number(r.decision));
  check('审核统计按决策分组（通过/拒绝/待审均出现）',
    [1, 2, 3].every((d) => decisions.includes(d)), auditStats.text);
  check('审核统计含队列与 SLA', Boolean(dataOf(auditStats)?.queue) && Boolean(dataOf(auditStats)?.sla));

  // ================================================================ §4 工作台
  section('§4 人工审核工作台');

  const queue = await adminReq('GET', '/audit/queue?type=COMMENT&status=0&pageSize=100',
    { session: superSession });
  const task = listOf(queue).find((t) => String(t.targetId) === String(commentCId));
  check('L1 评论生成待审任务', Boolean(task), queue.text);
  if (!task) throw new Error('未找到评论审核任务，后续工作台用例无法继续');
  check('任务带场景与 SLA 截止', task.scene === 'COMMENT' && Boolean(task.slaDeadline), task);

  const claim = await adminReq('POST', `/audit/${task.id}/claim`, { session: superSession });
  check('领取任务成功（status=1）', dataOf(claim)?.status === 1, claim.text);

  const secondClaim = await adminReq('POST', `/audit/${task.id}/claim`, { session: adminSession });
  check('他人已领取时不能重复领取', codeOf(secondClaim) !== 0, secondClaim.text);

  const release = await adminReq('POST', `/audit/${task.id}/release`, { session: superSession });
  check('释放任务成功', okOf(release), release.text);
  const claimAgain = await adminReq('POST', `/audit/${task.id}/claim`, { session: superSession });
  check('释放后可再次领取', dataOf(claimAgain)?.status === 1, claimAgain.text);

  const badBatch = await adminReq('POST', '/audit/batch-decision', {
    session: superSession,
    body: { taskIds: [task.id], decision: 'REJECT', rejectReason: '违规引流' },
  });
  check('批量处置缺少 confirmed 被拒', codeOf(badBatch) !== 0 && msgOf(badBatch).includes('二次确认'),
    badBatch.text);

  const reject = await adminReq('POST', `/audit/${task.id}/decision`, {
    session: superSession,
    body: { decision: 'REJECT', rejectReason: '违规引流', contentAction: 'HIDE', notify: false },
  });
  check('人工拒绝并把评论下线（task status=3）', dataOf(reject)?.status === 3, reject.text);

  if (HAS_MYSQL) {
    const st = mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id = ${q(commentCId)}`);
    check('库内确认评论 C 已隐藏（status=3）', st === '3', `status=${st}`);
  } else {
    skip('库内确认评论 C 已隐藏', '无 MYSQL_PASSWORD');
  }

  const trail = await adminReq('GET', `/audit/${task.id}/records`, { session: superSession });
  const actions = listOf(trail).map((r) => r.action);
  check('操作轨迹包含领取/释放/拒绝',
    ['CLAIM', 'RELEASE', 'REJECT'].every((a) => actions.includes(a)), actions);

  const decideAgain = await adminReq('POST', `/audit/${task.id}/decision`, {
    session: superSession,
    body: { decision: 'APPROVE' },
  });
  check('已结束任务不能二次处置', codeOf(decideAgain) !== 0, decideAgain.text);

  let manualRecordId = null;
  if (HAS_MYSQL) {
    manualRecordId = mysqlScalar(
      `SELECT id FROM newmusic.t_content_audit_record WHERE content_type='COMMENT' `
      + `AND content_id=${q(commentCId)} AND audit_type=2 ORDER BY id DESC LIMIT 1`);
    check('人工审核结论写入内容审核记录', Boolean(manualRecordId), manualRecordId);
  } else {
    skip('人工审核结论写入内容审核记录', '无 MYSQL_PASSWORD');
  }

  const queueStats = await adminReq('GET', '/audit/queue-stats', { session: superSession });
  check('队列统计可读', dataOf(queueStats) && 'pending' in dataOf(queueStats), queueStats.text);

  // ================================================================ §5 处置/信用分
  section('§5 用户处置与信用分');

  const warning = await adminReq('POST', `/users/${r3.userId}/restrict`, {
    session: superSession,
    body: { level: 1, reason: `首次警告-${RUN_TAG}`, reasonCategory: 'OTHER' },
  });
  check('L1 警告不产生生效限制（status=0）', dataOf(warning)?.status === 0, warning.text);

  let credit = await adminReq('GET', `/users/${r3.userId}/credit`, { session: superSession });
  check('L1 扣 5 分（100→95）', Number(dataOf(credit)?.score) === 95, credit.text);

  const restrictL2 = await adminReq('POST', `/users/${r3.userId}/restrict`, {
    session: superSession,
    body: { level: 2, type: 1, days: 1, reason: `限制发言-${RUN_TAG}` },
  });
  check('L2 限制发言生效（status=1）', dataOf(restrictL2)?.status === 1, restrictL2.text);
  const restrictionId = String(dataOf(restrictL2)?.id || '');
  check('限制记录返回 ID', Boolean(restrictionId), restrictL2.text);

  credit = await adminReq('GET', `/users/${r3.userId}/credit`, { session: superSession });
  check('L2 再扣 10 分（95→85）', Number(dataOf(credit)?.score) === 85, credit.text);

  const blockedComment = await call('POST', '/comments', {
    token: r3.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `被限制仍尝试评论-${RUN_TAG}` },
  });
  check('被限制发言的用户评论返回 20005', codeOf(blockedComment) === 20005, blockedComment.text);

  const active = await adminReq('GET', `/users/${r3.userId}/restrictions`,
    { session: superSession });
  check('生效中的限制列表包含 L2', listOf(active).some((x) => String(x.id) === restrictionId),
    active.text);

  const logs = await adminReq('GET', `/users/${r3.userId}/credit/logs`,
    { session: superSession });
  check('信用分流水记录了两次扣分', listOf(logs).length >= 2, listOf(logs).length);

  // ================================================================ §6 申诉
  section('§6 申诉与恢复');

  const accountAppeal = await call('POST', '/appeals', {
    token: r3.token, xff: syntheticIp(),
    body: {
      targetType: 'ACCOUNT', targetId: restrictionId,
      reason: `限制有误，请复核-${WORD_TAG}`, evidenceImages: ['https://example.com/probe.png'],
    },
  });
  check('账号处置申诉提交成功', okOf(accountAppeal) && dataOf(accountAppeal)?.status === 0,
    accountAppeal.text);
  const accountAppealId = String(dataOf(accountAppeal)?.id || '');
  check('申诉生成 24h SLA', Boolean(dataOf(accountAppeal)?.slaDeadline), accountAppeal.text);

  const otherView = await call('GET', `/appeals/${accountAppealId}`, { token: r1.token });
  check('他人查看申诉按不存在处理（72014）', codeOf(otherView) === 72014, otherView.text);

  const duplicateAppeal = await call('POST', '/appeals', {
    token: r3.token, xff: syntheticIp(),
    body: { targetType: 'ACCOUNT', targetId: restrictionId, reason: `重复申诉-${WORD_TAG}` },
  });
  check('同一处置重复申诉被拒（72011）', codeOf(duplicateAppeal) === 72011, duplicateAppeal.text);

  const selfReview = await adminReq('POST', `/appeals/${accountAppealId}/handle`, {
    session: superSession, body: { decision: 'APPROVE', result: '同意申诉' },
  });
  check('原审核员不能复核自己的处置（72013）', codeOf(selfReview) === 72013, selfReview.text);

  const accountApproved = await adminReq('POST', `/appeals/${accountAppealId}/handle`, {
    session: adminSession, body: { decision: 'APPROVE', result: '证据不足，撤销限制' },
  });
  check('换人复核后申诉通过（status=1）', dataOf(accountApproved)?.status === 1,
    accountApproved.text);

  const activeAfter = await adminReq('GET', `/users/${r3.userId}/restrictions`,
    { session: superSession });
  check('申诉通过后限制已解除', listOf(activeAfter).length === 0, activeAfter.text);

  credit = await adminReq('GET', `/users/${r3.userId}/credit`, { session: superSession });
  check('申诉通过补回 L2 的 10 分（85→95）', Number(dataOf(credit)?.score) === 95, credit.text);

  const retryComment = await call('POST', '/comments', {
    token: r3.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `解除限制后正常评论-${RUN_TAG}` },
  });
  check('解除限制后可以重新评论', okOf(retryComment), retryComment.text);

  // 内容申诉：人工拒绝记录 → 恢复评论
  if (!manualRecordId) {
    skip('内容申诉恢复评论', '未能取得人工审核记录 ID');
  } else {
    const contentAppeal = await call('POST', '/appeals', {
      token: author.token, xff: syntheticIp(),
      body: {
        targetType: 'CONTENT', targetId: manualRecordId,
        reason: `评论被误判，请恢复-${WORD_TAG}`,
      },
    });
    check('内容申诉提交成功', okOf(contentAppeal), contentAppeal.text);
    const contentAppealId = String(dataOf(contentAppeal)?.id || '');

    const contentSelfReview = await adminReq('POST', `/appeals/${contentAppealId}/handle`, {
      session: superSession, body: { decision: 'APPROVE', result: '同意恢复' },
    });
    check('内容申诉也执行原审核员回避（72013）',
      codeOf(contentSelfReview) === 72013, contentSelfReview.text);

    const contentApproved = await adminReq('POST', `/appeals/${contentAppealId}/handle`, {
      session: adminSession, body: { decision: 'APPROVE', result: '误判，恢复内容' },
    });
    check('内容申诉通过', dataOf(contentApproved)?.status === 1, contentApproved.text);

    if (HAS_MYSQL) {
      const st = mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id = ${q(commentCId)}`);
      check('申诉通过后评论恢复发布（status=1）', st === '1', `status=${st}`);
    } else {
      skip('申诉通过后评论恢复发布', '无 MYSQL_PASSWORD');
    }
  }

  // 7 天时效
  if (!HAS_MYSQL) {
    skip('超过 7 天的申诉被拒（72010）', '无 MYSQL_PASSWORD');
  } else {
    const expiredRecord = await adminReq('POST', '/audit/text-check', {
      session: superSession,
      body: {
        text: `赌博网站-${WORD_TAG}`, contentType: 'COMMENT', contentId: commentBId,
        userId: author.userId, scene: 'COMMENT',
      },
    });
    const expiredRecordId = String(dataOf(expiredRecord)?.recordId || '');
    mysql(`UPDATE newmusic.t_content_audit_record SET create_time = DATE_SUB(NOW(3), INTERVAL 8 DAY) `
      + `WHERE id = ${q(expiredRecordId)};`);
    const expiredAppeal = await call('POST', '/appeals', {
      token: author.token, xff: syntheticIp(),
      body: { targetType: 'CONTENT', targetId: expiredRecordId, reason: `超时申诉-${WORD_TAG}` },
    });
    check('超过 7 天的申诉被拒（72010）', codeOf(expiredAppeal) === 72010, expiredAppeal.text);
  }

  // 每日 3 次上限：直接设置当日计数，避免为了 4 次申诉造 4 条处置
  if (REDIS_OK) {
    const limitRestriction = await adminReq('POST', `/users/${r1.userId}/restrict`, {
      session: superSession, body: { level: 1, reason: `每日上限探针-${RUN_TAG}` },
    });
    const limitRestrictionId = String(dataOf(limitRestriction)?.id || '');
    const dayKey = `ml:audit:appeal:${r1.userId}:${TODAY}`;
    redis('set', dayKey, '3');
    probeRedisKeys.push(dayKey);
    const dailyLimit = await call('POST', '/appeals', {
      token: r1.token, xff: syntheticIp(),
      body: { targetType: 'ACCOUNT', targetId: limitRestrictionId, reason: `每日上限-${WORD_TAG}` },
    });
    check('每日第 4 次申诉被拒（72012）', codeOf(dailyLimit) === 72012, dailyLimit.text);
    check('超限时申诉未落库',
      HAS_MYSQL ? mysqlCount("SELECT COUNT(*) FROM newmusic.t_appeal "
        + `WHERE user_id=${q(r1.userId)}`) === 0 : true);
  } else {
    skip('每日申诉上限（72012）', 'Redis 不可用');
  }

  const appealStats = await adminReq('GET', '/appeals/stats?days=1', { session: superSession });
  check('申诉统计可读', Number(dataOf(appealStats)?.total) >= 2, appealStats.text);

  // ================================================================ §7 举报
  section('§7 举报治理、SLA 与自动隐藏');

  const reportBody = (reporter) => call('POST', '/reports', {
    token: reporter.token, xff: syntheticIp(),
    body: {
      targetType: 'COMMENT', targetId: commentBId, reasonType: 'TROLL',
      reason: `举报探针-${RUN_TAG}`,
    },
  });
  const report1 = await reportBody(r1);
  check('举报提交成功（主举报待处理 status=0）',
    okOf(report1) && dataOf(report1)?.status === 0, report1.text);
  const mainReportId = String(dataOf(report1)?.id || '');
  const report2 = await reportBody(r2);
  check('同目标同理由的第二条举报被合并（status=4）',
    dataOf(report2)?.status === 4, report2.text);
  const report3 = await reportBody(r3);
  check('第三条举报合并成功', dataOf(report3)?.status === 4, report3.text);

  if (HAS_MYSQL) {
    const reportNo = mysqlScalar(
      `SELECT report_no FROM newmusic.t_report WHERE id=${q(mainReportId)}`);
    check('举报单号以 R 开头', reportNo.startsWith('R'), reportNo);
    const row = mysqlScalar(`SELECT CONCAT(priority,':',report_count) FROM newmusic.t_report `
      + `WHERE id=${q(mainReportId)}`);
    check('合并计数与优先级落库（1:3）', row === '1:3', row);
    mysql(`UPDATE newmusic.t_report SET report_count = 49 WHERE id = ${q(mainReportId)};`);
  } else {
    skip('举报单号以 R 开头', '无 MYSQL_PASSWORD');
    skip('合并计数与优先级落库', '无 MYSQL_PASSWORD');
  }

  const report4 = await reportBody(r4);
  check('第四条举报仍合并', dataOf(report4)?.status === 4, report4.text);

  if (HAS_MYSQL) {
    const row = mysqlScalar(`SELECT CONCAT(auto_hidden,':',report_count) FROM newmusic.t_report `
      + `WHERE id=${q(mainReportId)}`);
    check('举报人数达到 50 → 自动隐藏（auto_hidden=1）', row === '1:50', row);
    const commentStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_comment WHERE id=${q(commentBId)}`);
    check('自动隐藏同步处置了评论（status=3）', commentStatus === '3', `status=${commentStatus}`);
  } else {
    skip('自动隐藏与评论同步处置', '无 MYSQL_PASSWORD');
  }

  const reportStats = await adminReq('GET', '/reports/stats?days=1', { session: superSession });
  check('举报统计含总量/自动隐藏/监管转办',
    Number(dataOf(reportStats)?.total) >= 4 && Number(dataOf(reportStats)?.autoHidden) >= 1,
    reportStats.text);
  check('举报统计按目标类型分组',
    Array.isArray(dataOf(reportStats)?.byTargetType), reportStats.text);

  const slaStats = await adminReq('GET', '/reports/sla-stats', { session: superSession });
  check('举报 SLA 统计可读', dataOf(slaStats) && 'overdue' in dataOf(slaStats), slaStats.text);

  const regulator = await adminReq('POST', `/reports/${mainReportId}/regulator`,
    { session: superSession });
  check('监管转办成功（is_regulator=1 / priority=1）',
    dataOf(regulator)?.isRegulator === 1 && dataOf(regulator)?.priority === 1, regulator.text);
  if (HAS_MYSQL) {
    const hours = Number(mysqlScalar(
      `SELECT TIMESTAMPDIFF(MINUTE, NOW(3), sla_deadline) FROM newmusic.t_report `
      + `WHERE id=${q(mainReportId)}`));
    check('监管转办 SLA 压缩到约 2 小时', hours > 0 && hours <= 120, `minutes=${hours}`);
  } else {
    skip('监管转办 SLA 压缩到约 2 小时', '无 MYSQL_PASSWORD');
  }

  // ================================================================ §8 版权
  section('§8 版权信息、到期下架与侵权投诉');

  const saveCopyright = await adminReq('POST', '/copyrights', {
    session: superSession,
    body: {
      songId: copyrightSongId, copyrightOwner: `版权方-${RUN_TAG}`,
      licenseType: 'NON_EXCLUSIVE', licenseRegion: '中国大陆',
      licenseStart: dayOffset(-30), licenseEnd: dayOffset(10),
      licenseScope: '在线播放', contractNo: `DEMO-${RUN_TAG}`,
    },
  });
  check('保存版权信息成功（10 天内到期 → status=1）',
    okOf(saveCopyright) && dataOf(saveCopyright)?.status === 1, saveCopyright.text);
  const copyrightId = String(dataOf(saveCopyright)?.id || '');

  const expiring = await adminReq('GET', '/copyrights/expiring?days=30', { session: superSession });
  check('即将到期列表包含本轮版权',
    listOf(expiring).some((x) => String(x.id) === copyrightId), expiring.text);

  if (!HAS_MYSQL) {
    skip('版权到期自动下架歌曲', '无 MYSQL_PASSWORD');
  } else {
    mysql(`UPDATE newmusic.t_copyright SET license_end = DATE_SUB(CURDATE(), INTERVAL 1 DAY), `
      + `status = 1 WHERE id = ${q(copyrightId)};`);
    const checked = await adminReq('POST', '/copyrights/check', { session: superSession });
    check('到期巡检返回下架数量 ≥1', Number(dataOf(checked)?.offline) >= 1, checked.text);
    const copyrightStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_copyright WHERE id=${q(copyrightId)}`);
    check('到期版权标记为已下架（status=3）', copyrightStatus === '3', `status=${copyrightStatus}`);
    const songStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_song WHERE id=${q(copyrightSongId)}`);
    check('到期歌曲自动下架（status=0）', songStatus === '0', `status=${songStatus}`);
    // 还原演示数据，保持脚本可重复执行
    mysql(`UPDATE newmusic.t_song SET status = 1 WHERE id=${q(copyrightSongId)} AND status = 0; `
      + `UPDATE newmusic.t_copyright SET license_end = DATE_ADD(CURDATE(), INTERVAL 2 YEAR), `
      + `status = 0 WHERE id=${q(copyrightId)};`);
  }

  const complaint = await call('POST', '/copyright/complaints', {
    token: author.token, xff: syntheticIp(),
    body: {
      complainantName: `投诉人-${RUN_TAG}`, complainantType: 'PERSON',
      contact: 'probe@example.com', songIds: [complaintSongId],
      description: `涉嫌侵权-${RUN_TAG}`, statement: true,
      proofFiles: ['https://example.com/proof.png'],
    },
  });
  check('侵权投诉登记成功并生成编号',
    okOf(complaint) && String(dataOf(complaint)?.complaintNo || '').startsWith('C'), complaint.text);
  check('投诉响应不回传联系方式密文', dataOf(complaint)?.contactEncrypted == null, complaint.text);
  const complaintId = String(dataOf(complaint)?.id || '');

  if (HAS_MYSQL) {
    const encrypted = mysqlScalar(
      `SELECT contact_encrypted FROM newmusic.t_copyright_complaint WHERE id=${q(complaintId)}`);
    check('联系方式加密落库且不是明文',
      Boolean(encrypted) && encrypted !== 'probe@example.com' && encrypted.length > 20,
      encrypted.slice(0, 24));
  } else {
    skip('联系方式加密落库', '无 MYSQL_PASSWORD');
  }

  const complaintDetail = await adminReq('GET', `/complaints/${complaintId}`,
    { session: superSession });
  check('后台投诉详情可读且不回传密文',
    okOf(complaintDetail) && dataOf(complaintDetail)?.contactEncrypted == null,
    complaintDetail.text);

  const complaintList = await adminReq('GET', '/complaints?status=0&pageSize=100',
    { session: superSession });
  check('投诉列表包含本轮投诉',
    listOf(complaintList).some((x) => String(x.id) === complaintId), complaintList.text);

  const takeDown = await adminReq('POST', `/complaints/${complaintId}/handle`, {
    session: superSession, body: { action: 'TAKE_DOWN', result: '证据充分，下架', notify: false },
  });
  check('投诉成立并下架（status=2）', dataOf(takeDown)?.status === 2, takeDown.text);
  if (HAS_MYSQL) {
    const songStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_song WHERE id=${q(complaintSongId)}`);
    check('投诉下架同步歌曲状态（status=0）', songStatus === '0', `status=${songStatus}`);
    mysql(`UPDATE newmusic.t_song SET status = 1 WHERE id=${q(complaintSongId)} AND status = 0;`);
  } else {
    skip('投诉下架同步歌曲状态', '无 MYSQL_PASSWORD');
  }

  const handleAgain = await adminReq('POST', `/complaints/${complaintId}/handle`, {
    session: superSession, body: { action: 'REJECT', result: '重复处理' },
  });
  check('已处理投诉不能二次处理', codeOf(handleAgain) !== 0, handleAgain.text);

  const copyrightStats = await adminReq('GET', '/copyrights/stats', { session: superSession });
  check('版权统计含投诉待处理/已下架',
    dataOf(copyrightStats) && 'complaintsTakenDown' in dataOf(copyrightStats), copyrightStats.text);

  // ================================================================ §9 未成年人
  section('§9 未成年人保护');

  const unknownStatus = await call('GET', '/users/me/minor-mode', { token: r4.token });
  check('无法识别年龄 → 默认保护营销/位置',
    dataOf(unknownStatus)?.ageUnknown === true
    && dataOf(unknownStatus)?.marketingAllowed === false
    && dataOf(unknownStatus)?.locationAllowed === false, unknownStatus.text);

  // 把禁用时段设成相同起止（3:00-3:00 = 不限制），让心跳/评论断言不受验收执行时刻影响。
  const windowConfig = await adminReq('PUT', `/users/${r4.userId}/minor-protection`, {
    session: superSession,
    body: { isMinor: false, mode: false, dailyLimitMinutes: 60, disableStartHour: 3, disableEndHour: 3 },
  });
  check('可配置青少年模式禁用时段', okOf(windowConfig), windowConfig.text);

  const enableMode = await call('PUT', '/users/me/minor-mode', {
    token: r4.token, body: { enabled: true, password: '2468' },
  });
  check('开启青少年模式成功且禁用社交',
    dataOf(enableMode)?.mode === true && dataOf(enableMode)?.socialAllowed === false,
    enableMode.text);

  const heartbeat1 = await call('POST', '/users/me/minor-mode/heartbeat', { token: r4.token });
  check('首次心跳计 1 分钟', Number(dataOf(heartbeat1)?.usedMinutes) === 1, heartbeat1.text);
  const heartbeat2 = await call('POST', '/users/me/minor-mode/heartbeat', { token: r4.token });
  check('同一分钟重复心跳不重复计时', Number(dataOf(heartbeat2)?.usedMinutes) === 1,
    heartbeat2.text);

  const minorComment = await call('POST', '/comments', {
    token: r4.token, xff: syntheticIp(),
    body: { targetType: 'SONG', targetId: songId, content: `青少年模式评论-${RUN_TAG}` },
  });
  check('青少年模式下评论被拒（72029）', codeOf(minorComment) === 72029, minorComment.text);

  const closeNoPassword = await call('PUT', '/users/me/minor-mode', {
    token: r4.token, body: { enabled: false },
  });
  check('关闭青少年模式必须输入密码（72020）', codeOf(closeNoPassword) === 72020,
    closeNoPassword.text);

  const closeWithPassword = await call('PUT', '/users/me/minor-mode', {
    token: r4.token, body: { enabled: false, password: '2468' },
  });
  check('普通账号可用密码关闭青少年模式', dataOf(closeWithPassword)?.mode === false,
    closeWithPassword.text);

  const minorConfig = await adminReq('PUT', `/users/${r4.userId}/minor-protection`, {
    session: superSession, body: { isMinor: true, dailyLimitMinutes: 60 },
  });
  check('管理端标记未成年人后强制开启青少年模式',
    dataOf(minorConfig)?.isMinor === 1 && dataOf(minorConfig)?.mode === 1, minorConfig.text);

  const minorClose = await call('PUT', '/users/me/minor-mode', {
    token: r4.token, body: { enabled: false, password: '2468' },
  });
  check('未成年人账号不可关闭青少年模式（72023）', codeOf(minorClose) === 72023, minorClose.text);

  const guardianConfig = await adminReq('PUT', `/users/${guardian.userId}/minor-protection`, {
    session: superSession, body: { isMinor: false, mode: false, dailyLimitMinutes: 60 },
  });
  check('标记监护人为成年人', dataOf(guardianConfig)?.isMinor === 0, guardianConfig.text);

  // 路径参数是「被监护人」：由监护人登录后把自己绑定为 r4 的监护人。
  const bind = await call('POST', `/users/me/wards/${r4.userId}`, { token: guardian.token });
  check('监护人绑定未成年人成功',
    String(dataOf(bind)?.guardianUserId) === String(guardian.userId), bind.text);

  const wards = await call('GET', '/users/me/wards', { token: guardian.token });
  check('监护人列表包含被监护人',
    (dataOf(wards) || []).some((w) => String(w.userId) === String(r4.userId)), wards.text);

  const guardianClose = await call('PUT', '/users/me/minor-mode', {
    token: guardian.token, body: { enabled: false, password: '2468', userId: r4.userId },
  });
  check('监护人也不能关闭未成年人的青少年模式', codeOf(guardianClose) === 72023,
    guardianClose.text);

  const strangerToggle = await call('PUT', '/users/me/minor-mode', {
    token: r1.token, body: { enabled: false, password: '2468', userId: r4.userId },
  });
  check('非监护人不能操作他人青少年模式（72030）', codeOf(strangerToggle) === 72030,
    strangerToggle.text);

  // ================================================================ §10 AI 标识
  section('§10 AI 生成内容标识');

  const playlist = await call('POST', '/music/playlists', {
    token: author.token, xff: syntheticIp(),
    body: { name: `AI歌单探针-${WORD_TAG}`, description: '用于验收 AI 标识', isPublic: 1 },
  });
  check('创建歌单成功', okOf(playlist), playlist.text);
  const playlistId = String(dataOf(playlist) || '');
  check('歌单返回 ID', Boolean(playlistId), playlist.text);

  const mark = await adminReq('POST',
    `/playlists/${playlistId}/ai-label?model=probe-model&promptVersion=v1`, { session: superSession });
  check('后台标记 AI 生成成功', dataOf(mark)?.aiGenerated === true && dataOf(mark)?.decision === 'PASS',
    mark.text);

  const labels = await call('GET',
    `/content/labels?contentType=PLAYLIST&contentId=${playlistId}`, { token: author.token });
  check('读取 AI 标识：aiGenerated=true / label=AI 生成',
    dataOf(labels)?.aiGenerated === true && dataOf(labels)?.label === 'AI 生成', labels.text);
  check('AI 标识包含模型与 Prompt 版本',
    dataOf(labels)?.aiModel === 'probe-model' && dataOf(labels)?.aiPromptVersion === 'v1',
    labels.text);

  const removeLabel = await adminReq('POST', '/content/labels/remove', { session: superSession });
  check('AI 标识任何角色都不可移除（72024）', codeOf(removeLabel) === 72024, removeLabel.text);

  if (HAS_MYSQL) {
    const ai = mysqlScalar(`SELECT ai_generated FROM newmusic.t_playlist WHERE id=${q(playlistId)}`);
    check('AI 标识持久化在歌单表', ai === '1', `ai_generated=${ai}`);
  } else {
    skip('AI 标识持久化在歌单表', '无 MYSQL_PASSWORD');
  }

  const unknownLabel = await call('GET',
    `/content/labels?contentType=PLAYLIST&contentId=${songId}`, { token: author.token });
  check('非歌单/非 AI 内容不误标', dataOf(unknownLabel)?.aiGenerated === false, unknownLabel.text);

  // ================================================================ §11 权限与共享安全
  section('§11 角色权限与共享实例安全');

  const opQueue = await adminReq('GET', '/audit/queue', { session: operatorSession });
  check('运营可查看审核队列', okOf(opQueue), opQueue.text);

  const opWords = await adminReq('GET', '/sensitive-words', { session: operatorSession });
  check('运营无词库查看权限（403/40301）',
    opWords.status === 403 && codeOf(opWords) === 40301, `${opWords.status}/${codeOf(opWords)}`);

  const opBan = await adminReq('POST', `/users/${r1.userId}/ban-permanent`, {
    session: operatorSession,
    body: { reason: '运营不应有永久封禁权限', confirm: 'BAN' },
  });
  check('运营无永久封禁权限（403/40301）',
    opBan.status === 403 && codeOf(opBan) === 40301, `${opBan.status}/${codeOf(opBan)}`);

  const adminBan = await adminReq('POST', `/users/${r1.userId}/ban-permanent`, {
    session: adminSession,
    body: { reason: '平台管理员不应有永久封禁权限', confirm: 'BAN' },
  });
  check('平台管理员无永久封禁权限（403/40301）',
    adminBan.status === 403 && codeOf(adminBan) === 40301, `${adminBan.status}/${codeOf(adminBan)}`);

  const banWrongConfirm = await adminReq('POST', `/users/${r4.userId}/ban-permanent`, {
    session: superSession,
    body: { reason: `永久封禁探针-${RUN_TAG}`, confirm: 'ban' },
  });
  check('永久封禁错误二次确认被拒（72016）',
    codeOf(banWrongConfirm) === 72016, banWrongConfirm.text);

  const permanentBan = await adminReq('POST', `/users/${r4.userId}/ban-permanent`, {
    session: superSession,
    body: {
      reason: `永久封禁探针，原因足够长-${RUN_TAG}`, confirm: 'BAN',
      reasonCategory: 'OTHER',
    },
  });
  check('超管输入 BAN 后可永久封禁',
    okOf(permanentBan) && dataOf(permanentBan)?.type === 4, permanentBan.text);
  const banId = String(dataOf(permanentBan)?.id || '');

  if (HAS_MYSQL) {
    const userStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_user WHERE id=${q(r4.userId)}`);
    check('永久封禁同步账号状态（status=2）', userStatus === '2', `status=${userStatus}`);
  } else {
    skip('永久封禁同步账号状态', '无 MYSQL_PASSWORD');
  }

  const releaseBan = await adminReq('DELETE',
    `/users/${r4.userId}/restrict/${banId}?reason=${encodeURIComponent('验收后恢复')}`,
    { session: superSession });
  check('超管可解除永久封禁', dataOf(releaseBan)?.status === 0, releaseBan.text);
  if (HAS_MYSQL) {
    const userStatus = mysqlScalar(
      `SELECT status FROM newmusic.t_user WHERE id=${q(r4.userId)}`);
    check('解除后账号恢复可用（status=1）', userStatus === '1', `status=${userStatus}`);
  } else {
    skip('解除后账号恢复可用', '无 MYSQL_PASSWORD');
  }

  // 共享实例安全快照
  const rankingAfter = REDIS_OK ? listKeys('ranking:*').sort() : [];
  check('Redis 其它项目 ranking:* 键未被改动',
    JSON.stringify(rankingBefore) === JSON.stringify(rankingAfter),
    `${rankingBefore.length} → ${rankingAfter.length}`);

  if (HAS_MYSQL) {
    const sharedAfter = mysqlScalar(
      "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('emp_db / music_db 表数量未变化', sharedAfter === sharedTablesBefore,
      `${sharedTablesBefore} → ${sharedAfter}`);
    const foreignRows = mysqlCount(
      "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='newmusic' "
      + "AND TABLE_NAME IN ('t_sensitive_word','t_content_audit_record','t_user_restriction',"
      + "'t_user_credit','t_credit_log','t_copyright','t_copyright_complaint',"
      + "'t_minor_protection','t_white_word','t_audit_rule','t_sensitive_word_version')");
    check('模块 18 数据表全部就位（11 张）', foreignRows === 11, foreignRows);
  } else {
    skip('emp_db / music_db 表数量未变化', '无 MYSQL_PASSWORD');
    skip('模块 18 数据表全部就位', '无 MYSQL_PASSWORD');
  }

  // ---------------------------------------------------------------- 清理
  if (REDIS_OK) {
    try {
      const date = TODAY;
      probeRedisKeys.push(
        `ml:social:report:day:${r1.userId}:${date}`,
        `ml:social:report:day:${r2.userId}:${date}`,
        `ml:social:report:day:${r3.userId}:${date}`,
        `ml:social:report:day:${r4.userId}:${date}`,
        `ml:minor:usage:${r4.userId}:${date}`);
      // Java 侧心跳键是本地时区 yyyyMMddHHmm；不能用 toISOString（UTC，会差 8 小时）。
      const d = new Date();
      const pad = (n) => String(n).padStart(2, '0');
      const minute = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
        + `${pad(d.getHours())}${pad(d.getMinutes())}`;
      probeRedisKeys.push(`ml:minor:tick:${r4.userId}:${minute}`);
      // 本轮探针账号的限流/信用/处置缓存都带当天日期，一并清掉。
      for (const uid of [author.userId, r1.userId, r2.userId, r3.userId, r4.userId, guardian.userId]) {
        probeRedisKeys.push(
          `ml:audit:appeal:${uid}:${date}`,
          `ml:audit:credit:${uid}`,
          `ml:audit:restriction:${uid}`);
      }
      redisDel(...[...new Set(probeRedisKeys)]);
    } catch (e) {
      note(`清理本轮 Redis 探针键失败（不影响结论）：${e.message}`);
    }
  }
}

// ---------------------------------------------------------------- 运行

main()
  .then(() => {
    section('验收结果');
    console.log(`  \x1b[32m通过 ${pass}\x1b[0m  \x1b[31m失败 ${fail}\x1b[0m  `
      + `\x1b[90m跳过 ${skipped}\x1b[0m`);
    if (fail > 0) {
      console.log('\n失败项：');
      for (const name of failures) console.log(`  - ${name}`);
    }
    if (skipNotes.length) {
      console.log('\n跳过项（逐条列出，不做静默略过）：');
      for (const noteText of skipNotes) console.log(`  - ${noteText}`);
    }
    process.exit(fail > 0 ? 1 : 0);
  })
  .catch((err) => {
    console.error(`\n\x1b[31m验收脚本异常终止：\x1b[0m ${err.stack || err.message}`);
    process.exit(2);
  });
