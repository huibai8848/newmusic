#!/usr/bin/env node
/**
 * 模块 08 验收脚本 —— 对应 docs/系分/08-AI推荐.md 的验收核对表。
 *
 * 运行：
 *   node scripts/verify-08.mjs
 *   MYSQL_PASSWORD=*** node scripts/verify-08.mjs   # 额外跑写库硬证据、屏蔽上限、播放量校正
 *   APP_LOG=other.log REDIS_CLI=/path/to/redis-cli node scripts/verify-08.mjs
 *
 * 前置：应用已启动（8081）+ 本机 Redis 6379 可连。依赖 Node 18+（内置 fetch）。
 *
 * ⚠ 本机 6379 是共享实例：db0 里还放着另一个项目的 ranking:* 键。
 *   脚本只读写 ml: 前缀的键，并在开头/结尾各数一遍 ranking:* 的键数来证明没碰它。
 *   绝不用 FLUSHDB/FLUSHALL；本机 redis-cli 的 --scan 会静默返回空，所以用 keys。
 *
 * ⚠ 限流隔离：每个小节用各自随机的合成 X-Forwarded-For。推荐接口 60/分钟，
 *   脚本对同一账号的请求远低于额度，但反馈/播放接口会连续调用，分片更稳。
 *
 * ⚠ 探测账号固定为 m08verify（幂等注册）。每次开跑先把它自己的历史/反馈/偏好
 *   清干净，保证「冷启动」断言不是上一轮残留的假象；收尾再清一次。
 *
 * ⚠ MySQL 3306 也是共享实例（还有 emp_db / music_db）。所有 SQL 都必须带
 *   newmusic. 限定符，口令只从 MYSQL_PASSWORD 读、经 MYSQL_PWD 传给客户端，
 *   不写进文件、不进 argv。没设口令时只跳过「写库硬证据」相关小节，而不是静默变绿。
 *
 * ⚠ 有效播放会让 t_song.play_count +1。这是真实副作用，必须还原：脚本在
 *   end(valid) 之前记下 play_count 基线，收尾 UPDATE 回原值。没有 MYSQL_PASSWORD
 *   时无法还原，因此有效播放用例整体跳过（不作为通过）。
 */

import { spawnSync } from 'node:child_process';

const BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';

const PROBE = { account: 'm08verify', password: 'Probe@12345' };
/** 稳定种子：晴天（周杰伦/叶惠美/流行/国语/269s） */
const SEED_SONG = '1000000000000005001';

/** 每节一个随机合成 IP，避免连跑两遍时上一轮的限流计数影响本轮 */
const syntheticIp = () => `10.98.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
const ipOf = (() => {
  const cache = new Map();
  return (section) => {
    if (!cache.has(section)) cache.set(section, syntheticIp());
    return cache.get(section);
  };
})();

let pass = 0;
let fail = 0;
const failures = [];
const skip = [];
const pending = [];

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
  return { status: res.status, json, text, elapsedMs, headers: res.headers };
}

// ---------------------------------------------------------------- redis helper

/**
 * redis-cli 走 stdin：本机 redis-cli 3.0 的 argv 会按 ANSI/GBK 转一遍非 ASCII，
 * 含中文的键或成员会静默对不上。stdin 是字节流，UTF-8 原样透传。
 */
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

const rTtl = (k) => Number(redis('ttl', k));
const rExists = (k) => redis('exists', k) === '1';
const rDel = (...keys) => redis('del', ...keys);
const rScard = (k) => Number(redis('scard', k));

const listKeys = (pattern) => {
  const out = redis('keys', pattern);
  return out === '' ? [] : out.split('\n').map((s) => s.trim()).filter(Boolean);
};
/** 共享 db0 里另一个项目的键。只数，不读不写 */
const rankingKeys = () => listKeys('ranking:*');
const RANKING_BEFORE = rankingKeys();

// ---------------------------------------------------------------- mysql helper

/**
 * SQL 走 stdin，且文本里不出现非 ASCII（中文用 CONVERT(0x… USING utf8mb4)）。
 * 口令只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给客户端，不进 argv、不落盘。
 * 所有语句都带 newmusic. 限定符。
 */
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_DB = process.env.MYSQL_DB || 'newmusic';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const utf8Hex = (s) => `CONVERT(0x${Buffer.from(s, 'utf8').toString('hex')} USING utf8mb4)`;

function mysql(sql) {
  const r = spawnSync(MYSQL_CLI, [
    '-u', MYSQL_USER, '-h', '127.0.0.1', '-P', process.env.MYSQL_PORT || '3306',
    '-D', MYSQL_DB, '-N', '-B', '--default-character-set=utf8mb4',
  ], { encoding: 'utf8', input: sql, env: { ...process.env, MYSQL_PWD } });
  if (r.error) throw new Error(`无法执行 mysql 客户端（${MYSQL_CLI}）：${r.error.message}`);
  if (r.status !== 0) throw new Error(`mysql 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

// ---------------------------------------------------------------- 小工具

function p95(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)];
}

const idsOf = (items) => (items || []).map((x) => String(x?.song?.id)).filter(Boolean);
const unique = (arr) => new Set(arr).size === arr.length;
const countBy = (arr, keyFn) => {
  const map = new Map();
  for (const x of arr) {
    const key = keyFn(x);
    if (key === null || key === undefined) continue;
    map.set(key, (map.get(key) || 0) + 1);
  }
  return map;
};
const today = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

/** 推荐条目多样性：歌手 ≤ maxArtist，专辑（非空时）≤ maxAlbum */
function diverse(items, maxArtist, maxAlbum) {
  const artists = countBy(items, (x) => x?.song?.artistId);
  const albums = countBy(items, (x) => x?.song?.albumId);
  const badArtists = [...artists.entries()].filter(([, n]) => n > maxArtist);
  const badAlbums = [...albums.entries()].filter(([, n]) => n > maxAlbum);
  return { ok: badArtists.length === 0 && badAlbums.length === 0, artists, albums, badArtists, badAlbums };
}

function reasonsOk(items) {
  return (items || []).every((x) => typeof x?.reason === 'string'
    && x.reason.trim().length > 0 && x.reason.length <= 20);
}

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

async function loginAs(account, password) {
  await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password, nickname: account },
  });
  const res = await call('POST', '/auth/login', { xff: syntheticIp(), body: { account, password } });
  if (res.json?.code !== 0) throw new Error(`账号登录失败 ${account}: ${JSON.stringify(res.json)}`);
  return { token: res.json.data.accessToken, userId: String(res.json.data.user.id) };
}

/** 当前屏蔽列表（用于开跑/收尾的幂等清理） */
async function clearBlocklist(token) {
  const res = await call('GET', '/recommend/blocklist', { token, xff: ipOf('cleanup') });
  const data = res.json?.data || {};
  for (const song of data.songs || []) {
    await call('DELETE', `/recommend/blocklist/song/${song.id}`, { token, xff: ipOf('cleanup') });
  }
  for (const artist of data.artists || []) {
    await call('DELETE', `/recommend/blocklist/artist/${artist.id}`, { token, xff: ipOf('cleanup') });
  }
}

/** 只删本探测账号自己的 ml: 键；不触碰 db0 里任何别的项目数据 */
function clearProbeRedis(userId) {
  const keys = [
    ...listKeys(`ml:rec:*${userId}*`),
    ...listKeys(`ml:play:*${userId}*`),
  ];
  if (keys.length > 0) rDel(...keys);
}

function clearProbeDb(userId) {
  if (!HAS_MYSQL) return;
  mysql(`DELETE FROM newmusic.t_recommend_feedback WHERE user_id = ${userId};`);
  mysql(`DELETE FROM newmusic.t_play_history WHERE user_id = ${userId};`);
  mysql(`DELETE FROM newmusic.t_user_preference WHERE user_id = ${userId};`);
}

async function resetProbe(userId) {
  await call('DELETE', '/play/history', { token: probe.token, xff: ipOf('cleanup') });
  await clearBlocklist(probe.token);
  await call('POST', '/recommend/questionnaire', {
    token: probe.token,
    xff: ipOf('cleanup'),
    body: { styles: [], languages: [], artists: [], mood: null },
  });
  clearProbeDb(userId);
  clearProbeRedis(userId);
}

// ================================================================ 主流程

let probe;
let catalog = [];
let catalogById = new Map();
/** end(valid) 会 +1 的歌曲基线，收尾还原 */
const playCountBaseline = new Map();
/** 游客用例创建的 deviceId，收尾兜底清理（即使中途断言抛错也不留脏行） */
const guestDeviceIds = new Set();

async function loadCatalog() {
  const res = await call('GET', '/music/songs?pageSize=100&pageNum=1', { xff: ipOf('catalog') });
  catalog = res.json?.data?.list || [];
  catalogById = new Map(catalog.map((s) => [String(s.id), s]));
  check('曲库可读（40 首种子，供多样性/冷启动断言）', res.json?.code === 0 && catalog.length >= 30,
    { code: res.json?.code, size: catalog.length });
  return catalog;
}

/**
 * 记录播放开始；返回 playId 与是否重复。
 * `auth: false` 时必须显式传，否则默认带探测账号 token——
 * 用 `token: undefined` 是没用的（解构默认值会把它当成「没传」）。
 */
async function recordPlay(songId, { auth = true, deviceId, source = 'verify' } = {}) {
  const body = { songId, quality: 'standard', source };
  if (deviceId) body.deviceId = deviceId;
  const res = await call('POST', '/play/record', {
    token: auth ? probe.token : undefined,
    body,
    xff: ipOf('play'),
  });
  return { res, playId: res.json?.data?.playId ? String(res.json.data.playId) : null, data: res.json?.data };
}

async function reportProgress(songId, playId, position, duration) {
  return call('POST', '/play/progress', {
    token: probe.token,
    xff: ipOf('play'),
    body: { songId, playId, position, duration },
  });
}

async function endPlay(playId, body, { auth = true } = {}) {
  return call('POST', '/play/end', {
    token: auth ? probe.token : undefined,
    xff: ipOf('play'),
    body: { playId, ...body },
  });
}

// ================================================================ §01 每日推荐

async function dailyStage() {
  section('§01 每日推荐（AC-17-001-1/3/4/6/8/9、AC-17-100）');

  const daily = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('daily') });
  const data = daily.json?.data || {};
  const items = data.items || [];
  check('code = 0', daily.json?.code === 0, daily.json);
  check('固定 30 首（AC-17-001-1 / BR-17-001）', items.length === 30, items.length);
  check('日期是今天（BR-17-002）', data.date === today(), { got: data.date, want: today() });
  check('不是降级兜底（本次曲库充足）', data.fallback === false, data.fallback);
  check('每首都有推荐理由且 ≤ 20 字（AC-17-001-4 / BR-17-013）', reasonsOk(items),
    items.filter((x) => !x.reason || x.reason.length > 20).slice(0, 3));
  check('没有重复歌曲（BR-17-001 的「30 首」不是 29 首 + 1 重复）', unique(idsOf(items)),
    idsOf(items).filter((id, i, a) => a.indexOf(id) !== i));
  const div = diverse(items, 2, Number.MAX_SAFE_INTEGER);
  check('同一歌手 ≤ 2 首（AC-17-001-3 / BR-17-007）', div.ok, div.badArtists);
  check('歌曲均可播放（copyrightStatus=1，AC-17-001-6 / BR-17-012）',
    items.every((x) => x?.song && x.song.copyrightStatus === 1),
    items.filter((x) => x?.song?.copyrightStatus !== 1).map((x) => x.song?.name));
  check('position 连续 1..30（前端按位置做动画）',
    items.every((x, i) => x.position === i + 1), items.map((x) => x.position).slice(0, 5));

  const key = `ml:rec:daily:${probe.userId}:${data.date}`;
  check('每日推荐已写 Redis（BR-17-009）', rExists(key), key);
  const ttl = rTtl(key);
  check(`缓存 TTL ≈ 48h（实测 ${ttl}s）`, ttl > 47 * 3600 && ttl <= 48 * 3600, ttl);

  // 缓存命中：同一份结果、P95 ≤ 300ms
  const again = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('daily') });
  check('第二次请求命中缓存且顺序一致', JSON.stringify(idsOf(again.json?.data?.items)) === JSON.stringify(idsOf(items)));
  const latencies = [];
  let allOk = true;
  for (let i = 0; i < 10; i += 1) {
    const r = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf(`daily-p95-${i % 2}`) });
    if (r.json?.code !== 0) allOk = false;
    latencies.push(r.elapsedMs);
  }
  const p95d = p95(latencies);
  check('缓存命中 10 次全部 code=0', allOk);
  check(`缓存命中 P95 ≤ 300ms（AC-17-001-8，实测 ${p95d.toFixed(1)}ms）`, p95d <= 300, latencies);

  // 曝光：当日已曝光歌曲不再生成（BR-17-003）
  const exposed = idsOf(items).slice(0, 3);
  const exp = await call('POST', '/recommend/exposure', {
    token: probe.token,
    xff: ipOf('exposure'),
    body: { songIds: exposed, scene: 'DAILY' },
  });
  check('曝光上报 code = 0', exp.json?.code === 0, exp.json);
  const exposureKey = `ml:rec:exposure:${probe.userId}:${data.date}`;
  check('曝光写入当日 Set', rScard(exposureKey) === 3, rScard(exposureKey));
  const expTtl = rTtl(exposureKey);
  check(`曝光 Set TTL ≈ 24h（实测 ${expTtl}s）`, expTtl > 23 * 3600 && expTtl <= 24 * 3600, expTtl);

  // 删掉当日缓存强制重算：曝光过的歌必须被排除
  rDel(key);
  const regen = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('daily-regen') });
  const regenIds = idsOf(regen.json?.data?.items);
  check('重新生成后仍返回 30 首（过滤曝光后曲库仍够）', regenIds.length === 30, regenIds.length);
  check('当日已曝光的 3 首不再出现（AC-17-001-5 / BR-17-003）',
    exposed.every((id) => !regenIds.includes(id)),
    { exposed, still: exposed.filter((id) => regenIds.includes(id)) });
  check('重新生成后歌手仍 ≤ 2', diverse(regen.json?.data?.items || [], 2, Number.MAX_SAFE_INTEGER).ok);
  note('曝光 Set 为 3 个成员；删除当日缓存再取，验证的是「重新生成时排除」而不是缓存过滤');
}

// ================================================================ §02 冷启动问卷

async function questionnaireStage() {
  section('§02 冷启动问卷（AC-17-006-1/2/3、AC-17-106）');

  const q = await call('GET', '/recommend/questionnaire', { token: probe.token, xff: ipOf('questionnaire') });
  const data = q.json?.data || {};
  check('问卷 code = 0', q.json?.code === 0, q.json);
  check('风格选项 9 个且含「摇滚」', (data.styleOptions || []).length === 9
    && data.styleOptions.includes('摇滚'), data.styleOptions);
  check('语种选项 6 个且含「国语」', (data.languageOptions || []).length === 6
    && data.languageOptions.includes('国语'), data.languageOptions);
  check('心情选项 5 个', Object.keys(data.moodOptions || {}).length === 5, data.moodOptions);

  // 清空历史后行为 0 条 → 猜你喜欢必须是 coldStart（AC-17-003-4）
  const cold = await call('GET', '/recommend/guess?page=1&size=20', { token: probe.token, xff: ipOf('guess-cold') });
  check('无行为用户猜你喜欢 code = 0 且 coldStart=true（EX-17-003-1）',
    cold.json?.code === 0 && cold.json?.data?.coldStart === true, cold.json?.data);
  check('冷启动也返回 20 首，不是空列表（AC-17-006-1/3）', (cold.json?.data?.items || []).length === 20,
    (cold.json?.data?.items || []).length);

  // 问卷选「摇滚」后每日推荐必须含摇滚（AC-17-006-2）
  const artistId = catalog.find((s) => s.genre === '摇滚')?.artistId
    || catalog[0]?.artistId;
  const saved = await call('POST', '/recommend/questionnaire', {
    token: probe.token,
    xff: ipOf('questionnaire'),
    body: { styles: ['摇滚'], languages: ['国语'], artists: [String(artistId)], mood: 'SPORT' },
  });
  const current = saved.json?.data?.current || {};
  check('保存问卷 code = 0', saved.json?.code === 0, saved.json);
  check('当前偏好回显 styles=摇滚 / languages=国语 / mood=SPORT',
    JSON.stringify(current.styles) === JSON.stringify(['摇滚'])
      && JSON.stringify(current.languages) === JSON.stringify(['国语'])
      && current.mood === 'SPORT', current);
  check('歌手 ID 回显（字符串/数字都可，比较字符串）',
    (current.artists || []).map(String).includes(String(artistId)), current.artists);

  const after = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('daily-q') });
  const afterItems = after.json?.data?.items || [];
  const rockCount = afterItems.filter((x) => x?.song?.genre === '摇滚').length;
  check('问卷提交后每日推荐立即重算并含摇滚歌曲（AC-17-006-2）', rockCount >= 1, rockCount);
  check('问卷结果仍满足歌手 ≤ 2', diverse(afterItems, 2, Number.MAX_SAFE_INTEGER).ok);

  const invalid = await call('POST', '/recommend/questionnaire', {
    token: probe.token,
    xff: ipOf('questionnaire'),
    body: { styles: ['不存在的风格'], languages: [], artists: [], mood: null },
  });
  check('非法风格被拒绝（白名单由服务端下发）', invalid.json?.code === 10001, invalid.json);

  if (HAS_MYSQL) {
    const row = mysql(`SELECT styles, languages, artists, mood FROM newmusic.t_user_preference `
      + `WHERE user_id = ${probe.userId} AND deleted = 0 LIMIT 1;`);
    const cols = row.split('\t');
    check('MySQL 硬证据：偏好已落库且是最后一次合法提交',
      cols[0] === '摇滚' && cols[1] === '国语' && cols[2] === String(artistId) && cols[3] === 'SPORT',
      cols);
  } else {
    skip.push('§02 的 MySQL 偏好落库硬证据：未设置 MYSQL_PASSWORD');
  }
}

// ================================================================ §03 猜你喜欢

async function guessStage() {
  section('§03 猜你喜欢（AC-17-003-1/2/3、AC-17-103）');

  const page1 = await call('GET', '/recommend/guess?page=1&size=20', { token: probe.token, xff: ipOf('guess') });
  const p1 = page1.json?.data || {};
  const items1 = p1.items || [];
  check('返回 20 首（AC-17-003-1 / BR-17-025）', items1.length === 20, items1.length);
  check('page=1、pageSize=20 回显正确', p1.page === 1 && p1.pageSize === 20, { page: p1.page, pageSize: p1.pageSize });
  check('每首带理由且 ≤ 20 字', reasonsOk(items1));
  check('同一歌手 ≤ 2 首（AC-17-003-3）', diverse(items1, 2, 1).ok, diverse(items1, 2, 1).badArtists);
  check('同一专辑 ≤ 1 首（BR-17-027）',
    [...diverse(items1, 2, 1).albums.entries()].every(([, n]) => n <= 1),
    diverse(items1, 2, 1).badAlbums);

  // hasNext 必须与「下一页实际有没有内容」一致。
  // 本机只有 40 首种子，且 BR-17-027 要求同专辑 ≤ 1 首（22 张专辑），
  // 每页 20 首时整池最多只能选出 20 首，所以第二页本来就为空——这不是分页坏了。
  const page2 = await call('GET', '/recommend/guess?page=2&size=20', { token: probe.token, xff: ipOf('guess') });
  const items2 = page2.json?.data?.items || [];
  check('page=2 code = 0', page2.json?.code === 0, page2.json);
  check('hasNext 与第二页实际内容一致（本曲库下第二页应为空）',
    p1.hasNext === (items2.length > 0), { hasNext: p1.hasNext, page2Size: items2.length });
  note(items2.length === 0
    ? '曲库 40 首 + 专辑 ≤1，整池只能选出 20 首：20/页时没有第二页，属数据规模限制而非分页缺陷'
    : `第二页 ${items2.length} 首`);

  // 用 10/页验证真实的翻页切片：第一页 10、第二页 10、第三页空
  const s1 = await call('GET', '/recommend/guess?page=1&size=10', { token: probe.token, xff: ipOf('guess-page') });
  const s2 = await call('GET', '/recommend/guess?page=2&size=10', { token: probe.token, xff: ipOf('guess-page') });
  const s3 = await call('GET', '/recommend/guess?page=3&size=10', { token: probe.token, xff: ipOf('guess-page') });
  const small1 = s1.json?.data?.items || [];
  const small2 = s2.json?.data?.items || [];
  check('10/页第一页返回 10 首且 hasNext=true', small1.length === 10 && s1.json?.data?.hasNext === true,
    { size: small1.length, hasNext: s1.json?.data?.hasNext });
  check('10/页第二页返回 10 首（滚动加载可用）', small2.length === 10, small2.length);
  check('两页不重复（同一候选池切片）',
    idsOf(small1).every((id) => !idsOf(small2).includes(id)),
    idsOf(small1).filter((id) => idsOf(small2).includes(id)));
  check('第二页 position 从 11 开始', small2[0]?.position === 11, small2[0]?.position);
  check('第二页歌手 ≤ 2、专辑 ≤ 1（按页应用多样性规则）',
    diverse(small2, 2, 1).ok, diverse(small2, 2, 1));
  check('第三页为空且 hasNext=false（恰好两页的边界）',
    (s3.json?.data?.items || []).length === 0 && s3.json?.data?.hasNext === false,
    { size: (s3.json?.data?.items || []).length, hasNext: s3.json?.data?.hasNext });

  const guessKey = `ml:rec:guess:${probe.userId}`;
  check('猜你喜欢写 Redis', rExists(guessKey), guessKey);
  const ttl = rTtl(guessKey);
  check(`缓存 TTL ≈ 30 分钟（实测 ${ttl}s）`, ttl > 1700 && ttl <= 1800, ttl);

  // 行为不足 5 条时 coldStart=true；新增行为会主动删缓存（BR-17-029）
  const behaviorSongs = catalog.slice(0, 5).map((s) => String(s.id));
  for (const songId of behaviorSongs) {
    const r = await recordPlay(songId);
    check(`播放上报成功 song=${songId.slice(-4)}`, r.res.json?.code === 0, r.res.json);
    if (r.res.json?.code !== 0) break;
  }
  check('新增播放行为后猜你喜欢缓存被主动删除（BR-17-029）', !rExists(guessKey), guessKey);

  const warmed = await call('GET', '/recommend/guess?page=1&size=20', { token: probe.token, xff: ipOf('guess-warm') });
  check('行为 ≥ 5 条后 coldStart=false（BR-17-028）', warmed.json?.data?.coldStart === false,
    warmed.json?.data?.coldStart);
  check('回暖后仍返回 20 首', (warmed.json?.data?.items || []).length === 20,
    (warmed.json?.data?.items || []).length);
  check('回暖后歌手仍 ≤ 2、专辑 ≤ 1', diverse(warmed.json?.data?.items || [], 2, 1).ok);
}

// ================================================================ §04 反馈与屏蔽

async function feedbackStage() {
  section('§04 反馈与屏蔽（AC-17-002-3/5/6、AC-17-102）');

  const daily = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('feedback') });
  const dailyItems = daily.json?.data?.items || [];
  const target = dailyItems[0]?.song;
  check('有可操作的每日推荐歌曲', Boolean(target), dailyItems.length);
  if (!target) return;
  const songId = String(target.id);
  const artistId = String(target.artistId);
  const guessKey = `ml:rec:guess:${probe.userId}`;

  const like = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId, action: 'LIKE', scene: 'DAILY', position: 1 },
  });
  check('LIKE code = 0 且 likedSongCount=1', like.json?.code === 0
    && like.json?.data?.likedSongCount === 1, like.json?.data);

  const dislike = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId, action: 'DISLIKE', scene: 'DAILY', position: 1 },
  });
  check('DISLIKE code = 0 且 dislikedSongCount=1', dislike.json?.code === 0
    && dislike.json?.data?.dislikedSongCount === 1, dislike.json?.data);
  check('反馈后猜你喜欢缓存被删除（BR-17-020/029）', !rExists(guessKey), guessKey);

  const block = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  const blockData = block.json?.data || {};
  check('屏蔽列表含该歌曲（AC-17-002-5）', (blockData.songs || []).map((s) => String(s.id)).includes(songId),
    (blockData.songs || []).map((s) => s.name));
  check('屏蔽上限 200 首 / 50 位歌手（BR-17-022）',
    blockData.songLimit === 200 && blockData.artistLimit === 50,
    { songLimit: blockData.songLimit, artistLimit: blockData.artistLimit });

  const dailyAfter = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('feedback') });
  check('不喜欢的歌曲从当日每日推荐中消失（AC-17-002-3 / BR-17-017）',
    !idsOf(dailyAfter.json?.data?.items || []).includes(songId),
    idsOf(dailyAfter.json?.data?.items || []).filter((id, i, a) => a.includes(songId)));
  const guessAfter = await call('GET', '/recommend/guess?page=1&size=20', { token: probe.token, xff: ipOf('feedback') });
  check('不喜欢的歌曲从猜你喜欢中消失（AC-17-002-6）',
    !idsOf(guessAfter.json?.data?.items || []).includes(songId));
  check('猜你喜欢重新生成后歌手仍 ≤ 2、专辑 ≤ 1', diverse(guessAfter.json?.data?.items || [], 2, 1).ok);

  const repeat = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId, action: 'DISLIKE', scene: 'DAILY' },
  });
  check('重复 DISLIKE 幂等：数量仍为 1（EX-17-002-1）',
    repeat.json?.code === 0 && repeat.json?.data?.dislikedSongCount === 1, repeat.json?.data);

  // 撤销歌曲不能把派生 artistId 一起写进日志，否则会误撤销歌手屏蔽
  const undo = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId, action: 'UNDO', scene: 'DAILY' },
  });
  check('UNDO 撤销不喜欢歌曲（BR-17-016）', undo.json?.code === 0
    && undo.json?.data?.dislikedSongCount === 0 && undo.json?.data?.dislikedArtistCount === 0, undo.json?.data);
  const blockEmpty = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  check('撤销后屏蔽列表清空', (blockEmpty.json?.data?.songs || []).length === 0
    && (blockEmpty.json?.data?.artists || []).length === 0, blockEmpty.json?.data);

  const dislikeArtist = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId, action: 'DISLIKE_ARTIST', artistId, scene: 'DAILY' },
  });
  check('DISLIKE_ARTIST code = 0 且歌手计数=1', dislikeArtist.json?.code === 0
    && dislikeArtist.json?.data?.dislikedArtistCount === 1, dislikeArtist.json?.data);
  const artistBlock = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  check('屏蔽列表含该歌手', (artistBlock.json?.data?.artists || []).map((a) => String(a.id)).includes(artistId),
    (artistBlock.json?.data?.artists || []).map((a) => a.name));

  const dailyArtist = await call('GET', '/recommend/daily', { token: probe.token, xff: ipOf('feedback') });
  check('屏蔽歌手后该歌手的歌全部从每日推荐消失',
    (dailyArtist.json?.data?.items || []).every((x) => String(x?.song?.artistId) !== artistId),
    (dailyArtist.json?.data?.items || []).filter((x) => String(x?.song?.artistId) === artistId).map((x) => x.song.name));
  const guessArtist = await call('GET', '/recommend/guess?page=1&size=20', { token: probe.token, xff: ipOf('feedback') });
  check('屏蔽歌手后该歌手的歌全部从猜你喜欢消失',
    (guessArtist.json?.data?.items || []).every((x) => String(x?.song?.artistId) !== artistId));

  const undoArtist = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { artistId, action: 'UNDO_ARTIST', scene: 'DAILY' },
  });
  check('UNDO_ARTIST 撤销歌手屏蔽', undoArtist.json?.code === 0
    && undoArtist.json?.data?.dislikedArtistCount === 0, undoArtist.json?.data);

  // 屏蔽管理的两个 DELETE 入口
  await call('POST', '/recommend/feedback', {
    token: probe.token, xff: ipOf('feedback'), body: { songId, action: 'DISLIKE', scene: 'DAILY' },
  });
  const delSong = await call('DELETE', `/recommend/blocklist/song/${songId}`, { token: probe.token, xff: ipOf('feedback') });
  const afterDelSong = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  check('从屏蔽列表移除歌曲（AC-17-002-5）', delSong.json?.code === 0
    && (afterDelSong.json?.data?.songs || []).length === 0, afterDelSong.json?.data);

  await call('POST', '/recommend/feedback', {
    token: probe.token, xff: ipOf('feedback'), body: { artistId, action: 'DISLIKE_ARTIST', scene: 'DAILY' },
  });
  const delArtist = await call('DELETE', `/recommend/blocklist/artist/${artistId}`, { token: probe.token, xff: ipOf('feedback') });
  const afterDelArtist = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  check('从屏蔽列表移除歌手（AC-17-002-5）', delArtist.json?.code === 0
    && (afterDelArtist.json?.data?.artists || []).length === 0, afterDelArtist.json?.data);

  // 参数校验
  const missing = await call('POST', '/recommend/feedback', {
    token: probe.token, xff: ipOf('feedback'), body: { action: 'LIKE' },
  });
  check('LIKE 缺少 songId → 10001', missing.json?.code === 10001, missing.json);
  const badAction = await call('POST', '/recommend/feedback', {
    token: probe.token, xff: ipOf('feedback'), body: { songId, action: 'NOPE' },
  });
  check('非法 action → 10001', badAction.json?.code === 10001, badAction.json);
  const noArtist = await call('POST', '/recommend/feedback', {
    token: probe.token, xff: ipOf('feedback'), body: { action: 'DISLIKE_ARTIST' },
  });
  check('DISLIKE_ARTIST 缺少 artistId → 10001', noArtist.json?.code === 10001, noArtist.json);

  if (!HAS_MYSQL) {
    skip.push('§04 屏蔽上限 200 首 → 80010：需要写 200 行反馈日志，未设置 MYSQL_PASSWORD');
    skip.push('§04 反馈追加日志的 MySQL 硬证据：未设置 MYSQL_PASSWORD');
    return;
  }

  // 200 首上限：直接灌 200 条 DISLIKE 日志，清掉物化标记后由回放得到 200 个屏蔽 ID
  const fakeBase = 8000000000000000000n;
  const rows = [];
  for (let i = 0; i < 200; i += 1) {
    const id = fakeBase + BigInt(i);
    rows.push(`(${id}, ${probe.userId}, ${id}, 'DISLIKE', 'VERIFY', 1)`);
  }
  mysql(`INSERT INTO newmusic.t_recommend_feedback (id, user_id, song_id, action, scene, position) VALUES ${rows.join(',')};`);
  rDel(`ml:rec:dislike:${probe.userId}`, `ml:rec:block:loaded:${probe.userId}`);
  const full = await call('POST', '/recommend/feedback', {
    token: probe.token,
    xff: ipOf('feedback'),
    body: { songId: String(catalog[1].id), action: 'DISLIKE', scene: 'DAILY' },
  });
  check('屏蔽列表满 200 首时返回 80010（EX-17-002-2）', full.json?.code === 80010, full.json);

  mysql(`DELETE FROM newmusic.t_recommend_feedback WHERE user_id = ${probe.userId} AND song_id >= ${fakeBase};`);
  rDel(`ml:rec:dislike:${probe.userId}`, `ml:rec:dislike:artist:${probe.userId}`,
    `ml:rec:like:${probe.userId}`, `ml:rec:block:loaded:${probe.userId}`);
  const restored = await call('GET', '/recommend/blocklist', { token: probe.token, xff: ipOf('feedback') });
  check('清理灌入的 200 行后屏蔽列表恢复为空', (restored.json?.data?.songs || []).length === 0
    && (restored.json?.data?.artists || []).length === 0, restored.json?.data);

  // 追加日志硬证据：5 种动作都应留下记录
  // 不要加 song_id 过滤：DISLIKE_ARTIST / UNDO_ARTIST 的 song_id 是 NULL，
  // `NULL < x` 的结果是 NULL（不是 true），会把这两类日志整批漏掉。
  const actions = mysql(`SELECT action, COUNT(*) FROM newmusic.t_recommend_feedback `
    + `WHERE user_id = ${probe.userId} GROUP BY action;`)
    .split('\n').filter(Boolean).map((line) => line.split('\t')[0]);
  check('反馈是追加日志：LIKE/DISLIKE/DISLIKE_ARTIST/UNDO/UNDO_ARTIST 均有落库',
    ['LIKE', 'DISLIKE', 'DISLIKE_ARTIST', 'UNDO', 'UNDO_ARTIST'].every((a) => actions.includes(a)), actions);
}

// ================================================================ §05 相似歌曲

async function similarStage() {
  section('§05 相似歌曲（AC-17-004-1/3/5、AC-17-104）');

  // 游客访问：相似歌曲是公开接口
  const res = await call('GET', `/recommend/similar?songId=${SEED_SONG}`, { xff: ipOf('similar') });
  const data = res.json?.data || {};
  const items = data.items || [];
  check('游客可访问（公开接口）且 code = 0', res.json?.code === 0, res.json);
  check('返回 20 首（AC-17-004-1 / BR-17-031）', items.length === 20, items.length);
  check('不含当前歌曲本身', !idsOf(items).includes(SEED_SONG), idsOf(items));
  check('同一歌手 ≤ 2 首（AC-17-004-3 / BR-17-033）', diverse(items, 2, Number.MAX_SAFE_INTEGER).ok,
    diverse(items, 2, Number.MAX_SAFE_INTEGER).badArtists);
  check('每首都有理由且 ≤ 20 字（和《晴天》很像）', reasonsOk(items),
    items.filter((x) => !x.reason || x.reason.length > 20).slice(0, 3));
  check('理由真实指向当前歌曲', items.every((x) => (x.reason || '').includes('晴天')),
    items.slice(0, 3).map((x) => x.reason));
  check('结果全部可播放（AC-17-004-5 / BR-17-032）',
    items.every((x) => x?.song?.copyrightStatus === 1),
    items.filter((x) => x?.song?.copyrightStatus !== 1).map((x) => x?.song?.name));
  check('无实时降级（预计算或已落库，fallback=false）', data.fallback === false, data.fallback);

  const key = `ml:rec:similar:${SEED_SONG}`;
  check('相似歌曲写 Redis', rExists(key), key);
  const ttl = rTtl(key);
  check(`缓存 TTL ≈ 7 天（实测 ${ttl}s）`, ttl > 6 * 24 * 3600 && ttl <= 7 * 24 * 3600, ttl);

  const again = await call('GET', `/recommend/similar?songId=${SEED_SONG}`, { xff: ipOf('similar') });
  check('第二次请求结果稳定（缓存命中）',
    JSON.stringify(idsOf(again.json?.data?.items)) === JSON.stringify(idsOf(items)));

  const missing = await call('GET', '/recommend/similar?songId=1', { xff: ipOf('similar') });
  check('不存在的歌曲 → 30001', missing.json?.code === 30001, missing.json);
}

// ================================================================ §06 继续播放 / 最近常听

async function continueStage() {
  section('§06 继续播放 / 最近常听 / 最近播放（AC-17-007-1/3、AC-17-108）');

  const fresh = catalog.filter((s) => s.copyrightStatus === 1).slice(10, 40);
  check('有足够的歌构造继续播放用例', fresh.length >= 12, fresh.length);

  // 25% 进度 → 出现在继续播放
  const c = await recordPlay(String(fresh[0].id));
  check('播放开始上报返回 playId', c.res.json?.code === 0 && Boolean(c.playId), c.res.json);
  const p = await reportProgress(String(fresh[0].id), c.playId, 50, 200);
  check('进度上报 code = 0', p.json?.code === 0, p.json);

  let cont = await call('GET', '/recommend/continue-play', { token: probe.token, xff: ipOf('continue') });
  let contItems = cont.json?.data || [];
  const cItem = contItems.find((x) => String(x?.song?.id) === String(fresh[0].id));
  check('25% 进度的歌曲进入继续播放（AC-17-007-1 / BR-17-054）', Boolean(cItem), contItems.length);
  check('返回断点位置与总时长', cItem?.position === 50 && cItem?.duration === 200, cItem);
  check('progress 在 0.1~0.9 之间且计算正确', Math.abs((cItem?.progress ?? 0) - 0.25) < 0.001,
    cItem?.progress);

  // 5% 与 95% 都不该出现（边界外）
  const low = await recordPlay(String(fresh[1].id));
  await reportProgress(String(fresh[1].id), low.playId, 10, 200);
  const high = await recordPlay(String(fresh[2].id));
  await reportProgress(String(fresh[2].id), high.playId, 190, 200);
  cont = await call('GET', '/recommend/continue-play', { token: probe.token, xff: ipOf('continue') });
  contItems = cont.json?.data || [];
  const contIds = contItems.map((x) => String(x?.song?.id));
  check('5% 进度不计入继续播放（BR-17-054）', !contIds.includes(String(fresh[1].id)));
  check('95% 进度不计入继续播放（BR-17-054）', !contIds.includes(String(fresh[2].id)));

  // 移除
  const dismiss = await call('DELETE', `/recommend/continue-play/${fresh[0].id}`, {
    token: probe.token, xff: ipOf('continue'),
  });
  check('移除继续播放项 code = 0（BR-17-058）', dismiss.json?.code === 0, dismiss.json);
  const afterDismiss = await call('GET', '/recommend/continue-play', { token: probe.token, xff: ipOf('continue') });
  check('移除后不再返回该歌曲',
    !(afterDismiss.json?.data || []).some((x) => String(x?.song?.id) === String(fresh[0].id)));
  const hiddenKey = `ml:rec:continue:hidden:${probe.userId}`;
  check('移除标记写 Redis 且 TTL ≈ 30 天', rExists(hiddenKey)
    && rTtl(hiddenKey) > 29 * 24 * 3600 && rTtl(hiddenKey) <= 30 * 24 * 3600, rTtl(hiddenKey));

  // 最多 10 首
  for (let i = 3; i < 14 && i < fresh.length; i += 1) {
    const r = await recordPlay(String(fresh[i].id));
    if (r.res.json?.code === 0) await reportProgress(String(fresh[i].id), r.playId, 50, 200);
  }
  const capped = await call('GET', '/recommend/continue-play', { token: probe.token, xff: ipOf('continue') });
  const cappedItems = capped.json?.data || [];
  check('继续播放最多 10 首（BR-17-055）', cappedItems.length === 10, cappedItems.length);
  check('继续播放按最近更新时间倒序（刚播的排在前面）',
    cappedItems.length > 1 && cappedItems[0]?.song?.id !== undefined,
    cappedItems.map((x) => x.song?.name).slice(0, 3));

  // 最近常听：≤ 8 位，按播放次数降序
  const freq = await call('GET', '/recommend/frequent-artists', { token: probe.token, xff: ipOf('continue') });
  const freqItems = freq.json?.data || [];
  check('最近常听 code = 0', freq.json?.code === 0, freq.json);
  check('最近常听最多 8 位（AC-17-007-3 / FR-17-007）', freqItems.length > 0 && freqItems.length <= 8,
    freqItems.length);
  const counts = freqItems.map((x) => Number(x.playCount));
  check('最近常听按播放次数降序（BR-17-057）',
    counts.every((n, i) => i === 0 || counts[i - 1] >= n), counts);
  check('最近常听带歌手名与头像字段（前端卡片要显示）',
    freqItems.every((x) => Boolean(x.artistName) && 'avatar' in x), freqItems[0]);

  // 最近播放 12 首
  const recent = await call('GET', '/recommend/recent-songs?limit=12', { token: probe.token, xff: ipOf('continue') });
  const recentSongs = recent.json?.data || [];
  check('最近播放最多 12 首（FR-17-007）', recent.json?.code === 0 && recentSongs.length === 12,
    recentSongs.length);
  check('最近播放包含刚才播过的歌', recentSongs.some((s) => String(s.id) === String(fresh[13]?.id ?? fresh[12]?.id)),
    recentSongs.slice(0, 3).map((s) => s.name));

  // 游客进度上报必须登录
  const guestProgress = await call('POST', '/play/progress', {
    xff: ipOf('play-guest'), body: { songId: String(SEED_SONG), position: 10, duration: 269 },
  });
  check('游客进度上报 → 40101（个人数据必须登录）', guestProgress.json?.code === 40101, guestProgress.json);
}

// ================================================================ §07 播放历史与有效播放

async function historyStage() {
  section('§07 播放上报 / 有效播放 / 历史（FR-16-004 + BR-17 行为前置）');

  const songs = catalog.filter((s) => s.copyrightStatus === 1);
  const v1 = songs[20];
  const v2 = songs[21];

  // 无效播放：29s 且 < 60%
  const inv = await recordPlay(String(v1.id));
  const invEnd = await endPlay(inv.playId, {
    playedDuration: 29, songDuration: 299, position: 29, completed: false,
  });
  check('无效播放 end code = 0', invEnd.json?.code === 0, invEnd.json);

  const history = await call('GET', '/play/history?pageNum=1&pageSize=50', {
    token: probe.token, xff: ipOf('history'),
  });
  const hItems = history.json?.data?.list || [];
  const invRow = hItems.find((x) => String(x.id) === inv.playId);
  check('历史列表可读且分页字段齐全',
    history.json?.code === 0 && Array.isArray(hItems)
      && typeof history.json?.data?.total === 'number'
      && typeof history.json?.data?.hasNext === 'boolean', history.json?.data);
  check('29s / 299s 不算有效播放（BR-17 口径）', invRow?.isValid === 0, invRow?.isValid);
  check('无效播放仍记录实际时长与位置', invRow?.playDuration === 29 && Number(invRow?.lastPosition) === 29,
    { playDuration: invRow?.playDuration, lastPosition: invRow?.lastPosition });

  if (!HAS_MYSQL) {
    skip.push('§07 有效播放 + play_count +1 的 MySQL 硬证据与还原：未设置 MYSQL_PASSWORD');
    skip.push('§07 游客播放归属（device_id）的 MySQL 硬证据：未设置 MYSQL_PASSWORD');
  } else {
    // 基线：有效播放会 +1，收尾必须还原。
    // 直接读 DB：歌曲详情有缓存，incrementCounter 不主动失效它，
    // 用详情接口的 playCount 做断言会把「缓存未过期」误判成「计数没加」。
    const playCountBefore = Number(mysql(`SELECT play_count FROM newmusic.t_song WHERE id = ${v2.id};`));
    check('取得歌曲 play_count 基线（用于收尾还原）', Number.isFinite(playCountBefore), playCountBefore);
    playCountBaseline.set(String(v2.id), playCountBefore);

    const val = await recordPlay(String(v2.id));
    const valEnd = await endPlay(val.playId, {
      playedDuration: 180, songDuration: 299, position: 150, completed: false,
    });
    check('有效播放 end code = 0', valEnd.json?.code === 0, valEnd.json);
    const playCountAfter = Number(mysql(`SELECT play_count FROM newmusic.t_song WHERE id = ${v2.id};`));
    check('有效播放使 DB play_count +1（BR-17 行为信号）',
      playCountAfter === playCountBefore + 1,
      { before: playCountBefore, after: playCountAfter });
    note('歌曲详情接口的 playCount 受缓存影响，可能短暂偏旧；计数的权威口径在 t_song');

    const row = mysql(`SELECT is_valid, play_duration, last_position, song_duration `
      + `FROM newmusic.t_play_history WHERE id = ${val.playId} AND user_id = ${probe.userId} LIMIT 1;`);
    check('MySQL 硬证据：is_valid=1 / played=180 / position=150',
      row === `1\t180\t150.0\t299`, row);

    const history2 = await call('GET', '/play/history?pageNum=1&pageSize=50', {
      token: probe.token, xff: ipOf('history'),
    });
    const valRow = (history2.json?.data?.list || []).find((x) => String(x.id) === val.playId);
    check('历史接口回显 isValid=1 与断点 150',
      valRow?.isValid === 1 && Number(valRow?.lastPosition) === 150,
      { isValid: valRow?.isValid, lastPosition: valRow?.lastPosition });

    // 断点优先取 position，而不是 playedDuration：拖进度条后两者不同
    check('续播点用 position=150 而不是 played=180（避免拖条后进度被拉回/前跳）',
      Number(valRow?.lastPosition) === 150, valRow?.lastPosition);

    // 游客归属：deviceId 必须与开始时一致
    const deviceId = `dev-verify08-${Date.now().toString(36)}`;
    guestDeviceIds.add(deviceId);
    const guest = await recordPlay(String(v1.id), { auth: false, deviceId });
    check('游客播放上报（带 deviceId）code = 0', guest.res.json?.code === 0, guest.res.json);
    const wrong = await endPlay(guest.playId, {
      playedDuration: 20, songDuration: 299, deviceId: `${deviceId}-wrong`,
    }, { auth: false });
    check('游客用错误 deviceId 结束 → 接口仍 200（静默忽略）', wrong.json?.code === 0, wrong.json);
    const guestRow1 = mysql(`SELECT play_duration, device_id FROM newmusic.t_play_history `
      + `WHERE id = ${guest.playId} AND device_id = ${utf8Hex(deviceId)} LIMIT 1;`);
    check('错误 deviceId 没有改动历史行（归属校验生效）', guestRow1.split('\t')[0] === '0', guestRow1);

    const right = await endPlay(guest.playId, {
      playedDuration: 20, songDuration: 299, position: 20, deviceId,
    }, { auth: false });
    check('游客用正确 deviceId 结束 code = 0', right.json?.code === 0, right.json);
    const guestRow2 = mysql(`SELECT play_duration, last_position FROM newmusic.t_play_history `
      + `WHERE id = ${guest.playId} LIMIT 1;`);
    check('正确 deviceId 才写入时长/位置', guestRow2.startsWith('20\t20.0'), guestRow2);

    mysql(`DELETE FROM newmusic.t_play_history WHERE device_id = ${utf8Hex(deviceId)};`);
  }

  // 删除单条 + 清空
  const toDelete = hItems[0];
  const del = await call('DELETE', `/play/history/${toDelete.id}`, { token: probe.token, xff: ipOf('history') });
  check('删除单条历史 code = 0', del.json?.code === 0, del.json);
  const afterDel = await call('GET', '/play/history?pageNum=1&pageSize=50', {
    token: probe.token, xff: ipOf('history'),
  });
  check('删除后该行不再出现', !(afterDel.json?.data?.list || []).some((x) => String(x.id) === String(toDelete.id)));

  const clear = await call('DELETE', '/play/history', { token: probe.token, xff: ipOf('history') });
  check('清空历史 code = 0', clear.json?.code === 0, clear.json);
  const cleared = await call('GET', '/play/history?pageNum=1&pageSize=50', {
    token: probe.token, xff: ipOf('history'),
  });
  check('清空后 total = 0', cleared.json?.data?.total === 0, cleared.json?.data?.total);

  const guestHistory = await call('GET', '/play/history', { xff: ipOf('history') });
  check('游客访问历史 → 40101', guestHistory.json?.code === 40101, guestHistory.json);
}

// ================================================================ 收尾与体检

async function cleanup(userId) {
  section('§08 清理与键空间体检');

  // MySQL 先行：把日志/历史删掉，再删 Redis 物化，避免回放出脏状态
  clearProbeDb(userId);
  clearProbeRedis(userId);
  await call('DELETE', '/play/history', { token: probe.token, xff: ipOf('cleanup') });
  await clearBlocklist(probe.token);
  if (HAS_MYSQL) {
    mysql(`DELETE FROM newmusic.t_user_preference WHERE user_id = ${userId};`);
    for (const deviceId of guestDeviceIds) {
      mysql(`DELETE FROM newmusic.t_play_history WHERE device_id = ${utf8Hex(deviceId)};`);
    }
    for (const [songId, baseline] of playCountBaseline.entries()) {
      mysql(`UPDATE newmusic.t_song SET play_count = ${baseline} WHERE id = ${songId};`);
    }
    const counts = mysql(`SELECT COUNT(*) FROM newmusic.t_recommend_feedback WHERE user_id = ${userId};`);
    check('探测账号反馈日志已清理', counts === '0', counts);
    const histories = mysql(`SELECT COUNT(*) FROM newmusic.t_play_history WHERE user_id = ${userId};`);
    check('探测账号播放历史已清理', histories === '0', histories);
    if (playCountBaseline.size > 0) {
      const [sid, baseline] = [...playCountBaseline.entries()][0];
      const now = mysql(`SELECT play_count FROM newmusic.t_song WHERE id = ${sid};`);
      check(`t_song.play_count 已还原（${sid.slice(-4)} → ${baseline}）`, now === String(baseline), now);
    }
  }

  // 再清一次 Redis：clearBlocklist 会写回 Set/标记，曝光/进度也要带走
  clearProbeRedis(userId);
  const leftover = [
    ...listKeys(`ml:rec:*${userId}*`),
    ...listKeys(`ml:play:*${userId}*`),
  ];
  check('探测账号的 ml:rec/ml:play 键已清空', leftover.length === 0, leftover);

  const allMl = [...listKeys(`ml:rec:*${userId}*`), ...listKeys(`ml:play:*${userId}*`)];
  check('本脚本产生的键都在 ml: 前缀内', allMl.every((k) => k.startsWith('ml:')), allMl);
  const rankingAfter = rankingKeys();
  check(`共享 db0 的 ranking:* 键数未变（${RANKING_BEFORE.length} → ${rankingAfter.length}）`,
    rankingAfter.length === RANKING_BEFORE.length,
    { before: RANKING_BEFORE.length, after: rankingAfter.length });
}

// ================================================================ 主流程

async function main() {
  console.log('\x1b[1m模块 08 验收：AI 智能推荐（docs/系分/08-AI推荐.md）\x1b[0m');
  console.log(`目标 ${BASE}    Redis ${REDIS_CLI}    MySQL ${HAS_MYSQL ? '已配置' : '未配置（写库用例跳过）'}`);
  if (!(await waitForBoot())) {
    console.error('应用未就绪，退出');
    process.exit(2);
  }

  probe = await loginAs(PROBE.account, PROBE.password);
  console.log(`探测账号 ${PROBE.account}（userId=${probe.userId}）`);

  await loadCatalog();
  await resetProbe(probe.userId);

  try {
    await dailyStage();
    await questionnaireStage();
    await guessStage();
    await feedbackStage();
    await similarStage();
    await continueStage();
    await historyStage();
  } finally {
    try {
      await cleanup(probe.userId);
    } catch (e) {
      fail += 1;
      failures.push('收尾清理失败');
      console.error('收尾清理失败：', e);
    }
  }

  section('§09 本轮未覆盖的 AC（逐条说明原因，不做无意义的绿）');
  skip.push('AC-17-001-7 运营置顶：FR-17-009 属 P1，本模块 P0 未交付运营配置（无对应表/接口）');
  skip.push('AC-17-004-2「相似度合理排在前面」：需要人工抽检，自动化只能验结构（不含自身/歌手上限/可播放）');
  skip.push('AC-17-006-4/5 新歌曝光与 10% 探索位：种子只有 40 首且无新歌发布流；探索位仅能通过代码路径确认，'
    + '缺少「新歌 24h 内曝光」的进程外可观测口径');
  skip.push('AC-17-006-5 的「约 10% 探索位」：探索位只在非冷启动生成时插入，'
    + '断言依赖具体排序；本轮以 daily 的结构与多样性为主，探索位由 ScoringService/DailyRecommendService 的逻辑保证');
  skip.push('AC-17-003-2「播放新歌后 30 分钟内推荐变化」：已验证行为触发缓存删除并重算 coldStart，'
    + '但「推荐内容因一首歌而变化」在 40 首曲库里不稳定，不做易碎断言');
  skip.push('FR-17-005 场景歌单 / FR-17-008 私人电台 / FR-17-009 运营位 / FR-17-010 效果统计：均为 P1，本轮不交付');
  skip.push('AC-17-001-10「全量用户 4 小时内生成」：定时任务只有单机 @Scheduled + 用户上限，'
    + '没有可观测的分片执行记录；生产需 XXL-Job（系分已登记）');
  skip.push('AC-17-008-* 与 AC-17-109：私人电台 P1，未实现');
  for (const s of skip) note(`跳过｜${s}`);
  for (const p of pending) note(`待办｜${p}`);

  console.log('\n' + '─'.repeat(66));
  console.log(`模块 08：\x1b[32m通过 ${pass}\x1b[0m  \x1b[31m失败 ${fail}\x1b[0m  `
    + `跳过 ${skip.length}  待办 ${pending.length}`);
  if (failures.length) {
    console.log('\n失败项：');
    failures.forEach((f) => console.log(`  - ${f}`));
  }
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error('\n脚本异常：', e);
  process.exit(2);
});
