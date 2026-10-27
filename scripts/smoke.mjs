#!/usr/bin/env node
/**
 * 模块 01 冒烟测试（对应 docs/系分/01-数据库CRUD.md §8 验收核对表）
 *
 * 运行：
 *   node scripts/smoke.mjs                        # 默认 http://localhost:8081/api/v1
 *   API_BASE=http://localhost:8081/api/v1 node scripts/smoke.mjs
 *
 * 前置：应用已启动（mvn spring-boot:run）+ schema.sql/data.sql 已执行。
 * 依赖：Node 18+（内置 fetch），无需 jq / curl。
 *
 * 脚本只做「新增→校验→删除」的可回收操作，重复执行不会残留脏数据。
 */

const BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
/** 应用根地址（actuator 等非 /api 路径用） */
const ROOT = BASE.replace(/\/api\/.*$/, '');
/** 管理端口根地址（actuator 独立端口，见 application.yml 的 management.server.port） */
const MGMT = process.env.MGMT_BASE || 'http://localhost:8082';

let pass = 0;
let fail = 0;
const failures = [];

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

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

async function call(method, path, { token, body, headers } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = `Bearer ${token}`;
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（如 actuator 健康检查） */
  }
  return { status: res.status, json, text, traceHeader: res.headers.get('X-Trace-Id') };
}

const isString = (v) => typeof v === 'string';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪，请确认端口 8081 与数据库配置。');
    process.exit(2);
  }

  // ---------------------------------------------------------------- 健康检查
  section('00 服务可用性');
  // actuator 走**独立管理端口**（application.yml 的 management.server.port=8082）：
  // 同端口注册 WebMvcEndpointHandlerMapping 会让 springfox 3.0.0（Knife4j 3.0.3）
  // 在 documentationPluginsBootstrapper 里 NPE，接口文档整个起不来。
  const healthRes = await fetch(`${MGMT}/actuator/health`);
  const healthText = await healthRes.text();
  check('actuator/health 可匿名访问且状态 UP',
    healthRes.status === 200 && /"status"\s*:\s*"UP"/.test(healthText), healthText);
  // 细则不该匿名可见（NFR-S-058 / show-details=never）
  check('actuator/health 不泄漏细节（show-details=never）',
    !/components|db|datasource|diskSpace/.test(healthText), healthText);
  // 其余端点不开放：只 include 了 health，env 应为 4xx
  const envRes = await fetch(`${MGMT}/actuator/env`);
  check('未暴露的 actuator 端点不可访问', envRes.status >= 400, `HTTP ${envRes.status}`);

  // ---------------------------------------------------------------- 鉴权
  section('01 鉴权与登录（AC: 用户域）');
  const adminLogin = await call('POST', '/auth/login', { body: { account: 'admin', password: 'admin123' } });
  check('admin 登录成功', adminLogin.json?.code === 0, adminLogin.json);
  const adminToken = adminLogin.json?.data?.accessToken;
  check('返回 accessToken / refreshToken',
    Boolean(adminToken) && Boolean(adminLogin.json?.data?.refreshToken));
  check('登录响应携带 traceId', Boolean(adminLogin.json?.traceId));
  check('响应头 X-Trace-Id 与响应体 traceId 一致',
    Boolean(adminLogin.traceHeader) && adminLogin.traceHeader === adminLogin.json?.traceId,
    { header: adminLogin.traceHeader, body: adminLogin.json?.traceId });
  check('用户 ID 序列化为字符串（AC-10-104）', isString(adminLogin.json?.data?.user?.id),
    adminLogin.json?.data?.user?.id);
  check('admin 持有 music:song:create 权限',
    (adminLogin.json?.data?.user?.perms || []).includes('music:song:create'));

  const demoLogin = await call('POST', '/auth/login', { body: { account: 'demo', password: 'demo123456' } });
  const demoToken = demoLogin.json?.data?.accessToken;
  check('demo 登录成功且仅有 ROLE_USER',
    demoLogin.json?.code === 0 && (demoLogin.json?.data?.user?.roles || []).join() === 'ROLE_USER');
  check('demo 不携带任何后台权限码（ROLE_USER 无权限点）',
    (demoLogin.json?.data?.user?.perms || []).length === 0, demoLogin.json?.data?.user?.perms);

  const operatorLogin = await call('POST', '/auth/login', { body: { account: 'operator', password: 'operator123' } });
  check('operator 拥有 music:song:create 但无 music:song:delete',
    (operatorLogin.json?.data?.user?.perms || []).includes('music:song:create')
    && !(operatorLogin.json?.data?.user?.perms || []).includes('music:song:delete'),
    operatorLogin.json?.data?.user?.perms);
  check('资源级通配 music:artist:* 已展开为具体动作码',
    (operatorLogin.json?.data?.user?.perms || []).includes('music:artist:create')
    && (operatorLogin.json?.data?.user?.perms || []).includes('music:artist:delete'),
    operatorLogin.json?.data?.user?.perms);

  const badLogin = await call('POST', '/auth/login', { body: { account: 'admin', password: 'wrong-pass' } });
  check('密码错误返回 20001 且 HTTP 200', badLogin.json?.code === 20001 && badLogin.status === 200, badLogin.json);

  const me = await call('GET', '/users/me', { token: demoToken });
  check('GET /users/me 返回本人资料', me.json?.code === 0 && me.json?.data?.username === 'demo', me.json);
  const noTokenMe = await call('GET', '/users/me');
  check('未登录访问 /users/me 返回 401/40101',
    noTokenMe.status === 401 && noTokenMe.json?.code === 40101, noTokenMe.json);

  // ---------------------------------------------------------------- 分页与筛选
  section('02 分页/排序/模糊查询（AC-10-001）');
  const big = await call('GET', '/music/songs?pageSize=999');
  check('pageSize=999 被截断为 100（AC-10-001-1）',
    big.json?.data?.pageSize === 100 && big.json?.data?.truncated === true, big.json?.data);
  check('列表 ID 为字符串（AC-10-104）', isString(big.json?.data?.list?.[0]?.id));
  check('列表不返回已删除数据（AC-10-001-5 前置）',
    (big.json?.data?.list || []).every((s) => s.deleted === undefined));

  const injection = await call('GET', '/music/songs?sortBy=' + encodeURIComponent('1;DROP TABLE t_song'));
  check('非法 sortBy 回退默认排序且不报错（AC-10-001-2）', injection.json?.code === 0, injection.json);
  const afterInjection = await call('GET', '/music/songs?pageSize=1');
  check('注入尝试后 t_song 仍可查询', afterInjection.json?.code === 0 && afterInjection.json?.data?.total > 0);

  const percent = await call('GET', '/music/songs?keyword=' + encodeURIComponent('%'));
  check('keyword=% 按字面匹配（转义生效，AC-10-001-4）',
    percent.json?.code === 0 && percent.json?.data?.total === 0, percent.json?.data);

  const kw = await call('GET', '/music/songs?keyword=' + encodeURIComponent('晴天'));
  check('keyword=晴天 命中歌曲', kw.json?.code === 0 && kw.json?.data?.total >= 1, kw.json?.data);

  const filtered = await call('GET', '/music/songs?pageSize=50&sortBy=hotScore&sortOrder=desc');
  const scores = (filtered.json?.data?.list || []).map((s) => s.hotScore ?? 0);
  check('sortBy=hotScore 按白名单排序生效',
    scores.every((v, i) => i === 0 || scores[i - 1] >= v), scores);

  const byTag = await call('GET', '/music/songs?tagId=1000000000000008006');
  check('tagId 子查询筛选可用', byTag.json?.code === 0 && byTag.json?.data?.total >= 1, byTag.json?.data);

  const songId = kw.json?.data?.list?.[0]?.id;
  const detail = await call('GET', `/music/songs/${songId}`);
  check('歌曲详情可读', detail.json?.code === 0, detail.json);
  const raw = detail.text;
  check('详情不含内部字段 deleted/createBy/updateBy（AC-10-002-2）',
    !raw.includes('"deleted"') && !raw.includes('"createBy"') && !raw.includes('"updateBy"'));
  check('详情返回 version 供乐观锁回传', detail.json?.data?.version !== undefined);

  // 必须是**合法 Long** 的不存在 ID。曾用 9999999999999999999（19 个 9 ≈ 1e19），
  // 它超过 Long.MAX（9.22e18），Jackson 绑不上参数 → 正确的 10001「参数格式不正确」，
  // 而断言要的是「记录不存在」这条路，于是永远失败。1000000000000005999 是合法的 19 位 ID。
  const missing = await call('GET', '/music/songs/1000000000000005999');
  check('不存在的歌曲返回 30001 且无堆栈（AC-05-006）',
    missing.json?.code === 30001 && !/Exception|at com\./.test(missing.text), missing.json);

  // ---------------------------------------------------------------- 权限控制
  section('03 权限控制（@PreAuthorize）');
  const demoCreate = await call('POST', '/music/songs', {
    token: demoToken,
    body: { name: '越权测试', artistId: '1000000000000003001' },
  });
  check('demo 新增歌曲被拒（HTTP 403 / 40301）',
    demoCreate.status === 403 && demoCreate.json?.code === 40301, demoCreate.json);

  const anonCreate = await call('POST', '/music/songs', { body: { name: '匿名测试' } });
  check('匿名写入返回 401 / 40101',
    anonCreate.status === 401 && anonCreate.json?.code === 40101, anonCreate.json);

  // ---------------------------------------------------------------- 实体 CRUD
  section('04 曲库 CRUD + 乐观锁（AC-10-100 / AC-10-004）');
  const created = await call('POST', '/music/artists', {
    token: adminToken,
    body: { name: `冒烟歌手-${Date.now()}`, alias: 'Smoke Artist', country: '中国', gender: 1 },
  });
  const artistId = created.json?.data;
  check('新增歌手返回字符串 ID', created.json?.code === 0 && isString(artistId), created.json);

  const artistDetail = await call('GET', `/music/artists/${artistId}`);
  check('新增歌手可查（version=0）',
    artistDetail.json?.data?.id === artistId && artistDetail.json?.data?.version === 0);

  const noVersion = await call('PUT', `/music/artists/${artistId}`, {
    token: adminToken,
    body: { name: `冒烟歌手-${Date.now()}` },
  });
  check('缺少 version 的更新被拒（10001）', noVersion.json?.code === 10001, noVersion.json);

  const updated = await call('PUT', `/music/artists/${artistId}`, {
    token: adminToken,
    body: { name: `冒烟歌手-更新-${Date.now()}`, country: '中国', version: 0 },
  });
  check('带正确 version 的更新成功', updated.json?.code === 0, updated.json);
  const afterUpdate = await call('GET', `/music/artists/${artistId}`);
  check('更新后 version 自增为 1', afterUpdate.json?.data?.version === 1, afterUpdate.json?.data?.version);

  const stale = await call('PUT', `/music/artists/${artistId}`, {
    token: adminToken,
    body: { name: '并发修改', version: 0 },
  });
  check('过期 version 返回 10008（AC-10-004-1）', stale.json?.code === 10008, stale.json);

  const deleted = await call('DELETE', `/music/artists/${artistId}`, { token: adminToken });
  check('删除歌手成功', deleted.json?.code === 0, deleted.json);
  const afterDelete = await call('GET', `/music/artists/${artistId}`);
  check('删除后详情不可见（AC-10-001-5）', afterDelete.json?.code === 30041, afterDelete.json);
  const repeatDelete = await call('DELETE', `/music/artists/${artistId}`, { token: adminToken });
  check('重复删除返回 30041（记录已不可见，不再报错 500）',
    repeatDelete.json?.code === 30041, repeatDelete.json);

  // ---------------------------------------------------------------- 歌曲 + 标签级联
  section('05 歌曲/歌词/标签级联');
  const newSong = await call('POST', '/music/songs', {
    token: adminToken,
    body: {
      name: `冒烟歌曲-${Date.now()}`,
      artistId: '1000000000000003001',
      albumId: '1000000000000004001',
      duration: 200,
      language: '国语',
      genre: '流行',
      copyrightStatus: 1,
      copyrightExpire: '2030-12-31',
      audioStandard: '/media/demo/demo-01.wav',
      auditStatus: 1,
      status: 1,
      tagIds: ['1000000000000008001', '1000000000000008005'],
    },
  });
  const newSongId = newSong.json?.data;
  check('新增歌曲（含标签、直接上架）', newSong.json?.code === 0 && isString(newSongId), newSong.json);
  const newSongDetail = await call('GET', `/music/songs/${newSongId}`);
  check('冗余字段 artistName/albumName 已回填',
    newSongDetail.json?.data?.artistName === '周杰伦' && newSongDetail.json?.data?.albumName === '叶惠美',
    newSongDetail.json?.data);

  const songTags = await call('GET', `/music/tags/song/${newSongId}`);
  check('歌曲标签绑定成功（2 个）', songTags.json?.data?.length === 2, songTags.json?.data);

  const noAudio = await call('POST', '/music/songs', {
    token: adminToken,
    body: { name: `冒烟无音质-${Date.now()}`, artistId: '1000000000000003001', auditStatus: 1, status: 1, copyrightStatus: 1 },
  });
  check('无标准音质不可上架（BR-04-002 / 30043）', noAudio.json?.code === 30043, noAudio.json);

  const lyricCreated = await call('POST', '/music/lyrics', {
    token: adminToken,
    body: { songId: newSongId, content: '[00:01.00]冒烟歌词\n[00:05.00]第二行', contentType: 1 },
  });
  check('新增歌词成功', lyricCreated.json?.code === 0, lyricCreated.json);
  const dupLyric = await call('POST', '/music/lyrics', {
    token: adminToken,
    body: { songId: newSongId, content: '[00:01.00]重复' },
  });
  check('同曲重复新增歌词返回 10010', dupLyric.json?.code === 10010, dupLyric.json);
  const lyric = await call('GET', `/music/lyrics/${newSongId}`);
  check('按 songId 取歌词命中', lyric.json?.data?.songId === newSongId, lyric.json);

  const badOffset = await call('POST', '/music/lyrics', {
    token: adminToken,
    body: { songId: newSongId, content: 'x', offsetMs: 99999 },
  });
  check('歌词偏移超限被拒（10001）', badOffset.json?.code === 10001, badOffset.json);

  const songDeleted = await call('DELETE', `/music/songs/${newSongId}`, { token: adminToken });
  check('删除歌曲成功', songDeleted.json?.code === 0, songDeleted.json);
  const tagsAfter = await call('GET', `/music/tags/song/${newSongId}`);
  check('删除歌曲后标签关联被清理', (tagsAfter.json?.data || []).length === 0, tagsAfter.json);
  const lyricAfter = await call('GET', `/music/lyrics/${newSongId}`);
  check('删除歌曲后歌词被级联删除', lyricAfter.json?.data === null, lyricAfter.json);

  // ---------------------------------------------------------------- 歌单
  section('06 歌单与歌单歌曲（AC-10-100 / BR-04-010~012）');
  const privateAnon = await call('GET', '/music/playlists/1000000000000010002');
  check('匿名访问他人私密歌单返回 30004', privateAnon.json?.code === 30004, privateAnon.json);
  const privateOwner = await call('GET', '/music/playlists/1000000000000010002', { token: demoToken });
  check('创建者可见私密歌单，歌曲按 sort_order 返回',
    privateOwner.json?.data?.playlist?.songCount === 2
    && privateOwner.json?.data?.songs?.list?.length === 2
    && privateOwner.json?.data?.songs?.list?.[0]?.id === '1000000000000005005',
    privateOwner.json?.data?.songs?.list?.map((s) => s.id));

  const pl = await call('POST', '/music/playlists', {
    token: demoToken,
    body: { name: `冒烟歌单-${Date.now()}`, description: 'smoke', isPublic: 1 },
  });
  const playlistId = pl.json?.data;
  check('创建歌单成功（默认用户歌单）', pl.json?.code === 0 && isString(playlistId), pl.json);

  const addRes = await call('POST', `/music/playlists/${playlistId}/songs`, {
    token: demoToken,
    body: { songIds: ['1000000000000005001', '1000000000000005002'] },
  });
  check('加歌 2 首', addRes.json?.data === 2, addRes.json);
  const addAgain = await call('POST', `/music/playlists/${playlistId}/songs`, {
    token: demoToken,
    body: { songIds: ['1000000000000005001'] },
  });
  check('重复加歌幂等返回 0（BR-04-011）', addAgain.json?.data === 0, addAgain.json);

  const plDetail = await call('GET', `/music/playlists/${playlistId}?pageSize=10`, { token: demoToken });
  check('歌单详情 songCount=2 且歌曲有序',
    plDetail.json?.data?.playlist?.songCount === 2
    && plDetail.json?.data?.songs?.list?.[0]?.id === '1000000000000005001',
    plDetail.json?.data);

  const reorder = await call('PUT', `/music/playlists/${playlistId}/songs/order`, {
    token: demoToken,
    body: { songIds: ['1000000000000005002', '1000000000000005001'] },
  });
  check('重排成功', reorder.json?.code === 0, reorder.json);
  const plDetail2 = await call('GET', `/music/playlists/${playlistId}?pageSize=10`, { token: demoToken });
  check('重排后顺序已变更（BR-04-012）',
    plDetail2.json?.data?.songs?.list?.[0]?.id === '1000000000000005002',
    plDetail2.json?.data?.songs?.list?.map((s) => s.id));

  const excess = await call('POST', `/music/playlists/${playlistId}/songs`, {
    token: demoToken,
    body: { songIds: Array.from({ length: 501 }, (_, i) => String(1000000000000099000 + i)) },
  });
  check('单次加歌 >500 返回 10001（@Size 校验）', excess.json?.code === 10001, excess.json);

  const otherEdit = await call('PUT', `/music/playlists/1000000000000010001`, {
    token: demoToken,
    body: { name: '越权改名', version: 0 },
  });
  check('demo 修改系统歌单被拒（30005 / HTTP 403）',
    otherEdit.json?.code === 30005 && otherEdit.status === 403, otherEdit.json);

  const removed = await call('DELETE', `/music/playlists/${playlistId}/songs`, {
    token: demoToken,
    body: { songIds: ['1000000000000005001'] },
  });
  check('移歌 1 首', removed.json?.data === 1, removed.json);
  const plDetail3 = await call('GET', `/music/playlists/${playlistId}`, { token: demoToken });
  check('移歌后 songCount=1', plDetail3.json?.data?.playlist?.songCount === 1, plDetail3.json?.data?.playlist);

  const plDeleted = await call('DELETE', `/music/playlists/${playlistId}`, { token: demoToken });
  check('删除歌单成功', plDeleted.json?.code === 0, plDeleted.json);
  const plAfterDelete = await call('GET', `/music/playlists/${playlistId}`, { token: demoToken });
  check('删除后歌单不可见（30004）', plAfterDelete.json?.code === 30004, plAfterDelete.json);

  // ---------------------------------------------------------------- 后台态
  section('07 前后台可见性差异');
  const adminList = await call('GET', '/music/songs?status=0&pageSize=5', { token: adminToken });
  check('管理员可按 status 过滤（含下架）', adminList.json?.code === 0);
  const publicList = await call('GET', '/music/songs?status=0&pageSize=5');
  check('游客传 status 被忽略：列表非空且仅含已上架+审核通过',
    publicList.json?.data?.total > 0
    && (publicList.json?.data?.list || []).every((s) => s.status === 1 && s.auditStatus === 1),
    publicList.json?.data?.list?.map((s) => [s.status, s.auditStatus]));

  // ---------------------------------------------------------------- 汇总
  console.log('\n' + '─'.repeat(64));
  console.log(`通过 ${pass} 项，失败 ${fail} 项`);
  if (fail) {
    console.log('失败清单：');
    failures.forEach((f) => console.log(`  - ${f}`));
  }
  console.log('\n需人工 SQL 校验的项：');
  console.log('  AC-10-005-1 逻辑删除后物理行仍存在：');
  console.log("  SELECT id, name, deleted FROM t_song WHERE name LIKE '冒烟歌曲%';  -- deleted=1");
  console.log('  冗余计数一致性（以下每条**只列不一致的行**，返回 0 行即通过）：');
  console.log('  SELECT a.name, a.song_count, (SELECT COUNT(*) FROM t_song s WHERE s.artist_id=a.id AND s.deleted=0) c');
  console.log('    FROM t_artist a WHERE a.deleted=0');
  console.log('    AND a.song_count <> (SELECT COUNT(*) FROM t_song s WHERE s.artist_id=a.id AND s.deleted=0);');
  console.log('  SELECT t.name, t.use_count, (SELECT COUNT(*) FROM t_song_tag st WHERE st.tag_id=t.id) c');
  console.log('    FROM t_tag t WHERE t.deleted=0');
  console.log('    AND t.use_count <> (SELECT COUNT(*) FROM t_song_tag st WHERE st.tag_id=t.id);');
  // 必须按 deleted=0 限定：歌单是逻辑删除，级联只清 t_playlist_song（物理删），
  // 墓碑行上的 song_count 故意不回退——读路径全都过滤 deleted=1，它已是死数据。
  // 不加这个条件，本脚本每跑一轮都会新增 5 条"冒烟歌单-*"墓碑，把不一致数刷上去。
  console.log('  SELECT p.name, p.song_count, (SELECT COUNT(*) FROM t_playlist_song ps WHERE ps.playlist_id=p.id) c');
  console.log('    FROM t_playlist p WHERE p.deleted=0');
  console.log('    AND p.song_count <> (SELECT COUNT(*) FROM t_playlist_song ps WHERE ps.playlist_id=p.id);');
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error('冒烟脚本异常：', e);
  process.exit(3);
});
