#!/usr/bin/env node
/**
 * 模块 06 验收脚本 —— 对应 docs/系分/06-搜索.md §8 验收核对表。
 *
 * 运行：
 *   node scripts/verify-06.mjs          # Stage A + Stage B
 *   node scripts/verify-06.mjs a        # 只跑 Stage A（检索/拼音/联想/筛选排序/深分页/限流）
 *   node scripts/verify-06.mjs b        # 只跑 Stage B（历史/热搜）
 *   APP_LOG=other.log REDIS_CLI=/path/to/redis-cli node scripts/verify-06.mjs
 *
 * 前置：应用已启动（8081）+ 本机 Redis 6379 可连。依赖 Node 18+（内置 fetch），无需 npm 包。
 *
 * ⚠ 本机 6379 是**共享实例**：db0 里躺着另一个项目的 ranking:* 键。
 *   脚本只读写 ml: 前缀的键，并在开头/结尾各数一遍 ranking:* 的键数来**证明**没碰它。
 *   绝不用 FLUSHDB/FLUSHALL，绝不用 KEYS *（本机 redis-cli 3.0 的 --scan 会静默返回空，
 *   那会让「全部通过」的断言其实什么都没检查）。
 *
 * ⚠ 限流隔离：BR-15-008 是游客 30/分钟，而本脚本对 /search 的请求有上百次。
 *   若全用同一个 IP，额度会在跑到 §08 之前就被自己耗光。所以**每节用各自随机的合成
 *   X-Forwarded-For**——额度天然分片，也就不必去删除真实的限流键。
 *
 * ⚠ 「P95 里不能混进被限流的响应」。统计 P95 时把 30 次请求分散到 3 个合成 IP（各 10 次），
 *   并断言 30 次**全部 code=0**：否则 P95 可能是在给一批 30ms 的 10004 拒绝响应算分位数。
 */

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const logPath = process.env.APP_LOG === '' ? null : resolve(process.env.APP_LOG || 'boot.log');

const argv = (process.argv[2] || 'all').toLowerCase();
const runA = argv === 'all' || argv === 'a' || argv === 'stage-a';
const runB = argv === 'all' || argv === 'b' || argv === 'stage-b';
if (!runA && !runB) {
  console.error(`未知参数 "${argv}"，可用：all / a / b`);
  process.exit(2);
}

/** 探测账号：幂等（已存在则直接登录），用来验登录态相关的用例 */
const PROBE = { account: 'm06probe', password: 'Probe@12345' };
/** 管理端账号：AC-15-001-7 要建一首名字里带 <script> 的临时歌曲 */
const ADMIN = { account: 'admin', password: 'admin123' };

/** 种子数据里的稳定标识（sql/data.sql，已用只读 SELECT 逐条核对） */
const SEED = {
  songQingTian: '1000000000000005001', // 晴天（周杰伦/叶惠美, 269s, hot 990）
  songJueJiang: '1000000000000005003', // 倔强（五月天, 251s, hot 920）
  songTuRan: '1000000000000005004', // 突然好想你（五月天, 347s, hot 900）
  artistJay: '1000000000000003001', // 周杰伦（alias Jay Chou, song_count 2）
  albumYeHuiMei: '1000000000000004001', // 叶惠美（周杰伦）
};

/**
 * `sql/data.sql` 最初那 6 首歌。
 *
 * <p>模块 08 又追加了 40 首演示歌曲（`sql/08-recommend-seed.sql`），其中
 * 《平凡的一天》《江南》《温柔》《后来的我们》《倒数》《句号》《消愁》的
 * 歌名/歌手名/专辑名同样含「天」——`keyword=天` 的全量结果从 3 条变成 10 条。
 *
 * <p>所以本脚本里凡是「恰好 N 条」的断言都**限定在这个集合内**：验的是种子数据上的
 * 业务规则，而不是「演示数据当前正好有几首」。以后再追加演示数据也不会误报。
 * 需要全量结果的排序断言则写成关系式（score 降序、同分按热度降序），不依赖条数。
 */
const SEED_SONG_IDS = [
  '1000000000000005001', // 晴天
  '1000000000000005002', // 稻香
  '1000000000000005003', // 倔强
  '1000000000000005004', // 突然好想你
  '1000000000000005005', // 泡沫
  '1000000000000005006', // 光年之外
];
const isSeedSong = (x) => Boolean(x) && SEED_SONG_IDS.includes(x.id);

/** 合成 IP：每次运行随机，避免连跑两遍被上一遍的计数影响 */
const syntheticIp = () => `10.96.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;
/** 每节一个 IP。见文件头「限流隔离」 */
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

/**
 * 后端实际使用的搜索引擎。模块 11 接入 ES 后，同一份脚本要能在 mysql / es 两种引擎下跑：
 * 从 §01 第一次检索的响应体 `engine` 字段读出（同时对应响应头 `X-Search-Engine`）。
 */
let ENGINE = 'unknown';

/**
 * 只在 MySQL 引擎下才有意义的分值断言。
 *
 * <p>BR-15-011 的权重表（name 5 / artist 3 / pinyin 3 / 首字母 4 / lyric 1）是
 * {@code MysqlSearchEngine} 的**加和**口径：score 是可解释的业务权重。
 * ES 返回的是 BM25 的 `_score`，数值大小没有业务含义（随字段长度/文档频率变化），
 * 把权重等值断言搬到 ES 上只会得到一批「红得没有信息量」的失败，
 * 所以这里显式跳过并写明原因——而不是删掉断言，MySQL 模式下它们仍然生效。
 */
function checkMysql(name, cond, extra) {
  if (ENGINE === 'mysql') {
    check(name, cond, extra);
  } else {
    skip.push(`${name}（引擎=${ENGINE}：BR-15-011 的加和权重是 MySQL 实现口径）`);
    console.log(`  \x1b[33m•\x1b[0m 跳过｜${name}：当前引擎=${ENGINE}，ES 的 _score 不是权重加和`);
  }
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
    /* 非 JSON（比如 HTML 错误页） */
  }
  return { status: res.status, json, text, elapsedMs, headers: res.headers };
}

// ---------------------------------------------------------------- redis helper

/**
 * 执行一条 redis-cli 命令。**命令走 stdin，不走 argv**。
 *
 * <p>理由是本机实测出来的一个静默陷阱：Windows 上的 redis-cli 是 ANSI 入口
 * （{@code main(int, char**)}），命令行里的非 ASCII 参数在被它拿到之前，
 * 会先按本机 ANSI 代码页（GBK）转一遍。于是成员名「热搜A123」变成一串 GBK 字节，
 * 与 JVM 用 UTF-8 写进 ZSet 的成员永远对不上，{@code zscore} **静默返回空**——
 * 不报错、不告警，看起来就像「这个功能根本没实现」。
 *
 * <p>同一个成员、同一条命令，两种传法的实测结果：
 * <pre>
 *   redis-cli zscore ml:search:hot:realtime 热搜A1792084026781   → （空）
 *   printf 'zscore ml:search:hot:realtime 热搜A1792084026781\n' | redis-cli → 1
 * </pre>
 *
 * <p>stdin 是字节流，UTF-8 原样透传，所以凡是键或成员可能含非 ASCII 的调用都必须走这里。
 * 含空白的参数补上双引号（redis-cli 的 stdin 解析器认引号，与 redis.conf 同一套规则）。
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
const rDel = (...keys) => redis('del', ...keys);
const rExists = (k) => redis('exists', k) === '1';
const rZScore = (k, m) => {
  const v = redis('zscore', k, m);
  return v === '' ? null : Number(v);
};
const listKeys = (pattern) => {
  const out = redis('keys', pattern);
  return out === '' ? [] : out.split('\n').map((s) => s.trim()).filter(Boolean);
};
const mlSearchKeys = () => listKeys('ml:search:*');
/** 共享 db0 里另一个项目的键。只数，不读不写 */
const rankingKeys = () => listKeys('ranking:*');

/** 起手就记一份，收尾再比一次——用「键数没变」证明脚本没碰别的项目 */
const RANKING_BEFORE = rankingKeys();

const HOT_ZSET = 'ml:search:hot:realtime';
const HOT_SNAPSHOT = 'ml:search:hot:snapshot';

// ---------------------------------------------------------------- mysql helper

/**
 * 写库型用例（§12）用。**SQL 走 stdin，不走 argv**——与 redis-cli 是同一个坑：
 * 本机的 mysql 客户端也是 ANSI 入口，argv 里的非 ASCII 会先被按 GBK 转一遍。
 *
 * 这里比 redis 那处更进一步：**SQL 文本里根本不出现非 ASCII**。中文一律用
 * {@link utf8Hex} 包成 `CONVERT(0x… USING utf8mb4)` 的十六进制字面量，
 * 于是「控制台代码页是什么」「客户端连接字符集是什么」这两个问题被一并绕开——
 * 不依赖任何一处的编码设置正确，也就不需要为了跑通而改本机环境。
 *
 * <p><b>口令只从环境变量 `MYSQL_PASSWORD` 读</b>（与 `application.yml` 的
 * `${MYSQL_PASSWORD:root}` 是同一个变量），并且用 `MYSQL_PWD` 传给客户端而不是
 * 拼进命令行。脚本是要提交的，**任何形式的明文口令都不许出现在文件里**，
 * 也不该出现在进程列表里。没设这个变量时整个 §12 跳过，而不是静默变绿。
 *
 * <p>所有语句都带 `newmusic.` 限定符。这台机器上的 MySQL 是**共享实例**，
 * 还放着别的项目的库（emp_db / music_db），不加限定的 DDL/DML 是严禁的。
 */
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_DB = process.env.MYSQL_DB || 'newmusic';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';

/** 非 ASCII 字面量 → `CONVERT(0x… USING utf8mb4)`，全程只走字节 */
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

// ---------------------------------------------------------------- 应用日志

/**
 * 读 boot.log，返回匹配的行。用它做「回填真的写了几行」这类硬证据。
 *
 * ⚠ 本机 JDK 1.8 的 `file.encoding` 是 GBK，日志里的中文是按 GBK 落盘的。
 *   所以**只按 ASCII 锚点匹配**（类名、SQL 片段），绝不用中文串去 grep——
 *   那种断言会永远为假，而且看起来像是「功能没实现」。
 */
function grepLog(pattern) {
  if (!logPath) return [];
  try {
    return readFileSync(logPath, 'latin1') // latin1 = 逐字节映射，不因解码失败而丢行
      .split('\n')
      .filter((line) => pattern.test(line));
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------- 小工具

function p95(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)];
}

const names = (list) => (list || []).map((x) => x && x.name).filter(Boolean);
const ids = (list) => (list || []).map((x) => x && x.id).filter(Boolean);
const byName = (list, name) => (list || []).find((x) => x && x.name === name);

// ---------------------------------------------------------------- 准备

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

async function loginAs({ account, password }) {
  // 幂等：已存在则忽略注册失败，直接登录
  await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password, nickname: account },
  });
  const res = await call('POST', '/auth/login', { xff: syntheticIp(), body: { account, password } });
  if (res.json?.code !== 0) throw new Error(`账号登录失败 ${account}: ${JSON.stringify(res.json)}`);
  return { token: res.json.data.accessToken, userId: res.json.data.user.id };
}

/** 搜索的语法糖：游客带 XFF，登录带 token */
const search = (query, { token, section = 'default' } = {}) =>
  call('GET', `/search?${new URLSearchParams(query)}`, token ? { token } : { xff: ipOf(section) });

const clearLimit = (type, dimension, identifier, api) => rDel(`ml:limit:${type}:${dimension}:${identifier}:${api}`);

// ================================================================ Stage A

async function stageA(probe) {
  console.log('\n\x1b[1m════ Stage A：检索 / 拼音 / 联想 / 筛选排序 / 深分页 / 限流 ════\x1b[0m');

  // ================================================================ §01 基础检索
  section('§01 基础检索（AC-15-001-1/2/3/5/7、AC-15-100）');

  // ---- AC-15-001-1：搜「晴天」Top 3 命中《晴天》
  const basic = await search({ keyword: '晴天', type: 'song' }, { section: 'basic' });
  check('code = 0', basic.json?.code === 0, basic.json);
  ENGINE = basic.json?.data?.engine || 'mysql';
  note(`本次运行命中的搜索引擎 = ${ENGINE}（响应体 engine 字段）`
    + (ENGINE === 'es' ? '：BR-15-011 的权重等值断言会转为跳过' : ''));
  check('type=song 走 list 字段（不是四段）', Array.isArray(basic.json?.data?.list), basic.json?.data);
  check('Top 3 含《晴天》（AC-15-001-1）',
    ids(basic.json?.data?.list).slice(0, 3).includes(SEED.songQingTian), names(basic.json?.data?.list));
  check('total = 1（种子里只有《晴天》的文本含「晴天」）', basic.json?.data?.total === 1, basic.json?.data?.total);
  const hit = byName(basic.json?.data?.list, '晴天');
  check('matchedFields 含 name', (hit?.matchedFields || []).includes('name'), hit?.matchedFields);
  // 得分是**加和**的，不是「取命中的那个字段的权重」。《晴天》的歌词正文里也写着「晴天」
  // （demo 歌词第一行就是歌名），所以 5(name) + 1(lyric) = 6。
  // 系分 §8 的 AC-15-001-1 原写「score = 5」，那是只考虑歌名命中时的取值，已在 §9 更正。
  checkMysql('score = 6 = name 5 + lyric 1（BR-15-011 的权重相加）', hit?.score === 6, hit?.score);
  check('带 artistName / albumName（前端列表要显示副标题）',
    hit?.artistName === '周杰伦' && hit?.albumName === '叶惠美', hit);
  check('copyright_status=1 → playable=true', hit?.playable === true, hit?.playable);

  // ---- AC-15-001-7：高亮
  check('highlight.name 恰为 <em class="hl">晴天</em>（AC-15-001-7）',
    hit?.highlight?.name === '<em class="hl">晴天</em>', hit?.highlight);
  check('degraded=false（首选引擎可用）', basic.json?.data?.degraded === false, basic.json?.data?.degraded);
  check('degraded 响应头 = false（D-48 双写）',
    basic.headers.get('degraded') === 'false', basic.headers.get('degraded'));
  check('costMs 是非负整数', Number.isInteger(basic.json?.data?.costMs) && basic.json.data.costMs >= 0,
    basic.json?.data?.costMs);
  check('结果不足一页时 cursor 为 null', !basic.json?.data?.cursor, basic.json?.data?.cursor);

  // ---- AC-15-001-2：搜「周杰伦」返回该歌手及其歌曲
  const all = await search({ keyword: '周杰伦', type: 'all' }, { section: 'all' });
  const d = all.json?.data || {};
  check('综合 Tab 四段齐全（都是 PageResult 形状）',
    d.songs && d.artists && d.albums && d.playlists &&
      Array.isArray(d.songs.list) && Array.isArray(d.artists.list) &&
      Array.isArray(d.albums.list) && Array.isArray(d.playlists.list), Object.keys(d));
  check('artists 段含周杰伦（AC-15-001-2）', names(d.artists?.list).includes('周杰伦'), names(d.artists?.list));
  check('songs 段含《晴天》《稻香》',
    names(d.songs?.list).includes('晴天') && names(d.songs?.list).includes('稻香'), names(d.songs?.list));
  check('albums 段含《叶惠美》《魔杰座》（靠冗余 artist_name 命中，权重 2）',
    names(d.albums?.list).includes('叶惠美') && names(d.albums?.list).includes('魔杰座'), names(d.albums?.list));
  check('songs 段配额 ≤ 10（BR-15-004）', (d.songs?.list?.length || 0) <= 10, d.songs?.list?.length);
  check('artists 段配额 ≤ 3（BR-15-004）', (d.artists?.list?.length || 0) <= 3, d.artists?.list?.length);
  check('歌手段带 songCount（前端要显示「N 首歌」）', d.artists?.list?.[0]?.songCount !== undefined, d.artists?.list?.[0]);
  check('playlists 段为空壳而不是缺字段（前端才能用同一套渲染）',
    Array.isArray(d.playlists?.list) && d.playlists.list.length === 0, d.playlists);
  check('综合 Tab 不返回 list / total 之外的噪音（NON_NULL：字段存在 == 它有值）',
    d.list === undefined, Object.keys(d));

  // ---- AC-15-001-3：搜歌词片段
  const lyricHit = await search({ keyword: '小黄花', type: 'song' }, { section: 'lyric' });
  const qingTianHit = byName(lyricHit.json?.data?.list, '晴天');
  check('搜「小黄花」命中《晴天》（AC-15-001-3）', Boolean(qingTianHit), names(lyricHit.json?.data?.list));
  check('matchedFields 含 lyric（而不是靠歌名碰巧命中）',
    (qingTianHit?.matchedFields || []).includes('lyric'), qingTianHit?.matchedFields);
  checkMysql('歌词得分 1（BR-15-011 的 lyric 权重）', qingTianHit?.score === 1, qingTianHit?.score);
  const snip = qingTianHit?.highlight?.lyric;
  check('歌词命中给出已高亮的片段', typeof snip === 'string' && snip.includes('<em class="hl">小黄花</em>'), snip);
  check('片段是片段而非整篇（D-36 窗口 = 命中前后各 30 字）', (snip || '').length < 120, (snip || '').length);
  check('片段里没有 LRC 时间轴（REGEXP_REPLACE 已剥离）', !/\[\d\d:/.test(snip || ''), snip);

  // 分类 Tab 的 type=lyric：在 SONG 的基础上再加 mt_lyric = 1
  const lyricOnly = await search({ keyword: '小黄花', type: 'lyric' }, { section: 'lyric' });
  check('type=lyric 同样命中《晴天》', names(lyricOnly.json?.data?.list).includes('晴天'),
    names(lyricOnly.json?.data?.list));
  const lyricNone = await search({ keyword: '稻香', type: 'lyric' }, { section: 'lyric' });
  check('type=lyric 只回歌词命中的歌（《稻香》歌名命中但歌词不含"稻香"）',
    !names(lyricNone.json?.data?.list).includes('稻香') || (
      (lyricNone.json?.data?.list || [])[0]?.matchedFields || []).includes('lyric'),
    names(lyricNone.json?.data?.list));

  // ---- AC-15-001-5：P95 ≤ 200ms（30 次分散到 3 个 IP，避免自己被限流）
  const costMsList = [];
  const e2eList = [];
  let p95AllOk = true;
  for (let i = 0; i < 30; i++) {
    const r = await search({ keyword: '晴天', type: 'song' }, { section: `p95-${i % 3}` });
    if (r.json?.code !== 0) p95AllOk = false;
    costMsList.push(r.json?.data?.costMs ?? 0);
    e2eList.push(r.elapsedMs);
  }
  const p95Cost = p95(costMsList);
  const p95E2e = p95(e2eList);
  check('30 次全部 code=0（P95 不能是在给被限流的拒绝响应算分位数）', p95AllOk);
  check(`costMs P95 ≤ 200ms（AC-15-001-5，实测 ${p95Cost.toFixed(1)}ms）`, p95Cost <= 200, costMsList);
  check(`端到端 P95 ≤ 200ms（实测 ${p95E2e.toFixed(1)}ms）`, p95E2e <= 200, e2eList);
  note('§9 已登记：种子只 6 行，P95 不具规模意义（LIKE \'%kw%\' 全表扫 + 逐行 REGEXP_REPLACE）');

  // ---- 空关键词（BR-15-001）
  const empty = await search({ type: 'song' }, { section: 'empty' });
  check('空关键词 code = 0', empty.json?.code === 0, empty.json);
  check('空关键词返回热搜词（BR-15-001）', (empty.json?.data?.hotWords || []).length > 0, empty.json?.data?.hotWords);
  check('空关键词的四段都是空壳（前端用同一套渲染）',
    [empty.json?.data?.songs, empty.json?.data?.artists, empty.json?.data?.albums, empty.json?.data?.playlists]
      .every((p) => p && Array.isArray(p.list) && p.list.length === 0), empty.json?.data);
  check('空关键词不给 keyword 字段（NON_NULL）', empty.json?.data?.keyword === undefined, empty.json?.data?.keyword);
  const emptyAsUser = await search({ type: 'song' }, { token: probe.token });
  check('登录用户空关键词额外返回历史（未登录时无此字段）',
    Array.isArray(emptyAsUser.json?.data?.history), emptyAsUser.json?.data?.history);

  // ---- AC-15-001-7 的另一半：建一首名字里带 <script> 的临时歌曲，验证存储型 XSS 被转义
  // 放在 §01 最后：它会短暂进入检索语料（歌手是周杰伦），建完立刻删，不干扰后续小节
  const admin = await loginAs(ADMIN);
  const marker = `冒烟XSS${Date.now()}`;
  const created = await call('POST', '/music/songs', {
    token: admin.token,
    body: {
      name: `<script>alert(1)</script>${marker}`,
      artistId: SEED.artistJay,
      albumId: SEED.albumYeHuiMei,
      duration: 200,
      language: '国语',
      genre: '流行',
      copyrightStatus: 1,
      copyrightExpire: '2030-12-31',
      audioStandard: '/media/demo/demo-01.wav',
      auditStatus: 1,
      status: 1,
    },
  });
  check('临时 XSS 歌曲创建成功（否则下面 4 条是空转）', created.json?.code === 0, created.json);
  if (created.json?.code === 0) {
    // 清理用的 id 取自**创建响应**而不是搜索结果。
    // 这不是洁癖：第一轮全量跑的时候搜索接口还是 401，xssHit 是 undefined，
    // DELETE /music/songs/undefined 静默失败，那首 <script> 歌曲就留在语料里了——
    // 之后每个搜「周杰伦」的小节都会多出一条脏数据，而失败原因显示在几百行之外。
    // 写进语料的临时数据，必须用不依赖任何后续请求的方式回收。
    const createdId = created.json?.data?.id;
    const xssSearch = await search({ keyword: marker, type: 'song' }, { section: 'xss' });
    const xssHit = (xssSearch.json?.data?.list || [])[0];
    check('临时歌曲可被搜到', Boolean(xssHit), names(xssSearch.json?.data?.list));
    const hl = xssHit?.highlight?.name || '';
    check('name 里的 <script> 被转义（存储型 XSS 的唯一防线，D-37）', !/<script/i.test(hl), hl);
    check('转义成实体 &lt;script&gt;', hl.includes('&lt;script&gt;'), hl);
    check('标记词仍被正常高亮（转义没有把高亮一起吃掉）', hl.includes(`<em class="hl">${marker}</em>`), hl);
    const cleaned = await call('DELETE', `/music/songs/${createdId ?? xssHit?.id}`, { token: admin.token });
    check('临时歌曲已清理（软删）', cleaned.json?.code === 0, cleaned.json);
    const gone = await search({ keyword: marker, type: 'song' }, { section: 'xss' });
    check('清理后搜不到（deleted=0 生效）', (gone.json?.data?.list || []).length === 0,
      names(gone.json?.data?.list));
  }

  // ================================================================ §02 拼音
  section('§02 拼音检索（AC-15-002-1/2/3/4、AC-15-101）');

  // 硬证据：启动日志里的回填结果。拼音列是写时物化的，没有回填就搜不到存量数据。
  // 日志是 GBK 的（见 grepLog 的注释），所以这里只认 ASCII 锚点，中文部分用 `=` 后的数字读回
  const backfillLines = grepLog(/PinyinBackfill/);
  check('启动回填组件真的执行了（T611：日志里有 PinyinBackfill 的输出）', backfillLines.length > 0,
    backfillLines.length);
  const backfillWrites = grepLog(/SET name_pinyin=/).length;
  const counts = [...backfillLines.join(' ').matchAll(/=(\d+)/g)].map((m) => Number(m[1]));
  note(counts.length === 3
    ? `回填量（歌曲/歌手/专辑）= ${counts.join(' / ')}，UPDATE 语句 ${backfillWrites} 条`
    : '回填本次无需处理（列已填好）；UPDATE 语句 0 条');
  check('拼音列不是空的 —— 由下面「qingtian 命中《晴天》」这一条功能断言反向证明',
    backfillLines.length > 0 && (backfillWrites > 0 || counts.length !== 3));

  // AC-15-002-1：qingtian 命中《晴天》（全拼，%kw%）
  const byFull = await search({ keyword: 'qingtian', type: 'song' }, { section: 'pinyin' });
  const fullHit = byFull.json?.data?.list?.[0];
  check('「qingtian」命中《晴天》（AC-15-002-1）', fullHit?.name === '晴天', names(byFull.json?.data?.list));
  check('matchedFields 是 name.pinyin（而不是 name）',
    (fullHit?.matchedFields || []).includes('name.pinyin'), fullHit?.matchedFields);
  check('拼音命中不给 highlight（字符对不上，给了等于没给）', !fullHit?.highlight?.name, fullHit?.highlight);
  checkMysql('「qingtian」得分 3 = 全拼权重（BR-15-011）', fullHit?.score === 3, fullHit?.score);

  // AC-15-002-2：qt 命中《晴天》（首字母，D-46：前缀 + 长度 ≥ 2）
  const byFirst = await search({ keyword: 'qt', type: 'song' }, { section: 'pinyin' });
  const firstHit = byFirst.json?.data?.list?.[0];
  check('「qt」命中《晴天》（AC-15-002-2）', firstHit?.name === '晴天', names(byFirst.json?.data?.list));
  check('matchedFields 含 name.pinyin.first', (firstHit?.matchedFields || []).includes('name.pinyin.first'),
    firstHit?.matchedFields);
  checkMysql('首字母得分 4 > 全拼得分 3（D-35 的权重表）', firstHit?.score === 4, firstHit?.score);
  // D-46 的守卫**只管首字母前缀那一项**（权重 4，且是 LIKE 'kw%'）。全拼仍是 `%kw%`（权重 3），
  // 这一条是 D-46 里写明的取舍：PRD 的 ES 侧开了 fuzziness: AUTO，`%kw%` 相对它仍是收紧。
  // 所以「q 不召回任何东西」这个预期是错的（q 会命中所有拼音含 q 的歌），
  // 该断言的是守卫本身生效：没有任何一条结果的命中来源是 name.pinyin.first。
  // 用 matchedFields 而不是 score 来断言，是因为前者直接是「哪一项参与了打分」的账本。
  const single = await search({ keyword: 'q', type: 'song' }, { section: 'pinyin' });
  const singleList = single.json?.data?.list || [];
  check('单字符不参与首字母前缀匹配（D-46）：没有结果的 matchedFields 含 name.pinyin.first',
    singleList.every((x) => !(x.matchedFields || []).includes('name.pinyin.first')),
    singleList.map((x) => `${x.name}[${(x.matchedFields || []).join('+')}]`));
  checkMysql('对照：同为前缀项的「qt」拿到 4 分 —— 证明上一条是守卫在起作用，不是这条查询本身不工作',
    firstHit?.score === 4 && singleList.length > 0,
    { qt: firstHit?.score, q: singleList.map((x) => `${x.name}:${x.score}`) });

  // AC-15-002-3：zjl 命中歌手「周杰伦」
  const byInitials = await search({ keyword: 'zjl', type: 'all' }, { section: 'pinyin' });
  const zjlArtists = byInitials.json?.data?.artists?.list || [];
  check('「zjl」在 artists 段命中周杰伦（AC-15-002-3）',
    names(zjlArtists).includes('周杰伦'), names(zjlArtists));
  check('歌手段 matchedFields 含 name.pinyin.first',
    (zjlArtists[0]?.matchedFields || []).includes('name.pinyin.first'), zjlArtists[0]?.matchedFields);

  // 歌曲侧的歌手全拼（artist_name_pinyin，权重 3）
  const byArtistPy = await search({ keyword: 'zhoujielun', type: 'song' }, { section: 'pinyin' });
  const pySongNames = names(byArtistPy.json?.data?.list);
  check('「zhoujielun」按歌手全拼命中《晴天》《稻香》',
    pySongNames.includes('晴天') && pySongNames.includes('稻香'), pySongNames);
  check('命中字段是 artist.name.pinyin',
    (byArtistPy.json?.data?.list?.[0]?.matchedFields || []).includes('artist.name.pinyin'),
    byArtistPy.json?.data?.list?.[0]?.matchedFields);

  // AC-15-002-4：拼音不影响中文检索（原文优先 + 含中文时不查拼音列）
  // 断言写成「中文命中 > 拼音命中」而不是「中文命中 = 5」：前者是这条 AC 要表达的**关系**
  // （原文永远压过拼音），后者把 AC 绑死在某个字段组合上——一旦歌词也含歌名就会红，
  // 而那次「红」并不说明拼音检索坏了。关系断言对数据变化是稳的，等值断言不是。
  const cnHit = byName(basic.json?.data?.list, '晴天');
  // 只断言关系，不钉死绝对值：MySQL 下是 6 > 3，ES 下是 name^5 与 namePinyin^3 的
  // BM25 得分之比。「原文压过拼音」是这条 AC 的意思，具体几分是实现口径。
  check('原文优先：中文命中得分 > 拼音命中得分（AC-15-002-4）',
    cnHit?.score > fullHit?.score,
    { cn: cnHit?.score, py: fullHit?.score, engine: ENGINE });
  check('中文命中走 name 字段，不靠拼音列（AC-15-002-4 的后半句）',
    (cnHit?.matchedFields || []).includes('name') &&
      !(cnHit?.matchedFields || []).includes('name.pinyin'), cnHit?.matchedFields);
  check('含中文时不匹配拼音列（BR-15-014）',
    !(cnHit?.matchedFields || []).some((f) => f.startsWith('name.pinyin')), cnHit?.matchedFields);
  check('「晴天」与「qingtian」命中同一首、走的却是两条不同路径',
    cnHit?.id === fullHit?.id && cnHit?.matchedFields.join() !== fullHit?.matchedFields.join(),
    { cn: cnHit?.matchedFields, py: fullHit?.matchedFields });
  note('系分 §8 原写「qingtian 得分 4」，实测为 3（name_pinyin_first 只做前缀匹配，'
    + '「qingtian」不以「qt」开头）——已同步更正 §8');

  // 已知缺口（MySQL 实现）：t_song.artist_name_pinyin_first 有列、有回填，但检索 SQL 从不读它。
  // ES 侧读的是文档里的 artistNamePinyinFirst，所以两种引擎在这里**行为不同**：
  // MySQL 搜「zjl」出 0 首，ES 出周杰伦的歌。这不是 bug 掩盖，而是必须被登记的口径差。
  const gapProbe = await search({ keyword: 'zjl', type: 'song' }, { section: 'pinyin' });
  const gapNames = names(gapProbe.json?.data?.list);
  if (ENGINE === 'es') {
    check('歌曲侧按歌手首字母可搜到（ES：zjl → 周杰伦的歌）',
      gapNames.includes('晴天') || gapNames.includes('稻香'), gapNames);
    note('引擎口径差已登记 §9：MySQL 实现不读 artist_name_pinyin_first（zjl 搜不到歌），'
      + 'ES 读 artistNamePinyinFirst。切引擎会改变这一条的结果');
  } else {
    check('歌曲侧的歌手首字母不被检索（MySQL 实现的事实：zjl 搜不到歌）',
      gapNames.length === 0, gapNames);
    note('登记 §9：t_song.artist_name_pinyin_first 被 T611 回填却无人查询——要么在 songScore 加一项'
      + '（会改排序权重），要么停止回填该列。本轮按「不改权重」处理');
  }

  // ================================================================ §03 联想
  section('§03 搜索联想（AC-15-003-1/2、AC-15-102）');

  const sug = await call('GET', '/search/suggest?keyword=%E5%91%A8', { xff: ipOf('suggest') });
  const items = sug.json?.data?.suggestions || [];
  check('code = 0', sug.json?.code === 0, sug.json);
  check('「周」有联想结果', items.length > 0, items);
  check('第一条是歌手（AC-15-003-2：歌手 > 歌曲 > 专辑 > 歌单）', items[0]?.type === 'artist', items[0]);
  check('歌手项 highlight 包裹关键词', /<em class="hl">周<\/em>/.test(items[0]?.highlight || ''), items[0]);
  const songItem = items.find((x) => x.type === 'song');
  check('歌曲项的 extra 是歌手名（前端副标题）', !songItem || songItem.extra === '周杰伦', songItem);
  check('联想条数 ≤ 10（BR-15-015）', items.length <= 10, items.length);
  check('建议项都是可展示的词（无单字符/纯符号）', items.every((x) => (x.text || '').length >= 2), items);

  const sugPy = await call('GET', '/search/suggest?keyword=zhou', { xff: ipOf('suggest') });
  const pyItems = sugPy.json?.data?.suggestions || [];
  check('联想支持拼音（zhou → 周杰伦）', pyItems.some((x) => x.text === '周杰伦'),
    pyItems.map((x) => x.text));
  check('拼音命中的项不给 highlight（字符对不上）',
    !(pyItems.find((x) => x.text === '周杰伦')?.highlight), pyItems.find((x) => x.text === '周杰伦'));

  const sugEmpty = await call('GET', '/search/suggest?keyword=', { xff: ipOf('suggest') });
  check('空关键词 → suggestions 空数组', (sugEmpty.json?.data?.suggestions || []).length === 0,
    sugEmpty.json?.data);
  check('空关键词 → 给热搜词兜底（BR-15-019）', (sugEmpty.json?.data?.hotWords || []).length > 0,
    sugEmpty.json?.data?.hotWords);

  const sugMiss = await call('GET', '/search/suggest?keyword=zzqqxx', { xff: ipOf('suggest') });
  check('联想无结果 → 给热搜兜底而不是空下拉（BR-15-021）',
    (sugMiss.json?.data?.suggestions || []).length === 0 &&
      (sugMiss.json?.data?.hotWords || []).length > 0, sugMiss.json?.data);

  // ⚠️ 想断言「缓存 TTL 是 1h」有两个坑，本轮各踩了一次（实测 3673s 与 2710s 各红一回）：
  //
  // ① 不能用 `listKeys(...)[0]`：这台机器的 `redis-cli --scan` 静默返回空（见记忆），
  //    所以列举走的是 `keys`，**返回顺序无保证**，`[0]` 很可能是上一轮脚本遗留的键；
  // ② `TTL` 给的是**剩余**秒数而不是写入时的值，于是断言会随「上次跑脚本是多久以前」抛硬币。
  //
  // 而且清 L2 后重新请求**不一定**会重建它：联想是两级缓存（模块 03 的 Caffeine L1 + Redis L2），
  // **L1 命中时不回落 Redis**。「周」在本节前面已经被打过几十次（含 P95 那 20 次），
  // L1 必然是热的——我 `del` 掉 L2 再请求，请求被 L1 接走，L2 根本没被写回，`exists` 当然是 0。
  //
  // 所以探测词必须**让缓存键（关键词前 8 字）本轮唯一**，而不是只有关键词本身唯一：
  // 早先用的 `zzq${Date.now().toString(36)}` 前 8 字在 ~47 秒内完全相同（base36 时间戳的
  // 第 5 位要 36^3ms≈46.7s 才进位一次），`zhoutest${...}` 的前 8 字更是每轮都撞。
  // 连跑两次、或刚跑过只含 §01~§03 的调试轮，就会在 L1 的 30s 生命期内命中旧键，
  // 请求被 L1 接走、不回写 L2——实测第三轮因此红了 3 条，看起来像缓存失效。
  // 随机 8 字前缀同时满足两个用途：L1 必冷，且截断用例能证明「只取前 8 字符」。
  const random8 = () => Math.random().toString(36).slice(2, 10).padEnd(8, '0');
  const suggestKeyOf = (kw) => `ml:search:suggest:${Array.from(kw).slice(0, 8).join('')}`;
  const ttlProbe = `zzq${random8()}`;
  const keyProbe = suggestKeyOf(ttlProbe);
  redis('del', keyProbe);
  await call('GET', `/search/suggest?keyword=${encodeURIComponent(ttlProbe)}`, { xff: ipOf('suggest') });
  check('联想结果进了缓存（唯一探测词，L1 必冷，不靠 keys 顺序）', rExists(keyProbe), keyProbe);

  // BR-15-020 的基础值是 1h，§4.6 给它加了 ±10% 抖动（防同一时刻集体过期），
  // 所以合法区间是 3240~3960s，而不是「≤3600」。下限同样要有：
  // 没有它，TTL 被误设成很小的值（甚至 -1）也会被判绿。
  const sugTtl = rTtl(keyProbe);
  check(`联想缓存 TTL 落在 1h±10% 区间（BR-15-020 + §4.6，实测 ${sugTtl}s）`,
    sugTtl >= 3240 && sugTtl <= 3960, `ttl=${sugTtl}`);
  redis('del', keyProbe);

  // 截断规则用超长关键词正面验一次，而不是只看既有键的集合性质。
  // 随机前缀必须放在**最前面**：`zhoutest` 恰好就是 8 字，放前面等于每轮都用同一个键。
  const longKw = `${random8()}truncatetest`;
  redis('del', suggestKeyOf(longKw));
  await call('GET', `/search/suggest?keyword=${encodeURIComponent(longKw)}`, { xff: ipOf('suggest') });
  check('缓存键截到前 8 字符（BR-15-020）', rExists(suggestKeyOf(longKw)), suggestKeyOf(longKw));
  redis('del', suggestKeyOf(longKw));

  const sugKeys = listKeys('ml:search:suggest:*');
  check('所有 ml:search:suggest:* 的后缀都不超过 8 字符',
    sugKeys.every((k) => k.slice('ml:search:suggest:'.length).length <= 8), sugKeys);

  const cold1 = await call('GET', '/search/suggest?keyword=%E5%91%A8', { xff: ipOf('suggest') });
  const cold2 = await call('GET', '/search/suggest?keyword=%E5%91%A8', { xff: ipOf('suggest') });
  check('缓存被删后仍返回同样结果（缓存不是唯一数据源）',
    JSON.stringify(cold1.json?.data?.suggestions) === JSON.stringify(cold2.json?.data?.suggestions),
    cold1.json?.data);

  // AC-15-003-1：P95 ≤ 100ms
  const sugLatency = [];
  let sugAllOk = true;
  for (let i = 0; i < 20; i++) {
    const r = await call('GET', '/search/suggest?keyword=%E5%91%A8', { xff: ipOf('suggest') });
    if (r.json?.code !== 0) sugAllOk = false;
    sugLatency.push(r.elapsedMs);
  }
  const sugP95 = p95(sugLatency);
  check('20 次联想全部 code=0', sugAllOk);
  check(`联想端到端 P95 ≤ 100ms（AC-15-003-1，实测 ${sugP95.toFixed(1)}ms）`, sugP95 <= 100, sugLatency);
  note('防抖 300ms 是前端行为（T614），脚本只能验后端 P95');

  // AC-15-003-3 的敏感词面需要写 t_sys_config 并等过缓存窗口，放在 §12（写库型用例）里，
  // 和 AC-15-005-2/005-4 共用一次快照重建——见那个阶段的注释。

  // ================================================================ §06 筛选排序
  section('§06 筛选与排序（AC-15-006-1/2/3、AC-15-104）');

  // keyword=天 在**种子里**命中 3 首：晴天（歌名）、倔强 + 突然好想你（冗余 artist_name「五月天」）。
  // 演示数据里另有 7 首也含「天」，故条数断言一律先按 SEED_SONG_IDS 过滤（见文件头的说明）。
  const base = await search({ keyword: '天', type: 'song' }, { section: 'filter' });
  const baseList = base.json?.data?.list || [];
  const baseSeed = baseList.filter(isSeedSong);
  check('「天」命中种子里那 3 首（歌名 + 冗余歌手名 + 专辑名都参与）',
    baseSeed.length === 3 &&
      names(baseSeed).slice().sort().join() === ['晴天', '倔强', '突然好想你'].sort().join(),
    { seedHits: names(baseSeed), total: baseList.length });
  // 默认排序是「score 降序，同分按 hotScore 降序」（D-34）。**不能**只断言 hotScore 单调：
  // 演示数据里《平凡的一天》得分高但热度低，它本来就该排在低分高热的歌前面。
  const baseOrder = baseList.map((x) => ({ name: x.name, score: x.score, hot: x.hotScore }));
  check('默认排序 = 相关性降序 → 同分按热度降序（D-34）',
    baseOrder.every((x, i) => i === 0 ||
      baseOrder[i - 1].score > x.score ||
      (baseOrder[i - 1].score === x.score && baseOrder[i - 1].hot >= x.hot)),
    baseOrder);

  const byHot = await search({ keyword: '天', type: 'song', sort: 'hot' }, { section: 'filter' });
  const hotScores = (byHot.json?.data?.list || []).map((x) => x.hotScore);
  check('sort=hot 按 hotScore 降序', hotScores.every((v, i) => i === 0 || hotScores[i - 1] >= v), hotScores);

  const byDur = await search({ keyword: '天', type: 'song', sort: 'duration_asc' }, { section: 'filter' });
  const durs = (byDur.json?.data?.list || []).map((x) => x.duration);
  check('sort=duration_asc 按时长升序', durs.every((v, i) => i === 0 || durs[i - 1] <= v), durs);
  const byDurDesc = await search({ keyword: '天', type: 'song', sort: 'duration_desc' }, { section: 'filter' });
  const dursDesc = (byDurDesc.json?.data?.list || []).map((x) => x.duration);
  check('sort=duration_desc 按时长降序', dursDesc.every((v, i) => i === 0 || dursDesc[i - 1] >= v), dursDesc);

  // ---- AC-15-006-1
  const rock = await search({ keyword: '天', type: 'song', genre: '摇滚' }, { section: 'filter' });
  const rockSeedList = (rock.json?.data?.list || []).filter(isSeedSong);
  const rockNames = names(rockSeedList);
  check('genre=摇滚 在种子里只回《倔强》《突然好想你》（AC-15-006-1）',
    rockNames.slice().sort().join() === ['倔强', '突然好想你'].sort().join(), rockNames);
  const rock00 = await search({ keyword: '天', type: 'song', genre: '摇滚', era: '00s' }, { section: 'filter' });
  const rock00Names = names((rock00.json?.data?.list || []).filter(isSeedSong));
  check('genre=摇滚&era=00s 种子结果不变（两首都是 00s）',
    rock00Names.slice().sort().join() === rockNames.slice().sort().join(), rock00Names);
  const cantonese = await search({ keyword: '天', type: 'song', language: '粤语' }, { section: 'filter' });
  check('language=粤语 在种子里为空（种子里没有粤语歌，AC-15-006-1）',
    (cantonese.json?.data?.list || []).filter(isSeedSong).length === 0,
    names(cantonese.json?.data?.list));
  const multi = await search({ keyword: '天', type: 'song', language: '国语,粤语' }, { section: 'filter' });
  check('多值筛选按 IN 展开（国语,粤语 在种子里仍回 3 首）',
    (multi.json?.data?.list || []).filter(isSeedSong).length === 3, names(multi.json?.data?.list));
  const longOnly = await search({ keyword: '天', type: 'song', duration: 'gt5' }, { section: 'filter' });
  check('duration=gt5 在种子里只回 >300s 的《突然好想你》(347s)',
    names((longOnly.json?.data?.list || []).filter(isSeedSong)).join() === '突然好想你',
    names(longOnly.json?.data?.list));
  const shortOnly = await search({ keyword: '天', type: 'song', duration: 'lt3' }, { section: 'filter' });
  check('duration=lt3 在种子里为空（3 首都 ≥180s），未知/越界取值都不放宽',
    (shortOnly.json?.data?.list || []).filter(isSeedSong).length === 0,
    names(shortOnly.json?.data?.list));
  const unknownDur = await search({ keyword: '天', type: 'song', duration: 'abc' }, { section: 'filter' });
  check('未知 duration 取值判为「不匹配」而非忽略（否则脏参数会静默返回全集）',
    (unknownDur.json?.data?.list || []).length === 0, names(unknownDur.json?.data?.list));

  // ---- AC-15-006-2：筛选用 filter 实现，_score 不变
  const scoreOf = (list, id) => (list || []).find((x) => x.id === id)?.score;
  const baseScorePairs = baseSeed.map((x) => `${x.id}:${x.score}`).sort();
  const rockScorePairs = rockSeedList.map((x) => `${x.id}:${x.score}`).sort();
  check('加筛选后命中项的 score 完全相同（AC-15-006-2：筛选不打分）',
    rockScorePairs.length === 2 && rockScorePairs.every((s) => baseScorePairs.includes(s)),
    { base: baseScorePairs, filtered: rockScorePairs });
  check('筛选只删行、不改相对顺序',
    rockNames.join() === names(baseSeed.filter((x) => x.genre === '摇滚')).join(),
    { filtered: rockNames, fromBase: names(baseSeed.filter((x) => x.genre === '摇滚')) });
  note(ENGINE === 'mysql'
    ? `score 硬证据：《晴天》${scoreOf(baseList, SEED.songQingTian)}（name 5）、`
      + `《倔强》${scoreOf(baseList, SEED.songJueJiang)}（artist_name 3）、`
      + `《突然好想你》${scoreOf(baseList, SEED.songTuRan)}（artist_name 3）`
    : `score（ES _score，取整后展示）：《晴天》${scoreOf(baseList, SEED.songQingTian)}、`
      + `《倔强》${scoreOf(baseList, SEED.songJueJiang)}、`
      + `《突然好想你》${scoreOf(baseList, SEED.songTuRan)}——只要求《晴天》最高，不要求等于 5`);

  // ---- AC-15-006-3：无结果不是错误（D-41）
  const none = await search({ keyword: 'zzqqxx不存在', type: 'song' }, { section: 'none' });
  check('无结果时 code 仍是 0（不是 70003，AC-15-006-3 / D-41）', none.json?.code === 0, none.json);
  check('list 是空数组而不是 null', Array.isArray(none.json?.data?.list) && none.json.data.list.length === 0,
    none.json?.data?.list);
  check('给出 suggestWords 供用户改词', (none.json?.data?.suggestWords || []).length > 0,
    none.json?.data?.suggestWords);
  check('suggestWords ≤ 5 条', (none.json?.data?.suggestWords || []).length <= 5, none.json?.data?.suggestWords);
  check('total = 0 且 degraded 仍为 false（没有结果 ≠ 降级）',
    none.json?.data?.total === 0 && none.json?.data?.degraded === false, none.json?.data);

  const badType = await search({ keyword: '晴天', type: 'nosuchtype' }, { section: 'none' });
  check('未知 type 退回 all 而不是报错（读接口越宽松越安全）',
    badType.json?.code === 0 && Array.isArray(badType.json?.data?.songs?.list), badType.json?.data?.type);

  // ================================================================ §07 深分页
  section('§07 深分页与游标（BR-15-010 / D-40）');

  // from = (100-1)*100 = 9900 ≥ 1000
  const deep = await search({ keyword: '天', type: 'song', pageNum: 100, pageSize: 100 }, { section: 'deep' });
  check('from=9900 且无 cursor → 70005（BR-15-010）', deep.json?.code === 70005, deep.json);
  check('提示语说明要改用游标', /游标|cursor/.test(deep.json?.message || ''), deep.json?.message);
  const deepCursor = await search(
    { keyword: '天', type: 'song', pageNum: 100, pageSize: 100, cursor: 'x' }, { section: 'deep' });
  check('带 cursor 时不再报 70005（改报参数非法，说明深分页这道门已经过了）',
    deepCursor.json?.code !== 70005, deepCursor.json);
  const allDeep = await search({ keyword: '周杰伦', type: 'all', pageNum: 100, pageSize: 100 }, { section: 'deep' });
  check('type=all 不受深分页限制（四段各有小配额，永远到不了 1000）', allDeep.json?.code === 0, allDeep.json);

  // 「不重不漏」：用游标把结果翻完。
  // 条数不写死（演示数据会继续增长）：先用一次大页查询拿到 total 作为基准，
  // 再断言游标各页的并集恰好等于它——这样断言的是「翻页本身不重不漏」，
  // 而不是「当前数据集正好有几首」。
  const allHits = await search({ keyword: '天', type: 'song', pageSize: 50 }, { section: 'deep' });
  const expectedTotal = allHits.json?.data?.total;
  const expectedIds = ids(allHits.json?.data?.list).slice().sort();

  const p1 = await search({ keyword: '天', type: 'song', pageSize: 2 }, { section: 'deep' });
  const p1List = p1.json?.data?.list || [];
  check('第 1 页回 2 条', p1List.length === 2, names(p1List));
  check('第 1 页 total 与全量查询一致（首页才算 COUNT）',
    typeof expectedTotal === 'number' && p1.json?.data?.total === expectedTotal,
    { page1: p1.json?.data?.total, full: expectedTotal });
  check('结果多于一页（否则下面整段翻页断言是空转）', expectedTotal > 2, expectedTotal);
  check('第 1 页 hasMore = true', p1.json?.data?.hasMore === true, p1.json?.data);
  const cursor = p1.json?.data?.cursor;
  check('第 1 页给出 cursor', typeof cursor === 'string' && cursor.length > 0, cursor);
  if (cursor) {
    const collected = [...p1List];
    let last = p1;
    let nextCursor = cursor;
    let hasMore = true;
    let pages = 1;
    // 上限 20 页：既是翻页失控的自我保护，也远大于当前数据所需
    while (hasMore && nextCursor && pages < 20) {
      last = await search({ keyword: '天', type: 'song', pageSize: 2, cursor: nextCursor }, { section: 'deep' });
      pages += 1;
      const pageList = last.json?.data?.list || [];
      collected.push(...pageList);
      if (pages === 2) {
        check('第 2 页不返回 total（游标页不再 COUNT）', last.json?.data?.total === undefined,
          last.json?.data?.total);
        check('第 2 页确实换了内容（不与第 1 页重复）',
          ids(pageList).every((id) => !ids(p1List).includes(id)), { 第1页: names(p1List), 第2页: names(pageList) });
      }
      hasMore = last.json?.data?.hasMore === true;
      nextCursor = last.json?.data?.cursor;
    }
    check('最后一页 hasMore = false 且不再给 cursor（不给一个必然为空的下一页）',
      hasMore === false && !nextCursor, { pages, hasMore, nextCursor });
    const union = ids(collected);
    check(`游标翻完 ${pages} 页共 ${union.length} 条 = total（不重不漏）`,
      union.length === expectedTotal && new Set(union).size === union.length,
      { total: expectedTotal, collected: union.length, unique: new Set(union).size });
    check('并集正好是全量查询的 ID 集合',
      union.slice().sort().join() === expectedIds.join(), { missing: expectedIds.filter((id) => !union.includes(id)) });
    check('种子里那 3 首都在并集里（翻页没有漏掉低分项）',
      [SEED.songQingTian, SEED.songJueJiang, SEED.songTuRan].every((id) => union.includes(id)), union);
  }

  // ================================================================ §08 限流
  section('§08 两档限流（BR-15-008 / D-39）');

  const guestIp = syntheticIp();
  clearLimit('search', 'ip', guestIp, 'GET:/api/v1/search');
  let guestRejectedAt = null;
  let guestRetryAfter = null;
  for (let i = 1; i <= 33; i++) {
    const r = await call('GET', '/search?keyword=%E6%99%B4%E5%A4%A9&type=song', { xff: guestIp });
    if (r.json?.code === 10004) {
      guestRejectedAt = i;
      guestRetryAfter = r.headers.get('Retry-After');
      break;
    }
  }
  check('游客第 31 次被拒（30/min，BR-15-008）', guestRejectedAt === 31, `第 ${guestRejectedAt} 次被拒`);
  check('限流响应带 Retry-After 且 ≤ 窗口 60s',
    guestRetryAfter !== null && Number(guestRetryAfter) > 0 && Number(guestRetryAfter) <= 60,
    `Retry-After=${guestRetryAfter}`);

  // 同一个 IP：游客额度已耗尽，登录用户应当照样能搜
  const stillOk = await call('GET', '/search?keyword=%E6%99%B4%E5%A4%A9&type=song',
    { token: probe.token, xff: guestIp });
  check('同一 IP 上登录用户不受游客额度牵连（两档是独立的计数器）',
    stillOk.json?.code !== 10004, stillOk.json);

  const userApi = 'GET:/api/v1/search';
  clearLimit('search', 'user', String(probe.userId), userApi);
  let memberRejectedAt = null;
  for (let i = 1; i <= 63; i++) {
    const r = await call('GET', '/search?keyword=%E6%99%B4%E5%A4%A9&type=song', { token: probe.token });
    if (r.json?.code === 10004) {
      memberRejectedAt = i;
      break;
    }
  }
  check('登录用户第 61 次被拒（60/min，BR-15-008）', memberRejectedAt === 61, `第 ${memberRejectedAt} 次被拒`);
  note(`游客键 ml:limit:search:ip:{ip}:${userApi}（已耗尽）｜登录键 ml:limit:search:user:${probe.userId}:${userApi}`);

  clearLimit('search', 'user', String(probe.userId), userApi);
  const afterClear = await call('GET', '/search?keyword=%E6%99%B4%E5%A4%A9&type=song', { token: probe.token });
  check('清掉限流键后立刻恢复（脚本可重复执行）', afterClear.json?.code === 0, afterClear.json);
}

// ================================================================ §11 前端契约

/**
 * 前端（T614/T625）依赖的**响应形状**。
 *
 * 这一节不验业务规则（那些在 §01~§08 已经验过），验的是「前端照着写的那几个假设
 * 现在还成立吗」。它们全都是**不会报错、只会静默错**的东西：
 * 比如 `hasMore=true` 却不给 `cursor`，页面就会永远转圈加载不出第二页；
 * 比如游标页突然开始返回 `total: 0`，页码就会从「共 6 条」跳成「共 0 条」。
 * 后端重构时这些字段最容易被顺手改掉，所以用断言钉住。
 */
async function contractStage(probe) {
  section('§11 前端契约（T614 / T625 依赖的响应形状）');

  // ---- 1. 游标：`hasMore=true` 必须同时给 `cursor`，否则「加载更多」无从下手 ----
  const p1 = await search({ keyword: '的', type: 'song', pageSize: 5 }, { section: 'fe1' });
  const d1 = p1.json?.data ?? {};
  check('pageSize=5 时 hasMore=true', d1.hasMore === true, { total: d1.total, hasMore: d1.hasMore });
  check('hasMore=true 时 cursor 非空（前端唯一的翻页凭据）', typeof d1.cursor === 'string' && d1.cursor.length > 0,
    { cursor: d1.cursor });

  // ---- 2. 游标页不返回 total：前端只用第一页的 total，若变成 0 会显示「共 0 条」 ----
  const p2 = await search({ keyword: '的', type: 'song', pageSize: 5, pageNum: 2, cursor: d1.cursor }, { section: 'fe1' });
  const d2 = p2.json?.data ?? {};
  check('带 cursor 的第 2 页 code=0', p2.json?.code === 0, p2.json);
  check('游标页**不返回** total 字段（不是返回 0）', !('total' in d2), { total: d2.total, keys: Object.keys(d2) });
  check('第 2 页确实换了内容（不是重复第 1 页）',
    JSON.stringify(names(d2.list)) !== JSON.stringify(names(d1.list)),
    { 第1页: names(d1.list), 第2页: names(d2.list) });

  // ---- 3. 筛选的作用域：只动歌曲，不动歌手/专辑/歌单（前端据此决定筛选栏出现在哪些 Tab） ----
  const scope = {};
  for (const type of ['song', 'artist', 'album', 'playlist']) {
    const before = await search({ keyword: '的', type }, { section: 'fe2' });
    const after = await search({ keyword: '的', type, genre: '摇滚' }, { section: 'fe2' });
    scope[type] = { before: before.json?.data?.total, after: after.json?.data?.total };
  }
  check('type=song：加 genre 后 total 变小', scope.song.after < scope.song.before, scope.song);
  for (const type of ['artist', 'album', 'playlist']) {
    check(`type=${type}：加 genre 后 total 不变（筛选对它无效）`,
      scope[type].after === scope[type].before, scope[type]);
  }

  // ---- 4. 综合 Tab 的分段形状 + 筛选只影响歌曲段 ----
  const allPlain = await search({ keyword: '的', type: 'all' }, { section: 'fe3' });
  const allFiltered = await search({ keyword: '的', type: 'all', genre: '摇滚' }, { section: 'fe3' });
  const segs = ['songs', 'artists', 'albums', 'playlists'];
  check('type=all 返回四个分段', segs.every((s) => allPlain.json?.data?.[s] != null),
    Object.keys(allPlain.json?.data ?? {}));
  check('每个分段都是 PageResult 形状（有 list/total/hasNext）',
    segs.every((s) => {
      const seg = allPlain.json?.data?.[s];
      return seg && Array.isArray(seg.list) && typeof seg.total === 'number' && 'hasNext' in seg;
    }),
    segs.map((s) => `${s}: ${JSON.stringify(Object.keys(allPlain.json?.data?.[s] ?? {}))}`));
  check('综合 Tab 加 genre 只缩减 songs 段',
    allFiltered.json?.data?.songs?.total < allPlain.json?.data?.songs?.total
      && allFiltered.json?.data?.albums?.total === allPlain.json?.data?.albums?.total,
    { songs: [allPlain.json?.data?.songs?.total, allFiltered.json?.data?.songs?.total],
      albums: [allPlain.json?.data?.albums?.total, allFiltered.json?.data?.albums?.total] });
  check('分类 Tab 才有的 list 字段在综合 Tab 下不出现（NON_NULL 生效）',
    !('list' in (allPlain.json?.data ?? {})), Object.keys(allPlain.json?.data ?? {}));

  // ---- 5. 无结果不是错误：给 suggestWords 当台阶（前端据此渲染「换个词试试」） ----
  const none = await search({ keyword: 'zzzzzzzz', type: 'song' }, { section: 'fe4' });
  check('无结果 code=0（不是错误码）', none.json?.code === 0, none.json);
  check('无结果给非空 suggestWords', Array.isArray(none.json?.data?.suggestWords)
    && none.json.data.suggestWords.length > 0, none.json?.data?.suggestWords);

  // ---- 6. 空关键词：登录给 history，匿名不给（前端据此决定要不要渲染历史区） ----
  const anonHome = await call('GET', '/search?type=all&keyword=', { xff: ipOf('fe5') });
  check('匿名空关键词：不返回 history 字段', !('history' in (anonHome.json?.data ?? {})),
    Object.keys(anonHome.json?.data ?? {}));
  check('匿名空关键词：仍然给 hotWords（热搜榜不要求登录）',
    Array.isArray(anonHome.json?.data?.hotWords) && anonHome.json.data.hotWords.length > 0,
    anonHome.json?.data?.hotWords);

  // 造两条历史，再确认登录态能拿到
  await call('GET', `/search?keyword=${encodeURIComponent('晴天')}&type=song`, { token: probe.token });
  await call('GET', `/search?keyword=${encodeURIComponent('稻香')}&type=song`, { token: probe.token });
  const home = await call('GET', '/search?type=all&keyword=', { token: probe.token });
  const history = home.json?.data?.history;
  check('登录空关键词：返回 history 数组', Array.isArray(history) && history.length > 0, history);
  check('history 是字符串数组（前端直接渲染，不再解析对象）',
    Array.isArray(history) && history.every((h) => typeof h === 'string'), history);
  check('空关键词不返回 suggestWords（那是「无结果」才有的）',
    !('suggestWords' in (home.json?.data ?? {})), Object.keys(home.json?.data ?? {}));

  // ---- 7. 热搜词形状：不含搜索量（BR-15-034），前端没有数字可展示 ----
  const hot = await call('GET', '/search/hot?limit=10', { xff: ipOf('fe6') });
  const words = hot.json?.data?.words ?? [];
  check('热搜返回 words + refreshedAt', Array.isArray(words) && typeof hot.json?.data?.refreshedAt === 'string',
    { n: words.length, refreshedAt: hot.json?.data?.refreshedAt });
  check('热搜词不含搜索量字段（BR-15-034）',
    words.every((w) => !('count' in w) && !('score' in w)), words[0]);
  check('热搜词形状 = word/rank/mark/isTop',
    words.every((w) => typeof w.word === 'string' && typeof w.rank === 'number' && 'isTop' in w), words[0]);

  // ---- 8. 联想形状：前端直接读 text/type/extra 渲染下拉 ----
  const sug = await call('GET', `/search/suggest?keyword=${encodeURIComponent('周')}&limit=10`, { xff: ipOf('fe7') });
  const suggestions = sug.json?.data?.suggestions ?? [];
  check('联想返回 suggestions[]', Array.isArray(suggestions) && suggestions.length > 0, suggestions.length);
  check('联想项形状 = text/type/id/extra/highlight',
    suggestions.every((s) => typeof s.text === 'string' && typeof s.type === 'string' && 'extra' in s && 'highlight' in s),
    suggestions[0]);

  // ---- 9. degraded 同时出现在响应体与响应头（前端读体，网关读头，D-48） ----
  const deg = await search({ keyword: '晴天', type: 'song' }, { section: 'fe8' });
  check('响应体带 degraded 布尔字段（前端无需读响应头）', typeof deg.json?.data?.degraded === 'boolean',
    deg.json?.data?.degraded);
  check('响应头带 degraded（网关/监控用）', deg.headers?.get('degraded') != null,
    deg.headers?.get('degraded'));
  check('响应体 degraded 与响应头一致',
    String(deg.json?.data?.degraded) === deg.headers?.get('degraded'),
    { body: deg.json?.data?.degraded, header: deg.headers?.get('degraded') });

  // ---- 10. 超长关键词静默截断到 50（前端不做本地截断，靠后端兜底） ----
  const long = await search({ keyword: '啊'.repeat(60), type: 'song' }, { section: 'fe9' });
  check('60 字关键词被截断到 50 而不是报错', long.json?.code === 0
    && long.json?.data?.keyword?.length === 50, { code: long.json?.code, len: long.json?.data?.keyword?.length });
}

// ================================================================ Stage B

async function stageB(probe) {
  console.log('\n\x1b[1m════ Stage B：搜索历史 / 热搜榜 ════\x1b[0m');

  // ================================================================ §04 历史
  section('§04 搜索历史（AC-15-004-1/2/3/4）');

  const anon = await call('GET', '/search/history', { xff: ipOf('history') });
  check('未登录访问历史 → 40101（历史是「我的」数据，没有游客版本）',
    anon.json?.code === 40101, { status: anon.status, code: anon.json?.code });

  const histKey = `ml:search:his:${probe.userId}`;
  const userApi = 'GET:/api/v1/search';
  // 下一段要连打 25+ 次搜索，先清一遍登录用户的 60/min 额度
  clearLimit('search', 'user', String(probe.userId), userApi);
  redis('del', histKey); // 从干净状态开始，保证脚本可重复执行

  const wordA = `历史A${Date.now()}`;
  const wordB = `历史B${Date.now()}`;
  await call('GET', `/search?keyword=${encodeURIComponent(wordA)}&type=song`, { token: probe.token });
  await call('GET', `/search?keyword=${encodeURIComponent(wordB)}&type=song`, { token: probe.token });
  await sleep(900); // 历史是异步入队的
  const h1 = await call('GET', '/search/history', { token: probe.token });
  check('code = 0', h1.json?.code === 0, h1.json);
  check('最新的在最前（wordB 在 wordA 之前）',
    h1.json.data.indexOf(wordB) < h1.json.data.indexOf(wordA), h1.json?.data);

  await call('GET', `/search?keyword=${encodeURIComponent(wordA)}&type=song`, { token: probe.token });
  await sleep(900);
  const h2 = await call('GET', '/search/history', { token: probe.token });
  check('重搜 wordA → 置顶（AC-15-004-2）', h2.json.data[0] === wordA, h2.json?.data);
  check('重搜不产生重复条目', h2.json.data.filter((x) => x === wordA).length === 1, h2.json?.data);
  check('wordB 仍在（去重只删了同名那一条）', h2.json.data.includes(wordB), h2.json?.data);

  // AC-15-004-1：最多 20 条
  for (let i = 0; i < 25; i++) {
    await call('GET', `/search?keyword=${encodeURIComponent(`历史填充${i}`)}&type=song`, { token: probe.token });
  }
  await sleep(1500);
  const llen = Number(redis('llen', histKey));
  const h3 = await call('GET', '/search/history', { token: probe.token });
  check('连搜 25 个不同词后 Redis List 长度 = 20（AC-15-004-1 / BR-15-022）', llen === 20, `llen=${llen}`);
  check('GET 返回也裁到 20 条', h3.json.data.length === 20, h3.json?.data?.length);
  check('裁掉的是最旧的（wordA/wordB 已被挤出）',
    !h3.json.data.includes(wordA) && !h3.json.data.includes(wordB), h3.json?.data);
  const histTtl = rTtl(histKey);
  check(`历史键带 TTL（BR-15-022，实测 ${histTtl}s）`, histTtl > 0, `ttl=${histTtl}`);

  // AC-15-004-3：跨设备同步 = 同一 userId 的同一份 Redis List
  const dev1 = await call('GET', '/search/history', { token: probe.token });
  const dev2 = await call('GET', '/search/history', { token: probe.token });
  check('同一账号两次读取完全一致（AC-15-004-3）',
    JSON.stringify(dev1.json.data) === JSON.stringify(dev2.json.data), dev1.json?.data);

  const target = `历史删除${Date.now()}`;
  await call('GET', `/search?keyword=${encodeURIComponent(target)}&type=song`, { token: probe.token });
  await sleep(900);
  const delRes = await call('DELETE', `/search/history?keyword=${encodeURIComponent(target)}`,
    { token: probe.token });
  check('删除单条成功', delRes.json?.code === 0, delRes.json);
  const afterDel = await call('GET', '/search/history', { token: probe.token });
  check('删掉的词不在列表里', !afterDel.json.data.includes(target), afterDel.json?.data);
  const delMissing = await call('DELETE', '/search/history?keyword=%E4%BB%8E%E6%B2%A1%E6%90%9C%E8%BF%87',
    { token: probe.token });
  check('删不存在的词也返回成功（幂等）', delMissing.json?.code === 0, delMissing.json);
  const anonDel = await call('DELETE', '/search/history?keyword=x', { xff: ipOf('history') });
  check('未登录删除历史 → 40101', anonDel.json?.code === 40101, anonDel.json);

  const clear = await call('DELETE', '/search/history/all', { token: probe.token });
  check('清空成功', clear.json?.code === 0, clear.json);
  const afterClear = await call('GET', '/search/history', { token: probe.token });
  check('清空后历史为空（AC-15-004-4）',
    Array.isArray(afterClear.json?.data) && afterClear.json.data.length === 0, afterClear.json?.data);
  check('清空连 Redis 键一起删（不是只删了一个影子副本）', !rExists(histKey));

  // ================================================================ §05 热搜
  section('§05 热搜榜（AC-15-005-1/3、AC-15-103）');

  const hot = await call('GET', '/search/hot', { xff: ipOf('hot') });
  check('code = 0', hot.json?.code === 0, hot.json);
  const words = hot.json?.data?.words || [];
  check('返回 words 数组', Array.isArray(hot.json?.data?.words), hot.json?.data);
  check('words 非空（EX-15-005-3：榜为空时用配置兜底词）', words.length > 0, words);
  check('words ≤ 10 条（BR-15-029）', words.length <= 10, words.length);
  check('rank 从 1 起连续', words.every((w, i) => w.rank === i + 1), words.map((w) => w.rank));
  check('词条带 word', words.every((w) => typeof w.word === 'string' && w.word.length > 0), words);
  check('词条**没有** count 字段（BR-15-034：不暴露真实搜索量）', !('count' in (words[0] || {})), words[0]);
  check('refreshedAt 有值（BR-15-032 的快照时间）',
    typeof hot.json?.data?.refreshedAt === 'string', hot.json?.data?.refreshedAt);
  check('榜单里没有单字符/纯符号词（BR-15-029 的展示过滤）', words.every((w) => w.word.length >= 2), words);

  // ---- AC-15-005-3：同一主体 1 分钟内连搜同一词 10 次只计一次
  const uniqA = `热搜ZQ${Date.now()}`;
  const subjectIp = ipOf('hot-dedup');
  const before = rZScore(HOT_ZSET, uniqA) || 0;
  for (let i = 0; i < 10; i++) {
    await call('GET', `/search?keyword=${encodeURIComponent(uniqA)}&type=song`, { xff: subjectIp });
  }
  await sleep(1200); // 计数是异步的
  const afterTen = rZScore(HOT_ZSET, uniqA) || 0;
  check('同一主体连搜 10 次 → ZSCORE 只 +1（AC-15-005-3 / EX-15-005-1 去刷榜）',
    afterTen === before + 1, { before, afterTen });

  await call('GET', `/search?keyword=${encodeURIComponent(uniqA)}&type=song`, { xff: ipOf('hot-dedup2') });
  await sleep(900);
  const afterOther = rZScore(HOT_ZSET, uniqA) || 0;
  check('换一个主体搜同一个词 → 再计一次（去重是按主体，不是按词）',
    afterOther === afterTen + 1, { afterTen, afterOther });

  // 无意义词在入口就被挡掉（BR-15-029）
  const junk = '。';
  const junkBefore = rZScore(HOT_ZSET, junk);
  await call('GET', `/search?keyword=${encodeURIComponent(junk)}&type=song`, { xff: ipOf('hot-junk') });
  await sleep(900);
  const junkAfter = rZScore(HOT_ZSET, junk);
  check('纯符号关键词不进 ZSet（否则它是最便宜的刷榜填充物）', junkAfter === junkBefore,
    { before: junkBefore, after: junkAfter });

  // ---- AC-15-005-1：造 3 个不同计数的词，断言排名与次数序一致
  //
  // 先把实时 ZSet 清空再计数。理由不是图省事，而是**只有这样断言才有意义**：
  // 本轮已经为 §01/§02 打过几十次「晴天」「周杰伦」等真实词，一个只搜 1 次的合成词
  // 天然排在它们后面（第一轮实测就是如此：搜 1 次的词被挤到第 11 名，Top 10 里没有它，
  // 于是「排名与次数序一致」这条断言变成了在测「别的词有多少次搜索」）。
  // 清空之后 Top 3 恰好就是这三个词，排名与计数的对应关系才是被断言的那件事。
  //
  // 清的是本项目自己的键（ml: 前缀，热的实时榜是纯派生数据，可从检索行为重建），
  // 与共享 db0 里另一个项目的 ranking:* 无关——§09 仍会核对那 9 个键一个没少。
  redis('del', HOT_ZSET);
  const stamp = Date.now();
  const w5 = `热搜C${stamp}`; // 5 次
  const w3 = `热搜B${stamp}`; // 3 次
  const w1 = `热搜A${stamp}`; // 1 次
  const mk = async (word, times) => {
    for (let i = 0; i < times; i++) {
      await call('GET', `/search?keyword=${encodeURIComponent(word)}&type=song`, { xff: syntheticIp() });
    }
  };
  await mk(w5, 5);
  await mk(w3, 3);
  await mk(w1, 1);
  await sleep(1500);

  // 硬证据先落在 ZSet 上：这三次计数是排名的**输入**，先确认输入对了，
  // 再看输出（快照顺序）对不对。两者分开断言，出错时能立刻分辨是「没计数」还是「排序错」
  const z5 = rZScore(HOT_ZSET, w5);
  const z3 = rZScore(HOT_ZSET, w3);
  const z1 = rZScore(HOT_ZSET, w1);
  check('ZSet 里的计数就是搜索次数（5 / 3 / 1）', z5 === 5 && z3 === 3 && z1 === 1, { z5, z3, z1 });

  // 强制重建快照：CacheService 的 L1（Caffeine）也持有同一份快照，只删 Redis 键不够
  redis('del', HOT_SNAPSHOT);
  note('已删快照键，等待 31s 让 L1 也过期（CacheService local-ttl = 30s）');
  await sleep(31000);
  const hot2 = await call('GET', '/search/hot', { xff: ipOf('hot') });
  const words2 = hot2.json?.data?.words || [];
  const rankOf = (w) => words2.findIndex((x) => x.word === w);
  check('重建后的榜单含刚造的 3 个词',
    rankOf(w5) >= 0 && rankOf(w3) >= 0 && rankOf(w1) >= 0,
    { w5, w3, w1, words: words2.map((x) => x.word) });
  check('搜 5 次的词排第 1（AC-15-005-1：排名与次数序一致）', rankOf(w5) === 0,
    { rank: rankOf(w5), words: words2.map((x) => x.word) });
  check('三个词的排名恰好与次数序一致（5 > 3 > 1 → 第 1/2/3 名）',
    rankOf(w5) === 0 && rankOf(w3) === 1 && rankOf(w1) === 2,
    { rankW5: rankOf(w5), rankW3: rankOf(w3), rankW1: rankOf(w1) });
  check('词条仍只有 word/rank/mark/isTop，没有计数',
    words2.every((x) => !('count' in x)), words2[0]);

  const snapTtl = rTtl(HOT_SNAPSHOT);
  // 上限按 §4.6 的 ±10% 收口：基础 300s，抖动后最多 330s（此前写成 400s 太松，
  // 那是「能过就行」而不是「按契约过」）。**不设下限**是刻意的——
  // 断言跑在快照写完之后，中间流逝的秒数无从得知，下限只会引入假红。
  check(`快照 TTL 在 (0, 330] 内（BR-15-032 的 300s + §4.6 的 ≤10% 抖动，实测 ${snapTtl}s）`,
    snapTtl > 0 && snapTtl <= 330, `ttl=${snapTtl}`);
  const zsetTtl = rTtl(HOT_ZSET);
  check(`实时 ZSet 带 TTL（不留常驻键，实测 ${zsetTtl}s）`, zsetTtl > 0, `ttl=${zsetTtl}`);

  // 清理：只删脚本自己造的词（ZREM，不是 DEL 整个 ZSet）
  redis('zrem', HOT_ZSET, uniqA, w5, w3, w1);

  // AC-15-005-2 / AC-15-005-4 需要写 t_sys_config 与 t_search_hot，同 §12 一并进行。
}

// ================================================================ §12 写配置 / 写库型用例

/**
 * 三条 AC 的共同点：**必须先改应用外部的状态（配置表 / 数据库）才能验**。
 *
 * <p>它们此前被记成「待办」，理由是「机制已由 SensitiveWordSupportTest 12 项覆盖」。
 * 那个理由只对了一半：单测覆盖的是 `isBlocked` 这个**函数**的行为，
 * 而这三条 AC 要的是「运营改一行配置/数据之后，**运行中的那个实例**的表现真的变了」——
 * 中间隔着 CacheService、隔着一个 5 秒的刷新窗口、隔着 `selectTops` 的当日过滤。
 * 这些是单测碰不到的地方，所以不能拿单测当交付证据。
 *
 * <p>反过来，这三条也只能在**进程外**验，所以它们既不属于 Stage A 的只读流程，
 * 也需要真的写库。做法：
 * <ul>
 *   <li>只写本项目自己的两张表（`newmusic.t_sys_config` / `newmusic.t_search_hot`），
 *       每条都在结束时**删除自己写的行并断言删除成功**——共享 MySQL 实例上还有别的项目的库，
 *       所有语句都带 `newmusic.` 限定符；</li>
 *   <li>AC-15-005-2 与 AC-15-005-4 都要看榜单，于是**共用一次快照重建**，
 *       否则光等 L1 过期就要等两遍 31s；</li>
 *   <li>没设 `MYSQL_PASSWORD` 时整个阶段跳过并说明怎么补跑——**不写库就报绿是假绿**。</li>
 * </ul>
 */
async function writeStage() {
  section('§12 写配置 / 写库型用例（AC-15-003-3、AC-15-005-2、AC-15-005-4）');

  if (!MYSQL_PWD) {
    for (const ac of ['AC-15-003-3', 'AC-15-005-2', 'AC-15-005-4']) {
      skip.push(`${ac}：本阶段要往 t_sys_config / t_search_hot 写行，但环境变量 MYSQL_PASSWORD 未设置，`
        + '脚本没有做任何写库操作。补跑：MYSQL_PASSWORD=<本机密码> node scripts/verify-06.mjs');
    }
    return;
  }

  // 词全部带本轮时间戳：跑第二遍时不会撞上第一遍的残留（尤其是 ZSet 成员与唯一键）
  const stamp = Date.now();
  const wordSensitive = `敏感甲${stamp}`; // 塞进 ZSet，验**展示**过滤
  const wordControl = `对照丙${stamp}`;   // 塞进 ZSet，证明榜单非空（否则「不含敏感词」恒真）
  const wordTop = `置顶乙${stamp}`;       // 写进 t_search_hot(is_top=1)，验运营置顶
  const seedWord = '周杰伦';              // 种子里的真实词，验**入口**拦截
  const CONFIG_KEY = 'search.sensitive.words';

  // ---- 写之前先记下原状，结束时要恢复到这个状态（而不是「删了了事」）
  const cfgBefore = mysql(`SELECT COUNT(*) FROM newmusic.t_sys_config WHERE config_key = '${CONFIG_KEY}';`);
  const hadRow = cfgBefore !== '0';
  const cfgValueBefore = hadRow
    ? mysql(`SELECT config_value FROM newmusic.t_sys_config WHERE config_key = '${CONFIG_KEY}' LIMIT 1;`)
    : null;
  note(`写库前 ${CONFIG_KEY} 有 ${cfgBefore} 行${hadRow ? '（结束时按原值恢复）' : '（结束时删除本脚本写的行）'}`);

  try {
    // ---- AC-15-003-3 入口侧：写一行配置，等运行中的实例开始拦截
    mysql(`INSERT INTO newmusic.t_sys_config (id, config_key, config_value, value_type, config_group, `
      + `description, is_public, status) VALUES (${stamp * 1000 + 1}, '${CONFIG_KEY}', `
      + `${utf8Hex(`${seedWord},${wordSensitive}`)}, 1, 'search', `
      + `${utf8Hex('验收脚本临时写入，跑完即删')}, 0, 1) `
      + `ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), status = 1;`);

    // SensitiveWordSupport 的刷新窗口是 5s（类注释里写明了为什么是 5s）：
    // 不立刻断言，而是轮询到生效为止——断言的是「最终真的生效」，
    // 不是「在某个我没测过的时机生效」。轮询上限 15×0.7s = 10.5s，留够 2 倍余量。
    // 用独立 IP 轮询：游客 /search 是 30 次/分/IP，不能用同一个桶。
    let codeAtPoll = null;
    let polls = 0;
    for (; polls < 15; polls += 1) {
      codeAtPoll = (await search({ keyword: seedWord, type: 'song' }, { section: 'write-poll' })).json?.code;
      if (codeAtPoll === 70004) break;
      await sleep(700);
    }
    check(`写一行配置后运行中的实例开始拦截真实关键词（AC-15-003-3 入口侧，等了约 ${(polls * 0.7).toFixed(1)}s）`,
      codeAtPoll === 70004, { code: codeAtPoll });

    const sugBlocked = await call('GET', `/search/suggest?keyword=${encodeURIComponent(seedWord)}`,
      { xff: ipOf('write') });
    check('敏感词不出现在联想里（AC-15-003-3）：suggestions 与 hotWords 都为空',
      sugBlocked.json?.code === 0
      && (sugBlocked.json.data.suggestions || []).length === 0
      && (sugBlocked.json.data.hotWords || []).length === 0,
      sugBlocked.json?.data);

    // ---- BR-15-027：敏感词在**计数之前**就被拒，所以连搜几次也不该进实时榜。
    // 这条断言的价值在于它区分了「拦在入口」与「拦在展示」：只拦展示的话，
    // 敏感词照样被计数、照样占掉一个榜单名额，只是不显示出来。
    for (let i = 0; i < 3; i += 1) {
      await search({ keyword: wordSensitive, type: 'song' }, { section: 'write' });
    }
    await sleep(1000); // 计数是异步的（searchExecutor），给它跑完的时间再下结论
    check('敏感词连搜 3 次后仍未进实时榜单（BR-15-027：拦在计数之前）',
      rZScore(HOT_ZSET, wordSensitive) === null, { score: rZScore(HOT_ZSET, wordSensitive) });

    // ---- AC-15-005-2 + AC-15-005-4：一次快照重建同时验两条
    // 展示过滤不能靠「搜它」来构造输入（上面刚证明它进不了 ZSet），所以直接 ZADD
    // 模拟「它已经攒了很多次搜索」——这条路径同样是 isRankable() 的必经之路。
    redis('zadd', HOT_ZSET, '99', wordSensitive);
    redis('zadd', HOT_ZSET, '50', wordControl);

    mysql(`INSERT INTO newmusic.t_search_hot (id, keyword, search_count, rank_no, is_top, stat_date) `
      + `VALUES (${stamp * 1000 + 2}, ${utf8Hex(wordTop)}, 0, 1, 1, CURDATE()) `
      + `ON DUPLICATE KEY UPDATE rank_no = 1, is_top = 1;`);
    const topRows = mysql(`SELECT COUNT(*) FROM newmusic.t_search_hot `
      + `WHERE keyword = ${utf8Hex(wordTop)} AND is_top = 1 AND stat_date = CURDATE();`);
    check('置顶行已写入当日的 t_search_hot（is_top=1）', topRows === '1', { rows: topRows });

    // 只删 Redis 快照不够：CacheService 的 L1（Caffeine，local-ttl 30s）持有同一份。
    // 这里与 §05 是同一处妥协——等它自然过期，而不是去加一个只为测试存在的失效接口。
    redis('del', HOT_SNAPSHOT);
    note('已删快照键，等待 31s 让 CacheService 的 L1 也过期（local-ttl = 30s）');
    await sleep(31000);

    const hotW = await call('GET', '/search/hot', { xff: ipOf('write-hot') });
    const ws = hotW.json?.data?.words || [];
    const wordList = ws.map((x) => x.word);
    check('榜单里至少有置顶词与对照词两条（否则「不含敏感词」恒真，是假绿）',
      wordList.includes(wordTop) && wordList.includes(wordControl), wordList);
    check('敏感词被挡在展示之外（AC-15-005-2）', !wordList.includes(wordSensitive), wordList);
    check('运营置顶词排在第 1 位且 isTop=true（AC-15-005-4）',
      ws[0]?.word === wordTop && ws[0]?.rank === 1 && ws[0]?.isTop === true, ws[0]);
    check('置顶词在榜上只出现一次（BR-15-030 的榜上去重，否则看起来像 bug）',
      wordList.filter((w) => w === wordTop).length === 1, wordList);
    check('置顶词不带增速标记（mark 为空——它不是「热度涨上来的」）',
      ws[0]?.mark == null, ws[0]);
  } finally {
    // ---- 清理：无论上面断言成败，都必须把写进去的东西撤回。
    // 留一条 is_top=1 的行会让热搜榜永远挂着这个探测词；
    // 留一行敏感词配置会让真实关键词一直被拦——两种都是「脚本自己制造故障」。
    mysql(`DELETE FROM newmusic.t_search_hot WHERE keyword IN `
      + `(${utf8Hex(wordTop)}, ${utf8Hex(wordSensitive)}, ${utf8Hex(wordControl)});`);
    check('临时热搜行已删除（含可能的落库残留）',
      mysql(`SELECT COUNT(*) FROM newmusic.t_search_hot WHERE keyword IN `
        + `(${utf8Hex(wordTop)}, ${utf8Hex(wordSensitive)}, ${utf8Hex(wordControl)});`) === '0');

    if (hadRow) {
      mysql(`UPDATE newmusic.t_sys_config SET config_value = `
        + `${cfgValueBefore === '' ? 'NULL' : utf8Hex(cfgValueBefore)} WHERE config_key = '${CONFIG_KEY}';`);
    } else {
      mysql(`DELETE FROM newmusic.t_sys_config WHERE config_key = '${CONFIG_KEY}';`);
    }
    check(`临时敏感词配置行已${hadRow ? '还原' : '删除'}（否则真实关键词会一直被拦）`,
      mysql(`SELECT COUNT(*) FROM newmusic.t_sys_config WHERE config_key = '${CONFIG_KEY}';`) === (hadRow ? '1' : '0'));

    redis('zrem', HOT_ZSET, wordSensitive, wordControl);
    redis('del', HOT_SNAPSHOT); // 让榜单回到「没有置顶」的真实状态
  }

  // ---- 反方向也要验：删掉配置行之后能恢复。
  // 这条比正面更有信息量——它证明词典是**周期重读**的，不是「进程启动时读一次」。
  let codeAfter = null;
  let waits = 0;
  for (; waits < 15; waits += 1) {
    codeAfter = (await search({ keyword: seedWord, type: 'song' }, { section: 'write-recover' })).json?.code;
    if (codeAfter === 0) break;
    await sleep(700);
  }
  check(`删掉配置行后恢复正常检索（等了约 ${(waits * 0.7).toFixed(1)}s）`, codeAfter === 0, { code: codeAfter });
}

// ================================================================ 主流程

async function main() {
  console.log('\x1b[1m模块 06 验收：搜索（docs/系分/06-搜索.md §8）\x1b[0m');
  console.log(`目标 ${BASE}    Redis ${REDIS_CLI}`);
  if (!(await waitForBoot())) {
    console.error('应用未就绪，退出');
    process.exit(2);
  }
  const probe = await loginAs(PROBE);

  if (runA) await stageA(probe);
  // 前端契约跟在 Stage A 后面跑：它只验「响应形状」，不依赖 Redis 的中间状态
  if (runA) await contractStage(probe);
  if (runB) await stageB(probe);
  // 写配置/写库型用例放最后：它会改配置表和热搜表，必须在所有只读断言之后跑，
  // 也必须自己把改动撤回（见 writeStage 的 finally）
  if (runB) await writeStage();

  // ---------------------------------------------------------------- 键空间体检
  section('§09 键空间：前缀完整性、TTL 与「没碰别的项目」');
  const keys = mlSearchKeys();
  check('产生了 ml:search:* 键', keys.length > 0, keys.slice(0, 8));
  const strayKeys = listKeys('ml:*').filter((k) => !k.startsWith('ml:'));
  check('所有应用键都在 ml: 前缀内', strayKeys.length === 0, strayKeys);
  const noTtl = keys.filter((k) => rTtl(k) === -1);
  check('ml:search:* 下没有意外常驻的键（都带 TTL）', noTtl.length === 0, noTtl);
  note(`ml:search:* 共 ${keys.length} 个键：${keys.slice(0, 5).join(', ')}${keys.length > 5 ? ' …' : ''}`);
  const rankingAfter = rankingKeys();
  check(`共享 db0 的 ranking:* 键数未变（${RANKING_BEFORE.length} → ${rankingAfter.length}）`,
    rankingAfter.length === RANKING_BEFORE.length,
    { before: RANKING_BEFORE.length, after: rankingAfter.length });

  // ---------------------------------------------------------------- 跳过与待办
  section('§10 本轮未覆盖的 AC（逐条说明原因，不做无意义的绿）');
  if (runA) {
    skip.push('AC-15-001-6「停掉 ES 后搜索仍可用」：本机没有 ES 可停（D-33）。'
      + '路由与降级已由 SearchEngineRouterTest 覆盖（10 项）；进程外只能验 degraded 字段与响应头存在');
    skip.push('AC-15-109「LLM 不可用时降级到关键词搜索」：本轮无 LLM（§1.2），可执行的部分同 AC-15-001-6');
    skip.push('AC-15-001-6 的另一半「全不可用 → 70001 + 热门歌曲」：'
      + '本机 6379 是另一项目的共享实例，未经确认不主动停服；规则见 SearchServiceImpl.unavailable 的 javadoc');
    skip.push('敏感词的「真实词库」（模块 18 的内容安全）：D-38 决定本模块只做「结构就位、词典可配、默认空」。'
      + '「填词即生效」已由 §12 在进程外实测（AC-15-003-3/005-2），'
      + '两级来源优先级与刷新窗口由 SensitiveWordSupportTest 12 项覆盖；'
      + '没有被验的是「词库内容本身够不够」——那不是本模块能交付的');
    skip.push('BR-15-010 的「单次 ≤1000 条」只有越界路径可验：种子仅 6 首，'
      + '验的是「越界必报 70005」与「游标翻页不重不漏」，不是真的翻 50 页');
  }
  if (runA && !runB) {
    skip.push('AC-15-003-3/005-2/005-4 的进程外实测在 Stage B 末尾的 §12：'
      + '本轮只跑了 Stage A，这三条未被执行（不是通过）');
  }
  for (const s of skip) note(`跳过｜${s}`);
  for (const p of pending) note(`待办｜${p}`);

  // ---------------------------------------------------------------- 汇总
  console.log('\n' + '─'.repeat(66));
  const stage = runA && runB ? 'Stage A + B' : runA ? 'Stage A' : 'Stage B';
  console.log(`${stage}：\x1b[32m通过 ${pass}\x1b[0m  \x1b[31m失败 ${fail}\x1b[0m  `
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
