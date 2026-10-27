#!/usr/bin/env node
/**
 * 模块 05 验收脚本 —— 对应 docs/系分/05-音乐房.md 的验收核对表。
 *
 * 运行：
 *   node scripts/verify-05.mjs
 *   API_BASE=http://localhost:8081/api/v1 MYSQL_PASSWORD=<本机密码> node scripts/verify-05.mjs
 *
 * 前置：应用已启动（8081）、Redis 6379 可连、newmusic 库已执行 sql/schema.sql 的
 *       §6 建表语句。依赖 Node 22+（全局 WebSocket），无需 npm 包。
 *
 * 共享环境约束（与 verify-04 / verify-06 相同）：
 * - Redis db0 里有另一个项目的 `ranking:*` 键：脚本只读写 `ml:` 前缀，
 *   开头/结尾各数一次 `ranking:*` 证明没碰；绝不用 FLUSHDB/FLUSHALL。
 * - MySQL 3306 是共享实例：所有 SQL 都带 `newmusic.` 限定符，只删本轮自己创建的房间数据。
 * - 口令只从环境变量 MYSQL_PASSWORD 读取，经 MYSQL_PWD 传给客户端，不写入文件。
 * - 每轮使用带时间戳的全新账号/房间，避免上次运行的禁言、踢出冷却、创建配额干扰断言。
 */

import { spawnSync } from 'node:child_process';

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const WS_BASE = process.env.WS_BASE
  || `${API_BASE.replace(/\/+$/, '').replace(/\/api\/v1$/, '')}/ws`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';

const SUFFIX = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const SHORT_SUFFIX = `${Date.now().toString(36).slice(-5)}${Math.random().toString(36).slice(2, 5)}`;
const PASSWORD = 'Probe@12345';

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
    console.log(`  \x1b[31m✗\x1b[0m ${name}${extra === undefined ? ''
      : ` → ${typeof extra === 'string' ? extra : JSON.stringify(extra)}`}`);
  }
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- HTTP

const syntheticIp = () => `10.${Math.floor(Math.random() * 250) + 1}.`
  + `${Math.floor(Math.random() * 250) + 1}.${Math.floor(Math.random() * 250) + 1}`;

async function call(method, path, { token, body, headers, xff } = {}) {
  const h = { Accept: 'application/json', ...(headers || {}) };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = `Bearer ${token}`;
  if (xff) h['X-Forwarded-For'] = xff;
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
  return { status: res.status, json, text };
}

async function waitForBoot(maxSeconds = 90) {
  const deadline = Date.now() + maxSeconds * 1000;
  process.stdout.write('等待应用启动');
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${API_BASE}/music/songs?pageSize=1`);
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

async function registerAndLogin(prefix) {
  // 用户名限制 4~20 位字母/数字/下划线：用短前缀 + 短后缀，避免超长
  const account = `${prefix}_${SHORT_SUFFIX}`;
  await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password: PASSWORD, nickname: account },
  });
  const res = await call('POST', '/auth/login', {
    xff: syntheticIp(),
    body: { account, password: PASSWORD },
  });
  if (res.json?.code !== 0) {
    throw new Error(`账号登录失败 ${account}: ${res.text}`);
  }
  return { account, token: res.json.data.accessToken, userId: String(res.json.data.user.id) };
}

// ---------------------------------------------------------------- Redis / MySQL

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

const rDel = (...keys) => (keys.length ? redis('del', ...keys) : '0');

const listKeys = (pattern) => {
  const out = redis('keys', pattern);
  return out === '' ? [] : out.replace(/\r/g, '').split('\n').map((s) => s.trim()).filter(Boolean);
};

const rankingKeys = () => listKeys('ranking:*');

function mysql(sql) {
  const r = spawnSync(MYSQL_CLI, [
    '-u', MYSQL_USER, '-h', '127.0.0.1', '-P', process.env.MYSQL_PORT || '3306',
    '-D', 'newmusic', '-N', '-B', '--default-character-set=utf8mb4',
  ], { encoding: 'utf8', input: sql, env: { ...process.env, MYSQL_PWD } });
  if (r.error) throw new Error(`无法执行 mysql 客户端（${MYSQL_CLI}）：${r.error.message}`);
  if (r.status !== 0) throw new Error(`mysql 退出码 ${r.status}：${(r.stderr || '').trim()}`);
  return (r.stdout || '').trim();
}

// ---------------------------------------------------------------- WebSocket（全局实现）

function connectWs(token, deviceId) {
  const url = new URL(WS_BASE);
  url.searchParams.set('token', token);
  url.searchParams.set('deviceId', deviceId);
  const socket = new WebSocket(url.toString());
  const inbox = [];
  const waiters = [];
  const state = { close: null, closed: false };

  const settleOnClose = () => {
    state.closed = true;
    while (waiters.length) {
      const waiter = waiters.shift();
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`连接已关闭（code=${state.close?.code}）`));
    }
  };

  socket.addEventListener('message', (event) => {
    let message;
    try {
      message = JSON.parse(String(event.data));
    } catch {
      return;
    }
    for (let i = 0; i < waiters.length; i += 1) {
      if (waiters[i].predicate(message)) {
        const waiter = waiters.splice(i, 1)[0];
        clearTimeout(waiter.timer);
        waiter.resolve(message);
        return;
      }
    }
    inbox.push(message);
    if (inbox.length > 500) inbox.shift();
  });
  socket.addEventListener('close', (event) => {
    state.close = { code: event.code, reason: event.reason };
    settleOnClose();
  });

  const opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', () => resolve());
    socket.addEventListener('error', () => reject(new Error('WS 连接失败')));
  });

  const client = {
    socket,
    get closeInfo() {
      return state.close;
    },
    get closed() {
      return state.closed;
    },
    async ready() {
      await opened;
      // 等 connected 帧，确保服务端会话已注册
      await client.wait((m) => m.type === 'connected', 5000).catch(() => null);
      return client;
    },
    send(type, data, clientMsgId) {
      socket.send(JSON.stringify({ type, clientMsgId, data: { ...(data || {}), clientMsgId }, ts: Date.now() }));
      return clientMsgId;
    },
    wait(predicate, timeout = 6000) {
      const existing = inbox.findIndex(predicate);
      if (existing >= 0) {
        return Promise.resolve(inbox.splice(existing, 1)[0]);
      }
      if (state.closed) {
        return Promise.reject(new Error(`连接已关闭（code=${state.close?.code}）`));
      }
      return new Promise((resolve, reject) => {
        const waiter = { predicate, resolve, reject, timer: null };
        waiter.timer = setTimeout(() => {
          const index = waiters.indexOf(waiter);
          if (index >= 0) waiters.splice(index, 1);
          reject(new Error('等待 WS 帧超时'));
        }, timeout);
        waiters.push(waiter);
      });
    },
    close() {
      try {
        socket.close(1000, 'test done');
      } catch {
        /* ignore */
      }
    },
  };
  return client;
}

let messageSeq = 0;
const newMsgId = () => `t-${Date.now().toString(36)}-${(messageSeq += 1).toString(36)}`;

async function wsJoin(client, roomId, password, timeout = 8000) {
  const clientMsgId = newMsgId();
  client.send('room.join', { roomId: String(roomId), password }, clientMsgId);
  return client.wait((m) => (m.type === 'room.join.ack' && m.clientMsgId === clientMsgId)
    || (m.type === 'error' && m.data?.context?.clientMsgId === clientMsgId), timeout);
}

async function wsControl(client, type, payload, timeout = 8000) {
  const clientMsgId = newMsgId();
  client.send(type, payload, clientMsgId);
  return client.wait((m) => (m.type === `${type}.ack` && m.clientMsgId === clientMsgId)
    || (m.type === 'error' && m.data?.context?.clientMsgId === clientMsgId), timeout);
}

async function wsChat(client, content, timeout = 5000) {
  const clientMsgId = newMsgId();
  client.send('room.chat', { content }, clientMsgId);
  return client.wait((m) => (m.type === 'room.chat.ack' && m.clientMsgId === clientMsgId)
    || (m.type === 'error' && m.data?.context?.clientMsgId === clientMsgId)
    || (m.type === 'room.chat.rejected' && m.clientMsgId === clientMsgId), timeout);
}

/**
 * 控播的 ack 先于 DB 同步下发（实时状态优先），队列落库是随后完成的。
 * 断言队列状态时轮询几百毫秒，避免把正常的最终一致性当成失败。
 */
/** 队列查询只返回待审/已通过；已播放项会从列表消失，因此用「消失」判定消费完成。 */
async function pollQueueAbsent(roomId, token, queueId, timeout = 3000) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    const res = await call('GET', `/room/rooms/${roomId}/queue`, { token });
    last = res.json?.data?.find((q) => q.id === queueId) ?? null;
    if (last === null) {
      return null;
    }
    await sleep(150);
  }
  return last;
}

// =================================================================

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪。');
    process.exit(2);
  }

  const rankingBefore = rankingKeys();
  const createdRooms = [];
  const users = [];
  const clients = [];

  const owner = await registerAndLogin('own');
  const member = await registerAndLogin('mem');
  const outsider = await registerAndLogin('gst');
  users.push(owner, member, outsider);

  const created = async (name, body = {}) => {
    const res = await call('POST', '/room/rooms', {
      token: owner.token,
      body: { name, micCount: 6, maxMembers: 50, ...body },
    });
    if (res.json?.code === 0 && res.json.data?.id) createdRooms.push(String(res.json.data.id));
    return res;
  };

  try {
    // ---------------------------------------------------------------- 01 创建 / 列表 / 详情
    section('§1 创建、列表与详情（FR-14-001 / FR-14-011）');

    const publicName = `验收公开房${SUFFIX}`;
    const publicRes = await created(publicName, { notice: '欢迎来验收' });
    const publicRoom = publicRes.json?.data;
    check('创建公开房成功', publicRes.json?.code === 0, publicRes.json);
    check('返回 6 位房间号', /^\d{6}$/.test(String(publicRoom?.roomNo ?? '')), publicRoom?.roomNo);
    check('创建者角色为房主（myRole=2）', publicRoom?.myRole === 2, publicRoom?.myRole);
    check('按 micCount 初始化 6 个麦位', publicRoom?.mics?.length === 6, publicRoom?.mics?.length);
    check('房主自动占用 1 号麦', publicRoom?.mics?.find((m) => m.micPos === 1)?.userId === owner.userId,
      publicRoom?.mics?.find((m) => m.micPos === 1));
    check('房主成员记录 role=2',
      publicRoom?.members?.find((m) => m.userId === owner.userId)?.role === 2,
      publicRoom?.members);

    const listRes = await call('GET', `/room/rooms?tab=recommend&pageSize=50&keyword=${encodeURIComponent(publicName)}`, {
      token: member.token,
    });
    check('推荐列表能查到公开房',
      listRes.json?.code === 0 && listRes.json.data.list.some((r) => r.id === publicRoom.id),
      listRes.json?.code);

    const detailRes = await call('GET', `/room/rooms/${publicRoom.roomNo}`, { token: member.token });
    check('按房间号查询详情成功', detailRes.json?.code === 0, detailRes.json);
    check('公开房 passwordRequired=false', detailRes.json?.data?.passwordRequired === false);

    const privateName = `验收私密房${SUFFIX}`;
    const privateRes = await created(privateName, { roomType: 2 });
    const privateRoom = privateRes.json?.data;
    check('创建私密房成功', privateRes.json?.code === 0, privateRes.json);

    const memberList = await call('GET', '/room/rooms?tab=recommend&pageSize=50', { token: member.token });
    check('私密房不出现在他人的列表中',
      !memberList.json.data.list.some((r) => r.id === privateRoom.id));

    const pwdName = `验收密码房${SUFFIX}`;
    const pwdRes = await created(pwdName, { roomType: 3, password: 'Room@1234' });
    const pwdRoom = pwdRes.json?.data;
    check('创建密码房成功', pwdRes.json?.code === 0, pwdRes.json);

    const pwdDetail = await call('GET', `/room/rooms/${pwdRoom.roomNo}`, { token: member.token });
    check('密码房 passwordRequired=true', pwdDetail.json?.data?.passwordRequired === true);

    // 创建接口同时有「3 次/小时」的限流，会把每日配额挡在前面。
    // 清掉本用户的限流窗口（只删 ml:limit:room:user:<自己>），才能验到 §14 的每日上限。
    rDel(...listKeys(`ml:limit:room:user:${owner.userId}:*`));
    const fourth = await created(`验收超限房${SUFFIX}`);
    check('每日创建 3 个房间后第 4 次被拒绝（40012）',
      fourth.json?.code === 40012, fourth.json?.code);

    // ---------------------------------------------------------------- 02 密码与加入
    section('§2 加入校验与密码房（FR-14-002 / BR-14-020~023）');

    const noPwd = await call('POST', `/room/rooms/${pwdRoom.id}/join`, { token: member.token, body: {} });
    check('密码房不带密码加入被拒绝（40003）', noPwd.json?.code === 40003, noPwd.json?.code);

    for (let i = 1; i <= 3; i += 1) {
      const wrong = await call('POST', `/room/rooms/${pwdRoom.id}/join`, {
        token: member.token,
        body: { password: 'wrong-pass' },
      });
      check(`密码错误第 ${i} 次被拒绝`, wrong.json?.code === 40003 || wrong.json?.code === 40019,
        wrong.json?.code);
    }
    const locked = await call('POST', `/room/rooms/${pwdRoom.id}/join`, {
      token: member.token,
      body: { password: 'Room@1234' },
    });
    check('连续 3 次失败后正确密码也被锁定（40019）', locked.json?.code === 40019, locked.json?.code);

    const guestNoPwd = await call('POST', `/room/rooms/${pwdRoom.id}/join`, {
      token: outsider.token, body: {},
    });
    check('验证锁定只影响单个用户：他人仍可尝试（40003）',
      guestNoPwd.json?.code === 40003, guestNoPwd.json?.code);

    const guestOk = await call('POST', `/room/rooms/${pwdRoom.id}/join`, {
      token: outsider.token, body: { password: 'Room@1234' },
    });
    check('正确密码加入成功', guestOk.json?.code === 0, guestOk.json);

    const guestWs = await connectWs(outsider.token, `pwd-${SUFFIX}`).ready();
    clients.push(guestWs);
    const guestJoinAck = await wsJoin(guestWs, pwdRoom.id);
    check('HTTP 验证过密码后，WS 入房无需重复带密码',
      guestJoinAck.type === 'room.join.ack', guestJoinAck);
    guestWs.close();

    // ---------------------------------------------------------------- 03 WS 入房与控播
    section('§3 WS 入房与控播（FR-14-003 / FR-14-005）');

    const ownerWs = await connectWs(owner.token, `owner-${SUFFIX}`).ready();
    const memberWs = await connectWs(member.token, `member-${SUFFIX}`).ready();
    clients.push(ownerWs, memberWs);

    const ownerAck = await wsJoin(ownerWs, publicRoom.id);
    check('房主 WS 入房成功', ownerAck.type === 'room.join.ack', ownerAck);
    check('房主快照带房间详情与权限', ownerAck.data?.room?.id === publicRoom.id
      && Array.isArray(ownerAck.data?.myPermissions)
      && ownerAck.data.myPermissions.includes('room.control'), ownerAck.data?.myPermissions);

    const memberAck = await wsJoin(memberWs, publicRoom.id);
    check('成员 WS 入房成功', memberAck.type === 'room.join.ack', memberAck);
    check('成员角色为普通成员（myRole=0）', memberAck.data?.myRole === 0, memberAck.data?.myRole);
    check('入房快照包含两名成员', memberAck.data?.members?.length === 2, memberAck.data?.members?.length);

    const again = await wsJoin(memberWs, publicRoom.id);
    check('同一连接重复 join 幂等（仍返回 ack）', again.type === 'room.join.ack', again);

    const songPage = await call('GET', '/music/songs?pageSize=20&status=1', { token: owner.token });
    const songs = songPage.json?.data?.list ?? [];
    const song1 = songs[0];
    const song2 = songs[1] ?? songs[0];
    check('能取到至少 2 首可播歌曲', Boolean(song1) && Boolean(song2));

    const memberSwitch = await wsControl(memberWs, 'room.switch', { songId: String(song1.id) });
    check('普通成员控播被拒绝（40006）', memberSwitch.type === 'error'
      && memberSwitch.data?.code === 40006, memberSwitch);

    const ownerSwitch = await wsControl(ownerWs, 'room.switch', { songId: String(song1.id) });
    check('房主切歌成功', ownerSwitch.type === 'room.switch.ack', ownerSwitch);
    const broadcast = await memberWs.wait((m) => m.type === 'room.switch', 5000);
    check('成员收到 room.switch 广播', broadcast.data?.songId === String(song1.id), broadcast.data?.songId);

    const ownerPause = await wsControl(ownerWs, 'room.pause', {});
    check('房主暂停成功', ownerPause.type === 'room.pause.ack', ownerPause);
    const pauseBroadcast = await memberWs.wait((m) => m.type === 'room.pause', 5000);
    check('成员收到 room.pause 广播', pauseBroadcast.data?.isPlaying === false, pauseBroadcast.data?.isPlaying);

    // ---------------------------------------------------------------- 04 麦位
    section('§4 上麦、审批与麦位锁定（FR-14-006）');

    const apply1 = await call('POST', `/room/rooms/${publicRoom.id}/mic/apply`, { token: member.token });
    check('需审核房间上麦进入排队（onMic=false）',
      apply1.json?.code === 0 && apply1.json.data?.onMic === false, apply1.json);

    const apply2 = await call('POST', `/room/rooms/${publicRoom.id}/mic/apply`, { token: member.token });
    check('重复申请排麦被拒绝（40009）', apply2.json?.code === 40009, apply2.json?.code);

    const beforeApprove = await call('GET', `/room/rooms/${publicRoom.id}/mics`, { token: member.token });
    check('审批前成员不在麦上',
      !beforeApprove.json.data.some((m) => m.userId === member.userId));

    const approveMic = await call('POST', `/room/rooms/${publicRoom.id}/mic/approve`, {
      token: owner.token,
      body: { userId: member.userId, approve: true },
    });
    check('房主审批上麦成功', approveMic.json?.code === 0, approveMic.json);

    const afterApprove = await call('GET', `/room/rooms/${publicRoom.id}/mics`, { token: member.token });
    const memberMic = afterApprove.json.data.find((m) => m.userId === member.userId);
    check('审批后成员占用一个非房主麦位', memberMic && memberMic.micPos !== 1, memberMic);

    const lock = await call('POST', `/room/rooms/${publicRoom.id}/mic/lock`, {
      token: owner.token,
      body: { micPos: 3, locked: true },
    });
    check('房主锁定空麦位成功', lock.json?.code === 0, lock.json);
    const lockedMics = await call('GET', `/room/rooms/${publicRoom.id}/mics`, { token: member.token });
    check('麦位状态变为锁定',
      lockedMics.json.data.find((m) => m.micPos === 3)?.locked === true);
    await call('POST', `/room/rooms/${publicRoom.id}/mic/lock`, {
      token: owner.token, body: { micPos: 3, locked: false },
    });

    const leaveMic = await call('POST', `/room/rooms/${publicRoom.id}/mic/leave`, { token: member.token });
    check('成员主动下麦成功', leaveMic.json?.code === 0, leaveMic.json);
    const afterLeaveMic = await call('GET', `/room/rooms/${publicRoom.id}/mics`, { token: member.token });
    check('下麦后麦位释放', !afterLeaveMic.json.data.some((m) => m.userId === member.userId));

    // ---------------------------------------------------------------- 05 点歌队列
    section('§5 点歌、审批与自动消费（FR-14-007）');

    // 先让当前歌曲停在 song2，避免 song1 恰好正在播放时「切到同一首」直接消费队列项
    await wsControl(ownerWs, 'room.switch', { songId: String(song2.id) });
    const add1 = await call('POST', `/room/rooms/${publicRoom.id}/queue`, {
      token: member.token, body: { songId: String(song1.id) },
    });
    check('成员点歌进入待审（status=0）',
      add1.json?.code === 0 && add1.json.data?.status === 0, add1.json);

    const queue1 = await call('GET', `/room/rooms/${publicRoom.id}/queue`, { token: member.token });
    const pending = queue1.json.data.find((q) => q.id === add1.json.data.id);
    // 查询接口返回完整队列（含待审），实时队列快照才只保留已通过项
    check('查询接口能看到待审歌曲（status=0）', pending?.status === 0, pending);

    const approveSong = await call('POST',
      `/room/rooms/${publicRoom.id}/queue/${add1.json.data.id}/approve?approve=true`,
      { token: owner.token });
    check('房主审批点歌成功', approveSong.json?.code === 0, approveSong.json);

    await wsControl(ownerWs, 'room.switch', { songId: String(song1.id) });
    const queueOnAir = await call('GET', `/room/rooms/${publicRoom.id}/queue`, { token: member.token });
    check('正在播放的已通过项保持 status=1',
      queueOnAir.json.data.find((q) => q.id === add1.json.data.id)?.status === 1, queueOnAir.json.data);
    // 队列消费的时机是「离开这首歌」：再切一首，song1 的已通过项才落为已播放
    await wsControl(ownerWs, 'room.switch', { songId: String(song2.id) });
    const stillListed = await pollQueueAbsent(publicRoom.id, member.token, add1.json.data.id);
    if (MYSQL_PWD) {
      const dbStatus = mysql(
        `SELECT status FROM newmusic.t_room_queue WHERE id=${add1.json.data.id};`,
      );
      check('切走该歌曲后队列项落库为已播放（status=3）', dbStatus === '3', dbStatus);
    } else {
      check('切走该歌曲后队列项离开待播列表', stillListed === null, stillListed);
    }

    // 模块 13 BR-22-025：同一首歌不再新增行，而是合并为一票。
    // 「每人 3 首」的上限必须用 3 首**不同**的歌来验证。
    const limitSongs = songs.slice(2, 5);
    check('能取到 3 首未在队列中的歌用于上限测试', limitSongs.length === 3,
      songs.map((s) => s.id));
    // 合并用例用房主自己的配额：成员要留 5 次/分钟的限额给下面的上限测试（BR-22-031）
    const mergeSong = songs[5];
    const ownerFirst = await call('POST', `/room/rooms/${publicRoom.id}/queue`, {
      token: owner.token, body: { songId: String(mergeSong.id) },
    });
    const merged = await call('POST', `/room/rooms/${publicRoom.id}/queue`, {
      token: owner.token, body: { songId: String(mergeSong.id) },
    });
    check('重复点同一首歌合并到原队列项（不新增行）',
      merged.json?.code === 0 && merged.json.data?.id === ownerFirst.json.data?.id
      && (merged.json.data?.voteCount ?? 0) >= 1, merged.json);

    for (let i = 0; i < 3; i += 1) {
      const more = await call('POST', `/room/rooms/${publicRoom.id}/queue`, {
        token: member.token, body: { songId: String(limitSongs[i].id) },
      });
      check(`成员第 ${i + 1} 首待审点歌成功`, more.json?.code === 0, more.json?.code);
    }
    // 第 4 首用一首从未点过的歌：已播完的队列项不算「同时排队中」
    const overflow = await call('POST', `/room/rooms/${publicRoom.id}/queue`, {
      token: member.token, body: { songId: String(songs[6].id) },
    });
    check('单用户同时 3 首后第 4 首被拒绝（40018）', overflow.json?.code === 40018, overflow.json?.code);
    const cleared = await call('DELETE', `/room/rooms/${publicRoom.id}/queue`, { token: owner.token });
    check('房主清空队列成功', cleared.json?.code === 0 && cleared.json.data?.removed >= 3, cleared.json);

    // ---------------------------------------------------------------- 06 治理
    section('§6 禁言、踢出、拉黑与房管（FR-14-008 / FR-14-009）');

    const mute = await call('POST', `/room/rooms/${publicRoom.id}/mute`, {
      token: owner.token, body: { userId: member.userId, durationMinutes: 10 },
    });
    check('房主禁言成员成功', mute.json?.code === 0, mute.json);
    const mutedChat = await wsChat(memberWs, '禁言期间的聊天');
    check('被禁言成员聊天被拒绝（muted）',
      mutedChat.type === 'room.chat.rejected' && mutedChat.data?.reason === 'muted', mutedChat);

    const unmute = await call('DELETE', `/room/rooms/${publicRoom.id}/mute/${member.userId}`, {
      token: owner.token,
    });
    check('解除禁言成功', unmute.json?.code === 0, unmute.json);
    const freeChat = await wsChat(memberWs, '解除禁言后的聊天');
    check('解除禁言后聊天成功', freeChat.type === 'room.chat.ack', freeChat);

    const muteOwner = await call('POST', `/room/rooms/${publicRoom.id}/mute`, {
      token: member.token, body: { userId: owner.userId, durationMinutes: 1 },
    });
    check('普通成员无权禁言房主（40006/40007）',
      [40006, 40007].includes(muteOwner.json?.code), muteOwner.json?.code);

    const setAdmin = await call('POST', `/room/rooms/${publicRoom.id}/admins`, {
      token: owner.token, body: { userId: member.userId },
    });
    check('房主设置房管成功', setAdmin.json?.code === 0, setAdmin.json);
    const adminSwitch = await wsControl(memberWs, 'room.switch', { songId: String(song2.id) });
    check('房管可以控播', adminSwitch.type === 'room.switch.ack', adminSwitch);
    await call('DELETE', `/room/rooms/${publicRoom.id}/admins/${member.userId}`, { token: owner.token });

    const kickOwner = await call('POST', `/room/rooms/${publicRoom.id}/kick`, {
      token: member.token, body: { userId: owner.userId, reason: 'test' },
    });
    check('普通成员无权踢出房主（40006/40007）',
      [40006, 40007].includes(kickOwner.json?.code), kickOwner.json?.code);

    const kick = await call('POST', `/room/rooms/${publicRoom.id}/kick`, {
      token: owner.token, body: { userId: member.userId, reason: '验收踢人' },
    });
    check('房主踢出成员成功', kick.json?.code === 0, kick.json);
    const kickedFrame = await memberWs.wait((m) => m.type === 'room.kicked', 5000).catch(() => null);
    check('被踢成员收到 room.kicked', kickedFrame?.data?.remainingSeconds > 0, kickedFrame?.data);
    await sleep(300);
    check('被踢连接被服务端关闭（4003）', memberWs.closeInfo?.code === 4003, memberWs.closeInfo);

    const rejoin = await call('POST', `/room/rooms/${publicRoom.id}/join`, { token: member.token, body: {} });
    check('踢出冷却期内重新加入被拒绝（40016）', rejoin.json?.code === 40016, rejoin.json?.code);

    const guestWs2 = await connectWs(outsider.token, `guest-${SUFFIX}`).ready();
    clients.push(guestWs2);
    const guestJoinPublic = await wsJoin(guestWs2, publicRoom.id);
    check('陌生人 WS 入房成功', guestJoinPublic.type === 'room.join.ack', guestJoinPublic);

    const blacklist = await call('POST', `/room/rooms/${publicRoom.id}/blacklist`, {
      token: owner.token, body: { userId: outsider.userId },
    });
    check('房主拉黑成员成功', blacklist.json?.code === 0, blacklist.json);
    const blocked = await call('POST', `/room/rooms/${publicRoom.id}/join`, {
      token: outsider.token, body: {},
    });
    check('黑名单用户加入被拒绝（40004）', blocked.json?.code === 40004, blocked.json?.code);

    const unblacklist = await call('DELETE', `/room/rooms/${publicRoom.id}/blacklist/${outsider.userId}`, {
      token: owner.token,
    });
    check('移出黑名单成功', unblacklist.json?.code === 0, unblacklist.json);

    // ---------------------------------------------------------------- 07 关闭 / 重开 / 解散
    section('§7 关闭、重开与解散（FR-14-010）');

    // 拉黑会把目标用户已有的 WS 会话以 4004 断开（上一步验证过）；
    // 重新建立一条干净连接，才能验证 close 的广播与 4005 关闭码。
    const closerWs = await connectWs(outsider.token, `closer-${SUFFIX}`).ready();
    clients.push(closerWs);
    const closerJoin = await wsJoin(closerWs, publicRoom.id);
    check('解除黑名单后可重新入房', closerJoin.type === 'room.join.ack', closerJoin);

    const close = await call('POST', `/room/rooms/${publicRoom.id}/close`, { token: owner.token });
    check('房主关闭房间成功', close.json?.code === 0, close.json);
    const closedFrame = await closerWs.wait((m) => m.type === 'room.closed', 5000).catch(() => null);
    check('在线成员收到 room.closed', Boolean(closedFrame), closedFrame);
    await sleep(300);
    check('关房后连接以 4005 关闭', closerWs.closeInfo?.code === 4005, closerWs.closeInfo);

    const joinClosed = await call('POST', `/room/rooms/${publicRoom.id}/join`, {
      token: outsider.token, body: {},
    });
    // 加入场景刻意把关闭/解散都映射为「不存在」，避免泄露房间历史（EX-14-002-1）
    check('已关闭房间按不存在处理（40001）', joinClosed.json?.code === 40001, joinClosed.json?.code);

    const reopen = await call('POST', `/room/rooms/${publicRoom.id}/reopen`, { token: owner.token });
    check('24 小时内重开成功', reopen.json?.code === 0 && reopen.json.data?.status === 1, reopen.json);

    const dissolve = await call('DELETE', `/room/rooms/${publicRoom.id}`, {
      token: owner.token, body: { confirmName: publicName },
    });
    check('房主解散房间成功', dissolve.json?.code === 0, dissolve.json);

    const afterDissolve = await call('GET', `/room/rooms/${publicRoom.roomNo}`, { token: owner.token });
    check('解散后详情返回 40001', afterDissolve.json?.code === 40001, afterDissolve.json?.code);

    const mine = await call('GET', '/room/rooms?tab=mine&pageSize=50', { token: owner.token });
    check('「我的」列表不再包含已解散房间',
      !mine.json.data.list.some((r) => r.id === publicRoom.id));

    // 剩下的私密房/密码房也解散，避免留下活跃房间
    for (const room of [privateRoom, pwdRoom].filter(Boolean)) {
      const res = await call('DELETE', `/room/rooms/${room.id}`, {
        token: owner.token, body: { confirmName: room.name },
      });
      check(`清理房间「${room.name}」`, res.json?.code === 0, res.json?.code);
    }
  } finally {
    clients.forEach((c) => c.close());
    await sleep(400);

    // ---------------------------------------------------------------- 清理
    const fixedKeys = [];
    for (const roomId of createdRooms) {
      fixedKeys.push(
        `ml:room:online:${roomId}`,
        `ml:room:online_count:${roomId}`,
        `ml:room:play:${roomId}`,
        `ml:room:ver:${roomId}`,
        `ml:room:mic:${roomId}`,
        `ml:room:mic:queue:${roomId}`,
        `ml:room:queue:${roomId}`,
        `ml:room:queue:seq:${roomId}`,
        `ml:room:mute:${roomId}`,
        `ml:room:blacklist:${roomId}`,
        `ml:room:owner:${roomId}`,
        `ml:room:hot:${roomId}`,
        `ml:room:interaction:${roomId}`,
        `ml:lock:room:mic:${roomId}`,
        `ml:lock:room:play:${roomId}`,
      );
      fixedKeys.push(...listKeys(`ml:room:pwd:${roomId}:*`));
      fixedKeys.push(...listKeys(`ml:room:pwd:fail:${roomId}:*`));
      fixedKeys.push(...listKeys(`ml:room:kick:${roomId}:*`));
    }
    for (const user of users) {
      fixedKeys.push(
        `ml:room:user:${user.userId}`,
        `ml:room:recent:${user.userId}`,
        `ml:notify:unread:${user.userId}`,
      );
      fixedKeys.push(...listKeys(`ml:ws:dedup:${user.userId}:*`));
      fixedKeys.push(...listKeys(`ml:ws:session:${user.userId}:*`));
      fixedKeys.push(...listKeys(`ml:limit:*:${user.userId}:*`));
    }
    rDel(...new Set(fixedKeys));

    if (MYSQL_PWD && createdRooms.length) {
      try {
        const ids = createdRooms.join(', ');
        const userIds = users.map((u) => u.userId).join(', ');
        mysql(`DELETE FROM newmusic.t_room_queue WHERE room_id IN (${ids});`);
        mysql(`DELETE FROM newmusic.t_room_mic WHERE room_id IN (${ids});`);
        mysql(`DELETE FROM newmusic.t_room_member WHERE room_id IN (${ids});`);
        mysql(`DELETE FROM newmusic.t_room_message WHERE room_id IN (${ids});`);
        mysql(`DELETE FROM newmusic.t_room WHERE id IN (${ids});`);
        mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN (${userIds});`);
        const left = mysql(`SELECT COUNT(*) FROM newmusic.t_room WHERE id IN (${ids});`);
        check('测试房间数据已从 DB 删除', left === '0', left);
      } catch (e) {
        skip.push(`测试数据清理失败（不影响断言）：${e.message}`);
      }
    } else {
      skip.push('测试数据清理：未设置 MYSQL_PASSWORD，房间/队列/麦位记录会留在 newmusic 库');
    }

    const rankingAfter = rankingKeys();
    check(`收尾后共享 db0 的 ranking:* 键数未变（${rankingBefore.length} → ${rankingAfter.length}）`,
      rankingAfter.length === rankingBefore.length,
      { before: rankingBefore.length, after: rankingAfter.length });
  }

  // ---------------------------------------------------------------- 汇总
  section('本轮未覆盖 / 说明');
  skip.push('投票切歌（playMode=3）：PRD 未定义投票收集与结算协议，本轮只验证自由播放（playMode=2）与房主控播');
  skip.push('浏览器走查（断线重连、音画同步、移动端布局）需要人工执行，不属于本脚本');
  skip.push('多实例下的跨节点广播：单机验收无法覆盖，见系分 05 的风险表');
  skip.forEach((item) => console.log(`  - ${item}`));

  console.log(`\n结果：\x1b[32m${pass} 通过\x1b[0m，${fail ? `\x1b[31m${fail} 失败\x1b[0m` : '0 失败'}`);
  if (failures.length) {
    console.log('失败项：');
    failures.forEach((name) => console.log(`  - ${name}`));
  }
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error('\n验收脚本异常：', e);
  process.exit(2);
});
