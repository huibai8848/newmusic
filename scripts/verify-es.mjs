#!/usr/bin/env node
/**
 * 模块 11 / 模块 06 —— 真实 Elasticsearch 接入验收。
 *
 * 运行（口令只从环境变量读，不写进任何文件）：
 *   ES_PASSWORD=*** node scripts/verify-es.mjs
 *
 * 可选环境变量：
 *   API_BASE           默认 http://localhost:8081/api/v1
 *   ES_URL             默认 http://127.0.0.1:9200
 *   ES_USERNAME        默认 elastic
 *   ES_INDEX_ALIAS     默认 music_song
 *   M11_ADMIN_USER / M11_ADMIN_PASSWORD   默认 admin / admin123（C 端 /auth/login）
 *
 * 前置：
 *   * 后端以 `SEARCH_ENGINE=es`、`DICT_ES_ENABLED=true` 启动，且已自动建好别名；
 *   * ES 已启动、开了 xpack security（Basic Auth），HTTP 未加密时 ES_URL 用 http://
 *     ——若开了 `xpack.security.http.ssl.enabled`，本脚本的 http 请求会直接被拒，
 *     这正是需要暴露的配置错误，而不是「脚本挂不住」。
 *
 * 覆盖：
 *   §00 前置：ES 可达、版本、后端 engine=es / degraded=false / 响应头；
 *   §01 索引：别名唯一指向 `music_song_v*`、schemaVersion、单字分析、四类文档计数；
 *   §02 基础检索：中文命中/高亮、综合 Tab 四段、分类 Tab、歌单；
 *   §03 拼音与单字：全拼/首字母/D-46 守卫、歌手首字母、单字中文；
 *   §04 筛选与排序：filter 上下文不打分、语种/流派/时长、三种排序；
 *   §05 歌词：命中标记、片段窗口、LRC 剥离、type=lyric 语义；
 *   §06 游标：search_after 翻页不重不漏、末页无 cursor；
 *   §07 写入穿透：后台建/改/删歌曲后 ES 立即可见/消失（refresh=true）；
 *   §08 共享实例：非 `music_song*` 索引集合前后一致、无残留测试文档。
 *
 * 安全约定：
 *   * **不碰 MySQL / Redis**：本脚本不执行任何 SQL、不建任何 Redis 键，
 *     共享实例上的 emp_db / music_db / ranking:* 天然不受影响（§08 只做前后计数证明）；
 *   * 写入的临时歌曲用唯一 marker 命名，并在 finally 里按创建响应拿到的 id 回收
 *     （不依赖任何后续搜索——搜不到时也要能删掉）；
 *   * 每节使用随机合成 X-Forwarded-For，隔离游客 30/分钟限流；
 *   * 只在别名上写文档，**不删任何索引**（清理旧索引是应用侧重建的职责）。
 */

const API = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ES_URL = (process.env.ES_URL || 'http://127.0.0.1:9200').replace(/\/+$/, '');
const ES_USER = process.env.ES_USERNAME || 'elastic';
const ES_PASS = process.env.ES_PASSWORD || '';
const ALIAS = process.env.ES_INDEX_ALIAS || 'music_song';
const ADMIN = {
  account: process.env.M11_ADMIN_USER || 'admin',
  password: process.env.M11_ADMIN_PASSWORD || 'admin123',
};

/** 种子数据里的稳定标识（sql/data.sql） */
const SEED = {
  songQingTian: '1000000000000005001',
  songDaoXiang: '1000000000000005002',
  songJueJiang: '1000000000000005003',
  songTuRan: '1000000000000005004',
  artistJay: '1000000000000003001',
  albumYeHuiMei: '1000000000000004001',
  playlistHuaYu: '1000000000000010001',
};
const SEED_SONG_IDS = [
  '1000000000000005001', '1000000000000005002', '1000000000000005003',
  '1000000000000005004', '1000000000000005005', '1000000000000005006',
];
const isSeedSong = (x) => Boolean(x) && SEED_SONG_IDS.includes(String(x.id));

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
  skipNotes.push(`${name}（${why}）`);
  console.log(`  \x1b[90m○\x1b[0m ${name} → 跳过：${why}`);
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

// ---------------------------------------------------------------- HTTP

/** 后端的请求。字节按 UTF-8 解，中文乱码才可能被 §08 的 mojibake 断言抓到 */
async function api(method, path, { token, body, headers, xff } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json; charset=utf-8';
  if (token) h.Authorization = `Bearer ${token}`;
  if (xff) h['X-Forwarded-For'] = xff;
  const started = process.hrtime.bigint();
  const res = await fetch(`${API}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
  const text = buf.toString('utf8');
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（错误页） */
  }
  return { status: res.status, json, text, buf, elapsedMs, headers: res.headers };
}

/** 直接打 ES。Basic Auth 口令只来自环境变量，不出现在命令行与文件里 */
async function es(method, path, body) {
  const headers = {
    Accept: 'application/json',
    Authorization: 'Basic ' + Buffer.from(`${ES_USER}:${ES_PASS}`).toString('base64'),
  };
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
  const res = await fetch(`${ES_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const buf = Buffer.from(await res.arrayBuffer());
  const text = buf.toString('utf8');
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* _cat 的 text 模式不是 JSON */
  }
  return { status: res.status, json, text, buf };
}

const search = (params, section) =>
  api('GET', `/search?${new URLSearchParams(params)}`, { xff: ipOf(section) });

const names = (list) => (list || []).map((x) => x.name);
const ids = (list) => (list || []).map((x) => String(x.id));
const byName = (list, name) => (list || []).find((x) => x.name === name);
const sortedNames = (list) => names(list).slice().sort().join();

async function adminLogin() {
  const res = await api('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account: ADMIN.account, password: ADMIN.password },
  });
  if (res.json?.code !== 0) {
    throw new Error(`后台账号登录失败：${res.status}/${JSON.stringify(res.json)}`);
  }
  return res.json.data.accessToken;
}

async function main() {
  if (!ES_PASS) {
    console.error('缺少 ES_PASSWORD 环境变量：口令只从环境变量读取（不要在脚本里写死）。');
    console.error('  ES_PASSWORD=*** node scripts/verify-es.mjs');
    process.exit(2);
  }

  // ================================================================ §00 前置
  section('§00 前置：ES 可达 / 后端确实在用 ES');

  const root = await es('GET', '/');
  check('ES 根路径 200（Basic Auth 正确）', root.status === 200, `${root.status} ${root.text.slice(0, 200)}`);
  if (root.status !== 200) {
    console.error('ES 不可用：先启动 ES，并核对 ES_URL / ES_USERNAME / ES_PASSWORD。');
    return;
  }
  const version = root.json?.version?.number || '';
  check('ES 版本可读（9.x）', version.startsWith('9.') || version.startsWith('8.'), version);
  note(`集群 ${root.json?.cluster_name} / 版本 ${version} / 节点 ${root.json?.name}`);

  const probe = await search({ keyword: '晴天', type: 'song' }, 'pre');
  const engine = probe.json?.data?.engine;
  check('后端响应 engine = es（SEARCH_ENGINE=es 生效）', engine === 'es', engine);
  if (engine !== 'es') {
    console.error('后端当前没有走 ES 引擎：请以 SEARCH_ENGINE=es、DICT_ES_ENABLED=true 重启后再跑本脚本。');
    return;
  }
  check('degraded = false（ES 可用，没有降级到 MySQL）', probe.json?.data?.degraded === false,
    probe.json?.data?.degraded);
  check('响应头 X-Search-Engine = es', probe.headers.get('x-search-engine') === 'es',
    probe.headers.get('x-search-engine'));

  // ================================================================ §01 索引结构
  section('§01 索引结构：别名、schemaVersion、分析器、文档计数');

  const aliasRes = await es('GET', `/_cat/aliases/${ALIAS}?format=json`);
  const aliasRows = Array.isArray(aliasRes.json) ? aliasRes.json : [];
  check('别名存在且只指向 1 个物理索引', aliasRows.length === 1, aliasRows);
  const INDEX = aliasRows[0]?.index || '';
  check('物理索引名符合 music_song_v{yyyyMMddHHmmss}', /^music_song_v\d{14}$/.test(INDEX), INDEX);

  const allIndices = await es('GET', '/_cat/indices?format=json&h=index');
  const indexNames = (Array.isArray(allIndices.json) ? allIndices.json : []).map((r) => r.index);
  const songIndices = indexNames.filter((n) => n.startsWith(`${ALIAS}_v`));
  check('没有残留的旧版本索引（重建后旧索引已删除）', songIndices.length === 1, songIndices);
  const othersBefore = indexNames.filter((n) => !n.startsWith(ALIAS)).sort();

  const mappingRes = await es('GET', `/${INDEX}/_mapping`);
  const mapping = mappingRes.json?.[INDEX]?.mappings || {};
  check('mapping._meta.schemaVersion = 4', mapping._meta?.schemaVersion === 4,
    mapping._meta);

  const settingsRes = await es('GET', `/${INDEX}/_settings`);
  const analysis = settingsRes.json?.[INDEX]?.settings?.index?.analysis || {};
  const indexAnalyzer = analysis.analyzer?.index_analyzer_music || {};
  const tokenizer = indexAnalyzer.tokenizer;
  const liteMode = tokenizer === 'standard';
  if (liteMode) {
    const filters = indexAnalyzer.filter || [];
    check('lite 映射使用 cjk_bigram_uni 过滤器', filters.includes('cjk_bigram_uni'), filters);
    // ES 把 settings 里的布尔值回读成字符串 "true"，两种形态都要认
    const unigrams = analysis.filter?.cjk_bigram_uni?.output_unigrams;
    check('cjk_bigram 的 output_unigrams = true（否则单字搜不到）',
      unigrams === true || unigrams === 'true', analysis.filter?.cjk_bigram_uni);
    const analyzed = await es('POST', `/${INDEX}/_analyze`, {
      analyzer: 'index_analyzer_music',
      text: '晴天',
    });
    const tokens = (analyzed.json?.tokens || []).map((t) => t.token);
    check('「晴天」被切成含单字的 token（晴 / 天）',
      tokens.includes('晴') && tokens.includes('天'), tokens);
  } else {
    skip('lite 映射的单字分析断言', `当前索引用 ${tokenizer}（IK 插件已装），单字由 IK 负责`);
  }

  const countOf = async (target) => {
    const res = await es('POST', `/${ALIAS}/_count`, { query: { term: { docType: target } } });
    return res.json?.count ?? -1;
  };
  const [songCount, artistCount, albumCount, playlistCount] = await Promise.all([
    countOf('SONG'), countOf('ARTIST'), countOf('ALBUM'), countOf('PLAYLIST'),
  ]);
  check('SONG 文档 ≥ 6（种子歌曲都在）', songCount >= 6, songCount);
  check('ARTIST 文档 ≥ 1', artistCount >= 1, artistCount);
  check('ALBUM 文档 ≥ 1', albumCount >= 1, albumCount);
  check('PLAYLIST 文档 ≥ 1（公开歌单）', playlistCount >= 1, playlistCount);
  note(`文档计数：SONG=${songCount} ARTIST=${artistCount} ALBUM=${albumCount} PLAYLIST=${playlistCount}`);

  // ================================================================ §02 基础检索
  section('§02 基础检索：中文命中、高亮、综合 Tab、分类 Tab');

  const qt = await search({ keyword: '晴天', type: 'song' }, 'basic');
  const qtList = qt.json?.data?.list || [];
  check('「晴天」total = 1（AND 语义：只含「天」的歌不该被召回）', qt.json?.data?.total === 1,
    { total: qt.json?.data?.total, names: names(qtList) });
  const qtHit = byName(qtList, '晴天');
  check('命中《晴天》且 id 是种子 id', String(qtHit?.id) === SEED.songQingTian, qtHit?.id);
  check('matchedFields 含 name', (qtHit?.matchedFields || []).includes('name'), qtHit?.matchedFields);
  check('highlight.name = <em class="hl">晴天</em>',
    qtHit?.highlight?.name === '<em class="hl">晴天</em>', qtHit?.highlight);
  check('带 artistName / albumName', qtHit?.artistName === '周杰伦' && qtHit?.albumName === '叶惠美',
    { artist: qtHit?.artistName, album: qtHit?.albumName });
  check('不足一页时不给 cursor（前端不会多翻一页空数据）', !qt.json?.data?.cursor, qt.json?.data?.cursor);
  check('costMs 是非负整数', Number.isInteger(qt.json?.data?.costMs), qt.json?.data?.costMs);

  const all = await search({ keyword: '周杰伦', type: 'all' }, 'basic');
  const d = all.json?.data || {};
  check('综合 Tab 四段齐全', ['songs', 'artists', 'albums', 'playlists']
    .every((k) => d[k] && Array.isArray(d[k].list)), Object.keys(d));
  check('songs 段含《晴天》《稻香》',
    names(d.songs?.list).includes('晴天') && names(d.songs?.list).includes('稻香'), names(d.songs?.list));
  check('artists 段含周杰伦', names(d.artists?.list).includes('周杰伦'), names(d.artists?.list));
  check('albums 段含《叶惠美》《魔杰座》（靠冗余 artistName 命中）',
    names(d.albums?.list).includes('叶惠美') && names(d.albums?.list).includes('魔杰座'),
    names(d.albums?.list));
  check('artists 段配额 ≤ 3（BR-15-004）', (d.artists?.list || []).length <= 3, d.artists?.list?.length);
  check('artists 段带 songCount', d.artists?.list?.[0]?.songCount !== undefined, d.artists?.list?.[0]);

  // 分类 Tab 的筛选只对歌曲文档生效：歌手/专辑/歌单没有 copyrightStatus，
  // 无条件加 term 会让这三个 Tab 永远为空（这是接入 ES 后踩过的坑）
  const artistTab = await search({ keyword: '周杰伦', type: 'artist' }, 'tab');
  check('type=artist 命中周杰伦（筛选条件没被错误套到歌手文档）',
    names(artistTab.json?.data?.list).includes('周杰伦'), names(artistTab.json?.data?.list));
  const albumTab = await search({ keyword: '叶惠美', type: 'album' }, 'tab');
  check('type=album 命中《叶惠美》',
    names(albumTab.json?.data?.list).includes('叶惠美'), names(albumTab.json?.data?.list));
  const playlistTab = await search({ keyword: '华语经典', type: 'playlist' }, 'tab');
  check('type=playlist 命中《华语经典必听》',
    names(playlistTab.json?.data?.list).includes('华语经典必听'), names(playlistTab.json?.data?.list));

  // ================================================================ §03 拼音与单字
  section('§03 拼音、首字母与单字中文');

  const full = await search({ keyword: 'qingtian', type: 'song' }, 'pinyin');
  const fullHit = full.json?.data?.list?.find((x) => x.name === '晴天');
  check('「qingtian」命中《晴天》', Boolean(fullHit), names(full.json?.data?.list));
  check('命中字段是 name.pinyin', (fullHit?.matchedFields || []).includes('name.pinyin'),
    fullHit?.matchedFields);
  check('拼音命中不给 highlight（字符对不上）', !fullHit?.highlight?.name, fullHit?.highlight);

  const first = await search({ keyword: 'qt', type: 'song' }, 'pinyin');
  const firstHit = first.json?.data?.list?.find((x) => x.name === '晴天');
  check('「qt」命中《晴天》', Boolean(firstHit), names(first.json?.data?.list));
  check('命中字段是 name.pinyin.first',
    (firstHit?.matchedFields || []).includes('name.pinyin.first'), firstHit?.matchedFields);

  const singlePy = await search({ keyword: 'q', type: 'song' }, 'pinyin');
  const singlePyList = singlePy.json?.data?.list || [];
  check('「q」仍能按全拼前缀召回（对照：守卫只管首字母项）', singlePyList.length > 0,
    names(singlePyList));
  check('D-46：单字符不参与首字母匹配（没有结果标记 name.pinyin.first）',
    singlePyList.every((x) => !(x.matchedFields || []).includes('name.pinyin.first')),
    singlePyList.map((x) => `${x.name}[${(x.matchedFields || []).join('+')}]`));

  const zjl = await search({ keyword: 'zjl', type: 'all' }, 'pinyin');
  const zjlArtists = zjl.json?.data?.artists?.list || [];
  check('「zjl」在 artists 段命中周杰伦', names(zjlArtists).includes('周杰伦'), names(zjlArtists));
  check('歌手段 matchedFields 含 name.pinyin.first',
    (zjlArtists[0]?.matchedFields || []).includes('name.pinyin.first'), zjlArtists[0]?.matchedFields);

  const artistPy = await search({ keyword: 'zhoujielun', type: 'song' }, 'pinyin');
  const artistPyList = artistPy.json?.data?.list || [];
  check('「zhoujielun」按歌手全拼命中《晴天》《稻香》',
    names(artistPyList).includes('晴天') && names(artistPyList).includes('稻香'), names(artistPyList));
  check('命中字段是 artist.name.pinyin',
    (byName(artistPyList, '晴天')?.matchedFields || []).includes('artist.name.pinyin'),
    byName(artistPyList, '晴天')?.matchedFields);

  const zjlSongs = await search({ keyword: 'zjl', type: 'song' }, 'pinyin');
  check('歌曲侧按歌手首字母可搜到（ES 读 artistNamePinyinFirst；MySQL 实现在这点上不同）',
    names(zjlSongs.json?.data?.list).includes('晴天'), names(zjlSongs.json?.data?.list));

  const singleCn = await search({ keyword: '晴', type: 'song' }, 'single');
  check('单字中文「晴」命中《晴天》（output_unigrams 生效）',
    names(singleCn.json?.data?.list).includes('晴天'), names(singleCn.json?.data?.list));
  const tian = await search({ keyword: '天', type: 'song' }, 'single');
  const tianSeed = names((tian.json?.data?.list || []).filter(isSeedSong));
  check('单字「天」命中种子里 3 首',
    tianSeed.slice().sort().join() === ['晴天', '倔强', '突然好想你'].sort().join(), tianSeed);

  // ================================================================ §04 筛选与排序
  section('§04 筛选与排序：filter 上下文、三种排序');

  const base = await search({ keyword: '天', type: 'song' }, 'filter');
  const baseList = base.json?.data?.list || [];
  const baseScores = baseList.map((x) => x.score);
  check('默认排序按 score 降序（取整后不递增）',
    baseScores.every((v, i) => i === 0 || baseScores[i - 1] >= v), baseScores);

  const rock = await search({ keyword: '天', type: 'song', genre: '摇滚' }, 'filter');
  const rockSeed = (rock.json?.data?.list || []).filter(isSeedSong);
  check('genre=摇滚 在种子里只回《倔强》《突然好想你》',
    sortedNames(rockSeed) === ['倔强', '突然好想你'].sort().join(), names(rockSeed));

  const scorePairs = (list) => list.filter(isSeedSong).map((x) => `${x.id}:${x.score}`).sort();
  check('加筛选后命中项 score 不变（filter 上下文不打分）',
    scorePairs(rock.json?.data?.list).every((s) => scorePairs(baseList).includes(s)),
    { base: scorePairs(baseList), filtered: scorePairs(rock.json?.data?.list) });

  const cantonese = await search({ keyword: '天', type: 'song', language: '粤语' }, 'filter');
  check('language=粤语 在种子里为空', (cantonese.json?.data?.list || []).filter(isSeedSong).length === 0,
    names(cantonese.json?.data?.list));

  const longOnly = await search({ keyword: '天', type: 'song', duration: 'gt5' }, 'filter');
  check('duration=gt5 在种子里只回《突然好想你》（347s）',
    sortedNames((longOnly.json?.data?.list || []).filter(isSeedSong)) === '突然好想你',
    names(longOnly.json?.data?.list));

  const badDur = await search({ keyword: '天', type: 'song', duration: 'abc' }, 'filter');
  check('未知 duration 取值判为不匹配（不是静默忽略）',
    (badDur.json?.data?.list || []).length === 0, names(badDur.json?.data?.list));

  const byHot = await search({ keyword: '天', type: 'song', sort: 'hot' }, 'filter');
  const hot = (byHot.json?.data?.list || []).map((x) => x.hotScore);
  check('sort=hot 按 hotScore 降序', hot.every((v, i) => i === 0 || hot[i - 1] >= v), hot);

  const byDurAsc = await search({ keyword: '天', type: 'song', sort: 'duration_asc' }, 'filter');
  const durAsc = (byDurAsc.json?.data?.list || []).map((x) => x.duration);
  check('sort=duration_asc 升序', durAsc.every((v, i) => i === 0 || durAsc[i - 1] <= v), durAsc);

  const byDurDesc = await search({ keyword: '天', type: 'song', sort: 'duration_desc' }, 'filter');
  const durDesc = (byDurDesc.json?.data?.list || []).map((x) => x.duration);
  check('sort=duration_desc 降序', durDesc.every((v, i) => i === 0 || durDesc[i - 1] >= v), durDesc);

  // ================================================================ §05 歌词
  section('§05 歌词检索：命中标记、片段窗口、LRC 剥离');

  const lyric = await search({ keyword: '小黄花', type: 'song' }, 'lyric');
  const lyricHit = byName(lyric.json?.data?.list, '晴天');
  check('「小黄花」命中《晴天》', Boolean(lyricHit), names(lyric.json?.data?.list));
  check('matchedFields 含 lyric', (lyricHit?.matchedFields || []).includes('lyric'),
    lyricHit?.matchedFields);
  const snippet = lyricHit?.highlight?.lyric || '';
  check('歌词片段已高亮 <em class="hl">小黄花</em>',
    snippet.includes('<em class="hl">小黄花</em>'), snippet);
  check('片段是 ±30 字窗口而不是整篇', snippet.length > 0 && snippet.length < 120, snippet.length);
  check('片段里没有 LRC 时间轴', !/\[\d\d:/.test(snippet), snippet);

  const lyricOnly = await search({ keyword: '故事的小黄花', type: 'lyric' }, 'lyric');
  check('type=lyric 命中《晴天》', names(lyricOnly.json?.data?.list).includes('晴天'),
    names(lyricOnly.json?.data?.list));
  const lyricMiss = await search({ keyword: '稻香', type: 'lyric' }, 'lyric');
  check('type=lyric 只回歌词命中的歌（《稻香》歌词不含歌名）',
    !names(lyricMiss.json?.data?.list).includes('稻香'), names(lyricMiss.json?.data?.list));

  const doc = await es('GET', `/${ALIAS}/_doc/SONG_${SEED.songQingTian}`);
  const docLyric = doc.json?._source?.lyric || '';
  check('索引里的歌词已剥离 LRC', docLyric.length > 0 && !/\[\d\d:/.test(docLyric),
    { len: docLyric.length, head: docLyric.slice(0, 40) });

  // ================================================================ §06 游标
  section('§06 游标翻页：search_after 不重不漏');

  const seen = new Set();
  let cursor = null;
  let pages = 0;
  let duplicated = false;
  let lastPageCursor = 'unset';
  let firstTotal = null;
  do {
    const params = { keyword: '天', type: 'song', pageSize: '3' };
    if (cursor) params.cursor = cursor;
    const r = await search(params, 'cursor');
    const list = r.json?.data?.list || [];
    if (pages === 0) firstTotal = r.json?.data?.total;
    for (const id of ids(list)) {
      if (seen.has(id)) duplicated = true;
      seen.add(id);
    }
    cursor = r.json?.data?.cursor || null;
    lastPageCursor = cursor;
    pages += 1;
    if (!cursor) break;
  } while (pages < 10);
  check('第一页 total ≥ 4（有得翻）', firstTotal >= 4, firstTotal);
  check('翻页没有重复 id', !duplicated, [...seen]);
  check('翻到了多页（≥2）', pages >= 2, pages);
  check('末页没有 cursor（前端不会多翻一页空数据）', lastPageCursor === null, lastPageCursor);
  check('游标集合非空', seen.size >= 4, seen.size);
  note(`游标翻页共 ${pages} 页、${seen.size} 条`);

  // ================================================================ §07 写入穿透
  section('§07 写入穿透：后台增/改/删后 ES 立即可见');

  const token = await adminLogin();
  const marker = `ES同步${Date.now()}`;
  const renamed = `${marker}改`;
  let createdId = null;
  let deleted = false;
  try {
    const created = await api('POST', '/music/songs', {
      token,
      body: {
        name: marker,
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
    createdId = created.json?.data;
    check('临时歌曲创建成功（否则后面几条是空转）', created.json?.code === 0 && Boolean(createdId),
      created.json);

    const found = await search({ keyword: marker, type: 'song' }, 'write');
    const foundHit = (found.json?.data?.list || [])[0];
    check('创建后立刻能搜到（refresh=true 生效）', foundHit?.name === marker,
      names(found.json?.data?.list));

    const esDoc = await es('GET', `/${ALIAS}/_doc/SONG_${createdId}`);
    check('ES 文档已写入（按 SONG_{id} 取到）', esDoc.json?.found === true, esDoc.status);
    check('文档里的中文没有乱码（_source.name 与请求一致）',
      esDoc.json?._source?.name === marker, esDoc.json?._source?.name);
    check('文档带应用侧物化的拼音字段',
      typeof esDoc.json?._source?.namePinyin === 'string'
        && esDoc.json._source.namePinyin.length > 0, esDoc.json?._source?.namePinyin);
    check('ES 响应本身是合法 UTF-8（无替换字符）', !esDoc.text.includes('\uFFFD'));

    const detail = await api('GET', `/music/songs/${createdId}`, { token, xff: ipOf('write') });
    const version = detail.json?.data?.version;
    const updated = await api('PUT', `/music/songs/${createdId}`, {
      token,
      body: {
        name: renamed,
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
        version,
      },
    });
    check('临时歌曲改名成功', updated.json?.code === 0, updated.json);

    const afterRename = await search({ keyword: renamed, type: 'song' }, 'write');
    check('改名后新名字能搜到', names(afterRename.json?.data?.list).includes(renamed),
      names(afterRename.json?.data?.list));
    const oldName = await search({ keyword: marker, type: 'song' }, 'write');
    check('改名后旧名字搜不到（upsert 覆盖而不是新增文档）',
      !names(oldName.json?.data?.list).includes(marker), names(oldName.json?.data?.list));

    const removed = await api('DELETE', `/music/songs/${createdId}`, { token });
    deleted = removed.json?.code === 0;
    check('临时歌曲删除成功（软删）', deleted, removed.json);

    const afterDelete = await search({ keyword: renamed, type: 'song' }, 'write');
    check('删除后搜不到', (afterDelete.json?.data?.list || []).length === 0,
      names(afterDelete.json?.data?.list));
    const docAfterDelete = await es('GET', `/${ALIAS}/_doc/SONG_${createdId}`);
    check('删除后 ES 文档也已移除（404 / found=false）',
      docAfterDelete.status === 404 || docAfterDelete.json?.found === false,
      docAfterDelete.status);
  } finally {
    // 无论哪一条断言失败，都要把临时歌曲删掉：写进语料的临时数据不能留在索引里
    if (createdId && !deleted) {
      const cleanup = await api('DELETE', `/music/songs/${createdId}`, { token });
      note(`清理临时歌曲 ${createdId}：${cleanup.json?.code === 0 ? '成功' : '失败，请手工删除'}`);
    }
  }

  // ================================================================ §08 共享实例与收尾
  section('§08 共享实例：只碰 music_song 别名，别的索引原样');

  const afterIndices = await es('GET', '/_cat/indices?format=json&h=index');
  const afterNames = (Array.isArray(afterIndices.json) ? afterIndices.json : []).map((r) => r.index);
  const othersAfter = afterNames.filter((n) => !n.startsWith(ALIAS)).sort();
  check('非 music_song* 索引集合前后一致（没误删/误建别人的索引）',
    othersAfter.join('|') === othersBefore.join('|'), { before: othersBefore, after: othersAfter });
  check('music_song_v* 仍然只有 1 个',
    afterNames.filter((n) => n.startsWith(`${ALIAS}_v`)).length === 1,
    afterNames.filter((n) => n.startsWith(`${ALIAS}_v`)));

  // 用 name.raw（keyword）做前缀：name 是 text，分词后前缀里那段中文对不上 token
  const leftover = await es('POST', `/${ALIAS}/_count`,
    { query: { prefix: { 'name.raw': { value: 'ES同步' } } } });
  check('临时测试文档没有残留', leftover.json?.count === 0, leftover.json?.count);
  note('本脚本全程不执行 SQL、不建 Redis 键：共享 MySQL/Redis 实例不受影响');
}

main()
  .catch((e) => {
    fail += 1;
    failures.push(`脚本异常：${e.message}`);
    console.error(`\n\x1b[31m脚本异常\x1b[0m：${e && e.stack ? e.stack : e}`);
  })
  .finally(() => {
    console.log(`\n\x1b[1m通过 ${pass} / 失败 ${fail}${skipNotes.length ? ` / 跳过 ${skipNotes.length}` : ''}\x1b[0m`);
    if (skipNotes.length) {
      console.log('\n跳过的断言：');
      for (const s of skipNotes) console.log(`  ○ ${s}`);
    }
    if (failures.length) {
      console.log('\n失败项：');
      for (const f of failures) console.log(`  ✗ ${f}`);
      process.exitCode = 1;
    }
  });
