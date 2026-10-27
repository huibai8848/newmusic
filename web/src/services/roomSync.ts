/**
 * 音乐房播放同步的纯计算部分（模块 13 FR-22-001 / FR-22-002）。
 *
 * 与 `wsCore.ts` 同一个理由：这里面全是 PRD 写死数字的规则（500ms / 2000ms /
 * 8% / 5 秒 / 60 秒 / 60%），放在纯函数里才能逐条对照，也才能在 Node 里用
 * `scripts/test-room-sync.mjs` 直接断言。本文件**不碰 window / Audio / 定时器**。
 */

/** 权威播放态的状态值（与后端 `RoomPlayStateVO` 一致） */
export const PLAY_STATUS = {
  PLAYING: 1,
  PAUSED: 2,
  BUFFERING: 3,
  ENDED: 4,
} as const;

/** BR-22-003：连接后立即做 3 次时钟同步，间隔 500ms；之后每 5 分钟一次 */
export const CLOCK_SYNC_BURST = 3;
export const CLOCK_SYNC_INTERVAL_MS = 500;
export const CLOCK_RESYNC_INTERVAL_MS = 5 * 60 * 1000;

/** BR-22-004：取 RTT 最小的 5 个样本，偏移取中位数 */
export const CLOCK_SAMPLE_LIMIT = 5;

/** BR-22-013 / 014 / 015 / 016 */
export const SOFT_THRESHOLD_MS = 500;
export const HARD_THRESHOLD_MS = 2000;
export const MAX_RATE_ADJUST = 0.08;
export const SOFT_CALIBRATION_MS = 5000;
/** BR-22-012：软校准每 10 秒检查一次；硬校准每 30 秒一次 */
export const SOFT_CHECK_INTERVAL_MS = 10_000;
export const HARD_CHECK_INTERVAL_MS = 30_000;

/** BR-22-076：RTT > 500ms 视为弱网，心跳 30s → 45s */
export const WEAK_RTT_MS = 500;
export const WEAK_HEARTBEAT_INTERVAL = 45_000;

export interface RoomPlaySnapshot {
  songId?: string | null;
  durationMs?: number | null;
  playStatus?: number | null;
  startPositionMs?: number | null;
  stateUpdateTimeMs?: number | null;
  /** 百分比，100 = 1.0x（房间内固定 100） */
  playbackRate?: number | null;
  playStateVersion?: number | null;
}

/**
 * 「此刻应该播放到哪里」——前端校准与服务端暂停基准共用同一套公式。
 *
 * <pre>
 * expected = startPositionMs + max(0, serverNow - stateUpdateTimeMs) * rate / 100
 * </pre>
 *
 * 非播放态直接返回起始位置（暂停/缓冲/结束不该继续累加）。
 * `stateUpdateTimeMs` 缺失时只返回起始位置，而不是把它当成 0 后累加到时长上限。
 */
export function expectedPositionMs(
  state: RoomPlaySnapshot | null | undefined,
  serverNowMs: number,
): number {
  if (!state) return 0;
  const duration = Math.max(0, state.durationMs ?? 0);
  const start = Math.max(0, state.startPositionMs ?? 0);
  const clamp = (value: number): number => {
    const bounded = duration > 0 ? Math.min(value, duration) : value;
    return Math.max(0, bounded);
  };
  if ((state.playStatus ?? PLAY_STATUS.PAUSED) !== PLAY_STATUS.PLAYING) {
    return clamp(start);
  }
  const update = state.stateUpdateTimeMs ?? 0;
  if (update <= 0) return clamp(start);
  const rate = Math.max(1, state.playbackRate ?? 100);
  const elapsed = (Math.max(0, serverNowMs - update) * rate) / 100;
  return clamp(start + elapsed);
}

export interface ClockSample {
  /** serverTime - 本地往返中点 */
  offsetMs: number;
  rttMs: number;
}

/**
 * NTP 风格的时间样本（PRD §10）。
 *
 * @param clientTime 客户端发送 `ROOM_SYNC_REQ` 的本地时间
 * @param serverTime 服务端 `ROOM_SYNC_ACK` 里的时间
 * @param receivedAt 客户端收到 ack 的本地时间
 */
export function clockSample(
  clientTime: number,
  serverTime: number,
  receivedAt: number,
): ClockSample {
  const rttMs = Math.max(0, receivedAt - clientTime);
  return {
    rttMs,
    offsetMs: Math.round(serverTime - (clientTime + receivedAt) / 2),
  };
}

/**
 * BR-22-004：按 RTT 升序只保留 5 个样本，再对偏移取中位数。
 *
 * 调用方每次收到 ack 都调用它；返回的数组已经排好序，可直接作为下一次输入。
 */
export function appendClockSample(
  samples: readonly ClockSample[],
  sample: ClockSample,
  limit: number = CLOCK_SAMPLE_LIMIT,
): ClockSample[] {
  const safeLimit = Math.max(1, limit);
  return [...samples, sample]
    .filter((item) => Number.isFinite(item.offsetMs) && Number.isFinite(item.rttMs))
    .sort((a, b) => a.rttMs - b.rttMs)
    .slice(0, safeLimit);
}

/** 当前样本下的时钟偏移；没有样本时返回 0（等价于「用本地时间」） */
export function pickClockOffset(samples: readonly ClockSample[]): number {
  if (samples.length === 0) return 0;
  const offsets = samples.map((s) => s.offsetMs).sort((a, b) => a - b);
  return Math.round(offsets[Math.floor(offsets.length / 2)]);
}

/** 服务端「现在」= 本地时间 + 时钟偏移 */
export function serverNow(localNowMs: number, offsetMs: number): number {
  return localNowMs + offsetMs;
}

export type CalibrationType = 'none' | 'soft' | 'hard';

export interface CalibrationInput {
  /** actual - expected；正数 = 本地快了 */
  driftMs: number;
  /** 期望位置的绝对值，硬校准要跳到它 */
  expectedMs: number;
  /** 音频尚未加载完成：跳过（EX-22-002-1） */
  audioReady: boolean;
  /** 缓冲中：暂停校准（BR-22-018 / BR-22-080） */
  buffering?: boolean;
  /** 页面不可见：暂停校准，恢复可见时由调用方传 forceHard（BR-22-021） */
  hidden?: boolean;
  /** 正在软校准：不叠加（BR-22-016） */
  calibrating?: boolean;
  /** 页面恢复可见 / 网络恢复：立即硬校准（BR-22-021 / BR-22-085） */
  forceHard?: boolean;
}

export interface CalibrationPlan {
  type: CalibrationType;
  /** 软校准目标倍速；其它情况恒为 1 */
  rate: number;
  /** 软校准持续时长；其它情况为 0 */
  durationMs: number;
  /** 硬校准目标位置（毫秒）；其它情况为 null */
  seekToMs: number | null;
}

function noCalibration(): CalibrationPlan {
  return { type: 'none', rate: 1, durationMs: 0, seekToMs: null };
}

/**
 * 漂移处置决策。
 *
 * 顺序有意如此：先排除「不能校准」的状态，再看强制硬校准，最后按阈值分级。
 * 软校准进行中只允许硬校准抢占——否则每 10 秒叠一次变速，5 秒窗口还没结束
 * 就被下一次改掉，`playbackRate` 会一直停在非 1.0。
 */
export function planCalibration(input: CalibrationInput): CalibrationPlan {
  if (!input.audioReady) return noCalibration();
  if (input.buffering) return noCalibration();
  if (input.hidden) return noCalibration();
  if (input.forceHard) {
    return {
      type: 'hard',
      rate: 1,
      durationMs: 0,
      seekToMs: Math.max(0, input.expectedMs),
    };
  }
  if (input.calibrating) return noCalibration();

  const drift = Math.abs(input.driftMs);
  if (drift > HARD_THRESHOLD_MS) {
    return {
      type: 'hard',
      rate: 1,
      durationMs: 0,
      seekToMs: Math.max(0, input.expectedMs),
    };
  }
  if (drift > SOFT_THRESHOLD_MS) {
    return {
      type: 'soft',
      rate: softRate(input.driftMs),
      durationMs: SOFT_CALIBRATION_MS,
      seekToMs: null,
    };
  }
  return noCalibration();
}

/**
 * 软校准倍速：5 秒内消掉偏差，最多 ±8%。
 *
 * 本地慢了（drift < 0）要加速 → 1.08；本地快了 → 0.92。
 * 结果保留三位小数，避免浮点尾巴让测试与埋点数值不可读。
 */
export function softRate(
  driftMs: number,
  durationMs: number = SOFT_CALIBRATION_MS,
  maxAdjust: number = MAX_RATE_ADJUST,
): number {
  const window = Math.max(1, durationMs);
  const adjust = -driftMs / window;
  const bounded = Math.max(-maxAdjust, Math.min(maxAdjust, adjust));
  return Math.round((1 + bounded) * 1000) / 1000;
}

/** 距离下一次软/硬检查是否已经够久 */
export function dueForCheck(
  lastCheckMs: number,
  nowMs: number,
  intervalMs: number,
): boolean {
  return nowMs - lastCheckMs >= intervalMs;
}

/** 投票剩余秒数（向上取整，发起瞬间显示 60 而不是 59） */
export function voteRemainingSeconds(
  endTimeMs: number | null | undefined,
  nowMs: number,
): number {
  if (!endTimeMs || !Number.isFinite(endTimeMs)) return 0;
  return Math.max(0, Math.ceil((endTimeMs - nowMs) / 1000));
}

/** 投票是否仍在进行：status=1 且未到截止时间 */
export function isVoteActive(
  vote: { status?: number | null; endTime?: number | null } | null | undefined,
  nowMs: number,
): boolean {
  if (!vote || vote.status !== 1) return false;
  return voteRemainingSeconds(vote.endTime, nowMs) > 0;
}

/**
 * 展示用的「还差几票通过」。
 *
 * 通过条件为三者取或：≥60% 在线；≥5 票且 ≥50%；房主一票。展示取前两者中
 * 更早能达成的那个——房主一票无法从人数推导，不参与计算。
 */
export function requiredAgreeCount(onlineCount: number): number {
  const online = Math.max(1, Math.floor(onlineCount));
  const byRatio = Math.ceil(online * 0.6);
  const byFloor = Math.max(5, Math.ceil(online * 0.5));
  return Math.max(1, Math.min(byRatio, byFloor));
}

/** 投票结果文案（ROOM_VOTE_RESULT 的 message 缺省时兜底） */
export function voteResultText(status: number | null | undefined, passed: boolean): string {
  if (status === 4) return '投票已取消';
  if (passed) return '投票通过，已跳过当前歌曲';
  if (status === 3) return '投票未通过，当前歌曲继续播放';
  return '';
}
