#!/usr/bin/env node
/**
 * 验收脚本 —— 歌曲歌词随导入写入 + 待审歌曲进入审核台 + 审核通过后上架。
 *
 * 覆盖本次修复的四条主链路（对应 docs/功能目录.md §0）：
 *   1. 导入歌曲时带 `lyric` → t_lyric 落库、t_song.lyric_id 回写、C 端可读；
 *   2. 后台歌词接口：读取、覆盖正文、content_type 自动识别 / 非法值拒绝；
 *   3. 逻辑删除后的墓碑行：再写入时复活原行（uk_lyric_song 不随软删释放），
 *      以及并发写入不返回 99001、最终只留一份歌词；
 *   4. audit_status=0 的歌曲自动进审核工作台（SONG 任务），领取→通过→状态回写，
 *      审核通过后可上架（status=1），删除歌曲时歌词级联清理。
 *
 * 运行（后端 8081 已启动，且 NEWMUSIC_ADMIN_CAPTCHA_ECHO=true）：
 *   node scripts/verify-lyrics-audit.mjs
 *   MYSQL_PASSWORD=*** node scripts/verify-lyrics-audit.mjs   # 额外清理审核任务/留痕
 *
 * 数据安全：
 *   * 只创建一条带随机后缀的临时歌曲，结束时用 C 端超管软删除；
 *   * 审核任务/留痕只有在提供 MYSQL_PASSWORD 时才按「本轮任务 ID」精确物理删除，
 *     且所有 SQL 都带 newmusic. 限定符，不做全库/无条件删除；
 *   * 口令只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给 mysql 客户端，不进 argv、不落盘；
 *   * 不读写 Redis，不触碰 ES 索引名，不删除任何非本轮创建的数据。
 *
 * 可重复执行：临时歌曲名带时间戳后缀；未提供 MYSQL_PASSWORD 时审核历史会保留一条，
 * 脚本会在结尾打印需要手工清理的 taskId（不自动执行任何危险 SQL）。
 */

import { spawnSync } from 'node:child_process';

const BASE = process.env.API_BASE || 'http://127.0.0.1:8081/api/v1';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const MYSQL_PORT = process.env.MYSQL_PORT || '3306';

const cookies = new Map();
const cookieHeader = () => [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');

/**
 * 统一请求：后台用 Cookie（登录后自动收集 Set-Cookie），C 端用 Bearer token。
 * 19 位 ID 一律当字符串处理，任何 Number() 都会丢精度。
 */
async function req(method, path, json, { token = null, base = BASE } = {}) {
  const headers = {};
  const cookie = cookieHeader();
  if (cookie) headers.Cookie = cookie;
  if (token) headers.Authorization = `Bearer ${token}`;
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
    body = JSON.stringify(json);
  }
  const res = await fetch(base + path, { method, headers, body });
  for (const raw of (res.headers.getSetCookie?.() || [])) {
    const pair = raw.split(';')[0];
    const i = pair.indexOf('=');
    if (i > 0) cookies.set(pair.slice(0, i).trim(), pair.slice(i + 1));
  }
  const text = await res.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = null; }
  return { status: res.status, json: parsed, text };
}

let pass = 0;
let fail = 0;
const failures = [];
function check(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    failures.push(name);
    console.log(`  ✗ ${name}${extra === undefined ? '' : ` → ${JSON.stringify(extra).slice(0, 500)}`}`);
  }
}

/** 只在提供 MYSQL_PASSWORD 时执行；SQL 必须是参数化的本轮精确条件。 */
function mysql(sql) {
  const r = spawnSync(MYSQL_CLI, [
    '-u', MYSQL_USER, '-h', '127.0.0.1', '-P', MYSQL_PORT,
    '-D', 'newmusic', '-N', '-B', '--default-character-set=utf8mb4',
  ], { encoding: 'utf8', input: sql, env: { ...process.env, MYSQL_PWD } });
  if (r.error) throw new Error(`无法执行 mysql 客户端（${MYSQL_CLI}）：${r.error.message}`);
  if (r.status !== 0) throw new Error(`mysql 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

let tempSongId = null;
let tempSongName = null;
let tempTaskId = null;
let adminTokenForCleanup = null;

async function cleanup() {
  console.log('\n— 清理 —');
  if (tempSongId) {
    const del = await req('DELETE', `/music/songs/${tempSongId}`, undefined, { token: adminTokenForCleanup });
    check('临时歌曲已软删除', del.json?.code === 0, del.json);
    const lyric = await req('GET', `/music/lyrics/${tempSongId}`);
    check('歌词随歌曲级联删除', lyric.json?.code === 0 && lyric.json?.data === null, lyric.json);
  } else {
    console.log('  · 没有创建临时歌曲，无需删除');
  }

  if (tempTaskId && MYSQL_PWD && /^\d+$/.test(String(tempTaskId))) {
    // 只删本轮 taskId：审核留痕先删，再删任务；都带 newmusic. 限定
    mysql(`DELETE FROM newmusic.t_audit_record WHERE task_id = ${tempTaskId};\n`
      + `DELETE FROM newmusic.t_audit_task WHERE id = ${tempTaskId} AND task_type = 'SONG';\n`);
    console.log(`  · 已清理审核任务 ${tempTaskId} 及其留痕`);
  } else if (tempTaskId) {
    console.log(`  · 未提供 MYSQL_PASSWORD，审核任务 ${tempTaskId} 保留（如不需要可手工删除）`);
  }
}

try {
  // ---------------------------------------------------------------- 0. 登录
  console.log('— 0. 登录 —');
  // 浏览器里残留的旧 Cookie 不能阻断重新登录（本次走查发现的第 5 个问题）
  const staleCaptcha = await fetch(`${BASE}/admin/auth/captcha`, {
    headers: { Cookie: 'ml_admin_access=stale.invalid.jwt' },
  });
  const staleJson = await staleCaptcha.json().catch(() => null);
  check('失效旧 Cookie 不再阻断验证码（可重新登录）', staleJson?.code === 0,
    { status: staleCaptcha.status, body: staleJson });
  const staleGuarded = await fetch(`${BASE}/admin/songs`, {
    headers: { Cookie: 'ml_admin_access=stale.invalid.jwt' },
  });
  check('受保护接口仍拒绝失效 Cookie', staleGuarded.status === 401 || staleGuarded.status === 403,
    staleGuarded.status);

  const cap = await req('GET', '/admin/auth/captcha');
  const adminLogin = await req('POST', '/admin/auth/login', {
    username: process.env.NEWMUSIC_ADMIN_USER || 'admin',
    password: process.env.NEWMUSIC_ADMIN_PASSWORD || 'admin123',
    captchaId: cap.json?.data?.captchaId,
    captchaCode: cap.json?.data?.code,
  });
  check('后台登录（验证码回显）', adminLogin.json?.code === 0,
    adminLogin.json?.message || '需要 NEWMUSIC_ADMIN_CAPTCHA_ECHO=true');
  if (adminLogin.json?.code !== 0) throw new Error('后台登录失败，后续用例无法执行');

  const cLogin = await req('POST', '/auth/login', {
    account: process.env.NEWMUSIC_SUPER_USER || 'admin',
    password: process.env.NEWMUSIC_SUPER_PASSWORD || 'admin123',
  });
  adminTokenForCleanup = cLogin.json?.data?.accessToken || null;
  const perms = cLogin.json?.data?.user?.perms || [];
  check('C 端超管拥有 music:song:delete（清理用）', Boolean(adminTokenForCleanup) && perms.includes('music:song:delete'),
    { hasToken: Boolean(adminTokenForCleanup), perms: perms.length });

  // ---------------------------------------------------------------- 1. 取一条可用音源
  console.log('\n— 1. 准备可复用的音源 —');
  const sourcePage = await req('GET', '/admin/songs?page=1&pageSize=100&status=1');
  const sourceSong = (sourcePage.json?.data?.list || []).find((s) => s.audioStandard && s.artistId);
  check('曲库里有可复用的已上架歌曲（音源 + 歌手）', Boolean(sourceSong),
    sourcePage.json?.message || '请先导入至少一首已上架歌曲');
  if (!sourceSong) throw new Error('没有可用音源，无法创建临时歌曲');

  // ---------------------------------------------------------------- 2. 导入带歌词的临时歌曲
  console.log('\n— 2. 导入歌曲时随行带歌词 —');
  tempSongName = `歌词审核联调-${Date.now().toString(36)}`;
  const lrc = '[00:01.00]第一句验收歌词\n[00:03.50]第二句验收歌词';
  const imp = await req('POST', '/admin/songs/import', {
    rows: [{
      name: tempSongName,
      artistId: sourceSong.artistId, // 字符串原样回传，禁止 Number()
      audioStandard: sourceSong.audioStandard,
      duration: 30,
      copyrightStatus: 1,
      lyric: lrc,
    }],
  });
  check('导入接口成功 1 行', imp.json?.code === 0 && imp.json?.data?.success === 1, imp.json);

  const find = await req('GET', `/admin/songs?keyword=${encodeURIComponent(tempSongName)}&page=1&pageSize=10`);
  const created = (find.json?.data?.list || []).find((s) => s.name === tempSongName);
  check('能按歌名查到新歌', Boolean(created), find.json?.data?.list);
  if (!created) throw new Error('找不到刚导入的临时歌曲');
  tempSongId = String(created.id);
  check('新歌默认待审（auditStatus=0）', created.auditStatus === 0, created.auditStatus);
  check('t_song.lyric_id 已回写', Boolean(created.lyricId), created.lyricId);

  const lyricRead = await req('GET', `/music/lyrics/${tempSongId}`);
  check('C 端可直接读到歌词正文', lyricRead.json?.code === 0 && lyricRead.json?.data?.content === lrc,
    lyricRead.json);
  check('歌词类型识别为标准 LRC(1)', lyricRead.json?.data?.contentType === 1,
    lyricRead.json?.data?.contentType);

  const adminLyric = await req('GET', `/admin/songs/${tempSongId}/lyric`);
  check('后台歌词读取接口返回同一份正文', adminLyric.json?.data?.content === lrc, adminLyric.json?.data?.content);

  // ---------------------------------------------------------------- 3. 后台覆盖歌词
  console.log('\n— 3. 后台歌词覆盖与类型校验 —');
  const plain = '这是一段没有时间轴的纯文本歌词';
  const putPlain = await req('PUT', `/admin/songs/${tempSongId}/lyric`, { content: plain });
  check('覆盖为纯文本成功', putPlain.json?.code === 0, putPlain.json);
  const afterPlain = await req('GET', `/admin/songs/${tempSongId}/lyric`);
  check('正文已覆盖', afterPlain.json?.data?.content === plain, afterPlain.json?.data?.content);
  check('自动识别为纯文本(3)', afterPlain.json?.data?.contentType === 3, afterPlain.json?.data?.contentType);

  const badType = await req('PUT', `/admin/songs/${tempSongId}/lyric`, { content: 'x', contentType: 9 });
  check('非法 contentType(9) 返回 10001', badType.json?.code === 10001, badType.json);
  const badBlank = await req('PUT', `/admin/songs/${tempSongId}/lyric`, { content: '   ' });
  check('空白正文返回 10001', badBlank.json?.code === 10001, badBlank.json);

  // ---------------------------------------------------------------- 3.5 删除后重传 / 并发写
  console.log('\n— 3.5 逻辑删除后的墓碑行与并发写入 —');
  // 逻辑删除不释放 uk_lyric_song：如果按歌曲写入只做 INSERT，这里会永远失败。
  const plainLyricId = afterPlain.json?.data?.id;
  const delLyric = await req('DELETE', `/music/lyrics/${plainLyricId}`, undefined,
    { token: adminTokenForCleanup });
  check('C 端超管逻辑删除歌词成功', delLyric.json?.code === 0, delLyric.json);
  const gone = await req('GET', `/music/lyrics/${tempSongId}`);
  check('删除后 C 端读不到歌词', gone.json?.code === 0 && gone.json?.data === null, gone.json);

  const revived = await req('PUT', `/admin/songs/${tempSongId}/lyric`, {
    content: '[00:02.00]删除后重新写入的歌词',
  });
  check('删后再按歌曲写入：复活墓碑行而不是撞唯一键',
    revived.json?.code === 0 && String(revived.json?.data) === String(plainLyricId),
    { code: revived.json?.code, id: revived.json?.data, expected: plainLyricId });
  const afterRevive = await req('GET', `/admin/songs/${tempSongId}/lyric`);
  check('复活后正文可读且复用原行 ID',
    afterRevive.json?.data?.content === '[00:02.00]删除后重新写入的歌词'
      && String(afterRevive.json?.data?.id) === String(plainLyricId),
    { content: afterRevive.json?.data?.content, id: afterRevive.json?.data?.id });

  // 再删一次，让两个并发请求都从「看不到可见行」开始：
  // 一个复活墓碑行，另一个复活失败/撞唯一键后转覆盖，两者都应成功且只留一份歌词
  const delAgain = await req('DELETE', `/music/lyrics/${plainLyricId}`, undefined,
    { token: adminTokenForCleanup });
  check('并发写入前再次逻辑删除歌词', delAgain.json?.code === 0, delAgain.json);
  const concurrent = await Promise.all([
    req('PUT', `/admin/songs/${tempSongId}/lyric`, { content: '[00:03.00]并发写入 A' }),
    req('PUT', `/admin/songs/${tempSongId}/lyric`, { content: '[00:03.00]并发写入 B' }),
  ]);
  check('并发按歌曲写入均成功（不返回 99001）',
    concurrent.every((r) => r.json?.code === 0),
    concurrent.map((r) => ({ code: r.json?.code, message: r.json?.message })));
  const afterConcurrent = await req('GET', `/admin/songs/${tempSongId}/lyric`);
  check('并发后只有一份歌词，正文是两者之一',
    ['[00:03.00]并发写入 A', '[00:03.00]并发写入 B'].includes(afterConcurrent.json?.data?.content),
    afterConcurrent.json?.data?.content);

  // ---------------------------------------------------------------- 4. 审核台
  console.log('\n— 4. 待审歌曲进入审核工作台 —');
  const audits = await req('GET', '/admin/audits?taskType=SONG&status=0&page=1&pageSize=100');
  const task = (audits.json?.data?.list || []).find((t) => String(t.targetId) === tempSongId);
  check('审核列表出现该歌曲', Boolean(task), (audits.json?.data?.list || []).map((t) => t.targetId));
  if (task) {
    tempTaskId = String(task.id);
    check('任务类型/场景均为 SONG', task.taskType === 'SONG' && task.scene === 'SONG', {
      taskType: task.taskType, scene: task.scene,
    });
    check('任务标题包含歌名', String(task.title || '').includes(tempSongName), task.title);
    check('已设置 24h SLA', Boolean(task.slaDeadline), task.slaDeadline);
    check('后台导入无伪造提交人', task.submitterId === null || task.submitterId === undefined, task.submitterId);

    const claim = await req('POST', `/admin/audits/${tempTaskId}/claim`);
    check('领取任务', claim.json?.code === 0, claim.json);
    const handle = await req('POST', `/admin/audits/${tempTaskId}/handle`, { result: 'APPROVE' });
    check('审核通过', handle.json?.code === 0, handle.json);

    const detail = await req('GET', `/admin/songs/${tempSongId}`);
    check('歌曲 auditStatus 回写为 1', detail.json?.data?.auditStatus === 1, detail.json?.data?.auditStatus);

    const records = await req('GET', `/admin/audits/${tempTaskId}/records`);
    check('审核留痕包含领取与处理', (records.json?.data || []).length >= 2, records.json?.data?.length);
  }

  // ---------------------------------------------------------------- 5. 上架
  console.log('\n— 5. 审核通过后上架 —');
  const publish = await req('PUT', `/admin/songs/${tempSongId}/status/1`);
  check('上架接口成功', publish.json?.code === 0, publish.json);
  const afterPublish = await req('GET', `/admin/songs/${tempSongId}`);
  check('歌曲 status=1', afterPublish.json?.data?.status === 1, afterPublish.json?.data?.status);
} finally {
  await cleanup();
}

console.log(`\n结果：通过 ${pass} / 失败 ${fail}`);
if (failures.length) {
  console.log('失败用例：');
  for (const name of failures) console.log(`  - ${name}`);
}
process.exitCode = fail === 0 ? 0 : 1;
