/**
 * WebSocket 的纯计算部分（模块 04 T4xx）。
 *
 * 这里**不碰 window / WebSocket / 定时器**，只保留「给定输入算出输出」的逻辑：
 * 重连退避、消息去重、播放进度推算、漂移分级。`wsClient.ts` 负责 I/O，
 * `scripts/test-ws.mjs` 用 Node 直接 import 本文件做断言（零依赖，同模块 07 的做法）。
 *
 * 把这些函数抽出来的原因不是「为了测试」——它们本来就是 PRD 里写死的规则
 * （BR-13-033/021/022、EX-13-007-5），放在纯函数里才能逐条对照数字。
 */

/**
 * PRD FR-13-006 / 模块 13 BR-22-077：指数退避序列，上限 30s。
 *
 * 模块 13 补充了 **±20% 抖动**：所有客户端同时断线时不能在同一毫秒一起重连。
 */
export const RECONNECT_DELAYS: readonly number[] = [1000, 2000, 4000, 8000, 16000, 30000];

/** 退避抖动比例（BR-22-077） */
export const RECONNECT_JITTER = 0.2;

/** BR-13-039：连续失败 10 次后停止自动重连 */
export const MAX_RECONNECT_ATTEMPTS = 10;

/** 心跳与 pong 超时（服务端会在 connected 帧里下发心跳间隔，这里给兜底值） */
export const DEFAULT_HEARTBEAT_INTERVAL = 30_000;
export const PONG_TIMEOUT = 10_000;
export const MAX_MISSED_PONGS = 2;

/** 服务端 ack 等待时长（BR-13-035 / FR-13-006 的「5s」） */
export const ACK_TIMEOUT = 5_000;
export const MAX_SEND_RETRIES = 2;

export type WsStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

/**
 * 下一次重连延迟；返回 `null` 表示已达到 10 次上限，应停止自动重连。
 *
 * 抖动落在 `[base × 0.8, base × 1.2]`，但仍然 `≤ 30s` 封顶（`random()=1` 时
 * 30000 的 +20% 会被夹回 30000）。向下取整，测试断言边界更容易。
 *
 * @param attempt 已失败次数（0 表示第一次断线）
 * @param random  注入随机源
 */
export function reconnectDelay(
  attempt: number,
  random: () => number = Math.random,
): number | null {
  if (attempt >= MAX_RECONNECT_ATTEMPTS) return null;
  const index = Math.min(Math.max(0, attempt), RECONNECT_DELAYS.length - 1);
  const base = RECONNECT_DELAYS[index];
  const factor = 1 - RECONNECT_JITTER + random() * RECONNECT_JITTER * 2;
  return Math.min(30_000, Math.floor(base * factor));
}

/**
 * 按 msgId 去重（BR-13-008 / EX-13-007-5）。
 *
 * 用 Map 的插入顺序做 LRU 式淘汰：只保留最近 `capacity` 个 ID。
 * 不设上限的话，一个长连接跑一天会把整天的消息 ID 都留在内存里。
 */
export class MessageDeduper {
  private readonly seenIds = new Set<string>();

  private readonly order: string[] = [];

  private readonly capacity: number;

  // 不用「构造器参数属性」：Node 的 type stripping 不支持它，测试脚本会 import 失败
  constructor(capacity = 500) {
    this.capacity = capacity;
  }

  /** @returns true 表示重复消息，调用方应丢弃 */
  isDuplicate(msgId: string | undefined | null): boolean {
    if (!msgId) return false;
    if (this.seenIds.has(msgId)) return true;
    this.seenIds.add(msgId);
    this.order.push(msgId);
    while (this.order.length > this.capacity) {
      const oldest = this.order.shift();
      if (oldest !== undefined) this.seenIds.delete(oldest);
    }
    return false;
  }

  reset(): void {
    this.seenIds.clear();
    this.order.length = 0;
  }

  get size(): number {
    return this.seenIds.size;
  }
}

export interface ServerPlayState {
  position?: number;
  serverTime?: number;
  isPlaying?: boolean;
}

/**
 * 由权威状态推算「此刻应播放到的秒数」（PRD FR-13-004 的核心算法）。
 *
 * 暂停时直接返回 position；播放中才加 `(now - serverTime)`。
 * 网络时延补偿由调用方决定是否叠加——PRD 把它标为「精度要求高时启用」，
 * 这里不擅自加，避免两处各补一次导致过量。
 */
export function computeServerPosition(state: ServerPlayState | null | undefined,
  now: number = Date.now()): number {
  if (!state) return 0;
  const position = Math.max(0, state.position ?? 0);
  if (!state.isPlaying) return position;
  const elapsed = Math.max(0, (now - (state.serverTime ?? now)) / 1000);
  return position + elapsed;
}

export type DriftAction = 'none' | 'soft' | 'hard';

/**
 * 漂移分级（BR-13-022）：≤0.5s 不动；0.5~1.5s 软校准；>1.5s 硬校准。
 *
 * 边界取「大于」而不是「大于等于」：PRD 写的是「偏差 ≤ 0.5s 不校准」，
 * 所以 0.5 本身属于不校准，1.5 本身属于软校准。
 */
export function driftAction(
  currentTime: number,
  target: number,
  softThreshold = 0.5,
  hardThreshold = 1.5,
): DriftAction {
  const drift = Math.abs(currentTime - target);
  if (drift > hardThreshold) return 'hard';
  if (drift > softThreshold) return 'soft';
  return 'none';
}

/** 软校准的倍速；PRD 用的是 ±2%，这里统一由函数给出，避免页面里散落魔数 */
export function softCalibrationRate(currentTime: number, target: number): number {
  return target >= currentTime ? 1.02 : 0.98;
}

/** 只应用 version 更大的消息（BR-13-021 / EX-13-004-2） */
export function shouldApplyVersion(
  currentVersion: number | null | undefined,
  incomingVersion: number | null | undefined,
): boolean {
  if (incomingVersion === null || incomingVersion === undefined) return false;
  if (currentVersion === null || currentVersion === undefined) return true;
  return incomingVersion > currentVersion;
}

/** token 是否进入「即将过期」窗口（服务端 auth.expiring 的客户端兜底判断） */
export function isAuthExpiring(
  expireAt: number | null | undefined,
  now: number = Date.now(),
  thresholdMs = 5 * 60 * 1000,
): boolean {
  if (!expireAt) return false;
  const remaining = expireAt - now;
  return remaining > 0 && remaining <= thresholdMs;
}

/**
 * 拼 WS 地址。token 走 query（浏览器 WebSocket 不能设置 Authorization 头），
 * 因此这里不用 URLSearchParams 之外的任何拼接方式，确保 token 被正确转义。
 */
export function buildWsUrl(baseUrl: string, token: string, deviceId: string): string {
  const url = new URL(baseUrl);
  url.searchParams.set('token', token);
  url.searchParams.set('deviceId', deviceId);
  return url.toString();
}
