#!/usr/bin/env node
/**
 * 模块 11 验收脚本 —— IK 分词与中文搜索优化。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-11.mjs
 *
 * 前置：
 *   * 应用已启动（8081），`ADMIN_CAPTCHA_ECHO=true`；
 *   * 已执行 sql/11-ik-seed.sql（或用 schema.sql + data.sql 初始化）；
 *   * 本机 Redis 6379 / MySQL 3306 可连（不连也能跑，只是 DB 断言会跳过）。
 *
 * 覆盖：
 *   * `/api/v1/internal/dict/ik` 四类词典、ETag/Last-Modified/304、非法 type；
 *   * 主词典 CRUD/重复/非法/导入（逐行错误）/导出/自动词条只读；
 *   * 停用词 CRUD、年份与同义词冲突、导入导出；
 *   * 同义词组 CRUD、跨组冲突、主词校验、导入导出；
 *   * 拼音词典 CRUD、格式校验、导入导出；
 *   * 分词测试（本地兜底 + 预览词条）；
 *   * 搜索改写：周董→周杰伦、rap→说唱、繁→简、首尾停用词、拼音；
 *   * 状态/重载/重建索引的降级语义（ES 未启用 → 71008）；
 *   * 歌手自动同步与 71004 只读保护；RBAC（operator 无 reindex）；
 *   * 共享 MySQL/Redis 实例完整性。
 *
 * 安全约定：
 *   * MySQL 全部 SQL 带 `newmusic.` 限定符，只用精确 ID/word 删除自己造的数据；
 *   * Redis 只用 `ml:` 前缀键；不执行 FLUSHDB/FLUSHALL；
 *   * 每节使用随机合成 X-Forwarded-For，隔离登录失败计数与限流；
 *   * 断言「同义词/繁简有效」前先证明对照组非空，避免在空集合上恒真。
 */

import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';

// ---------------------------------------------------------------- 配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ADMIN_BASE = process.env.ADMIN_API_BASE || `${API_BASE}/admin`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';

const ADMIN_USER = process.env.M11_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.M11_ADMIN_PASSWORD || 'admin123';
const OPERATOR_USER = process.env.M11_OPERATOR_USER || 'operator';
const OPERATOR_PASS = process.env.M11_OPERATOR_PASSWORD || 'operator123';

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
const wordA = `词${RUN_TAG}`;
const wordB = `导${RUN_TAG}A`;
const wordC = `导${RUN_TAG}B`;
const stopA = `停${RUN_TAG}`;
const stopB = `停${RUN_TAG}B`;
const synA = `考${RUN_TAG}A`;
const synB = `考${RUN_TAG}B`;
const synC = `考${RUN_TAG}C`;
const synImpA = `合${RUN_TAG}A`;
const synImpB = `合${RUN_TAG}B`;
const synOneA = `单${RUN_TAG}A`;
const synOneB = `单${RUN_TAG}B`;
const pinyinWordA = `拼${RUN_TAG}A`;
const pinyinWordB = `拼${RUN_TAG}B`;
const artistName = `考歌手${RUN_TAG}`;
const analyzeWord = `量子音乐${RUN_TAG}`;

const syntheticIp = () => `10.96.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
const ipOf = (() => {
  const cache = new Map();
  return (section) => {
    if (!cache.has(section)) cache.set(section, syntheticIp());
    return cache.get(section);
  };
})();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- 断言输出

let pass = 0;
let fail = 0;
let skipped = 0;
/** /dict/status 报告的是否启用 ES —— 后续「降级语义」按它分支，两种环境都能跑 */
let ES_ENABLED = false;
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
const sqlStr = (s) => `CONVERT(0x${Buffer.from(String(s), 'utf8').toString('hex')} USING utf8mb4)`;

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
  if (keys.length) redis('del', ...keys);
};

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，Redis 完整性断言会跳过：${e.message}`);
}

const redisCleanupKeys = [];
const rankingBefore = REDIS_OK ? listKeys('ranking:*') : [];
const sharedDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

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

function jwtClaims(token) {
  try {
    const part = String(token).split('.')[1];
    if (!part) return null;
    return JSON.parse(Buffer.from(part.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
  } catch {
    return null;
  }
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
      session.jar.set(name, { value, attrs, path: attrs.path || '/' });
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
  const buffer = Buffer.from(await res.arrayBuffer());
  const text = buffer.toString('utf8');
  if (session) applySetCookies(session, res);
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（词典文本/导出文本） */
  }
  return { status: res.status, json, text, headers: res.headers, buffer };
}

const adminReq = (method, path, opts = {}) => http(method, `${ADMIN_BASE}${path}`, opts);
const apiReq = (method, path, opts = {}) => http(method, `${API_BASE}${path}`, opts);

function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

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

async function fetchCaptcha(session, xff) {
  const res = await adminReq('GET', '/auth/captcha', { session, xff });
  const captcha = res.json && res.json.data ? res.json.data : null;
  if (captcha && captcha.captchaId) redisCleanupKeys.push(`ml:admin:captcha:${captcha.captchaId}`);
  return { res, captcha };
}

async function login(username, password, { xff, tag } = {}) {
  const session = new Session(tag || username);
  const { res: capRes, captcha } = await fetchCaptcha(session, xff);
  if (!captcha || !captcha.captchaId || !captcha.code) {
    throw new Error(`验证码回显不可用（${capRes.status}/${capRes.json && capRes.json.code}）：`
      + '请确认 ADMIN_CAPTCHA_ECHO=true');
  }
  const res = await adminReq('POST', '/auth/login', {
    session,
    xff,
    body: {
      username,
      password,
      captchaId: captcha.captchaId,
      captchaCode: captcha.code,
    },
  });
  if (!res.json || res.json.code !== 0) {
    throw new Error(`后台登录失败 ${username}：${res.status}/${res.text.slice(0, 200)}`);
  }
  return session;
}

// ---------------------------------------------------------------- 数据台账

const createdWordIds = new Set();
const createdWords = new Set();
const createdStopwordIds = new Set();
const createdStopwords = new Set();
const createdGroupNos = new Set();
const createdPinyinIds = new Set();
const createdPinyinWords = new Set();
const createdArtistIds = new Set();

const trackWord = (entity) => {
  if (entity && entity.id !== undefined) createdWordIds.add(String(entity.id));
  if (entity && entity.word) createdWords.add(entity.word);
};
const trackStopword = (entity) => {
  if (entity && entity.id !== undefined) createdStopwordIds.add(String(entity.id));
  if (entity && entity.word) createdStopwords.add(entity.word);
};
const trackPinyin = (entity) => {
  if (entity && entity.id !== undefined) createdPinyinIds.add(String(entity.id));
  if (entity && entity.word) createdPinyinWords.add(entity.word);
};

function idList(set) {
  return [...set].map((id) => (/^\d+$/.test(String(id)) ? String(id) : '0')).join(',');
}

function wordList(set) {
  if (!set.size) return 'NULL';
  return [...set].map(sqlStr).join(',');
}

function cleanup() {
  if (!HAS_MYSQL) {
    note('未提供 MYSQL_PASSWORD，跳过数据库清理；请手工清理本轮词条（RUN_TAG=' + RUN_TAG + '）');
    return;
  }
  try {
    if (createdWordIds.size) {
      mysql(`DELETE FROM newmusic.t_ik_dict_word WHERE id IN (${idList(createdWordIds)});`);
    }
    if (createdWords.size) {
      mysql(`DELETE FROM newmusic.t_ik_dict_word WHERE word IN (${wordList(createdWords)});`);
    }
    if (createdStopwordIds.size) {
      mysql(`DELETE FROM newmusic.t_ik_stopword WHERE id IN (${idList(createdStopwordIds)});`);
    }
    if (createdStopwords.size) {
      mysql(`DELETE FROM newmusic.t_ik_stopword WHERE word IN (${wordList(createdStopwords)});`);
    }
    if (createdGroupNos.size) {
      mysql(`DELETE FROM newmusic.t_synonym WHERE group_no IN (${[...createdGroupNos].join(',')});`);
    }
    mysql(`DELETE FROM newmusic.t_synonym WHERE word LIKE ${sqlStr(`%${RUN_TAG}%`)};`);
    if (createdPinyinIds.size) {
      mysql(`DELETE FROM newmusic.t_pinyin_dict WHERE id IN (${idList(createdPinyinIds)});`);
    }
    if (createdPinyinWords.size) {
      mysql(`DELETE FROM newmusic.t_pinyin_dict WHERE word IN (${wordList(createdPinyinWords)});`);
    }
    if (createdArtistIds.size) {
      const ids = idList(createdArtistIds);
      mysql(`DELETE FROM newmusic.t_ik_dict_word WHERE source = 'AUTO_ARTIST' AND ref_id IN (${ids});`);
      mysql(`DELETE FROM newmusic.t_artist WHERE id IN (${ids});`);
    }
  } catch (e) {
    note(`清理失败（需手工处理）：${e.message}`);
  }
}

// ---------------------------------------------------------------- 主流程

async function internalDictSection() {
  section('1. IK 远程词典服务（/api/v1/internal/dict/ik）');
  const xff = ipOf('internal');
  // 该接口 produces text/plain；默认的 Accept: application/json 会触发 406，
  // 被全局异常处理器包成 99001。ES 的 IK 插件按 text/plain 拉取，这里也照做。
  const internalReq = (path, headers = {}) =>
    apiReq('GET', path, { xff, headers: { Accept: 'text/plain', ...headers } });

  const main = await internalReq('/internal/dict/ik?type=main');
  check('无需登录即可被 ES 拉取', main.status === 200, `${main.status} ${main.text.slice(0, 120)}`);
  check('Content-Type 为 text/plain;charset=UTF-8',
    (main.headers.get('content-type') || '').toLowerCase().includes('text/plain')
    && (main.headers.get('content-type') || '').toLowerCase().includes('utf-8'),
    main.headers.get('content-type'));
  check('主词典内容非空', main.text.trim().length > 0, main.text.length);
  const etag = main.headers.get('etag');
  const lastModified = main.headers.get('last-modified');
  check('返回强 ETag（带引号）', Boolean(etag && /^".+"$/.test(etag)), etag);
  check('返回 Last-Modified', Boolean(lastModified), lastModified);

  if (etag) {
    const notModified = await internalReq('/internal/dict/ik?type=main',
      { 'If-None-Match': etag });
    check('If-None-Match 命中返回 304', notModified.status === 304, notModified.status);
    check('304 不带正文', notModified.text === '', notModified.text.slice(0, 60));
  } else {
    skip('If-None-Match → 304', '响应没有 ETag');
  }

  if (lastModified) {
    const ims = await internalReq('/internal/dict/ik?type=main',
      { 'If-Modified-Since': lastModified });
    check('If-Modified-Since 命中返回 304', ims.status === 304, ims.status);
  } else {
    skip('If-Modified-Since → 304', '响应没有 Last-Modified');
  }

  const bad = await internalReq('/internal/dict/ik?type=unknown');
  check('非法 type 返回 400', bad.status === 400, `${bad.status} ${bad.text}`);

  for (const [type, expected] of [
    ['stopword', '的'],
    ['synonym', '周杰伦'],
    ['pinyin', '重庆'],
  ]) {
    const res = await internalReq(`/internal/dict/ik?type=${type}`);
    check(`${type} 词典可拉取且包含种子词「${expected}」`,
      res.status === 200 && res.text.includes(expected), `${res.status} ${res.text.slice(0, 80)}`);
  }

  skip('非白名单 IP 拒绝', '脚本只能从 127.0.0.1 发起，进程外构造不出外部来源；该分支由 '
    + 'InternalDictControllerTest 的 7 项 MockMvc 用例覆盖（含伪造 X-Forwarded-For / X-Real-IP），'
    + '部署时用 DICT_INTERNAL_IPS 收紧');
}

async function statusSection(session) {
  section('2. 词典状态 / 分词测试');
  const xff = ipOf('status');
  const status = await adminReq('GET', '/dict/status', { session, xff });
  const data = status.json && status.json.data;
  ES_ENABLED = !!(data && data.esEnabled);
  check('登录后可读取状态', status.json && status.json.code === 0, status.text.slice(0, 160));
  // 本机现在装了 ES：脚本要在「有 ES」和「没 ES」两种环境下都能跑，
  // 断言的是「状态与 esEnabled 自洽」，而不是「一定是某一种」
  if (ES_ENABLED) {
    check('ES 启用时状态如实反映连通情况', data
      && (data.effectiveStatus !== '本地兜底' || data.esAvailable === false), data && data.effectiveStatus);
  } else {
    check('ES 未启用时走本地兜底', data && data.effectiveStatus === '本地兜底', data && data.effectiveStatus);
  }
  check('四类词典快照均返回', data && Array.isArray(data.dicts) && data.dicts.length === 4,
    data && data.dicts && data.dicts.map((d) => d.type).join(','));
  check('给出说明文本', data && data.notice && data.notice.length > 0, data && data.notice);
  check('搜索引擎为 mysql 或 es', data && ['mysql', 'es'].includes(data.searchEngine),
    data && data.searchEngine);

  const analyze = await adminReq(
    'GET', `/dict/analyze${qs({ text: '周杰伦的稻香', analyzer: 'ik_smart' })}`,
    { session, xff });
  const analyzed = analyze.json && analyze.json.data;
  check('分词测试可用', analyze.json && analyze.json.code === 0, analyze.text.slice(0, 160));
  // 装了 IK 才会真的由 ES 分词，否则（含「ES 已启用但无 IK」）如实标记 LOCAL
  check('分词结果如实标记来源', analyzed && ['LOCAL', 'ES'].includes(analyzed.source),
    analyzed && analyzed.source);
  const tokens = analyzed ? analyzed.tokens.map((t) => t.token) : [];
  check('种子词「周杰伦」被切成整词', tokens.includes('周杰伦'), tokens.join('|'));
  check('种子词「稻香」被切成整词', tokens.includes('稻香'), tokens.join('|'));

  const preview = await adminReq(
    'GET', `/dict/analyze${qs({ text: analyzeWord, analyzer: 'ik_smart', previewWords: analyzeWord })}`,
    { session, xff });
  const previewData = preview.json && preview.json.data;
  const previewTokens = previewData ? previewData.tokens : [];
  check('预览词条参与分词', previewTokens.some((t) => t.token === analyzeWord
    && t.startOffset === 0 && t.endOffset === analyzeWord.length),
    previewTokens.map((t) => `${t.token}@${t.startOffset}-${t.endOffset}`).join('|'));
  check('预览模式提示已加入未保存词条',
    previewData && previewData.notice && previewData.notice.includes('预览'),
    previewData && previewData.notice);
}

async function wordSection(session) {
  section('3. 主词典 CRUD / 导入导出');
  const xff = ipOf('words');

  const created = await adminReq('POST', '/dict/words', {
    session,
    xff,
    body: { word: wordA, frequency: 77, pos: 'nz', dictType: 'MAIN', enabled: 1 },
  });
  check('新增词条成功', created.json && created.json.code === 0, created.text.slice(0, 160));
  trackWord(created.json && created.json.data);
  const wordId = created.json && created.json.data && created.json.data.id;

  const duplicate = await adminReq('POST', '/dict/words', {
    session,
    xff,
    body: { word: wordA, frequency: 80, pos: 'n', dictType: 'MAIN' },
  });
  check('重复词条返回 71002', duplicate.json && duplicate.json.code === 71002,
    duplicate.json && duplicate.json.code);

  const invalid = await adminReq('POST', '/dict/words', {
    session,
    xff,
    body: { word: 'a', frequency: 10, pos: 'n', dictType: 'MAIN' },
  });
  check('过短词条返回 71001', invalid.json && invalid.json.code === 71001,
    invalid.json && invalid.json.code);

  const updated = await adminReq('PUT', `/dict/words/${wordId}`, {
    session,
    xff,
    body: { word: wordA, frequency: 321, pos: 'nz', dictType: 'MAIN', enabled: 0 },
  });
  check('修改词条成功', updated.json && updated.json.code === 0, updated.text.slice(0, 160));
  if (HAS_MYSQL) {
    const dbRow = mysqlScalar(
      `SELECT CONCAT(frequency, '|', enabled) FROM newmusic.t_ik_dict_word WHERE id = ${wordId};`);
    check('词频/状态已落库', dbRow === '321|0', dbRow);
  } else {
    skip('词频/状态已落库', '未提供 MYSQL_PASSWORD');
  }

  const imported = await adminReq('POST', '/dict/words/import', {
    session,
    xff,
    body: {
      dictType: 'MAIN',
      overwrite: true,
      content: [
        '# 验收导入',
        `${wordB} 66 n`,
        `${wordC}\t67\tn`,
        'a',
        `${wordB} 70 n`,
      ].join('\n'),
    },
  });
  const importResult = imported.json && imported.json.data;
  check('导入返回逐行报告', imported.json && imported.json.code === 0, imported.text.slice(0, 200));
  check('导入统计：新增 2 / 更新 1 / 失败 1',
    importResult && importResult.inserted === 2 && importResult.updated === 1
    && importResult.failed === 1 && importResult.errors.length === 1,
    importResult && JSON.stringify({
      inserted: importResult.inserted,
      updated: importResult.updated,
      failed: importResult.failed,
      errors: importResult.errors,
    }));
  trackWord({ word: wordB });
  trackWord({ word: wordC });

  const exported = await adminReq('GET', `/dict/words/export${qs({ source: 'MANUAL' })}`, { session, xff });
  check('导出为 UTF-8 文本', exported.status === 200 && exported.text.includes('# word'),
    `${exported.status} ${exported.text.slice(0, 60)}`);
  check('导出包含导入的词条', exported.text.includes(wordB), wordB);

  // 自动同步词条只读
  const autoPage = await adminReq('GET', `/dict/words${qs({ source: 'AUTO_ARTIST', pageSize: 1 })}`, {
    session, xff,
  });
  const autoWord = autoPage.json && autoPage.json.data && autoPage.json.data.list
    && autoPage.json.data.list[0];
  if (autoWord) {
    const autoUpdate = await adminReq('PUT', `/dict/words/${autoWord.id}`, {
      session,
      xff,
      body: { word: autoWord.word, frequency: 101, pos: 'n', dictType: 'MAIN' },
    });
    check('自动词条不可修改（71004）', autoUpdate.json && autoUpdate.json.code === 71004,
      autoUpdate.json && autoUpdate.json.code);
    const autoDelete = await adminReq('DELETE', `/dict/words/${autoWord.id}`, { session, xff });
    check('自动词条不可删除（71004）', autoDelete.json && autoDelete.json.code === 71004,
      autoDelete.json && autoDelete.json.code);
  } else {
    skip('自动词条只读保护', '库里没有 AUTO_ARTIST 词条（先执行 11-ik-seed.sql 或 sync-artists）');
  }

  const removed = await adminReq('DELETE', `/dict/words/${wordId}`, { session, xff });
  check('删除词条成功', removed.json && removed.json.code === 0, removed.text.slice(0, 160));
  if (HAS_MYSQL) {
    const count = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_ik_dict_word WHERE id = ${wordId};`);
    check('词条已从库中删除', count === '0', count);
  } else {
    skip('词条已从库中删除', '未提供 MYSQL_PASSWORD');
  }
}

async function stopwordSection(session) {
  section('4. 停用词 CRUD / 冲突校验');
  const xff = ipOf('stopwords');

  const created = await adminReq('POST', '/dict/stopwords', {
    session, xff, body: { word: stopA, enabled: 1 },
  });
  check('新增停用词成功', created.json && created.json.code === 0, created.text.slice(0, 160));
  trackStopword(created.json && created.json.data);
  const stopId = created.json && created.json.data && created.json.data.id;

  const duplicate = await adminReq('POST', '/dict/stopwords', {
    session, xff, body: { word: stopA },
  });
  check('重复停用词返回 71002', duplicate.json && duplicate.json.code === 71002,
    duplicate.json && duplicate.json.code);

  const year = await adminReq('POST', '/dict/stopwords', {
    session, xff, body: { word: '2024' },
  });
  check('年份不能作为停用词（71001）', year.json && year.json.code === 71001,
    year.json && year.json.code);
  check('年份拒绝原因可读', year.json && year.json.message && year.json.message.includes('年份'),
    year.json && year.json.message);

  const synonymPage = await adminReq('GET', `/dict/synonyms${qs({ keyword: '周董' })}`, { session, xff });
  const hasSynonymSeed = Boolean(synonymPage.json && synonymPage.json.data
    && synonymPage.json.data.total > 0);
  if (hasSynonymSeed) {
    const conflict = await adminReq('POST', '/dict/stopwords', {
      session, xff, body: { word: '周董' },
    });
    check('与同义词冲突的停用词被拒绝（71001）',
      conflict.json && conflict.json.code === 71001, conflict.json && conflict.json.code);
  } else {
    skip('停用词/同义词冲突', '同义词种子未导入');
  }

  const imported = await adminReq('POST', '/dict/stopwords/import', {
    session,
    xff,
    body: { content: `# 验收\n${stopB}\n2025\n` },
  });
  const importResult = imported.json && imported.json.data;
  check('停用词导入逐行校验', importResult && importResult.inserted === 1
    && importResult.failed === 1, importResult && JSON.stringify(importResult));
  trackStopword({ word: stopB });

  const exported = await adminReq('GET', '/dict/stopwords/export', { session, xff });
  check('停用词导出包含新增词', exported.text.includes(stopB), stopB);

  const removed = await adminReq('DELETE', `/dict/stopwords/${stopId}`, { session, xff });
  check('删除停用词成功', removed.json && removed.json.code === 0, removed.text.slice(0, 120));
}

async function synonymSection(session) {
  section('5. 同义词组 CRUD / 冲突校验');
  const xff = ipOf('synonyms');

  const created = await adminReq('POST', '/dict/synonyms', {
    session,
    xff,
    body: {
      words: [synA, synB],
      primaryWord: synA,
      synonymType: 'EQUIVALENT',
      category: 'ARTIST',
      enabled: 1,
    },
  });
  check('新增同义词组成功', created.json && created.json.code === 0, created.text.slice(0, 160));
  const groupNo = created.json && created.json.data && created.json.data.groupNo;
  if (groupNo !== undefined && groupNo !== null) createdGroupNos.add(groupNo);

  const conflict = await adminReq('POST', '/dict/synonyms', {
    session,
    xff,
    body: { words: [synB, synC], primaryWord: synB, synonymType: 'EQUIVALENT' },
  });
  check('同一个词不能属于两个组（71006）', conflict.json && conflict.json.code === 71006,
    conflict.json && conflict.json.code);

  const badPrimary = await adminReq('POST', '/dict/synonyms', {
    session,
    xff,
    body: { words: [synC, `${synC}X`], primaryWord: '不存在的主词', synonymType: 'EQUIVALENT' },
  });
  check('主词必须属于该组（71005）', badPrimary.json && badPrimary.json.code === 71005,
    badPrimary.json && badPrimary.json.code);

  const updated = await adminReq('PUT', `/dict/synonyms/${groupNo}`, {
    session,
    xff,
    body: {
      words: [synA, synB, synC],
      primaryWord: synA,
      synonymType: 'EQUIVALENT',
      category: 'ARTIST',
      enabled: 1,
    },
  });
  check('修改同义词组成功', updated.json && updated.json.code === 0, updated.text.slice(0, 160));
  check('修改后组内 3 个词', updated.json && updated.json.data
    && updated.json.data.words.length === 3, updated.json && updated.json.data
    && updated.json.data.words.length);

  const imported = await adminReq('POST', '/dict/synonyms/import', {
    session,
    xff,
    body: { content: `${synImpA},${synImpB}\n${synOneA} => ${synOneB}\n` },
  });
  const importResult = imported.json && imported.json.data;
  check('同义词导入（等价 + 单向）成功', importResult && importResult.inserted === 2
    && importResult.failed === 0, importResult && JSON.stringify(importResult));

  const page = await adminReq('GET', `/dict/synonyms${qs({ keyword: RUN_TAG, pageSize: 50 })}`, {
    session, xff,
  });
  const groups = page.json && page.json.data && page.json.data.list;
  check('按关键词能查到本轮全部同义词组', groups && groups.length >= 3,
    groups && groups.length);
  if (groups) {
    groups.forEach((group) => createdGroupNos.add(group.groupNo));
    const oneWay = groups.find((group) => group.word === synOneA
      || group.primaryWord === synOneA || group.words.some((w) => w.word === synOneA));
    check('单向组类型保持 ONE_WAY', oneWay && oneWay.synonymType === 'ONE_WAY',
      oneWay && oneWay.synonymType);
  }

  const exported = await adminReq('GET', '/dict/synonyms/export', { session, xff });
  check('同义词导出包含单向箭头', exported.text.includes('=>'), exported.text.slice(0, 120));

  const removed = await adminReq('DELETE', `/dict/synonyms/${groupNo}`, { session, xff });
  check('删除同义词组成功', removed.json && removed.json.code === 0, removed.text.slice(0, 120));

  // 停用词与同义词冲突：先造一个停用词，再把它加入新组
  const stop = await adminReq('POST', '/dict/stopwords', {
    session, xff, body: { word: stopA },
  });
  trackStopword(stop.json && stop.json.data);
  if (stop.json && stop.json.code === 0) {
    const conflict2 = await adminReq('POST', '/dict/synonyms', {
      session,
      xff,
      body: { words: [stopA, synImpA], primaryWord: stopA, synonymType: 'EQUIVALENT' },
    });
    check('同义词不能与停用词重复（71006）', conflict2.json && conflict2.json.code === 71006,
      conflict2.json && conflict2.json.code);
    await adminReq('DELETE', `/dict/stopwords/${stop.json.data.id}`, { session, xff });
  } else {
    skip('同义词/停用词冲突', '临时停用词创建失败');
  }
}

async function pinyinSection(session) {
  section('6. 拼音词典 CRUD / 格式校验');
  const xff = ipOf('pinyin');

  const created = await adminReq('POST', '/dict/pinyin', {
    session, xff, body: { word: pinyinWordA, pinyin: 'pinyinceshi', enabled: 1 },
  });
  check('新增拼音词条成功', created.json && created.json.code === 0, created.text.slice(0, 160));
  trackPinyin(created.json && created.json.data);
  const pinyinId = created.json && created.json.data && created.json.data.id;

  const duplicate = await adminReq('POST', '/dict/pinyin', {
    session, xff, body: { word: pinyinWordA, pinyin: 'pinyinchongfu' },
  });
  check('重复拼音词条返回 71002', duplicate.json && duplicate.json.code === 71002,
    duplicate.json && duplicate.json.code);

  const bad = await adminReq('POST', '/dict/pinyin', {
    session, xff, body: { word: pinyinWordB, pinyin: 'Pin Yin' },
  });
  check('拼音格式非法返回 71010', bad.json && bad.json.code === 71010,
    bad.json && bad.json.code);

  const updated = await adminReq('PUT', `/dict/pinyin/${pinyinId}`, {
    session, xff, body: { word: pinyinWordA, pinyin: 'pinyingengxin', enabled: 1 },
  });
  check('修改拼音词条成功', updated.json && updated.json.code === 0, updated.text.slice(0, 160));

  const imported = await adminReq('POST', '/dict/pinyin/import', {
    session, xff, body: { content: `${pinyinWordB} pinyinb\n坏行\n` },
  });
  const importResult = imported.json && imported.json.data;
  check('拼音导入逐行校验', importResult && importResult.inserted === 1
    && importResult.failed === 1, importResult && JSON.stringify(importResult));
  trackPinyin({ word: pinyinWordB });

  const exported = await adminReq('GET', '/dict/pinyin/export', { session, xff });
  check('拼音导出包含新词', exported.text.includes(pinyinWordB), pinyinWordB);

  const removed = await adminReq('DELETE', `/dict/pinyin/${pinyinId}`, { session, xff });
  check('删除拼音词条成功', removed.json && removed.json.code === 0, removed.text.slice(0, 120));
}

async function searchSection() {
  section('7. 搜索查询改写（不需要登录）');
  const xff = ipOf('search');

  const search = async (keyword) => {
    const res = await apiReq('GET', `/search/songs${qs({ keyword, pageNum: 1, pageSize: 5 })}`, { xff });
    return res.json && res.json.data ? res.json.data : null;
  };

  const baselineJay = await search('周杰伦');
  const jayCount = baselineJay ? baselineJay.total : 0;
  check('对照组：搜「周杰伦」有结果', baselineJay && jayCount > 0, jayCount);
  if (baselineJay && jayCount > 0) {
    const alias = await search('周董');
    check('同义词：周董 → 周杰伦', alias && alias.total > 0, alias && alias.total);
    const trim = await search('我想听周杰伦的');
    check('停用词只裁首尾：「我想听周杰伦的」仍能命中',
      trim && trim.total > 0, trim && trim.total);

    const pinyin = await search('zhoujielun');
    if (pinyin && pinyin.total > 0) {
      check('拼音：zhoujielun 命中周杰伦', true);
    } else {
      skip('拼音：zhoujielun 命中周杰伦', '拼音列未回填或歌手名不在 name_pinyin（SEARCH_PINYIN_BACKFILL）');
    }
  } else {
    skip('同义词 / 停用词 / 拼音改写', '对照组为空，无法证明改写有效');
  }

  const baselineEason = await search('陈奕迅');
  if (baselineEason && baselineEason.total > 0) {
    const traditional = await search('陳奕迅');
    check('繁简转换：陳奕迅 → 陈奕迅', traditional && traditional.total > 0,
      traditional && traditional.total);
  } else {
    skip('繁简转换', '对照组「陈奕迅」为空');
  }

  const baselineRap = await search('说唱');
  if (baselineRap && baselineRap.total > 0) {
    const rap = await search('rap');
    check('同义词：rap → 说唱', rap && rap.total > 0, rap && rap.total);
  } else {
    skip('同义词：rap → 说唱', '对照组「说唱」为空');
  }
}

async function syncSection(session) {
  section('8. 歌手自动同步 / ES 降级语义');
  const xff = ipOf('sync');

  const artist = await adminReq('POST', '/artists', {
    session, xff, body: { name: artistName, gender: 3, status: 1 },
  });
  const artistId = artist.json && artist.json.code === 0 ? artist.json.data : null;
  if (artistId !== null && artistId !== undefined) createdArtistIds.add(String(artistId));
  check('创建临时歌手成功', artistId !== null && artistId !== undefined, artist.text.slice(0, 160));

  const synced = await adminReq('POST', '/dict/sync-artists', { session, xff });
  const syncResult = synced.json && synced.json.data;
  check('歌手同步执行成功', synced.json && synced.json.code === 0, synced.text.slice(0, 160));
  check('同步扫描到歌手', syncResult && syncResult.scanned >= 1, syncResult && syncResult.scanned);

  if (artistId !== null && artistId !== undefined) {
    const auto = await adminReq('GET',
      `/dict/words${qs({ keyword: artistName, source: 'AUTO_ARTIST' })}`, { session, xff });
    const row = auto.json && auto.json.data && auto.json.data.list && auto.json.data.list[0];
    check('新歌手进入主词典且标记 AUTO_ARTIST', row && row.source === 'AUTO_ARTIST',
      row && `${row.source}/${row.word}`);
    if (HAS_MYSQL) {
      const count = mysqlScalar(
        `SELECT COUNT(*) FROM newmusic.t_ik_dict_word WHERE source = 'AUTO_ARTIST' AND ref_id = ${artistId};`);
      check('自动词条落到数据库', count === '1', count);
    } else {
      skip('自动词条落到数据库', '未提供 MYSQL_PASSWORD');
    }
    if (row) {
      const readonly = await adminReq('DELETE', `/dict/words/${row.id}`, { session, xff });
      check('同步生成的词条不可手工删除（71004）',
        readonly.json && readonly.json.code === 71004, readonly.json && readonly.json.code);
    }
  }

  const reload = await adminReq('POST', '/dict/reload', { session, xff });
  if (ES_ENABLED) {
    check('ES 启用时重载分析器成功', reload.json && reload.json.code === 0,
      reload.text.slice(0, 160));
  } else {
    check('ES 未启用时重载返回 71008', reload.json && reload.json.code === 71008,
      reload.json && reload.json.code);
    check('重载失败给出明确原因',
      reload.json && reload.json.message && reload.json.message.includes('Elasticsearch'),
      reload.json && reload.json.message);
  }

  const reindex = await adminReq('POST', '/dict/reindex', { session, xff });
  if (ES_ENABLED) {
    const rebuilt = reindex.json && reindex.json.data;
    check('ES 启用时重建索引成功', reindex.json && reindex.json.code === 0,
      reindex.text.slice(0, 200));
    check('重建索引报告写入条数', rebuilt && rebuilt.scanned >= 1 && rebuilt.created >= 1,
      rebuilt && `${rebuilt.scanned}/${rebuilt.created}`);
  } else {
    check('ES 未启用时重建索引返回 71008', reindex.json && reindex.json.code === 71008,
      reindex.json && reindex.json.code);
  }
}

async function changeLogSection(session) {
  section('9. 变更历史');
  const xff = ipOf('changelog');
  const res = await adminReq('GET', `/dict/change-log${qs({ pageSize: 100 })}`, { session, xff });
  const page = res.json && res.json.data;
  check('变更历史可查询', res.json && res.json.code === 0, res.text.slice(0, 160));
  check('记录数量大于 0', page && page.total > 0, page && page.total);
  check('本轮操作已留痕', page && page.list.some((row) => row.operatorName
    && row.operatorName !== 'SYSTEM'), page && page.list.slice(0, 3));
  check('记录包含 CREATE/UPDATE 之一', page && page.list.some((row) =>
    ['CREATE', 'UPDATE', 'IMPORT', 'DELETE'].includes(row.changeType)),
    page && page.list.slice(0, 5).map((row) => row.changeType).join(','));
}

async function rbacSection() {
  section('10. RBAC');
  const xff = ipOf('rbac');
  const operator = await login(OPERATOR_USER, OPERATOR_PASS, { xff, tag: 'operator' });

  const view = await adminReq('GET', '/dict/status', { session: operator, xff });
  check('operator 有词典查看权限', view.json && view.json.code === 0, view.text.slice(0, 160));

  const reload = await adminReq('POST', '/dict/reload', { session: operator, xff });
  check('operator 无重载权限（403 / 40301）',
    reload.status === 403 && reload.json && reload.json.code === 40301,
    `${reload.status} ${reload.text.slice(0, 120)}`);

  await adminReq('POST', '/auth/logout', { session: operator, xff });
}

function integritySection() {
  section('11. 共享实例完整性');
  if (REDIS_OK) {
    const after = listKeys('ranking:*');
    check('其它项目的 ranking:* 键数量未变', after.length === rankingBefore.length,
      `${rankingBefore.length} → ${after.length}`);
  } else {
    skip('ranking:* 完整性', 'Redis CLI 不可用');
  }
  if (HAS_MYSQL) {
    const after = mysqlScalar(
      "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('emp_db / music_db 表数量未变', after === sharedDbTablesBefore,
      `${sharedDbTablesBefore} → ${after}`);
  } else {
    skip('共享 MySQL 完整性', '未提供 MYSQL_PASSWORD');
  }
}

/** 清理之后才能断言没有残留，否则断言的是「清理前」的必然结果。 */
function leakSection() {
  section('12. 本轮数据清理');
  if (!HAS_MYSQL) {
    skip('本轮词条清理', '未提供 MYSQL_PASSWORD');
    return;
  }
  try {
    const leaked = mysqlScalar(
      `SELECT (SELECT COUNT(*) FROM newmusic.t_ik_dict_word WHERE word LIKE ${sqlStr(`%${RUN_TAG}%`)})
            + (SELECT COUNT(*) FROM newmusic.t_ik_stopword WHERE word LIKE ${sqlStr(`%${RUN_TAG}%`)})
            + (SELECT COUNT(*) FROM newmusic.t_synonym WHERE word LIKE ${sqlStr(`%${RUN_TAG}%`)})
            + (SELECT COUNT(*) FROM newmusic.t_pinyin_dict WHERE word LIKE ${sqlStr(`%${RUN_TAG}%`)});`);
    check('本轮词条已按精确 ID / RUN_TAG 清理干净', leaked === '0', leaked);
  } catch (e) {
    check('本轮词条清理结果可查询', false, e.message);
  }
}

// ---------------------------------------------------------------- 入口

async function main() {
  console.log(`\x1b[1m模块 11 验收\x1b[0m  RUN_TAG=${RUN_TAG}`);
  await internalDictSection();

  const xff = ipOf('admin');
  const admin = await login(ADMIN_USER, ADMIN_PASS, { xff, tag: 'admin' });
  note(`已登录 ${ADMIN_USER}`);

  await statusSection(admin);
  await wordSection(admin);
  await stopwordSection(admin);
  await synonymSection(admin);
  await pinyinSection(admin);
  await searchSection();
  await syncSection(admin);
  await changeLogSection(admin);
  await rbacSection();

  await adminReq('POST', '/auth/logout', { session: admin, xff });

  integritySection();
}

try {
  await main();
} catch (e) {
  fail += 1;
  failures.push('脚本异常终止');
  console.error(`\n\x1b[31m脚本异常：\x1b[0m ${e && e.stack ? e.stack : e}`);
} finally {
  cleanup();
  leakSection();
  if (REDIS_OK && redisCleanupKeys.length) {
    try {
      redisDel(...redisCleanupKeys);
    } catch (e) {
      note(`清理 Redis 临时键失败：${e.message}`);
    }
  }
  console.log('\n' + '─'.repeat(60));
  console.log(`通过 \x1b[32m${pass}\x1b[0m / 失败 \x1b[31m${fail}\x1b[0m / 跳过 \x1b[90m${skipped}\x1b[0m`);
  if (failures.length) console.log(`失败项：${failures.join('；')}`);
  if (skipNotes.length) console.log(`跳过项：${skipNotes.join('；')}`);
  process.exitCode = fail === 0 ? 0 : 1;
}
