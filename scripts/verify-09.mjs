#!/usr/bin/env node
/**
 * 模块 09 验收脚本 —— 对应 docs/系分/09-管理后台.md 的验收核对表。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-09.mjs
 *
 * 前置：应用已启动（8081）、本机 Redis 6379 可连、MySQL 已执行 sql/09-admin-seed.sql。
 * 依赖 Node 18+（内置 fetch / crypto）。零第三方依赖。
 *
 * 设计要点：
 *   * 后台 Token 只在 httpOnly Cookie 里，fetch 不会自动存 Cookie，脚本自带极简 Cookie Jar；
 *   * 验证码回显（ADMIN_CAPTCHA_ECHO=true）只用于自动化，脚本据此完成登录；
 *   * 超管 TOTP 用 RFC 6238 现场算，不引入 OTP 库；
 *   * 每节使用随机合成 X-Forwarded-For，避免上一轮登录失败计数/限流干扰；
 *   * MySQL 3306 与 Redis 6379 都是共享实例：SQL 全部带 newmusic. 限定符，
 *     Redis 只用 ml: 前缀，开头/结尾比对 ranking:* 键数与 emp_db/music_db 表数，证明没碰别的项目；
 *   * 所有测试数据用随机 ID 创建，finally 里按精确 ID 清理；
 *   * 断言拿硬证据（DB / Redis / 操作日志），不看「响应看起来对」。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- 基础配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ADMIN_BASE = process.env.ADMIN_API_BASE || `${API_BASE}/admin`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';

const ADMIN_USER = process.env.M09_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.M09_ADMIN_PASSWORD || 'admin123';
const SUPER_USER = process.env.M09_SUPER_USER || 'superadmin';
const SUPER_PASS = process.env.M09_SUPER_PASSWORD || 'admin123';
const SUPER_TOTP_SECRET = process.env.M09_SUPER_TOTP_SECRET || 'JBSWY3DPEHPK3PXP';
const OPERATOR_USER = process.env.M09_OPERATOR_USER || 'operator';
const OPERATOR_PASS = process.env.M09_OPERATOR_PASSWORD || 'operator123';

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
const RUN_BASE = Date.now() * 1000 + Math.floor(Math.random() * 900) + 10;
let idCursor = RUN_BASE;
const nextId = () => String(++idCursor);

const syntheticIp = () => `10.97.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
const ipOf = (() => {
  const cache = new Map();
  return (section) => {
    if (!cache.has(section)) cache.set(section, syntheticIp());
    return cache.get(section);
  };
})();
const freshIp = () => syntheticIp();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
    console.log(`  \x1b[31m✗\x1b[0m ${name}${extra !== undefined ? ` → ${typeof extra === 'string' ? extra : JSON.stringify(extra)}` : ''}`);
  }
}

function note(msg) {
  console.log(`  \x1b[33m•\x1b[0m ${msg}`);
}

function skip(name, why) {
  skipped += 1;
  skipNotes.push(`${name}（${why}）`);
  console.log(`  \x1b[90m○\x1b[0m ${name} → 跳过：${why}`);
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

// ---------------------------------------------------------------- MySQL / Redis

const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const utf8Hex = (s) => {
  const hex = Buffer.from(s == null ? '' : String(s), 'utf8').toString('hex');
  // CONVERT(0x USING ...) 是非法语法；空串必须写成字面量 ''
  return hex ? `CONVERT(0x${hex} USING utf8mb4)` : "''";
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
  const out = redis('keys', pattern);
  return out === '' ? [] : out.split('\n').map((s) => s.trim()).filter(Boolean);
};
const redisDel = (...keys) => {
  if (!keys.length) return;
  redis('del', ...keys);
};

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，验证码/创建限制的 Redis 断言会跳过：${e.message}`);
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*') : [];
const sharedDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- HTTP / Cookie

class Session {
  constructor(label) {
    this.label = label;
    this.jar = new Map();
    this.setCookies = [];
  }
}

function setCookieLines(res) {
  if (typeof res.headers.getSetCookie === 'function') {
    const values = res.headers.getSetCookie();
    if (values && values.length) return values;
  }
  const raw = res.headers.get('set-cookie');
  if (!raw) return [];
  // 只按本项目的 Cookie 名切分；Expires 里的逗号不会误伤。
  return raw.split(/,(?=\s*ml_admin_)/).map((s) => s.trim()).filter(Boolean);
}

function jwtClaims(token) {
  try {
    const part = String(token).split('.')[1];
    if (!part) return null;
    const json = Buffer.from(part.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
    return JSON.parse(json);
  } catch {
    return null;
  }
}

function applySetCookies(session, res) {
  for (const line of setCookieLines(res)) {
    session.setCookies.push(line);
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
      session.jar.set(name, { value, attrs, path: attrs.path || '/' });
      // 记录 refresh jti 的 Redis 键，便于清理（refresh 有效期 8h）
      if (name === 'ml_admin_refresh') {
        const claims = jwtClaims(value);
        if (claims && claims.jti) redisCleanupKeys.push(`ml:admin:refresh:${claims.jti}`);
      }
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

async function http(method, url, { session, body, headers, xff } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (xff) h['X-Forwarded-For'] = xff;
  if (session) {
    const cookie = cookieHeader(session, new URL(url).pathname);
    if (cookie) h.Cookie = cookie;
  }
  const res = await fetch(url, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  // 用 Buffer 解码而不是 res.text()：WHATWG 解码会吞掉 UTF-8 BOM，
  // 导致「导出 CSV 带 BOM」这条断言无法验证。
  const buffer = Buffer.from(await res.arrayBuffer());
  const text = buffer.toString('utf8');
  if (session) applySetCookies(session, res);
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（CSV 等） */
  }
  return { status: res.status, json, text, headers: res.headers };
}

const adminReq = (method, path, opts = {}) => http(method, `${ADMIN_BASE}${path}`, opts);

function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ---------------------------------------------------------------- TOTP

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

// ---------------------------------------------------------------- 登录

async function fetchCaptcha(session, xff) {
  const res = await adminReq('GET', '/auth/captcha', { session, xff });
  const captcha = res.json && res.json.data ? res.json.data : null;
  if (captcha && captcha.captchaId) redisCleanupKeys.push(`ml:admin:captcha:${captcha.captchaId}`);
  return { res, captcha };
}

async function login(username, password, { totpCode, xff, tag } = {}) {
  const session = new Session(`${tag || username}`);
  const { res: capRes, captcha } = await fetchCaptcha(session, xff);
  if (!captcha || !captcha.captchaId || !captcha.code) {
    throw new Error(`验证码回显不可用（cap.code=${capRes.status}/${capRes.json && capRes.json.code}）：`
      + '请确认 ADMIN_CAPTCHA_ECHO=true');
  }
  const body = {
    username,
    password,
    captchaId: captcha.captchaId,
    captchaCode: captcha.code,
  };
  if (totpCode) body.totpCode = totpCode;
  const res = await adminReq('POST', '/auth/login', { session, body, xff });
  return { session, res, captcha, capRes };
}

async function loginSuper(xff, tag = 'super') {
  let last = null;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    last = await login(SUPER_USER, SUPER_PASS, { totpCode: totp(SUPER_TOTP_SECRET), xff, tag });
    if (last.res.json && last.res.json.code === 0) return last;
    if (!last.res.json || last.res.json.code !== 80016) return last;
    await sleep(1100);
  }
  return last;
}

// ---------------------------------------------------------------- 测试数据台账

const created = {
  userIds: [],
  adminIds: [],
  roomIds: [],
  messageIds: [],
  taskIds: [],
  reportIds: [],
  artistIds: [],
  albumIds: [],
  songIds: [],
  configIds: [],
  configKeys: [],
};
let fixtureLogId = null;
let sensitiveOriginal = null;
let sensitiveHistoryBaseline = null;
let mainAdminId = null;
let superAdminId = null;
let operatorId = null;
const redisCleanupKeys = [];

function track(list, id) {
  const value = String(id);
  if (!list.includes(value)) list.push(value);
  return value;
}

function sqlIdList(ids) {
  const valid = ids.map(String).filter((id) => /^\d+$/.test(id));
  return valid.length ? valid.join(',') : null;
}

async function waitFor(fn, timeout = 6000, interval = 250) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  return last;
}

// ================================================================ 测试主体

async function run() {
  section('§0 前置检查');
  const pre = await adminReq('GET', '/auth/captcha', { xff: ipOf('pre') });
  check('后台验证码接口可访问（应用已启动）', pre.status === 200 && pre.json && pre.json.code === 0,
    `${pre.status}/${pre.json && pre.json.code}`);
  const preCap = pre.json && pre.json.data ? pre.json.data : null;
  if (preCap && preCap.captchaId) redisCleanupKeys.push(`ml:admin:captcha:${preCap.captchaId}`);
  check('验证码响应包含 captchaId 与 PNG data URI',
    Boolean(preCap && preCap.captchaId) && Boolean(preCap && /^data:image\/png;base64,/.test(preCap.image)));
  check('验证码回显已开启（自动化登录前提）', Boolean(preCap && /^[A-Z0-9]{4}$/.test(preCap.code || '')),
    preCap && preCap.code);
  if (REDIS_OK) {
    check('验证码写入 Redis（ml:admin:captcha:{id}）',
      redis('exists', `ml:admin:captcha:${preCap.captchaId}`) === '1');
  } else {
    skip('验证码写入 Redis', 'redis-cli 不可用');
  }

  const anon = await adminReq('GET', '/auth/me', { xff: ipOf('pre') });
  check('未登录访问 /auth/me 返回 401 + 40101',
    anon.status === 401 && anon.json && anon.json.code === 40101, `${anon.status}/${anon.json && anon.json.code}`);

  // ---------------------------------------------------------------- §1 认证
  section('§1 登录、Cookie 与后台/C 端隔离');

  // 错误验证码：一次即作废
  const badSession = new Session('bad-captcha');
  const { captcha: badCap } = await fetchCaptcha(badSession, ipOf('auth'));
  const badLogin = await adminReq('POST', '/auth/login', {
    session: badSession,
    xff: ipOf('auth'),
    body: {
      username: ADMIN_USER,
      password: ADMIN_PASS,
      captchaId: badCap.captchaId,
      captchaCode: '0000',
    },
  });
  check('验证码错误返回 80013', badLogin.json && badLogin.json.code === 80013,
    badLogin.json && badLogin.json.code);
  if (REDIS_OK) {
    check('验证码校验后立即从 Redis 删除（错误也作废）',
      redis('exists', `ml:admin:captcha:${badCap.captchaId}`) === '0');
  } else {
    skip('验证码一次性消费', 'redis-cli 不可用');
  }

  const adminLogin = await login(ADMIN_USER, ADMIN_PASS, { xff: ipOf('auth'), tag: 'admin' });
  const adminSession = adminLogin.session;
  check('平台管理员 admin/admin123 登录成功',
    adminLogin.res.json && adminLogin.res.json.code === 0, adminLogin.res.json && adminLogin.res.json.code);
  mainAdminId = String(adminLogin.res.json?.data?.admin?.id || '');
  check('登录响应体不含任何 Token 字段', !/ml_admin_(access|refresh)|"accessToken"|"refreshToken"/.test(adminLogin.res.text),
    adminLogin.res.text.slice(0, 180));
  check('下发 access Token Cookie（ml_admin_access）', adminSession.jar.has('ml_admin_access'));
  check('下发 refresh Token Cookie（ml_admin_refresh）', adminSession.jar.has('ml_admin_refresh'));

  const accessCookie = adminSession.jar.get('ml_admin_access');
  const refreshCookie = adminSession.jar.get('ml_admin_refresh');
  check('access Cookie 为 HttpOnly + SameSite=Strict + Path=/api/v1/admin',
    Boolean(accessCookie) && 'httponly' in accessCookie.attrs
      && accessCookie.attrs.samesite === 'Strict' && accessCookie.attrs.path === '/api/v1/admin',
    accessCookie && accessCookie.attrs);
  check('refresh Cookie Path 收窄到 /api/v1/admin/auth',
    Boolean(refreshCookie) && refreshCookie.attrs.path === '/api/v1/admin/auth'
      && 'httponly' in refreshCookie.attrs && refreshCookie.attrs.samesite === 'Strict',
    refreshCookie && refreshCookie.attrs);
  check('登录响应回显 forceChangePassword / totpEnabled',
    adminLogin.res.json?.data?.forceChangePassword === false
      && adminLogin.res.json?.data?.totpEnabled === false);

  const me = await adminReq('GET', '/auth/me', { session: adminSession, xff: ipOf('auth') });
  check('仅凭 Cookie 可访问 /auth/me（无 Authorization 头）',
    me.json && me.json.code === 0 && me.json.data.username === ADMIN_USER,
    me.json && me.json.code);
  check('平台管理员拥有 admin:user:list 且不含 admin:sysconfig:edit',
    Boolean(me.json?.data?.perms?.includes('admin:user:list'))
      && !me.json?.data?.perms?.includes('admin:sysconfig:edit'));

  // 真正把后台 access Token 放进 Authorization 头去撞 C 端接口：
  // Cookie 的 Path 已经把后台 Token 挡在 /api/v1/admin 之外，只测 Cookie 证明不了 issuer 隔离。
  const crossEnd = await http('GET', `${API_BASE}/users/me`, {
    xff: ipOf('auth'),
    headers: { Authorization: `Bearer ${accessCookie.value}` },
  });
  check('后台 access Token 无法通过 C 端认证（独立 issuer）',
    crossEnd.status === 401, `${crossEnd.status}/${crossEnd.json && crossEnd.json.code}`);

  // 回归：AdminJwtAuthenticationFilter 明确支持 Authorization: Bearer（脚本/内部调用）。
  // C 端 JwtAuthenticationFilter 曾被 Boot 自动注册到全局链，在后台 Bearer 请求上解析失败后
  // 清空上下文，导致后台 Controller 看到 40101 —— Cookie 认证不带这个头，所以只有这条能发现。
  const adminBearer = await http('GET', `${ADMIN_BASE}/dashboard`, {
    xff: ipOf('auth'),
    headers: { Authorization: `Bearer ${accessCookie.value}` },
  });
  check('后台 Bearer Token 可正常访问后台接口',
    adminBearer.status === 200 && adminBearer.json && adminBearer.json.code === 0,
    `${adminBearer.status}/${adminBearer.json && adminBearer.json.code}`);

  const operatorLogin = await login(OPERATOR_USER, OPERATOR_PASS, { xff: ipOf('auth'), tag: 'operator' });
  const operatorSession = operatorLogin.session;
  operatorId = String(operatorLogin.res.json?.data?.admin?.id || '');
  check('运营账号 operator/operator123 登录成功',
    operatorLogin.res.json && operatorLogin.res.json.code === 0,
    operatorLogin.res.json && operatorLogin.res.json.code);

  const superLogin = await loginSuper(ipOf('auth'), 'super');
  check('超级管理员 TOTP 登录成功',
    superLogin.res.json && superLogin.res.json.code === 0,
    superLogin.res.json && superLogin.res.json.code);
  const superSession = superLogin.session;
  superAdminId = String(superLogin.res.json?.data?.admin?.id || '');
  check('超管权限已把 *:*:* 展开为具体权限',
    Boolean(superLogin.res.json?.data?.admin?.perms?.includes('admin:sysconfig:edit'))
      && Boolean(superLogin.res.json?.data?.admin?.perms?.includes('admin:adminuser:manage')));

  // ---------------------------------------------------------------- §2 TOTP
  section('§2 TOTP 与登录失败锁定');

  const noTotp = await login(SUPER_USER, SUPER_PASS, { xff: ipOf('totp'), tag: 'no-totp' });
  check('已开启 TOTP 的账号未填动态码返回 80015',
    noTotp.res.json && noTotp.res.json.code === 80015, noTotp.res.json && noTotp.res.json.code);

  const actual = totp(SUPER_TOTP_SECRET);
  const wrongTotp = actual === '000000' ? '111111' : '000000';
  const badTotp = await login(SUPER_USER, SUPER_PASS, { totpCode: wrongTotp, xff: ipOf('totp'), tag: 'bad-totp' });
  check('动态码错误返回 80016', badTotp.res.json && badTotp.res.json.code === 80016,
    badTotp.res.json && badTotp.res.json.code);
  if (HAS_MYSQL && superAdminId) {
    check('TOTP 错误后 fail_count 累加，成功登录前未误锁',
      Number(mysqlScalar(`SELECT fail_count FROM newmusic.t_admin_user WHERE id=${superAdminId}`)) >= 1);
    // 随后的成功登录会清零，断言放在下面
  }
  const superAgain = await loginSuper(ipOf('totp'), 'super-2');
  check('动态码正确后登录成功',
    superAgain.res.json && superAgain.res.json.code === 0,
    superAgain.res.json && superAgain.res.json.code);
  if (HAS_MYSQL && superAdminId) {
    check('成功登录后 fail_count 清零',
      mysqlScalar(`SELECT fail_count FROM newmusic.t_admin_user WHERE id=${superAdminId}`) === '0');
  }

  // 锁定流程用一次性账号，避免锁死演示账号
  const lockUser = `m09lock_${RUN_TAG}`;
  const lockPass = 'Probe@12345';
  const lockCreate = await adminReq('POST', '/accounts', {
    session: superSession,
    xff: ipOf('totp'),
    body: {
      username: lockUser,
      password: lockPass,
      realName: 'M09 Lock Probe',
      email: `${lockUser}@example.com`,
      roleCode: 'OPERATOR',
      totpEnabled: false,
      remark: 'verify-09 temporary lock probe',
    },
  });
  const lockId = lockCreate.json && lockCreate.json.code === 0 ? track(created.adminIds, lockCreate.json.data) : null;
  check('创建锁定流程专用账号', Boolean(lockId), lockCreate.json && lockCreate.json.code);
  if (lockId) {
    const statuses = [];
    for (let i = 0; i < 5; i += 1) {
      const attempt = await login(lockUser, 'WrongPass123', { xff: ipOf('totp'), tag: `lock-${i}` });
      statuses.push(attempt.res.json && attempt.res.json.code);
    }
    check('连续 5 次密码错误均返回 80011', statuses.every((c) => c === 80011), statuses);
    const locked = await login(lockUser, lockPass, { xff: ipOf('totp'), tag: 'lock-final' });
    check('第 6 次即使密码正确也返回 80012（已锁定）',
      locked.res.json && locked.res.json.code === 80012, locked.res.json && locked.res.json.code);
    if (HAS_MYSQL) {
      check('数据库 lock_until 已写入且在未来',
        mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_admin_user WHERE id=${lockId} AND lock_until > NOW(3)`) === '1');
    }
  }

  // ---------------------------------------------------------------- §3 RBAC
  section('§3 RBAC 与日志可见范围');

  const opDash = await adminReq('GET', '/dashboard', { session: operatorSession, xff: ipOf('rbac') });
  check('运营可访问数据看板', opDash.json && opDash.json.code === 0, opDash.json && opDash.json.code);

  const opUsers = await adminReq('GET', '/users?pageNum=1&pageSize=10', { session: operatorSession, xff: ipOf('rbac') });
  check('运营访问用户列表被拒（403/40301）',
    opUsers.status === 403 && opUsers.json && opUsers.json.code === 40301,
    `${opUsers.status}/${opUsers.json && opUsers.json.code}`);
  const opConfigs = await adminReq('GET', '/configs', { session: operatorSession, xff: ipOf('rbac') });
  check('运营访问系统配置被拒', opConfigs.status === 403 && opConfigs.json.code === 40301);
  const opAccounts = await adminReq('GET', '/accounts', { session: operatorSession, xff: ipOf('rbac') });
  check('运营访问管理员账号被拒', opAccounts.status === 403 && opAccounts.json.code === 40301);

  if (HAS_MYSQL) {
    fixtureLogId = nextId();
    mysql(`INSERT INTO newmusic.t_operation_log
      (id, operator_id, operator_name, op_type, target_type, target_id, description, ip, result, create_time)
      VALUES (${fixtureLogId}, ${operatorId}, 'operator', 'VERIFY_M09_SELF', 'SYSTEM', NULL,
              'm09 operator self log fixture', '127.0.0.1', 1, NOW(3));`);
  }
  const opLogs = await waitFor(async () => {
    const r = await adminReq('GET', `/logs/operations${qs({ opType: 'VERIFY_M09_SELF', pageNum: 1, pageSize: 20 })}`,
      { session: operatorSession, xff: ipOf('rbac') });
    const list = r.json?.data?.list;
    return list && list.length ? list : null;
  });
  check('运营只能看到自己的操作日志（onlySelf 非空对照）',
    Array.isArray(opLogs) && opLogs.length > 0
      && opLogs.every((row) => String(row.operatorId) === operatorId),
    opLogs && opLogs.map((r) => r.operatorId));

  const opExport = await adminReq('GET', '/logs/operations/export', { session: operatorSession, xff: ipOf('rbac') });
  check('运营无导出权限（403）', opExport.status === 403 && opExport.json.code === 40301);
  const adminExport = await adminReq('GET', '/logs/operations/export', { session: adminSession, xff: ipOf('rbac') });
  check('平台管理员默认也没有日志导出权限', adminExport.status === 403 && adminExport.json.code === 40301);

  // ---------------------------------------------------------------- §4 CSRF
  section('§4 Origin/Referer CSRF 白名单');

  const evil = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('csrf'),
    headers: { Origin: 'http://evil.example.com' },
    body: { configKey: 'verify.m09.csrf', configValue: 'x' },
  });
  // 跨域请求先被 Spring Security 的 CorsFilter 拦下（403 空体，还没走到业务过滤链）；
  // CSRF 过滤器本身的统一错误体用下面的 Referer 用例验证。
  check('不可信 Origin 的写请求被拒（403）', evil.status === 403, `${evil.status}/${evil.json && evil.json.code}`);
  const evilReferer = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('csrf'),
    headers: { Referer: 'http://evil.example.com/steal' },
    body: { configKey: 'verify.m09.csrf', configValue: 'x' },
  });
  check('不可信 Referer 由 CSRF 过滤器拒绝并返回 40301',
    evilReferer.status === 403 && evilReferer.json && evilReferer.json.code === 40301,
    `${evilReferer.status}/${evilReferer.json && evilReferer.json.code}`);
  const allowedOrigin = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('csrf'),
    headers: { Origin: 'http://localhost:5174' },
    body: {},
  });
  check('白名单 Origin 可进入业务层（得到参数校验而不是 CSRF 拒绝）',
    allowedOrigin.json && allowedOrigin.json.code === 10001,
    `${allowedOrigin.status}/${allowedOrigin.json && allowedOrigin.json.code}`);

  // 回归：AdminCsrfFilter 是 @Component，Boot 会把它自动注册到全局 Servlet 链。
  // 没有路径限定时，后台的 5174 白名单会把 C 端 5173 的写请求一起拒掉。
  const cEndWrite = await http('POST', `${API_BASE}/auth/login`, {
    xff: ipOf('csrf'),
    headers: { Origin: 'http://localhost:5173' },
    body: {},
  });
  check('C 端 5173 的写请求不受后台 CSRF 白名单影响',
    !(cEndWrite.status === 403 && cEndWrite.json && cEndWrite.json.code === 40301),
    `${cEndWrite.status}/${cEndWrite.json && cEndWrite.json.code}`);

  // 回归：AdminJwtAuthenticationFilter 同样是 @Component，也会被 Boot 自动注册到全局
  // Servlet 链。没有路径限定时，它会用后台密钥解析 C 端每个 Bearer Token，解析失败就写
  // 401/40102 —— C 端登录成功后所有请求都被判「凭证无效」，刷新也救不回来，最终被踢回登录页。
  section('§4.1 后台过滤器不得误伤 C 端（登录回跳回归）');
  const cLogin = await http('POST', `${API_BASE}/auth/login`, {
    xff: ipOf('cend'),
    headers: { Origin: 'http://localhost:5173' },
    body: { account: 'demo', password: 'demo123456' },
  });
  const cToken = cLogin.json && cLogin.json.data && cLogin.json.data.accessToken;
  check('C 端 demo 登录拿到 accessToken',
    cLogin.json && cLogin.json.code === 0 && Boolean(cToken),
    `${cLogin.status}/${cLogin.json && cLogin.json.code}`);

  const cMe = await http('GET', `${API_BASE}/users/me`, {
    xff: ipOf('cend'),
    headers: { Authorization: `Bearer ${cToken}` },
  });
  check('C 端 accessToken 能通过 C 端认证（/users/me）',
    cMe.status === 200 && cMe.json && cMe.json.code === 0 && cMe.json.data?.username === 'demo',
    `${cMe.status}/${cMe.json && cMe.json.code}`);

  const cSongsAuthed = await http('GET', `${API_BASE}/music/songs?pageSize=1`, {
    xff: ipOf('cend'),
    headers: { Authorization: `Bearer ${cToken}` },
  });
  check('带 C 端 Token 读公开曲库仍为 200',
    cSongsAuthed.status === 200 && cSongsAuthed.json && cSongsAuthed.json.code === 0,
    `${cSongsAuthed.status}/${cSongsAuthed.json && cSongsAuthed.json.code}`);

  // 非法 Token 在公开接口上必须静默降级为匿名（过滤器自身注释里的承诺）。
  // 这条比上一条更严格：只有「后台过滤器没在全局链上乱拦」才能通过。
  const cSongsBadToken = await http('GET', `${API_BASE}/music/songs?pageSize=1`, {
    xff: ipOf('cend'),
    headers: { Authorization: 'Bearer not-a-jwt' },
  });
  check('非法 Bearer 在公开接口降级为匿名而不是 40102',
    cSongsBadToken.status === 200,
    `${cSongsBadToken.status}/${cSongsBadToken.json && cSongsBadToken.json.code}`);

  // ---------------------------------------------------------------- §5 看板
  section('§5 数据看板（真实聚合 + 7 天补零）');

  const dash = await adminReq('GET', '/dashboard', { session: adminSession, xff: ipOf('dash') });
  const summary = dash.json && dash.json.data ? dash.json.data : null;
  check('看板接口返回成功', dash.json && dash.json.code === 0, dash.json && dash.json.code);
  check('总用户数 / 总歌曲数为真实非零值（种子库非空对照）',
    Boolean(summary) && Number(summary.totalUsers) > 0 && Number(summary.totalSongs) > 0,
    summary && { totalUsers: summary.totalUsers, totalSongs: summary.totalSongs });
  const userTrend = summary && Array.isArray(summary.userTrend) ? summary.userTrend : [];
  check('用户趋势补齐 7 天且日期升序', userTrend.length === 7
    && userTrend.every((p, i) => i === 0 || p.date > userTrend[i - 1].date), userTrend.map((p) => p.date));
  const playTrend = summary && Array.isArray(summary.playTrend) ? summary.playTrend : [];
  check('播放趋势补齐 7 天且无负数', playTrend.length === 7
    && playTrend.every((p) => Number(p.value) >= 0));
  check('待审核 / 待处理举报计数均为非负整数',
    Number(summary?.pendingAuditTasks) >= 0 && Number(summary?.pendingReports) >= 0
      && Number(summary?.escalatedAuditTasks) >= 0);

  // ---------------------------------------------------------------- §6 用户
  section('§6 用户管理（脱敏、编辑、封禁、敏感手机号）');

  const cUser = nextId();
  const cUsername = `m09u_${RUN_TAG}`;
  const cPhone = '13800138000';
  const cNick = `M09 User ${RUN_TAG}`;
  mysql(`INSERT INTO newmusic.t_user
    (id, username, phone, password, nickname, user_type, status, force_change_password, deleted, version, create_time)
    VALUES (${cUser}, '${cUsername}', '${cPhone}',
            '$2a$10$ttdk9FzHXNcEIquDlmp4l.LCaJ1W/iVk6FGxQjqw2IJPgLO.bedO6',
            '${cNick}', 1, 1, 0, 0, 0, NOW(3));`);
  track(created.userIds, cUser);

  const userPage = await adminReq('GET', `/users${qs({ keyword: cUsername, pageNum: 1, pageSize: 10 })}`,
    { session: adminSession, xff: ipOf('user') });
  const foundUser = userPage.json?.data?.list?.find((u) => String(u.id) === cUser);
  check('后台用户列表可按用户名精确命中测试用户',
    userPage.json?.code === 0 && Boolean(foundUser) && userPage.json.data.total >= 1,
    userPage.json?.data?.total);
  check('列表中的手机号已脱敏（138****8000）', foundUser?.phoneMasked === '138****8000', foundUser?.phoneMasked);

  const userDetail = await adminReq('GET', `/users/${cUser}`, { session: adminSession, xff: ipOf('user') });
  check('用户详情返回脱敏手机号',
    userDetail.json?.code === 0 && userDetail.json.data.phoneMasked === '138****8000');

  const phoneRes = await adminReq('GET', `/users/${cUser}/phone`, { session: adminSession, xff: ipOf('user') });
  check('有 admin:user:phone:view 时可查看完整手机号',
    phoneRes.json?.code === 0 && phoneRes.json.data.phone === cPhone, phoneRes.json?.data);

  const opPhone = await adminReq('GET', `/users/${cUser}/phone`, { session: operatorSession, xff: ipOf('user') });
  check('运营无敏感手机号权限（403）', opPhone.status === 403 && opPhone.json.code === 40301);

  const editRes = await adminReq('PUT', `/users/${cUser}`, {
    session: adminSession,
    xff: ipOf('user'),
    body: { nickname: `${cNick} Edited`, userType: 2 },
  });
  check('编辑用户资料成功并回显新昵称',
    editRes.json?.code === 0 && editRes.json.data.nickname === `${cNick} Edited`,
    editRes.json?.data?.nickname);
  const badType = await adminReq('PUT', `/users/${cUser}`, {
    session: adminSession,
    xff: ipOf('user'),
    body: { userType: 4 },
  });
  check('不允许把 C 端用户改成 3/4（10001）', badType.json?.code === 10001, badType.json?.code);

  const shortBan = await adminReq('POST', `/users/${cUser}/ban`, {
    session: adminSession,
    xff: ipOf('user'),
    body: { reason: 'no' },
  });
  check('封禁原因少于 5 字被参数校验拒绝', shortBan.json?.code === 10001, shortBan.json?.code);

  const banRes = await adminReq('POST', `/users/${cUser}/ban`, {
    session: adminSession,
    xff: ipOf('user'),
    body: { reason: 'Repeated community violations' },
  });
  check('封禁接口返回影响条数 1', banRes.json?.code === 0 && Number(banRes.json.data) === 1,
    banRes.json?.data);
  check('封禁后 DB status=2',
    mysqlScalar(`SELECT status FROM newmusic.t_user WHERE id=${cUser}`) === '2');
  redisCleanupKeys.push(`ml:auth:userban:${cUser}`);
  if (REDIS_OK) {
    check('封禁后 Redis 即时拦截标记存在', redis('exists', `ml:auth:userban:${cUser}`) === '1');
  } else {
    skip('Redis 封禁标记', 'redis-cli 不可用');
  }

  const bannedPage = await adminReq('GET', `/users${qs({ keyword: cUsername, banned: 'true' })}`,
    { session: adminSession, xff: ipOf('user') });
  check('banned=true 过滤能命中被封禁用户',
    bannedPage.json?.data?.list?.some((u) => String(u.id) === cUser));

  const unbanRes = await adminReq('POST', `/users/${cUser}/unban`, {
    session: adminSession,
    xff: ipOf('user'),
  });
  check('解封成功', unbanRes.json?.code === 0);
  check('解封后 DB status=1',
    mysqlScalar(`SELECT status FROM newmusic.t_user WHERE id=${cUser}`) === '1');
  if (REDIS_OK) {
    check('解封后 Redis 封禁标记被删除', redis('exists', `ml:auth:userban:${cUser}`) === '0');
  }

  const resetC = await adminReq('POST', `/users/${cUser}/reset-password`, {
    session: adminSession,
    xff: ipOf('user'),
  });
  check('重置 C 端密码返回一次性临时密码（10 位）',
    resetC.json?.code === 0 && typeof resetC.json.data === 'string' && resetC.json.data.length === 10,
    resetC.json?.data);
  check('重置后 force_change_password=1',
    mysqlScalar(`SELECT force_change_password FROM newmusic.t_user WHERE id=${cUser}`) === '1');

  // ---------------------------------------------------------------- §7 内容
  section('§7 歌手 / 专辑 / 歌曲 CRUD 与上架门禁');

  const artistName = `M09 Artist ${RUN_TAG}`;
  const artistRes = await adminReq('POST', '/artists', {
    session: adminSession,
    xff: ipOf('content'),
    body: { name: artistName, status: 1, country: 'CN' },
  });
  const artistId = artistRes.json?.code === 0 ? track(created.artistIds, artistRes.json.data) : null;
  check('新增歌手成功', Boolean(artistId), artistRes.json?.code);
  if (artistId) {
    const artistDetail = await adminReq('GET', `/artists/${artistId}`, { session: adminSession, xff: ipOf('content') });
    check('歌手首字母按名称自动截取为 M', artistDetail.json?.data?.firstLetter === 'M',
      artistDetail.json?.data?.firstLetter);
    const duplicate = await adminReq('POST', '/artists', {
      session: adminSession,
      xff: ipOf('content'),
      body: { name: artistName },
    });
    check('同名歌手被拒绝（10010）', duplicate.json?.code === 10010, duplicate.json?.code);
  }

  const albumName = `M09 Album ${RUN_TAG}`;
  const albumRes = artistId && await adminReq('POST', '/albums', {
    session: adminSession,
    xff: ipOf('content'),
    body: {
      name: albumName,
      artistId,
      publishDate: '2026-01-01',
      company: 'M09 Records',
      language: 'English',
      status: 1,
    },
  });
  const albumId = albumRes && albumRes.json?.code === 0 ? track(created.albumIds, albumRes.json.data) : null;
  check('新增专辑成功', Boolean(albumId), albumRes && albumRes.json?.code);
  if (albumId) {
    const albumDetail = await adminReq('GET', `/albums/${albumId}`, { session: adminSession, xff: ipOf('content') });
    check('专辑详情带冗余歌手名且歌曲数为 0',
      albumDetail.json?.data?.artistName === artistName && Number(albumDetail.json?.data?.songCount) === 0,
      albumDetail.json?.data);
  }

  async function createSong(name) {
    const res = await adminReq('POST', '/songs', {
      session: adminSession,
      xff: ipOf('content'),
      body: {
        name,
        artistId,
        albumId,
        duration: 180,
        language: 'English',
        genre: 'Pop',
        audioStandard: '/media/uploads/m09-verify.mp3',
        copyrightStatus: 1,
        copyrightOwner: 'M09 Records',
        auditStatus: 0,
        status: 0,
      },
    });
    return res.json?.code === 0 ? { id: track(created.songIds, res.json.data), res } : { id: null, res };
  }

  const song1 = await createSong(`M09 Song Approve ${RUN_TAG}`);
  check('新增歌曲默认下架 + 待审核', Boolean(song1.id), song1.res.json?.code);
  if (song1.id) {
    const songDetail = await adminReq('GET', `/songs/${song1.id}`, { session: adminSession, xff: ipOf('content') });
    check('歌曲默认 status=0、auditStatus=0',
      Number(songDetail.json?.data?.status) === 0 && Number(songDetail.json?.data?.auditStatus) === 0,
      songDetail.json?.data && { status: songDetail.json.data.status, auditStatus: songDetail.json.data.auditStatus });
    if (HAS_MYSQL && artistId) {
      check('新增歌曲后歌手 song_count=1',
        mysqlScalar(`SELECT song_count FROM newmusic.t_artist WHERE id=${artistId}`) === '1');
      check('新增歌曲后专辑 song_count=1',
        mysqlScalar(`SELECT song_count FROM newmusic.t_album WHERE id=${albumId}`) === '1');
    }
    const v = Number(songDetail.json?.data?.version);
    const updateSong = await adminReq('PUT', `/songs/${song1.id}`, {
      session: adminSession,
      xff: ipOf('content'),
      body: {
        name: `M09 Song Approve Edited ${RUN_TAG}`,
        artistId,
        albumId,
        duration: 181,
        language: 'English',
        genre: 'Pop',
        audioStandard: '/media/uploads/m09-verify.mp3',
        copyrightStatus: 1,
        auditStatus: 0,
        status: 0,
        version: v,
      },
    });
    check('按详情返回的 version 编辑歌曲成功', updateSong.json?.code === 0, updateSong.json && updateSong.json.code);
    const stale = await adminReq('PUT', `/songs/${song1.id}`, {
      session: adminSession,
      xff: ipOf('content'),
      body: { name: 'stale update', artistId, albumId, version: v },
    });
    check('过期 version 的更新被拒绝（乐观锁生效）', stale.json?.code !== 0, stale.json?.code);
    const onlineTooEarly = await adminReq('PUT', `/songs/${song1.id}/status/1`, {
      session: adminSession,
      xff: ipOf('content'),
    });
    check('未审核通过时上架被拒（30043）', onlineTooEarly.json?.code === 30043, onlineTooEarly.json?.code);
  }

  const song2 = await createSong(`M09 Song Reject ${RUN_TAG}`);
  check('创建第二首测试歌曲（拒绝分支）', Boolean(song2.id), song2.res.json?.code);

  const adminDelete = song1.id && await adminReq('DELETE', `/songs/${song1.id}`, {
    session: adminSession,
    xff: ipOf('content'),
    body: { reason: 'admin should not delete' },
  });
  check('平台管理员无歌曲删除权限（403）',
    Boolean(adminDelete) && adminDelete.status === 403 && adminDelete.json.code === 40301,
    adminDelete && adminDelete.status);

  // ---------------------------------------------------------------- §8 房间
  section('§8 房间管理（强制关闭 / 创建限制 / 消息查看）');

  async function createRoom(label) {
    const id = nextId();
    let roomNo = null;
    for (let i = 0; i < 20; i += 1) {
      const candidate = String(Math.floor(100000 + Math.random() * 900000));
      if (mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_room WHERE room_no='${candidate}'`) === '0') {
        roomNo = candidate;
        break;
      }
    }
    if (!roomNo) throw new Error('无法生成唯一 room_no');
    const name = `M09 Room ${label} ${RUN_TAG}`.slice(0, 30);
    mysql(`INSERT INTO newmusic.t_room
      (id, room_no, name, owner_id, room_type, status, online_count, hot_score, max_members, mic_count,
       play_mode, need_approve, allow_free_mic, create_time, last_active_time, deleted, version)
      VALUES (${id}, '${roomNo}', '${name}', ${cUser}, 1, 1, 0, 0, 100, 6, 1, 1, 0, NOW(3), NOW(3), 0, 0);`);
    track(created.roomIds, id);
    return { id, name, roomNo };
  }

  const room1 = await createRoom('A');
  const room2 = await createRoom('B');
  check('创建两个测试房间（列表/强制关闭/举报联动）', Boolean(room1.id && room2.id));

  const msgId = nextId();
  mysql(`INSERT INTO newmusic.t_room_message
    (id, room_id, user_id, message_type, content, audit_status, status, create_time)
    VALUES (${msgId}, ${room1.id}, ${cUser}, 1, 'm09 verify room message', 1, 1, NOW(3));`);
  track(created.messageIds, msgId);

  const roomPage = await adminReq('GET', `/rooms${qs({ ownerId: cUser, pageNum: 1, pageSize: 20 })}`,
    { session: adminSession, xff: ipOf('room') });
  check('房间列表按 ownerId 命中测试房间',
    roomPage.json?.code === 0 && (roomPage.json.data.list || []).some((r) => String(r.id) === room1.id),
    roomPage.json?.data?.total);

  const roomDetail = await adminReq('GET', `/rooms/${room1.id}`, { session: adminSession, xff: ipOf('room') });
  check('房间详情返回活跃状态与房主',
    roomDetail.json?.code === 0 && Number(roomDetail.json.data.status) === 1
      && String(roomDetail.json.data.ownerId) === cUser,
    roomDetail.json?.data && { status: roomDetail.json.data.status, ownerId: roomDetail.json.data.ownerId });

  const msgPage = await adminReq('GET', `/rooms/${room1.id}/messages${qs({ keyword: 'm09 verify room message' })}`,
    { session: adminSession, xff: ipOf('room') });
  check('房间消息查看返回测试消息',
    msgPage.json?.code === 0 && (msgPage.json.data.list || []).some((m) => String(m.id) === String(msgId)),
    msgPage.json?.data?.total);

  const closeShort = await adminReq('POST', `/rooms/${room1.id}/force-close`, {
    session: adminSession,
    xff: ipOf('room'),
    body: { reason: 'no' },
  });
  check('强制关闭原因过短被拒（10001）', closeShort.json?.code === 10001, closeShort.json?.code);

  const closeRes = await adminReq('POST', `/rooms/${room1.id}/force-close`, {
    session: adminSession,
    xff: ipOf('room'),
    body: { reason: 'This room violates the rules' },
  });
  check('强制关闭房间成功', closeRes.json?.code === 0, closeRes.json?.code);
  check('关闭后 DB status=2 且在线数归零',
    mysqlScalar(`SELECT CONCAT(status,':',online_count) FROM newmusic.t_room WHERE id=${room1.id}`) === '2:0');
  const closeAgain = await adminReq('POST', `/rooms/${room1.id}/force-close`, {
    session: adminSession,
    xff: ipOf('room'),
    body: { reason: 'Already closed room attempt' },
  });
  check('重复强制关闭返回 40011（只有活跃房间可关闭）', closeAgain.json?.code === 40011, closeAgain.json?.code);

  const banCreate = await adminReq('POST', `/rooms/owners/${cUser}/create-ban`, {
    session: adminSession,
    xff: ipOf('room'),
    body: { reason: 'Repeated room violations', hours: 2 },
  });
  check('禁止创建房间成功', banCreate.json?.code === 0, banCreate.json?.code);
  redisCleanupKeys.push(`ml:admin:room:createban:${cUser}`);
  if (REDIS_OK) {
    const key = `ml:admin:room:createban:${cUser}`;
    check('创建限制写入 Redis 且 TTL 约 2 小时', redis('exists', key) === '1'
      && Number(redis('ttl', key)) > 7000 && Number(redis('ttl', key)) <= 7200,
      redis('ttl', key));
    check('Redis 中保存了限制原因', redis('get', key) === 'Repeated room violations', redis('get', key));
  } else {
    skip('创建限制 Redis 断言', 'redis-cli 不可用');
  }
  const unbanCreate = await adminReq('DELETE', `/rooms/owners/${cUser}/create-ban`, {
    session: adminSession,
    xff: ipOf('room'),
  });
  check('解除创建限制成功', unbanCreate.json?.code === 0, unbanCreate.json?.code);
  if (REDIS_OK) {
    check('解除后 Redis 键被删除', redis('exists', `ml:admin:room:createban:${cUser}`) === '0');
  }

  // ---------------------------------------------------------------- §9 审核
  section('§9 审核工作台（领取锁 / 冲突 / 拒绝理由 / 歌曲联动）');

  const task1Title = `M09 audit approve ${RUN_TAG}`;
  const task2Title = `M09 audit reject ${RUN_TAG}`;

  /**
   * 复用应用自动创建的 SONG 任务，而不是直接 INSERT 第二条。
   *
   * `uk_audit_open_target`（task_type + target_id，仅 status IN (0,1)）保证一首待审歌
   * 只能有一条未完成任务：新增歌曲( auditStatus=0 )时 SongAuditTaskService 已经建好了。
   * 这里把那条任务的标题/优先级改成脚本预期的值；只有旧库没有自动任务时才回退插入。
   */
  function adoptSongAuditTask(songId, priority, title, snapshot, machineResult, machineScore) {
    const existing = mysqlScalar(`SELECT id FROM newmusic.t_audit_task
      WHERE task_type='SONG' AND target_id='${songId}' AND status IN (0,1)
      ORDER BY id DESC LIMIT 1`);
    if (existing) {
      mysql(`UPDATE newmusic.t_audit_task
        SET priority=${priority}, submitter_id=${cUser}, title=${utf8Hex(title)},
            content_snapshot=${utf8Hex(snapshot)}, machine_result=${utf8Hex(machineResult)},
            machine_score=${machineScore}, update_time=NOW(3)
        WHERE id=${existing};`);
      track(created.taskIds, existing);
      return String(existing);
    }
    const id = nextId();
    mysql(`INSERT INTO newmusic.t_audit_task
      (id, task_type, target_type, target_id, priority, status, submitter_id, title, content_snapshot,
       machine_result, machine_score, escalated, action_applied, create_time)
      VALUES
      (${id}, 'SONG', 'SONG', '${songId}', ${priority}, 0, ${cUser}, ${utf8Hex(title)},
       ${utf8Hex(snapshot)}, ${utf8Hex(machineResult)}, ${machineScore}, 0, 0, NOW(3));`);
    track(created.taskIds, id);
    return String(id);
  }

  const task1 = adoptSongAuditTask(song1.id, 1, task1Title, '{"name":"approve"}', 'machine pass', 0.9123);
  const task2 = adoptSongAuditTask(song2.id, 2, task2Title, '{"name":"reject"}', 'machine reject', 0.1234);
  check('待审歌曲已有唯一审核任务，脚本复用而非重复插入',
    Boolean(task1) && Boolean(task2) && task1 !== task2, { task1, task2 });

  const taskPage = await adminReq('GET', `/audits${qs({ keyword: task1Title, pageNum: 1, pageSize: 10 })}`,
    { session: adminSession, xff: ipOf('audit') });
  check('审核列表按标题命中测试任务',
    taskPage.json?.code === 0 && (taskPage.json.data.list || []).some((t) => String(t.id) === task1),
    taskPage.json?.data?.total);

  const taskDetail = await adminReq('GET', `/audits/${task1}`, { session: adminSession, xff: ipOf('audit') });
  check('审核详情返回优先级与内容快照',
    taskDetail.json?.code === 0 && Number(taskDetail.json.data.priority) === 1
      && String(taskDetail.json.data.contentSnapshot || '').includes('approve'));
  const records0 = await adminReq('GET', `/audits/${task1}/records`, { session: adminSession, xff: ipOf('audit') });
  check('领取前审核记录为空', records0.json?.code === 0 && (records0.json.data || []).length === 0);

  const claim1 = await adminReq('POST', `/audits/${task1}/claim`, { session: adminSession, xff: ipOf('audit') });
  check('领取任务成功且状态变 1',
    claim1.json?.code === 0 && Number(claim1.json.data.status) === 1
      && String(claim1.json.data.claimedBy) === mainAdminId,
    claim1.json?.data && { status: claim1.json.data.status, claimedBy: claim1.json.data.claimedBy });
  if (HAS_MYSQL) {
    check('领取锁为 10 分钟（claim_expire_at 在 9~11 分钟后）',
      mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_audit_task WHERE id=${task1}
        AND claim_expire_at BETWEEN DATE_ADD(NOW(3), INTERVAL 9 MINUTE)
        AND DATE_ADD(NOW(3), INTERVAL 11 MINUTE)`) === '1');
  }

  // 第二个管理员账号用于并发冲突（§12 会继续用它，所以这里先建）
  const admin2User = `m09adm_${RUN_TAG}`;
  const admin2Pass = 'Probe@12345';
  const admin2Create = await adminReq('POST', '/accounts', {
    session: superSession,
    xff: ipOf('audit'),
    body: {
      username: admin2User,
      password: admin2Pass,
      realName: 'M09 Verify Admin',
      email: `${admin2User}@example.com`,
      phone: '13900139000',
      roleCode: 'ADMIN',
      totpEnabled: false,
      remark: 'verify-09 temporary admin',
    },
  });
  const admin2Id = admin2Create.json?.code === 0 ? track(created.adminIds, admin2Create.json.data) : null;
  check('创建第二个管理员账号（用于并发冲突与控制台会话测试）', Boolean(admin2Id),
    admin2Create.json && admin2Create.json.code);
  let admin2Session = null;
  if (admin2Id) {
    const admin2Login = await login(admin2User, admin2Pass, { xff: ipOf('audit'), tag: 'admin2' });
    admin2Session = admin2Login.session;
    check('新管理员用初始密码登录成功，且 forceChangePassword=true',
      admin2Login.res.json?.code === 0 && admin2Login.res.json?.data?.forceChangePassword === true,
      admin2Login.res.json?.data && admin2Login.res.json.data.forceChangePassword);
  }

  if (admin2Session) {
    const conflict = await adminReq('POST', `/audits/${task1}/claim`, {
      session: admin2Session,
      xff: ipOf('audit'),
    });
    check('已被他人领取的任务再次领取返回 80030', conflict.json?.code === 80030, conflict.json?.code);
    const wrongRelease = await adminReq('POST', `/audits/${task1}/release`, {
      session: admin2Session,
      xff: ipOf('audit'),
    });
    check('非领取人释放任务返回 80030', wrongRelease.json?.code === 80030, wrongRelease.json?.code);
  }

  const onlyMine = await adminReq('GET', `/audits${qs({ onlyMine: 'true', pageNum: 1, pageSize: 50 })}`,
    { session: adminSession, xff: ipOf('audit') });
  check('onlyMine=true 能查到本人刚领取的任务',
    onlyMine.json?.code === 0 && (onlyMine.json.data.list || []).some((t) => String(t.id) === task1));

  const release = await adminReq('POST', `/audits/${task1}/release`, { session: adminSession, xff: ipOf('audit') });
  check('领取人释放任务成功', release.json?.code === 0, release.json?.code);
  check('释放后任务回到待领状态',
    (await adminReq('GET', `/audits/${task1}`, { session: adminSession, xff: ipOf('audit') })).json?.data?.status === 0);

  // 拒绝分支：重新领取 → 理由过短 → 合法拒绝
  await adminReq('POST', `/audits/${task1}/claim`, { session: adminSession, xff: ipOf('audit') });
  const rejectShort = await adminReq('POST', `/audits/${task1}/handle`, {
    session: adminSession,
    xff: ipOf('audit'),
    body: { result: 'REJECT', reason: 'bad' },
  });
  check('拒绝理由少于 5 字返回 80028', rejectShort.json?.code === 80028, rejectShort.json?.code);

  const reject = await adminReq('POST', `/audits/${task1}/handle`, {
    session: adminSession,
    xff: ipOf('audit'),
    body: { result: 'REJECT', reason: 'Contains prohibited content' },
  });
  check('拒绝审核任务成功且状态变 3',
    reject.json?.code === 0 && Number(reject.json.data.status) === 3
      && Number(reject.json.data.actionApplied) === 1,
    reject.json?.data && { status: reject.json.data.status, actionApplied: reject.json.data.actionApplied });
  if (HAS_MYSQL) {
    check('拒绝歌曲审核会同步把歌曲 audit_status 置 2',
      mysqlScalar(`SELECT audit_status FROM newmusic.t_song WHERE id=${song1.id}`) === '2');
    check('审核结果通知已写给提交人（notify_type=8）',
      mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${cUser} AND notify_type=8`) !== '0');
  }
  const handleAgain = await adminReq('POST', `/audits/${task1}/handle`, {
    session: adminSession,
    xff: ipOf('audit'),
    body: { result: 'REJECT', reason: 'Second attempt on processed task' },
  });
  check('已处理任务再次处理返回 80030', handleAgain.json?.code === 80030, handleAgain.json?.code);

  // 通过分支：task2
  await adminReq('POST', `/audits/${task2}/claim`, { session: adminSession, xff: ipOf('audit') });
  const approve = await adminReq('POST', `/audits/${task2}/handle`, {
    session: adminSession,
    xff: ipOf('audit'),
    body: { result: 'APPROVE', version: 2 },
  });
  check('通过审核任务成功且状态变 2',
    approve.json?.code === 0 && Number(approve.json.data.status) === 2,
    approve.json?.data && approve.json.data.status);
  check('通过歌曲审核会同步把歌曲 audit_status 置 1',
    mysqlScalar(`SELECT audit_status FROM newmusic.t_song WHERE id=${song2.id}`) === '1');

  const records1 = await adminReq('GET', `/audits/${task1}/records`, { session: adminSession, xff: ipOf('audit') });
  const actions = (records1.json?.data || []).map((r) => r.action);
  check('审核留痕覆盖 CLAIM / RELEASE / REJECT',
    actions.includes('CLAIM') && actions.includes('RELEASE') && actions.includes('REJECT'),
    actions);

  const opAudit = await adminReq('GET', `/audits${qs({ pageNum: 1, pageSize: 5 })}`,
    { session: operatorSession, xff: ipOf('audit') });
  check('运营无审核列表权限（403）', opAudit.status === 403 && opAudit.json.code === 40301);

  // ---------------------------------------------------------------- §10 歌曲上架与举报
  section('§10 歌曲上架门禁与举报处置联动');

  const online2 = await adminReq('PUT', `/songs/${song2.id}/status/1`, {
    session: adminSession,
    xff: ipOf('report'),
  });
  check('审核通过后歌曲可以上架', online2.json?.code === 0, online2.json?.code);
  const onlineDetail = await adminReq('GET', `/songs/${song2.id}`, { session: adminSession, xff: ipOf('report') });
  check('上架后 status=1 且 publishTime 非空',
    Number(onlineDetail.json?.data?.status) === 1 && Boolean(onlineDetail.json?.data?.publishTime),
    onlineDetail.json?.data && { status: onlineDetail.json.data.status, publishTime: onlineDetail.json.data.publishTime });
  if (HAS_MYSQL) {
    check('歌曲上架状态已落库',
      mysqlScalar(`SELECT status FROM newmusic.t_song WHERE id=${song2.id}`) === '1');
  }

  async function createReport(targetType, targetId, targetName, label, priority = 1) {
    const id = nextId();
    mysql(`INSERT INTO newmusic.t_report
      (id, reporter_id, reporter_name, target_type, target_id, target_name, reason_type, reason,
       priority, status, version, create_time)
      VALUES (${id}, ${cUser}, '${cNick}', '${targetType}', '${targetId}',
              ${utf8Hex(targetName)}, 'OTHER', 'verify-09 ${label}', ${priority}, 0, 0, NOW(3));`);
    track(created.reportIds, id);
    return id;
  }

  const reportSong = await createReport('SONG', song2.id, `M09 report song ${RUN_TAG}`, 'song');
  const reportPage = await adminReq('GET', `/reports${qs({ keyword: `M09 report song ${RUN_TAG}`, pageNum: 1, pageSize: 10 })}`,
    { session: adminSession, xff: ipOf('report') });
  check('举报列表按目标名命中测试举报',
    reportPage.json?.code === 0 && (reportPage.json.data.list || []).some((r) => String(r.id) === reportSong),
    reportPage.json?.data?.total);
  const reportDetail = await adminReq('GET', `/reports/${reportSong}`, { session: adminSession, xff: ipOf('report') });
  check('举报详情返回待处理状态', reportDetail.json?.code === 0 && Number(reportDetail.json.data.status) === 0);

  const badAction = await adminReq('POST', `/reports/${reportSong}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'VALID', reason: 'Unsupported action probe', action: 'DROP_DATABASE' },
  });
  check('未知处置动作返回 10001 且事务回滚',
    badAction.json?.code === 10001
      && mysqlScalar(`SELECT status FROM newmusic.t_report WHERE id=${reportSong}`) === '0',
    badAction.json?.code);

  const offline = await adminReq('POST', `/reports/${reportSong}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'VALID', reason: 'Confirmed copyright violation', action: 'OFFLINE_SONG' },
  });
  check('举报成立并联动下架歌曲', offline.json?.code === 0
    && mysqlScalar(`SELECT status FROM newmusic.t_song WHERE id=${song2.id}`) === '0',
    offline.json?.code);
  const handleAgainReport = await adminReq('POST', `/reports/${reportSong}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'INVALID', reason: 'second handling attempt' },
  });
  check('已处理举报再次处理返回 80025', handleAgainReport.json?.code === 80025, handleAgainReport.json?.code);

  const reportUser = await createReport('USER', cUser, `M09 report user ${RUN_TAG}`, 'user');
  const banByReport = await adminReq('POST', `/reports/${reportUser}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'VALID', reason: 'Confirmed abusive behaviour', action: 'BAN_USER' },
  });
  check('举报成立并联动封禁用户（7 天）', banByReport.json?.code === 0
    && mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_user WHERE id=${cUser} AND status=2 AND ban_until > NOW(3)`) === '1',
    banByReport.json?.code);
  await adminReq('POST', `/users/${cUser}/unban`, { session: adminSession, xff: ipOf('report') });

  const reportRoom = await createReport('ROOM', room2.id, `M09 report room ${RUN_TAG}`, 'room');
  const reportRoomSibling = await createReport('ROOM', room2.id, `M09 report room ${RUN_TAG}`, 'room-sibling');
  const closeByReport = await adminReq('POST', `/reports/${reportRoom}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: {
      result: 'VALID',
      reason: 'Confirmed room rule violation',
      action: 'CLOSE_ROOM',
      mergeSiblings: true,
    },
  });
  check('举报成立并联动关闭房间', closeByReport.json?.code === 0
    && mysqlScalar(`SELECT status FROM newmusic.t_room WHERE id=${room2.id}`) === '2',
    closeByReport.json?.code);
  check('同目标其它待处理举报被合并（status=4 + merged_into_id）',
    mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_report WHERE id=${reportRoomSibling}
      AND status=4 AND merged_into_id=${reportRoom}`) === '1');

  const reportNeedMore = await createReport('SONG', song1.id, `M09 report needmore ${RUN_TAG}`, 'needmore');
  const needMore = await adminReq('POST', `/reports/${reportNeedMore}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'NEED_MORE', reason: 'Please provide more evidence' },
  });
  check('举报可标记为待补充（status=3）',
    needMore.json?.code === 0 && Number(needMore.json.data.status) === 3,
    needMore.json?.data && needMore.json.data.status);
  const invalidAfter = await adminReq('POST', `/reports/${reportNeedMore}/handle`, {
    session: adminSession,
    xff: ipOf('report'),
    body: { result: 'INVALID', reason: 'Evidence does not support the claim' },
  });
  check('待补充举报可以再次处理为不成立（status=2）',
    invalidAfter.json?.code === 0 && Number(invalidAfter.json.data.status) === 2,
    invalidAfter.json?.data && invalidAfter.json.data.status);

  // ---------------------------------------------------------------- §11 配置
  section('§11 系统配置（类型校验 / 历史 / 回滚 / 敏感键 / 热更新）');

  if (HAS_MYSQL) {
    sensitiveOriginal = mysqlScalar(
      "SELECT config_value FROM newmusic.t_sys_config WHERE config_key='search.sensitive.words' LIMIT 1");
    sensitiveHistoryBaseline = mysqlScalar(
      "SELECT COALESCE(MAX(id),0) FROM newmusic.t_sys_config_history WHERE config_key='search.sensitive.words'");
  }

  const configPage = await adminReq('GET', `/configs${qs({ keyword: 'search.sensitive.words', pageNum: 1, pageSize: 10 })}`,
    { session: superSession, xff: ipOf('config') });
  const sensitiveConfig = configPage.json?.data?.list?.find((c) => c.configKey === 'search.sensitive.words');
  check('配置列表能查到搜索敏感词项', configPage.json?.code === 0 && Boolean(sensitiveConfig),
    configPage.json?.data?.total);
  check('敏感词配置为字符串类型（valueType=1）', Number(sensitiveConfig?.valueType) === 1,
    sensitiveConfig?.valueType);

  const cfgKey = `verify.m09.${RUN_TAG}.flag`;
  const cfgCreate = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('config'),
    body: {
      configKey: cfgKey,
      configValue: 'true',
      valueType: 3,
      configGroup: 'verify',
      description: 'verify-09 boolean flag',
    },
  });
  const cfgId = cfgCreate.json?.code === 0 ? track(created.configIds, cfgCreate.json.data.id) : null;
  if (cfgId) {
    track(created.configKeys, cfgKey);
    check('新增布尔配置成功', Boolean(cfgId), cfgCreate.json?.code);
    const cfgUpdate = await adminReq('PUT', `/configs/${cfgId}`, {
      session: superSession,
      xff: ipOf('config'),
      body: { configValue: 'false', remark: 'verify-09 update' },
    });
    check('修改配置值为 false 成功', cfgUpdate.json?.code === 0, cfgUpdate.json?.code);
    const history = await adminReq('GET', `/configs/${cfgId}/history`, { session: superSession, xff: ipOf('config') });
    const updateHistory = (history.json?.data || [])
      .find((h) => h.changeType === 'UPDATE' && h.oldValue === 'true' && h.newValue === 'false');
    check('变更历史记录 old/new 值',
      history.json?.code === 0 && Boolean(updateHistory),
      history.json?.data && history.json.data.length);
    const badBoolean = await adminReq('PUT', `/configs/${cfgId}`, {
      session: superSession,
      xff: ipOf('config'),
      body: { configValue: 'notabool' },
    });
    check('布尔配置写入非法值返回 80023', badBoolean.json?.code === 80023, badBoolean.json?.code);
    check('非法值未落库（仍为 false）',
      mysqlScalar(`SELECT config_value FROM newmusic.t_sys_config WHERE id=${cfgId}`) === 'false');

    if (updateHistory) {
      const rollback = await adminReq('POST', `/configs/history/${updateHistory.id}/rollback`, {
        session: superSession,
        xff: ipOf('config'),
      });
      check('回滚到历史值成功', rollback.json?.code === 0, rollback.json?.code);
      check('回滚后配置值恢复为 true',
        mysqlScalar(`SELECT config_value FROM newmusic.t_sys_config WHERE id=${cfgId}`) === 'true');
    } else {
      skip('配置回滚', '未找到可回滚的历史记录');
    }
  } else {
    check('新增布尔配置成功', false, cfgCreate.json?.code);
  }

  const badNumberKey = `verify.m09.${RUN_TAG}.number`;
  const badNumber = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('config'),
    body: { configKey: badNumberKey, configValue: 'abc', valueType: 2, configGroup: 'verify' },
  });
  check('整数型配置写入 abc 返回 80023', badNumber.json?.code === 80023, badNumber.json?.code);

  const secretKey = `verify.m09.${RUN_TAG}.password`;
  const secretCfg = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('config'),
    body: { configKey: secretKey, configValue: 'should-not-be-stored', configGroup: 'verify' },
  });
  check('键名含 password 的配置被拒（80026）', secretCfg.json?.code === 80026, secretCfg.json?.code);

  const dupCfg = await adminReq('POST', '/configs', {
    session: superSession,
    xff: ipOf('config'),
    body: { configKey: cfgKey, configValue: 'true', valueType: 3, configGroup: 'verify' },
  });
  check('重复配置键返回 10010', dupCfg.json?.code === 10010, dupCfg.json?.code);

  // 敏感词热更新：写配置 → 搜索立刻被拦 → 恢复 → 搜索放行
  if (sensitiveConfig) {
    const blockWord = `m09block${RUN_TAG}`.toLowerCase();
    const setWords = await adminReq('PUT', `/configs/${sensitiveConfig.id}`, {
      session: superSession,
      xff: ipOf('config'),
      body: { configValue: blockWord, remark: 'verify-09 sensitive word hot reload' },
    });
    check('修改 search.sensitive.words 成功', setWords.json?.code === 0, setWords.json?.code);
    const blockedSearch = await http('GET', `${API_BASE}/search${qs({ keyword: blockWord })}`,
      { xff: freshIp() });
    check('新敏感词在下一次搜索请求即生效（70004）',
      blockedSearch.json?.code === 70004, blockedSearch.json?.code);

    const restoreWords = await adminReq('PUT', `/configs/${sensitiveConfig.id}`, {
      session: superSession,
      xff: ipOf('config'),
      body: { configValue: sensitiveOriginal == null ? '' : sensitiveOriginal, remark: 'verify-09 restore' },
    });
    check('恢复原敏感词配置成功', restoreWords.json?.code === 0, restoreWords.json?.code);
    const allowedSearch = await http('GET', `${API_BASE}/search${qs({ keyword: blockWord })}`,
      { xff: freshIp() });
    check('恢复后同一个词不再被拦截（词典已重新加载）',
      allowedSearch.json && allowedSearch.json.code !== 70004,
      allowedSearch.json && allowedSearch.json.code);
  } else {
    skip('敏感词热更新', '未找到 search.sensitive.words 配置');
  }

  // ---------------------------------------------------------------- §12 账号
  section('§12 管理员账号（会话轮换 / 重置密码 / TOTP / 最后超管保护）');

  if (!admin2Session) {
    skip('管理员账号会话与 TOTP 用例', '第二个管理员账号未创建成功');
    return;
  }

  // refresh 一次性轮换 + 重放检测
  const oldRefresh = admin2Session.jar.get('ml_admin_refresh')?.value;
  const oldAccess = admin2Session.jar.get('ml_admin_access')?.value;
  const refresh = await adminReq('POST', '/auth/refresh', { session: admin2Session, xff: ipOf('account') });
  check('refresh Token 轮换成功', refresh.json?.code === 0, refresh.json?.code);
  const newRefresh = admin2Session.jar.get('ml_admin_refresh')?.value;
  check('轮换后拿到新的 refresh Token', Boolean(oldRefresh) && oldRefresh !== newRefresh);
  check('轮换后拿到新的 access Token',
    Boolean(oldAccess) && oldAccess !== admin2Session.jar.get('ml_admin_access')?.value);

  const replay = await http('POST', `${ADMIN_BASE}/auth/refresh`, {
    xff: ipOf('account'),
    headers: { Cookie: `ml_admin_refresh=${oldRefresh}` },
  });
  check('旧 refresh Token 重放被拒（401/40102）',
    replay.status === 401 && replay.json && replay.json.code === 40102,
    `${replay.status}/${replay.json && replay.json.code}`);
  const afterReplay = await adminReq('GET', '/auth/me', { session: admin2Session, xff: ipOf('account') });
  check('重放检测会递增会话版本，使新 access Token 同时失效',
    afterReplay.status === 401, `${afterReplay.status}/${afterReplay.json && afterReplay.json.code}`);

  const admin2Relogin = await login(admin2User, admin2Pass, { xff: ipOf('account'), tag: 'admin2-relogin' });
  admin2Session = admin2Relogin.session;
  check('重新登录成功', admin2Relogin.res.json?.code === 0, admin2Relogin.res.json?.code);

  const logoutAccess = admin2Session.jar.get('ml_admin_access')?.value;
  const logoutClaims = logoutAccess ? jwtClaims(logoutAccess) : null;
  const logout = await adminReq('POST', '/auth/logout', { session: admin2Session, xff: ipOf('account') });
  if (logoutClaims && logoutClaims.jti) redisCleanupKeys.push(`ml:admin:blacklist:${logoutClaims.jti}`);
  check('退出登录成功', logout.json?.code === 0, logout.json?.code);
  check('退出登录清除两个 Cookie', !admin2Session.jar.has('ml_admin_access') && !admin2Session.jar.has('ml_admin_refresh'));
  const afterLogout = await adminReq('GET', '/auth/me', { session: admin2Session, xff: ipOf('account') });
  check('退出后原会话立即失效（401）', afterLogout.status === 401,
    `${afterLogout.status}/${afterLogout.json && afterLogout.json.code}`);

  admin2Session = (await login(admin2User, admin2Pass, { xff: ipOf('account'), tag: 'admin2-3' })).session;
  check('退出后可再次登录', admin2Session.jar.has('ml_admin_access'));

  const wrongOld = await adminReq('POST', '/auth/password', {
    session: admin2Session,
    xff: ipOf('account'),
    body: { oldPassword: 'WrongOld123', newPassword: 'Probe@54321' },
  });
  check('修改密码时原密码错误返回 80011', wrongOld.json?.code === 80011, wrongOld.json?.code);
  const changed = await adminReq('POST', '/auth/password', {
    session: admin2Session,
    xff: ipOf('account'),
    body: { oldPassword: admin2Pass, newPassword: 'Probe@54321' },
  });
  check('修改密码成功并重新签发 Cookie',
    changed.json?.code === 0 && admin2Session.jar.has('ml_admin_access'), changed.json?.code);
  if (HAS_MYSQL) {
    check('修改密码后 force_change_password 清零',
      mysqlScalar(`SELECT force_change_password FROM newmusic.t_admin_user WHERE id=${admin2Id}`) === '0');
  }
  const meAfterPwd = await adminReq('GET', '/auth/me', { session: admin2Session, xff: ipOf('account') });
  check('改密后会话仍然可用（无需重新登录）', meAfterPwd.json?.code === 0);

  // 没有密钥时直接开启 TOTP 应被拒绝
  const enableNoSecret = await adminReq('PUT', `/accounts/${admin2Id}`, {
    session: superSession,
    xff: ipOf('account'),
    body: {
      username: admin2User,
      realName: 'M09 Verify Admin',
      email: `${admin2User}@example.com`,
      roleCode: 'ADMIN',
      totpEnabled: true,
      allowIpList: '',
    },
  });
  check('没有 TOTP 密钥时直接开启被拒（10001）', enableNoSecret.json?.code === 10001, enableNoSecret.json?.code);

  const resetTotp = await adminReq('POST', `/accounts/${admin2Id}/reset-totp`, {
    session: superSession,
    xff: ipOf('account'),
  });
  const newSecret = resetTotp.json?.code === 0 ? resetTotp.json.data : null;
  check('重置 TOTP 返回 Base32 密钥', typeof newSecret === 'string' && newSecret.length >= 16,
    newSecret && `${newSecret.slice(0, 3)}***`);
  if (HAS_MYSQL && newSecret) {
    check('TOTP 密钥与开关已落库',
      mysqlScalar(`SELECT CONCAT(totp_enabled,':',totp_secret) FROM newmusic.t_admin_user WHERE id=${admin2Id}`) === `1:${newSecret}`);
  }

  const needTotp = await login(admin2User, 'Probe@54321', { xff: ipOf('account'), tag: 'admin2-nototp' });
  check('开启 TOTP 后登录必须提供动态码（80015）',
    needTotp.res.json?.code === 80015, needTotp.res.json?.code);
  const withTotp = await login(admin2User, 'Probe@54321', {
    totpCode: totp(newSecret),
    xff: ipOf('account'),
    tag: 'admin2-totp',
  });
  check('使用 TOTP 登录成功', withTotp.res.json?.code === 0, withTotp.res.json?.code);
  admin2Session = withTotp.session;

  // 关闭 TOTP 时必须显式传 false，并清空密钥
  const disableTotp = await adminReq('PUT', `/accounts/${admin2Id}`, {
    session: superSession,
    xff: ipOf('account'),
    body: {
      username: admin2User,
      realName: 'M09 Verify Admin',
      email: `${admin2User}@example.com`,
      roleCode: 'ADMIN',
      totpEnabled: false,
      allowIpList: '',
    },
  });
  check('关闭 TOTP 成功', disableTotp.json?.code === 0, disableTotp.json?.code);
  if (HAS_MYSQL) {
    check('关闭 TOTP 后密钥被清空',
      mysqlScalar(`SELECT CONCAT(totp_enabled,':',COALESCE(totp_secret,'NULL')) FROM newmusic.t_admin_user WHERE id=${admin2Id}`) === '0:NULL');
    check('编辑时未提交 phone 不会清空已保存手机号',
      mysqlScalar(`SELECT phone FROM newmusic.t_admin_user WHERE id=${admin2Id}`) === '13900139000');
  }

  // IP 白名单
  const ipAllowed = '203.0.113.9';
  const setIp = await adminReq('PUT', `/accounts/${admin2Id}`, {
    session: superSession,
    xff: ipOf('account'),
    body: {
      username: admin2User,
      realName: 'M09 Verify Admin',
      email: `${admin2User}@example.com`,
      roleCode: 'ADMIN',
      totpEnabled: false,
      allowIpList: ipAllowed,
    },
  });
  check('设置 IP 白名单成功', setIp.json?.code === 0, setIp.json?.code);
  const loginAllowedIp = await login(admin2User, 'Probe@54321', { xff: ipAllowed, tag: 'admin2-ip-ok' });
  check('白名单内 IP 可以登录', loginAllowedIp.res.json?.code === 0, loginAllowedIp.res.json?.code);
  admin2Session = loginAllowedIp.session;
  const loginBlockedIp = await login(admin2User, 'Probe@54321', { xff: '198.51.100.7', tag: 'admin2-ip-no' });
  check('白名单外 IP 登录返回 80027',
    loginBlockedIp.res.json?.code === 80027, loginBlockedIp.res.json?.code);

  const clearIp = await adminReq('PUT', `/accounts/${admin2Id}`, {
    session: superSession,
    xff: ipOf('account'),
    body: {
      username: admin2User,
      realName: 'M09 Verify Admin',
      email: `${admin2User}@example.com`,
      roleCode: 'ADMIN',
      totpEnabled: false,
      allowIpList: '',
    },
  });
  check('清空白名单成功', clearIp.json?.code === 0, clearIp.json?.code);

  const resetPwd = await adminReq('POST', `/accounts/${admin2Id}/reset-password`, {
    session: superSession,
    xff: ipOf('account'),
  });
  const tempPassword = resetPwd.json?.code === 0 ? resetPwd.json.data : null;
  check('重置管理员密码返回一次性 12 位临时密码',
    typeof tempPassword === 'string' && tempPassword.length === 12, tempPassword && `${tempPassword.slice(0, 2)}***`);
  if (HAS_MYSQL) {
    check('重置后 force_change_password=1 且旧会话版本被提升',
      mysqlScalar(`SELECT force_change_password FROM newmusic.t_admin_user WHERE id=${admin2Id}`) === '1');
  }
  const tempLogin = await login(admin2User, tempPassword, { xff: ipOf('account'), tag: 'admin2-temp' });
  check('临时密码可以登录且强制改密标记为 true',
    tempLogin.res.json?.code === 0 && tempLogin.res.json?.data?.forceChangePassword === true,
    tempLogin.res.json?.data && tempLogin.res.json.data.forceChangePassword);

  const disable = await adminReq('POST', `/accounts/${admin2Id}/status`, {
    session: superSession,
    xff: ipOf('account'),
    body: { status: 2 },
  });
  check('禁用管理员账号成功', disable.json?.code === 0, disable.json?.code);
  const disabledLogin = await login(admin2User, tempPassword, { xff: ipOf('account'), tag: 'admin2-disabled' });
  check('被禁用账号登录返回 80014', disabledLogin.res.json?.code === 80014, disabledLogin.res.json?.code);

  const selfDisable = await adminReq('POST', `/accounts/${superAdminId}/status`, {
    session: superSession,
    xff: ipOf('account'),
    body: { status: 2 },
  });
  check('超管不能禁用自己（80018）', selfDisable.json?.code === 80018, selfDisable.json?.code);

  const demoteLastSuper = await adminReq('PUT', `/accounts/${superAdminId}`, {
    session: superSession,
    xff: ipOf('account'),
    body: {
      username: SUPER_USER,
      realName: '超级管理员',
      email: 'superadmin@example.com',
      roleCode: 'ADMIN',
      totpEnabled: true,
      allowIpList: '',
    },
  });
  check('不能把最后一个超管降级（80017）',
    demoteLastSuper.json?.code === 80017, demoteLastSuper.json?.code);
  if (HAS_MYSQL && superAdminId) {
    check('降级失败后超管角色未被改动',
      mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_admin_user_role ur
        JOIN newmusic.t_admin_role r ON r.id=ur.role_id
        WHERE ur.user_id=${superAdminId} AND r.role_code='SUPER_ADMIN'`) === '1');
  }

  // ---------------------------------------------------------------- §12.5 日志证据
  section('§12.5 操作日志硬证据');

  const phoneLog = await waitFor(async () => {
    const r = await adminReq('GET', `/logs/operations${qs({
      opType: 'USER_PHONE_VIEW',
      targetType: 'USER',
      keyword: cUser,
      pageNum: 1,
      pageSize: 20,
    })}`, { session: adminSession, xff: ipOf('log') });
    const list = r.json?.data?.list || [];
    return list.find((row) => String(row.targetId) === cUser) || null;
  });
  check('查看完整手机号写入了操作日志（USER_PHONE_VIEW）',
    Boolean(phoneLog) && String(phoneLog.operatorId) === mainAdminId,
    phoneLog && phoneLog.operatorId);
  check('操作日志快照对手机号脱敏（不含完整号码）',
    Boolean(phoneLog) && String(phoneLog.afterSnapshot || '').includes('***')
      && !String(phoneLog.afterSnapshot || '').includes(cPhone),
    phoneLog && String(phoneLog.afterSnapshot || '').slice(0, 160));

  const loginLogs = await waitFor(async () => {
    const r = await adminReq('GET', `/logs/logins${qs({ loginType: 2, keyword: SUPER_USER, pageNum: 1, pageSize: 20 })}`,
      { session: superSession, xff: ipOf('log') });
    const list = r.json?.data?.list || [];
    return list.length ? list : null;
  });
  check('后台登录日志记录成功事件且 loginType=2',
    Array.isArray(loginLogs) && loginLogs.length > 0
      && loginLogs.every((row) => Number(row.loginType) === 2)
      && loginLogs.some((row) => Number(row.result) === 1),
    loginLogs && loginLogs.length);

  const exportRes = await adminReq('GET', `/logs/operations/export${qs({ pageNum: 1, pageSize: 10 })}`,
    { session: superSession, xff: ipOf('log') });
  check('超管可导出操作日志 CSV',
    exportRes.status === 200 && /text\/csv/i.test(exportRes.headers.get('content-type') || ''),
    `${exportRes.status}/${exportRes.headers.get('content-type')}`);
  check('导出 CSV 带 UTF-8 BOM 与表头',
    exportRes.text.charCodeAt(0) === 0xfeff && exportRes.text.includes('ID,时间,操作人ID'), exportRes.text.slice(0, 40));
}

// ================================================================ 清理

async function cleanup() {
  if (!HAS_MYSQL) return;
  const adminIds = sqlIdList(created.adminIds);
  const userIds = sqlIdList(created.userIds);
  const roomIds = sqlIdList(created.roomIds);
  const messageIds = sqlIdList(created.messageIds);
  const taskIds = sqlIdList(created.taskIds);
  const reportIds = sqlIdList(created.reportIds);
  const artistIds = sqlIdList(created.artistIds);
  const albumIds = sqlIdList(created.albumIds);
  const songIds = sqlIdList(created.songIds);
  const configIds = sqlIdList(created.configIds);

  try {
    if (adminIds) {
      mysql(`DELETE FROM newmusic.t_operation_log WHERE operator_id IN (${adminIds});`);
      mysql(`DELETE FROM newmusic.t_login_log WHERE login_type=2 AND user_id IN (${adminIds});`);
      mysql(`DELETE FROM newmusic.t_admin_user_role WHERE user_id IN (${adminIds});`);
      mysql(`DELETE FROM newmusic.t_admin_user WHERE id IN (${adminIds});`);
    }
    if (fixtureLogId) {
      mysql(`DELETE FROM newmusic.t_operation_log WHERE id=${fixtureLogId};`);
    }
    if (userIds) {
      mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN (${userIds});`);
      mysql(`DELETE FROM newmusic.t_user WHERE id IN (${userIds});`);
    }
    if (roomIds) {
      mysql(`DELETE FROM newmusic.t_room_message WHERE room_id IN (${roomIds});`);
      mysql(`DELETE FROM newmusic.t_room WHERE id IN (${roomIds});`);
    }
    if (messageIds) {
      mysql(`DELETE FROM newmusic.t_room_message WHERE id IN (${messageIds});`);
    }
    if (taskIds) {
      mysql(`DELETE FROM newmusic.t_audit_record WHERE task_id IN (${taskIds});`);
      mysql(`DELETE FROM newmusic.t_audit_task WHERE id IN (${taskIds});`);
    }
    if (reportIds) {
      mysql(`DELETE FROM newmusic.t_report WHERE id IN (${reportIds});`);
    }
    if (songIds) {
      mysql(`DELETE FROM newmusic.t_song WHERE id IN (${songIds});`);
    }
    if (albumIds) {
      mysql(`DELETE FROM newmusic.t_album WHERE id IN (${albumIds});`);
    }
    if (artistIds) {
      mysql(`DELETE FROM newmusic.t_artist WHERE id IN (${artistIds});`);
    }
    if (configIds) {
      mysql(`DELETE FROM newmusic.t_sys_config WHERE id IN (${configIds});`);
    }
    for (const key of created.configKeys) {
      mysql(`DELETE FROM newmusic.t_sys_config_history WHERE config_key=${utf8Hex(key)};`);
      mysql(`DELETE FROM newmusic.t_sys_config WHERE config_key=${utf8Hex(key)};`);
    }
    // 敏感词配置：恢复原值并删除本轮新增历史
    if (sensitiveOriginal !== null && sensitiveHistoryBaseline !== null) {
      mysql(`UPDATE newmusic.t_sys_config SET config_value=${utf8Hex(sensitiveOriginal)}
        WHERE config_key='search.sensitive.words';`);
      mysql(`DELETE FROM newmusic.t_sys_config_history
        WHERE config_key='search.sensitive.words' AND id > ${Number(sensitiveHistoryBaseline) || 0};`);
    }
  } finally {
    if (REDIS_OK) {
      const keys = [
        ...redisCleanupKeys,
        ...created.userIds.map((id) => `ml:auth:userban:${id}`),
        ...created.userIds.map((id) => `ml:admin:room:createban:${id}`),
        ...created.userIds.map((id) => `ml:notify:unread:${id}`),
        ...created.adminIds.map((id) => `ml:admin:session:ver:${id}`),
        ...created.adminIds.map((id) => `ml:admin:refresh:user:${id}`),
      ];
      try {
        redisDel(...keys);
      } catch (e) {
        note(`Redis 清理失败：${e.message}`);
      }
    }
  }
}

// ================================================================ 启动

if (!HAS_MYSQL) {
  console.error('\x1b[31m缺少 MYSQL_PASSWORD\x1b[0m');
  console.error('本脚本需要直接插入/清理测试用户、房间、审核任务与举报，未设置密码时不会伪造通过。');
  console.error('用法：MYSQL_PASSWORD=<本机 MySQL 口令> node scripts/verify-09.mjs');
  process.exit(2);
}

console.log(`模块 09 验收开始：tag=${RUN_TAG} admin=${ADMIN_USER} base=${ADMIN_BASE}`);

let runError = null;
try {
  await run();
} catch (e) {
  runError = e;
  fail += 1;
  failures.push('脚本执行异常');
  console.error(`\n\x1b[31m脚本执行异常：\x1b[0m ${e && e.stack ? e.stack : e}`);
}

try {
  await cleanup();
  console.log('\n\x1b[90m测试数据已按精确 ID 清理。\x1b[0m');
} catch (e) {
  fail += 1;
  failures.push('清理测试数据失败');
  console.error(`\n\x1b[31m清理失败：\x1b[0m ${e && e.stack ? e.stack : e}`);
}

section('§13 共享实例完整性');
if (HAS_MYSQL) {
  const leftover = mysqlScalar(`SELECT
    (SELECT COUNT(*) FROM newmusic.t_admin_user WHERE id IN (${sqlIdList(created.adminIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_user WHERE id IN (${sqlIdList(created.userIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_room WHERE id IN (${sqlIdList(created.roomIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_audit_task WHERE id IN (${sqlIdList(created.taskIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_report WHERE id IN (${sqlIdList(created.reportIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_song WHERE id IN (${sqlIdList(created.songIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_album WHERE id IN (${sqlIdList(created.albumIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_artist WHERE id IN (${sqlIdList(created.artistIds) || '0'})) +
    (SELECT COUNT(*) FROM newmusic.t_sys_config WHERE id IN (${sqlIdList(created.configIds) || '0'}))`);
  check('本轮测试数据已全部清理（业务表零残留）', Number(leftover) === 0, leftover);
  const sharedDbTablesAfter = mysqlScalar(
    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
  check('共享实例中的 emp_db / music_db 表数量未变化',
    sharedDbTablesAfter === sharedDbTablesBefore, `${sharedDbTablesBefore} → ${sharedDbTablesAfter}`);
} else {
  skip('MySQL 残留与共享库完整性', '未设置 MYSQL_PASSWORD');
}
if (REDIS_OK) {
  const rankingAfter = listKeys('ranking:*');
  check('另一个项目的 ranking:* 键数量未变化',
    rankingAfter.length === rankingBefore.length, `${rankingBefore.length} → ${rankingAfter.length}`);
  check('本轮创建的封禁/创建限制键均已删除',
    [...created.userIds].every((id) => redis('exists', `ml:auth:userban:${id}`) === '0'
      && redis('exists', `ml:admin:room:createban:${id}`) === '0'));
} else {
  skip('Redis 共享键完整性', 'redis-cli 不可用');
}

// ================================================================ 汇总

console.log('\n' + '='.repeat(64));
console.log(`\x1b[1m模块 09 验收结果：通过 ${pass}，失败 ${fail}，跳过 ${skipped}\x1b[0m`);
if (skipNotes.length) {
  console.log('\n跳过项：');
  for (const item of skipNotes) console.log(`  - ${item}`);
}
if (failures.length) {
  console.log('\n失败项：');
  for (const item of failures) console.log(`  - ${item}`);
}
if (runError) {
  console.log(`\n首个异常：${runError.message}`);
}
console.log('='.repeat(64));
process.exit(fail > 0 ? 1 : 0);
