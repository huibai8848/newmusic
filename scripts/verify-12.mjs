#!/usr/bin/env node
/**
 * 模块 12 验收脚本 —— 播放体验升级（音质 / 倍速 / 设置 / 设备 / 跨设备续播 / PWA 接口面）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-12.mjs
 *
 * 前置：
 *   * 应用已启动（8081）；
 *   * 已执行 sql/12-play-seed.sql（或 schema.sql 的第 28 节）；
 *   * 本机 Redis 6379 / MySQL 3306 可连（连不上时相应用例跳过，不会让脚本整体失败）。
 *
 * 覆盖：
 *   * `GET /player/qualities` 公开可用性、游客/VIP/音源的可用性优先级；
 *   * `GET/PUT /play/settings` 默认值、局部更新、7 档倍速、8 类非法值 30061；
 *   * `ml:play:setting:*` L2 缓存与 `ml:play:quality:*` 同步；
 *   * 播放地址权限降级：LOGIN_REQUIRED / VIP_REQUIRED / NO_SOURCE；
 *   * `POST/GET /play/progress` 跨设备进度、新写入覆盖旧值、fromCurrentDevice；
 *   * `GET/DELETE /api/v1/devices` 设备登记、类型规范化、单台/全部登出、30062；
 *   * 未登录访问个人资源 → 401；
 *   * Redis 键前缀与共享实例完整性（ranking:* 与其它库表数量前后一致）。
 *
 * 安全约定：
 *   * MySQL 语句全部 `newmusic.` 限定，只按本次注册用户的精确 ID 清理自己造的数据；
 *   * Redis 只用 `ml:` 前缀键，不执行 FLUSHDB/FLUSHALL，只 KEYS 不 SCAN；
 *   * 每节使用随机合成 X-Forwarded-For，隔离登录失败计数与限流；
 *   * VIP 标记只改本次注册的验收账号，并在 finally 中恢复。
 */

import { spawnSync } from 'node:child_process';

// ---------------------------------------------------------------- 配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const PASS_WORD = 'Aa123456';
const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;

/**
 * 种子曲库里稳定存在的歌曲（晴天）；DB 可连时会重新挑选。
 *
 * <p><b>始终用字符串保存 ID</b>：19 位雪花 ID 超出 JS Number 安全整数范围，
 * 一旦 `Number()` 就会丢精度（…5001 → …5000），后续 404 / 找不到历史行。
 * 后端 Jackson 把 Long 序列化成字符串，正好可以原样比较。
 */
let SONG_ID = '1000000000000005001';
let SONG_ONLY_STANDARD = null;
let SONG_WITH_LOSSLESS = null;

const syntheticIp = () => `10.97.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
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
const redisExists = (key) => redis('exists', key) === '1';
const redisGet = (key) => (redisExists(key) ? redis('get', key) : null);
const redisTtl = (key) => Number(redis('ttl', key));

let REDIS_OK = false;
try {
  REDIS_OK = redis('ping') === 'PONG';
} catch (e) {
  note(`Redis CLI 不可用，缓存相关断言会跳过：${e.message}`);
}

const rankingBefore = REDIS_OK ? listKeys('ranking:*') : [];
/** 快照：其它模块（种子用户的播放记录等）本来就有 ml:play:* 键，不能一概当成自己的 */
const playKeysBefore = REDIS_OK ? listKeys('ml:play:*') : [];
const otherDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- HTTP

async function http(method, path, { token, body, xff, headers } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (xff) h['X-Forwarded-For'] = xff;
  if (token) h.Authorization = `Bearer ${token}`;
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON 响应保留 text */
  }
  return { status: res.status, json, text };
}

const api = (method, path, opts = {}) => http(method, path, opts);
const codeOf = (res) => (res.json && typeof res.json.code === 'number' ? res.json.code : -1);
const dataOf = (res) => (res.json ? res.json.data : null);

function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ---------------------------------------------------------------- 数据准备与清理

async function registerAndLogin(label) {
  const username = `v12${label}${RUN_TAG}`;
  const reg = await api('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username, password: PASS_WORD, nickname: `验收12${label}${RUN_TAG}` },
  });
  if (codeOf(reg) !== 0 || !dataOf(reg)) {
    throw new Error(`注册用户 ${label} 失败：code=${codeOf(reg)} ${reg.json?.message || reg.text}`);
  }
  const login = await api('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account: username, password: PASS_WORD },
  });
  if (codeOf(login) !== 0 || !dataOf(login) || !dataOf(login).accessToken) {
    throw new Error(`登录用户 ${label} 失败：code=${codeOf(login)} ${login.json?.message || login.text}`);
  }
  return {
    id: String(dataOf(login).user.id),
    username,
    token: dataOf(login).accessToken,
  };
}

const createdUsers = [];
const createdDeviceIds = [];

function cleanup() {
  // Redis：只删本次验收产生的键，全部以 ml: 开头
  if (REDIS_OK) {
    const keys = new Set();
    for (const user of createdUsers) {
      keys.add(`ml:play:setting:${user.id}`);
      keys.add(`ml:play:quality:${user.id}`);
      keys.add(`ml:device:${user.id}`);
      keys.add(`ml:play:user:${user.id}:recent`);
      keys.add(`ml:play:progress:${user.id}:${SONG_ID}`);
      if (SONG_ONLY_STANDARD) keys.add(`ml:play:progress:${user.id}:${SONG_ONLY_STANDARD}`);
      keys.add(`ml:play:dedup:u:${user.id}:${SONG_ID}`);
      for (const deviceId of createdDeviceIds) {
        keys.add(`ml:play:dedup:d:${deviceId}:${SONG_ID}`);
      }
    }
    try {
      if (keys.size) redis('del', ...keys);
    } catch (e) {
      note(`Redis 清理失败（不影响断言）：${e.message}`);
    }
  }
  // MySQL：只按本次用户的精确 ID 删除
  if (HAS_MYSQL && createdUsers.length) {
    const ids = createdUsers.map((u) => u.id).join(',');
    try {
      mysql(`
        DELETE FROM newmusic.t_user_play_setting WHERE user_id IN (${ids});
        DELETE FROM newmusic.t_user_device WHERE user_id IN (${ids});
        DELETE FROM newmusic.t_play_history WHERE user_id IN (${ids});
        UPDATE newmusic.t_user SET vip_level = 0, vip_expire_time = NULL
         WHERE id IN (${ids}) AND username LIKE 'v12%';
      `);
    } catch (e) {
      note(`MySQL 清理失败（不影响断言）：${e.message}`);
    }
  }
}

// ---------------------------------------------------------------- 主流程

async function main() {
  section('§00 前置检查');
  // actuator 端点未暴露，这里用公开接口探活（能返回 JSON 即可）
  let reachable = false;
  try {
    const res = await fetch(`${API_BASE}/player/qualities`);
    const text = await res.text();
    reachable = res.ok && text.includes('"code"');
  } catch {
    reachable = false;
  }
  check('后端 API 可访问', reachable);

  if (HAS_MYSQL) {
    const picked = mysqlScalar(`
      SELECT id FROM newmusic.t_song
       WHERE deleted = 0 AND copyright_status = 1
         AND audio_standard IS NOT NULL AND audio_standard <> ''
         AND audio_high IS NOT NULL AND audio_high <> ''
       ORDER BY id LIMIT 1;`);
    if (picked) SONG_ID = String(picked);
    const onlyStandard = mysqlScalar(`
      SELECT id FROM newmusic.t_song
       WHERE deleted = 0 AND copyright_status = 1
         AND audio_standard IS NOT NULL AND audio_standard <> ''
         AND (audio_high IS NULL OR audio_high = '')
       ORDER BY id LIMIT 1;`);
    if (onlyStandard) SONG_ONLY_STANDARD = String(onlyStandard);
    const withLossless = mysqlScalar(`
      SELECT id FROM newmusic.t_song
       WHERE deleted = 0 AND copyright_status = 1
         AND audio_lossless IS NOT NULL AND audio_lossless <> ''
       ORDER BY id LIMIT 1;`);
    if (withLossless) SONG_WITH_LOSSLESS = String(withLossless);
    note(`歌曲取样：high+standard=${SONG_ID}；仅 standard=${SONG_ONLY_STANDARD ?? '无'}；含无损=${SONG_WITH_LOSSLESS ?? '无'}`);
  } else {
    skip('歌曲取样', '未设置 MYSQL_PASSWORD，使用种子歌曲 ID');
  }

  const userA = await registerAndLogin('a');
  const userB = await registerAndLogin('b');
  createdUsers.push(userA, userB);

  // ---------------------------------------------------------------- §01
  section('§01 公开音质列表 GET /player/qualities');
  const qualitiesGuest = await api('GET', '/player/qualities', { xff: syntheticIp() });
  const qg = dataOf(qualitiesGuest);
  check('游客可访问（无需登录）', codeOf(qualitiesGuest) === 0 && qg !== null, qualitiesGuest.status);
  check('返回 3 档音质，从高到低', Array.isArray(qg?.qualities) && qg.qualities.length === 3
    && qg.qualities.map((x) => x.code).join(',') === 'lossless,high,standard', qg?.qualities?.map((x) => x.code));
  const qgLossless = qg?.qualities?.find((x) => x.code === 'lossless');
  const qgHigh = qg?.qualities?.find((x) => x.code === 'high');
  check('游客 vip=false', qg?.vip === false);
  check('无损标 vipOnly 且游客不可用', qgLossless?.vipOnly === true && qgLossless?.available === false
    && /VIP/.test(qgLossless?.unavailableReason || ''), qgLossless);
  check('高清/标准对游客可用', qgHigh?.available === true && qg?.qualities?.find((x) => x.code === 'standard')?.available === true);
  check('游客推荐音质为 standard', qg?.recommended === 'standard', qg?.recommended);
  check('未传 songId 时 songId 为 null', qg?.songId == null);

  const qualitiesSong = dataOf(await api('GET', `/player/qualities${qs({ songId: SONG_ID })}`, { xff: syntheticIp() }));
  check('按歌曲查询返回同一首歌曲', String(qualitiesSong?.songId) === SONG_ID, qualitiesSong?.songId);
  check('歌曲有高清音源时 high 可用', qualitiesSong?.qualities?.find((x) => x.code === 'high')?.available === true);

  // ---------------------------------------------------------------- §02
  section('§02 播放设置鉴权与默认值');
  const noAuth = await api('GET', '/play/settings');
  check('未登录读取设置 → HTTP 401 / 40101', noAuth.status === 401 && codeOf(noAuth) === 40101, `${noAuth.status}/${codeOf(noAuth)}`);

  const defaults = dataOf(await api('GET', '/play/settings', { token: userA.token }));
  check('默认音质 high', defaults?.defaultQuality === 'high', defaults?.defaultQuality);
  check('默认播放模式 loop', defaults?.playMode === 'loop', defaults?.playMode);
  check('默认自动降质开启', defaults?.autoQuality === 1, defaults?.autoQuality);
  check('默认省流量关闭 / 倍速 1.00', defaults?.dataSaver === 0 && Number(defaults?.playbackRate) === 1);
  check('默认歌词模式 original_translation', defaults?.lyricMode === 'original_translation', defaults?.lyricMode);
  check('返回的 userId 是本人', String(defaults?.userId) === userA.id);

  if (HAS_MYSQL) {
    const rows = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_user_play_setting WHERE user_id = ${userA.id};`);
    check('只读默认值不落库（首次 PUT 才插入）', rows === '0', rows);
  } else {
    skip('只读默认值不落库', '未设置 MYSQL_PASSWORD');
  }

  // ---------------------------------------------------------------- §03
  section('§03 播放设置局部更新与 7 档倍速');
  const updated = dataOf(await api('PUT', '/play/settings', {
    token: userA.token,
    xff: syntheticIp(),
    body: {
      defaultQuality: 'lossless',
      playMode: 'single',
      autoQuality: 0,
      dataSaver: 1,
      lyricMode: 'original',
      lyricOffset: 800,
      theme: 'dark',
      playbackRate: 1.25,
    },
  }));
  check('PUT 返回 code=0 并回显新值', updated?.defaultQuality === 'lossless'
    && updated?.playMode === 'single' && updated?.autoQuality === 0 && Number(updated?.playbackRate) === 1.25, updated);

  const partial = dataOf(await api('PUT', '/play/settings', {
    token: userA.token, xff: syntheticIp(), body: { playMode: 'shuffle' },
  }));
  check('局部更新不清空未传字段', partial?.playMode === 'shuffle' && partial?.defaultQuality === 'lossless'
    && partial?.lyricMode === 'original' && Number(partial?.playbackRate) === 1.25, partial);

  for (const rate of [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]) {
    const res = await api('PUT', '/play/settings', {
      token: userA.token, xff: syntheticIp(), body: { playbackRate: rate },
    });
    check(`倍速 ${rate.toFixed(2)} 可保存`, codeOf(res) === 0 && Number(dataOf(res)?.playbackRate) === rate,
      dataOf(res)?.playbackRate);
    if (HAS_MYSQL) {
      const stored = mysqlScalar(`SELECT playback_rate FROM newmusic.t_user_play_setting WHERE user_id = ${userA.id};`);
      check(`倍速 ${rate.toFixed(2)} 落库为两位小数`, Number(stored) === rate && /^\d+\.\d{2}$/.test(stored), stored);
    }
  }

  if (HAS_MYSQL) {
    const row = mysqlScalar(`SELECT CONCAT(default_quality, '|', play_mode, '|', auto_quality, '|', data_saver, '|', lyric_offset, '|', theme)
                               FROM newmusic.t_user_play_setting WHERE user_id = ${userA.id};`);
    check('DB 行保留全部已更新字段', row === 'lossless|shuffle|0|1|800|dark', row);
  } else {
    skip('DB 行字段核对', '未设置 MYSQL_PASSWORD');
  }

  // ---------------------------------------------------------------- §04
  section('§04 非法设置一律 30061');
  const invalidCases = [
    ['非法音质 flac', { defaultQuality: 'flac' }],
    ['非法播放模式 random', { playMode: 'random' }],
    ['非法倍速 2.50', { playbackRate: 2.5 }],
    ['非法开关值 2', { autoQuality: 2 }],
    ['歌词偏移越界 5001', { lyricOffset: 5001 }],
    ['非法主题 blue', { theme: 'blue' }],
    ['非法 EQ 预设 metal', { equalizerPreset: 'metal' }],
    ['非法 EQ 频段非 JSON 数组', { equalizerBands: '60:+3' }],
  ];
  for (const [name, body] of invalidCases) {
    const res = await api('PUT', '/play/settings', { token: userA.token, xff: syntheticIp(), body });
    check(`${name} → 30061`, codeOf(res) === 30061, `${codeOf(res)} ${res.json?.message || ''}`);
  }
  if (HAS_MYSQL) {
    const row = mysqlScalar(`SELECT CONCAT(default_quality, '|', play_mode, '|', playback_rate)
                               FROM newmusic.t_user_play_setting WHERE user_id = ${userA.id};`);
    check('非法请求没有污染已保存的设置', row === 'lossless|shuffle|2.00', row);
  }

  if (REDIS_OK) {
    section('§05 设置缓存与音质键');
    const settingKey = `ml:play:setting:${userA.id}`;
    const qualityKey = `ml:play:quality:${userA.id}`;
    check('ml:play:setting:{userId} 已写入', redisExists(settingKey));
    const ttl = redisTtl(settingKey);
    // CacheService 会加抖动，上限放宽到 8 天；超过说明 TTL 配置写错了
    check('设置缓存 TTL 为正且不超过 8 天', ttl > 0 && ttl <= 8 * 24 * 3600, ttl);
    check('ml:play:quality:{userId} 同步为 lossless', redisGet(qualityKey) === 'lossless', redisGet(qualityKey));
    const ttlQuality = redisTtl(qualityKey);
    check('音质键永久（TTL=-1）', ttlQuality === -1, ttlQuality);
    check('缓存键全部在 ml: 前缀下', listKeys('play:setting:*').length === 0 && listKeys('play:quality:*').length === 0);
  } else {
    section('§05 设置缓存与音质键');
    skip('Redis 缓存断言', 'Redis CLI 不可用');
  }

  // ---------------------------------------------------------------- §06
  section('§06 播放地址权限降级');
  const urlGuestLossless = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'lossless' })}`, { xff: syntheticIp() }));
  check('游客请求无损 → 实际 standard', urlGuestLossless?.quality === 'standard' && urlGuestLossless?.requestedQuality === 'lossless',
    urlGuestLossless);
  check('降级原因为 LOGIN_REQUIRED 且有提示文案', urlGuestLossless?.downgraded === true
    && urlGuestLossless?.downgradeReason === 'LOGIN_REQUIRED' && Boolean(urlGuestLossless?.notice), urlGuestLossless?.downgradeReason);
  check('签发地址带 expires/sign', /[?&]expires=\d+/.test(urlGuestLossless?.url || '')
    && /[?&]sign=[0-9a-f]{16,}/i.test(urlGuestLossless?.url || ''), urlGuestLossless?.url);

  const urlGuestHigh = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'high' })}`, { xff: syntheticIp() }));
  check('游客请求高清 → standard + LOGIN_REQUIRED', urlGuestHigh?.quality === 'standard'
    && urlGuestHigh?.downgradeReason === 'LOGIN_REQUIRED', urlGuestHigh);

  const urlGuestStandard = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'standard' })}`, { xff: syntheticIp() }));
  check('游客请求标准音质不降级', urlGuestStandard?.quality === 'standard' && urlGuestStandard?.downgraded === false
    && (urlGuestStandard?.downgradeReason == null || urlGuestStandard?.downgradeReason === ''), urlGuestStandard);

  const urlUnknown = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'flac' })}`, { xff: syntheticIp() }));
  check('未知音质按 standard 处理且不算降级', urlUnknown?.quality === 'standard' && urlUnknown?.requestedQuality === 'standard'
    && urlUnknown?.downgraded === false, urlUnknown);

  const urlNoParam = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID })}`, { xff: syntheticIp() }));
  check('不传音质默认 standard', urlNoParam?.quality === 'standard' && urlNoParam?.downgraded === false, urlNoParam);

  const urlUserLossless = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'lossless' })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('非 VIP 请求无损 → high + VIP_REQUIRED', urlUserLossless?.quality === 'high'
    && urlUserLossless?.downgradeReason === 'VIP_REQUIRED' && Boolean(urlUserLossless?.notice), urlUserLossless);
  check('非 VIP 请求无损时 requestedQuality 保留 lossless', urlUserLossless?.requestedQuality === 'lossless');

  const urlUserHigh = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'high' })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('非 VIP 请求高清 → high 不降级', urlUserHigh?.quality === 'high' && urlUserHigh?.downgraded === false, urlUserHigh);

  const qualitiesUser = dataOf(await api('GET', `/player/qualities${qs({ songId: SONG_ID })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('非 VIP 的可用音质列表 vip=false、无损不可用', qualitiesUser?.vip === false
    && qualitiesUser?.qualities?.find((x) => x.code === 'lossless')?.available === false);

  if (SONG_ONLY_STANDARD) {
    const res = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ONLY_STANDARD, quality: 'high' })}`, {
      token: userA.token, xff: syntheticIp(),
    }));
    check('仅有标准音源时 high → standard + NO_SOURCE', res?.quality === 'standard'
      && res?.downgradeReason === 'NO_SOURCE', res);
  } else {
    skip('NO_SOURCE 降级（非 VIP）', '种子数据里没有仅标准音源的歌曲');
  }

  // ---------------------------------------------------------------- §07
  section('§07 VIP 音质权限（仅修改本次验收账号）');
  if (!HAS_MYSQL) {
    skip('VIP 相关断言', '未设置 MYSQL_PASSWORD');
  } else {
    mysql(`UPDATE newmusic.t_user SET vip_level = 1, vip_expire_time = DATE_ADD(NOW(), INTERVAL 1 DAY)
            WHERE id = ${userB.id} AND username = '${userB.username}';`);
    const vipSet = mysqlScalar(`SELECT vip_level FROM newmusic.t_user WHERE id = ${userB.id};`);
    check('验收账号 B 已标记为 VIP', vipSet === '1', vipSet);

    const vipQualities = dataOf(await api('GET', '/player/qualities', { token: userB.token, xff: syntheticIp() }));
    check('VIP 的可用音质列表 vip=true', vipQualities?.vip === true, vipQualities?.vip);
    check('VIP 未指定歌曲时无损可用', vipQualities?.qualities?.find((x) => x.code === 'lossless')?.available === true);

    const vipSongQualities = dataOf(await api('GET', `/player/qualities${qs({ songId: SONG_ID })}`, {
      token: userB.token, xff: syntheticIp(),
    }));
    const vipLossless = vipSongQualities?.qualities?.find((x) => x.code === 'lossless');
    if (SONG_WITH_LOSSLESS) {
      check('无损音源存在时 VIP 可选无损', vipLossless?.available === true);
    } else {
      check('无无损音源时 VIP 也不可用（原因指向音源）', vipLossless?.available === false
        && /音质/.test(vipLossless?.unavailableReason || ''), vipLossless);
    }

    const vipUrl = dataOf(await api('GET', `/player/url${qs({ songId: SONG_ID, quality: 'lossless' })}`, {
      token: userB.token, xff: syntheticIp(),
    }));
    if (SONG_WITH_LOSSLESS) {
      check('VIP 请求无损 → 下发 lossless 且不降级', vipUrl?.quality === 'lossless' && vipUrl?.downgraded === false, vipUrl);
    } else {
      check('VIP 请求无损但无音源 → high + NO_SOURCE', vipUrl?.quality === 'high'
        && vipUrl?.downgradeReason === 'NO_SOURCE', vipUrl);
    }
  }

  // ---------------------------------------------------------------- §08
  section('§08 播放上报与跨设备进度');
  const deviceA1 = `dev1-${RUN_TAG}`;
  const deviceA2 = `dev2-${RUN_TAG}`;
  createdDeviceIds.push(deviceA1, deviceA2);

  const record = await api('POST', '/play/record', {
    token: userA.token,
    xff: syntheticIp(),
    body: {
      songId: SONG_ID,
      quality: 'high',
      source: 'search',
      deviceId: deviceA1,
      deviceName: 'Chrome on Windows',
      deviceType: 'pwa',
    },
  });
  const rec = dataOf(record);
  check('播放开始上报成功', codeOf(record) === 0 && rec?.duplicated === false && rec?.playId != null, rec);
  const playId = rec?.playId;

  const dup = dataOf(await api('POST', '/play/record', {
    token: userA.token, xff: syntheticIp(),
    body: { songId: SONG_ID, quality: 'high', deviceId: deviceA1 },
  }));
  check('5 秒内重复上报标记 duplicated 且复用 playId', dup?.duplicated === true
    && String(dup?.playId) === String(playId), dup);

  const p1 = await api('POST', '/play/progress', {
    token: userA.token, xff: syntheticIp(),
    body: {
      songId: SONG_ID,
      playId,
      position: 42.5,
      duration: 269,
      deviceId: deviceA1,
      deviceName: 'Chrome on Windows',
      deviceType: 'pwa',
    },
  });
  check('进度上报成功', codeOf(p1) === 0, p1.json);

  const progressOwn = dataOf(await api('GET', `/play/progress${qs({ songId: SONG_ID, deviceId: deviceA1 })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('读取进度 found=true 且来自 Redis', progressOwn?.found === true && progressOwn?.source === 'REDIS', progressOwn);
  check('位置/时长/playId 正确', progressOwn?.position === 42.5 && progressOwn?.duration === 269
    && String(progressOwn?.playId) === String(playId), progressOwn);
  check('上报设备为 dev1', progressOwn?.deviceId === deviceA1, progressOwn?.deviceId);
  check('同设备读取 fromCurrentDevice=true', progressOwn?.fromCurrentDevice === true);
  check('服务端提供 updateTime（毫秒）', Number(progressOwn?.updateTime) > 0, progressOwn?.updateTime);

  const progressOther = dataOf(await api('GET', `/play/progress${qs({ songId: SONG_ID, deviceId: deviceA2 })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('另一台设备读取 fromCurrentDevice=false', progressOther?.fromCurrentDevice === false && progressOther?.found === true);

  const progressNoDevice = dataOf(await api('GET', `/play/progress${qs({ songId: SONG_ID })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('不传 deviceId 时不做「本机」判断', progressNoDevice?.fromCurrentDevice === false);

  await api('POST', '/play/progress', {
    token: userA.token, xff: syntheticIp(),
    body: { songId: SONG_ID, playId, position: 88, duration: 269, deviceId: deviceA2, deviceType: 'MOBILE_WEB' },
  });
  const progressNewest = dataOf(await api('GET', `/play/progress${qs({ songId: SONG_ID, deviceId: deviceA1 })}`, {
    token: userA.token, xff: syntheticIp(),
  }));
  check('新进度覆盖旧值（newest wins）', progressNewest?.position === 88 && progressNewest?.deviceId === deviceA2, progressNewest);
  check('来源设备变为 dev2 后 dev1 不再是本机', progressNewest?.fromCurrentDevice === false);

  if (HAS_MYSQL) {
    const stored = mysqlScalar(`SELECT last_position FROM newmusic.t_play_history
                                 WHERE id = ${playId} AND user_id = ${userA.id};`);
    check('DB last_position 同步为 88', Number(stored) === 88, stored);
  } else {
    skip('DB last_position 核对', '未设置 MYSQL_PASSWORD');
  }

  const guestProgressPost = await api('POST', '/play/progress', {
    xff: syntheticIp(), body: { songId: SONG_ID, position: 1 },
  });
  check('游客写入进度 → 401', guestProgressPost.status === 401 && codeOf(guestProgressPost) === 40101,
    `${guestProgressPost.status}/${codeOf(guestProgressPost)}`);
  const guestProgressGet = await api('GET', `/play/progress${qs({ songId: SONG_ID })}`);
  check('游客读取进度 → 401', guestProgressGet.status === 401 && codeOf(guestProgressGet) === 40101);
  const badSong = dataOf(await api('GET', `/player/url${qs({ songId: 999999999 })}`, { xff: syntheticIp() }));
  check('不存在的歌曲不签发地址', badSong === null, badSong);

  // ---------------------------------------------------------------- §09
  section('§09 设备列表与登出');
  const devices = dataOf(await api('GET', `/devices${qs({ deviceId: deviceA1 })}`, { token: userA.token, xff: syntheticIp() }));
  check('设备列表包含两台设备', Array.isArray(devices) && devices.length === 2, devices?.length);
  const d1 = devices?.find((x) => x.deviceId === deviceA1);
  const d2 = devices?.find((x) => x.deviceId === deviceA2);
  check('设备类型规范化为大写 PWA', d1?.deviceType === 'PWA', d1?.deviceType);
  check('设备名与 IP 已登记', d1?.deviceName === 'Chrome on Windows' && Boolean(d1?.lastIp), d1);
  check('按最后活跃倒序（dev2 在 dev1 之前）', devices?.[0]?.deviceId === deviceA2, devices?.map((x) => x.deviceId));
  check('当前设备被标记 current', d1?.current === true && d2?.current === false);

  const logoutOne = await api('DELETE', `/devices/${deviceA1}`, { token: userA.token, xff: syntheticIp() });
  check('登出单台设备成功', codeOf(logoutOne) === 0, logoutOne.json);
  const afterLogout = dataOf(await api('GET', `/devices${qs({ deviceId: deviceA1 })}`, { token: userA.token, xff: syntheticIp() }));
  check('登出后列表不再包含该设备', Array.isArray(afterLogout) && !afterLogout.some((x) => x.deviceId === deviceA1), afterLogout);

  const logoutAgain = await api('DELETE', `/devices/${deviceA1}`, { token: userA.token, xff: syntheticIp() });
  check('重复登出 → 30062', codeOf(logoutAgain) === 30062, codeOf(logoutAgain));
  const logoutUnknown = await api('DELETE', `/devices/not-exist-${RUN_TAG}`, { token: userA.token, xff: syntheticIp() });
  check('登出不存在的设备 → 30062', codeOf(logoutUnknown) === 30062, codeOf(logoutUnknown));

  // 通过进度上报重新激活 dev1，再验证「登出全部」
  await api('POST', '/play/progress', {
    token: userA.token, xff: syntheticIp(),
    body: { songId: SONG_ID, playId, position: 90, duration: 269, deviceId: deviceA1 },
  });
  const reactivated = dataOf(await api('GET', `/devices${qs({ deviceId: deviceA1 })}`, { token: userA.token, xff: syntheticIp() }));
  check('再次上报会重新登记已登出的设备', reactivated?.some((x) => x.deviceId === deviceA1), reactivated);

  const logoutAll = await api('DELETE', '/devices/all', { token: userA.token, xff: syntheticIp() });
  check('登出全部返回受影响台数 ≥2', codeOf(logoutAll) === 0 && Number(dataOf(logoutAll)) >= 2, dataOf(logoutAll));
  const afterAll = dataOf(await api('GET', '/devices', { token: userA.token, xff: syntheticIp() }));
  check('登出全部后列表为空', Array.isArray(afterAll) && afterAll.length === 0, afterAll);

  const guestDevices = await api('GET', '/devices');
  check('游客访问设备列表 → 401', guestDevices.status === 401 && codeOf(guestDevices) === 40101);

  // ---------------------------------------------------------------- §10
  section('§10 Redis 键与共享实例完整性');
  if (REDIS_OK) {
    check('不存在未加 ml: 前缀的播放设置键', listKeys('play:setting:*').length === 0 && listKeys('device:*').length === 0);
    check('ml:play:progress:{userId}:{songId} 是 Hash',
      redis('type', `ml:play:progress:${userA.id}:${SONG_ID}`) === 'hash');
    check('进度 Hash 记录了来源设备', redis('hget', `ml:play:progress:${userA.id}:${SONG_ID}`, 'deviceId') === deviceA1);
    const deviceKey = `ml:device:${userA.id}`;
    check('登出全部后删除 ml:device:{userId}', !redisExists(deviceKey), deviceKey);
    const addedPlayKeys = listKeys('ml:play:*').filter((k) => !playKeysBefore.includes(k));
    check('本次新增的 ml:play:* 键都属于验收用户',
      addedPlayKeys.every((k) => k.includes(`:${userA.id}`) || k.includes(`:${userB.id}`)) && addedPlayKeys.length > 0,
      addedPlayKeys);
  } else {
    skip('Redis 键断言', 'Redis CLI 不可用');
  }

  const rankingAfter = REDIS_OK ? listKeys('ranking:*') : [];
  check('共享 Redis 的 ranking:* 键未被误伤', rankingAfter.length === rankingBefore.length,
    `${rankingBefore.length} → ${rankingAfter.length}`);
  if (HAS_MYSQL) {
    const otherAfter = mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('共享 MySQL 的其它库表未被误伤', otherAfter === otherDbTablesBefore, `${otherDbTablesBefore} → ${otherAfter}`);
  } else {
    skip('共享 MySQL 完整性', '未设置 MYSQL_PASSWORD');
  }
}

main()
  .catch((e) => {
    fail += 1;
    failures.push('脚本异常');
    console.error(`\n\x1b[31m脚本异常：\x1b[0m${e && e.stack ? e.stack : e}`);
  })
  .finally(() => {
    cleanup();
    console.log(`\n${'─'.repeat(56)}`);
    console.log(`通过 \x1b[32m${pass}\x1b[0m / 失败 \x1b[31m${fail}\x1b[0m / 跳过 \x1b[33m${skipped}\x1b[0m`);
    if (failures.length) {
      console.log(`\n失败项：\n${failures.map((f) => `  - ${f}`).join('\n')}`);
    }
    if (skipNotes.length) {
      console.log(`\n跳过项：\n${skipNotes.map((f) => `  - ${f}`).join('\n')}`);
    }
    process.exitCode = fail === 0 ? 0 : 1;
  });
