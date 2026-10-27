import {
  ACK_TIMEOUT,
  DEFAULT_HEARTBEAT_INTERVAL,
  MAX_MISSED_PONGS,
  MAX_SEND_RETRIES,
  MessageDeduper,
  buildWsUrl,
  reconnectDelay,
  type WsStatus,
} from './wsCore';
import {
  CLOCK_RESYNC_INTERVAL_MS,
  CLOCK_SYNC_BURST,
  CLOCK_SYNC_INTERVAL_MS,
  WEAK_HEARTBEAT_INTERVAL,
  WEAK_RTT_MS,
  appendClockSample,
  clockSample,
  pickClockOffset,
  type ClockSample,
} from './roomSync';

/**
 * WebSocket 客户端（模块 04 FR-13-001/002/006/007）。
 *
 * 这一层只干 I/O：建连、心跳、重连、可靠消息重发。**不碰 Zustand、不渲染 UI**，
 * 状态变化通过回调交给 `wsStore`。纯规则（退避序列、去重、版本比较）在 `wsCore.ts`。
 *
 * <p>关于 token：浏览器原生 WebSocket 不能带 Authorization 头，所以走 query。
 * 每次重连都调用 `getToken()` 重新取——HTTP 层刷新过 token 后，WS 必须用新的那个，
 * 否则重连会一直 401。日志里也从不打印完整 URL（它会带 token）。
 */

export interface WsEnvelope<T = Record<string, unknown>> {
  type: string;
  msgId?: string;
  clientMsgId?: string;
  roomId?: string;
  ts?: number;
  data?: T;
}

export interface ReliableHandlers {
  onAck?: (data: Record<string, unknown>) => void;
  /** 业务拒绝（如 `room.chat.rejected`），reason 见协议表 */
  onReject?: (reason: string, data: Record<string, unknown>) => void;
  onError?: (code: number, message: string) => void;
  /** 重试耗尽仍无 ack */
  onGiveUp?: () => void;
}

export interface WsClientOptions {
  /** 形如 `ws://localhost:5173/ws`；不传 query */
  url: string;
  deviceId: string;
  getToken: () => string | null;
  onStatus?: (status: WsStatus) => void;
  onMessage?: (message: WsEnvelope) => void;
  /** 每次连上都会触发（首次 + 重连），用于重新订阅房间 */
  onOpen?: () => void;
  onClose?: (code: number, reason: string) => void;
  /** 连续 10 次失败，停止自动重连 */
  onGiveUp?: () => void;
  onAuthExpiring?: (data: { expireAt: number; remainingSeconds: number }) => void;
  /** 每次进入重连退避时回调（attempt 从 1 开始），用于「网络不稳定」提示（BR-22-083） */
  onReconnectAttempt?: (attempt: number) => void;
  /** 时钟同步结果（模块 13 FR-22-001）；`degraded=true` 表示全部超时 */
  onClockSync?: (state: ClockSyncState) => void;
}

export interface ClockSyncState {
  offsetMs: number;
  rttMs: number;
  /** RTT > 500ms：心跳降到 45s（BR-22-076） */
  weak: boolean;
  degraded: boolean;
}

interface Pending {
  type: string;
  data: Record<string, unknown>;
  attempts: number;
  timer: ReturnType<typeof setTimeout> | null;
  handlers: ReliableHandlers;
}

/** 默认 WS 地址：dev 下由 Vite 代理 `/ws`，生产由同域网关转发 */
function defaultWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}/ws`;
}

let clientMsgSeq = 0;

function newClientMsgId(): string {
  clientMsgSeq += 1;
  return `c-${Date.now().toString(36)}-${clientMsgSeq.toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

export class WsClient {
  private socket: WebSocket | null = null;

  private status: WsStatus = 'idle';

  /** 已连续失败次数；连上就归零 */
  private attempt = 0;

  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;

  /** 服务端 connected 帧下发的基础心跳间隔 */
  private heartbeatBase = DEFAULT_HEARTBEAT_INTERVAL;

  /** 当前这一轮心跳里没收到 pong 的次数 */
  private missedPongs = 0;

  // -------------------- 模块 13：时钟同步与弱网自适应 --------------------

  private clockSamples: ClockSample[] = [];

  private clockBurstTimers: ReturnType<typeof setTimeout>[] = [];

  private clockResyncTimer: ReturnType<typeof setInterval> | null = null;

  private clockDegradeTimer: ReturnType<typeof setTimeout> | null = null;

  private lastRttMs = 0;

  private weakNetwork = false;

  private readonly deduper = new MessageDeduper(500);

  private readonly pending = new Map<string, Pending>();

  /** 用户主动 disconnect 后不再自动重连 */
  private stopped = true;

  private readonly visibilityHandler: () => void;

  constructor(private readonly options: WsClientOptions) {
    this.visibilityHandler = () => {
      // BR-13-038：切回前台时若已断线，立即重连，不等退避
      if (document.visibilityState === 'visible' && !this.stopped && !this.isOpen()) {
        this.reconnectNow(false);
      }
    };
  }

  get currentStatus(): WsStatus {
    return this.status;
  }

  /** 开始连接；重复调用无副作用 */
  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.attempt = 0;
    document.addEventListener('visibilitychange', this.visibilityHandler);
    this.connect();
  }

  /** 主动断开（登出、卸载），不会再自动重连 */
  stop(): void {
    this.stopped = true;
    this.clearReconnectTimer();
    this.stopHeartbeat();
    this.stopClockSync();
    this.failAllPending('连接已关闭');
    this.closeSocket(1000, 'client stop');
    document.removeEventListener('visibilitychange', this.visibilityHandler);
    this.setStatus('idle');
  }

  /** 「连接失败，点击重试」：重置退避计数后立即重连 */
  manualReconnect(): void {
    this.stopped = false;
    this.attempt = 0;
    document.addEventListener('visibilitychange', this.visibilityHandler);
    this.reconnectNow(true);
  }

  /** 用最新 token 重连：收到 `auth.expiring` 后刷新完 token 调用 */
  reconnect(): void {
    if (this.stopped) return;
    this.attempt = 0;
    this.reconnectNow(true);
  }

  // ------------------------------------------------------------------ 发送 API

  /** 可靠消息：5s 无 ack 用**同一个 clientMsgId** 重发（BR-13-035） */
  sendReliable(type: string, data: Record<string, unknown>, handlers: ReliableHandlers = {}): string {
    const clientMsgId = newClientMsgId();
    const frame = { type, clientMsgId, data: { ...data, clientMsgId }, ts: Date.now() };
    this.pending.set(clientMsgId, { type, data, attempts: 0, timer: null, handlers });
    if (!this.sendFrame(frame)) {
      // 没连上时不要假装已发送：立刻告知调用方，UI 才能回滚乐观渲染
      this.pending.delete(clientMsgId);
      handlers.onError?.(-1, '连接已断开，请稍后重试');
      return clientMsgId;
    }
    this.armAckTimer(clientMsgId);
    return clientMsgId;
  }

  /** 不可靠消息（弹幕/心跳）：不重试、不去重（BR-13-036） */
  sendUnreliable(type: string, data: Record<string, unknown>): boolean {
    return this.sendFrame({ type, data, ts: Date.now() });
  }

  joinRoom(roomId: string, password?: string, handlers?: ReliableHandlers): string {
    // 密码房在 WS 入房时带密码；重连时由 wsStore 保存的密码重放
    return this.sendReliable('room.join', compact({ roomId, password }), handlers);
  }

  leaveRoom(): boolean {
    return this.sendUnreliable('room.leave', {});
  }

  chat(content: string, handlers?: ReliableHandlers): string {
    return this.sendReliable('room.chat', { content }, handlers);
  }

  danmaku(content: string): boolean {
    return this.sendUnreliable('room.danmaku', { content });
  }

  play(songId?: string, position?: number, handlers?: ReliableHandlers): string {
    return this.sendReliable('room.play', compact({ songId, position }), handlers);
  }

  pause(position?: number, handlers?: ReliableHandlers): string {
    return this.sendReliable('room.pause', compact({ position }), handlers);
  }

  seek(position: number, isPlaying?: boolean, handlers?: ReliableHandlers): string {
    return this.sendReliable('room.seek', compact({ position, isPlaying }), handlers);
  }

  switchSong(songId: string, position?: number, handlers?: ReliableHandlers): string {
    return this.sendReliable('room.switch', compact({ songId, position }), handlers);
  }

  requestSync(): boolean {
    return this.sendUnreliable('room.sync.request', {});
  }

  // ------------------------------------------------------------------ 连接生命周期

  private connect(): void {
    const token = this.options.getToken();
    if (!token) {
      // 未登录不建立连接（调用方本就不该 start，这里只是防御）
      this.setStatus('idle');
      return;
    }
    this.setStatus(this.attempt === 0 ? 'connecting' : 'reconnecting');
    let url: string;
    try {
      url = buildWsUrl(this.options.url || defaultWsUrl(), token, this.options.deviceId);
    } catch {
      this.setStatus('closed');
      this.options.onGiveUp?.();
      return;
    }

    let socket: WebSocket;
    try {
      socket = new WebSocket(url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;

    socket.onopen = () => {
      if (this.socket !== socket) return;
      // connected 帧到达前先不改 status：服务端可能因实例满立刻关连接
    };

    socket.onmessage = (event) => {
      if (this.socket !== socket) return;
      this.handleRaw(event.data);
    };

    socket.onerror = () => {
      // 具体原因由 onclose 的 code/reason 给出；这里不重复处理
    };

    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.stopHeartbeat();
      this.stopClockSync();
      this.options.onClose?.(event.code, event.reason);
      if (this.stopped) return;
      this.scheduleReconnect();
    };
  }

  private handleRaw(raw: unknown): void {
    if (typeof raw !== 'string') return;
    let message: WsEnvelope;
    try {
      message = JSON.parse(raw) as WsEnvelope;
    } catch {
      return;
    }
    if (!message || typeof message.type !== 'string') return;

    // msgId 去重（EX-13-007-5）：跨节点广播/重放都可能让同一帧到达两次
    if (message.msgId && this.deduper.isDuplicate(message.msgId)) {
      return;
    }

    if (message.type === 'connected') {
      this.onConnected(message);
    } else if (message.type === 'pong') {
      this.missedPongs = 0;
    } else if (message.type === 'ROOM_SYNC_ACK') {
      this.handleClockAck((message.data ?? {}) as Record<string, unknown>);
    } else if (message.type === 'auth.expiring') {
      const data = (message.data ?? {}) as { expireAt?: number; remainingSeconds?: number };
      this.options.onAuthExpiring?.({
        expireAt: data.expireAt ?? 0,
        remainingSeconds: data.remainingSeconds ?? 0,
      });
    }

    if (message.clientMsgId) {
      this.settlePending(message);
    } else if (message.type === 'error') {
      // 错误帧把 clientMsgId 放在 data.context 里（服务端 §8.4），顶层没有
      const context = ((message.data ?? {}) as { context?: { clientMsgId?: string } }).context;
      if (context?.clientMsgId) {
        this.settlePending({ ...message, clientMsgId: context.clientMsgId });
      }
    }
    this.options.onMessage?.(message);
  }

  private onConnected(message: WsEnvelope): void {
    this.attempt = 0;
    this.missedPongs = 0;
    this.deduper.reset();
    const data = (message.data ?? {}) as { heartbeatInterval?: number };
    // 服务端下发毫秒（PRD §8.2 示例为 30000）；给个下限防止配置成 0 导致打满 CPU
    this.heartbeatBase = Math.max(5_000, data.heartbeatInterval ?? DEFAULT_HEARTBEAT_INTERVAL);
    this.weakNetwork = false;
    this.startHeartbeat();
    // BR-22-003：连上后立即做 3 次时钟同步，之后每 5 分钟一次
    this.startClockSync();
    this.setStatus('open');
    this.resendPending();
    this.options.onOpen?.();
  }

  // ------------------------------------------------------------------ 时钟同步

  /** 连接建立/重连成功后调用：3 次突发 + 5 分钟周期校准 */
  private startClockSync(): void {
    this.stopClockSync();
    this.clockSamples = [];
    for (let i = 0; i < CLOCK_SYNC_BURST; i += 1) {
      const timer = setTimeout(() => {
        this.sendClockRequest();
      }, i * CLOCK_SYNC_INTERVAL_MS);
      this.clockBurstTimers.push(timer);
    }
    this.clockResyncTimer = setInterval(() => {
      if (this.isOpen()) this.sendClockRequest();
    }, CLOCK_RESYNC_INTERVAL_MS);
    // EX-22-001-5：全部超时就标记降级，用本地时间继续（降低同步精度而不是停摆）
    this.clockDegradeTimer = setTimeout(() => {
      this.clockDegradeTimer = null;
      if (this.clockSamples.length === 0) {
        this.options.onClockSync?.({
          offsetMs: 0, rttMs: 0, weak: false, degraded: true,
        });
      }
    }, 10_000);
  }

  private stopClockSync(): void {
    this.clockBurstTimers.forEach((timer) => clearTimeout(timer));
    this.clockBurstTimers = [];
    if (this.clockResyncTimer !== null) {
      clearInterval(this.clockResyncTimer);
      this.clockResyncTimer = null;
    }
    if (this.clockDegradeTimer !== null) {
      clearTimeout(this.clockDegradeTimer);
      this.clockDegradeTimer = null;
    }
  }

  private sendClockRequest(): void {
    // clientTime 会被服务端原样回显，用它算 RTT
    this.sendUnreliable('ROOM_SYNC_REQ', { clientTime: Date.now() });
  }

  private handleClockAck(data: Record<string, unknown>): void {
    const clientTime = Number(data.clientTime);
    const serverTime = Number(data.serverTime);
    if (!Number.isFinite(clientTime) || !Number.isFinite(serverTime)) return;
    const sample = clockSample(clientTime, serverTime, Date.now());
    this.clockSamples = appendClockSample(this.clockSamples, sample);
    this.lastRttMs = sample.rttMs;

    if (this.clockDegradeTimer !== null) {
      clearTimeout(this.clockDegradeTimer);
      this.clockDegradeTimer = null;
    }

    const weak = this.lastRttMs > WEAK_RTT_MS;
    if (weak !== this.weakNetwork) {
      // BR-22-076：RTT > 500ms 时心跳 30s → 45s，恢复后降回来
      this.weakNetwork = weak;
      this.startHeartbeat();
    }
    this.options.onClockSync?.({
      offsetMs: pickClockOffset(this.clockSamples),
      rttMs: this.lastRttMs,
      weak,
      degraded: false,
    });
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    const delay = reconnectDelay(this.attempt);
    if (delay === null) {
      // BR-13-039：10 次后停下，由 UI 提示「点击重试」
      this.setStatus('closed');
      this.options.onGiveUp?.();
      return;
    }
    this.attempt += 1;
    this.options.onReconnectAttempt?.(this.attempt);
    this.setStatus('reconnecting');
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.stopped) this.connect();
    }, delay);
  }

  /** 立即重连（跳过退避）；`resetDedupe` 用于手动重试后重新接收 */
  private reconnectNow(resetAttempts: boolean): void {
    if (resetAttempts) this.attempt = 0;
    this.clearReconnectTimer();
    this.stopHeartbeat();
    this.closeSocket(1000, 'reconnect');
    this.connect();
  }

  // ------------------------------------------------------------------ 心跳

  private startHeartbeat(): void {
    this.stopHeartbeat();
    // BR-22-076：弱网下心跳间隔延长到 45s（不能低于服务端下发的基线）
    const interval = this.weakNetwork
      ? Math.max(this.heartbeatBase, WEAK_HEARTBEAT_INTERVAL)
      : this.heartbeatBase;
    this.heartbeatTimer = setInterval(() => {
      if (!this.isOpen()) return;
      if (this.missedPongs >= MAX_MISSED_PONGS) {
        // BR-13-010：连续 2 次没 pong 视为断线，主动重连
        this.forceReconnect();
        return;
      }
      this.missedPongs += 1;
      this.sendFrame({ type: 'ping', ts: Date.now() });
    }, interval);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.missedPongs = 0;
  }

  private forceReconnect(): void {
    // 主动 close 后 onclose 会走正常的退避流程；这里不直接 connect，避免两条路径并发
    this.closeSocket(4000, 'heartbeat timeout');
  }

  // ------------------------------------------------------------------ 可靠消息

  private armAckTimer(clientMsgId: string): void {
    const entry = this.pending.get(clientMsgId);
    if (!entry) return;
    if (entry.timer !== null) clearTimeout(entry.timer);
    entry.timer = setTimeout(() => {
      const current = this.pending.get(clientMsgId);
      if (!current) return;
      current.timer = null;
      if (current.attempts >= MAX_SEND_RETRIES) {
        this.pending.delete(clientMsgId);
        current.handlers.onGiveUp?.();
        return;
      }
      current.attempts += 1;
      // 同一个 clientMsgId 重发：服务端去重表会重放上一次的 ack
      if (this.sendFrame({
        type: current.type,
        clientMsgId,
        data: { ...current.data, clientMsgId },
        ts: Date.now(),
      })) {
        this.armAckTimer(clientMsgId);
      }
      // 发送失败则保持 pending，等重连后的 resendPending()
    }, ACK_TIMEOUT);
  }

  /** 重连成功后把未确认的可靠消息全部重发（不增加重试次数） */
  private resendPending(): void {
    this.pending.forEach((entry, clientMsgId) => {
      if (entry.timer !== null) {
        clearTimeout(entry.timer);
        entry.timer = null;
      }
      if (entry.attempts >= MAX_SEND_RETRIES) {
        this.pending.delete(clientMsgId);
        entry.handlers.onGiveUp?.();
        return;
      }
      if (this.sendFrame({
        type: entry.type,
        clientMsgId,
        data: { ...entry.data, clientMsgId },
        ts: Date.now(),
      })) {
        this.armAckTimer(clientMsgId);
      }
    });
  }

  private settlePending(message: WsEnvelope): void {
    const clientMsgId = message.clientMsgId as string;
    const entry = this.pending.get(clientMsgId);
    if (!entry) return;
    const data = (message.data ?? {}) as Record<string, unknown>;
    const isAck = message.type.endsWith('.ack');
    const isRejected = message.type.endsWith('.rejected');
    const isError = message.type === 'error';
    if (!isAck && !isRejected && !isError) return;

    this.pending.delete(clientMsgId);
    if (entry.timer !== null) clearTimeout(entry.timer);

    if (isAck) {
      entry.handlers.onAck?.(data);
    } else if (isRejected) {
      entry.handlers.onReject?.(String(data.reason ?? 'rejected'), data);
    } else {
      const context = (data.context ?? {}) as { clientMsgId?: string };
      if (context.clientMsgId !== clientMsgId) {
        // error 帧的 context 才对应该消息；不匹配说明是别的错误，交还给 onMessage
        this.pending.set(clientMsgId, entry);
        return;
      }
      entry.handlers.onError?.(Number(data.code ?? 0), String(data.message ?? '发送失败'));
    }
  }

  private failAllPending(reason: string): void {
    this.pending.forEach((entry, clientMsgId) => {
      if (entry.timer !== null) clearTimeout(entry.timer);
      this.pending.delete(clientMsgId);
      entry.handlers.onError?.(-1, reason);
    });
  }

  // ------------------------------------------------------------------ helpers

  private sendFrame(frame: Record<string, unknown>): boolean {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    try {
      socket.send(JSON.stringify(frame));
      return true;
    } catch {
      return false;
    }
  }

  private isOpen(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
  }

  private setStatus(status: WsStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.options.onStatus?.(status);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private closeSocket(code: number, reason: string): void {
    const socket = this.socket;
    if (!socket) return;
    try {
      socket.close(code, reason);
    } catch {
      /* ignore */
    }
  }
}

/** 去掉 undefined 字段，避免把 `{"position": null}` 这类空值发给服务端 */
function compact(data: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  Object.entries(data).forEach(([key, value]) => {
    if (value !== undefined && value !== null) result[key] = value;
  });
  return result;
}
