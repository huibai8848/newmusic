#!/usr/bin/env node
/**
 * 模块 10 验收脚本 —— 对应 docs/系分/10-社交基础.md 的验收核对表。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-10.mjs
 *
 * 前置：应用已启动（8081）、本机 Redis 6379 可连、MySQL 已执行 sql/10-social-seed.sql。
 * 依赖 Node 22+（全局 fetch / WebSocket），零第三方依赖。
 *
 * 设计要点：
 *   * 两个本轮新注册用户 A/B 覆盖评论、点赞、收藏、关注、拉黑、私信、通知、设置；
 *     注册后把 create_time 回拨 30 天，让评论走「已发布」而不是新用户先审后发；
 *   * 复用种子用户 demo 做「第二举报人合并」「歌单评论权限」这两类需要第三方身份的分支；
 *   * 每节使用随机合成 X-Forwarded-For，避免限流/登录失败计数串场；
 *   * 敏感词、限流、非互关每日条数都按真实窗口断言，必要时只清理本轮精确的用户键；
 *   * MySQL 3306 / Redis 6379 是共享实例：SQL 全部带 newmusic. 限定符，
 *     Redis 只用 ml: 前缀，开头/结尾比对 ranking:* 与 emp_db/music_db，证明没碰别的项目；
 *   * 所有测试数据按精确 ID 清理，并核对被改动的内容计数已恢复原值；
 *   * 断言拿硬证据（DB / Redis / WS 帧），不看「响应看起来对」。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- 基础配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ROOT_BASE = API_BASE.replace(/\/+$/, '').replace(/\/api\/v1$/, '');
const ADMIN_BASE = process.env.ADMIN_API_BASE || `${API_BASE}/admin`;
const WS_BASE = process.env.WS_BASE || `${ROOT_BASE}/ws`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';

const ADMIN_USER = process.env.M10_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.M10_ADMIN_PASSWORD || 'admin123';
const ADMIN_TOTP_SECRET = process.env.M10_ADMIN_TOTP_SECRET || '';

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
const PASS_WORD = 'Aa123456';

const SONG_ID = '1000000000000005001';
const SONG_ID_2 = '1000000000000005002';
const PLAYLIST_ID = '1000000000000010001';
const PRIVATE_PLAYLIST_ID = '1000000000000010002';
const ALBUM_ID = '1000000000000004001';
const ARTIST_ID = '1000000000000003001';
// demo 名下没有仍有效的公开歌单（历史冒烟数据已逻辑删除），本轮现建一个，收尾物理删除
let demoPlaylistId = null;
const DEMO_ACCOUNT = 'demo';
const DEMO_PASSWORD = 'demo123456';

const syntheticIp = () => `10.98.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
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
const mysqlCount = (sql) => Number(mysqlScalar(sql) || 0);

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
const delByPattern = (pattern) => {
  const keys = listKeys(pattern);
  if (keys.length) redisDel(...keys);
  return keys.length;
};

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，Redis 侧断言会跳过：${e.message}`);
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*') : [];
const sharedDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- HTTP

const touchedIps = new Set();

async function http(method, path, { token, body, xff, headers, redirect } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = `Bearer ${token}`;
  if (xff) {
    h['X-Forwarded-For'] = xff;
    touchedIps.add(xff);
  }
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: redirect || 'follow',
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text, headers: res.headers };
}

async function rawGet(url, { redirect, xff } = {}) {
  const h = { Accept: '*/*' };
  if (xff) {
    h['X-Forwarded-For'] = xff;
    touchedIps.add(xff);
  }
  return fetch(url, { method: 'GET', headers: h, redirect: redirect || 'follow' });
}

const codeOf = (res) => (res && res.json ? res.json.code : undefined);
const dataOf = (res) => (res && res.json ? res.json.data : undefined);

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

// ---------------------------------------------------------------- TOTP（后台登录兜底）

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

function setCookieLines(res) {
  if (typeof res.headers.getSetCookie === 'function') {
    const values = res.headers.getSetCookie();
    if (values && values.length) return values;
  }
  const raw = res.headers.get('set-cookie');
  if (!raw) return [];
  return raw.split(/,(?=\s*ml_admin_)/).map((s) => s.trim()).filter(Boolean);
}

async function adminLogin() {
  const ip = syntheticIp();
  touchedIps.add(ip);
  const capRes = await fetch(`${ADMIN_BASE}/auth/captcha`, {
    headers: { Accept: 'application/json', 'X-Forwarded-For': ip },
  });
  const cap = await capRes.json();
  if (!cap || cap.code !== 0 || !cap.data || !cap.data.captchaId || !cap.data.code) {
    throw new Error(`后台验证码回显不可用（code=${cap && cap.code}）`);
  }
  created.adminCaptchaIds.push(cap.data.captchaId);

  const body = {
    username: ADMIN_USER,
    password: ADMIN_PASS,
    captchaId: cap.data.captchaId,
    captchaCode: cap.data.code,
  };
  const first = await fetch(`${ADMIN_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': ip },
    body: JSON.stringify(body),
  });
  let login = await first.json();
  if (login && login.code === 80016 && ADMIN_TOTP_SECRET) {
    body.totpCode = totp(ADMIN_TOTP_SECRET);
    const second = await fetch(`${ADMIN_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': syntheticIp() },
      body: JSON.stringify(body),
    });
    login = await second.json();
    return { login, cookies: setCookieLines(second) };
  }
  if (login && login.code === 0 && login.data && login.data.admin
      && Number(login.data.admin.totpEnabled) === 1 && !ADMIN_TOTP_SECRET) {
    throw new Error('后台账号启用了 TOTP，但未设置 M10_ADMIN_TOTP_SECRET');
  }
  return { login, cookies: setCookieLines(first) };
}

async function adminHttp(method, path, { cookie, body, xff } = {}) {
  const h = { Accept: 'application/json', Cookie: cookie };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (xff) {
    h['X-Forwarded-For'] = xff;
    touchedIps.add(xff);
  }
  const res = await fetch(`${ADMIN_BASE}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* ignore */
  }
  return { status: res.status, json, text, headers: res.headers };
}

// ---------------------------------------------------------------- WebSocket

function connectWs(token, deviceId) {
  const url = new URL(WS_BASE);
  url.searchParams.set('token', token);
  url.searchParams.set('deviceId', deviceId);
  const socket = new WebSocket(url.toString());
  const inbox = [];
  const waiters = [];
  const state = { close: null, closed: false };

  socket.addEventListener('message', (event) => {
    let message;
    try {
      message = JSON.parse(String(event.data));
    } catch {
      return;
    }
    for (let i = 0; i < waiters.length; i += 1) {
      if (waiters[i].predicate(message)) {
        const waiter = waiters.splice(i, 1)[0];
        clearTimeout(waiter.timer);
        waiter.resolve(message);
        return;
      }
    }
    inbox.push(message);
    if (inbox.length > 500) inbox.shift();
  });
  socket.addEventListener('close', (event) => {
    state.close = { code: event.code, reason: event.reason };
    state.closed = true;
    while (waiters.length) {
      const waiter = waiters.shift();
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`连接已关闭（code=${state.close?.code}）`));
    }
  });

  const opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', () => resolve());
    socket.addEventListener('error', () => reject(new Error('WS 连接失败')));
  });

  const client = {
    socket,
    async ready() {
      await opened;
      await client.wait((m) => m.type === 'connected', 5000).catch(() => null);
      return client;
    },
    wait(predicate, timeout = 6000) {
      const existing = inbox.findIndex(predicate);
      if (existing >= 0) return Promise.resolve(inbox.splice(existing, 1)[0]);
      if (state.closed) return Promise.reject(new Error('连接已关闭'));
      return new Promise((resolve, reject) => {
        const waiter = {
          predicate,
          resolve,
          reject,
          timer: setTimeout(() => {
            const idx = waiters.indexOf(waiter);
            if (idx >= 0) waiters.splice(idx, 1);
            reject(new Error('等待 WS 事件超时'));
          }, timeout),
        };
        waiters.push(waiter);
      });
    },
    close() {
      try {
        socket.close();
      } catch {
        /* ignore */
      }
    },
  };
  return client;
}

// ---------------------------------------------------------------- 测试数据台账

const created = {
  userIds: [],
  commentIds: [],
  folderIds: [],
  reportIds: [],
  shareCodes: [],
  adminCaptchaIds: [],
  userRefreshJtis: [],
  demoRefreshJtis: [],
  adminRefreshJtis: [],
};

const q = (id) => `'${String(id)}'`;
const idList = (ids) => (ids && ids.length ? ids.map(q).join(',') : '0');

// ---------------------------------------------------------------- 启动

async function main() {
  if (!HAS_MYSQL) {
    console.error('\x1b[31m缺少 MYSQL_PASSWORD\x1b[0m');
    console.error('本脚本需要直接核对/清理测试数据，未设置密码时不会伪造通过。');
    console.error('用法：MYSQL_PASSWORD=<本机 MySQL 口令> node scripts/verify-10.mjs');
    process.exitCode = 2;
    return;
  }

  try {
    // 项目没有开放 actuator，用公开字典接口当就绪探针
    const probe = await fetch(`${API_BASE}/reports/reasons`).catch(() => null);
    if (!probe || !probe.ok) throw new Error(`后端不可用：${API_BASE}/reports/reasons`);
  } catch (e) {
    console.error(`\x1b[31m后端未启动\x1b[0m：${e.message}`);
    process.exitCode = 2;
    return;
  }

  const baseline = {
    song: counterRow('t_song', SONG_ID),
    playlist: counterRow('t_playlist', PLAYLIST_ID),
    album: counterRow('t_album', ALBUM_ID),
    demoPlaylist: [0, 0, 0, 0],
    artistFollower: mysqlCount(`SELECT follower_count FROM newmusic.t_artist WHERE id=${ARTIST_ID}`),
  };

  const demoPrivacy = mysql(
    `SELECT message_policy,comment_policy,mention_policy,show_following,show_followers,`
    + `play_history_visibility,read_receipt,new_song_notify,system_notify `
    + `FROM newmusic.t_user_privacy_setting WHERE user_id=1000000000000002003 AND deleted=0`,
  );
  const demoPrivacyExisted = demoPrivacy !== '';

  let users = null;
  let demo = null;
  let admin = null;
  let wsA = null;
  let wsB = null;

  try {
    // ============================================================ §1 准备
    section('§1 准备：注册两个本轮用户并登录');

    const regA = await registerAndLogin('a', 'A');
    const regB = await registerAndLogin('b', 'B');
    users = { A: regA, B: regB };
    created.userIds.push(regA.id, regB.id);

    // 新用户 7 天内评论先审后发；本脚本要验证「已发布」路径，按精确 ID 回拨注册时间。
    mysql(`UPDATE newmusic.t_user SET create_time=DATE_SUB(NOW(3), INTERVAL 30 DAY) `
      + `WHERE id IN (${idList(created.userIds)})`);
    check('两个验收用户已注册并完成登录',
      Boolean(regA.token && regB.token && regA.id && regB.id), { a: regA.id, b: regB.id });

    demo = await loginExisting(DEMO_ACCOUNT, DEMO_PASSWORD, 'demo');
    check('种子用户 demo 登录成功（第三方举报/歌单权限用）', Boolean(demo.token), demo.loginCode);

    // ============================================================ §2 公共读
    section('§2 公共读接口');

    const reasons = await http('GET', '/reports/reasons', { xff: syntheticIp() });
    check('举报理由字典公开可读', codeOf(reasons) === 0 && Array.isArray(dataOf(reasons)));
    check('举报理由包含 17 项且含 POLITICS/OBSCENE', dataOf(reasons)?.length === 17
      && dataOf(reasons).some((r) => r.code === 'POLITICS')
      && dataOf(reasons).some((r) => r.code === 'OBSCENE'), dataOf(reasons)?.length);

    const anonComments = await http('GET',
      `/comments?targetType=SONG&targetId=${SONG_ID}&page=1&size=10`, { xff: syntheticIp() });
    check('评论列表游客可读', codeOf(anonComments) === 0 && Array.isArray(dataOf(anonComments)?.list));
    const anonProtected = await http('GET', '/notifications', { xff: syntheticIp() });
    check('未登录访问通知中心被鉴权拦下', anonProtected.status === 401 || codeOf(anonProtected) !== 0,
      { status: anonProtected.status, code: codeOf(anonProtected) });

    // ============================================================ §3 评论
    section('§3 评论：发布 / 回复 / 清洗 / 去重 / 删除权限');

    const c1Text = `验收评论C1-${RUN_TAG}`;
    const c2Text = `验收评论C2-${RUN_TAG}`;
    const c1Res = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID, content: c1Text },
    });
    const c1 = c1Res.json?.data ? String(c1Res.json.data.id) : null;
    if (c1) created.commentIds.push(c1);
    check('A 发表评论成功且直接发布', codeOf(c1Res) === 0 && c1
      && Number(c1Res.json.data.status) === 1, c1Res.json);

    const c2Res = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID, content: c2Text },
    });
    const c2 = c2Res.json?.data ? String(c2Res.json.data.id) : null;
    if (c2) created.commentIds.push(c2);
    check('A 发表第二篇评论成功（后续点赞抑制用）', codeOf(c2Res) === 0 && c2, codeOf(c2Res));

    const c3Res = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: {
        targetType: 'SONG', targetId: SONG_ID,
        content: `<script>alert(1)</script>安全评论${RUN_TAG}`,
      },
    });
    const c3 = c3Res.json?.data ? String(c3Res.json.data.id) : null;
    if (c3) created.commentIds.push(c3);
    check('评论中的 HTML 标签被剔除', codeOf(c3Res) === 0 && c3
      && !String(c3Res.json.data.content).includes('<script>')
      && String(c3Res.json.data.content).includes(`安全评论${RUN_TAG}`), c3Res.json?.data?.content);

    if (c1) {
      check('评论落库为已发布且内容已清洗', HAS_MYSQL
        && mysqlScalar(`SELECT CONCAT(status,':',target_type,':',target_id) FROM newmusic.t_comment `
          + `WHERE id=${q(c1)}`) === `1:SONG:${SONG_ID}`, HAS_MYSQL
        ? mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id=${q(c1)}`) : 'no-mysql');
      check('歌曲评论计数 +3（C1/C2/C3）', HAS_MYSQL
        && mysqlCount(`SELECT comment_count FROM newmusic.t_song WHERE id=${SONG_ID}`)
          === baseline.song[2] + 3,
      HAS_MYSQL ? mysqlScalar(`SELECT comment_count FROM newmusic.t_song WHERE id=${SONG_ID}`) : 'no-mysql');
    }

    const publicList = await http('GET',
      `/comments?targetType=SONG&targetId=${SONG_ID}&page=1&size=20`, { xff: syntheticIp() });
    const publicIds = (dataOf(publicList)?.list || []).map((x) => String(x.id));
    check('公开评论列表包含刚发布的评论（对照集合非空）',
      publicIds.length >= 2 && publicIds.includes(c1) && publicIds.includes(c2), publicIds);

    let r1 = null;
    if (c1) {
      const replyRes = await http('POST', '/comments', {
        token: users.B.token,
        xff: syntheticIp(),
        body: { targetType: 'COMMENT', targetId: c1, content: `验收回复R1-${RUN_TAG}` },
      });
      r1 = replyRes.json?.data ? String(replyRes.json.data.id) : null;
      if (r1) created.commentIds.push(r1);
      check('B 回复 A 的评论成功', codeOf(replyRes) === 0 && r1
        && String(replyRes.json.data.rootId) === c1
        && String(replyRes.json.data.parentId) === c1
        && String(replyRes.json.data.replyUserId) === users.A.id, replyRes.json?.data);
      check('回复落库 root_id/parent_id/reply_user_id 正确', HAS_MYSQL && r1
        && mysqlScalar(`SELECT CONCAT(root_id,':',parent_id,':',reply_user_id) FROM newmusic.t_comment `
          + `WHERE id=${q(r1)}`) === `${c1}:${c1}:${users.A.id}`);
      check('根评论 reply_count +1', HAS_MYSQL && r1
        && mysqlCount(`SELECT reply_count FROM newmusic.t_comment WHERE id=${q(c1)}`) === 1);

      const replies = await http('GET', `/comments/${c1}/replies?page=1&size=20`, {
        token: users.A.token, xff: syntheticIp(),
      });
      check('回复列表返回该回复（对照集合非空）',
        (dataOf(replies)?.list || []).some((x) => String(x.id) === r1), dataOf(replies));
    }

    if (c1) {
      const duplicate = await http('POST', '/comments', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { targetType: 'SONG', targetId: SONG_ID, content: c1Text },
      });
      check('5 分钟内重复内容被拒绝（50009）', codeOf(duplicate) === 50009, codeOf(duplicate));

      const forbidden = await http('DELETE', `/comments/${c1}`, {
        token: users.B.token, xff: syntheticIp(),
      });
      check('非作者不能删除他人评论', codeOf(forbidden) !== 0,
        { status: forbidden.status, code: codeOf(forbidden) });
      check('越权删除后评论仍在', (await http('GET',
        `/comments?targetType=SONG&targetId=${SONG_ID}&size=20`, { xff: syntheticIp() }))
        .json?.data?.list?.some((x) => String(x.id) === c1));
    }

    if (c3) {
      const del = await http('DELETE', `/comments/${c3}`, {
        token: users.A.token, xff: syntheticIp(),
      });
      check('作者删除评论成功', codeOf(del) === 0, codeOf(del));
      check('删除后数据库状态为已删除', HAS_MYSQL
        && mysqlScalar(`SELECT status FROM newmusic.t_comment WHERE id=${q(c3)}`) === '2');
      const listAfterDelete = await http('GET',
        `/comments?targetType=SONG&targetId=${SONG_ID}&size=50`, { xff: syntheticIp() });
      check('删除后公开列表不再返回该评论',
        !(dataOf(listAfterDelete)?.list || []).some((x) => String(x.id) === c3));
    }

    // ============================================================ §4 点赞
    section('§4 点赞：幂等 / 批量 / 我喜欢的歌曲 / Redis 镜像');

    const likeRes = await http('POST', '/likes', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID },
    });
    check('A 点赞歌曲成功', codeOf(likeRes) === 0 && likeRes.json.data.liked === true,
      likeRes.json?.data);
    check('歌曲点赞计数 +1', HAS_MYSQL
      && mysqlCount(`SELECT like_count FROM newmusic.t_song WHERE id=${SONG_ID}`)
        === baseline.song[0] + 1);
    check('点赞关系落库', HAS_MYSQL && mysqlCount(
      `SELECT COUNT(*) FROM newmusic.t_like WHERE user_id=${q(users.A.id)} `
      + `AND target_type='SONG' AND target_id=${SONG_ID}`) === 1);
    if (REDIS_OK) {
      check('点赞写入当前用户 Redis 集合', redis('sismember',
        `ml:like:user:${users.A.id}:SONG`, SONG_ID) === '1');
      check('点赞写入目标 Redis 集合', redis('sismember',
        `ml:like:SONG:${SONG_ID}`, users.A.id) === '1');
    } else {
      skip('点赞 Redis 镜像', 'redis-cli 不可用');
    }

    const repeat = await http('POST', '/likes', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID },
    });
    check('重复点赞幂等，不重复计数', codeOf(repeat) === 0 && HAS_MYSQL
      && mysqlCount(`SELECT like_count FROM newmusic.t_song WHERE id=${SONG_ID}`)
        === baseline.song[0] + 1);

    const batch = await http('POST', '/likes/batch-check', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetIds: [SONG_ID, SONG_ID_2] },
    });
    check('批量点赞状态查询返回两首歌曲',
      codeOf(batch) === 0
      && batch.json.data.liked[SONG_ID] === true
      && batch.json.data.liked[SONG_ID_2] === false, batch.json?.data);

    const likedSongs = await http('GET', '/likes/songs?page=1&size=20', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('我喜欢的歌曲包含刚点赞的歌（对照集合非空）',
      (dataOf(likedSongs)?.list || []).some((x) => String(x.id) === SONG_ID), dataOf(likedSongs)?.total);

    const cancelLike = await http('DELETE', '/likes', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID },
    });
    check('取消点赞成功', codeOf(cancelLike) === 0 && cancelLike.json.data.liked === false);
    check('取消后歌曲点赞计数恢复', HAS_MYSQL
      && mysqlCount(`SELECT like_count FROM newmusic.t_song WHERE id=${SONG_ID}`) === baseline.song[0]);
    check('取消后点赞关系物理删除', HAS_MYSQL && mysqlCount(
      `SELECT COUNT(*) FROM newmusic.t_like WHERE user_id=${q(users.A.id)} `
      + `AND target_type='SONG' AND target_id=${SONG_ID}`) === 0);

    if (c1) {
      const likeComment = await http('POST', '/likes', {
        token: users.B.token,
        xff: syntheticIp(),
        body: { targetType: 'COMMENT', targetId: c1 },
      });
      check('B 点赞 A 的评论成功', codeOf(likeComment) === 0
        && likeComment.json.data.liked === true && Number(likeComment.json.data.count) === 1,
      likeComment.json?.data);
      check('评论 like_count 同步为 1', HAS_MYSQL
        && mysqlCount(`SELECT like_count FROM newmusic.t_comment WHERE id=${q(c1)}`) === 1);

      const hot = await http('GET',
        `/comments?targetType=SONG&targetId=${SONG_ID}&sort=hot&page=1&size=20`, { xff: syntheticIp() });
      check('热度排序把获赞评论排在首位',
        (dataOf(hot)?.list || []).length > 0 && String(dataOf(hot).list[0].id) === c1,
        (dataOf(hot)?.list || []).map((x) => x.id));
    }

    // ============================================================ §5 收藏
    section('§5 收藏：收藏夹 / 移动 / 容量与私密校验');

    const folders0 = await http('GET', '/collect-folders', {
      token: users.A.token, xff: syntheticIp(),
    });
    const defaultFolder = (dataOf(folders0) || []).find((f) => f.isDefault === true);
    check('收藏夹列表包含默认收藏夹且条目为 0',
      codeOf(folders0) === 0 && defaultFolder && Number(defaultFolder.itemCount) === 0,
      dataOf(folders0));

    const folderRes = await http('POST', '/collect-folders', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { name: `验收夹-${RUN_TAG}` },
    });
    const folderId = folderRes.json?.data ? String(folderRes.json.data.id) : null;
    if (folderId) created.folderIds.push(folderId);
    check('新建收藏夹成功', codeOf(folderRes) === 0 && folderId
      && folderRes.json.data.name === `验收夹-${RUN_TAG}`, folderRes.json);

    const dupFolder = await http('POST', '/collect-folders', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { name: `验收夹-${RUN_TAG}` },
    });
    check('同名收藏夹被拒绝（50024）', codeOf(dupFolder) === 50024, codeOf(dupFolder));

    if (folderId) {
      const statusBefore = await http(
        'GET', `/collects/status?targetType=SONG&targetId=${SONG_ID}`,
        { token: users.A.token, xff: syntheticIp() },
      );
      check('收藏状态接口：未收藏时 collected=false 且无 folderId',
        codeOf(statusBefore) === 0
        && statusBefore.json.data.collected === false
        && statusBefore.json.data.folderId === null, statusBefore.json?.data);

      const collect = await http('POST', '/collects', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { targetType: 'SONG', targetId: SONG_ID, folderId },
      });
      const collectId = collect.json?.data ? String(collect.json.data.id) : null;
      check('收藏歌曲到指定收藏夹成功', codeOf(collect) === 0 && collectId
        && String(collect.json.data.folderId) === folderId, collect.json?.data);
      check('歌曲收藏计数 +1', HAS_MYSQL
        && mysqlCount(`SELECT collect_count FROM newmusic.t_song WHERE id=${SONG_ID}`)
          === baseline.song[1] + 1);
      check('收藏夹 item_count=1', HAS_MYSQL
        && mysqlCount(`SELECT item_count FROM newmusic.t_collect_folder WHERE id=${q(folderId)}`) === 1);

      const statusInFolder = await http(
        'GET', `/collects/status?targetType=SONG&targetId=${SONG_ID}`,
        { token: users.A.token, xff: syntheticIp() },
      );
      check('收藏状态接口：已收藏时 collected=true 且返回所在收藏夹',
        codeOf(statusInFolder) === 0
        && statusInFolder.json.data.collected === true
        && String(statusInFolder.json.data.folderId) === folderId, statusInFolder.json?.data);

      const moved = await http('PUT', '/collects/move', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { collectIds: [collectId], folderId: null },
      });
      check('移动收藏到默认收藏夹成功', codeOf(moved) === 0 && Number(moved.json.data) === 1,
        moved.json?.data);
      check('移动后收藏夹 item_count 归零', HAS_MYSQL
        && mysqlCount(`SELECT item_count FROM newmusic.t_collect_folder WHERE id=${q(folderId)}`) === 0);
      check('移动后收藏 folder_id 为 NULL', HAS_MYSQL
        && mysqlScalar(`SELECT IFNULL(folder_id,'NULL') FROM newmusic.t_collect WHERE id=${q(collectId)}`)
          === 'NULL');

      const defaultPage = await http('GET', '/collects?folderId=0&page=1&size=10', {
        token: users.A.token, xff: syntheticIp(),
      });
      const defaultList = dataOf(defaultPage)?.list || [];
      check('folderId=0 等价默认收藏夹且能查到该收藏',
        codeOf(defaultPage) === 0 && defaultList.length > 0
        && defaultList.some((item) => String(item.id) === collectId), dataOf(defaultPage));

      const namedPage = await http('GET', `/collects?folderId=${folderId}&page=1&size=10`, {
        token: users.A.token, xff: syntheticIp(),
      });
      const namedList = dataOf(namedPage)?.list || [];
      check('按指定收藏夹查询不含已移出的收藏',
        codeOf(namedPage) === 0
        && !namedList.some((item) => String(item.id) === collectId), dataOf(namedPage));

      const moveBack = await http('PUT', '/collects/move', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { collectIds: [collectId], folderId },
      });
      check('收藏可再次移回收纳夹', codeOf(moveBack) === 0 && Number(moveBack.json.data) === 1);

      const namedPageAgain = await http('GET', `/collects?folderId=${folderId}&page=1&size=10`, {
        token: users.A.token, xff: syntheticIp(),
      });
      const namedListAgain = dataOf(namedPageAgain)?.list || [];
      check('移回收纳夹后按收藏夹可查到该收藏',
        codeOf(namedPageAgain) === 0 && namedListAgain.length > 0
        && namedListAgain.some((item) => String(item.id) === collectId), dataOf(namedPageAgain));

      const privateCollect = await http('POST', '/collects', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { targetType: 'PLAYLIST', targetId: PRIVATE_PLAYLIST_ID },
      });
      check('私密歌单不可收藏（50022）', codeOf(privateCollect) === 50022, codeOf(privateCollect));

      const delFolder = await http('DELETE', `/collect-folders/${folderId}`, {
        token: users.A.token, xff: syntheticIp(),
      });
      check('删除收藏夹成功', codeOf(delFolder) === 0, codeOf(delFolder));
      check('删除收藏夹后收藏落回默认收藏夹', HAS_MYSQL
        && mysqlScalar(`SELECT IFNULL(folder_id,'NULL') FROM newmusic.t_collect WHERE id=${q(collectId)}`)
          === 'NULL');
      const foldersAfter = await http('GET', '/collect-folders', {
        token: users.A.token, xff: syntheticIp(),
      });
      check('删除后收藏夹列表只剩默认夹',
        (dataOf(foldersAfter) || []).length === 1
        && (dataOf(foldersAfter) || [])[0].isDefault === true, dataOf(foldersAfter));
      check('默认收藏夹 item_count=1',
        Number((dataOf(foldersAfter) || [])[0]?.itemCount) === 1);

      const uncollect = await http('DELETE', '/collects', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { targetType: 'SONG', targetId: SONG_ID },
      });
      check('取消收藏成功', codeOf(uncollect) === 0, codeOf(uncollect));
      check('取消后歌曲收藏计数恢复', HAS_MYSQL
        && mysqlCount(`SELECT collect_count FROM newmusic.t_song WHERE id=${SONG_ID}`) === baseline.song[1]);

      const statusAfter = await http(
        'GET', `/collects/status?targetType=SONG&targetId=${SONG_ID}`,
        { token: users.A.token, xff: syntheticIp() },
      );
      check('取消收藏后状态接口回到 collected=false',
        codeOf(statusAfter) === 0 && statusAfter.json.data.collected === false,
        statusAfter.json?.data);
    }

    // ============================================================ §6 关注
    section('§6 关注：用户 / 歌手 / 隐私可见性');

    const followAB = await http('POST', '/follows', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetUserId: users.B.id },
    });
    check('A 关注 B 成功', codeOf(followAB) === 0 && followAB.json.data.followed === true
      && followAB.json.data.mutual === false, followAB.json?.data);
    check('关注关系落库', HAS_MYSQL && mysqlCount(
      `SELECT COUNT(*) FROM newmusic.t_follow WHERE follower_id=${q(users.A.id)} `
      + `AND following_id=${q(users.B.id)}`) === 1);

    const selfFollow = await http('POST', '/follows', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetUserId: users.A.id },
    });
    check('不能关注自己（50004）', codeOf(selfFollow) === 50004, codeOf(selfFollow));

    const followingA = await http('GET', '/follows/following?page=1&size=20', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('A 的关注列表包含 B（对照集合非空）',
      (dataOf(followingA)?.list || []).some((x) => String(x.id) === users.B.id), dataOf(followingA)?.total);

    const followersB = await http('GET', `/follows/followers?userId=${users.B.id}&page=1&size=20`, {
      token: users.A.token, xff: syntheticIp(),
    });
    check('B 的粉丝列表包含 A',
      (dataOf(followersB)?.list || []).some((x) => String(x.id) === users.A.id), dataOf(followersB)?.total);

    const followArtist = await http('POST', `/artist-follows/${ARTIST_ID}`, {
      token: users.A.token, xff: syntheticIp(),
    });
    check('A 关注歌手成功', codeOf(followArtist) === 0 && followArtist.json.data.followed === true,
      followArtist.json?.data);
    check('歌手粉丝数 +1', HAS_MYSQL
      && mysqlCount(`SELECT follower_count FROM newmusic.t_artist WHERE id=${ARTIST_ID}`)
        === baseline.artistFollower + 1);
    const artistStatus = await http('GET', `/artist-follows/${ARTIST_ID}`, {
      token: users.A.token, xff: syntheticIp(),
    });
    check('歌手关注状态查询为已关注', codeOf(artistStatus) === 0
      && artistStatus.json.data.followed === true);

    // B 作为访客查看 A 的关注列表：默认可见 → 关闭后为空 → 恢复
    const visibleBefore = await http('GET', `/follows/following?userId=${users.A.id}&page=1&size=20`, {
      token: users.B.token, xff: syntheticIp(),
    });
    check('隐私默认允许他人查看关注列表（对照集合非空）',
      (dataOf(visibleBefore)?.list || []).some((x) => String(x.id) === users.B.id),
      dataOf(visibleBefore)?.total);

    const hideFollow = await http('PUT', '/privacy-settings', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { showFollowing: 0 },
    });
    check('A 关闭关注列表可见性', codeOf(hideFollow) === 0
      && Number(hideFollow.json.data.showFollowing) === 0);
    const hidden = await http('GET', `/follows/following?userId=${users.A.id}&page=1&size=20`, {
      token: users.B.token, xff: syntheticIp(),
    });
    check('关闭后他人看不到关注列表', codeOf(hidden) === 0
      && (dataOf(hidden)?.list || []).length === 0);
    const selfStillVisible = await http('GET', `/follows/following?page=1&size=20`, {
      token: users.A.token, xff: syntheticIp(),
    });
    check('本人仍可查看自己的关注列表',
      (dataOf(selfStillVisible)?.list || []).some((x) => String(x.id) === users.B.id));
    await http('PUT', '/privacy-settings', {
      token: users.A.token, xff: syntheticIp(), body: { showFollowing: 1 },
    });

    // ============================================================ §6.5 歌单评论权限（demo）
    section('§6.5 隐私设置：歌单评论权限（commentPolicy）');

    const demoPlaylistRes = await http('POST', '/music/playlists', {
      token: demo.token,
      xff: syntheticIp(),
      body: { name: `验收权限歌单-${RUN_TAG}`, isPublic: 1 },
    });
    if (codeOf(demoPlaylistRes) === 0 && demoPlaylistRes.json.data) {
      demoPlaylistId = String(demoPlaylistRes.json.data);
      baseline.demoPlaylist = counterRow('t_playlist', demoPlaylistId);
    }
    check('demo 创建公开歌单成功（评论权限测试载体）', codeOf(demoPlaylistRes) === 0
      && demoPlaylistId, demoPlaylistRes.json);

    const demoComment2 = await http('PUT', '/privacy-settings', {
      token: demo.token,
      xff: syntheticIp(),
      body: { commentPolicy: 2 },
    });
    check('demo 关闭歌单评论', codeOf(demoComment2) === 0
      && Number(demoComment2.json.data.commentPolicy) === 2);
    const blockedComment = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'PLAYLIST', targetId: demoPlaylistId, content: `权限评论-${RUN_TAG}` },
    });
    check('关闭评论后他人不能评论（50029）', codeOf(blockedComment) === 50029, codeOf(blockedComment));

    const demoComment1 = await http('PUT', '/privacy-settings', {
      token: demo.token,
      xff: syntheticIp(),
      body: { commentPolicy: 1 },
    });
    check('demo 改为仅关注者可评论', codeOf(demoComment1) === 0
      && Number(demoComment1.json.data.commentPolicy) === 1);
    const notFollower = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'PLAYLIST', targetId: demoPlaylistId, content: `权限评论2-${RUN_TAG}` },
    });
    check('未关注歌单主时不能评论（50029）', codeOf(notFollower) === 50029, codeOf(notFollower));

    const followDemo = await http('POST', '/follows', {
      token: users.A.token, xff: syntheticIp(), body: { targetUserId: '1000000000000002003' },
    });
    check('A 关注 demo 成功（歌单评论权限前置）', codeOf(followDemo) === 0, codeOf(followDemo));
    const allowedComment = await http('POST', '/comments', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'PLAYLIST', targetId: demoPlaylistId, content: `权限评论3-${RUN_TAG}` },
    });
    const demoCommentId = allowedComment.json?.data ? String(allowedComment.json.data.id) : null;
    if (demoCommentId) created.commentIds.push(demoCommentId);
    check('关注后可在歌单下评论', codeOf(allowedComment) === 0 && demoCommentId,
      codeOf(allowedComment));
    await http('DELETE', `/follows/1000000000000002003`, {
      token: users.A.token, xff: syntheticIp(),
    });

    // 还原 demo 的隐私设置（它是共享种子账号，测试后必须回到原样）
    if (demoPrivacyExisted) {
      const [mp, cp, menp, sf, sfo, phv, rr, nsn, sn] = demoPrivacy.split('\t');
      const num = (v) => (v === undefined || v === 'NULL' || v === '' ? 'NULL' : Number(v));
      mysql(`UPDATE newmusic.t_user_privacy_setting SET message_policy=${num(mp)},`
        + `comment_policy=${num(cp)},mention_policy=${num(menp)},show_following=${num(sf)},`
        + `show_followers=${num(sfo)},play_history_visibility=${num(phv)},`
        + `read_receipt=${num(rr)},new_song_notify=${num(nsn)},system_notify=${num(sn)} `
        + `WHERE user_id=1000000000000002003`);
    } else {
      mysql('DELETE FROM newmusic.t_user_privacy_setting WHERE user_id=1000000000000002003');
    }
    const demoRestored = await http('GET', '/privacy-settings', {
      token: demo.token, xff: syntheticIp(),
    });
    const expectedCommentPolicy = demoPrivacyExisted
      ? Number(demoPrivacy.split('\t')[1]) : 0;
    check('demo 隐私设置已恢复测试前取值', codeOf(demoRestored) === 0
      && Number(demoRestored.json.data.commentPolicy) === expectedCommentPolicy,
    { expected: expectedCommentPolicy, actual: demoRestored.json?.data?.commentPolicy });

    // ============================================================ §7 黑名单
    section('§7 黑名单：自动解除双向关注 / 双向内容过滤 / 互动拦截');

    const followBA = await http('POST', '/follows', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetUserId: users.A.id },
    });
    check('B 回关 A，形成互关', codeOf(followBA) === 0 && followBA.json.data.mutual === true);

    const block = await http('POST', '/blacklist', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetUserId: users.B.id },
    });
    check('A 拉黑 B 成功', codeOf(block) === 0, codeOf(block));
    check('拉黑后双向关注关系被清除', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_follow WHERE `
        + `(follower_id=${q(users.A.id)} AND following_id=${q(users.B.id)}) OR `
        + `(follower_id=${q(users.B.id)} AND following_id=${q(users.A.id)})`) === 0);

    const blockedFollow = await http('POST', '/follows', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetUserId: users.A.id },
    });
    check('被拉黑后不能关注（50005）', codeOf(blockedFollow) === 50005, codeOf(blockedFollow));

    const blockedMessage = await http('POST', '/messages', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetUserId: users.A.id, msgType: 'TEXT', content: `被拦私信-${RUN_TAG}` },
    });
    check('被拉黑后不能私信（50013）', codeOf(blockedMessage) === 50013, codeOf(blockedMessage));

    if (c1 && r1) {
      const repliesAsA = await http('GET', `/comments/${c1}/replies?page=1&size=20`, {
        token: users.A.token, xff: syntheticIp(),
      });
      check('拉黑后 A 看不到 B 的回复（对照：拉黑前可见）',
        !(dataOf(repliesAsA)?.list || []).some((x) => String(x.id) === r1),
        dataOf(repliesAsA)?.total);
      const listAsB = await http('GET', `/comments?targetType=SONG&targetId=${SONG_ID}&size=50`, {
        token: users.B.token, xff: syntheticIp(),
      });
      check('拉黑后 B 看不到 A 的评论',
        !(dataOf(listAsB)?.list || []).some((x) => String(x.id) === c1 || String(x.id) === c2),
        dataOf(listAsB)?.total);
    }

    const unblock = await http('DELETE', `/blacklist/${users.B.id}`, {
      token: users.A.token, xff: syntheticIp(),
    });
    check('解除拉黑成功', codeOf(unblock) === 0, codeOf(unblock));
    check('解除拉黑后黑名单为空', (dataOf(await http('GET', '/blacklist?page=1&size=20', {
      token: users.A.token, xff: syntheticIp(),
    }))?.list || []).length === 0);

    // 私信需要一个非互关起点：只让 A 关注 B
    const followAgain = await http('POST', '/follows', {
      token: users.A.token, xff: syntheticIp(), body: { targetUserId: users.B.id },
    });
    check('拉黑解除后 A 可重新关注 B（非互关状态）', codeOf(followAgain) === 0
      && followAgain.json.data.mutual === false, followAgain.json?.data);

    // ============================================================ §8 私信
    section('§8 私信：非互关限额 / 频率限制 / 未读 / 已读 / 撤回 / 删除');

    try {
      wsA = await connectWs(users.A.token, `m10a-${RUN_TAG}`);
      wsB = await connectWs(users.B.token, `m10b-${RUN_TAG}`);
      await wsA.ready();
      await wsB.ready();
      check('A/B 的 WebSocket 均已连接', !wsA.closed && !wsB.closed);
    } catch (e) {
      skip('私信 WebSocket 实时帧', e.message);
      wsA = wsB = null;
    }

    const sent = [];
    const sendMsg = async (from, to, content, type = 'TEXT') => {
      const res = await http('POST', '/messages', {
        token: from.token,
        xff: syntheticIp(),
        body: { targetUserId: to.id, msgType: type, content },
      });
      if (codeOf(res) === 0 && res.json.data) sent.push(String(res.json.data.id));
      return res;
    };

    const m1 = await sendMsg(users.A, users.B, `<b>hello</b>m1-${RUN_TAG}`);
    check('非互关第 1 条私信发送成功且 HTML 被清洗', codeOf(m1) === 0
      && m1.json.data.content === `hellom1-${RUN_TAG}`, m1.json?.data?.content);
    await sendMsg(users.A, users.B, `m2-${RUN_TAG}`);
    await sendMsg(users.A, users.B, `m3-${RUN_TAG}`);
    const m4 = await sendMsg(users.A, users.B, `m4-${RUN_TAG}`);
    check('非互关第 4 条私信被拒（50015）', codeOf(m4) === 50015, codeOf(m4));
    check('私信落库且会话 unread_b=3', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_private_message WHERE sender_id=${q(users.A.id)} `
        + `AND receiver_id=${q(users.B.id)}`) === 3
      && mysqlScalar(`SELECT CONCAT(unread_a,':',unread_b) FROM newmusic.t_conversation WHERE `
        + `user_a_id=LEAST(${q(users.A.id)},${q(users.B.id)}) AND `
        + `user_b_id=GREATEST(${q(users.A.id)},${q(users.B.id)})`) === '0:3');
    check('B 的未读数接口返回 3', (await http('GET', '/messages/unread-count', {
      token: users.B.token, xff: syntheticIp(),
    })).json?.data === 3);

    if (wsB) {
      try {
        const frame = await wsB.wait((m) => m.type === 'msg.new'
          && String(m.data?.content || '').includes(`hellom1-${RUN_TAG}`), 5000);
        check('B 收到 msg.new 实时帧', Boolean(frame));
      } catch (e) {
        check('B 收到 msg.new 实时帧', false, e.message);
      }
    }

    check('首条私信为 B 生成一条 MESSAGE 通知', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.B.id)} `
        + `AND notify_type=5`) === 1,
    HAS_MYSQL ? mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.B.id)} AND notify_type=5`) : 'no-mysql');

    const mutual = await http('POST', '/follows', {
      token: users.B.token, xff: syntheticIp(), body: { targetUserId: users.A.id },
    });
    check('B 回关 A 后成为互关', codeOf(mutual) === 0 && mutual.json.data.mutual === true);

    for (let i = 5; i <= 20; i += 1) {
      const r = await sendMsg(users.A, users.B, `m${i}-${RUN_TAG}`);
      if (codeOf(r) !== 0) {
        check(`第 ${i} 条互关私信发送成功`, false, codeOf(r));
        break;
      }
    }
    check('互关后累计 19 条私信成功', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_private_message WHERE sender_id=${q(users.A.id)} `
        + `AND receiver_id=${q(users.B.id)} AND is_revoked=0`) === 19);
    check('B 的未读数为 19', (await http('GET', '/messages/unread-count', {
      token: users.B.token, xff: syntheticIp(),
    })).json?.data === 19);

    const m21 = await sendMsg(users.A, users.B, `m21-${RUN_TAG}`);
    check('每分钟第 21 条被拒（50016）', codeOf(m21) === 50016, codeOf(m21));

    // 清掉本轮 A 的私信频率窗口，继续验证已读/撤回（键里带本轮用户 ID，不会误伤）
    if (REDIS_OK) {
      delByPattern(`ml:social:msg:rate:${users.A.id}:*`);
    }

    const convList = await http('GET', '/messages/conversations?page=1&size=20', {
      token: users.B.token, xff: syntheticIp(),
    });
    const conversation = (dataOf(convList)?.list || []).find((c) => String(c.otherUserId) === users.A.id);
    const conversationId = conversation ? String(conversation.id) : null;
    check('会话列表包含 A 且未读 19、预览为最后一条内容',
      Boolean(conversation) && Number(conversation.unread) === 19
      && String(conversation.lastMessagePreview || '').includes(`m20-${RUN_TAG}`),
      conversation);

    if (conversationId) {
      check('会话表 user_a_id < user_b_id 去重', HAS_MYSQL
        && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_conversation WHERE `
          + `user_a_id=LEAST(${q(users.A.id)},${q(users.B.id)}) AND `
          + `user_b_id=GREATEST(${q(users.A.id)},${q(users.B.id)})`) === 1);

      const page5 = await http('GET',
        `/messages?conversationId=${conversationId}&limit=5`, {
        token: users.B.token, xff: syntheticIp(),
      });
      const list5 = dataOf(page5) || [];
      check('消息游标返回最新 5 条且按时间正序',
        list5.length === 5
        && list5.every((m, i) => i === 0 || Number(m.id) > Number(list5[i - 1].id)), list5.map((m) => m.id));

      const beforeId = list5.length ? String(list5[0].id) : null;
      if (beforeId) {
        const older = await http('GET',
          `/messages?conversationId=${conversationId}&beforeId=${beforeId}&limit=5`, {
          token: users.B.token, xff: syntheticIp(),
        });
        const olderList = dataOf(older) || [];
        check('beforeId 游标返回更早的消息',
          olderList.length === 5 && olderList.every((m) => Number(m.id) < Number(beforeId)),
          olderList.map((m) => m.id));
      }

      const read = await http('PUT', '/messages/read', {
        token: users.B.token,
        xff: syntheticIp(),
        body: { conversationId },
      });
      check('B 标记会话已读返回 19 条', codeOf(read) === 0 && Number(read.json.data) === 19,
        read.json?.data);
      check('已读后未读数归零', (await http('GET', '/messages/unread-count', {
        token: users.B.token, xff: syntheticIp(),
      })).json?.data === 0);
      if (wsA) {
        try {
          const frame = await wsA.wait((m) => m.type === 'msg.read'
            && String(m.data?.conversationId) === conversationId, 5000);
          check('A 收到 msg.read 已读回执', Boolean(frame));
        } catch (e) {
          check('A 收到 msg.read 已读回执', false, e.message);
        }
      }

      // 删除仅对自己隐藏：B 删除一条未读私信，A 仍能看到
      const m22 = await sendMsg(users.A, users.B, `m22-${RUN_TAG}`);
      const m22Id = m22.json?.data ? String(m22.json.data.id) : null;
      if (m22Id) {
        const del = await http('DELETE', `/messages/${m22Id}`, {
          token: users.B.token, xff: syntheticIp(),
        });
        check('B 删除私信成功（仅自己隐藏）', codeOf(del) === 0, codeOf(del));
        check('删除方消息列表不再返回该条',
          !(dataOf(await http('GET', `/messages?conversationId=${conversationId}&limit=50`, {
            token: users.B.token, xff: syntheticIp(),
          })) || []).some((m) => String(m.id) === m22Id));
        check('对方消息列表仍返回该条',
          (dataOf(await http('GET', `/messages?conversationId=${conversationId}&limit=50`, {
            token: users.A.token, xff: syntheticIp(),
          })) || []).some((m) => String(m.id) === m22Id));
        check('删除未读私信后未读数减 1', (await http('GET', '/messages/unread-count', {
          token: users.B.token, xff: syntheticIp(),
        })).json?.data === 0);
      }

      // 撤回：发送者 2 分钟内可撤，双方收到 msg.revoke
      const m23 = await sendMsg(users.A, users.B, `m23-${RUN_TAG}`);
      const m23Id = m23.json?.data ? String(m23.json.data.id) : null;
      if (m23Id) {
        const revoke = await http('POST', `/messages/${m23Id}/revoke`, {
          token: users.A.token, xff: syntheticIp(),
        });
        check('发送者在 2 分钟内撤回成功', codeOf(revoke) === 0, codeOf(revoke));
        check('撤回落库 is_revoked=1', HAS_MYSQL
          && mysqlScalar(`SELECT is_revoked FROM newmusic.t_private_message WHERE id=${q(m23Id)}`) === '1');
        const revokedList = dataOf(await http('GET',
          `/messages?conversationId=${conversationId}&limit=50`, {
          token: users.B.token, xff: syntheticIp(),
        })) || [];
        const revoked = revokedList.find((m) => String(m.id) === m23Id);
        check('撤回后消息内容替换为「消息已撤回」', revoked
          && revoked.isRevoked === true && revoked.content === '消息已撤回', revoked);
        if (wsB) {
          try {
            const frame = await wsB.wait((m) => m.type === 'msg.revoke'
              && String(m.data?.messageId) === m23Id, 5000);
            check('B 收到 msg.revoke 实时帧', Boolean(frame));
          } catch (e) {
            check('B 收到 msg.revoke 实时帧', false, e.message);
          }
        }
        const revokeByReceiver = await http('POST', `/messages/${m23Id}/revoke`, {
          token: users.B.token, xff: syntheticIp(),
        });
        check('接收者不能撤回（50025）', codeOf(revokeByReceiver) === 50025, codeOf(revokeByReceiver));
      }

      // 超过 120 秒：按精确 ID 回拨创建时间
      const m24 = await sendMsg(users.A, users.B, `m24-${RUN_TAG}`);
      const m24Id = m24.json?.data ? String(m24.json.data.id) : null;
      if (m24Id) {
        mysql(`UPDATE newmusic.t_private_message SET create_time=DATE_SUB(NOW(3), INTERVAL 3 MINUTE) `
          + `WHERE id=${q(m24Id)}`);
        const expired = await http('POST', `/messages/${m24Id}/revoke`, {
          token: users.A.token, xff: syntheticIp(),
        });
        check('超过 2 分钟撤回被拒（50007）', codeOf(expired) === 50007, codeOf(expired));
      }

      // 隐私设置：接收者关闭私信
      const closeMsg = await http('PUT', '/privacy-settings', {
        token: users.B.token, xff: syntheticIp(), body: { messagePolicy: 2 },
      });
      check('B 关闭私信（messagePolicy=2）', codeOf(closeMsg) === 0
        && Number(closeMsg.json.data.messagePolicy) === 2);
      const denied = await sendMsg(users.A, users.B, `m25-${RUN_TAG}`);
      check('对方关闭私信后发送被拒（50014）', codeOf(denied) === 50014, codeOf(denied));
      await http('PUT', '/privacy-settings', {
        token: users.B.token, xff: syntheticIp(), body: { messagePolicy: 0 },
      });

      const selfMsg = await http('POST', '/messages', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { targetUserId: users.A.id, msgType: 'TEXT', content: 'self' },
      });
      check('不能给自己发私信（50004）', codeOf(selfMsg) === 50004, codeOf(selfMsg));
    }

    // ============================================================ §9 通知中心
    section('§9 通知中心：分类 / 已读 / 删除 / 清空 / 分类开关');

    const allA = await http('GET', '/notifications?tab=all&page=1&size=50', {
      token: users.A.token, xff: syntheticIp(),
    });
    const allAList = dataOf(allA)?.list || [];
    check('A 的通知列表含点赞/评论/关注三类', codeOf(allA) === 0
      && allAList.some((n) => Number(n.notifyType) === 3)
      && allAList.some((n) => Number(n.notifyType) === 2)
      && allAList.some((n) => Number(n.notifyType) === 4),
    allAList.map((n) => n.notifyType));

    const interaction = await http('GET', '/notifications?tab=interaction&page=1&size=50', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('互动 tab 只含点赞/评论/提及',
      (dataOf(interaction)?.list || []).every((n) => [2, 3, 6].includes(Number(n.notifyType)))
      && (dataOf(interaction)?.list || []).length > 0, dataOf(interaction)?.total);

    const followTab = await http('GET', '/notifications?tab=follow&page=1&size=50', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('关注 tab 含关注通知',
      (dataOf(followTab)?.list || []).some((n) => Number(n.notifyType) === 4), dataOf(followTab)?.total);

    const unreadBefore = (await http('GET', '/notifications/unread-count', {
      token: users.A.token, xff: syntheticIp(),
    })).json?.data;
    check('A 的通知未读数大于 0', Number(unreadBefore) > 0, unreadBefore);

    const oneToRead = allAList.find((n) => Number(n.notifyType) === 3);
    if (oneToRead) {
      const mark = await http('PUT', '/notifications/read', {
        token: users.A.token,
        xff: syntheticIp(),
        body: { ids: [String(oneToRead.id)] },
      });
      check('批量已读接口返回更新条数', codeOf(mark) === 0 && Number(mark.json.data) >= 1,
        mark.json?.data);
      check('已读状态落库', HAS_MYSQL
        && mysqlScalar(`SELECT is_read FROM newmusic.t_notification WHERE id=${q(oneToRead.id)}`) === '1');
    }

    const oneToDelete = allAList.find((n) => Number(n.notifyType) === 2);
    if (oneToDelete) {
      const del = await http('DELETE', `/notifications/${oneToDelete.id}`, {
        token: users.A.token, xff: syntheticIp(),
      });
      check('删除单条通知成功', codeOf(del) === 0, codeOf(del));
      check('删除后数据库不再有该通知', HAS_MYSQL
        && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE id=${q(oneToDelete.id)}`) === 0);
    }

    const readAll = await http('PUT', '/notifications/read-all', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('全部已读接口返回条数', codeOf(readAll) === 0 && Number(readAll.json.data) >= 0,
      readAll.json?.data);
    check('全部已读后未读数为 0', (await http('GET', '/notifications/unread-count', {
      token: users.A.token, xff: syntheticIp(),
    })).json?.data === 0);

    const clearA = await http('DELETE', '/notifications', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('清空通知成功', codeOf(clearA) === 0, codeOf(clearA));
    check('清空后 A 的通知列表为空', (dataOf(await http('GET',
      '/notifications?tab=all&page=1&size=20', {
        token: users.A.token, xff: syntheticIp(),
      }))?.list || []).length === 0);
    const clearB = await http('DELETE', '/notifications', {
      token: users.B.token, xff: syntheticIp(),
    });
    check('清空 B 的通知成功', codeOf(clearB) === 0, codeOf(clearB));

    const setting0 = await http('GET', '/notifications/settings', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('通知设置默认全部开启', codeOf(setting0) === 0
      && Number(setting0.json.data.likeEnabled) === 1
      && Number(setting0.json.data.commentEnabled) === 1, setting0.json?.data);

    const disableLike = await http('PUT', '/notifications/settings', {
      token: users.A.token, xff: syntheticIp(), body: { likeEnabled: 0 },
    });
    check('A 关闭点赞通知', codeOf(disableLike) === 0
      && Number(disableLike.json.data.likeEnabled) === 0);
    check('通知开关落库', HAS_MYSQL && mysqlScalar(
      `SELECT like_enabled FROM newmusic.t_notification_setting WHERE user_id=${q(users.A.id)}`) === '0');

    const likeC2 = await http('POST', '/likes', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: c2 },
    });
    check('B 点赞 C2 成功（用于验证通知抑制）', codeOf(likeC2) === 0, codeOf(likeC2));
    check('关闭点赞通知后不落 LIKE 通知', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.A.id)} `
        + `AND notify_type=3`) === 0);

    await http('PUT', '/notifications/settings', {
      token: users.A.token, xff: syntheticIp(), body: { likeEnabled: 1 },
    });
    await http('DELETE', '/likes', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: c2 },
    });
    const likeC2Again = await http('POST', '/likes', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: c2 },
    });
    check('重新点赞 C2 成功', codeOf(likeC2Again) === 0);
    check('恢复点赞通知后重新收到 LIKE 通知', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.A.id)} `
        + `AND notify_type=3`) === 1);

    const disableMsg = await http('PUT', '/notifications/settings', {
      token: users.B.token, xff: syntheticIp(), body: { messageEnabled: 0 },
    });
    check('B 关闭私信通知', codeOf(disableMsg) === 0
      && Number(disableMsg.json.data.messageEnabled) === 0);
    // 先把 B 当前未读清零，确保下一条私信会触发「首条通知」判定
    if (conversationId) {
      await http('PUT', '/messages/read', {
        token: users.B.token, xff: syntheticIp(), body: { conversationId },
      });
    }
    const msgCountBefore = HAS_MYSQL
      ? mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.B.id)} `
        + `AND notify_type=5`) : -1;
    const m26 = await sendMsg(users.A, users.B, `m26-${RUN_TAG}`);
    check('关闭私信通知后仍能收到私信', codeOf(m26) === 0, codeOf(m26));
    check('关闭私信通知后不落 MESSAGE 通知', HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.B.id)} `
        + `AND notify_type=5`) === msgCountBefore);

    const m26Id = m26.json?.data ? String(m26.json.data.id) : null;
    if (m26Id) await http('DELETE', `/messages/${m26Id}`, { token: users.B.token, xff: syntheticIp() });
    await http('PUT', '/notifications/settings', {
      token: users.B.token, xff: syntheticIp(), body: { messageEnabled: 1 },
    });
    const m27 = await sendMsg(users.A, users.B, `m27-${RUN_TAG}`);
    check('恢复私信通知后重新收到 MESSAGE 通知', codeOf(m27) === 0 && HAS_MYSQL
      && mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.B.id)} `
        + `AND notify_type=5`) === msgCountBefore + 1);

    await http('PUT', '/notifications/settings', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { likeEnabled: 1, commentEnabled: 1, followEnabled: 1, messageEnabled: 1, systemEnabled: 1, newSongEnabled: 1, readReceiptEnabled: 1 },
    });
    await http('PUT', '/notifications/settings', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { likeEnabled: 1, commentEnabled: 1, followEnabled: 1, messageEnabled: 1, systemEnabled: 1, newSongEnabled: 1, readReceiptEnabled: 1 },
    });

    // ============================================================ §10 举报
    section('§10 举报：理由 / 去重 / 合并 / 自举报 / 优先级 / 证据');

    const reportB = await http('POST', '/reports', {
      token: users.B.token,
      xff: syntheticIp(),
      body: {
        targetType: 'COMMENT',
        targetId: c2,
        reasonType: 'OBSCENE',
        reason: `B 举报 C2-${RUN_TAG}`,
      },
    });
    const reportBId = reportB.json?.data ? String(reportB.json.data.id) : null;
    if (reportBId) created.reportIds.push(reportBId);
    check('B 举报 A 的评论成功且为待处理', codeOf(reportB) === 0 && reportBId
      && Number(reportB.json.data.status) === 0, reportB.json?.data);
    check('普通举报优先级为 2', HAS_MYSQL
      && mysqlScalar(`SELECT priority FROM newmusic.t_report WHERE id=${q(reportBId)}`) === '2');

    const reportDemo = await http('POST', '/reports', {
      token: demo.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: c2, reasonType: 'OBSCENE', reason: 'demo 举报' },
    });
    const reportDemoId = reportDemo.json?.data ? String(reportDemo.json.data.id) : null;
    if (reportDemoId) created.reportIds.push(reportDemoId);
    check('相同目标+理由的第二起举报被合并',
      codeOf(reportDemo) === 0 && reportDemoId
      && Number(reportDemo.json.data.status) === 4
      && String(reportDemo.json.data.mergedIntoId) === reportBId, reportDemo.json?.data);

    const selfReport = await http('POST', '/reports', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: c2, reasonType: 'OBSCENE' },
    });
    check('不能举报自己的内容（50017）', codeOf(selfReport) === 50017, codeOf(selfReport));

    const reportA = await http('POST', '/reports', {
      token: users.A.token,
      xff: syntheticIp(),
      body: {
        targetType: 'COMMENT',
        targetId: r1,
        reasonType: 'POLITICS',
        reason: `A 举报 R1-${RUN_TAG}`,
        evidenceUrls: ['http://example.com/e1.png', 'http://example.com/e2.png'],
      },
    });
    const reportAId = reportA.json?.data ? String(reportA.json.data.id) : null;
    if (reportAId) created.reportIds.push(reportAId);
    check('A 举报 B 的回复成功', codeOf(reportA) === 0 && reportAId, reportA.json?.data);
    check('高风险理由优先级为 1', HAS_MYSQL
      && mysqlScalar(`SELECT priority FROM newmusic.t_report WHERE id=${q(reportAId)}`) === '1');
    if (HAS_MYSQL && reportAId) {
      let evidence = [];
      try {
        evidence = JSON.parse(mysqlScalar(
          `SELECT evidence_urls FROM newmusic.t_report WHERE id=${q(reportAId)}`));
      } catch {
        /* ignore */
      }
      check('举报证据按 JSON 数组落库', Array.isArray(evidence) && evidence.length === 2
        && evidence[0] === 'http://example.com/e1.png', evidence);
    }

    const duplicateReport = await http('POST', '/reports', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: r1, reasonType: 'POLITICS' },
    });
    check('同人同目标同理由 24h 内重复举报被拒（50008）',
      codeOf(duplicateReport) === 50008, codeOf(duplicateReport));

    const missingTarget = await http('POST', '/reports', {
      token: users.A.token,
      xff: syntheticIp(),
      body: { targetType: 'COMMENT', targetId: '9999999999999999999', reasonType: 'OTHER' },
    });
    check('举报不存在的目标被拒（50019）', codeOf(missingTarget) === 50019, codeOf(missingTarget));

    const myReports = await http('GET', '/reports/my?page=1&size=20', {
      token: users.A.token, xff: syntheticIp(),
    });
    check('我的举报记录包含刚提交的那条（对照集合非空）',
      (dataOf(myReports)?.list || []).some((r) => String(r.id) === reportAId)
      && (dataOf(myReports)?.list || []).length >= 1, dataOf(myReports)?.total);

    // ============================================================ §11 分享
    section('§11 分享：游客/登录建链 / 302 归因 / 点击与 UV / 过期');

    const shareAnon = await http('POST', '/share', {
      xff: syntheticIp(),
      body: { targetType: 'SONG', targetId: SONG_ID, channel: 'COPY_LINK' },
    });
    const shareAnonData = dataOf(shareAnon);
    const anonCode = shareAnonData ? String(shareAnonData.code) : null;
    if (anonCode) created.shareCodes.push(anonCode);
    check('游客生成短链成功且短码为 6 位 Base62', codeOf(shareAnon) === 0 && anonCode
      && /^[2-9A-HJ-NP-Za-km-z]{6}$/.test(anonCode), anonCode);
    check('短链返回可跳转地址与归因目标地址', Boolean(shareAnonData)
      && String(shareAnonData.shortUrl).endsWith(`/s/${anonCode}`)
      && String(shareAnonData.targetUrl).includes(`/song/${SONG_ID}?from=share`),
    shareAnonData);
    check('短链落库且游客分享 sharer_id 为 NULL', HAS_MYSQL && anonCode
      && mysqlScalar(`SELECT IFNULL(sharer_id,'NULL') FROM newmusic.t_share WHERE code=${utf8Hex(anonCode)}`)
        === 'NULL');
    if (REDIS_OK && anonCode) {
      check('短链写入 Redis 映射', redis('exists', `ml:social:short:${anonCode}`) === '1');
    }

    if (anonCode) {
      const ip1 = syntheticIp();
      const redirect1 = await rawGet(`${ROOT_BASE}/s/${anonCode}`, { redirect: 'manual', xff: ip1 });
      const location = redirect1.headers.get('location') || '';
      check('短链 302 到 C 端歌曲页', redirect1.status === 302
        && location.includes(`/song/${SONG_ID}?from=share`), { status: redirect1.status, location });
      const redirect2 = await rawGet(`${ROOT_BASE}/s/${anonCode}`, { redirect: 'manual', xff: ip1 });
      check('同一访客再次点击仍 302', redirect2.status === 302);
      const redirect3 = await rawGet(`${ROOT_BASE}/s/${anonCode}`, {
        redirect: 'manual', xff: syntheticIp(),
      });
      check('不同访客点击仍 302', redirect3.status === 302);
      check('点击次数落库为 3', HAS_MYSQL
        && mysqlScalar(`SELECT click_count FROM newmusic.t_share WHERE code=${utf8Hex(anonCode)}`) === '3');
      if (REDIS_OK) {
        check('Redis 点击计数为 3',
          redis('get', `ml:social:short:click:${anonCode}`) === '3');
        check('Redis UV 集合为 2', redis('scard', `ml:social:short:visitor:${anonCode}`) === '2');
      }
      check('歌曲分享计数 +1', HAS_MYSQL
        && mysqlCount(`SELECT share_count FROM newmusic.t_song WHERE id=${SONG_ID}`)
          === baseline.song[3] + 1);
    }

    const shareLogin = await http('POST', '/share', {
      token: users.B.token,
      xff: syntheticIp(),
      body: { targetType: 'PLAYLIST', targetId: PLAYLIST_ID, channel: 'IN_APP' },
    });
    const shareLoginData = dataOf(shareLogin);
    const loginCode = shareLoginData ? String(shareLoginData.code) : null;
    if (loginCode) created.shareCodes.push(loginCode);
    check('登录用户生成短链并带 sharerId 归因', codeOf(shareLogin) === 0 && loginCode
      && String(shareLoginData.targetUrl).includes(`sharerId=${users.B.id}`), shareLoginData);
    check('登录分享人落库', HAS_MYSQL && loginCode
      && mysqlScalar(`SELECT sharer_id FROM newmusic.t_share WHERE code=${utf8Hex(loginCode)}`)
        === users.B.id);

    const shareExpired = await http('POST', '/share', {
      xff: syntheticIp(),
      body: { targetType: 'ALBUM', targetId: ALBUM_ID, channel: 'OTHER' },
    });
    const expiredCode = dataOf(shareExpired)?.code ? String(dataOf(shareExpired).code) : null;
    if (expiredCode) {
      created.shareCodes.push(expiredCode);
      mysql(`UPDATE newmusic.t_share SET expire_time=DATE_SUB(NOW(3), INTERVAL 1 DAY) `
        + `WHERE code=${utf8Hex(expiredCode)}`);
      // 缓存里还留着未过期的原 TTL，先清掉让解析走库，才能验证过期分支
      if (REDIS_OK) redisDel(`ml:social:short:${expiredCode}`);
      const expired = await rawGet(`${ROOT_BASE}/s/${expiredCode}`, {
        redirect: 'manual', xff: syntheticIp(),
      });
      check('过期短链返回 404', expired.status === 404, expired.status);
      if (REDIS_OK) {
        check('过期短链的 Redis 映射被删除',
          redis('exists', `ml:social:short:${expiredCode}`) === '0');
      }
    }
    const invalid = await rawGet(`${ROOT_BASE}/s/ZZZZZZ`, { redirect: 'manual', xff: syntheticIp() });
    check('不存在的短码返回 404', invalid.status === 404, invalid.status);

    // ============================================================ §12 新歌通知
    section('§12 关注歌手发新歌通知（发布后事务提交触发）');

    try {
      const { login: adminLoginJson, cookies } = await adminLogin();
      const accessCookie = (cookies || []).find((c) => c.startsWith('ml_admin_access='));
      const refreshCookie = (cookies || []).find((c) => c.startsWith('ml_admin_refresh='));
      if (refreshCookie) {
        const claims = jwtClaims(refreshCookie.split(';')[0].split('=').slice(1).join('='));
        if (claims && claims.jti) created.adminRefreshJtis.push(claims.jti);
      }
      if (!adminLoginJson || adminLoginJson.code !== 0 || !accessCookie) {
        skip('关注歌手发新歌通知', `后台登录失败（code=${adminLoginJson && adminLoginJson.code}）`);
      } else {
        admin = { cookie: accessCookie.split(';')[0] };

        const ensureFollow = await http('POST', `/artist-follows/${ARTIST_ID}`, {
          token: users.A.token, xff: syntheticIp(),
        });
        check('A 仍关注测试歌手（新歌通知前置）', codeOf(ensureFollow) === 0);
        await http('PUT', '/notifications/settings', {
          token: users.A.token, xff: syntheticIp(), body: { newSongEnabled: 1 },
        });

        const newSongCount = () => (HAS_MYSQL ? mysqlCount(
          `SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id=${q(users.A.id)} `
          + `AND notify_type=10`) : -1);
        const beforeOffline = newSongCount();

        const off1 = await adminHttp('PUT', `/songs/${SONG_ID}/status/0`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        check('后台下架歌曲成功（事务内不发新歌通知）', codeOf(off1) === 0, codeOf(off1));
        await sleep(200);
        check('仅下架不产生新歌通知', newSongCount() === beforeOffline, newSongCount());

        const on1 = await adminHttp('PUT', `/songs/${SONG_ID}/status/1`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        check('后台重新上架歌曲成功', codeOf(on1) === 0, codeOf(on1));
        await sleep(300);
        const afterOnline = newSongCount();
        check('上架后粉丝收到一条新歌通知', afterOnline === beforeOffline + 1,
          { before: beforeOffline, after: afterOnline });
        if (HAS_MYSQL) {
          check('新歌通知指向该歌曲且标题正确', mysqlScalar(
            `SELECT CONCAT(notify_type,':',target_type,':',target_id) FROM newmusic.t_notification `
            + `WHERE user_id=${q(users.A.id)} AND notify_type=10 ORDER BY create_time DESC LIMIT 1`)
            === `10:1:${SONG_ID}`, mysqlScalar(
            `SELECT CONCAT(notify_type,':',target_type,':',target_id) FROM newmusic.t_notification `
            + `WHERE user_id=${q(users.A.id)} AND notify_type=10 ORDER BY create_time DESC LIMIT 1`));
        }

        // 关闭新歌通知后同一次上下架不再落通知
        await http('PUT', '/notifications/settings', {
          token: users.A.token, xff: syntheticIp(), body: { newSongEnabled: 0 },
        });
        const countBeforeSilent = newSongCount();
        await adminHttp('PUT', `/songs/${SONG_ID}/status/0`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        await adminHttp('PUT', `/songs/${SONG_ID}/status/1`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        await sleep(300);
        check('关闭新歌通知后不再落通知', newSongCount() === countBeforeSilent,
          { before: countBeforeSilent, after: newSongCount() });

        // 恢复开启并再发一次，验证设置重新生效；同时确保歌曲最终在线
        await http('PUT', '/notifications/settings', {
          token: users.A.token, xff: syntheticIp(), body: { newSongEnabled: 1 },
        });
        await adminHttp('PUT', `/songs/${SONG_ID}/status/0`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        await adminHttp('PUT', `/songs/${SONG_ID}/status/1`, {
          cookie: admin.cookie, xff: syntheticIp(),
        });
        await sleep(300);
        check('重新开启新歌通知后再次收到通知', newSongCount() === countBeforeSilent + 1,
          { before: countBeforeSilent, after: newSongCount() });
        check('测试歌曲最终保持在线', HAS_MYSQL
          && mysqlScalar(`SELECT status FROM newmusic.t_song WHERE id=${SONG_ID}`) === '1');
      }
    } catch (e) {
      skip('关注歌手发新歌通知', e.message);
    }
  } finally {
    try {
      if (wsA) wsA.close();
      if (wsB) wsB.close();
    } catch {
      /* ignore */
    }
    await cleanup(baseline, demoPrivacyExisted);
  }

  // ================================================================ 汇总

  console.log('\n' + '='.repeat(64));
  console.log(`\x1b[1m模块 10 验收结果：通过 ${pass}，失败 ${fail}，跳过 ${skipped}\x1b[0m`);
  if (skipNotes.length) {
    console.log('\n跳过项：');
    for (const item of skipNotes) console.log(`  - ${item}`);
  }
  if (failures.length) {
    console.log('\n失败项：');
    for (const item of failures) console.log(`  - ${item}`);
  }
  if (fail > 0) process.exitCode = 1;
}

// ---------------------------------------------------------------- 用户辅助

async function registerAndLogin(label, nick) {
  const username = `v10${label}${RUN_TAG}`;
  const ip = syntheticIp();
  touchedIps.add(ip);
  const reg = await http('POST', '/auth/register', {
    xff: ip,
    body: { username, password: PASS_WORD, nickname: `验收${nick}${RUN_TAG}` },
  });
  if (codeOf(reg) !== 0 || !reg.json.data) {
    throw new Error(`注册用户 ${label} 失败：code=${codeOf(reg)} ${reg.json?.message || reg.text}`);
  }
  const id = String(reg.json.data.id);
  const login = await http('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account: username, password: PASS_WORD },
  });
  if (codeOf(login) !== 0 || !login.json.data) {
    throw new Error(`登录用户 ${label} 失败：code=${codeOf(login)} ${login.json?.message || login.text}`);
  }
  trackRefresh(id, login.json.data.refreshToken, false);
  return {
    id,
    username,
    password: PASS_WORD,
    token: login.json.data.accessToken,
    refreshToken: login.json.data.refreshToken,
  };
}

async function loginExisting(account, password, label) {
  const login = await http('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account, password },
  });
  if (codeOf(login) !== 0 || !login.json.data) {
    return { token: null, loginCode: codeOf(login) };
  }
  const id = String(login.json.data.user.id);
  trackRefresh(id, login.json.data.refreshToken, true);
  return {
    id,
    token: login.json.data.accessToken,
    refreshToken: login.json.data.refreshToken,
    loginCode: 0,
    label,
  };
}

function trackRefresh(userId, refreshToken, isDemo) {
  const claims = jwtClaims(refreshToken);
  if (claims && claims.jti) {
    if (isDemo) created.demoRefreshJtis.push(claims.jti);
    else created.userRefreshJtis.push(claims.jti);
  }
}

// ---------------------------------------------------------------- 计数辅助

const COUNTER_COLS = ['like_count', 'collect_count', 'comment_count', 'share_count'];

function counterRow(table, id) {
  const line = mysqlScalar(`SELECT ${COUNTER_COLS.join(',')} FROM newmusic.${table} WHERE id=${q(id)}`);
  return line.split('\t').map((v) => Number(v || 0));
}

function currentCount(table, type, targetId, column) {
  const rows = mysqlCount(`SELECT COUNT(*) FROM newmusic.${table} WHERE target_type='${type}' `
    + `AND target_id=${q(targetId)} AND ${column}`);
  return rows;
}

function liveLikes(type, targetId) {
  return mysqlCount(`SELECT COUNT(*) FROM newmusic.t_like WHERE target_type='${type}' `
    + `AND target_id=${q(targetId)} AND user_id IN (${idList(created.userIds)})`);
}

function liveCollects(type, targetId) {
  return mysqlCount(`SELECT COUNT(*) FROM newmusic.t_collect WHERE target_type='${type}' `
    + `AND target_id=${q(targetId)} AND user_id IN (${idList(created.userIds)})`);
}

function liveComments(type, targetId) {
  return mysqlCount(`SELECT COUNT(*) FROM newmusic.t_comment WHERE target_type='${type}' `
    + `AND target_id=${q(targetId)} AND status=1 AND deleted=0 `
    + `AND user_id IN (${idList(created.userIds)})`);
}

function liveShares(type, targetId) {
  return mysqlCount(`SELECT COUNT(*) FROM newmusic.t_share WHERE target_type='${type}' `
    + `AND target_id=${q(targetId)} AND (sharer_id IN (${idList(created.userIds)}) `
    + `OR code IN (${idList(created.shareCodes)}))`);
}

function subtractCounter(table, id, deltas) {
  const sets = COUNTER_COLS.map((col, i) => `${col}=GREATEST(0, ${col}-${Number(deltas[i] || 0)})`);
  mysql(`UPDATE newmusic.${table} SET ${sets.join(',')} WHERE id=${q(id)}`);
}

function countersEqual(table, id, expected) {
  const now = counterRow(table, id);
  return COUNTER_COLS.every((_, i) => now[i] === expected[i]);
}

// ---------------------------------------------------------------- 清理

async function cleanup(baseline, demoPrivacyExisted) {
  const ids = created.userIds;
  const userList = idList(ids);
  const hasUsers = ids.length > 0;

  try {
    if (hasUsers) {
      // 1) 先按当前存活行回退内容计数，避免删除关系后丢增量
      subtractCounter('t_song', SONG_ID, [
        liveLikes('SONG', SONG_ID),
        liveCollects('SONG', SONG_ID),
        liveComments('SONG', SONG_ID),
        liveShares('SONG', SONG_ID),
      ]);
      subtractCounter('t_playlist', PLAYLIST_ID, [
        liveLikes('PLAYLIST', PLAYLIST_ID),
        liveCollects('PLAYLIST', PLAYLIST_ID),
        liveComments('PLAYLIST', PLAYLIST_ID),
        liveShares('PLAYLIST', PLAYLIST_ID),
      ]);
      subtractCounter('t_album', ALBUM_ID, [
        liveLikes('ALBUM', ALBUM_ID),
        liveCollects('ALBUM', ALBUM_ID),
        liveComments('ALBUM', ALBUM_ID),
        liveShares('ALBUM', ALBUM_ID),
      ]);
      if (demoPlaylistId) {
        subtractCounter('t_playlist', demoPlaylistId, [
          liveLikes('PLAYLIST', demoPlaylistId),
          liveCollects('PLAYLIST', demoPlaylistId),
          liveComments('PLAYLIST', demoPlaylistId),
          liveShares('PLAYLIST', demoPlaylistId),
        ]);
      }
      const artistFollows = mysqlCount(
        `SELECT COUNT(*) FROM newmusic.t_user_artist_follow WHERE user_id IN (${userList}) `
        + `AND artist_id=${ARTIST_ID}`);
      if (artistFollows > 0) {
        mysql(`UPDATE newmusic.t_artist SET follower_count=GREATEST(0, follower_count-${artistFollows}) `
          + `WHERE id=${ARTIST_ID}`);
      }

      // 2) 删除本轮用户产生的社交数据（全部按精确用户 ID / 精确记录 ID）
      mysql(`DELETE FROM newmusic.t_comment WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_like WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_collect WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_collect_folder WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_follow WHERE follower_id IN (${userList}) `
        + `OR following_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_user_artist_follow WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_blacklist WHERE user_id IN (${userList}) `
        + `OR blocked_user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_private_message WHERE sender_id IN (${userList}) `
        + `OR receiver_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_conversation WHERE user_a_id IN (${userList}) `
        + `OR user_b_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN (${userList}) `
        + `OR from_user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_notification_setting WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_user_privacy_setting WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_user_role WHERE user_id IN (${userList})`);
      mysql(`DELETE FROM newmusic.t_user WHERE id IN (${userList})`);
    }

    if (created.reportIds.length) {
      mysql(`DELETE FROM newmusic.t_report WHERE id IN (${idList(created.reportIds)})`);
    }
    if (created.shareCodes.length) {
      mysql(`DELETE FROM newmusic.t_share WHERE code IN (${idList(created.shareCodes)})`);
    }
    if (demoPlaylistId) {
      mysql(`DELETE FROM newmusic.t_playlist_song WHERE playlist_id=${q(demoPlaylistId)}`);
      mysql(`DELETE FROM newmusic.t_playlist WHERE id=${q(demoPlaylistId)}`);
    }
  } catch (e) {
    note(`MySQL 清理失败：${e.message}`);
  }

  if (REDIS_OK) {
    try {
      const keys = [];
      for (const id of ids) {
        keys.push(`ml:like:user:${id}:SONG`, `ml:like:user:${id}:COMMENT`,
          `ml:like:user:${id}:PLAYLIST`, `ml:like:user:${id}:ALBUM`,
          `ml:follow:${id}`, `ml:fans:${id}`,
          `ml:blacklist:${id}`, `ml:blockedby:${id}`,
          `ml:notify:unread:${id}`,
          `ml:auth:refresh:user:${id}`);
      }
      for (const jti of created.userRefreshJtis) keys.push(`ml:auth:refresh:${jti}`);
      redisDel(...keys);

      // 目标集合是共享的：只 SREM 本轮用户，绝不整键 DEL（别的用户/项目可能在用）
      if (ids.length) {
        const likeTargets = [
          ['SONG', SONG_ID], ['SONG', SONG_ID_2],
          ['PLAYLIST', PLAYLIST_ID],
          ...(demoPlaylistId ? [['PLAYLIST', demoPlaylistId]] : []),
          ['ALBUM', ALBUM_ID],
          ...created.commentIds.map((cid) => ['COMMENT', cid]),
        ];
        for (const [type, target] of likeTargets) {
          redis('srem', `ml:like:${type}:${target}`, ...ids);
        }
        for (const id of ids) {
          redis('srem', `ml:follow:${id}`, ...ids.filter((x) => x !== id));
          redis('srem', `ml:fans:${id}`, ...ids.filter((x) => x !== id));
        }
      }

      for (const code of created.shareCodes) {
        redisDel(`ml:social:short:${code}`, `ml:social:short:click:${code}`,
          `ml:social:short:visitor:${code}`);
      }
      for (const jti of created.demoRefreshJtis) {
        redisDel(`ml:auth:refresh:${jti}`);
        redis('srem', 'ml:auth:refresh:user:1000000000000002003', jti);
      }
      for (const jti of created.adminRefreshJtis) redisDel(`ml:admin:refresh:${jti}`);
      for (const captchaId of created.adminCaptchaIds) redisDel(`ml:admin:captcha:${captchaId}`);

      for (const id of ids) {
        delByPattern(`ml:limit:*:USER:${id}:*`);
        delByPattern(`ml:social:msg:rate:${id}:*`);
        delByPattern(`ml:social:msg:daily:${id}:*`);
        delByPattern(`ml:social:follow:day:${id}:*`);
        delByPattern(`ml:social:report:day:${id}:*`);
      }
      for (const ip of touchedIps) {
        delByPattern(`ml:limit:*:IP:${ip}:*`);
      }
      // demo 的举报日计数只回退本轮 1 次，不整键删除；日期按 JVM 的本地时区拼
      const now = new Date();
      const localDate = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
      const demoReportKey = `ml:social:report:day:1000000000000002003:${localDate}`;
      if (redis('exists', demoReportKey) === '1') {
        redis('decrby', demoReportKey, '1');
      }
    } catch (e) {
      note(`Redis 清理失败：${e.message}`);
    }
  }

  // 3) 残留 / 共享实例完整性 / 计数恢复
  section('§13 清理与共享实例完整性');
  if (hasUsers) {
    const leftover = mysqlScalar(`SELECT
      (SELECT COUNT(*) FROM newmusic.t_user WHERE id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_comment WHERE user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_like WHERE user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_collect WHERE user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_follow WHERE follower_id IN (${userList}) OR following_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_blacklist WHERE user_id IN (${userList}) OR blocked_user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_private_message WHERE sender_id IN (${userList}) OR receiver_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_conversation WHERE user_a_id IN (${userList}) OR user_b_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id IN (${userList}) OR from_user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_notification_setting WHERE user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_user_privacy_setting WHERE user_id IN (${userList})) +
      (SELECT COUNT(*) FROM newmusic.t_report WHERE id IN (${idList(created.reportIds)})) +
      (SELECT COUNT(*) FROM newmusic.t_share WHERE code IN (${idList(created.shareCodes)}))`);
    check('本轮测试数据已全部清理（业务表零残留）', Number(leftover) === 0, leftover);
  } else {
    skip('本轮测试数据清理', '用户注册未完成');
  }

  check('测试歌曲计数已恢复原值', countersEqual('t_song', SONG_ID, baseline.song),
    { expected: baseline.song, actual: counterRow('t_song', SONG_ID) });
  check('测试歌单计数已恢复原值', countersEqual('t_playlist', PLAYLIST_ID, baseline.playlist),
    { expected: baseline.playlist, actual: counterRow('t_playlist', PLAYLIST_ID) });
  check('测试专辑计数已恢复原值', countersEqual('t_album', ALBUM_ID, baseline.album),
    { expected: baseline.album, actual: counterRow('t_album', ALBUM_ID) });
  if (demoPlaylistId) {
    check('demo 测试歌单已物理删除',
      mysqlCount(`SELECT COUNT(*) FROM newmusic.t_playlist WHERE id=${q(demoPlaylistId)}`) === 0);
  }
  check('歌手粉丝数已恢复原值',
    mysqlCount(`SELECT follower_count FROM newmusic.t_artist WHERE id=${ARTIST_ID}`)
      === baseline.artistFollower);

  const sharedDbTablesAfter = mysqlScalar(
    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
  check('共享实例中的 emp_db / music_db 表数量未变化',
    sharedDbTablesAfter === sharedDbTablesBefore, `${sharedDbTablesBefore} → ${sharedDbTablesAfter}`);

  if (REDIS_OK) {
    const rankingAfter = listKeys('ranking:*');
    check('另一个项目的 ranking:* 键数量未变化',
      rankingAfter.length === rankingBefore.length, `${rankingBefore.length} → ${rankingAfter.length}`);
  } else {
    skip('Redis 共享键完整性', 'redis-cli 不可用');
  }
}

main().catch((e) => {
  console.error('\n\x1b[31m验收脚本异常终止\x1b[0m');
  console.error(e && e.stack ? e.stack : e);
  process.exitCode = 1;
});
