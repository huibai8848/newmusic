#!/usr/bin/env node
/**
 * 模块 14 验收脚本 —— AI 能力增强（统一网关 / 歌词翻译 / 情绪识别 / 后台运维）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-14.mjs
 *
 * 前置：
 *   * 应用已启动（8081），使用默认 mock provider（或已配置 HTTP provider）；
 *   * 已执行 sql/14-ai-seed.sql；
 *   * 后台验证码回显开启（ADMIN_CAPTCHA_ECHO=true），与 verify-09 相同；
 *   * Node 22+，Redis 6379 / MySQL 3306 可连。
 *
 * 共享实例约束（与 verify-04/05/06/09/12/13 相同）：
 *   * Redis db0 有别的项目的 `ranking:*` 键：只读写 `ml:` 前缀，绝不 FLUSHDB/FLUSHALL；
 *     开头/结尾各数一次 `ranking:*` 并逐键比对，证明未误伤。
 *   * MySQL 3306 有 emp_db / music_db：所有语句带 `newmusic.` 限定，只更新/删除本轮
 *     演示歌曲（5101/5102）的精确行；开头/结尾对比其它库的表数量。
 *   * 口令只从环境变量 MYSQL_PASSWORD 读取，经 MYSQL_PWD 传给客户端，不写入文件。
 *
 * 数据清理：脚本开头与结尾都会把演示歌曲 5101/5102 的译文、情绪标注与 Redis 永久缓存
 * 恢复为空，保证重复执行结果一致（AI 调用日志属于审计数据，只增不删）。
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

/** 模块 14 的演示歌曲（sql/14-ai-seed.sql）：5101 有英文歌词，5102 无歌词 */
const SONG_WITH_LYRIC = '1000000000000005101';
const SONG_WITHOUT_LYRIC = '1000000000000005102';
const PROBE_SONGS = [SONG_WITH_LYRIC, SONG_WITHOUT_LYRIC];
const EMOTION_TAGS = ['快乐', '悲伤', '愤怒', '平静', '浪漫', '思念', '励志', '怀旧', '治愈', '孤独'];
const SCENE_TAGS = ['通勤', '学习', '运动', '睡前', '聚会', '独处', '咖啡厅', '驾车', '清晨', '雨天', '旅行', '工作'];

let pass = 0;
let fail = 0;
let skipped = 0;
const failures = [];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.${Math.floor(Math.random() * 200) + 20}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

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
  const account = `v14${label}${TAG}`;
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

async function adminLogin(xff, tag = 'ai') {
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

const rankingBefore = REDIS_OK ? listKeys('ranking:*') : [];
const sharedDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

/**
 * 清掉演示歌曲上的 AI 数据，让脚本可重复执行。
 *
 * 除了 DB 与永久译文缓存，还要清 **网关结果缓存**（`ml:ai:cache:*`）：
 * 它按 prompt 指纹缓存模型输出，与 songId 无关，清不掉的话第二次运行会直接命中缓存，
 * 于是「真实生成」用例看到 cost=0 / fromCache=true，看起来像成本记账没生效。
 * 缓存本身是可丢弃的派生数据，删掉只会让下一次调用回源模型。
 */
function resetProbeData(probeUserId) {
  if (REDIS_OK) {
    for (const songId of PROBE_SONGS) {
      redis('del', `ml:ai:lyric:trans:${songId}`);
    }
    const cached = listKeys('ml:ai:cache:*');
    if (cached.length) redis('del', ...cached);
  }
  if (!HAS_MYSQL) return;
  const songs = PROBE_SONGS.join(',');
  mysql(`
UPDATE newmusic.t_lyric SET translation=NULL, trans_lang=NULL WHERE song_id IN (${songs});
UPDATE newmusic.t_song SET has_translation=0 WHERE id IN (${songs});
DELETE FROM newmusic.t_song_emotion WHERE song_id IN (${songs});
${probeUserId ? `DELETE FROM newmusic.t_ai_feedback WHERE song_id IN (${songs}) AND user_id=${probeUserId};` : ''}
`);
}

/** 读取一个配置的当前值，用于测试后恢复。 */
function configValue(key) {
  return mysqlScalar(`SELECT config_value FROM newmusic.t_sys_config WHERE config_key='${key}' LIMIT 1`);
}

// ---------------------------------------------------------------- 主流程

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪。');
    process.exit(2);
  }

  if (!HAS_MYSQL) {
    note('未提供 MYSQL_PASSWORD：DB 断言与演示数据清理将跳过（脚本仍可验证 HTTP 行为）');
  }

  const user = await registerAndLogin('user');
  const adminSession = await adminLogin(syntheticIp());
  const xff = () => syntheticIp();

  let originalBudget = null;
  let originalRouting = null;
  let chineseSongId = null;

  try {
    resetProbeData(user.userId);
    if (HAS_MYSQL) {
      originalBudget = configValue('ai.daily.budget.cent');
      originalRouting = configValue('ai.model.routing');
      chineseSongId = mysqlScalar(`
SELECT s.id FROM newmusic.t_song s
JOIN newmusic.t_lyric l ON l.song_id = s.id AND l.deleted = 0
WHERE s.deleted = 0 AND l.content IS NOT NULL AND l.content <> ''
  AND (s.language LIKE '%中文%' OR s.language LIKE '%国语%' OR s.language LIKE '%粤语%')
LIMIT 1`).trim() || null;
    }

    // ======================================================================
    section('§1 歌词翻译读取（公开接口，FR-23-002）');
    // ======================================================================
    // 必须是合法 Long 但不存在：超过 Long.MAX 会先被参数解析拦成 10001
    const missing = await call('GET', '/lyric/1234567890123456789/translation');
    check('不存在的歌曲 → 30001', codeOf(missing) === 30001, missing.json?.message);

    const before = await call('GET', `/lyric/${SONG_WITH_LYRIC}/translation`, { xff: xff() });
    const beforeData = dataOf(before) || {};
    check('GET 译文无需登录', codeOf(before) === 0, before.json);
    check('未生成时 generated=false 而不是报错', beforeData.generated === false);
    check('行数与 LRC 一致（8 行，含重复副歌）',
      Array.isArray(beforeData.lines) && beforeData.lines.length === 8,
      beforeData.lines?.length);
    check('每行带 timeMs，前端才能按时间对齐',
      (beforeData.lines || []).every((l) => Number.isFinite(l.timeMs)),
      beforeData.lines?.map((l) => l.timeMs));
    check('时间轴来自标签原始值（0/5000/.../45000）',
      JSON.stringify((beforeData.lines || []).map((l) => l.timeMs))
        === JSON.stringify([0, 5000, 10000, 15000, 20000, 25000, 30000, 45000]));
    check('重复行都保留（30s 与 45s 同一句）',
      beforeData.lines?.length === 8
        && beforeData.lines[6].original === beforeData.lines[7].original);
    check('未生成时 translated 全为 null',
      (beforeData.lines || []).every((l) => l.translation === null));

    const badLang = await call('GET', `/lyric/${SONG_WITH_LYRIC}/translation?lang=ja`, { xff: xff() });
    check('lang=ja → 10001（目前只支持中文）', codeOf(badLang) === 10001, badLang.json?.message);

    const noLyric = await call('GET', `/lyric/${SONG_WITHOUT_LYRIC}/translation`, { xff: xff() });
    check('无歌词 → 60006（不适用而不是故障）', codeOf(noLyric) === 60006, noLyric.json?.message);

    if (chineseSongId) {
      const chinese = await call('GET', `/lyric/${chineseSongId}/translation`, { xff: xff() });
      check(`中文歌曲（${chineseSongId}）→ 60006`, codeOf(chinese) === 60006, chinese.json?.message);
    } else {
      skip('中文歌曲拒绝翻译', HAS_MYSQL ? '库中没有带歌词的中文歌' : '需要 MYSQL_PASSWORD');
    }

    // ======================================================================
    section('§2 生成译文 / 行数校验 / 永久缓存 / 反馈（FR-23-002）');
    // ======================================================================
    const noAuth = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation`, { xff: xff() });
    check('未登录生成 → 401', noAuth.status === 401, `${noAuth.status}/${codeOf(noAuth)}`);

    const gen = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation`, {
      token: user.token, xff: xff(),
    });
    const genData = dataOf(gen) || {};
    check('生成译文成功', codeOf(gen) === 0 && genData.generated === true, gen.json);
    check('本次不是缓存（fromCache=false）', genData.fromCache === false);
    check('每行都有非空译文',
      (genData.lines || []).length === 8 && genData.lines.every((l) => typeof l.translation === 'string'
        && l.translation.trim().length > 0),
      genData.lines);
    check('译文与原文时间轴一一对应',
      JSON.stringify(genData.lines.map((l) => l.timeMs))
        === JSON.stringify(beforeData.lines.map((l) => l.timeMs)));
    check('返回成本 ≥ 1 分（BR-23-003）', Number(genData.costCent) >= 1, genData.costCent);
    check('返回实际使用的模型', Boolean(genData.model), genData.model);
    check('生成结果 LRC 含时间标签', typeof genData.translation === 'string'
      && genData.translation.includes('[00:05'));

    const cached = await call('GET', `/lyric/${SONG_WITH_LYRIC}/translation`, { xff: xff() });
    check('再次读取命中 Redis（fromCache=true）', codeOf(cached) === 0 && dataOf(cached)?.fromCache === true,
      dataOf(cached)?.fromCache);
    check('缓存读取不产生成本', Number(dataOf(cached)?.costCent) === 0, dataOf(cached)?.costCent);
    if (REDIS_OK) {
      check('永久缓存键存在（ml:ai:lyric:trans:{songId}）',
        redis('exists', `ml:ai:lyric:trans:${SONG_WITH_LYRIC}`) === '1');
      const ttl = Number(redis('ttl', `ml:ai:lyric:trans:${SONG_WITH_LYRIC}`));
      check('缓存为永久（TTL=-1，BR-23-019）', ttl === -1, ttl);
    } else {
      skip('永久缓存键检查', 'Redis CLI 不可用');
    }

    const again = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation`, {
      token: user.token, xff: xff(),
    });
    check('重复生成幂等：直接返回已有译文', codeOf(again) === 0 && dataOf(again)?.generated === true);
    check('重复生成不再产生成本', Number(dataOf(again)?.costCent) === 0, dataOf(again)?.costCent);

    if (HAS_MYSQL) {
      const row = mysql(`SELECT IF(translation IS NULL,'NULL','OK'), trans_lang
FROM newmusic.t_lyric WHERE song_id=${SONG_WITH_LYRIC} ORDER BY id DESC LIMIT 1;`).split(/\r?\n/)[0];
      check('译文已落库且 trans_lang=zh-CN', row === 'OK\tzh-CN', row);
      check('t_song.has_translation=1',
        mysqlScalar(`SELECT has_translation FROM newmusic.t_song WHERE id=${SONG_WITH_LYRIC}`) === '1');
      const logRow = mysql(`SELECT success, cost_cent, from_cache, model_level
FROM newmusic.t_ai_call_log WHERE scene='LYRIC_TRANS' AND biz_id='${SONG_WITH_LYRIC}'
ORDER BY create_time DESC LIMIT 1;`).replace(/\r/g, '');
      const [logSuccess, logCost, logCache, logLevel] = logRow.split('\t');
      check('调用日志：成功、成本>0、非缓存',
        logSuccess === '1' && Number(logCost) >= 1 && logCache === '0', logRow);
      check('调用日志记录了模型档位（MEDIUM）', logLevel === 'MEDIUM', logLevel);

      const fb = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation/feedback`, {
        token: user.token, xff: xff(), body: { issue: '第二句意思不够准确' },
      });
      check('翻译反馈提交成功', codeOf(fb) === 0 && Number(dataOf(fb)) > 0, fb.json);
      check('反馈已入库（status=0 待处理）',
        mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_ai_feedback
WHERE song_id=${SONG_WITH_LYRIC} AND user_id=${user.userId} AND status=0`) === '1');
    } else {
      skip('译文落库 / 调用日志 / 反馈入库', '需要 MYSQL_PASSWORD');
      const fb = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation/feedback`, {
        token: user.token, xff: xff(), body: { issue: '第二句意思不够准确' },
      });
      check('翻译反馈提交成功', codeOf(fb) === 0 && Number(dataOf(fb)) > 0, fb.json);
    }

    const emptyIssue = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation/feedback`, {
      token: user.token, xff: xff(), body: { issue: '   ' },
    });
    check('空反馈 → 10001', codeOf(emptyIssue) === 10001, emptyIssue.json?.message);
    const longIssue = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation/feedback`, {
      token: user.token, xff: xff(), body: { issue: 'x'.repeat(201) },
    });
    check('反馈超过 200 字 → 10001', codeOf(longIssue) === 10001, longIssue.json?.message);

    const noAuthFeedback = await call('POST', `/lyric/${SONG_WITH_LYRIC}/translation/feedback`, {
      xff: xff(), body: { issue: '未登录' },
    });
    check('未登录反馈 → 401', noAuthFeedback.status === 401, noAuthFeedback.status);

    // ======================================================================
    section('§3 情绪 / 场景识别与人工校正（FR-23-004）');
    // ======================================================================
    const emotionBefore = await call('GET', `/songs/${SONG_WITHOUT_LYRIC}/emotion`, { xff: xff() });
    const ebData = dataOf(emotionBefore) || {};
    check('未标注时 annotated=false 且标签为空',
      codeOf(emotionBefore) === 0 && ebData.annotated === false
        && Array.isArray(ebData.emotionTags) && ebData.emotionTags.length === 0,
      emotionBefore.json);

    const annotate = await adminReq('POST', '/ai/annotate', {
      session: adminSession, xff: xff(),
      body: { scene: 'EMOTION_TAG', songId: SONG_WITHOUT_LYRIC },
    });
    const marked = dataOf(annotate)?.emotion || null;
    check('后台单曲标注成功', codeOf(annotate) === 0 && marked?.annotated === true, annotate.json);
    check('情绪标签来自白名单', (marked?.emotionTags || []).length > 0
      && marked.emotionTags.every((t) => EMOTION_TAGS.includes(t)), marked?.emotionTags);
    check('场景标签来自白名单', (marked?.sceneTags || []).length > 0
      && marked.sceneTags.every((t) => SCENE_TAGS.includes(t)), marked?.sceneTags);
    check('标签数量 ≤ 3', (marked?.emotionTags || []).length <= 3
      && (marked?.sceneTags || []).length <= 3);
    check('数值夹在 0~1', [marked?.valence, marked?.arousal, marked?.energy, marked?.confidence]
      .every((v) => typeof v === 'number' && v >= 0 && v <= 1),
    [marked?.valence, marked?.arousal, marked?.energy, marked?.confidence]);
    check('来源为 AI', marked?.source === 'AI', marked?.source);
    check('无歌词歌曲也能标注（提示词标注「无歌词」）', marked?.confidence > 0);

    const publicEmotion = await call('GET', `/songs/${SONG_WITHOUT_LYRIC}/emotion`, { xff: xff() });
    check('C 端公开接口可读到标注',
      codeOf(publicEmotion) === 0 && dataOf(publicEmotion)?.annotated === true
        && JSON.stringify(dataOf(publicEmotion)?.emotionTags) === JSON.stringify(marked?.emotionTags),
      publicEmotion.json);

    if (HAS_MYSQL) {
      check('情绪标注已入库（source=AI）',
        mysqlScalar(`SELECT source FROM newmusic.t_song_emotion WHERE song_id=${SONG_WITHOUT_LYRIC}`) === 'AI');
    }

    const manual = await adminReq('PUT', `/songs/${SONG_WITHOUT_LYRIC}/emotion`, {
      session: adminSession, xff: xff(),
      body: { valence: 0.7, arousal: 0.3, energy: 0.2, emotionTags: ['浪漫'], sceneTags: ['睡前'], reason: '人工抽检' },
    });
    check('人工校正成功且 source=MANUAL',
      codeOf(manual) === 0 && dataOf(manual)?.source === 'MANUAL'
        && dataOf(manual)?.confidence === 1, manual.json);

    const manualRead = await call('GET', `/songs/${SONG_WITHOUT_LYRIC}/emotion`, { xff: xff() });
    check('C 端看到人工校正结果',
      JSON.stringify(dataOf(manualRead)?.emotionTags) === JSON.stringify(['浪漫'])
        && dataOf(manualRead)?.source === 'MANUAL', manualRead.json?.data);

    const batch = await adminReq('POST', '/ai/annotate', {
      session: adminSession, xff: xff(), body: { scene: 'EMOTION_TAG', limit: 1 },
    });
    check('批量标注返回 jobId 与统计',
      codeOf(batch) === 0 && Boolean(dataOf(batch)?.jobId)
        && Number.isFinite(Number(dataOf(batch)?.total)), batch.json);

    if (HAS_MYSQL) {
      check('批处理不会覆盖 MANUAL（BR-23-039）',
        mysqlScalar(`SELECT source FROM newmusic.t_song_emotion WHERE song_id=${SONG_WITHOUT_LYRIC}`) === 'MANUAL');
    }

    const jobId = dataOf(batch)?.jobId;
    if (jobId) {
      const progress = await adminReq('GET', `/ai/progress/${jobId}`, { session: adminSession, xff: xff() });
      check('任务进度可查询且已结束',
        codeOf(progress) === 0 && ['FINISHED', 'FINISHED_WITH_ERRORS'].includes(dataOf(progress)?.status),
        progress.json?.data);
    }

    const badTag = await adminReq('PUT', `/songs/${SONG_WITHOUT_LYRIC}/emotion`, {
      session: adminSession, xff: xff(),
      body: { valence: 0.5, arousal: 0.5, energy: 0.5, emotionTags: ['摇滚'], sceneTags: [] },
    });
    check('非白名单情绪标签 → 10001', codeOf(badTag) === 10001, badTag.json?.message);

    const lowConfidence = await adminReq('GET', '/ai/low-confidence?limit=20', {
      session: adminSession, xff: xff(),
    });
    check('低置信度队列可读', codeOf(lowConfidence) === 0 && Array.isArray(dataOf(lowConfidence)),
      lowConfidence.json?.message);

    // ======================================================================
    section('§4 用户额度（BR-23-034）');
    // ======================================================================
    const quotaNoAuth = await call('GET', '/ai/quota', { xff: xff() });
    check('额度查询需要登录', quotaNoAuth.status === 401, quotaNoAuth.status);

    const quotaAll = await call('GET', '/ai/quota', { token: user.token, xff: xff() });
    const quotaList = dataOf(quotaAll) || [];
    check('返回所有有限额的场景', codeOf(quotaAll) === 0 && quotaList.length > 0, quotaList.length);
    const transQuota = quotaList.find((q) => q.scene === 'LYRIC_TRANS');
    check('含 LYRIC_TRANS 额度（20/小时、100/天）',
      transQuota?.hourLimit === 20 && transQuota?.dayLimit === 100, transQuota);
    check('本次生成已计入额度', Number(transQuota?.hourUsed) >= 1 && Number(transQuota?.dayUsed) >= 1,
      transQuota);
    check('剩余额度 = 上限 - 已用',
      transQuota?.hourRemaining === transQuota.hourLimit - transQuota.hourUsed
        && transQuota?.dayRemaining === transQuota.dayLimit - transQuota.dayUsed);

    const quotaScene = await call('GET', '/ai/quota?scene=LYRIC_TRANS', { token: user.token, xff: xff() });
    check('按场景查询返回单条', codeOf(quotaScene) === 0 && (dataOf(quotaScene) || []).length === 1
      && dataOf(quotaScene)[0].scene === 'LYRIC_TRANS', quotaScene.json?.data);

    const quotaBad = await call('GET', '/ai/quota?scene=NOPE', { token: user.token, xff: xff() });
    check('未知场景 → 10001', codeOf(quotaBad) === 10001, quotaBad.json?.message);

    // ======================================================================
    section('§5 后台运维接口与权限（FR-23-001 成本 / 路由）');
    // ======================================================================
    const stats = await adminReq('GET', '/ai/cost-stats?days=1', { session: adminSession, xff: xff() });
    const statsData = dataOf(stats) || {};
    check('成本统计可读', codeOf(stats) === 0, stats.json?.message);
    check('统计含按场景汇总', Array.isArray(statsData.scenes) && statsData.scenes.length >= 1,
      Object.keys(statsData));
    check('统计含每日趋势', Array.isArray(statsData.daily) && statsData.daily.length === 1);
    check('统计含预算状态', statsData.budget && Number(statsData.budget.usedCent) >= 1, statsData.budget);
    check('统计含生效配置', statsData.config && Number(statsData.config.dailyBudgetCent) > 0,
      statsData.config);
    check('provider 与后台任务开关可见',
      typeof statsData.provider === 'string' && typeof statsData.jobsEnabled === 'boolean');

    const logs = await adminReq('GET', '/ai/call-logs?pageNum=1&pageSize=10&scene=LYRIC_TRANS&success=1', {
      session: adminSession, xff: xff(),
    });
    const logList = dataOf(logs) || {};
    check('调用日志可分页且按场景过滤',
      codeOf(logs) === 0 && Array.isArray(logList.list) && logList.list.length >= 1
        && logList.list.every((l) => l.scene === 'LYRIC_TRANS' && l.success === 1),
      logList.list?.length);

    const cTokenOnAdmin = await adminReq('GET', '/ai/cost-stats', {
      xff: xff(), headers: { Authorization: `Bearer ${user.token}` },
    });
    check('C 端 token 不能访问后台接口', cTokenOnAdmin.status === 401 || codeOf(cTokenOnAdmin) !== 0,
      `${cTokenOnAdmin.status}/${codeOf(cTokenOnAdmin)}`);

    const badRoutingScene = await adminReq('PUT', '/ai/model-routing', {
      session: adminSession, xff: xff(), body: { routing: { NOPE: 'SMALL' } },
    });
    check('路由写入拒绝未知场景', codeOf(badRoutingScene) === 10001, badRoutingScene.json?.message);

    const badRoutingLevel = await adminReq('PUT', '/ai/model-routing', {
      session: adminSession, xff: xff(), body: { routing: { LYRIC_TRANS: 'HUGE' } },
    });
    check('路由写入拒绝未知档位', codeOf(badRoutingLevel) === 10001, badRoutingLevel.json?.message);

    const setRouting = await adminReq('PUT', '/ai/model-routing', {
      session: adminSession, xff: xff(), body: { routing: { LYRIC_TRANS: 'SMALL' } },
    });
    check('合法路由可保存', codeOf(setRouting) === 0, setRouting.json?.message);
    const routingRead = await adminReq('GET', '/ai/cost-stats?days=1', { session: adminSession, xff: xff() });
    check('保存后立即生效（配置缓存被清）',
      dataOf(routingRead)?.config?.modelRouting?.LYRIC_TRANS === 'SMALL',
      dataOf(routingRead)?.config?.modelRouting);

    if (originalRouting) {
      await adminReq('PUT', '/ai/model-routing', {
        session: adminSession, xff: xff(), body: { routing: JSON.parse(originalRouting) },
      });
    }

    if (HAS_MYSQL) {
      check('后台权限码已授权（admin:ai:view / admin:ai:edit）',
        mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_admin_permission
WHERE perm_code IN ('admin:ai:view','admin:ai:edit') AND status=1`) === '2');
    } else {
      skip('后台权限码检查', '需要 MYSQL_PASSWORD');
    }

    // ======================================================================
    section('§6 日预算熔断与恢复（BR-23-030 / BR-23-032）');
    // ======================================================================
    if (!HAS_MYSQL) {
      skip('预算熔断', '需要 MYSQL_PASSWORD 以恢复原预算值');
    } else {
      const setBudget = await adminReq('PUT', '/ai/budget', {
        session: adminSession, xff: xff(), body: { dailyBudgetCent: 1 },
      });
      check('预算可下调到 1 分', codeOf(setBudget) === 0, setBudget.json?.message);

      // 今天的成本至少已有刚才翻译的 1 分，因此下一次调用应被立即拦下
      const blocked = await adminReq('POST', '/ai/annotate', {
        session: adminSession, xff: xff(),
        body: { scene: 'EMOTION_TAG', songId: SONG_WITH_LYRIC },
      });
      check('预算用尽后 AI 调用被熔断（60002）', codeOf(blocked) === 60002, blocked.json);
      check('熔断原因是预算而非其它错误',
        typeof blocked.json?.message === 'string' && blocked.json.message.includes('预算'),
        blocked.json?.message);

      const pausedState = await adminReq('GET', '/ai/cost-stats?days=1', { session: adminSession, xff: xff() });
      check('成本统计显示已暂停', dataOf(pausedState)?.budget?.paused === true,
        dataOf(pausedState)?.budget);

      const restore = await adminReq('PUT', '/ai/budget', {
        session: adminSession, xff: xff(), body: { dailyBudgetCent: Number(originalBudget) },
      });
      check('调回预算成功', codeOf(restore) === 0, restore.json?.message);
      const resumed = await adminReq('GET', '/ai/cost-stats?days=1', { session: adminSession, xff: xff() });
      check('调高预算立即解除熔断',
        dataOf(resumed)?.budget?.paused === false
          && Number(dataOf(resumed)?.budget?.dailyBudgetCent) === Number(originalBudget),
        dataOf(resumed)?.budget);

      // 解除后应可继续调用（情绪标注，不依赖译文）
      const afterResume = await adminReq('POST', '/ai/annotate', {
        session: adminSession, xff: xff(),
        body: { scene: 'EMOTION_TAG', songId: SONG_WITH_LYRIC },
      });
      check('解除熔断后调用恢复', codeOf(afterResume) === 0
        && dataOf(afterResume)?.emotion?.annotated === true, afterResume.json);
    }

    // ======================================================================
    section('§7 共享实例完整性复核');
    // ======================================================================
  } finally {
    // 无论断言成败都恢复配置与演示数据
    if (HAS_MYSQL && originalBudget !== null && originalBudget !== ''
      && configValue('ai.daily.budget.cent') !== originalBudget) {
      await adminReq('PUT', '/ai/budget', {
        session: adminSession, xff: syntheticIp(), body: { dailyBudgetCent: Number(originalBudget) },
      }).catch(() => null);
    }
    if (HAS_MYSQL && originalRouting) {
      await adminReq('PUT', '/ai/model-routing', {
        session: adminSession, xff: syntheticIp(), body: { routing: JSON.parse(originalRouting) },
      }).catch(() => null);
    }
    try {
      resetProbeData(user.userId);
    } catch (e) {
      note(`演示数据清理失败（请手工检查 5101/5102）：${e.message}`);
    }
  }

  const rankingAfter = REDIS_OK ? listKeys('ranking:*') : [];
  check('Redis 其它项目 ranking:* 键未被改动',
    JSON.stringify(rankingAfter.sort()) === JSON.stringify(rankingBefore.sort()),
    { before: rankingBefore.length, after: rankingAfter.length });
  if (HAS_MYSQL) {
    const sharedAfter = mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('emp_db / music_db 表数量未变', sharedAfter === sharedDbTablesBefore,
      { before: sharedDbTablesBefore, after: sharedAfter });
    check('演示数据已复位（译文清空 / 情绪标注删除）',
      mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_song_emotion WHERE song_id IN (${PROBE_SONGS.join(',')})`) === '0'
        && mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_lyric
WHERE song_id=${SONG_WITH_LYRIC} AND translation IS NOT NULL`) === '0');
  } else {
    skip('MySQL 共享实例复核', '需要 MYSQL_PASSWORD');
  }

  console.log('\n========================================');
  console.log(`通过 ${pass} / 失败 ${fail} / 跳过 ${skipped}`);
  if (failures.length) {
    console.log('\n失败项：');
    failures.forEach((name, i) => console.log(`  ${i + 1}. ${name}`));
  }
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error('\n验收脚本异常：', error);
  process.exit(2);
});
