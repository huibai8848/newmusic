#!/usr/bin/env node
/**
 * 模块 16 验收脚本 —— 社交能力升级（P0 + P1）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-16.mjs
 *
 * 前置：应用已启动（8081）、Redis 6379 可连、已执行 sql/16-social-upgrade-seed.sql。
 * 依赖 Node 22+（全局 fetch），零第三方依赖。
 *
 * 覆盖：
 *   §1 个人主页 / 资料编辑 / 隐私开关
 *   §2 动态发布、详情、点赞、评论、收藏、转发
 *   §3 @ 提及与互动通知
 *   §4 关注流（push 收件箱、游标翻页）与发现流（质量门槛、曝光 3 次淘汰、不感兴趣）
 *   §5 关系链推荐与忽略
 *   §6 私信增强：会话开关、搜索、引用、转发、营销限制
 *   §7 群聊：创建、成员、@、未读、引用、踢人、退群、解散
 *   §8 亲密度与粉丝榜
 *   §9 消息中心五分类未读
 *
 * 共享实例安全：
 *   * MySQL 3306 上还有 emp_db / music_db，所有 SQL 都带 newmusic. 限定符；
 *   * Redis 6379 的 db0 有其它项目的 ranking:* 键，只读写 ml:*；
 *   * 密码只从 MYSQL_PASSWORD 读，经 MYSQL_PWD 传给客户端，不进 argv、不落盘；
 *   * 开头/结尾比对 ranking:* 与 emp_db/music_db 表数，证明未破坏共享实例。
 */

import { spawnSync } from 'node:child_process';

// ---------------------------------------------------------------- 配置

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const RUN_TAG = `${Date.now().toString(36)}${Math.floor(Math.random() * 4096).toString(36)}`;
const PASS_WORD = 'Aa123456';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const syntheticIp = () => `10.96.${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

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
const q = (v) => `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
const idList = (ids) => ids.map((id) => q(id)).join(',');

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

const rankingBefore = REDIS_OK ? listKeys('ranking:*').sort() : [];
const sharedTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';
const userCountBefore = HAS_MYSQL ? mysqlCount('SELECT COUNT(*) FROM newmusic.t_user') : 0;

// ---------------------------------------------------------------- HTTP

const touchedIps = new Set();

async function http(method, path, { token, body, xff, headers } = {}) {
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

const codeOf = (res) => (res && res.json ? res.json.code : undefined);
const dataOf = (res) => (res && res.json ? res.json.data : undefined);
const msgOf = (res) => (res && res.json ? res.json.message : undefined);
const listOf = (res) => {
  const d = dataOf(res);
  return Array.isArray(d) ? d : (d && Array.isArray(d.list) ? d.list : []);
};
const sid = (v) => (v === null || v === undefined ? '' : String(v));

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

const created = {
  userIds: [],
  userRefreshJtis: [],
  postIds: [],
  conversationIds: [],
  messageIds: [],
  topicNames: [],
};

function trackRefresh(userId, refreshToken) {
  const claims = jwtClaims(refreshToken);
  if (claims && claims.jti) {
    created.userRefreshJtis.push(claims.jti);
  }
  created.userIds.push(userId);
}

async function registerAndLogin(label) {
  const username = `v16${label}${RUN_TAG}`;
  const nickname = `验收${label}${RUN_TAG}`;
  const reg = await http('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username, password: PASS_WORD, nickname },
  });
  if (codeOf(reg) !== 0 || !reg.json.data) {
    throw new Error(`注册用户 ${label} 失败：code=${codeOf(reg)} ${msgOf(reg) || reg.text}`);
  }
  const id = sid(reg.json.data.id);
  const login = await http('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account: username, password: PASS_WORD },
  });
  if (codeOf(login) !== 0 || !login.json.data) {
    throw new Error(`登录用户 ${label} 失败：code=${codeOf(login)} ${msgOf(login) || login.text}`);
  }
  trackRefresh(id, login.json.data.refreshToken);
  return {
    id,
    username,
    nickname,
    token: login.json.data.accessToken,
    refreshToken: login.json.data.refreshToken,
  };
}

/** 发布动态；失败的用例直接抛错，避免后续断言级联失败。 */
async function publish(user, body, why) {
  const res = await http('POST', '/posts', { token: user.token, xff: syntheticIp(), body });
  if (codeOf(res) !== 0 || !dataOf(res)) {
    throw new Error(`${why}失败：code=${codeOf(res)} ${msgOf(res) || res.text}`);
  }
  created.postIds.push(sid(dataOf(res).id));
  return dataOf(res);
}

async function openConversation(user, targetUserId) {
  const res = await http('POST', '/conversations', {
    token: user.token, xff: syntheticIp(), body: { targetUserId },
  });
  if (codeOf(res) !== 0 || !dataOf(res)) {
    throw new Error(`创建会话失败：code=${codeOf(res)} ${msgOf(res) || res.text}`);
  }
  if (!created.conversationIds.includes(sid(dataOf(res).id))) {
    created.conversationIds.push(sid(dataOf(res).id));
  }
  return dataOf(res);
}

async function sendMessage(user, conversationId, body) {
  const res = await http('POST', `/conversations/${conversationId}/messages`, {
    token: user.token, xff: syntheticIp(), body,
  });
  if (codeOf(res) === 0 && dataOf(res) && dataOf(res).id) {
    created.messageIds.push(sid(dataOf(res).id));
  }
  return res;
}

async function conversationList(user, extra = '') {
  const res = await http('GET', `/conversations?page=1&size=50${extra}`, {
    token: user.token, xff: syntheticIp(),
  });
  return listOf(res);
}

// ---------------------------------------------------------------- 主流程

async function main() {
  section('§0 共享实例基线');
  check('Redis 可用（ranking:* 基线已采集）', REDIS_OK, REDIS_OK ? undefined : 'redis-cli 不可用');
  check('MySQL 可用（emp_db/music_db 表数已采集）', HAS_MYSQL, HAS_MYSQL ? undefined : '未设置 MYSQL_PASSWORD');
  if (HAS_MYSQL) {
    const module16Tables = mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='newmusic' "
      + "AND TABLE_NAME IN ('t_conversation_member','t_user_profile','t_user_post','t_user_relation_score','t_fans_rank')");
    check('模块 16 表已建（5 张核心表）', Number(module16Tables) === 5, module16Tables);
  }

  // ============================================================ §1 用户
  section('§1 个人主页 / 资料编辑 / 隐私开关');
  const A = await registerAndLogin('A');
  const B = await registerAndLogin('B');
  const C = await registerAndLogin('C');
  const D = await registerAndLogin('D');
  const users = { A, B, C, D };
  check('四个验收用户已注册并登录',
    Boolean(A.token && B.token && C.token && D.token), Object.fromEntries(Object.entries(users).map(([k, v]) => [k, v.id])));

  if (HAS_MYSQL) {
    mysql(`UPDATE newmusic.t_user SET create_time=DATE_SUB(NOW(3), INTERVAL 30 DAY) `
      + `WHERE id IN (${idList(created.userIds)})`);
  }

  const profileAB = await http('GET', `/users/${A.id}/profile`, { token: B.token, xff: syntheticIp() });
  check('B 查看 A 的主页：self=false、内容可见',
    codeOf(profileAB) === 0 && dataOf(profileAB)
    && dataOf(profileAB).self === false && dataOf(profileAB).contentVisible === true,
    { code: codeOf(profileAB), self: dataOf(profileAB)?.self, visible: dataOf(profileAB)?.contentVisible });

  const bio = `模块16简介${RUN_TAG}`;
  const profileUpdate = await http('PUT', '/users/me/profile', {
    token: A.token, xff: syntheticIp(),
    body: { bio, region: '上海', tags: ['摇滚', '民谣'], gender: 1 },
  });
  check('资料编辑返回新简介', codeOf(profileUpdate) === 0
    && dataOf(profileUpdate)?.bio === bio, msgOf(profileUpdate));
  if (HAS_MYSQL) {
    const dbBio = mysqlScalar(`SELECT bio FROM newmusic.t_user_profile WHERE user_id=${q(A.id)}`);
    check('资料已落库（t_user_profile.bio）', dbBio === bio, dbBio);
  }

  const privacy = await http('GET', '/users/me/privacy', { token: A.token, xff: syntheticIp() });
  check('隐私设置包含 intimacyScore / fansRank 开关',
    codeOf(privacy) === 0 && dataOf(privacy) && 'intimacyScore' in dataOf(privacy)
    && 'fansRank' in dataOf(privacy), dataOf(privacy));

  const privacySet = await http('PUT', '/users/me/privacy', {
    token: A.token, xff: syntheticIp(), body: { intimacyScore: 0, fansRank: 0 },
  });
  check('更新隐私开关成功', codeOf(privacySet) === 0
    && Number(dataOf(privacySet)?.intimacyScore) === 0, msgOf(privacySet));

  // ============================================================ §2 动态
  section('§2 动态：发布 / 详情 / 互动');
  const topic = `M16${RUN_TAG}`;
  created.topicNames.push(topic);
  const p1 = await publish(A, {
    type: 1,
    content: `模块16 动态${RUN_TAG} #${topic}# @${B.nickname}`,
    topics: [topic],
    mentions: [{ userId: B.id, offset: 0, length: B.nickname.length + 1 }],
  }, 'A 发布动态');
  check('A 发布纯文本动态成功（已发布）', sid(p1.id) && p1.status === 1, { id: p1.id, status: p1.status });
  check('动态带出 @ 提及渲染信息', Array.isArray(p1.mentionedUsers)
    && p1.mentionedUsers.some((m) => sid(m.userId) === B.id), p1.mentionedUsers);

  const hideProfile = await http('PUT', '/users/me/profile', {
    token: A.token, xff: syntheticIp(), body: { profileVisibility: 2 },
  });
  check('A 设置主页仅自己可见', codeOf(hideProfile) === 0
    && Number(dataOf(hideProfile)?.privacy?.profile) === 2, msgOf(hideProfile));

  const hiddenProfile = await http('GET', `/users/${A.id}/profile`, {
    token: B.token, xff: syntheticIp(),
  });
  check('隐藏主页后 B 看到 contentVisible=false',
    codeOf(hiddenProfile) === 0 && dataOf(hiddenProfile)?.contentVisible === false
    && Boolean(dataOf(hiddenProfile)?.visibilityHint), dataOf(hiddenProfile)?.visibilityHint);

  const hiddenPosts = await http('GET', `/users/${A.id}/posts?page=1&size=10`, {
    token: B.token, xff: syntheticIp(),
  });
  check('隐藏主页后动态列表为空（后端拦截，非仅 UI）',
    codeOf(hiddenPosts) === 0 && listOf(hiddenPosts).length === 0,
    { code: codeOf(hiddenPosts), size: listOf(hiddenPosts).length });

  const hiddenStats = await http('GET', `/users/${A.id}/stats`, {
    token: B.token, xff: syntheticIp(),
  });
  const hiddenStatsData = dataOf(hiddenStats);
  check('隐藏主页后统计项全部不可见',
    codeOf(hiddenStats) === 0 && hiddenStatsData
    && hiddenStatsData.playDurationVisible === false && hiddenStatsData.weekCountVisible === false
    && hiddenStatsData.topArtistVisible === false && hiddenStatsData.genreVisible === false
    && hiddenStatsData.listenDaysVisible === false, hiddenStatsData);

  const hiddenAbout = await http('GET', `/users/${A.id}/about`, {
    token: B.token, xff: syntheticIp(),
  });
  check('隐藏主页后「关于我」为空',
    codeOf(hiddenAbout) === 0 && dataOf(hiddenAbout)
    && Object.keys(dataOf(hiddenAbout)).length === 0, dataOf(hiddenAbout));

  const hiddenRecent = await http('GET', `/users/${A.id}/recent-listening`, {
    token: B.token, xff: syntheticIp(),
  });
  check('隐藏主页后最近常听为空',
    codeOf(hiddenRecent) === 0 && listOf(hiddenRecent).length === 0);

  const restoreProfile = await http('PUT', '/users/me/profile', {
    token: A.token, xff: syntheticIp(), body: { profileVisibility: 0 },
  });
  check('恢复主页公开', codeOf(restoreProfile) === 0
    && Number(dataOf(restoreProfile)?.privacy?.profile) === 0, msgOf(restoreProfile));

  const detailB = await http('GET', `/posts/${p1.id}`, { token: B.token, xff: syntheticIp() });
  check('B 查看 A 的动态详情', codeOf(detailB) === 0 && sid(dataOf(detailB)?.id) === sid(p1.id));
  const viewAfter = Number(dataOf(detailB)?.viewCount);
  check('详情浏览会增加浏览数', viewAfter >= 1, viewAfter);
  if (REDIS_OK) {
    const seen = redis('hget', `ml:post:seen:${B.id}`, String(p1.id));
    check('曝光计数写入 ml:post:seen Hash', Number(seen || 0) >= 1, seen);
  } else {
    skip('曝光计数写入 ml:post:seen Hash', 'Redis 不可用');
  }

  const like = await http('POST', `/posts/${p1.id}/like`, { token: B.token, xff: syntheticIp() });
  check('B 点赞 A 的动态', codeOf(like) === 0 && dataOf(like)?.liked === true
    && Number(dataOf(like)?.likeCount) >= 1, { code: codeOf(like), liked: dataOf(like)?.liked });
  const comment = await http('POST', `/posts/${p1.id}/comments`, {
    token: B.token, xff: syntheticIp(), body: { content: `评论${RUN_TAG}` },
  });
  check('B 评论 A 的动态', codeOf(comment) === 0 && dataOf(comment)?.id
    && Number(dataOf(comment)?.status) === 1, { code: codeOf(comment), status: dataOf(comment)?.status });
  const collect = await http('POST', `/posts/${p1.id}/collect`, { token: B.token, xff: syntheticIp() });
  check('B 收藏 A 的动态', codeOf(collect) === 0 && dataOf(collect)?.collected === true);
  const repost = await http('POST', `/posts/${p1.id}/repost`, {
    token: B.token, xff: syntheticIp(), body: { content: `转发${RUN_TAG}` },
  });
  check('B 转发 A 的动态（只保留一层引用）',
    codeOf(repost) === 0 && sid(dataOf(repost)?.repostPostId) === sid(p1.id),
    { code: codeOf(repost), repostPostId: dataOf(repost)?.repostPostId });
  if (dataOf(repost)?.id) created.postIds.push(sid(dataOf(repost).id));

  const p2 = await publish(A, { type: 1, content: `第二条${RUN_TAG}` }, 'A 发布第二条动态');
  const p3 = await publish(A, { type: 1, content: `第三条${RUN_TAG}` }, 'A 发布第三条动态');
  const pb = await publish(B, { type: 1, content: `B的动态${RUN_TAG}` }, 'B 发布动态');
  check('多条动态发布成功', Boolean(p2.id && p3.id && pb.id));

  // ============================================================ §3 @ 提及
  section('§3 @ 提及与通知');
  if (HAS_MYSQL) {
    const mentionRows = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_post_mention WHERE source_id=${q(p1.id)} `
      + `AND mentioned_user_id=${q(B.id)}`);
    check('@ 关系落库（t_post_mention）', mentionRows === 1, mentionRows);
    const mentionNotice = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification `
      + `WHERE user_id=${q(B.id)} AND notify_type=6 AND from_user_id=${q(A.id)}`);
    check('被 @ 的用户收到提及通知', mentionNotice >= 1, mentionNotice);
  } else {
    skip('@ 关系落库与通知', '未设置 MYSQL_PASSWORD');
  }
  const mentionSearch = await http('GET', `/users/search?keyword=${encodeURIComponent(C.nickname)}`, {
    token: B.token, xff: syntheticIp(),
  });
  check('@ 用户搜索返回目标用户', codeOf(mentionSearch) === 0
    && listOf(mentionSearch).some((u) => sid(u.id) === C.id), listOf(mentionSearch).map((u) => u.id));

  // ============================================================ §4 Feed
  section('§4 关注流与发现流');
  const bFollowA = await http('POST', '/follows', {
    token: B.token, xff: syntheticIp(), body: { targetUserId: A.id },
  });
  check('B 关注 A', codeOf(bFollowA) === 0, msgOf(bFollowA));
  const aFollowB = await http('POST', '/follows', {
    token: A.token, xff: syntheticIp(), body: { targetUserId: B.id },
  });
  check('A 回关 B（互关）', codeOf(aFollowB) === 0 && dataOf(aFollowB)?.mutual === true,
    dataOf(aFollowB));

  // 关注之后发布的动态才会实时写进粉丝收件箱（关注前的历史动态靠首次读取回填）
  const pFeed = await publish(A, { type: 1, content: `关注后动态${RUN_TAG}` }, 'A 发布关注后动态');
  if (REDIS_OK) {
    const inboxKey = `ml:feed:inbox:${B.id}`;
    const score = redis('zscore', inboxKey, String(pFeed.id));
    check('关注流 push 收件箱写入 ZSet（粉丝数低于阈值）', score !== '', score);
    check('收件箱键带 30 天 TTL', Number(redis('ttl', inboxKey)) > 0, redis('ttl', inboxKey));
  } else {
    skip('关注流 push 收件箱', 'Redis 不可用');
  }

  const feed1 = await http('GET', '/feed/following?limit=1', { token: B.token, xff: syntheticIp() });
  const feed1List = listOf(feed1);
  check('关注流第一页返回 1 条', codeOf(feed1) === 0 && feed1List.length === 1,
    { code: codeOf(feed1), len: feed1List.length });
  check('关注流游标分页标记 hasMore', dataOf(feed1)?.hasMore === true
    && typeof dataOf(feed1)?.nextCursor === 'string', dataOf(feed1)?.nextCursor);
  const feed2 = await http('GET', `/feed/following?limit=10&cursor=${encodeURIComponent(dataOf(feed1)?.nextCursor || '')}`, {
    token: B.token, xff: syntheticIp(),
  });
  const feed2List = listOf(feed2);
  check('关注流第二页返回且不与第一页重复',
    codeOf(feed2) === 0 && feed2List.length >= 1
    && !feed2List.some((p) => sid(p.id) === sid(feed1List[0]?.id)),
    { len: feed2List.length, first: sid(feed1List[0]?.id) });

  // 发现流：C 发布一条有互动的动态
  const dcPost = await publish(C, { type: 1, content: `发现流候选${RUN_TAG}` }, 'C 发布发现流动态');
  await http('GET', `/posts/${dcPost.id}`, { token: D.token, xff: syntheticIp() });
  await http('POST', `/posts/${dcPost.id}/like`, { token: D.token, xff: syntheticIp() });

  let discovered = false;
  let discoveredSeen = '';
  let scanCursor = '';
  for (let i = 0; i < 12 && !discovered; i += 1) {
    const path = `/feed/discover?limit=30${scanCursor ? `&cursor=${encodeURIComponent(scanCursor)}` : ''}`;
    const res = await http('GET', path, { token: B.token, xff: syntheticIp() });
    const list = listOf(res);
    if (list.some((p) => sid(p.id) === sid(dcPost.id))) {
      discovered = true;
      if (REDIS_OK) discoveredSeen = redis('hget', `ml:post:seen:${B.id}`, String(dcPost.id));
    }
    if (!dataOf(res)?.hasMore || !dataOf(res)?.nextCursor) break;
    scanCursor = dataOf(res).nextCursor;
  }
  check('发现流能返回未关注作者的合格动态', discovered, { postId: dcPost.id });
  check('发现流已曝光动态写入曝光计数', discovered && Number(discoveredSeen || 0) >= 1, discoveredSeen);

  if (REDIS_OK && discovered) {
    redis('hset', `ml:post:seen:${B.id}`, String(dcPost.id), '3');
    let hidden = true;
    let cur = '';
    for (let i = 0; i < 12; i += 1) {
      const res = await http('GET', `/feed/discover?limit=30${cur ? `&cursor=${encodeURIComponent(cur)}` : ''}`, {
        token: B.token, xff: syntheticIp(),
      });
      if (listOf(res).some((p) => sid(p.id) === sid(dcPost.id))) {
        hidden = false;
        break;
      }
      if (!dataOf(res)?.hasMore || !dataOf(res)?.nextCursor) break;
      cur = dataOf(res).nextCursor;
    }
    check('曝光达到 3 次后不再出现在发现流', hidden);
    redis('hdel', `ml:post:seen:${B.id}`, String(dcPost.id));

    const dislike = await http('POST', `/posts/${dcPost.id}/dislike`, { token: B.token, xff: syntheticIp() });
    check('不感兴趣写入 Redis 集合', codeOf(dislike) === 0
      && redis('sismember', `ml:post:dislike:${B.id}`, String(dcPost.id)) === '1');
    let dislikedHidden = true;
    let dcursor = '';
    for (let i = 0; i < 12; i += 1) {
      const res = await http('GET', `/feed/discover?limit=30${dcursor ? `&cursor=${encodeURIComponent(dcursor)}` : ''}`, {
        token: B.token, xff: syntheticIp(),
      });
      if (listOf(res).some((p) => sid(p.id) === sid(dcPost.id))) {
        dislikedHidden = false;
        break;
      }
      if (!dataOf(res)?.hasMore || !dataOf(res)?.nextCursor) break;
      dcursor = dataOf(res).nextCursor;
    }
    check('不感兴趣的动态 30 天内不再推荐', dislikedHidden);
    redis('srem', `ml:post:dislike:${B.id}`, String(dcPost.id));
  } else {
    skip('曝光 3 次淘汰 / 不感兴趣过滤', REDIS_OK ? '未找到候选动态' : 'Redis 不可用');
  }

  // ============================================================ §5 推荐关注
  section('§5 关系链推荐');
  const cFollowB = await http('POST', '/follows', {
    token: C.token, xff: syntheticIp(), body: { targetUserId: B.id },
  });
  check('C 关注 B（制造「关注了你」候选）', codeOf(cFollowB) === 0, msgOf(cFollowB));
  const recommend = await http('GET', '/users/recommend?limit=20', { token: B.token, xff: syntheticIp() });
  const recC = listOf(recommend).find((r) => sid(r.user?.id) === C.id);
  check('推荐列表包含关注了我但未回关的 C', codeOf(recommend) === 0 && Boolean(recC), msgOf(recommend));
  check('推荐理由与来源正确', recC?.reason === '关注了你' && recC?.source === 'mutual'
    && recC?.followBack === true, recC && { reason: recC.reason, source: recC.source });

  const ignore = await http('POST', `/users/recommend/${C.id}/ignore`, { token: B.token, xff: syntheticIp() });
  const recommend2 = await http('GET', '/users/recommend?limit=20', { token: B.token, xff: syntheticIp() });
  check('忽略推荐后 30 天内不再出现', codeOf(ignore) === 0
    && !listOf(recommend2).some((r) => sid(r.user?.id) === C.id));

  // ============================================================ §6 私信
  section('§6 私信增强：会话 / 搜索 / 引用 / 转发');
  const convAB = await openConversation(A, B.id);
  const convAC = await openConversation(A, C.id);
  const convAD = await openConversation(A, D.id);
  check('创建单聊会话（3 个）', convAB.type === 1 && convAC.type === 1 && convAD.type === 1);

  const m1 = await sendMessage(A, convAB.id, { content: `第一条私信${RUN_TAG}`, msgType: 'TEXT' });
  check('A 通过会话接口发送私信', codeOf(m1) === 0 && dataOf(m1)?.mine === true,
    { code: codeOf(m1), mine: dataOf(m1)?.mine });

  const m2 = await sendMessage(B, convAB.id, {
    content: `引用回复${RUN_TAG}`, msgType: 'TEXT', quoteMsgId: dataOf(m1)?.id,
  });
  check('单聊引用消息带出被引用内容', codeOf(m2) === 0
    && sid(dataOf(m2)?.quote?.messageId) === sid(dataOf(m1)?.id)
    && dataOf(m2)?.quote?.content === `第一条私信${RUN_TAG}`,
    { code: codeOf(m2), quote: dataOf(m2)?.quote });

  const listB = await conversationList(B);
  const abForB = listB.find((c) => sid(c.id) === sid(convAB.id));
  check('B 的会话列表包含与 A 的会话', Boolean(abForB) && abForB.type === 1, abForB && abForB.id);
  check('B 会话有未读数（引用回复不算自己的）', Number(abForB?.unread) >= 1, abForB?.unread);

  const readB = await http('PUT', `/conversations/${convAB.id}/read`, { token: B.token, xff: syntheticIp() });
  const listB2 = await conversationList(B);
  check('B 标记已读后该会话未读清零', codeOf(readB) === 0
    && Number(listB2.find((c) => sid(c.id) === sid(convAB.id))?.unread) === 0);

  const searchWord = `M16SEARCH${RUN_TAG}`;
  await sendMessage(A, convAB.id, { content: searchWord });
  const search = await http('GET', `/conversations/search?keyword=${encodeURIComponent(searchWord)}`, {
    token: B.token, xff: syntheticIp(),
  });
  check('会话搜索命中消息内容', codeOf(search) === 0
    && (dataOf(search)?.messages || []).some((m) => sid(m.conversationId) === sid(convAB.id)
      && m.content === searchWord), dataOf(search)?.messages);

  const mute = await http('PUT', `/conversations/${convAB.id}/mute`, {
    token: B.token, xff: syntheticIp(), body: { enabled: true },
  });
  check('免打扰开关生效', codeOf(mute) === 0 && dataOf(mute)?.muted === true);
  const archive = await http('PUT', `/conversations/${convAB.id}/archive`, {
    token: B.token, xff: syntheticIp(), body: { enabled: true },
  });
  check('归档会话后从主列表消失', codeOf(archive) === 0
    && !(await conversationList(B)).some((c) => sid(c.id) === sid(convAB.id)));
  const archivedList = await conversationList(B, '&archived=true');
  check('归档列表可查到该会话', archivedList.some((c) => sid(c.id) === sid(convAB.id)));
  const unarchive = await http('PUT', `/conversations/${convAB.id}/archive`, {
    token: B.token, xff: syntheticIp(), body: { enabled: false },
  });
  check('取消归档后回到主列表', codeOf(unarchive) === 0
    && (await conversationList(B)).some((c) => sid(c.id) === sid(convAB.id)));

  // 置顶上限：3 个单聊全部置顶后，再置顶群聊应失败
  const pinAB = await http('PUT', `/conversations/${convAB.id}/pin`, {
    token: A.token, xff: syntheticIp(), body: { enabled: true },
  });
  const pinAC = await http('PUT', `/conversations/${convAC.id}/pin`, {
    token: A.token, xff: syntheticIp(), body: { enabled: true },
  });
  const pinAD = await http('PUT', `/conversations/${convAD.id}/pin`, {
    token: A.token, xff: syntheticIp(), body: { enabled: true },
  });
  check('置顶 3 个单聊会话成功', codeOf(pinAB) === 0 && codeOf(pinAC) === 0 && codeOf(pinAD) === 0,
    [codeOf(pinAB), codeOf(pinAC), codeOf(pinAD)]);

  // 转发：B 发给 A 的消息，A 转发给 C，不能暴露原发送者 B
  const fwdSource = await sendMessage(B, convAB.id, { content: `待转发${RUN_TAG}` });
  const fwd = await http('POST', `/conversations/${convAC.id}/forward/${dataOf(fwdSource)?.id}`, {
    token: A.token, xff: syntheticIp(),
  });
  check('转发消息成功并标记 forwarded', codeOf(fwd) === 0 && dataOf(fwd)?.forwarded === true,
    { code: codeOf(fwd), forwarded: dataOf(fwd)?.forwarded });
  check('转发后发送者为转发人而非原作者', sid(dataOf(fwd)?.senderId) === A.id
    && sid(dataOf(fwd)?.senderId) !== B.id, dataOf(fwd)?.senderId);
  const cMessages = listOf(await http('GET', `/conversations/${convAC.id}/messages?limit=50`, {
    token: C.token, xff: syntheticIp(),
  }));
  check('接收方看到的内容来自转发人', cMessages.some((m) => m.content === `待转发${RUN_TAG}`
    && sid(m.senderId) === A.id && m.forwarded === true));

  // 撤回消息不可转发
  const rev = await sendMessage(B, convAB.id, { content: `撤回${RUN_TAG}` });
  const revoke = await http('POST', `/messages/${dataOf(rev)?.id}/revoke`, { token: B.token, xff: syntheticIp() });
  const forwardRevoked = await http('POST', `/conversations/${convAC.id}/forward/${dataOf(rev)?.id}`, {
    token: A.token, xff: syntheticIp(),
  });
  check('已撤回消息不能转发', codeOf(revoke) === 0 && codeOf(forwardRevoked) === 50006,
    { revoke: codeOf(revoke), forward: codeOf(forwardRevoked) });

  // 营销限制：同一内容发到 11 个会话才触发。这里只校验隐私拦截与正常发送。
  const dPrivacy = await http('PUT', '/privacy-settings', {
    token: D.token, xff: syntheticIp(), body: { messagePolicy: 2 },
  });
  check('D 关闭私信（拒绝所有人）', codeOf(dPrivacy) === 0, msgOf(dPrivacy));
  const blockedCreate = await http('POST', '/conversations', {
    token: A.token, xff: syntheticIp(), body: { targetUserId: D.id },
  });
  const blockedSend = await sendMessage(A, convAD.id, { content: `应被拒${RUN_TAG}` });
  check('隐私策略拒绝建立新会话', codeOf(blockedCreate) !== 0, msgOf(blockedCreate));
  check('隐私策略拒绝向该会话发送消息', codeOf(blockedSend) !== 0, msgOf(blockedSend));

  // ============================================================ §7 群聊
  section('§7 群聊');
  const group = await http('POST', '/conversations/group', {
    token: A.token, xff: syntheticIp(),
    body: { name: `验收群${RUN_TAG}`, memberIds: [B.id, C.id] },
  });
  const groupId = sid(dataOf(group)?.id);
  if (groupId) created.conversationIds.push(groupId);
  check('创建群聊成功（3 人，创建者为群主）', codeOf(group) === 0
    && dataOf(group)?.type === 2 && Number(dataOf(group)?.memberCount) === 3
    && sid(dataOf(group)?.ownerId) === A.id, { code: codeOf(group), data: dataOf(group) });

  const pinGroup = await http('PUT', `/conversations/${groupId}/pin`, {
    token: A.token, xff: syntheticIp(), body: { enabled: true },
  });
  check('第 4 个置顶被拒绝（上限 3）', codeOf(pinGroup) === 50036, msgOf(pinGroup));

  const members = await http('GET', `/conversations/${groupId}/members`, { token: B.token, xff: syntheticIp() });
  const memberList = listOf(members);
  check('群成员列表返回 3 人且包含群主',
    codeOf(members) === 0 && memberList.length === 3
    && memberList.some((m) => sid(m.userId) === A.id && m.owner === true),
    memberList.map((m) => ({ id: m.userId, role: m.role })));

  const gm1 = await sendMessage(B, groupId, { content: `@${A.nickname} 群消息${RUN_TAG}` });
  check('群成员发送群消息成功', codeOf(gm1) === 0 && sid(dataOf(gm1)?.senderId) === B.id,
    msgOf(gm1));
  if (HAS_MYSQL) {
    const mentioned = mysqlScalar(`SELECT mentioned_user_ids FROM newmusic.t_private_message WHERE id=${q(dataOf(gm1)?.id)}`);
    check('群聊 @ 解析写入 mentioned_user_ids', String(mentioned).split(',').includes(A.id), mentioned);
    const groupMentionNotice = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_notification `
      + `WHERE user_id=${q(A.id)} AND notify_type=6 AND from_user_id=${q(B.id)}`);
    check('群聊被 @ 收到通知', groupMentionNotice >= 1, groupMentionNotice);
  } else {
    skip('群聊 @ 落库与通知', '未设置 MYSQL_PASSWORD');
  }

  const groupListA = await conversationList(A);
  const gForA = groupListA.find((c) => sid(c.id) === groupId);
  check('A 的会话列表包含群聊，且未读为 1', Boolean(gForA) && gForA.type === 2
    && Number(gForA.unread) === 1, { unread: gForA?.unread });
  const groupListB = await conversationList(B);
  check('B 自己发的群消息不产生未读',
    Number(groupListB.find((c) => sid(c.id) === groupId)?.unread) === 0);

  const groupRead = await http('PUT', `/conversations/${groupId}/read`, { token: A.token, xff: syntheticIp() });
  const groupListA2 = await conversationList(A);
  check('群聊标记已读后未读清零', codeOf(groupRead) === 0
    && Number(groupListA2.find((c) => sid(c.id) === groupId)?.unread) === 0);

  const totalUnread = await http('GET', '/conversations/unread-count', { token: A.token, xff: syntheticIp() });
  check('会话总未读数接口可用（含群聊）', codeOf(totalUnread) === 0
    && Number(dataOf(totalUnread)) >= 0, dataOf(totalUnread));

  const gQuote = await sendMessage(A, groupId, {
    content: `群聊引用${RUN_TAG}`, quoteMsgId: dataOf(gm1)?.id,
  });
  check('群聊引用消息带出原发送者昵称', codeOf(gQuote) === 0
    && sid(dataOf(gQuote)?.quote?.messageId) === sid(dataOf(gm1)?.id)
    && dataOf(gQuote)?.quote?.senderNickname === B.nickname,
    { code: codeOf(gQuote), quote: dataOf(gQuote)?.quote });

  const gMessages = listOf(await http('GET', `/conversations/${groupId}/messages?limit=50`, {
    token: C.token, xff: syntheticIp(),
  }));
  check('群聊消息列表带发送者昵称', gMessages.length >= 2
    && gMessages.every((m) => Boolean(m.senderNickname)), gMessages.length);

  const addD = await http('POST', `/conversations/${groupId}/members`, {
    token: A.token, xff: syntheticIp(), body: { userIds: [D.id] },
  });
  const membersAfterAdd = listOf(await http('GET', `/conversations/${groupId}/members`, {
    token: A.token, xff: syntheticIp(),
  }));
  check('邀请 D 入群后成员数为 4', codeOf(addD) === 0 && membersAfterAdd.length === 4, membersAfterAdd.length);
  check('D 能在会话列表看到群聊',
    (await conversationList(D)).some((c) => sid(c.id) === groupId));

  const removeD = await http('DELETE', `/conversations/${groupId}/members/${D.id}`, {
    token: A.token, xff: syntheticIp(),
  });
  check('群主移除 D 后 D 看不到群聊', codeOf(removeD) === 0
    && !(await conversationList(D)).some((c) => sid(c.id) === groupId));

  const removeByB = await http('DELETE', `/conversations/${groupId}/members/${C.id}`, {
    token: B.token, xff: syntheticIp(),
  });
  check('非群主移除成员被拒绝（50035）', codeOf(removeByB) === 50035, msgOf(removeByB));
  const dissolveByB = await http('DELETE', `/conversations/${groupId}`, { token: B.token, xff: syntheticIp() });
  check('非群主解散群聊被拒绝（50035）', codeOf(dissolveByB) === 50035, msgOf(dissolveByB));
  const quitByOwner = await http('DELETE', `/conversations/${groupId}/members/me`, {
    token: A.token, xff: syntheticIp(),
  });
  check('群主不能直接退群（50035）', codeOf(quitByOwner) === 50035, msgOf(quitByOwner));

  const quitC = await http('DELETE', `/conversations/${groupId}/members/me`, {
    token: C.token, xff: syntheticIp(),
  });
  const membersAfterQuit = listOf(await http('GET', `/conversations/${groupId}/members`, {
    token: A.token, xff: syntheticIp(),
  }));
  check('C 退群后成员数降为 2', codeOf(quitC) === 0 && membersAfterQuit.length === 2,
    { code: codeOf(quitC), len: membersAfterQuit.length });

  const fwdGroup = await http('POST', `/conversations/${groupId}/forward/${dataOf(fwdSource)?.id}`, {
    token: A.token, xff: syntheticIp(),
  });
  check('消息可转发到群聊', codeOf(fwdGroup) === 0 && dataOf(fwdGroup)?.forwarded === true,
    msgOf(fwdGroup));

  const dissolve = await http('DELETE', `/conversations/${groupId}`, { token: A.token, xff: syntheticIp() });
  check('群主解散群聊成功', codeOf(dissolve) === 0, msgOf(dissolve));
  check('解散后群聊从会话列表消失',
    !(await conversationList(A)).some((c) => sid(c.id) === groupId));
  if (HAS_MYSQL) {
    const deleted = mysqlScalar(`SELECT deleted FROM newmusic.t_conversation WHERE id=${q(groupId)}`);
    check('群聊逻辑删除已落库', Number(deleted) === 1, deleted);
  }

  // ============================================================ §8 亲密度 / 粉丝榜
  section('§8 亲密度与粉丝榜');
  const intimacy = await http('GET', `/users/${A.id}/intimacy`, { token: B.token, xff: syntheticIp() });
  const intimacyScore = Number(dataOf(intimacy)?.score);
  check('B 对 A 的亲密度已累计（消息+评论+点赞+互关）',
    codeOf(intimacy) === 0 && intimacyScore >= 14,
    { code: codeOf(intimacy), score: dataOf(intimacy)?.score, level: dataOf(intimacy)?.levelName });
  check('亲密度等级为「认识」（10~49）', dataOf(intimacy)?.level === 1
    && dataOf(intimacy)?.levelName === '认识', dataOf(intimacy)?.levelName);

  const hideIntimacy = await http('PUT', '/users/me/privacy', {
    token: A.token, xff: syntheticIp(), body: { intimacyScore: 1 },
  });
  const intimacyHidden = await http('GET', `/users/${A.id}/intimacy`, { token: B.token, xff: syntheticIp() });
  check('A 隐藏亲密度后 score 为空但等级仍可见',
    codeOf(hideIntimacy) === 0 && dataOf(intimacyHidden)?.score === null
    && dataOf(intimacyHidden)?.scoreVisible === false && dataOf(intimacyHidden)?.level === 1,
    { score: dataOf(intimacyHidden)?.score, visible: dataOf(intimacyHidden)?.scoreVisible });

  await http('POST', `/posts/${pb.id}/like`, { token: A.token, xff: syntheticIp() });

  const rankOff = await http('GET', `/users/${B.id}/fans-rank?type=1`, { token: A.token, xff: syntheticIp() });
  check('未开启榜单时 visible=false 且列表为空', codeOf(rankOff) === 0
    && dataOf(rankOff)?.visible === false && (dataOf(rankOff)?.list || []).length === 0,
    dataOf(rankOff));

  const enableRank = await http('PUT', '/users/me/privacy', {
    token: B.token, xff: syntheticIp(), body: { fansRank: 1 },
  });
  const rankOn = await http('GET', `/users/${B.id}/fans-rank?type=1`, { token: A.token, xff: syntheticIp() });
  const rankList = dataOf(rankOn)?.list || [];
  check('开启榜单后可见且 A 上榜', codeOf(rankOn) === 0
    && dataOf(rankOn)?.visible === true
    && rankList.some((item) => sid(item.userId) === A.id),
    { code: codeOf(rankOn), visible: dataOf(rankOn)?.visible, list: rankList.map((x) => x.userId) });
  check('榜单名次与徽章字段完整', rankList.length > 0
    && Number(rankList[0]?.rank) === 1 && typeof rankList[0]?.badge === 'boolean', rankList[0]);
  const rankInteraction = await http('GET', `/users/${B.id}/fans-rank?type=2`, {
    token: A.token, xff: syntheticIp(),
  });
  check('互动榜包含近 30 天互动用户',
    codeOf(rankInteraction) === 0
    && (dataOf(rankInteraction)?.list || []).some((item) => sid(item.userId) === A.id),
    dataOf(rankInteraction)?.list);
  const rankGift = await http('GET', `/users/${B.id}/fans-rank?type=3`, {
    token: A.token, xff: syntheticIp(),
  });
  check('礼物榜本期无数据源（空列表）', codeOf(rankGift) === 0
    && (dataOf(rankGift)?.list || []).length === 0);
  if (HAS_MYSQL) {
    const snapshots = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_fans_rank `
      + `WHERE user_id=${q(B.id)} AND rank_type=1 AND stat_date=CURDATE()`);
    check('粉丝榜每日快照已落库', snapshots >= 1, snapshots);
  }

  // ============================================================ §9 消息中心
  section('§9 消息中心五分类未读');
  const unread = await http('GET', '/messages/unread', { token: B.token, xff: syntheticIp() });
  check('五分类未读数接口返回完整分类', codeOf(unread) === 0
    && dataOf(unread)?.categories
    && ['interaction', 'follow', 'message'].every((k) => k in dataOf(unread).categories),
    dataOf(unread)?.categories);
  check('B 有互动类未读（点赞/评论/@）',
    Number(dataOf(unread)?.categories?.interaction) > 0, dataOf(unread)?.categories);

  const readInteraction = await http('POST', '/messages/read-all?category=interaction', {
    token: B.token, xff: syntheticIp(),
  });
  const unread2 = await http('GET', '/messages/unread', { token: B.token, xff: syntheticIp() });
  check('按分类清空互动未读', codeOf(readInteraction) === 0
    && Number(dataOf(unread2)?.categories?.interaction) === 0,
    { code: codeOf(readInteraction), interaction: dataOf(unread2)?.categories?.interaction });

  const interactions = await http('GET', '/messages/interactions?page=1&size=20', {
    token: B.token, xff: syntheticIp(),
  });
  check('互动消息列表可查询', codeOf(interactions) === 0 && Array.isArray(listOf(interactions)));
}

// ---------------------------------------------------------------- 清理

async function cleanup() {
  const ids = created.userIds;
  const users = idList(ids);
  if (HAS_MYSQL && ids.length) {
    try {
      mysql(`DELETE FROM newmusic.t_post_mention WHERE author_id IN (${users}) `
        + `OR mentioned_user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_post_like WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_post_collect WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_comment WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_post WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_feed_inbox WHERE user_id IN (${users}) OR author_id IN (${users})`);
      if (created.conversationIds.length) {
        const convs = idList(created.conversationIds);
        mysql(`DELETE FROM newmusic.t_private_message WHERE conversation_id IN (${convs})`);
        mysql(`DELETE FROM newmusic.t_conversation_member WHERE conversation_id IN (${convs})`);
      }
      mysql(`DELETE FROM newmusic.t_private_message WHERE sender_id IN (${users}) OR receiver_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_conversation_member WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_conversation WHERE user_a_id IN (${users}) OR user_b_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_notification_aggregate WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN (${users}) OR from_user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_notification_setting WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_privacy_setting WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_relation_score WHERE user_id IN (${users}) `
        + `OR target_user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_fans_rank WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_profile_log WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_profile WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_follow WHERE follower_id IN (${users}) OR following_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_blacklist WHERE user_id IN (${users}) OR blocked_user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user_role WHERE user_id IN (${users})`);
      mysql(`DELETE FROM newmusic.t_user WHERE id IN (${users})`);
      for (const name of created.topicNames) {
        mysql(`DELETE FROM newmusic.t_topic WHERE name=${q(name)}`);
      }
    } catch (e) {
      note(`MySQL 清理失败：${e.message}`);
    }
  }

  if (REDIS_OK && ids.length) {
    try {
      const keys = [];
      for (const id of ids) {
        keys.push(
          `ml:feed:inbox:${id}`, `ml:feed:outbox:${id}`,
          `ml:feed:newcount:${id}`, `ml:feed:lastread:${id}`,
          `ml:post:seen:${id}`, `ml:post:dislike:${id}`,
          `ml:relation:rec:ignore:${id}`, `ml:relation:score:${id}`,
          `ml:msg:spam:restrict:${id}`,
          `ml:social:msg:rate:${id}`, `ml:notify:unread:${id}`,
          `ml:auth:refresh:user:${id}`,
        );
      }
      for (const jti of created.userRefreshJtis) keys.push(`ml:auth:refresh:${jti}`);
      redisDel(...keys);
      for (const id of ids) {
        delByPattern(`ml:msg:spam:${id}:*`);
        delByPattern(`ml:relation:score:${id}:*`);
        delByPattern(`ml:relation:score:*:${id}`);
        delByPattern(`ml:post:rate:${id}:*`);
        delByPattern(`ml:social:message:*:${id}*`);
        delByPattern(`ml:social:message:${id}:*`);
      }
    } catch (e) {
      note(`Redis 清理失败：${e.message}`);
    }
  }
}

// ---------------------------------------------------------------- 完整性

function verifyIntegrity() {
  section('§10 共享实例完整性');
  if (REDIS_OK) {
    const after = listKeys('ranking:*').sort();
    check('Redis ranking:* 键与测试前完全一致',
      after.length === rankingBefore.length && after.every((k, i) => k === rankingBefore[i]),
      { before: rankingBefore.length, after: after.length });
  } else {
    skip('Redis ranking:* 完整性', 'Redis 不可用');
  }
  if (HAS_MYSQL) {
    const sharedAfter = mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES "
      + "WHERE TABLE_SCHEMA IN ('emp_db','music_db')");
    check('MySQL emp_db/music_db 表数未变化', sharedAfter === sharedTablesBefore,
      { before: sharedTablesBefore, after: sharedAfter });
    const userAfter = mysqlCount('SELECT COUNT(*) FROM newmusic.t_user');
    check('本轮注册的验收用户已清理（t_user 行数复原）', userAfter === userCountBefore,
      { before: userCountBefore, after: userAfter });
    const leftovers = mysqlCount(`SELECT COUNT(*) FROM newmusic.t_user_post WHERE user_id IN (${idList(created.userIds)})`);
    check('动态与关系数据已按精确 ID 清理', leftovers === 0, leftovers);
  } else {
    skip('MySQL 完整性', '未设置 MYSQL_PASSWORD');
  }
}

// ---------------------------------------------------------------- 入口

let crashed = null;
try {
  await main();
} catch (e) {
  crashed = e;
  fail += 1;
  failures.push(`脚本异常：${e.message}`);
  console.log(`\n\x1b[31m脚本异常\x1b[0m ${e.stack || e.message}`);
} finally {
  await cleanup();
  try {
    verifyIntegrity();
  } catch (e) {
    fail += 1;
    failures.push(`完整性检查异常：${e.message}`);
    console.log(`\n\x1b[31m完整性检查异常\x1b[0m ${e.stack || e.message}`);
  }
  console.log(`\n\x1b[1m汇总\x1b[0m 通过 ${pass} / 失败 ${fail} / 跳过 ${skipped}`);
  if (failures.length) {
    console.log('\x1b[31m失败项：\x1b[0m');
    for (const f of failures) console.log(`  - ${f}`);
  }
  if (skipNotes.length) {
    console.log('\x1b[90m跳过项：\x1b[0m');
    for (const s of skipNotes) console.log(`  - ${s}`);
  }
  if (crashed || fail > 0) process.exitCode = 1;
}
