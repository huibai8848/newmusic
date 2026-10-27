#!/usr/bin/env node
/**
 * 模块 04 验收脚本 —— 对应 docs/系分/04-WebSocket.md §8 验收核对表。
 *
 * 运行：
 *   node scripts/verify-04.mjs            # 全部（约 20s；不含 90s 空闲超时）
 *   node scripts/verify-04.mjs idle       # 附带空闲超时用例（多等约 100s）
 *   API_BASE=http://localhost:8081/api/v1 node scripts/verify-04.mjs
 *
 * 前置：应用已启动（8081）、本机 Redis 6379 可连、MySQL 里已建 t_notification /
 *       t_room_message（DDL 见 sql/schema.sql）。依赖 Node 18+，无需 npm 包。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 为什么不用 Node 内建的 WebSocket
 * ─────────────────────────────────────────────────────────────────────────────
 * 内建 `WebSocket` 不能设置自定义请求头，而本脚本有两类用例**必须**在握手请求上
 * 控制头部：
 *   1. 单 IP 连接上限（BR-13-003）：需要给每条连接不同的/相同的 `X-Forwarded-For`，
 *      否则全部来自 127.0.0.1，跟本机其它调试连接混在一起，计数无法断言；
 *   2. 握手被拒的 HTTP 状态码（401/403/429，EX-13-001-1~4）：内建 API 只抛一个
 *      “handshake failed”，拿不到 status 与响应体里的业务错误码。
 * 所以这里用 `node:http` 手写了一个最小的 RFC 6455 客户端（握手 + 帧编解码 +
 * 关闭帧）。它只实现本脚本用到的部分：文本/二进制/close/ping/pong、分片重组。
 * 这不是在造轮子，是在补内建 API 明确不提供的能力。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 共享环境约束（与 verify-06 相同）
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠ 本机 6379 是共享实例：db0 里躺着另一个项目的 `ranking:*` 键。脚本只读写
 *   `ml:` 前缀，并在开头/结尾各数一遍 `ranking:*` 的键数来证明没碰它。
 *   绝不用 FLUSHDB/FLUSHALL；列举用 `keys`（本机 redis-cli 3.0 的 `--scan`
 *   会静默返回空）。中文参数一律走 stdin，避免 ANSI 代码页二次转码。
 *
 * ⚠ 本机 3306 是共享实例：清理测试数据时所有 SQL 都带 `newmusic.` 限定符，
 *   且只删本脚本自己创建的用户/房间的数据。口令只从环境变量 `MYSQL_PASSWORD`
 *   读取，经 `MYSQL_PWD` 传给客户端，不写进文件、不出现在进程参数里。
 *
 * ⚠ 每次运行都用**全新用户**（带时间戳后缀）。通知未读数是 DB 真值，复用老用户
 *   会让「未读 = 1」这类断言依赖上一次运行的残留。
 */

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';

const API_BASE = process.env.API_BASE || 'http://localhost:8081/api/v1';
const WS_BASE = process.env.WS_BASE
  || `${API_BASE.replace(/\/+$/, '').replace(/\/api\/v1$/, '')}/ws`;
const REDIS_CLI = process.env.REDIS_CLI || 'C:\\Program Files\\Redis\\redis-cli';
const logPath = process.env.APP_LOG === '' ? null : resolve(process.env.APP_LOG || 'boot.log');

const WITH_IDLE = (process.argv[2] || '').toLowerCase() === 'idle';

/** 种子歌曲：晴天（周杰伦，copyright_status=1，可播） */
const SEED_SONG = '1000000000000005001';
const ADMIN = { account: 'admin', password: 'admin123' };
const PASSWORD = 'Probe@12345';

/** 每次运行唯一：用户名 4~20 位字母数字下划线，所以时间戳只取 36 进制 */
const STAMP = Date.now().toString(36);
const ACCOUNT = {
  a: `m04a${STAMP}`,
  b: `m04b${STAMP}`,
  c: `m04c${STAMP}`,
};
/** 房间号用当前毫秒：本轮唯一，且远小于 JS 安全整数 */
const ROOM = String(Date.now());

/** 合成 IP 每次运行随机，避免上一轮残留的计数影响；IP 上限用例独占一段 */
const IP_PREFIX = `10.94.${Math.floor(Math.random() * 250) + 1}`;
const syntheticIp = (suffix) => `${IP_PREFIX}.${suffix}`;

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
    console.log(`  \x1b[31m✗\x1b[0m ${name}${extra !== undefined ? ` → ${summarize(extra)}` : ''}`);
  }
}

function note(msg) {
  console.log(`  \x1b[33m•\x1b[0m ${msg}`);
}

function section(title) {
  console.log(`\n\x1b[36m${title}\x1b[0m`);
}

function summarize(value) {
  if (value === undefined) return 'undefined';
  const s = typeof value === 'string' ? value : JSON.stringify(value);
  return s && s.length > 400 ? `${s.slice(0, 400)}…` : s;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- HTTP helper

const syntheticHttpIp = () => syntheticIp(Math.floor(Math.random() * 250) + 1);

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

/** 注册（幂等）+ 登录。返回 token 与 userId（userId 是字符串：雪花 ID 走字符串序列化） */
async function loginAs(account, password = PASSWORD) {
  await call('POST', '/auth/register', {
    xff: syntheticHttpIp(),
    body: { username: account, password, nickname: account },
  });
  const res = await call('POST', '/auth/login', { xff: syntheticHttpIp(), body: { account, password } });
  if (res.json?.code !== 0) throw new Error(`账号登录失败 ${account}: ${res.text}`);
  return { token: res.json.data.accessToken, userId: String(res.json.data.user.id), account };
}

// ---------------------------------------------------------------- Redis helper

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
const rDel = (...keys) => (keys.length ? redis('del', ...keys) : '0');
const rExists = (k) => redis('exists', k) === '1';
const rGet = (k) => redis('get', k);
const rScard = (k) => Number(redis('scard', k));
const rHget = (k, f) => redis('hget', k, f);
const listKeys = (pattern) => {
  const out = redis('keys', pattern);
  return out === '' ? [] : out.split('\n').map((s) => s.trim()).filter(Boolean);
};
const rankingKeys = () => listKeys('ranking:*');

// ---------------------------------------------------------------- MySQL helper

const MYSQL_CLI = process.env.MYSQL_CLI || 'mysql';
const MYSQL_USER = process.env.MYSQL_USER || 'root';
const MYSQL_DB = process.env.MYSQL_DB || 'newmusic';
const MYSQL_PWD = process.env.MYSQL_PASSWORD || '';

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

function grepLog(pattern) {
  if (!logPath) return [];
  try {
    return readFileSync(logPath, 'latin1')
      .split('\n')
      .filter((line) => pattern.test(line));
  } catch {
    return [];
  }
}

// =================================================================
// 最小 RFC 6455 客户端
// =================================================================

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** 客户端帧必须加掩码（RFC 6455 §5.3）；长度编码按 7/16/64 位三档。 */
function encodeFrame(opcode, payload) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload, 'utf8');
  const len = data.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  header[0] = 0x80 | opcode; // FIN=1，不分片
  header[1] |= 0x80;         // MASK=1
  const mask = crypto.randomBytes(4);
  const masked = Buffer.alloc(len);
  for (let i = 0; i < len; i += 1) masked[i] = data[i] ^ mask[i % 4];
  return Buffer.concat([header, mask, masked]);
}

class RawWs {
  constructor(socket, head) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.queue = [];
    this.waiters = [];
    this.closed = null;
    this.fragOpcode = null;
    this.fragParts = [];

    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.parse();
    });
    socket.on('end', () => this.markClosed(1006, 'socket ended'));
    socket.on('close', () => this.markClosed(1006, 'socket closed'));
    socket.on('error', () => this.markClosed(1006, 'socket error'));

    if (head && head.length) {
      this.buffer = Buffer.concat([this.buffer, head]);
      this.parse();
    }
  }

  parse() {
    for (;;) {
      if (this.buffer.length < 2) return;
      const b0 = this.buffer[0];
      const b1 = this.buffer[1];
      const fin = (b0 & 0x80) !== 0;
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let offset = 2;
      if (len === 126) {
        if (this.buffer.length < 4) return;
        len = this.buffer.readUInt16BE(2);
        offset = 4;
      } else if (len === 127) {
        if (this.buffer.length < 10) return;
        const big = this.buffer.readBigUInt64BE(2);
        if (big > BigInt(Number.MAX_SAFE_INTEGER)) {
          this.destroy();
          return;
        }
        len = Number(big);
        offset = 10;
      }
      let maskKey = null;
      if (masked) {
        if (this.buffer.length < offset + 4) return;
        maskKey = this.buffer.subarray(offset, offset + 4);
        offset += 4;
      }
      if (this.buffer.length < offset + len) return;
      let payload = this.buffer.subarray(offset, offset + len);
      if (maskKey) {
        const copy = Buffer.alloc(len);
        for (let i = 0; i < len; i += 1) copy[i] = payload[i] ^ maskKey[i % 4];
        payload = copy;
      }
      this.buffer = this.buffer.subarray(offset + len);
      this.handleFrame(fin, opcode, payload);
    }
  }

  handleFrame(fin, opcode, payload) {
    if (opcode === 0x0) {
      this.fragParts.push(payload);
      if (fin) {
        const whole = Buffer.concat(this.fragParts);
        const op = this.fragOpcode;
        this.fragParts = [];
        this.fragOpcode = null;
        this.dispatch(op, whole);
      }
      return;
    }
    if (opcode === 0x1 || opcode === 0x2) {
      if (!fin) {
        this.fragOpcode = opcode;
        this.fragParts = [payload];
        return;
      }
      this.dispatch(opcode, payload);
      return;
    }
    if (opcode === 0x8) {
      const code = payload.length >= 2 ? payload.readUInt16BE(0) : 1005;
      const reason = payload.length > 2 ? payload.subarray(2).toString('utf8') : '';
      if (!this.closed) {
        // 回一条关闭帧（RFC 要求对端收到 close 后回应），然后断开
        try { this.socket.write(encodeFrame(0x8, payload.subarray(0, 2))); } catch { /* ignore */ }
        this.markClosed(code, reason);
        setTimeout(() => this.destroy(), 50);
      }
      return;
    }
    if (opcode === 0x9) {
      try { this.socket.write(encodeFrame(0xA, payload)); } catch { /* ignore */ }
      return;
    }
    // 0xA pong：客户端不发 ping，忽略
  }

  dispatch(opcode, payload) {
    if (opcode === 0x2) {
      this.push({ kind: 'binary', byteLength: payload.length });
      return;
    }
    const text = payload.toString('utf8');
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* 非 JSON 文本 */
    }
    this.push({ kind: 'message', text, json });
  }

  push(item) {
    this.queue.push(item);
    this.drain();
  }

  drain() {
    while (this.waiters.length && this.queue.length) {
      const waiter = this.waiters.shift();
      clearTimeout(waiter.timer);
      waiter.resolve(this.queue.shift());
    }
  }

  markClosed(code, reason) {
    if (this.closed) return;
    this.closed = { code, reason };
    this.queue.push({ kind: 'close', code, reason });
    // 已有等待者：按顺序把队列发完，其余的直接用关闭事件结束
    while (this.waiters.length) {
      const waiter = this.waiters.shift();
      clearTimeout(waiter.timer);
      if (this.queue.length) {
        waiter.resolve(this.queue.shift());
      } else {
        waiter.reject(new Error(`连接已关闭（${code} ${reason}）`));
      }
    }
  }

  next(timeoutMs = 4000) {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    if (this.closed) {
      return Promise.reject(new Error(`连接已关闭（${this.closed.code} ${this.closed.reason}）`));
    }
    return new Promise((resolvePromise, rejectPromise) => {
      const waiter = { resolve: resolvePromise, reject: rejectPromise, timer: null };
      waiter.timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        rejectPromise(new Error('等待 WS 消息超时'));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  sendJson(obj) {
    this.socket.write(encodeFrame(0x1, JSON.stringify(obj)));
  }

  sendText(text) {
    this.socket.write(encodeFrame(0x1, text));
  }

  sendBinary(buf) {
    this.socket.write(encodeFrame(0x2, buf));
  }

  sendClose(code = 1000, reason = '') {
    const payload = Buffer.alloc(2 + Buffer.byteLength(reason));
    payload.writeUInt16BE(code, 0);
    payload.write(reason, 2, 'utf8');
    try { this.socket.write(encodeFrame(0x8, payload)); } catch { /* ignore */ }
  }

  destroy() {
    try { this.socket.destroy(); } catch { /* ignore */ }
  }
}

/**
 * 发起一次 WS 握手。
 *
 * @returns {Promise<{ok:true,ws:RawWs,accept:string}|{ok:false,status:number,body:string,json:any}>}
 *          失败时**不抛异常**：握手失败的状态码本身就是被测对象。
 */
function rawUpgrade({ token, deviceId = 'default', xff, origin, timeoutMs = 6000 } = {}) {
  const url = new URL(WS_BASE);
  if (token !== undefined && token !== null) url.searchParams.set('token', token);
  url.searchParams.set('deviceId', deviceId);
  const key = crypto.randomBytes(16).toString('base64');

  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolvePromise(value);
    };
    const fail = (err) => {
      if (settled) return;
      settled = true;
      rejectPromise(err);
    };

    const req = http.request({
      hostname: url.hostname,
      port: url.port || 80,
      path: `${url.pathname}${url.search}`,
      method: 'GET',
      headers: {
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Version': '13',
        'Sec-WebSocket-Key': key,
        ...(origin ? { Origin: origin } : {}),
        ...(xff ? { 'X-Forwarded-For': xff } : {}),
      },
      timeout: timeoutMs,
    });

    req.on('upgrade', (res, socket, head) => {
      const accept = res.headers['sec-websocket-accept'];
      const expected = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
      socket.setNoDelay(true);
      finish({ ok: true, ws: new RawWs(socket, head), accept, acceptOk: accept === expected });
    });

    req.on('response', (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => {
        let json = null;
        try { json = body ? JSON.parse(body) : null; } catch { /* ignore */ }
        finish({ ok: false, status: res.statusCode, body, json });
      });
      res.on('error', () => finish({ ok: false, status: res.statusCode, body, json: null }));
    });

    req.on('timeout', () => { req.destroy(new Error('握手超时')); });
    req.on('error', (err) => fail(err));
    req.end();
  });
}

/**
 * 握手并读掉第一帧 `connected`。
 * 失败时返回 `{ok:false,...}`；成功时返回 `{ok:true, ws, connected}`。
 */
async function openWs(opts) {
  const up = await rawUpgrade(opts);
  if (!up.ok) return up;
  const first = await up.ws.next(6000);
  if (first.kind !== 'message') {
    up.ws.destroy();
    return { ok: false, status: 0, body: `connected 前连接被关闭（${first.code} ${first.reason}）`, json: null };
  }
  return { ok: true, ws: up.ws, connected: first.json };
}

/** 关闭连接并给它一点时间走完服务端的 afterConnectionClosed */
async function closeWs(ws) {
  if (!ws) return;
  ws.sendClose(1000, 'client bye');
  await sleep(120);
  ws.destroy();
}

/** 等一个指定 type 的帧；其它帧被跳过（返回 skipped 便于排查）。 */
async function nextOfType(ws, types, timeoutMs = 5000) {
  const wanted = Array.isArray(types) ? types : [types];
  const deadline = Date.now() + timeoutMs;
  const skipped = [];
  for (;;) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      throw new Error(`等待 ${wanted.join('/')} 超时；期间收到 ${skipped.map((x) => x?.type || x).join(',') || '无'}`);
    }
    const item = await ws.next(remaining);
    if (item.kind === 'close') {
      throw new Error(`等待 ${wanted.join('/')} 时连接被关闭（${item.code} ${item.reason}）`);
    }
    if (item.kind !== 'message') {
      skipped.push(item.kind);
      continue;
    }
    if (wanted.includes(item.json?.type)) return item.json;
    skipped.push(item.json?.type);
  }
}

/**
 * 在窗口内断言**没有**收到某类帧。
 * 返回窗口内收到的帧类型列表（用于失败时展示「其实收到了什么」）。
 */
async function expectNoType(ws, type, windowMs = 600) {
  const deadline = Date.now() + windowMs;
  const seen = [];
  for (;;) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) return seen;
    let item;
    try {
      item = await ws.next(remaining);
    } catch {
      return seen; // 超时 = 没有新帧，正是期望
    }
    if (item.kind === 'close') {
      seen.push(`close:${item.code}`);
      return seen;
    }
    seen.push(item.json?.type ?? item.kind);
    if (item.json?.type === type) return seen;
  }
}

/** 一直读到关闭帧，返回 `{close, messages}`；用于 1009/1008 这类「连接会死」的用例。 */
async function drainUntilClose(ws, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  const messages = [];
  for (;;) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) return { close: null, messages };
    let item;
    try {
      item = await ws.next(remaining);
    } catch {
      return { close: null, messages };
    }
    if (item.kind === 'close') return { close: item, messages };
    if (item.kind === 'message') messages.push(item.json);
  }
}

// =================================================================
// 业务小工具
// =================================================================

const wsSend = (ws, obj) => ws.sendJson(obj);

async function joinRoom(ws, roomId, clientMsgId) {
  wsSend(ws, { type: 'room.join', clientMsgId, data: { roomId: String(roomId) } });
  return nextOfType(ws, 'room.join.ack');
}

const joinChk = (ack, expectedOwner) => ack?.data?.isOwner === expectedOwner;

function clearLimit(type, dimension, identifier, api) {
  rDel(`ml:limit:${type}:${dimension}:${identifier}:${api}`);
}

const WS_CHAT_LIMIT = (userId) => `ml:limit:ws-chat:user:${userId}:chat`;
const WS_DANMAKU_LIMIT = (userId) => `ml:limit:ws-danmaku:user:${userId}:danmaku`;

// =================================================================
// 主流程
// =================================================================

async function main() {
  console.log('\x1b[1m模块 04 验收：WebSocket 实时通信（docs/系分/04-WebSocket.md §8）\x1b[0m');
  console.log(`HTTP ${API_BASE}    WS ${WS_BASE}\nRedis ${REDIS_CLI}    房间 ${ROOM}    账号 ${ACCOUNT.a}/${ACCOUNT.b}/${ACCOUNT.c}`);
  if (!(await waitForBoot())) {
    console.error('应用未就绪，退出');
    process.exit(2);
  }

  // 先记一份共享 db0 里别的项目的键，收尾对比
  const rankingBefore = rankingKeys();

  const [A, B, C] = await Promise.all([loginAs(ACCOUNT.a), loginAs(ACCOUNT.b), loginAs(ACCOUNT.c)]);
  const admin = await loginAs(ADMIN.account, ADMIN.password);
  note(`用户 ID：A=${A.userId} B=${B.userId} C=${C.userId}`);

  let aWs = null;
  let bWs = null;
  let cWs = null;

  try {
    // =============================================================== §01 握手
    section('§01 握手鉴权与容量（FR-13-001 / EX-13-001-1~4）');

    const noToken = await rawUpgrade({ deviceId: 'no-token' });
    check('不带 token → HTTP 401（EX-13-001-1）',
      noToken.ok === false && noToken.status === 401, noToken.status || noToken.body);
    check('响应体业务码 = 40101 UNAUTHORIZED',
      noToken.ok === false && noToken.json?.code === 40101, noToken.json);

    const badToken = await rawUpgrade({ token: 'not-a-jwt', deviceId: 'bad-token' });
    check('token 无效 → HTTP 401 + 40102 TOKEN_INVALID（EX-13-001-2）',
      badToken.ok === false && badToken.status === 401 && badToken.json?.code === 40102,
      badToken.json || badToken.status);

    // 封禁：直接写 Redis 键（与 TokenSessionStore.ban 的落点一致），测完立刻删
    const banKey = `ml:auth:userban:${C.userId}`;
    redis('set', banKey, 'verify-04', 'ex', '120');
    const banned = await rawUpgrade({ token: C.token, deviceId: 'banned' });
    redis('del', banKey);
    check('封禁账号 → HTTP 403 + 20005 ACCOUNT_BANNED（EX-13-001-3）',
      banned.ok === false && banned.status === 403 && banned.json?.code === 20005,
      banned.json || banned.status);
    check('解封键已删除（否则后续用例会被误伤）', !rExists(banKey));

    // 单 IP 上限 20（BR-13-003）。用独占的合成 IP，与本机其它调试连接互不影响。
    const ipLimitIp = syntheticIp(250);
    const held = [];
    let rejectedAt = null;
    for (let i = 0; i < 21; i += 1) {
      const up = await openWs({ token: A.token, deviceId: `ipl-${i}`, xff: ipLimitIp });
      if (!up.ok) {
        rejectedAt = { at: i + 1, status: up.status, json: up.json };
        break;
      }
      held.push(up.ws);
    }
    check('同一 IP 前 20 条连接全部建立（max-connections-per-ip=20）',
      held.length === 20, `实际 ${held.length}`);
    check('第 21 条被拒 → HTTP 429 + 10004 RATE_LIMITED（EX-13-001-4）',
      rejectedAt?.at === 21 && rejectedAt?.status === 429 && rejectedAt?.json?.code === 10004,
      rejectedAt);
    for (const ws of held) await closeWs(ws);
    note('已释放 20 条容量测试连接；实例总上限 10000 不单独造，避免压垮本机');

    // =============================================================== §02 连接与协议
    section('§02 连接建立 / 心跳 / 错误帧（FR-13-008、§8.2~8.4）');

    const probeA = await openWs({ token: A.token, deviceId: 'a-d1' });
    check('合法 token 握手成功', probeA.ok === true, probeA.body);
    if (!probeA.ok) throw new Error(`无法建立主连接：${probeA.body}`);
    aWs = probeA.ws;
    const conn = probeA.connected;
    check('第一帧是 connected', conn?.type === 'connected', conn);
    check('connected.connectionId 非空', typeof conn?.data?.connectionId === 'string'
      && conn.data.connectionId.length > 0, conn?.data);
    check('connected.serverTime 与本机时钟相差 < 60s',
      Math.abs(Number(conn?.data?.serverTime) - Date.now()) < 60_000, conn?.data?.serverTime);
    check('connected.heartbeatInterval = 30000ms（PRD §8.2 的单位是毫秒）',
      conn?.data?.heartbeatInterval === 30_000, conn?.data?.heartbeatInterval);
    check('connected.maxMessageSize = 65536', conn?.data?.maxMessageSize === 65536,
      conn?.data?.maxMessageSize);
    check('connected.tokenExpireAt 是未来时间戳（JWT 30 分钟）',
      Number(conn?.data?.tokenExpireAt) > Date.now(), conn?.data?.tokenExpireAt);

    const firstUnread = await nextOfType(aWs, 'notify.unread');
    check('连上即推 notify.unread（BR-13-027），新用户为 0',
      firstUnread?.data?.unread === 0, firstUnread);

    aWs.sendJson({ type: 'ping', ts: 123456789 });
    const pong = await nextOfType(aWs, 'pong');
    check('ping → pong 且原样带回 ts', pong?.data?.ts === 123456789, pong);
    check('pong 带 serverTime', Number(pong?.data?.serverTime) > 0, pong?.data?.serverTime);

    aWs.sendJson({ type: 'no.such.type', clientMsgId: 'bad-type' });
    const typeErr = await nextOfType(aWs, 'error');
    check('未知 type → error 10001 且 context 指明哪条消息',
      typeErr?.data?.code === 10001 && typeErr?.data?.context?.type === 'no.such.type'
      && typeErr?.data?.context?.clientMsgId === 'bad-type', typeErr);
    check('错误帧不关闭连接（下一条 ping 仍可用）', await (async () => {
      aWs.sendJson({ type: 'ping', ts: 1 });
      return (await nextOfType(aWs, 'pong'))?.data?.ts === 1;
    })());

    aWs.sendText('{not json');
    const parseErr = await nextOfType(aWs, 'error');
    check('非法 JSON → error 10002 BODY_PARSE_ERROR', parseErr?.data?.code === 10002, parseErr);

    aWs.sendBinary(Buffer.from([0x01, 0x02, 0x03]));
    const binaryErr = await nextOfType(aWs, 'error');
    check('二进制帧 → error 10007 FILE_TYPE_UNSUPPORTED', binaryErr?.data?.code === 10007, binaryErr);

    // 超大消息：容器（65536）或业务兜底都会以 1009 结束连接。
    // 两种实现都符合契约，所以断言「连接以 1009 关闭或先收到 10001 再关闭」。
    const big = await openWs({ token: A.token, deviceId: 'a-big' });
    check('超大消息用例的连接已建立', big.ok === true, big.body);
    if (big.ok) {
      big.ws.sendText(JSON.stringify({
        type: 'room.chat',
        clientMsgId: 'too-big',
        data: { content: 'x'.repeat(70_000) },
      }));
      const { close, messages } = await drainUntilClose(big.ws, 8000);
      const sawParamError = messages.some((m) => m?.type === 'error' && m?.data?.code === 10001);
      check('超过 64KB 的消息被拒绝并以 1009 关闭（或先回 10001 再关）',
        close?.code === 1009 || sawParamError, { close, types: messages.map((m) => m?.type) });
      big.ws.destroy();
    }

    // =============================================================== §03 多设备
    section('§03 同设备替换 / 多设备并存（BR-13-001、EX-13-001-6）');

    const replacedOld = aWs;
    const aD1New = await openWs({ token: A.token, deviceId: 'a-d1' });
    check('同用户同 deviceId 再连一次成功', aD1New.ok === true, aD1New.body);
    const oldClose = await drainUntilClose(replacedOld, 4000);
    check('旧连接被关闭且 close code = 1008 POLICY_VIOLATION',
      oldClose.close?.code === 1008, oldClose.close);
    check('关闭原因说明「replaced by a newer connection」',
      /replaced by a newer/i.test(oldClose.close?.reason || ''), oldClose.close?.reason);
    aWs = aD1New.ws;

    const aD2 = await openWs({ token: A.token, deviceId: 'a-d2' });
    check('同用户不同 deviceId 可以并存（多设备）', aD2.ok === true, aD2.body);

    aWs.sendJson({ type: 'ping', ts: 21 });
    const pongD1 = await nextOfType(aWs, 'pong');
    check('d1 仍可用', pongD1?.data?.ts === 21, pongD1);

    aD2.ws.sendJson({ type: 'ping', ts: 22 });
    const pongD2 = await nextOfType(aD2.ws, 'pong');
    check('d2 也可用（两条连接互不干扰）', pongD2?.data?.ts === 22, pongD2);

    const sessD1 = rGet(`ml:ws:session:${A.userId}:a-d1`);
    const sessD2 = rGet(`ml:ws:session:${A.userId}:a-d2`);
    check('Redis 会话键每设备一条（D-04 的偏差），值形如 nodeId:connectionId',
      sessD1.endsWith(`:${aD1New.connected.data.connectionId}`) && sessD2.includes(':'),
      { sessD1, sessD2 });
    check('会话键 TTL 在 (0, 90] 内（BR-13-011）',
      rTtl(`ml:ws:session:${A.userId}:a-d1`) > 0
      && rTtl(`ml:ws:session:${A.userId}:a-d1`) <= 90,
      rTtl(`ml:ws:session:${A.userId}:a-d1`));

    await closeWs(aD2.ws);
    await sleep(250);
    check('设备连接断开后会话键被清除', !rExists(`ml:ws:session:${A.userId}:a-d2`));

    // =============================================================== §04 房间
    section('§04 入房 / 房主 / 成员进出（FR-13-002、BR-13-040）');

    const joinA = await joinRoom(aWs, ROOM, 'join-a');
    check('A 入房 ack：room.join.ack + clientMsgId 关联', joinA?.type === 'room.join.ack'
      && joinA?.clientMsgId === 'join-a', joinA);
    check('首个进房者成为房主（演示策略 D-05）',
      joinA?.data?.isOwner === true && joinA?.data?.ownerId === A.userId, joinA?.data);
    check('房间号按字符串下发（§8.3 防精度丢失）', joinA?.data?.roomId === ROOM, joinA?.data?.roomId);
    check('onlineCount = 1', joinA?.data?.onlineCount === 1, joinA?.data?.onlineCount);
    check('ack 内带权威播放态快照（新加入者首屏对齐）',
      joinA?.data?.snapshot && joinA.data.snapshot.roomId === ROOM, joinA?.data?.snapshot);
    check('房主占位键写入（24h，模块 05 接入后由 t_room.owner_id 取代）',
      rGet(`ml:room:owner:${ROOM}`) === A.userId, rGet(`ml:room:owner:${ROOM}`));

    const bProbe = await openWs({ token: B.token, deviceId: 'b-d1' });
    check('B 连接已建立', bProbe.ok === true, bProbe.body);
    bWs = bProbe.ws;
    const joinB = await joinRoom(bWs, ROOM, 'join-b');
    check('B 入房 ack：不是房主、ownerId 指向 A',
      joinB?.data?.isOwner === false && joinB?.data?.ownerId === A.userId, joinB?.data);
    check('B 的 onlineCount = 2', joinB?.data?.onlineCount === 2, joinB?.data?.onlineCount);

    const memberJoin = await nextOfType(aWs, 'room.member.join');
    check('A 收到 B 的 room.member.join，带 userId 与 onlineCount',
      memberJoin?.data?.userId === B.userId && memberJoin?.data?.onlineCount === 2, memberJoin);
    check('成员事件是业务帧（带 msgId，可按 EX-13-007-5 去重）',
      typeof memberJoin?.msgId === 'string' && memberJoin.msgId.length > 0, memberJoin?.msgId);

    // B 的第二台设备：房间内多连接。关闭它时不应广播 leave（用户没真正离开）。
    const bD2 = await openWs({ token: B.token, deviceId: 'b-d2' });
    check('B 的第二台设备连接成功', bD2.ok === true, bD2.body);
    const joinB2 = await joinRoom(bD2.ws, ROOM, 'join-b2');
    check('B 第二设备入房 ack 正常', joinB2?.type === 'room.join.ack', joinB2);
    await nextOfType(aWs, 'room.member.join');
    await closeWs(bD2.ws);
    const noLeave = await expectNoType(aWs, 'room.member.leave', 700);
    check('关闭 B 的第二台设备不广播 leave（同一用户仍有连接在房间）',
      !noLeave.includes('room.member.leave'), noLeave);

    const sessB = rGet(`ml:ws:session:${B.userId}:b-d1`);
    check('B 会话键存在', sessB.length > 0, sessB);
    check('房间在线集合含 A、B 两个 userId（按用户去重，不按连接）',
      rScard(`ml:room:online:${ROOM}`) === 2, rScard(`ml:room:online:${ROOM}`));
    check('online_count 与集合基数一致（不维护第二个真相源）',
      Number(redis('get', `ml:room:online_count:${ROOM}`)) === 2,
      redis('get', `ml:room:online_count:${ROOM}`));

    // =============================================================== §05 聊天
    section('§05 聊天 ack / 广播 / 幂等 / 拒绝原因（FR-13-003、BR-13-008/012/035）');

    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c1', data: { content: 'hello-ws' } });
    const chatAck = await nextOfType(aWs, 'room.chat.ack');
    check('发送方收到 room.chat.ack，clientMsgId 原样带回',
      chatAck?.clientMsgId === 'c1', chatAck);
    check('ack 的 messageId 是 19 位字符串（防 JS 精度丢失）',
      typeof chatAck?.data?.messageId === 'string' && /^\d{18,19}$/.test(chatAck.data.messageId),
      chatAck?.data?.messageId);
    check('ack 的 roomId 为字符串', chatAck?.data?.roomId === ROOM, chatAck?.data?.roomId);
    const c1Id = chatAck?.data?.messageId;

    const chatBroadcast = await nextOfType(bWs, 'room.chat');
    check('B 收到 room.chat 广播', chatBroadcast?.type === 'room.chat', chatBroadcast);
    check('广播 messageId 与 ack 一致', chatBroadcast?.data?.messageId === c1Id,
      { ack: c1Id, broadcast: chatBroadcast?.data?.messageId });
    check('广播带 userId / nickname / content / createTime',
      chatBroadcast?.data?.userId === A.userId
      && chatBroadcast?.data?.nickname === ACCOUNT.a
      && chatBroadcast?.data?.content === 'hello-ws'
      && Number(chatBroadcast?.data?.createTime) > 0, chatBroadcast?.data);
    check('广播带顶层 roomId 与 msgId（§8.1）',
      chatBroadcast?.roomId === ROOM && typeof chatBroadcast?.msgId === 'string',
      { roomId: chatBroadcast?.roomId, msgId: chatBroadcast?.msgId });
    check('发送方不会重复收到自己的广播（ack 已足够）',
      !(await expectNoType(aWs, 'room.chat', 400)).includes('room.chat'));

    // 幂等重放：同一个 clientMsgId 再发一次，回同一条 ack，且不再广播
    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c1', data: { content: 'hello-ws' } });
    const replayAck = await nextOfType(aWs, 'room.chat.ack');
    check('重复 clientMsgId → 原样重放上一次 ack（BR-13-035）',
      replayAck?.data?.messageId === c1Id, replayAck?.data?.messageId);
    check('重复提交不会二次广播', !(await expectNoType(bWs, 'room.chat', 500)).includes('room.chat'));

    aWs.sendJson({ type: 'room.chat', data: { content: 'no-id' } });
    const missingId = await nextOfType(aWs, 'room.chat.rejected');
    check('缺 clientMsgId → reason=missing_client_msg_id（BR-13-012）',
      missingId?.data?.reason === 'missing_client_msg_id', missingId?.data);

    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c-empty', data: { content: '   ' } });
    const empty = await nextOfType(aWs, 'room.chat.rejected');
    check('空白内容 → reason=empty', empty?.data?.reason === 'empty', empty?.data);

    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c-long', data: { content: 'x'.repeat(201) } });
    const tooLong = await nextOfType(aWs, 'room.chat.rejected');
    check('内容 > 200 字 → reason=too_long', tooLong?.data?.reason === 'too_long', tooLong?.data);

    // C 尚未入房
    const cProbe = await openWs({ token: C.token, deviceId: 'c-d1' });
    check('C 连接已建立', cProbe.ok === true, cProbe.body);
    cWs = cProbe.ws;
    cWs.sendJson({ type: 'room.chat', clientMsgId: 'c-no-room', data: { content: 'x' } });
    const notInRoom = await nextOfType(cWs, 'room.chat.rejected');
    check('未入房发言 → reason=not_in_room 且 roomId 为 null',
      notInRoom?.data?.reason === 'not_in_room' && notInRoom?.data?.roomId === null, notInRoom?.data);

    const joinC = await joinRoom(cWs, ROOM, 'join-c');
    check('C 入房成功', joinC?.type === 'room.join.ack', joinC);
    await nextOfType(aWs, 'room.member.join');

    // 黑名单：模块 05 才会写这个 Set，这里直接写 Redis 模拟运营动作
    redis('sadd', `ml:room:blacklist:${ROOM}`, C.userId);
    cWs.sendJson({ type: 'room.chat', clientMsgId: 'c-black', data: { content: 'x' } });
    const blacklisted = await nextOfType(cWs, 'room.chat.rejected');
    check('黑名单用户发言 → reason=blacklisted', blacklisted?.data?.reason === 'blacklisted',
      blacklisted?.data);
    redis('srem', `ml:room:blacklist:${ROOM}`, C.userId);

    // 禁言：Hash userId → 到期毫秒
    redis('hset', `ml:room:mute:${ROOM}`, C.userId, String(Date.now() + 120_000));
    cWs.sendJson({ type: 'room.chat', clientMsgId: 'c-mute', data: { content: 'x' } });
    const muted = await nextOfType(cWs, 'room.chat.rejected');
    check('禁言用户发言 → reason=muted 且 remainingSeconds 约 120',
      muted?.data?.reason === 'muted'
      && muted?.data?.remainingSeconds >= 110 && muted?.data?.remainingSeconds <= 121,
      muted?.data);
    redis('hdel', `ml:room:mute:${ROOM}`, C.userId);

    // 限流：20 条/分钟，第 21 条被拒
    clearLimit('ws-chat', 'user', C.userId, 'chat');
    let rlAcks = 0;
    for (let i = 1; i <= 21; i += 1) {
      cWs.sendJson({ type: 'room.chat', clientMsgId: `c-rl-${i}`, data: { content: `rl-${i}` } });
      const frame = await nextOfType(cWs, ['room.chat.ack', 'room.chat.rejected']);
      if (frame.type === 'room.chat.ack') rlAcks += 1;
      if (i === 21) {
        check('第 21 条聊天被限流 → reason=rate_limited（BR-13-012）',
          frame.type === 'room.chat.rejected' && frame.data?.reason === 'rate_limited', frame);
        check('限流拒绝带 retryAfterSeconds（1~60）',
          frame.data?.retryAfterSeconds >= 1 && frame.data?.retryAfterSeconds <= 60,
          frame.data?.retryAfterSeconds);
      }
    }
    check('前 20 条全部 ack（20 条/分钟）', rlAcks === 20, rlAcks);
    check('限流键落在 ml:limit:ws-chat:user:{id}:chat', rExists(WS_CHAT_LIMIT(C.userId)),
      WS_CHAT_LIMIT(C.userId));
    clearLimit('ws-chat', 'user', C.userId, 'chat');

    // 弹幕：不可靠消息，无 ack、含发送方
    cWs.sendJson({ type: 'room.danmaku', data: { content: 'danmaku-1' } });
    const dmC = await nextOfType(cWs, 'room.danmaku');
    check('弹幕广播包含发送方自己（没有 ack 可依赖）',
      dmC?.data?.userId === C.userId && dmC?.data?.messageType === 2, dmC?.data);
    const dmA = await nextOfType(aWs, 'room.danmaku');
    check('弹幕同样广播给房间内其他人', dmA?.data?.content === 'danmaku-1', dmA?.data);

    cWs.sendJson({ type: 'room.danmaku', data: { content: 'x'.repeat(51) } });
    const dmRejected = await nextOfType(cWs, 'room.danmaku.rejected');
    check('弹幕 > 50 字 → reason=too_long', dmRejected?.data?.reason === 'too_long',
      dmRejected?.data);

    // =============================================================== §06 控播
    section('§06 控播权限 / 版本单调 / 同步补偿（FR-13-004、BR-13-020/021/024）');

    bWs.sendJson({ type: 'room.play', clientMsgId: 'b-play', data: { songId: SEED_SONG } });
    const noPermission = await nextOfType(bWs, 'error');
    check('非房主控播 → 40006 ROOM_NO_PERMISSION（EX-13-004-1）',
      noPermission?.data?.code === 40006, noPermission?.data);
    check('错误帧的 context.clientMsgId 可用于关联失败消息',
      noPermission?.data?.context?.clientMsgId === 'b-play', noPermission?.data?.context);

    aWs.sendJson({
      type: 'room.switch',
      clientMsgId: 'p1',
      data: { songId: SEED_SONG, position: 0 },
    });
    const switchAck = await nextOfType(aWs, 'room.switch.ack');
    const v1 = switchAck?.data?.version;
    check('切歌 ack 带 version', Number.isInteger(v1) && v1 > 0, switchAck?.data);
    const switchBc = await nextOfType(aWs, 'room.switch');
    check('切歌广播同版本、权威字段齐全',
      switchBc?.data?.version === v1
      && String(switchBc?.data?.songId) === SEED_SONG
      && switchBc?.data?.songName === '晴天'
      && switchBc?.data?.isPlaying === true
      && switchBc?.data?.operatorId === A.userId, switchBc?.data);
    check('切歌广播同时到达普通成员 B',
      (await nextOfType(bWs, 'room.switch'))?.data?.version === v1);

    aWs.sendJson({
      type: 'room.switch',
      clientMsgId: 'p1',
      data: { songId: SEED_SONG, position: 0 },
    });
    const switchReplay = await nextOfType(aWs, 'room.switch.ack');
    check('控播幂等：重复 clientMsgId 回同一 version，不重新升版',
      switchReplay?.data?.version === v1, switchReplay?.data);
    check('控播重复提交不二次广播',
      !(await expectNoType(bWs, 'room.switch', 500)).includes('room.switch'));

    aWs.sendJson({ type: 'room.pause', clientMsgId: 'p2', data: {} });
    const pauseAck = await nextOfType(aWs, 'room.pause.ack');
    const v2 = pauseAck?.data?.version;
    check('暂停 version 递增', Number.isInteger(v2) && v2 > v1, { v1, v2 });
    const pauseBc = await nextOfType(aWs, 'room.pause');
    check('暂停广播 isPlaying=false，position 不回退到旧值',
      pauseBc?.data?.isPlaying === false && pauseBc?.data?.position >= 0
      && pauseBc?.data?.version === v2, pauseBc?.data);
    await nextOfType(bWs, 'room.pause');

    aWs.sendJson({ type: 'room.seek', clientMsgId: 'p3', data: { position: 42, isPlaying: true } });
    const seekAck = await nextOfType(aWs, 'room.seek.ack');
    const v3 = seekAck?.data?.version;
    check('跳转 version 继续递增', Number.isInteger(v3) && v3 > v2, { v2, v3 });
    const seekBc = await nextOfType(aWs, 'room.seek');
    check('跳转广播 position=42、isPlaying=true',
      seekBc?.data?.position === 42 && seekBc?.data?.isPlaying === true, seekBc?.data);
    await nextOfType(bWs, 'room.seek');

    check('Redis 版本键与广播版本一致',
      Number(redis('get', `ml:room:ver:${ROOM}`)) === v3, redis('get', `ml:room:ver:${ROOM}`));
    check('Redis 播放态 Hash 记录 songId/position/isPlaying（模块 05 的正式契约）',
      rHget(`ml:room:play:${ROOM}`, 'songId') === SEED_SONG
      && rHget(`ml:room:play:${ROOM}`, 'position') === '42'
      && rHget(`ml:room:play:${ROOM}`, 'isPlaying') === 'true',
      redis('hgetall', `ml:room:play:${ROOM}`));

    bWs.sendJson({ type: 'room.sync.request' });
    const syncB = await nextOfType(bWs, 'room.sync');
    check('room.sync 返回权威版本与进度（BR-13-024）',
      syncB?.data?.version === v3 && syncB?.data?.position === 42
      && syncB?.data?.isPlaying === true, syncB?.data);

    const playstate = await call('GET', `/ws/rooms/${ROOM}/playstate`, { token: B.token });
    check('HTTP playstate 与 room.sync 同源同值（首屏对齐的备用通道）',
      playstate.json?.code === 0 && playstate.json?.data?.version === v3
      && playstate.json?.data?.position === 42, playstate.json);

    aWs.sendJson({ type: 'room.switch', clientMsgId: 'p-bad', data: { songId: '1' } });
    const badSong = await nextOfType(aWs, 'error');
    check('不存在的歌曲 → 歌曲域错误码（30001/30002/30003）',
      [30001, 30002, 30003].includes(badSong?.data?.code), badSong?.data);

    // =============================================================== §07 通知
    section('§07 通知：在线推送 / 离线拉取 / 未读与已读（FR-13-005、BR-13-027/028/031）');

    const pushA = await call('POST', '/ws/admin/notifications/push', {
      token: admin.token,
      body: {
        userIds: [A.userId, B.userId],
        notifyType: 1,
        title: '系统公告-在线',
        content: '模块 04 验收',
      },
    });
    check('管理端批量推送成功（created=2）', pushA.json?.code === 0
      && pushA.json?.data?.created === 2, pushA.json);

    const notifyA = await nextOfType(aWs, 'notify.system');
    check('在线用户 A 收到 notify.system（notifyType=1 → notify.system）',
      notifyA?.data?.title === '系统公告-在线'
      && typeof notifyA?.data?.notificationId === 'string', notifyA?.data);
    const unreadA = await nextOfType(aWs, 'notify.unread');
    check('随后收到 notify.unread = 1', unreadA?.data?.unread === 1, unreadA?.data);
    check('通知帧带 msgId（客户端可去重）',
      typeof notifyA?.msgId === 'string' && notifyA.msgId.length > 0, notifyA?.msgId);

    // B 此刻在线，也会收到；先把它关掉，再验证「离线只落库、不推送」。
    const notifyBOnline = await nextOfType(bWs, 'notify.system');
    check('在线用户 B 同样收到推送', notifyBOnline?.data?.title === '系统公告-在线',
      notifyBOnline?.data);
    await nextOfType(bWs, 'notify.unread');
    await closeWs(bWs);
    bWs = null;
    await expectNoType(aWs, 'room.member.leave', 100); // 只清一下队列，不在这里断言

    const unreadHttpA = await call('GET', '/ws/notifications/unread', { token: A.token });
    check('HTTP 未读数以 DB 为准 = 1', unreadHttpA.json?.data?.unread === 1, unreadHttpA.json);

    const listA = await call('GET', '/ws/notifications?pageNum=1&pageSize=20', { token: A.token });
    const nA = listA.json?.data?.list?.[0];
    check('通知列表最新在前，形状含 id/title/content/isRead/createTime',
      listA.json?.code === 0 && nA?.title === '系统公告-在线'
      && typeof nA?.id === 'string' && nA?.isRead === 0 && typeof nA?.createTime === 'string',
      nA);

    // 离线用户 B：重连后只有未读数，没有历史推送（BR-13-028：离线消息走 HTTP 拉取）
    const bAgain = await openWs({ token: B.token, deviceId: 'b-d1' });
    check('离线用户 B 重连成功', bAgain.ok === true, bAgain.body);
    bWs = bAgain.ws;
    const offlineUnread = await nextOfType(bWs, 'notify.unread');
    check('重连后 notify.unread = 1（离线期间的通知已落库）',
      offlineUnread?.data?.unread === 1, offlineUnread?.data);
    const noReplay = await expectNoType(bWs, 'notify.system', 600);
    check('离线期间的通知不会在重连时补推（由 HTTP 列表负责）',
      !noReplay.includes('notify.system'), noReplay);

    const listB = await call('GET', '/ws/notifications?pageNum=1&pageSize=20', { token: B.token });
    check('离线用户通过 HTTP 拉到了那条通知（EX-13-005-3）',
      listB.json?.data?.list?.[0]?.title === '系统公告-在线', listB.json?.data?.list?.[0]);

    // 单条已读
    const markOne = await call('PUT', `/ws/notifications/${nA.id}/read`, { token: A.token });
    check('标记单条已读成功', markOne.json?.code === 0, markOne.json);
    check('已读后未读数为 0', (await call('GET', '/ws/notifications/unread',
      { token: A.token })).json?.data?.unread === 0);


    const pushLike = await call('POST', '/ws/admin/notifications/push', {
      token: admin.token,
      body: { userIds: [A.userId], notifyType: 3, title: '点赞通知', content: 'x' },
    });
    check('再次推送（notifyType=3）成功', pushLike.json?.data?.created === 1, pushLike.json);
    const likeFrame = await nextOfType(aWs, 'notify.like');
    check('notifyType=3 映射为 notify.like（§8.2 映射表）',
      likeFrame?.data?.title === '点赞通知', likeFrame?.data);
    await nextOfType(aWs, 'notify.unread');

    const readAll = await call('PUT', '/ws/notifications/read-all', { token: A.token });
    check('全部已读返回 changed ≥ 1', readAll.json?.code === 0
      && readAll.json?.data?.changed >= 1, readAll.json);
    check('全部已读后 unread = 0（DB 重算并回写缓存）',
      (await call('GET', '/ws/notifications/unread', { token: A.token })).json?.data?.unread === 0);

    // =============================================================== §08 补偿
    section('§08 断线补偿：messageId 游标（BR-13-037）');

    await sleep(1200); // 落库是异步的（wsMessageExecutor）
    const allMsgs = await call('GET', `/ws/rooms/${ROOM}/messages?sinceId=0&limit=200`,
      { token: A.token });
    const msgList = allMsgs.json?.data || [];
    check('补偿接口 code=0 且返回数组', allMsgs.json?.code === 0 && Array.isArray(msgList),
      allMsgs.json);
    check('聊天/弹幕已落库（至少含 c1 与一条限流消息）',
      msgList.some((m) => String(m.id) === String(c1Id)), msgList.length);
    check('消息 ID 一律字符串（雪花 ID 超过 JS 安全整数）',
      msgList.every((m) => typeof m.id === 'string'), msgList[0]);
    check('结果按 ID 升序（游标可续拉）',
      msgList.every((m, i) => i === 0 || BigInt(m.id) > BigInt(msgList[i - 1].id)),
      msgList.slice(0, 3).map((m) => m.id));
    const lastSeenId = msgList.length ? String(msgList[msgList.length - 1].id) : '0';

    // B 断线；A 继续说话；B 用最后看到的 ID 补齐
    await closeWs(bWs);
    bWs = null;
    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c2', data: { content: 'offline-1' } });
    const c2Ack = await nextOfType(aWs, 'room.chat.ack');
    aWs.sendJson({ type: 'room.chat', clientMsgId: 'c3', data: { content: 'offline-2' } });
    const c3Ack = await nextOfType(aWs, 'room.chat.ack');
    await sleep(1000);

    const sinceRes = await call('GET',
      `/ws/rooms/${ROOM}/messages?sinceId=${lastSeenId}&limit=200`, { token: B.token });
    const sinceIds = (sinceRes.json?.data || []).map((m) => String(m.id));
    check('sinceId 只返回游标之后的消息，且正好是断线期间的 2 条',
      sinceIds.length === 2 && sinceIds[0] === c2Ack?.data?.messageId
      && sinceIds[1] === c3Ack?.data?.messageId, sinceIds);
    check('补偿内容是断线期间那两条', (sinceRes.json?.data || [])
      .map((m) => m.content).join(',') === 'offline-1,offline-2', sinceRes.json?.data);

    const bCompensate = await openWs({ token: B.token, deviceId: 'b-d1' });
    check('B 补偿后重连成功', bCompensate.ok === true, bCompensate.body);
    bWs = bCompensate.ws;
    const joinB2nd = await joinRoom(bWs, ROOM, 'join-b-again');
    check('重连后重新入房并拿到当前快照（version 未变）',
      joinB2nd?.data?.snapshot?.version === v3, joinB2nd?.data?.snapshot);
    await nextOfType(aWs, 'room.member.join');
    const noAutoReplay = await expectNoType(bWs, 'room.chat', 600);
    check('重连本身不自动补发断线消息（客户端按游标走 HTTP，避免两套补发打架）',
      !noAutoReplay.includes('room.chat'), noAutoReplay);

    // =============================================================== §09 Redis
    section('§09 键空间与共享 Redis 隔离');

    check('会话映射存在（A/B）',
      rExists(`ml:ws:session:${A.userId}:a-d1`) && rExists(`ml:ws:session:${B.userId}:b-d1`));
    check('节点负载键存在且 TTL ≤ 60s',
      listKeys('ml:ws:node:*').length > 0 && rTtl(listKeys('ml:ws:node:*')[0]) > 0
      && rTtl(listKeys('ml:ws:node:*')[0]) <= 60,
      listKeys('ml:ws:node:*'));
    check('聊天幂等账本存在且 TTL ≤ 300s（BR-13-035）',
      rExists(`ml:ws:dedup:${A.userId}:c1`)
      && rTtl(`ml:ws:dedup:${A.userId}:c1`) > 0
      && rTtl(`ml:ws:dedup:${A.userId}:c1`) <= 300,
      rTtl(`ml:ws:dedup:${A.userId}:c1`));
    check('未读缓存键存在且带 TTL（BR-13-031）',
      rExists(`ml:notify:unread:${B.userId}`) && rTtl(`ml:notify:unread:${B.userId}`) > 0,
      { value: rGet(`ml:notify:unread:${B.userId}`), ttl: rTtl(`ml:notify:unread:${B.userId}`) });
    check('房间在线集合、播放态、版本号均已写入',
      rExists(`ml:room:online:${ROOM}`) && rExists(`ml:room:play:${ROOM}`)
      && rExists(`ml:room:ver:${ROOM}`));
    check('用户当前房间键 C 指向本房间（TTL 90s）',
      rGet(`ml:room:user:${C.userId}`) === ROOM
      && rTtl(`ml:room:user:${C.userId}`) > 0
      && rTtl(`ml:room:user:${C.userId}`) <= 90,
      { value: rGet(`ml:room:user:${C.userId}`), ttl: rTtl(`ml:room:user:${C.userId}`) });
    const rankingAfter = rankingKeys();
    check(`共享 db0 的 ranking:* 键数未变（${rankingBefore.length} → ${rankingAfter.length}）`,
      rankingAfter.length === rankingBefore.length,
      { before: rankingBefore.length, after: rankingAfter.length });

    // =============================================================== §10 指标
    section('§10 运行指标（FR-13-010）');

    const stats = await call('GET', '/ws/stats', { token: admin.token });
    const st = stats.json?.data || {};
    check('GET /ws/stats code=0', stats.json?.code === 0, stats.json);
    check('指标名与 PRD 对应（current/total/in/out/rooms/pending）',
      Number.isInteger(st.ws_connections_current) && Number.isInteger(st.ws_connections_total)
      && Number.isInteger(st.ws_messages_in_total) && Number.isInteger(st.ws_messages_out_total)
      && Number.isInteger(st.ws_rooms) && Number.isInteger(st.ws_message_executor_pending),
      st);
    check('当前连接数 ≥ 3（A/B/C）', st.ws_connections_current >= 3, st.ws_connections_current);
    check('累计连接数覆盖了本轮的反复重连（≥ 10）', st.ws_connections_total >= 10,
      st.ws_connections_total);
    check('入站/出站计数都已增长', st.ws_messages_in_total > 0 && st.ws_messages_out_total > 0, st);
    check('本机房间数 ≥ 1，且 roomsDetail 指向本轮房间',
      st.ws_rooms >= 1 && Number(st.roomsDetail?.[ROOM]) >= 1, st.roomsDetail);
    check('ws_node_id 非空（多实例排障用）',
      typeof st.ws_node_id === 'string' && st.ws_node_id.length > 0, st.ws_node_id);
    check('落库线程池无积压（pending 为 0）', st.ws_message_executor_pending === 0,
      st.ws_message_executor_pending);

    const statsForbidden = await call('GET', '/ws/stats', { token: A.token });
    check('普通用户访问 /ws/stats → 403 + 40301（运营/管理员专属）',
      statsForbidden.status === 403 && statsForbidden.json?.code === 40301,
      { status: statsForbidden.status, body: statsForbidden.json });

    // =============================================================== §11 空闲
    section('§11 空闲连接清理（BR-13-007）');
    if (WITH_IDLE) {
      note('本用例会等 100s（idle-timeout=90s，扫描周期 10s）');
      const idle = await openWs({ token: C.token, deviceId: 'idle-probe' });
      if (idle.ok) {
        const { close } = await drainUntilClose(idle.ws, 115_000);
        check('90s 心跳超时后服务端以 4002 关闭连接', close?.code === 4002, close);
        idle.ws.destroy();
      }
      // 上面的连接会占用 C 的房间？没有 join，不影响。
    } else {
      skip.push('BR-13-007 的 90s 空闲超时：默认跳过（要等 100s）。'
        + '补跑：node scripts/verify-04.mjs idle');
    }

    // =============================================================== §12 跨实例
    section('§12 跨实例广播（D-03：Redis Pub/Sub）');
    skip.push('BR-13-040 跨实例投递需要第二个实例（WS_NODE_ID 不同、端口不同）。'
      + '本机只启动了一个实例；WsClusterBus 的发布/订阅逻辑与 originNodeId 去重由代码审查覆盖，'
      + '接口行为已由本脚本的单实例部分验证');
    note('启动第二个实例后可用 WS_BASE=http://localhost:8082/ws 再跑一遍房间/通知用例');
  } finally {
    // ---------------------------------------------------------------- 清理
    await closeWs(aWs).catch(() => {});
    await closeWs(bWs).catch(() => {});
    await closeWs(cWs).catch(() => {});
    await sleep(400);

    // 只删本轮自己造的房间/用户相关的键；ml: 前缀之外一律不碰
    const cleanupKeys = [
      `ml:room:owner:${ROOM}`,
      `ml:room:online:${ROOM}`,
      `ml:room:online_count:${ROOM}`,
      `ml:room:play:${ROOM}`,
      `ml:room:ver:${ROOM}`,
      `ml:room:blacklist:${ROOM}`,
      `ml:room:mute:${ROOM}`,
      `ml:notify:unread:${A.userId}`,
      `ml:notify:unread:${B.userId}`,
      `ml:notify:unread:${C.userId}`,
      `ml:room:user:${A.userId}`,
      `ml:room:user:${B.userId}`,
      `ml:room:user:${C.userId}`,
      WS_CHAT_LIMIT(C.userId),
      WS_DANMAKU_LIMIT(C.userId),
    ];
    rDel(...cleanupKeys);
    for (const userId of [A.userId, B.userId, C.userId]) {
      const dedupKeys = listKeys(`ml:ws:dedup:${userId}:*`);
      const sessionKeys = listKeys(`ml:ws:session:${userId}:*`);
      if (dedupKeys.length) rDel(...dedupKeys);
      if (sessionKeys.length) rDel(...sessionKeys);
    }

    if (MYSQL_PWD) {
      try {
        mysql(`DELETE FROM newmusic.t_room_message WHERE room_id = ${ROOM};`);
        mysql(`DELETE FROM newmusic.t_notification WHERE user_id IN `
          + `(${A.userId}, ${B.userId}, ${C.userId});`);
        const roomRows = mysql(`SELECT COUNT(*) FROM newmusic.t_room_message WHERE room_id = ${ROOM};`);
        const notifyRows = mysql(`SELECT COUNT(*) FROM newmusic.t_notification WHERE user_id IN `
          + `(${A.userId}, ${B.userId}, ${C.userId});`);
        check('测试数据已从 t_room_message / t_notification 删除',
          roomRows === '0' && notifyRows === '0', { roomRows, notifyRows });
      } catch (e) {
        note(`测试数据清理失败（不影响断言）：${e.message}`);
      }
    } else {
      skip.push('测试数据清理：MYSQL_PASSWORD 未设置，t_room_message / t_notification 里会留下'
        + `本轮数据（房间 ${ROOM}，用户 ${A.userId}/${B.userId}/${C.userId}）。补跑：`
        + 'MYSQL_PASSWORD=<本机密码> node scripts/verify-04.mjs');
    }

    const rankingFinally = rankingKeys();
    check(`收尾后共享 db0 的 ranking:* 键数仍未变（${rankingBefore.length} → ${rankingFinally.length}）`,
      rankingFinally.length === rankingBefore.length,
      { before: rankingBefore.length, after: rankingFinally.length });
  }

  // ---------------------------------------------------------------- 汇总
  section('§13 本轮未覆盖的项（逐条说明原因，不做无意义的绿）');
  skip.push('AC 中涉及「浏览器行为」的部分（visibilitychange 立即重连、软/硬校准的实际播放效果、'
    + '通知气泡 UI）在 web/scripts/test-ws.mjs 锁定了纯函数参数，页面走查仍属人工项');
  skip.push('ACK 超时重发（5s + 最多 2 次）依赖真实网络抖动；客户端规则由 test-ws.mjs 断言，'
    + '服务端的幂等账本已在本脚本 §05 用「重复 clientMsgId」直接验证');
  for (const s of skip) note(`跳过｜${s}`);

  console.log('\n' + '─'.repeat(66));
  console.log(`模块 04：\x1b[32m通过 ${pass}\x1b[0m  \x1b[31m失败 ${fail}\x1b[0m  跳过 ${skip.length}`);
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
