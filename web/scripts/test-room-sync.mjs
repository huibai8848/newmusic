#!/usr/bin/env node
/**
 * 音乐房同步纯规则断言（模块 13 FR-22-001 / FR-22-002 / FR-22-004）。
 *
 * 与 `test-ws.mjs` 同一套路：零依赖，Node 原生擦除 TS 类型直接 import
 * `src/services/roomSync.ts`。那个文件不碰 window / Audio / 定时器，所以能在 Node 里跑。
 *
 * 重点覆盖 PRD 写死数字的地方（AC-22-101 ~ AC-22-105 / AC-22-108）：
 * 期望位置公式、时钟偏移（最小 RTT 中位数）、500/2000ms 两条边界、
 * ±8% 变速、5 秒窗口、校准不叠加、缓冲/后台跳过、投票阈值。
 */

import {
  CLOCK_SAMPLE_LIMIT,
  HARD_THRESHOLD_MS,
  MAX_RATE_ADJUST,
  PLAY_STATUS,
  SOFT_CALIBRATION_MS,
  SOFT_THRESHOLD_MS,
  WEAK_HEARTBEAT_INTERVAL,
  WEAK_RTT_MS,
  appendClockSample,
  clockSample,
  dueForCheck,
  expectedPositionMs,
  isVoteActive,
  pickClockOffset,
  planCalibration,
  requiredAgreeCount,
  serverNow,
  softRate,
  voteRemainingSeconds,
  voteResultText,
} from '../src/services/roomSync.ts';

let failed = 0;
let passed = 0;

function eq(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    failed += 1;
    console.log(`✗ ${name}\n    实际: ${a}\n    期望: ${e}`);
  } else {
    passed += 1;
    console.log(`✓ ${name}`);
  }
}

function ok(name, cond, detail) {
  if (cond) {
    passed += 1;
    console.log(`✓ ${name}`);
  } else {
    failed += 1;
    console.log(`✗ ${name}${detail !== undefined ? `\n    ${detail}` : ''}`);
  }
}

const NOW = 1_700_000_000_000;

// ---------------------------------------------------------------------------
// 1. 期望位置公式（FR-22-001）
// ---------------------------------------------------------------------------
console.log('\n— 期望播放位置 —');
eq('无状态 → 0', expectedPositionMs(null, NOW), 0);
eq('播放中：起始 35s + 经过 5s', expectedPositionMs({
  playStatus: PLAY_STATUS.PLAYING,
  startPositionMs: 35_000,
  stateUpdateTimeMs: NOW - 5_000,
  playbackRate: 100,
  durationMs: 200_000,
}, NOW), 40_000);
eq('暂停：位置不随时间增长', expectedPositionMs({
  playStatus: PLAY_STATUS.PAUSED,
  startPositionMs: 35_000,
  stateUpdateTimeMs: NOW - 60_000,
  durationMs: 200_000,
}, NOW), 35_000);
eq('缓冲：与暂停一样停在起始位置', expectedPositionMs({
  playStatus: PLAY_STATUS.BUFFERING,
  startPositionMs: 12_000,
  stateUpdateTimeMs: NOW - 60_000,
}, NOW), 12_000);
eq('结束：停在最后位置', expectedPositionMs({
  playStatus: PLAY_STATUS.ENDED,
  startPositionMs: 199_000,
  stateUpdateTimeMs: NOW - 60_000,
  durationMs: 200_000,
}, NOW), 199_000);
eq('倍速 100 = 1.0x', expectedPositionMs({
  playStatus: PLAY_STATUS.PLAYING,
  startPositionMs: 0,
  stateUpdateTimeMs: NOW - 10_000,
  playbackRate: 100,
}, NOW), 10_000);
eq('状态更新时间在未来 → 不倒退', expectedPositionMs({
  playStatus: PLAY_STATUS.PLAYING,
  startPositionMs: 42_000,
  stateUpdateTimeMs: NOW + 5_000,
  durationMs: 200_000,
}, NOW), 42_000);
eq('超过时长 → 夹到时长', expectedPositionMs({
  playStatus: PLAY_STATUS.PLAYING,
  startPositionMs: 199_000,
  stateUpdateTimeMs: NOW - 10_000,
  durationMs: 200_000,
}, NOW), 200_000);
eq('缺少 stateUpdateTimeMs → 只返回起始位置（不是 0 或时长）',
  expectedPositionMs({ playStatus: PLAY_STATUS.PLAYING, startPositionMs: 7_000 }, NOW), 7_000);
eq('脏数据负位置 → 0', expectedPositionMs({ playStatus: PLAY_STATUS.PAUSED, startPositionMs: -1 }, NOW), 0);

// ---------------------------------------------------------------------------
// 2. 时钟同步（BR-22-003 / BR-22-004）
// ---------------------------------------------------------------------------
console.log('\n— 时钟同步 —');
eq('RTT 40ms、服务端领先 2s：offset = server - 往返中点',
  clockSample(NOW - 40, NOW + 1_980, NOW).offsetMs, 2_000);
eq('样本的 RTT 来自本地收发时间', clockSample(NOW - 40, NOW, NOW).rttMs, 40);

const samples = appendClockSample([], { offsetMs: 100, rttMs: 30 });
eq('第一个样本直接采用', pickClockOffset(samples), 100);
eq('样本上限 5', CLOCK_SAMPLE_LIMIT, 5);
{
  const many = [
    { offsetMs: 5, rttMs: 90 },
    { offsetMs: 9, rttMs: 80 },
    { offsetMs: 1, rttMs: 70 },
    { offsetMs: 7, rttMs: 60 },
    { offsetMs: 3, rttMs: 50 },
    { offsetMs: 1000, rttMs: 900 },
  ].reduce((acc, s) => appendClockSample(acc, s), []);
  eq('只保留 RTT 最小的 5 个（高 RTT 的 1000 被丢弃）', many.length, 5);
  eq('最小 RTT 集合的偏移中位数 = 5',
    pickClockOffset(many), 5);
}
{
  // 偶数个时取下标 floor(n/2)（偏大的中位数），结果确定、可复现
  const even = [
    { offsetMs: 10, rttMs: 30 },
    { offsetMs: 30, rttMs: 10 },
  ];
  eq('偶数样本取偏大的中位数', pickClockOffset(even), 30);
}
eq('没有样本 → offset 0（用本地时间，EX-22-001-5）', pickClockOffset([]), 0);
eq('serverNow = 本地 + offset', serverNow(NOW, 250), NOW + 250);

// ---------------------------------------------------------------------------
// 3. 校准阈值与软校准（BR-22-013 ~ BR-22-016）
// ---------------------------------------------------------------------------
console.log('\n— 校准阈值 —');
const READY = { audioReady: true, expectedMs: 50_000 };
eq('无偏差 → none', planCalibration({ ...READY, driftMs: 0 }).type, 'none');
eq('偏差恰好 500ms → none（"超过 500 才软校准"）',
  planCalibration({ ...READY, driftMs: SOFT_THRESHOLD_MS }).type, 'none');
eq('偏差 501ms → soft',
  planCalibration({ ...READY, driftMs: SOFT_THRESHOLD_MS + 1 }).type, 'soft');
eq('偏差恰好 2000ms → soft（"超过 2000 才硬校准"）',
  planCalibration({ ...READY, driftMs: HARD_THRESHOLD_MS }).type, 'soft');
eq('偏差 2001ms → hard',
  planCalibration({ ...READY, driftMs: HARD_THRESHOLD_MS + 1 }).type, 'hard');
eq('负方向同样分级（本地慢 1.5s → soft）',
  planCalibration({ ...READY, driftMs: -1_500 }).type, 'soft');

console.log('\n— 软校准倍速 —');
eq('本地慢 1s → 1.08 追赶',
  planCalibration({ ...READY, driftMs: -1_000 }).rate, 1.08);
eq('本地快 1s → 0.92 放慢',
  planCalibration({ ...READY, driftMs: 1_000 }).rate, 0.92);
eq('偏差 600ms → 变速约 8%（-0.6/5 = 0.12 → 夹到 8%）',
  softRate(-600), 1.08);
eq('偏差 100ms（未达软阈值）→ 变速不会超过 8%',
  softRate(-100), 1.02);
ok('任意偏差的变速都落在 ±8% 内',
  [-9_999, -3_000, 0, 2_500, 9_999].every((d) => {
    const rate = softRate(d);
    return rate >= 1 - MAX_RATE_ADJUST - 1e-9 && rate <= 1 + MAX_RATE_ADJUST + 1e-9;
  }));
eq('软校准持续 5 秒',
  planCalibration({ ...READY, driftMs: -1_000 }).durationMs, SOFT_CALIBRATION_MS);
eq('软校准不发 seek',
  planCalibration({ ...READY, driftMs: -1_000 }).seekToMs, null);

console.log('\n— 校准状态互斥 —');
eq('软校准进行中 → 不叠加',
  planCalibration({ ...READY, driftMs: -1_000, calibrating: true }).type, 'none');
eq('软校准进行中遇到大偏差 → 仍不叠加（等 5 秒窗口结束）',
  planCalibration({ ...READY, driftMs: -5_000, calibrating: true }).type, 'none');
eq('缓冲中 → 跳过校准（BR-22-018）',
  planCalibration({ ...READY, driftMs: -5_000, buffering: true }).type, 'none');
eq('页面不可见 → 跳过校准（BR-22-021）',
  planCalibration({ ...READY, driftMs: -5_000, hidden: true }).type, 'none');
eq('音频未就绪 → 跳过校准（EX-22-002-1）',
  planCalibration({ ...READY, driftMs: -5_000, audioReady: false }).type, 'none');
eq('恢复可见/网络恢复 → 立即硬校准',
  planCalibration({ ...READY, driftMs: -100, forceHard: true }).type, 'hard');
eq('强制硬校准跳到期望位置',
  planCalibration({ ...READY, driftMs: -100, forceHard: true }).seekToMs, 50_000);
eq('强制硬校准不受缓冲阻挡？不——缓冲仍然优先',
  planCalibration({ ...READY, driftMs: -100, forceHard: true, buffering: true }).type, 'none');

console.log('\n— 检查频率 —');
eq('软检查间隔 10 秒：差 9.999s → 未到',
  dueForCheck(NOW - 9_999, NOW, 10_000), false);
eq('软检查间隔 10 秒：恰好 10s → 到期',
  dueForCheck(NOW - 10_000, NOW, 10_000), true);

// ---------------------------------------------------------------------------
// 4. 投票展示规则（FR-22-004）
// ---------------------------------------------------------------------------
console.log('\n— 投票 —');
eq('截止时间 60s 后 → 剩余 60 秒（向上取整）',
  voteRemainingSeconds(NOW + 60_000, NOW), 60);
eq('已过期 → 0', voteRemainingSeconds(NOW - 1, NOW), 0);
eq('缺少截止时间 → 0', voteRemainingSeconds(null, NOW), 0);
eq('status=1 且未到期 → 进行中',
  isVoteActive({ status: 1, endTime: NOW + 1_000 }, NOW), true);
eq('status=2（已通过）不再进行中',
  isVoteActive({ status: 2, endTime: NOW + 60_000 }, NOW), false);

eq('3 人在线：60% = 2 票', requiredAgreeCount(3), 2);
eq('10 人在线：min(6, max(5,5)) = 5 票', requiredAgreeCount(10), 5);
eq('20 人在线：min(12, 10) = 10 票', requiredAgreeCount(20), 10);
eq('1 人在线：至少 1 票', requiredAgreeCount(1), 1);
eq('在线数脏数据 0 → 至少 1 票', requiredAgreeCount(0), 1);

eq('通过文案', voteResultText(2, true), '投票通过，已跳过当前歌曲');
eq('未通过文案', voteResultText(3, false), '投票未通过，当前歌曲继续播放');
eq('取消文案', voteResultText(4, false), '投票已取消');

// ---------------------------------------------------------------------------
// 5. 弱网常量（BR-22-076）
// ---------------------------------------------------------------------------
console.log('\n— 弱网 —');
eq('弱网阈值 500ms', WEAK_RTT_MS, 500);
eq('弱网心跳 45s', WEAK_HEARTBEAT_INTERVAL, 45_000);

console.log(`\n结果：${passed} 通过，${failed} 失败`);
process.exit(failed === 0 ? 0 : 1);
