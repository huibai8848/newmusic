#!/usr/bin/env node
/**
 * 模块 13 验收脚本 —— 音乐房功能升级（权威播放态 / 队列 / 投票 / 榜单 / 弹幕）。
 *
 * 运行：
 *   MYSQL_PASSWORD=*** node scripts/verify-13.mjs
 *
 * 前置：
 *   * 应用已启动（8081）；
 *   * 已执行 sql/13-room-upgrade-seed.sql（或 schema.sql 的对应增量）；
 *   * Node 22+（全局 WebSocket），Redis 6379 / MySQL 3306 可连。
 *
 * 共享实例约束（与 verify-04/05/06/12 相同）：
 *   * Redis db0 有别的项目的 `ranking:*` 键：只读写 `ml:` 前缀，绝不 FLUSHDB/FLUSHALL；
 *     脚本开头/结尾各数一次 `ranking:*`，证明未误伤。
 *   * MySQL 3306 有 emp_db / music_db：所有语句带 `newmusic.` 限定，只删本轮房间的精确 ID；
 *     开头/结尾对比其它库的表数量。
 *   * 口令只从环境变量 MYSQL_PASSWORD 读取，经 MYSQL_PWD 传给客户端，不写入文件。
 *
 * 说明：弱网重连退避、软/硬校准与音画同步属于浏览器端行为，由
 * `web/scripts/test-room-sync.mjs` 覆盖纯函数部分，真实设备走查不在本脚本内。
 */

import { spawnSync } from 'node:child_process';

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const WS_BASE = process.env.WS_BASE
  || `${API_BASE.replace(/\/+$/, '').replace(/\/api\/v1$/, '')}/ws`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';
const HAS_MYSQL = Boolean(MYSQL_PWD);

const PASSWORD = 'Probe@12345';
const TAG = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

let pass = 0;
let fail = 0;
const failures = [];
const skipNotes = [];

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

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

function note(message) {
  console.log(`  \x1b[33m•\x1b[0m ${message}`);
}

// ---------------------------------------------------------------- HTTP

async function call(method, path, { token, body, xff } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  if (xff) headers['X-Forwarded-For'] = xff;
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
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
  const account = `v13${label}${TAG}`;
  const reg = await call('POST', '/auth/register', {
    xff: syntheticIp(),
    body: { username: account, password: PASSWORD, nickname: account },
  });
  if (codeOf(reg) !== 0) {
    throw new Error(`注册 ${account} 失败：${reg.text}`);
  }
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
  // 本机 --scan 静默返回空，固定用 keys；只在验收脚本里对 ml:/ranking: 前缀使用
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
const otherDbTablesBefore = HAS_MYSQL
  ? mysqlScalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')")
  : '0';

// ---------------------------------------------------------------- WebSocket

function connectWs(token, deviceId) {
  const url = new URL(WS_BASE);
  url.searchParams.set('token', token);
  url.searchParams.set('deviceId', deviceId);
  const socket = new WebSocket(url.toString());
  const inbox = [];
  const waiters = [];
  const state = { close: null, closed: false };

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
    state.closed = true;
    while (waiters.length) {
      const waiter = waiters.shift();
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`连接已关闭（code=${state.close?.code}）`));
    }
  });

  const opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', () => resolve());
    socket.addEventListener('error', () => reject(new Error('WS 连接失败')));
  });

  const client = {
    socket,
    get closed() {
      return state.closed;
    },
    async ready() {
      await opened;
      await client.wait((m) => m.type === 'connected', 5000).catch(() => null);
      return client;
    },
    send(type, data, clientMsgId) {
      socket.send(JSON.stringify({
        type,
        clientMsgId,
        data: { ...(data || {}), clientMsgId },
        ts: Date.now(),
      }));
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
        socket.close(1000, 'verify done');
      } catch {
        /* ignore */
      }
    },
  };
  return client;
}

let seq = 0;
const newMsgId = () => `v13-${Date.now().toString(36)}-${(seq += 1).toString(36)}`;

async function wsJoin(client, roomId) {
  const clientMsgId = newMsgId();
  client.send('room.join', { roomId: String(roomId) }, clientMsgId);
  return client.wait((m) => (m.type === 'room.join.ack' && m.clientMsgId === clientMsgId)
    || (m.type === 'error' && m.data?.context?.clientMsgId === clientMsgId));
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

// ---------------------------------------------------------------- 主流程

async function main() {
  if (!(await waitForBoot())) {
    console.error('\n应用未在 90s 内就绪。');
    process.exit(2);
  }

  const owner = await registerAndLogin('own');
  const member = await registerAndLogin('mem');
  const member2 = await registerAndLogin('mm2');
  const users = [owner, member, member2];
  const clients = [];
  let room = null;
  let roomName = null;

  try {
    section('§0 服务器时间（FR-22-001）');
    const time = await call('GET', '/time');
    check('GET /time 无需登录且返回毫秒时间戳',
      codeOf(time) === 0 && Number(dataOf(time)?.serverTime) > 0, time.json);
    const drift = Math.abs(Number(dataOf(time)?.serverTime) - Date.now());
    check('服务器时间与本机偏差 < 5 分钟（时钟同步前提）', drift < 300_000, drift);

    section('§1 权威播放态与时钟同步（FR-22-001 / BR-22-009）');
    roomName = `验收13房${TAG}`;
    const created = await call('POST', '/room/rooms', {
      token: owner.token,
      body: { name: roomName, micCount: 6, maxMembers: 50 },
    });
    room = dataOf(created);
    check('创建验收房间成功', codeOf(created) === 0 && Boolean(room?.id), created.json);
    const roomId = room.id;

    const joinMember = await call('POST', `/room/rooms/${roomId}/join`, {
      token: member.token, body: {},
    });
    const joinMember2 = await call('POST', `/room/rooms/${roomId}/join`, {
      token: member2.token, body: {},
    });
    check('两名听众 HTTP 加入成功',
      codeOf(joinMember) === 0 && codeOf(joinMember2) === 0,
      [joinMember.json?.code, joinMember2.json?.code]);

    const ownerWs = await connectWs(owner.token, `v13-own-${TAG}`).ready();
    const memberWs = await connectWs(member.token, `v13-mem-${TAG}`).ready();
    const member2Ws = await connectWs(member2.token, `v13-mm2-${TAG}`).ready();
    clients.push(ownerWs, memberWs, member2Ws);
    const ownerAck = await wsJoin(ownerWs, roomId);
    const memberAck = await wsJoin(memberWs, roomId);
    const member2Ack = await wsJoin(member2Ws, roomId);
    check('三人 WS 入房成功（最后一人快照在线数=3）',
      ownerAck.type === 'room.join.ack' && memberAck.type === 'room.join.ack'
      && member2Ack.type === 'room.join.ack'
      && member2Ack.data?.onlineCount === 3,
      [ownerAck.type, memberAck.type, member2Ack.type, member2Ack.data?.onlineCount]);

    const songsRes = await call('GET', '/music/songs?pageSize=20&status=1', { token: owner.token });
    const songs = dataOf(songsRes)?.list ?? [];
    check('能取到至少 6 首可播歌曲', songs.length >= 6, songs.length);
    if (songs.length < 6) {
      throw new Error('曲库不足，无法继续模块 13 验收');
    }
    const [songA, songB, songC, songD, songE, songX] = songs;

    const syncId = newMsgId();
    const sentAt = Date.now();
    ownerWs.send('ROOM_SYNC_REQ', { clientTime: sentAt }, syncId);
    const syncAck = await ownerWs.wait((m) => m.type === 'ROOM_SYNC_ACK'
      && m.data?.clientTime === sentAt, 5000);
    check('ROOM_SYNC_REQ 回显 clientTime 并返回 serverTime',
      syncAck.data?.clientTime === sentAt && Number(syncAck.data?.serverTime) > 0,
      syncAck.data);

    const switched = await wsControl(ownerWs, 'room.switch', {
      songId: String(songA.id), positionMs: 35000,
    });
    check('房主切歌成功（起播位置 35s）', switched.type === 'room.switch.ack', switched);
    const playBroadcast = await memberWs.wait((m) => m.type === 'ROOM_PLAY_STATE', 5000);
    check('成员收到 ROOM_PLAY_STATE 权威帧',
      playBroadcast.data?.songId === String(songA.id), playBroadcast.data?.songId);
    check('权威帧同时带毫秒制与模块 04 秒制字段',
      Number(playBroadcast.data?.startPositionMs) === 35000
      && playBroadcast.data?.playStatus === 1
      && playBroadcast.data?.isPlaying === true
      && Number(playBroadcast.data?.playStateVersion) > 0,
      playBroadcast.data);

    const state1 = await call('GET', `/room/rooms/${roomId}/play-state`, { token: member.token });
    const s1 = dataOf(state1);
    check('GET play-state 返回完整权威字段',
      codeOf(state1) === 0 && s1?.playStatus === 1 && s1?.startPositionMs === 35000
      && s1?.playbackRate === 100 && s1?.volume === 100
      && Number(s1?.playStateVersion) > 0 && Number(s1?.serverTime) > 0, s1);
    const expectedA = s1.startPositionMs + Math.max(0, Date.now() - s1.stateUpdateTimeMs);
    check('按公式计算的应到进度不早于起播位置', expectedA >= 35000, expectedA);

    const pause = await call('POST', `/room/rooms/${roomId}/play`, {
      token: owner.token, body: { action: 'pause' },
    });
    check('HTTP 暂停成功且 playStatus=2',
      codeOf(pause) === 0 && dataOf(pause)?.playStatus === 2, pause.json);
    const pauseVersion = Number(dataOf(pause)?.playStateVersion);
    const resume = await call('POST', `/room/rooms/${roomId}/play`, {
      token: owner.token, body: { action: 'play' },
    });
    check('HTTP 继续播放且版本号递增',
      codeOf(resume) === 0 && dataOf(resume)?.playStatus === 1
      && Number(dataOf(resume)?.playStateVersion) > pauseVersion,
      [pauseVersion, dataOf(resume)?.playStateVersion]);

    const nonOwnerPause = await call('POST', `/room/rooms/${roomId}/play`, {
      token: member.token, body: { action: 'pause' },
    });
    check('普通成员 HTTP 控播被拒绝（40006）', codeOf(nonOwnerPause) === 40006,
      nonOwnerPause.json?.code);

    // 恢复 35s，供投票的「前 30 秒不可发起」规则通过
    await wsControl(ownerWs, 'room.switch', {
      songId: String(songA.id), positionMs: 35000,
    });

    section('§2 队列：合并、置顶、撤销（FR-22-003 / BR-22-023~029）');
    const addB = await call('POST', `/room/rooms/${roomId}/queue`, {
      token: owner.token, body: { songId: String(songB.id) },
    });
    check('房主点歌直接进入已通过区（status=1, priority=100）',
      codeOf(addB) === 0 && dataOf(addB)?.status === 1 && dataOf(addB)?.priority === 100,
      addB.json);
    const addC = await call('POST', `/room/rooms/${roomId}/queue`, {
      token: owner.token, body: { songId: String(songC.id) },
    });
    check('第二首点歌入队成功', codeOf(addC) === 0, addC.json);

    const merge = await call('POST', `/room/rooms/${roomId}/queue`, {
      token: owner.token, body: { songId: String(songB.id) },
    });
    check('重复点歌合并到原队列项而不是新增行',
      codeOf(merge) === 0 && dataOf(merge)?.id === dataOf(addB)?.id
      && Number(dataOf(merge)?.voteCount) >= 1, merge.json);

    const memberAdd = await call('POST', `/room/rooms/${roomId}/queue`, {
      token: member.token, body: { songId: String(songX.id) },
    });
    check('普通成员点歌进入待审（status=0）',
      codeOf(memberAdd) === 0 && dataOf(memberAdd)?.status === 0, memberAdd.json);
    const queueId = dataOf(memberAdd)?.id;
    const withdraw = await call('DELETE', `/room/rooms/${roomId}/queue/${queueId}`, {
      token: member.token,
    });
    check('点歌人可撤销自己的待审歌曲', codeOf(withdraw) === 0, withdraw.json);

    const memberAdd2 = await call('POST', `/room/rooms/${roomId}/queue`, {
      token: member.token, body: { songId: String(songX.id) },
    });
    const pin = await call('POST', `/room/rooms/${roomId}/queue/${dataOf(memberAdd2)?.id}/priority`, {
      token: owner.token, body: { pinned: true },
    });
    check('房主置顶后 priority=100', codeOf(pin) === 0 && dataOf(pin)?.priority === 100, pin.json);
    const unpin = await call('POST', `/room/rooms/${roomId}/queue/${dataOf(memberAdd2)?.id}/priority`, {
      token: owner.token, body: { pinned: false },
    });
    check('取消置顶后 priority=0', codeOf(unpin) === 0 && dataOf(unpin)?.priority === 0, unpin.json);
    await call('DELETE', `/room/rooms/${roomId}/queue/${dataOf(memberAdd2)?.id}`, {
      token: owner.token,
    });

    const memberPin = await call('POST', `/room/rooms/${roomId}/queue/${dataOf(addB)?.id}/priority`, {
      token: member.token, body: { pinned: true },
    });
    check('普通成员置顶被拒绝（40006）', codeOf(memberPin) === 40006, memberPin.json?.code);

    section('§3 投票跳过（FR-22-004 / BR-22-033~041）');
    const vote1 = await call('POST', `/room/rooms/${roomId}/votes`, { token: owner.token });
    check('房主发起投票成功（在线快照=3，剩余 60 秒）',
      codeOf(vote1) === 0 && dataOf(vote1)?.onlineCount === 3
      && dataOf(vote1)?.status === 1 && dataOf(vote1)?.remainingSeconds >= 55, vote1.json);
    const voteId1 = dataOf(vote1)?.voteId;

    const duplicateVote = await call('POST', `/room/rooms/${roomId}/votes`, { token: member.token });
    check('同一首歌重复发起被拒绝（40022）', codeOf(duplicateVote) === 40022, duplicateVote.json?.code);

    const voted = await call('POST', `/room/rooms/${roomId}/votes/${voteId1}/agree`, {
      token: owner.token,
    });
    check('房主一票通过（status=2）', codeOf(voted) === 0 && dataOf(voted)?.status === 2, voted.json);
    const voteResult1 = await memberWs.wait((m) => m.type === 'ROOM_VOTE_RESULT', 5000);
    check('成员收到投票通过广播', voteResult1.data?.passed === true, voteResult1.data);
    const afterSkip1 = await pollPlayState(roomId, member.token, (s) => s.songId === String(songB.id));
    check('投票通过后自动切到队列下一首（songB）',
      afterSkip1?.songId === String(songB.id), afterSkip1);

    await wsControl(ownerWs, 'room.switch', { songId: String(songB.id), positionMs: 35000 });
    const vote2 = await call('POST', `/room/rooms/${roomId}/votes`, { token: member.token });
    check('普通成员可发起第二首歌的投票', codeOf(vote2) === 0, vote2.json);
    const voteId2 = dataOf(vote2)?.voteId;
    const agree21 = await call('POST', `/room/rooms/${roomId}/votes/${voteId2}/agree`, {
      token: member2.token,
    });
    check('第一票计入（未通过）',
      codeOf(agree21) === 0 && dataOf(agree21)?.status === 1
      && dataOf(agree21)?.agreeCount === 1, agree21.json);
    const agree22 = await call('POST', `/room/rooms/${roomId}/votes/${voteId2}/agree`, {
      token: member.token,
    });
    check('2/3 在线（≥60%）自动通过',
      codeOf(agree22) === 0 && dataOf(agree22)?.status === 2, agree22.json);
    const afterSkip2 = await pollPlayState(roomId, member.token, (s) => s.songId === String(songC.id));
    check('第二首歌投票通过后切到 songC', afterSkip2?.songId === String(songC.id), afterSkip2);

    await wsControl(ownerWs, 'room.switch', { songId: String(songC.id), positionMs: 35000 });
    const vote3 = await call('POST', `/room/rooms/${roomId}/votes`, { token: member.token });
    check('第三首歌投票发起成功', codeOf(vote3) === 0, vote3.json);
    const voteId3 = dataOf(vote3)?.voteId;
    await wsControl(ownerWs, 'room.switch', { songId: String(songD.id), positionMs: 35000 });
    const cancelled = await memberWs.wait((m) => m.type === 'ROOM_VOTE_RESULT'
      && m.data?.songId === String(songC.id) && m.data?.passed === false, 5000);
    check('切歌自动作废进行中的投票',
      cancelled.data?.status === 4 && /取消/.test(cancelled.data?.message || ''), cancelled.data);
    const currentVote = await call('GET', `/room/rooms/${roomId}/votes/current`, { token: member.token });
    check('GET current 返回最近一次投票（已取消）',
      codeOf(currentVote) === 0 && dataOf(currentVote)?.voteId === voteId3
      && dataOf(currentVote)?.status === 4, currentVote.json);

    const vote4 = await call('POST', `/room/rooms/${roomId}/votes`, { token: member2.token });
    check('同一房间 5 分钟内第 4 次发起被冷却拒绝（40023）',
      codeOf(vote4) === 40023, vote4.json?.code);

    section('§4 榜单、点赞、收听与隐身（FR-22-005~007）');
    const chat1 = await wsChat(memberWs, '模块13验收聊天');
    check('成员聊天成功（活跃+1）', chat1.type === 'room.chat.ack', chat1);
    const rankActive = await call('GET', `/room/rooms/${roomId}/rank?type=active&limit=10`, {
      token: member.token,
    });
    const rankList = dataOf(rankActive) ?? [];
    check('活跃榜按分数降序且包含房主',
      codeOf(rankActive) === 0 && rankList.length >= 3
      && rankList[0].userId === owner.userId
      && rankList.every((item, i) => i === 0 || item.score <= rankList[i - 1].score),
      rankList);
    const requestRank = await call('GET', `/room/rooms/${roomId}/rank?type=request`, {
      token: member.token,
    });
    check('点歌榜记录房主的点歌行为',
      codeOf(requestRank) === 0
      && dataOf(requestRank)?.some((item) => item.userId === owner.userId), requestRank.json);

    const badType = await call('GET', `/room/rooms/${roomId}/rank?type=unknown`, {
      token: member.token,
    });
    check('非法榜单类型被拒绝（40027）', codeOf(badType) === 40027, badType.json?.code);

    const hide = await call('POST', `/room/rooms/${roomId}/rank/hidden`, {
      token: owner.token, body: { hidden: true },
    });
    const hiddenRank = await call('GET', `/room/rooms/${roomId}/rank?type=active`, {
      token: member.token,
    });
    check('选择不上榜后从榜单消失',
      codeOf(hide) === 0
      && !(dataOf(hiddenRank) ?? []).some((item) => item.userId === owner.userId),
      dataOf(hiddenRank));
    await call('POST', `/room/rooms/${roomId}/rank/hidden`, {
      token: owner.token, body: { hidden: false },
    });

    const like1 = await call('POST', `/room/rooms/${roomId}/like`, { token: member.token });
    check('给当前歌曲点赞成功（liked=true, count=1）',
      codeOf(like1) === 0 && dataOf(like1)?.liked === true
      && Number(dataOf(like1)?.likeCount) === 1, like1.json);
    const like2 = await call('POST', `/room/rooms/${roomId}/like`, { token: member.token });
    check('重复点赞幂等（liked=false, count 仍为 1）',
      codeOf(like2) === 0 && dataOf(like2)?.liked === false
      && Number(dataOf(like2)?.likeCount) === 1, like2.json);

    // BR-22-069：点赞是累计值，后进房（或刷新）的人也要能看到，不能只靠增量广播
    const stateWithLike = await call('GET', `/room/rooms/${roomId}/play-state`, {
      token: owner.token,
    });
    check('播放态携带累计点赞数（后进房可见）',
      codeOf(stateWithLike) === 0 && Number(dataOf(stateWithLike)?.likeCount) === 1,
      dataOf(stateWithLike));

    const listen1 = await call('POST', `/room/rooms/${roomId}/listen`, {
      token: member.token, body: { seconds: 5 },
    });
    check('收听上报首次计 5 秒',
      codeOf(listen1) === 0 && Number(dataOf(listen1)?.acceptedSeconds) === 5, listen1.json);
    const listen2 = await call('POST', `/room/rooms/${roomId}/listen`, {
      token: member.token, body: { seconds: 5 },
    });
    check('连续伪造上报被真实间隔截断（≤2 秒）',
      codeOf(listen2) === 0 && Number(dataOf(listen2)?.acceptedSeconds) <= 2, listen2.json);

    section('§5 弹幕、表情与 @ 提醒（FR-22-006 / BR-22-045）');
    const tooLong = await new Promise((resolve) => {
      const clientMsgId = newMsgId();
      member2Ws.send('room.danmaku', { content: '字'.repeat(31) }, clientMsgId);
      member2Ws.wait((m) => m.type === 'room.danmaku.rejected', 5000).then(resolve).catch(resolve);
    });
    check('弹幕超过 30 字被拒绝', tooLong?.data?.reason === 'too_long', tooLong?.data);

    const danmaku = await new Promise((resolve) => {
      member2Ws.send('room.danmaku', { content: '字'.repeat(30) }, newMsgId());
      member2Ws.wait((m) => m.type === 'ROOM_DANMAKU', 5000).then(resolve).catch(resolve);
    });
    check('30 字弹幕收到 ROOM_DANMAKU 广播',
      danmaku?.type === 'ROOM_DANMAKU'
      && danmaku?.data?.content?.length === 30, danmaku?.data);

    const emoji = await new Promise((resolve) => {
      member2Ws.send('room.emoji', { emoji: '🎉' }, newMsgId());
      member2Ws.wait((m) => m.type === 'ROOM_EMOJI', 5000).then(resolve).catch(resolve);
    });
    check('预置表情广播成功', emoji?.data?.emoji === '🎉', emoji?.data);

    const badEmoji = await new Promise((resolve) => {
      member2Ws.send('room.emoji', { emoji: 'not-an-emoji' }, newMsgId());
      member2Ws.wait((m) => m.type === 'room.emoji.rejected', 5000).then(resolve).catch(resolve);
    });
    check('非预置表情被拒绝', badEmoji?.data?.reason === 'invalid_emoji', badEmoji?.data);

    const mentionText = `@${member2.account} 来看验收`;
    const mentionChat = await wsChat(memberWs, mentionText);
    check('带 @ 的聊天发送成功', mentionChat.type === 'room.chat.ack', mentionChat);
    const mention = await member2Ws.wait((m) => m.type === 'room.mention', 5000).catch(() => null);
    check('被 @ 的在线成员收到 room.mention',
      mention?.data?.fromUserId === member.userId
      && mention?.data?.roomId === String(roomId), mention?.data);

    section('§6 共享实例完整性');
    const voteRows = HAS_MYSQL
      ? mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_room_vote WHERE room_id=${roomId}`)
      : '0';
    check('投票记录已落库（同一首歌唯一）', !HAS_MYSQL || Number(voteRows) >= 3, voteRows);
    if (HAS_MYSQL) {
      const dup = mysqlScalar(`SELECT COUNT(*) FROM (SELECT song_id FROM newmusic.t_room_vote
        WHERE room_id=${roomId} GROUP BY song_id HAVING COUNT(*) > 1) t`);
      check('同一歌曲没有重复投票记录', dup === '0', dup);
    }
  } finally {
    for (const client of clients) {
      client.close();
    }
    await sleep(300);

    if (room) {
      try {
        await call('DELETE', `/room/rooms/${room.id}`, {
          token: owner.token, body: { confirmName: roomName },
        });
      } catch {
        /* 清理失败不覆盖断言 */
      }
    }

    if (REDIS_OK) {
      const cleanup = [];
      for (const user of users) {
        cleanup.push(...listKeys(`ml:limit:*:${user.userId}:*`));
        cleanup.push(...listKeys(`ml:ws:dedup:${user.userId}:*`));
        cleanup.push(...listKeys(`ml:ws:session:${user.userId}:*`));
        cleanup.push(`ml:notify:unread:${user.userId}`);
      }
      if (cleanup.length) {
        // 只删本次注册用户/本房间的 ml: 键
        redis('del', ...cleanup);
      }
    }

    if (HAS_MYSQL && room) {
      const roomId = room.id;
      const userIds = users.map((u) => u.userId).join(',');
      try {
        mysql(`DELETE FROM newmusic.t_room_vote_detail WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_vote WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_play_log WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_queue WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_mic WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_member WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room_message WHERE room_id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_room WHERE id=${roomId};`);
        mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN (${userIds});`);
        const left = mysqlScalar(`SELECT COUNT(*) FROM newmusic.t_room WHERE id=${roomId}`);
        check('验收房间数据已从 DB 清理', left === '0', left);
      } catch (e) {
        skipNotes.push(`清理失败（不影响断言）：${e.message}`);
      }
    }

    if (REDIS_OK) {
      const rankingAfter = listKeys('ranking:*');
      check('共享 Redis 的 ranking:* 键未被误伤',
        rankingAfter.length === rankingBefore.length,
        `${rankingBefore.length} → ${rankingAfter.length}`);
    }
    if (HAS_MYSQL) {
      const otherAfter = mysqlScalar(
        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('emp_db','music_db')",
      );
      check('共享 MySQL 的 emp_db/music_db 表数量未变',
        otherAfter === otherDbTablesBefore, `${otherDbTablesBefore} → ${otherAfter}`);
    }
  }

  section('本轮未覆盖 / 说明');
  skipNotes.forEach((item) => console.log(`  - ${item}`));
  console.log('  - 软/硬校准、重连退避、音画同步：见 web/scripts/test-room-sync.mjs 与浏览器走查');
  console.log(`\n结果：\x1b[32m${pass} 通过\x1b[0m，${fail ? `\x1b[31m${fail} 失败\x1b[0m` : '0 失败'}`);
  if (failures.length) {
    console.log('失败项：');
    failures.forEach((name) => console.log(`  - ${name}`));
  }
  process.exit(fail ? 1 : 0);
}

async function pollPlayState(roomId, token, predicate, timeout = 4000) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    const res = await call('GET', `/room/rooms/${roomId}/play-state`, { token });
    last = dataOf(res);
    if (last && predicate(last)) {
      return last;
    }
    await sleep(150);
  }
  return last;
}

main().catch((e) => {
  console.error('\n验收脚本异常：', e);
  process.exit(2);
});
