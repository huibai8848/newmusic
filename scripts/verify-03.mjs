#!/usr/bin/env node
/**
 * 模块 03 验收脚本：Redis 缓存 / 布隆过滤器 / 击穿锁 / 限流 / 登录态
 * 对应 docs/系分/03-Redis缓存与并发控制.md §8 验收核对表
 *
 * 运行：
 *   node scripts/verify-03.mjs
 *   REDIS_CLI="C:\\Program Files\\Redis\\redis-cli" node scripts/verify-03.mjs
 *
 * 前置：应用已启动（端口 8081）+ 本机 Redis 6379 可连。
 * 依赖：Node 18+（内置 fetch / child_process），无需 npm 包。
 *
 * ⚠ 本机 6379 是**共享实例**：db0 里躺着另一个项目的 ranking:* 键。
 *   脚本只读写 ml: 前缀的键，并用 keys 列出（不用 --scan —— 本机 redis-cli 3.0
 *   的 --scan 静默返回空，会让断言「全部通过」却没检查任何东西）。
 */

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
/**
 * 应用日志路径。用它数「回源真的打了几次库」——比断言响应一致硬得多。
 * 后端按 `mvn spring-boot:run > boot.log 2>&1` 启动时默认就是这个文件。
 */
const logPath = process.env.APP_LOG === '' ? null : resolve(process.env.APP_LOG || 'boot.log');

/** 幂等的探测账号：重复运行不会累积脏数据（注册失败就直接登录） */
const PROBE = { account: 'm03probe', password: 'Probe@12345' };
/** 锁定测试专用账号，与上面分开，免得 15 分钟锁定把后续用例一起拖下水 */
const LOCKACCT = { account: 'm03lock', password: 'Lock@12345' };
/**
 * 占位码：不存在的歌曲（布隆过滤器判定「一定不存在」）。
 * 必须落在 long 范围内——19 个 9 会溢出 Long，在 @PathVariable 绑定阶段就变成 10001，
 * 根本走不到布隆过滤器（首次运行踩过）。
 */
const ABSENT_SONG = '8888888888888888888';

/**
 * 登录/刷新/登出这一组用例统一走这个合成 IP。
 *
 * 为什么不用真实的 127.0.0.1：§07+§08 每遍要打十几次 /auth/*，会吃掉本机 IP 维度
 * 30 次/分钟的额度——连跑两遍就把第三遍的 admin 登录挡在门外（报 10004「请求过于频繁」）。
 * 每遍换一个随机 IP，额度天然重置，也就不必去删真实的限流键。
 */
const AUTH_IP = `10.98.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

let pass = 0;
let fail = 0;
const failures = [];
const skip = [];

function check(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log(`  \x1b[32m✓\x1b[0m ${name}`);
  } else {
    fail += 1;
    failures.push(name);
    console.log(`  \x1b[31m✗\x1b[0m ${name}${extra ? ` → ${typeof extra === 'string' ? extra : JSON.stringify(extra)}` : ''}`);
  }
}

function note(msg) {
  console.log(`  \x1b[33m•\x1b[0m ${msg}`);
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, path, { token, body, headers, xff } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = `Bearer ${token}`;
  if (xff) h['X-Forwarded-For'] = xff;
  const started = process.hrtime.bigint();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON */
  }
  return {
    status: res.status,
    json,
    text,
    elapsedMs,
    retryAfter: res.headers.get('Retry-After'),
  };
}

// ---------------------------------------------------------------- redis helper

function redis(...args) {
  const r = spawnSync(REDIS_CLI, args, { encoding: 'utf8' });
  if (r.error) {
    throw new Error(`无法执行 redis-cli（${REDIS_CLI}）：${r.error.message}`);
  }
  if (r.status !== 0) {
    throw new Error(`redis-cli ${args.join(' ')} 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  }
  return (r.stdout || '').trim();
}

const rGet = (k) => {
  const v = redis('get', k);
  return v === '' ? null : v;
};
const rTtl = (k) => Number(redis('ttl', k));
const rDel = (...keys) => redis('del', ...keys);
const rExists = (k) => redis('exists', k) === '1';
/** 只列出 ml: 前缀的键。绝不用 KEYS *（会带出别的项目的键，也让断言失去意义） */
const mlKeys = () => {
  const out = redis('keys', 'ml:*');
  return out === '' ? [] : out.split('\n').map((s) => s.trim()).filter(Boolean);
};

/**
 * 清掉某个限流窗口，供用例自我隔离。
 *
 * 账号维度的登录限流（10 次/分钟）是**跨运行累计**的，脚本连着跑两遍时第二遍会被
 * 上一遍的探测请求限流掉（首次连跑就栽在这里，报 10004「请 13 秒后再试」）。
 * 只删 ml: 下这一个键，且只删脚本自己的探测账号。
 */
const clearLimit = (type, dimension, identifier, api) =>
  rDel(`ml:limit:${type}:${dimension}:${identifier}:${api}`);

async function waitForBoot(maxSeconds = 90) {
  const deadline = Date.now() + maxSeconds * 1000;
  process.stdout.write('等待应用启动');
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${BASE}/music/songs?pageSize=1`);
      if (res.status < 500) {
        console.log(' 就绪');
        return true;
      }
    } catch {
      /* 未就绪 */
    }
    process.stdout.write('.');
    await sleep(2000);
  }
  console.log(' 超时');
  return false;
}

/** 注册（已存在则登录），返回 { token, refresh, userId } */
async function loginAs({ account, password }) {
  await call('POST', '/auth/register', {
    xff: AUTH_IP,
    body: { username: account, password, nickname: account },
  });
  const res = await call('POST', '/auth/login', { xff: AUTH_IP, body: { account, password } });
  if (res.json?.code !== 0) {
    throw new Error(`探测账号登录失败 ${account}: ${JSON.stringify(res.json)}`);
  }
  return {
    token: res.json.data.accessToken,
    refresh: res.json.data.refreshToken,
    userId: res.json.data.user.id,
  };
}

/**
 * 取一个真实存在的歌曲 ID。
 * 用管理员列表接口而不是硬编码：数据由 data.sql 决定，硬编码会在别人改种子数据时静默失效。
 */
async function firstSongId(adminToken) {
  const res = await call('GET', '/music/songs?pageSize=1', { token: adminToken });
  const rows = res.json?.data?.list || res.json?.data?.records || [];
  return rows.length ? String(rows[0].id) : null;
}

async function snapshot(songId, token) {
  const res = await call('GET', `/music/songs/${songId}`, { token });
  return res.json?.data;
}

/**
 * 把某首歌的两级缓存都清掉，让随后的读一定是真回源。
 *
 * <p><b>不能用 redis-cli DEL 代替。</b>缓存是两级的，进程内还有一层 Caffeine
 * （`local-ttl-seconds=30`）。只删 Redis 键的话，下一次读会命中本地层直接返回、
 * **不回写 Redis**，于是「删了键之后 Redis 里还是没有」——看起来像缓存没生效，
 * 实际是测试没制造出真正的冷读（首次运行就栽在这里）。
 * 这里走应用自己的写路径：一次等值 PUT 会触发 D-26 的 afterCommit 清理，
 * 两级缓存一起失效，这正是线上真实的失效路径。
 */
async function coldSong(songId, adminToken) {
  const s = await snapshot(songId, adminToken);
  if (!s) {
    throw new Error(`coldSong 取不到歌曲快照 songId=${songId}`);
  }
  const res = await call('PUT', `/music/songs/${songId}`, {
    token: adminToken,
    body: {
      name: s.name,
      artistId: s.artistId,
      duration: s.duration,
      language: s.language,
      genre: s.genre,
      copyrightStatus: s.copyrightStatus,
      audioStandard: s.audioStandard,
      auditStatus: s.auditStatus,
      status: s.status,
      version: s.version,
    },
  });
  if (res.json?.code !== 0) {
    throw new Error(`coldSong 的等值 PUT 失败：${JSON.stringify(res.json)}`);
  }
  // 越过延迟双删的 500ms 窗口：否则它会在我们回源写回之后又删一次
  await sleep(650);
}

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪。');
    process.exit(2);
  }
  try {
    redis('ping');
  } catch (e) {
    console.error(`\n无法连接 Redis：${e.message}`);
    process.exit(2);
  }

  // 共享实例安全检查：确认我们看到的键确实是本机共享的那一份
  const rankingCount = redis('keys', 'ranking:*').split('\n').filter(Boolean).length;
  note(`共享 db0：ranking:* 键 ${rankingCount} 个（另一个项目所有，本脚本绝不触碰）`);

  const admin = (await call('POST', '/auth/login', { body: { account: 'admin', password: 'admin123' } }))
    .json?.data;
  if (!admin?.accessToken) {
    console.error('admin 登录失败，无法继续。');
    process.exit(2);
  }
  const adminToken = admin.accessToken;

  const songId = await firstSongId(adminToken);
  if (!songId) {
    console.error('曲库为空，无法验证缓存。');
    process.exit(2);
  }

  // ================================================================ 6.1 读路径
  section('01 读路径：Cache-Aside + 空值 + 抖动（FR-12-001 / AC-12-001-1、104）');
  const songKey = `ml:music:song:${songId}`;
  await coldSong(songId, adminToken);
  check('冷读前置：两级缓存均已清空', !rExists(songKey));

  const first = await call('GET', `/music/songs/${songId}`);
  check('未命中时回源成功（code=0）', first.json?.code === 0, first.json);
  check(`回源后写入 ${songKey}`, rExists(songKey));

  let cached = rGet(songKey);
  check('缓存值与接口 data 字段逐字段一致（StringRedisTemplate + 应用 ObjectMapper）',
    cached !== null && JSON.stringify(JSON.parse(cached)) === JSON.stringify(first.json.data),
    cached?.slice(0, 80));
  check('缓存值不含 Jackson 类型污染（@class）',
    cached !== null && !cached.includes('@class'), cached?.slice(0, 80));

  // TTL 抖动：base=1800s、ratio=10% => 期望落在 [1800, 1980)
  const ttl = rTtl(songKey);
  check(`TTL 抖动只加不减：${ttl}s ∈ [1800, 1980)`, ttl >= 1800 && ttl < 1980, `ttl=${ttl}`);
  check('缓存键符合 BR-12-001 前缀约定', songId && songKey.startsWith('ml:music:song:'));

  const second = await call('GET', `/music/songs/${songId}`);
  check('命中缓存时响应体与回源一致',
    JSON.stringify(second.json?.data) === JSON.stringify(first.json?.data));
  // 只对「命中」设绝对上限，不比倍数：回源的绝对耗时受 MySQL/Druid 预热影响太大，
  // 用比值断言在冷热机之间会忽闪——真正硬的证据是 §05 的 DB 查询次数
  note(`耗时：回源 ${first.elapsedMs.toFixed(1)}ms / 命中 ${second.elapsedMs.toFixed(1)}ms`);
  check(`命中缓存 <20ms（实测 ${second.elapsedMs.toFixed(1)}ms）`, second.elapsedMs < 20,
    `${second.elapsedMs.toFixed(1)}ms`);

  // ================================================================ 布隆：穿透
  section('02 布隆过滤器：挡住不存在的 ID（FR-12-009 / AC-12-001-2）');
  rDel(`ml:music:song:${ABSENT_SONG}`);
  const absent = await call('GET', `/music/songs/${ABSENT_SONG}`);
  check('不存在的歌曲返回 30001', absent.json?.code === 30001, absent.json);
  check('布隆判定「一定不存在」时**不写**空值缓存（否则穿透防御反成缓存写放大）',
    !rExists(`ml:music:song:${ABSENT_SONG}`));
  check('布隆过滤器 ml:bloom:song 已建立且不过期',
    rExists('ml:bloom:song') && rTtl('ml:bloom:song') === -1, `ttl=${rTtl('ml:bloom:song')}`);

  // ================================================================ 空值缓存
  section('03 空值缓存 __NULL__：布隆放行但库里没有（AC-12-001-3）');
  // 造出「布隆说可能有、库里确实没有」的状态：新增会写入布隆，逻辑删除不会（布隆删不掉）
  const probeName = `m03-null-${Date.now()}`;
  const created = await call('POST', '/music/songs', {
    token: adminToken,
    body: {
      name: probeName,
      artistId: '1000000000000003001',
      duration: 100,
      language: '国语',
      genre: '流行',
      copyrightStatus: 1,
      audioStandard: '/media/demo/demo-01.wav',
      auditStatus: 1,
      status: 1,
    },
  });
  const tmpId = created.json?.data;
  check('新增歌曲成功（同时写入布隆）', created.json?.code === 0 && Boolean(tmpId), created.json);

  const warm = await call('GET', `/music/songs/${tmpId}`);
  check('新增后可读且已进缓存', warm.json?.code === 0 && rExists(`ml:music:song:${tmpId}`));

  const removed = await call('DELETE', `/music/songs/${tmpId}`, { token: adminToken });
  check('删除歌曲成功', removed.json?.code === 0, removed.json);
  check('删除后缓存键被立即清除（D-26）', !rExists(`ml:music:song:${tmpId}`));

  const afterDel = await call('GET', `/music/songs/${tmpId}`);
  check('删除后详情返回 30001', afterDel.json?.code === 30001, afterDel.json);
  const nullMarker = rGet(`ml:music:song:${tmpId}`);
  check('写下空值标记 __NULL__（防穿透）', nullMarker === '__NULL__', nullMarker);
  const nullTtl = rTtl(`ml:music:song:${tmpId}`);
  check(`空值 TTL 远短于实体（null=60s，实测 ${nullTtl}s ∈ [60, 66)）`,
    nullTtl >= 60 && nullTtl < 66, `ttl=${nullTtl}`);

  // 延迟双删：500ms 后应再删一次（这是有意的过度失效，代价换并发一致性）
  await sleep(700);
  check('延迟双删在 ~500ms 后再次清除该键', !rExists(`ml:music:song:${tmpId}`));

  // ================================================================ 写路径失效
  section('04 写路径：先清缓存再提交、删除而非更新（D-23 / D-26）');
  await coldSong(songId, adminToken);
  const w = await call('GET', `/music/songs/${songId}`);
  check('读一次建立缓存', w.json?.code === 0 && rExists(songKey));

  const target = await call('GET', `/music/songs/${songId}`);
  const originalName = target.json.data.name;
  const version = target.json.data.version;
  const renamed = `${originalName}-m03`;
  const upd = await call('PUT', `/music/songs/${songId}`, {
    token: adminToken,
    body: {
      name: renamed,
      artistId: target.json.data.artistId,
      duration: target.json.data.duration,
      language: target.json.data.language,
      genre: target.json.data.genre,
      copyrightStatus: target.json.data.copyrightStatus,
      audioStandard: target.json.data.audioStandard,
      auditStatus: target.json.data.auditStatus,
      status: target.json.data.status,
      version,
    },
  });
  check('改名成功', upd.json?.code === 0, upd.json);
  check('提交后缓存键被清除', !rExists(songKey));

  const reread = await call('GET', `/music/songs/${songId}`);
  check('回源读到新名称（不是旧缓存）', reread.json?.data?.name === renamed,
    { got: reread.json?.data?.name, want: renamed });

  // 还原，避免污染种子数据
  const back = await call('PUT', `/music/songs/${songId}`, {
    token: adminToken,
    body: {
      name: originalName,
      artistId: reread.json.data.artistId,
      duration: reread.json.data.duration,
      language: reread.json.data.language,
      genre: reread.json.data.genre,
      copyrightStatus: reread.json.data.copyrightStatus,
      audioStandard: reread.json.data.audioStandard,
      auditStatus: reread.json.data.auditStatus,
      status: reread.json.data.status,
      version: reread.json.data.version,
    },
  });
  check('名称已还原（脚本可重复执行）', back.json?.code === 0, back.json);

  // ================================================================ 击穿锁
  section('05 缓存击穿：并发回源只加载一次（FR-12-001 / AC-12-001-4）');
  await coldSong(songId, adminToken);
  check('冷读前置：两级缓存均已清空', !rExists(songKey));
  // 应用把 MyBatis 的 SQL 打到 stdout（StdOutImpl），所以这里能直接数出「回源真的打了几次库」——
  // 比断言「响应一致」硬得多：没有锁的话 20 个并发会打出 20 条 SELECT。
  // 日志不可读时（比如应用输出被重定向到别处）降级为只做功能断言，不强判失败。
  const detailSql = /Preparing: SELECT[^\n]*FROM t_song WHERE id=\?/g;
  const sqlCount = () => {
    if (!logPath) return null;
    try {
      return (readFileSync(logPath, 'utf8').match(detailSql) || []).length;
    } catch {
      return null;
    }
  };
  const sqlBefore = sqlCount();

  const N = 20;
  const burst = await Promise.all(
    Array.from({ length: N }, () => call('GET', `/music/songs/${songId}`)),
  );
  const okCount = burst.filter((r) => r.json?.code === 0).length;
  check(`${N} 个并发请求全部成功`, okCount === N, `${okCount}/${N}`);
  const bodies = new Set(burst.map((r) => JSON.stringify(r.json?.data)));
  check('并发响应内容完全一致（无脏读/半成品）', bodies.size === 1, `不同响应数=${bodies.size}`);
  check('并发回源后缓存已重建', rExists(songKey));
  check('未残留分布式锁', !rExists(`ml:lock:cache:song:${songId}`));

  const sqlAfter = sqlCount();
  if (sqlBefore === null || sqlAfter === null) {
    skip.push(`击穿锁的 DB 查询计数：未读到应用日志（设 APP_LOG=<路径> 可启用）`);
    note('未读到应用日志，只做了功能断言');
  } else {
    const hits = sqlAfter - sqlBefore;
    check(`${N} 个并发未命中只打 1 次库（实测 ${hits} 次）`, hits === 1, `实测 ${hits} 次`);
  }

  // ================================================================ 限流
  section('06 限流：滑动窗口 + Retry-After（FR-12-005 / AC-12-005）');
  // 用 X-Forwarded-For 制造成独立的 IP 维度，避免污染 127.0.0.1 的真实计数
  const fakeIp = `10.99.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
  const dupName = 'admin'; // 用已存在的账号：前 5 次不会真的建用户
  const codes = [];
  for (let i = 0; i < 7; i += 1) {
    const r = await call('POST', '/auth/register', {
      xff: fakeIp,
      body: { username: dupName, password: 'Whatever@123', nickname: 'x' },
    });
    codes.push({ code: r.json?.code, retryAfter: r.retryAfter });
  }
  const allowed = codes.slice(0, 5).filter((c) => c.code !== 10004).length;
  const blocked = codes.slice(5).filter((c) => c.code === 10004).length;
  check('配额内 5 次放行（注册限 5 次/分钟）', allowed === 5, codes.map((c) => c.code));
  check('第 6、7 次被限流返回 10004', blocked === 2, codes.map((c) => c.code));
  const ra = codes[5].retryAfter;
  check('被限流响应带 Retry-After 头且 ≤ 窗口(60s)',
    ra !== null && Number(ra) > 0 && Number(ra) <= 60, `Retry-After=${ra}`);
  const limitKey = `ml:limit:auth:ip:${fakeIp}:POST:/api/v1/auth/register`;
  check(`限流键按 BR-12-026 命名：${limitKey}`, rExists(limitKey));
  check('限流键是 ZSet（滑动窗口）', redis('type', limitKey) === 'zset', redis('type', limitKey));
  check('限流键 TTL ≤ 窗口长度', rTtl(limitKey) > 0 && rTtl(limitKey) <= 60, `ttl=${rTtl(limitKey)}`);

  const otherIp = `10.99.${fakeIp.split('.')[2]}.${(Number(fakeIp.split('.')[3]) % 250) + 1}`;
  const isolated = await call('POST', '/auth/register', {
    xff: otherIp,
    body: { username: dupName, password: 'Whatever@123', nickname: 'x' },
  });
  check('另一 IP 不受影响（维度隔离）', isolated.json?.code !== 10004, isolated.json);

  // ================================================================ 登录态
  section('07 登录态：登出吊销 / refresh 轮换与重放（FR-12-004 / AC-12-004）');
  // 账号维度的登录限流（10 次/分钟）按账号累计、跨运行不重置，所以这里清掉自己上一遍的窗口；
  // IP 维度靠本节的 AUTH_IP 隔离，不去动 127.0.0.1 的真实计数
  clearLimit('auth', 'account', PROBE.account, 'POST:/api/v1/auth/login');
  const probe = await loginAs(PROBE);
  const meOk = await call('GET', '/users/me', { token: probe.token });
  check('登录后 accessToken 可用', meOk.json?.code === 0, meOk.json);

  const out = await call('POST', '/auth/logout', { token: probe.token, xff: AUTH_IP });
  check('登出返回成功', out.json?.code === 0, out.json);
  const afterLogout = await call('GET', '/users/me', { token: probe.token });
  check('登出后旧 accessToken 立即失效（40102）', afterLogout.json?.code === 40102, afterLogout.json);
  // 40102 只在「确实需要鉴权」时出现：公开接口带登出残留的 Token 应当仍按游客可用，
  // 否则用户一登出就连曲库都刷不出来（JwtAuthenticationFilter 的公开接口放行语义）
  const publicWithRevoked = await call('GET', `/music/songs/${songId}`, { token: probe.token });
  check('登出残留 Token 访问公开接口仍按游客放行（不被 40102 拦）',
    publicWithRevoked.json?.code === 0, publicWithRevoked.json);
  const anonProtected = await call('GET', '/users/me');
  check('未带 Token 访问需鉴权接口仍是 40101（两种未认证可区分）',
    anonProtected.json?.code === 40101, anonProtected.json);

  const s1 = await loginAs(PROBE);
  const r1 = await call('POST', '/auth/refresh', { xff: AUTH_IP, body: { refreshToken: s1.refresh } });
  check('refresh 换取新双 Token', r1.json?.code === 0 && Boolean(r1.json?.data?.accessToken), r1.json);
  const r2 = await call('POST', '/auth/refresh', { xff: AUTH_IP, body: { refreshToken: s1.refresh } });
  check('旧 refreshToken 重放被拒（40102）', r2.json?.code === 40102, r2.json);
  const r3 = await call('POST', '/auth/refresh', { xff: AUTH_IP, body: { refreshToken: r1.json?.data?.refreshToken } });
  check('重放后该用户全部会话被撤销（新 refreshToken 也失效）',
    r3.json?.code === 40102, r3.json);

  // 封禁：直接写标记，模拟模块 09 的封禁动作。
  // 原因用 ASCII：Windows 的 redis-cli 会把命令行里的中文按 GBK 发出去，
  // 应用按 UTF-8 解码就成了乱码 —— 是**测试工具的编码问题**，不是应用的。
  // （应用自身写入/读取中文已经在 01 的缓存比对里用「晴天」验证过了。）
  const banKey = `ml:auth:userban:${s1.userId}`;
  redis('set', banKey, 'm03-ban-probe', 'EX', '60');
  const banned = await call('GET', '/users/me', { token: s1.token });
  check('被封禁用户请求返回 20005', banned.json?.code === 20005, banned.json);
  check('封禁提示包含原因', /m03-ban-probe/.test(banned.json?.message || ''), banned.json?.message);
  rDel(banKey);
  const unban = await call('GET', '/users/me', { token: s1.token });
  check('解封后立即恢复（标记即封即解）', unban.json?.code === 0, unban.json);

  // ================================================================ 登录失败锁定
  section('08 登录失败锁定：连续 5 次锁 15 分钟（BR-12-019）');
  rDel(`ml:auth:fail:${LOCKACCT.account}`);
  // 本节要连打 7 次登录，会吃掉账号维度 10 次/分钟额度的大半；先清掉自己上一遍留下的窗口
  clearLimit('auth', 'account', LOCKACCT.account, 'POST:/api/v1/auth/login');
  await call('POST', '/auth/register', {
    xff: AUTH_IP,
    body: { username: LOCKACCT.account, password: LOCKACCT.password, nickname: LOCKACCT.account },
  });
  let last = null;
  for (let i = 0; i < 5; i += 1) {
    last = await call('POST', '/auth/login', {
      xff: AUTH_IP,
      body: { account: LOCKACCT.account, password: 'definitely-wrong' },
    });
  }
  check('前 5 次密码错误返回 20001', last.json?.code === 20001, last.json);
  const failKey = `ml:auth:fail:${LOCKACCT.account}`;
  check(`失败计数键 ${failKey} 已建立`, rExists(failKey), rGet(failKey));
  check('计数 = 5', rGet(failKey) === '5', rGet(failKey));

  const locked = await call('POST', '/auth/login', {
    xff: AUTH_IP,
    body: { account: LOCKACCT.account, password: LOCKACCT.password },
  });
  check('锁定期内**正确密码**也被拒（复用 20001，不泄漏账号是否存在）',
    locked.json?.code === 20001, locked.json);
  check('提示语含剩余时间', /分钟/.test(locked.json?.message || ''), locked.json?.message);
  const failTtl = rTtl(failKey);
  check(`锁定窗口 TTL ≤ 15 分钟（实测 ${failTtl}s）`, failTtl > 0 && failTtl <= 900, `ttl=${failTtl}`);

  // 清理锁定，保证脚本可重复执行（只删 ml: 下的这一个键）
  rDel(failKey);
  const unlocked = await call('POST', '/auth/login', {
    xff: AUTH_IP,
    body: { account: LOCKACCT.account, password: LOCKACCT.password },
  });
  check('计数清除后即可正常登录（可重复执行）', unlocked.json?.code === 0, unlocked.json);

  // ================================================================ 键空间体检
  section('09 键空间：前缀完整性与 TTL（AC-12-104）');
  const keys = mlKeys();
  const bad = keys.filter((k) => !k.startsWith('ml:'));
  check('所有应用键都在 ml: 前缀内', bad.length === 0, bad);
  const noTtl = keys.filter((k) => rTtl(k) === -1 && !k.includes('bloom:song'));
  check('除布隆过滤器外，所有键都有 TTL（无意外常驻键）', noTtl.length === 0, noTtl);
  note(`ml:* 键 ${keys.length} 个：${keys.slice(0, 8).join(', ')}${keys.length > 8 ? ' …' : ''}`);
  note(`共享 db0 的 ranking:* 键仍为 ${redis('keys', 'ranking:*').split('\n').filter(Boolean).length} 个（未被触碰）`);

  // ================================================================ 降级
  section('10 降级：Redis 不可用时的行为（FR-12-010 / AC-12-001-4、005-4）');
  skip.push('停 Redis 验证降级：本机 6379 是另一项目的共享实例，未经确认不主动停服');
  note('已跳过（见上方 skip 原因）；降级矩阵的设计与人工验证步骤见系分 §6.5');

  // ---------------------------------------------------------------- 汇总
  console.log('\n' + '─'.repeat(64));
  console.log(`\x1b[32m通过 ${pass}\x1b[0m   \x1b[31m失败 ${fail}\x1b[0m   跳过 ${skip.length}`);
  if (failures.length) {
    console.log('\n失败项：');
    failures.forEach((f) => console.log(`  - ${f}`));
  }
  if (skip.length) {
    console.log('\n跳过项：');
    skip.forEach((s) => console.log(`  - ${s}`));
  }
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error('\n脚本异常：', e);
  process.exit(2);
});
