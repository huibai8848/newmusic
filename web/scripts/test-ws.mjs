#!/usr/bin/env node
/**
 * WebSocket 纯规则断言（模块 04）。
 *
 * 与 `test-lrc.mjs` / `test-playorder.mjs` 同一套路：零依赖，靠 Node 原生擦除
 * TS 类型直接 import `src/services/wsCore.ts`。那个文件不碰 window/WebSocket，
 * 所以能在 Node 里跑；`wsClient.ts` 依赖浏览器全局对象，不在本文件范围内。
 *
 * 断言重点都在 PRD 写死数字的地方（AC-13-004/006）：
 * 退避序列与上限、漂移分级的两条边界、version 单调、5 分钟过期窗口。
 * 边界值刻意同时测「等于」与「刚超过」——这类阈值最容易在实现里写成 >= / > 反了。
 */

import {
  ACK_TIMEOUT,
  MAX_RECONNECT_ATTEMPTS,
  MAX_SEND_RETRIES,
  MessageDeduper,
  RECONNECT_DELAYS,
  buildWsUrl,
  computeServerPosition,
  driftAction,
  isAuthExpiring,
  reconnectDelay,
  shouldApplyVersion,
  softCalibrationRate,
} from '../src/services/wsCore.ts';

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

function note(msg) {
  console.log(`  · ${msg}`);
}

const ZERO = () => 0;
const ALMOST_ONE = () => 0.999_999;

// ---------------------------------------------------------------------------
// 1. 重连退避：1s→2s→4s→8s→16s→30s 封顶，10 次后放弃（FR-13-006 / BR-13-039）
// ---------------------------------------------------------------------------
console.log('\n— 重连退避序列 —');
eq('序列本身与 PRD 一致', [...RECONNECT_DELAYS], [1000, 2000, 4000, 8000, 16000, 30000]);
eq('放弃阈值是 10 次', MAX_RECONNECT_ATTEMPTS, 10);

// 模块 13 BR-22-077：抖动改为 ±20%，且 30s 封顶
for (let i = 0; i < RECONNECT_DELAYS.length; i += 1) {
  const base = RECONNECT_DELAYS[i];
  eq(`第 ${i + 1} 次失败 → ${base}ms（random=0 时 -20%）`,
    reconnectDelay(i, ZERO), Math.floor(base * 0.8));
  const jittered = reconnectDelay(i, ALMOST_ONE);
  ok(`第 ${i + 1} 次失败加抖动后落在 [base×0.8, base×1.2] 且 ≤30s`,
    jittered >= Math.floor(base * 0.8) && jittered <= Math.min(30_000, base * 1.2),
    jittered);
}
eq('超过序列长度仍封顶 30s（第 8 次，-20%）', reconnectDelay(7, ZERO), 24000);
eq('第 10 次（attempt=9）仍是 30s 档', reconnectDelay(9, ZERO), 24000);
eq('30s 档的 +20% 被夹回 30000', reconnectDelay(9, ALMOST_ONE), 30000);
eq('第 11 次（attempt=10）→ null（停止自动重连）', reconnectDelay(10, ZERO), null);
eq('更后面的 attempt 也是 null', reconnectDelay(99, ZERO), null);
eq('负数 attempt 被夹到 0（不会读到 undefined）', reconnectDelay(-3, ZERO), 800);

// 抖动必须真的存在：否则所有客户端会在同一毫秒重连（BR-13-033 的原话）
{
  let min = Infinity;
  let max = -Infinity;
  for (let i = 0; i < 500; i += 1) {
    const delay = reconnectDelay(5, Math.random);
    min = Math.min(min, delay);
    max = Math.max(max, delay);
  }
  ok('真实随机下 500 次抖动不是同一个值', max > min, { min, max });
  ok('全部仍在 [24000, 30000] 且不超过封顶', min >= 24_000 && max <= 30_000, { min, max });
}

// ---------------------------------------------------------------------------
// 2. 消息去重：同一 msgId 第二次必须被识别（EX-13-007-5）
// ---------------------------------------------------------------------------
console.log('\n— 消息去重 —');
{
  const deduper = new MessageDeduper(3);
  eq('首次出现 → 不是重复', deduper.isDuplicate('m1'), false);
  eq('第二次出现 → 是重复', deduper.isDuplicate('m1'), true);
  eq('没有 msgId 的帧不参与去重（控制帧每次都该处理）', deduper.isDuplicate(undefined), false);
  eq('空字符串同样不参与', deduper.isDuplicate(''), false);

  eq('容量内保留：m2 首次', deduper.isDuplicate('m2'), false);
  eq('容量内保留：m3 首次', deduper.isDuplicate('m3'), false);
  // 此时 m1/m2/m3 占满容量 3；再来 m4 会把最早的 m1 挤掉
  eq('容量满后加入 m4', deduper.isDuplicate('m4'), false);
  eq('m1 已被淘汰 → 再出现视为新消息', deduper.isDuplicate('m1'), false);
  eq('m4 还在窗口内 → 重复', deduper.isDuplicate('m4'), true);
  eq('窗口大小等于容量', deduper.size, 3);

  deduper.reset();
  eq('reset 后历史全部忘记', deduper.isDuplicate('m1'), false);
  eq('reset 后大小为 0 再计一次', deduper.size, 1);
}

// ---------------------------------------------------------------------------
// 3. 播放进度推算：暂停取快照位置，播放中叠加经过时间（FR-13-004）
// ---------------------------------------------------------------------------
console.log('\n— 服务端播放进度推算 —');
const NOW = 1_700_000_000_000;
eq('无快照 → 0', computeServerPosition(null, NOW), 0);
eq('暂停 → 就停在 position（serverTime 过去多久都不动）',
  computeServerPosition({ position: 42, serverTime: NOW - 60_000, isPlaying: false }, NOW), 42);
eq('播放中 → position + 经过秒数',
  computeServerPosition({ position: 10, serverTime: NOW - 5_000, isPlaying: true }, NOW), 15);
eq('播放中、缺少 serverTime → 只返回 position（按 0 秒经过处理）',
  computeServerPosition({ position: 10, isPlaying: true }, NOW), 10);
eq('快照时间在未来（节点时钟略快）→ 不倒退成负数',
  computeServerPosition({ position: 10, serverTime: NOW + 5_000, isPlaying: true }, NOW), 10);
eq('position 为负数（脏数据）→ 夹到 0',
  computeServerPosition({ position: -7, isPlaying: false }, NOW), 0);
eq('小数秒保留精度（不做 floor，格式化交给 UI）',
  computeServerPosition({ position: 1, serverTime: NOW - 500, isPlaying: true }, NOW), 1.5);

// ---------------------------------------------------------------------------
// 4. 漂移分级的两条边界（BR-13-022）
//     PRD：≤0.5s 不校准；0.5~1.5s 软校准；>1.5s 硬校准
// ---------------------------------------------------------------------------
console.log('\n— 漂移分级边界 —');
eq('偏差 0 → none', driftAction(10, 10), 'none');
eq('偏差恰好 0.5 → none（"≤0.5 不校准"）', driftAction(10, 10.5), 'none');
eq('偏差 0.5001 → soft', driftAction(10, 10.5001), 'soft');
eq('偏差恰好 1.5 → soft（1.5 本身仍属软校准）', driftAction(10, 11.5), 'soft');
eq('偏差 1.5001 → hard', driftAction(10, 11.5001), 'hard');
eq('方向相反同样分级（本地慢 1 秒 → soft）', driftAction(10, 9), 'soft');
eq('方向相反同样分级（本地快 2 秒 → hard）', driftAction(12, 10), 'hard');
eq('自定义阈值生效', driftAction(10, 10.3, 0.2, 0.4), 'soft');
eq('自定义硬阈值生效', driftAction(10, 10.5, 0.2, 0.4), 'hard');

console.log('\n— 软校准倍速 —');
eq('目标在本曲之后 → 1.02 倍速追赶', softCalibrationRate(10, 11), 1.02);
eq('目标在本曲之前 → 0.98 倍速等待', softCalibrationRate(11, 10), 0.98);
eq('恰好相等按"不落后"处理 → 1.02', softCalibrationRate(10, 10), 1.02);

// ---------------------------------------------------------------------------
// 5. version 单调：只接受更大的版本（BR-13-021 / EX-13-004-2）
// ---------------------------------------------------------------------------
console.log('\n— 控播版本单调 —');
eq('首次（无当前版本）→ 应用', shouldApplyVersion(null, 1), true);
eq('首次 undefined → 应用', shouldApplyVersion(undefined, 1), true);
eq('版本更大 → 应用', shouldApplyVersion(3, 4), true);
eq('版本相同 → 忽略（重复广播）', shouldApplyVersion(4, 4), false);
eq('版本更小 → 忽略（乱序到达）', shouldApplyVersion(5, 4), false);
eq('传入消息没有版本 → 一律忽略（不能拿它覆盖权威态）', shouldApplyVersion(3, null), false);
eq('当前有版本、传入 undefined → 忽略', shouldApplyVersion(3, undefined), false);

// ---------------------------------------------------------------------------
// 6. token 过期窗口：剩余 < 5 分钟（PRD §4.3）
// ---------------------------------------------------------------------------
console.log('\n— token 即将过期 —');
eq('剩余恰好 5 分钟 → 进入窗口', isAuthExpiring(NOW + 300_000, NOW), true);
eq('剩余 4 分 59 秒 → 进入窗口', isAuthExpiring(NOW + 299_000, NOW), true);
eq('剩余 5 分 01 秒 → 不进窗口', isAuthExpiring(NOW + 301_000, NOW), false);
eq('已经过期 → 不提示（应当直接触发重新登录）', isAuthExpiring(NOW - 1_000, NOW), false);
eq('没有过期时间（老 token / 解析不到 exp）→ 不提示', isAuthExpiring(null, NOW), false);
eq('自定义窗口生效', isAuthExpiring(NOW + 70_000, NOW, 60_000), false);

// ---------------------------------------------------------------------------
// 7. WS URL：token 必须被转义（浏览器不能设 Authorization 头）
// ---------------------------------------------------------------------------
console.log('\n— WS URL 拼接 —');
{
  const url = new URL(buildWsUrl('ws://localhost:5173/ws', 'a+b/c==', 'dev-1'));
  eq('协议与路径保持不变', `${url.protocol}//${url.host}${url.pathname}`, 'ws://localhost:5173/ws');
  eq('token 原样取回（+/= 已被 percent-encode 再解码）', url.searchParams.get('token'), 'a+b/c==');
  eq('deviceId 原样取回', url.searchParams.get('deviceId'), 'dev-1');
  ok('原始 token 的 + 号不会变成空格',
    buildWsUrl('ws://h/ws', 'a+b', 'd').includes('token=a%2Bb'));

  const https = buildWsUrl('wss://api.example.com/ws', 't', 'd');
  ok('wss 地址保持 wss', https.startsWith('wss://'));
  ok('路径不会被改写', new URL(https).pathname === '/ws');
}

// ---------------------------------------------------------------------------
// 8. 常量与未覆盖部分
// ---------------------------------------------------------------------------
console.log('\n— 常量 —');
eq('ack 等待 5s（BR-13-035）', ACK_TIMEOUT, 5000);
eq('可靠消息最多重试 2 次（加上首发共 3 次投递）', MAX_SEND_RETRIES, 2);

console.log('\n— 未覆盖 —');
note('wsClient 的心跳计时、重连计时、ack 超时重发都依赖 WebSocket/setTimeout，属浏览器行为；');
note('  纯函数层只能锁定它们的参数（间隔、次数、退避序列），实际时序靠 scripts/verify-04.mjs 连真服务端验证。');
note('页面可见性触发立即重连（BR-13-038）需要 document.visibilitychange，属浏览器走查项。');

console.log(`\n${failed === 0 ? '✅' : '❌'} 通过 ${passed} / 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
