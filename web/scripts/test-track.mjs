#!/usr/bin/env node
/**
 * 模块 17 埋点 SDK 纯函数断言。
 *
 * `trackCore.ts` 不碰 window / localStorage，Node 的原生类型擦除可以直接 import。
 * 断言集中在最容易在线上翻车的地方：
 * - 事件名规范与服务端 `EventNameValidator` 完全一致；
 * - 500ms 去重窗口的闭开区间；
 * - 每分钟限流窗口的重置；
 * - 失败重试 3 次后丢弃；
 * - localStorage 里的坏数据不能把队列卡死；
 * - 会话空闲 30 分钟换新。
 */

import {
  TRACK_LIMITS,
  completionRate,
  dedupKey,
  derivePageId,
  enqueueTrack,
  isEventNameValid,
  newTrackId,
  normalizeNetwork,
  parsePersistedQueue,
  requeueFailed,
  retryDelay,
  sessionExpired,
  shouldSuppressDuplicate,
  takeBatch,
  takeRateToken,
  toBatchBody,
} from '../src/utils/trackCore.ts';

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
    console.log(`✗ ${name}${detail === undefined ? '' : ` — ${detail}`}`);
  }
}

const payload = (event, extra = {}) => ({
  eventId: newTrackId('evt', 1_700_000_000_000, 0.5),
  event,
  eventTime: 1_700_000_000_000,
  common: {
    deviceId: 'dev-1',
    sessionId: 'sess-1',
    platform: 'web',
    appVersion: 'web-1.0.0',
    os: 'Windows',
    browser: 'Chrome',
    screen: '1920x1080',
    network: '4g',
    pageId: 'home',
    pageUrl: '/',
    referrer: '',
  },
  properties: extra,
});

// ---------------------------------------------------------------- 事件名

ok('合法事件名 player_song_play', isEventNameValid('player_song_play'));
ok('合法事件名 user_register_success', isEventNameValid('user_register_success'));
ok('两段 domain_action 事件合法（目录里有 page_view）', isEventNameValid('page_view'));
ok('单段事件名被拒', !isEventNameValid('pageview'));
ok('大写事件名被拒', !isEventNameValid('Player_song_play'));
ok('数字开头被拒', !isEventNameValid('1player_song_play'));
ok('超过 6 段被拒', !isEventNameValid('a_b_c_d_e_f_g'));
ok('超过 64 字符被拒', !isEventNameValid(`a_${'b'.repeat(63)}_c`));

// ---------------------------------------------------------------- 页面 ID

eq('根路径归一为 home', derivePageId('/'), 'home');
eq('歌曲详情去掉 ID', derivePageId('/song/1847563920182736459'), 'song_detail');
eq('查询串不影响页面 ID', derivePageId('/search?keyword=周杰伦'), 'search');
eq('尾斜杠归一', derivePageId('/rooms/'), 'room_list');
eq('未知页面按首段归类', derivePageId('/lyric/abc'), 'page_lyric');
eq('非法字符被清洗', derivePageId('/中文页面'), 'page_unknown');

// ---------------------------------------------------------------- ID

const id1 = newTrackId('evt', 1_700_000_000_000, 0.123456);
const id2 = newTrackId('evt', 1_700_000_000_000, 0.654321);
ok('事件 ID 唯一', id1 !== id2, `${id1} vs ${id2}`);
ok('事件 ID 不超过 64 字符', id1.length <= 64);
ok('事件 ID 前缀被保留', id1.startsWith('evt_'));
ok('非法前缀被清洗', newTrackId('e vt!', 1, 0.1).startsWith('evt_'));

// ---------------------------------------------------------------- 去重

const key = dedupKey('page_view', { page_id: 'home', b: 2, a: 1 });
eq('去重键与属性顺序无关', key, dedupKey('page_view', { a: 1, b: 2, page_id: 'home' }));
ok('不同事件名不同键', key !== dedupKey('page_leave', { page_id: 'home', b: 2, a: 1 }));
ok('499ms 内重复被抑制', shouldSuppressDuplicate(key, 1000, key, 1499));
ok('正好 500ms 不再抑制（闭开区间）', !shouldSuppressDuplicate(key, 1000, key, 1500));
ok('不同键不抑制', !shouldSuppressDuplicate(key, 1000, 'other', 1001));
ok('时钟回拨不抑制', !shouldSuppressDuplicate(key, 2000, key, 1999));

// ---------------------------------------------------------------- 限流

let state = { windowStart: 0, count: 0 };
let allowedCount = 0;
for (let i = 0; i < 100; i += 1) {
  const result = takeRateToken(state, 60_000 + i);
  state = result.state;
  if (result.allowed) allowedCount += 1;
}
eq('一分钟内前 100 条允许', allowedCount, 100);
eq('第 101 条被拒', takeRateToken(state, 60_100).allowed, false);
const reset = takeRateToken(state, 121_000);
ok('进入下一分钟窗口后恢复', reset.allowed && reset.state.count === 1, JSON.stringify(reset));

// ---------------------------------------------------------------- 队列

let queue = [];
for (let i = 0; i < 25; i += 1) {
  queue = enqueueTrack(queue, { payload: payload('page_view', { i }), attempts: 0, queuedAt: i });
}
eq('未超上限时全部保留', queue.length, 25);
eq('保留最新一条', queue[queue.length - 1].payload.properties.i, 24);

let bigQueue = [];
for (let i = 0; i < TRACK_LIMITS.queueSize + 10; i += 1) {
  bigQueue = enqueueTrack(bigQueue, { payload: payload('page_view', { i }), attempts: 0, queuedAt: i });
}
eq('超过上限按 queueSize 裁剪', bigQueue.length, TRACK_LIMITS.queueSize);
eq('裁剪保留最新一条', bigQueue[bigQueue.length - 1].payload.properties.i, TRACK_LIMITS.queueSize + 9);

const { batch, rest } = takeBatch([...queue], 20);
eq('每批 20 条', batch.length, 20);
eq('剩余 5 条', rest.length, 5);

const failedOnce = requeueFailed(rest, batch, 3);
eq('失败回滚后批次回到队首', failedOnce.queue.length, 25);
eq('重试次数 +1', failedOnce.queue[0].attempts, 1);
eq('未达上限不丢弃', failedOnce.dropped, 0);

const exhausted = requeueFailed([], [{ ...batch[0], attempts: 3 }], 3);
eq('达到 3 次后丢弃', exhausted.dropped, 1);
eq('丢弃后队列为空', exhausted.queue.length, 0);

eq('重试退避 0 次为 0', retryDelay(0), 0);
eq('重试退避 1 次为 1s', retryDelay(1), 1000);
eq('重试退避 3 次为 4s', retryDelay(3), 4000);
eq('重试退避封顶 30s', retryDelay(10), 30_000);

// ---------------------------------------------------------------- 持久化

eq('空串解析为空队列', parsePersistedQueue('').length, 0);
eq('坏 JSON 解析为空队列', parsePersistedQueue('{oops').length, 0);
eq('非数组解析为空队列', parsePersistedQueue('{"a":1}').length, 0);
eq(
  '缺 eventId 的条目被丢弃',
  parsePersistedQueue(JSON.stringify([{ payload: { event: 'page_view' } }])).length,
  0,
);
const restored = parsePersistedQueue(
  JSON.stringify([{ payload: payload('page_view', { a: 1 }), attempts: 1, queuedAt: 5 }]),
);
eq('合法条目被恢复', restored.length, 1);
eq('恢复时保留重试次数', restored[0].attempts, 1);
eq('恢复时保留属性', restored[0].payload.properties.a, 1);
eq(
  '超过重试上限的条目被丢弃',
  parsePersistedQueue(
    JSON.stringify([{ payload: payload('page_view'), attempts: 9, queuedAt: 1 }]),
  ).length,
  0,
);
const partial = parsePersistedQueue(
  JSON.stringify([{ payload: { eventId: 'x', event: 'page_view' }, attempts: 0, queuedAt: 1 }]),
);
eq('缺 common 时补默认值', partial[0].payload.common.platform, 'web');
eq('缺 properties 时补空对象', JSON.stringify(partial[0].payload.properties), '{}');

const body = toBatchBody([{ payload: payload('page_view', { a: 1 }), attempts: 0, queuedAt: 0 }], 'dev-1');
eq('批量请求体带 deviceId', body.deviceId, 'dev-1');
eq('批量请求体事件数', body.events.length, 1);
eq('批量请求体事件名', body.events[0].event, 'page_view');

// ---------------------------------------------------------------- 会话与工具

ok('空闲 29 分钟仍有效', !sessionExpired(1000, 1000 + 29 * 60_000, 30 * 60_000));
ok('空闲 30 分钟过期', sessionExpired(1000, 1000 + 30 * 60_000, 30 * 60_000));
ok('未来时间视为过期', sessionExpired(1000, 999));
ok('无记录视为过期', sessionExpired(0, 1));

eq('4g 归一', normalizeNetwork('4g'), '4g');
eq('WIFI 大写归一', normalizeNetwork('WiFi'), 'wifi');
eq('未知网络', normalizeNetwork(undefined), 'unknown');
eq('慢速网络', normalizeNetwork('slow-2g'), 'slow');

eq('完成率 30/100', completionRate(30, 100), 0.3);
eq('完成率封顶 1', completionRate(120, 100), 1);
eq('完成率为负时归零', completionRate(-5, 100), 0);
eq('时长为 0 返回 0', completionRate(10, 0), 0);
eq('NaN 输入返回 0', completionRate(Number.NaN, 100), 0);
eq('完成率保留 4 位', completionRate(1, 3), 0.3333);

console.log(`\n模块 17 埋点 SDK：${passed} 通过 / ${failed} 失败`);
if (failed > 0) process.exit(1);
