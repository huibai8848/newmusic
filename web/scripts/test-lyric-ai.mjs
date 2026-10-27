#!/usr/bin/env node
/**
 * 歌词译文对齐断言（模块 14 FR-23-002）。
 *
 * 这是双语歌词最容易出错的地方：后端按自己的解析结果给 `timeMs`，前端按自己的
 * 解析结果渲染，两者一旦按行号硬拼，用户在副歌重复段就会看到错句。
 * 与其它前端测试一样零依赖：Node 22.6+ 原生擦除 TS 类型，直接 import `.ts`。
 */

import { alignTranslations, displayText } from '../src/utils/lyricTranslation.ts';

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

/** 造一条前端解析出来的行：rawTime 是标签原始秒数 */
const line = (rawTime, time = rawTime, text = `o${rawTime}`) => ({ rawTime, time, text });

// ---------------------------------------------------------------------------
// 1. 按时间对齐：前端有 offset（time 变了）也不影响
// ---------------------------------------------------------------------------
const offsetLines = [line(0, 0.5), line(5, 5.5), line(10, 10.5)];
const offsetTrans = [
  { timeMs: 0, translation: '零' },
  { timeMs: 5000, translation: '五' },
  { timeMs: 10000, translation: '十' },
];
eq('offset 下按 rawTime 对齐', alignTranslations(offsetLines, offsetTrans), {
  translations: ['零', '五', '十'],
  mode: 'time',
  matched: 3,
});

// ---------------------------------------------------------------------------
// 2. 同一时间重复（副歌）：按出现顺序一一配对，不丢行
// ---------------------------------------------------------------------------
const dupLines = [line(45), line(45), line(50)];
const dupTrans = [
  { timeMs: 45000, translation: '第一次' },
  { timeMs: 45000, translation: '第二次' },
  { timeMs: 50000, translation: '第三句' },
];
eq('重复时间点按顺序配对', alignTranslations(dupLines, dupTrans), {
  translations: ['第一次', '第二次', '第三句'],
  mode: 'time',
  matched: 3,
});

// ---------------------------------------------------------------------------
// 3. 某行没有译文：该行为 null，其它行照常
// ---------------------------------------------------------------------------
eq(
  '空译文不影响其它行',
  alignTranslations([line(0), line(5)], [
    { timeMs: 0, translation: '好的' },
    { timeMs: 5000, translation: '   ' },
  ]),
  { translations: ['好的', null], mode: 'time', matched: 1 },
);

// ---------------------------------------------------------------------------
// 4. 时间全对不上但行数一致 → 按行号兜底（无时间轴歌词）
// ---------------------------------------------------------------------------
eq(
  '行数一致时按行号兜底',
  alignTranslations([line(0), line(1)], [
    { timeMs: 9000, translation: 'A' },
    { timeMs: 9100, translation: 'B' },
  ]),
  { translations: ['A', 'B'], mode: 'index', matched: 2 },
);

// ---------------------------------------------------------------------------
// 5. 时间对不上且行数不一致 → 不猜，全部留空
// ---------------------------------------------------------------------------
eq(
  '行数不一致且时间不匹配时不按行号硬拼',
  alignTranslations([line(0), line(1), line(2)], [
    { timeMs: 9000, translation: 'A' },
    { timeMs: 9100, translation: 'B' },
  ]),
  { translations: [null, null, null], mode: 'none', matched: 0 },
);

eq('没有译文数据', alignTranslations([line(0)], null), {
  translations: [null],
  mode: 'none',
  matched: 0,
});

eq('没有歌词行', alignTranslations([], [{ timeMs: 0, translation: 'A' }]), {
  translations: [],
  mode: 'none',
  matched: 0,
});

// ---------------------------------------------------------------------------
// 6. 展示模式
// ---------------------------------------------------------------------------
eq('原文模式忽略译文', displayText('hello', '你好', 'original'), {
  primary: 'hello',
  secondary: null,
});
eq('双语模式主原文副译文', displayText('hello', '你好', 'original_translation'), {
  primary: 'hello',
  secondary: '你好',
});
eq('仅译文模式', displayText('hello', '你好', 'translation'), {
  primary: '你好',
  secondary: null,
});
eq('仅译文但该行缺译文时回退原文', displayText('hello', null, 'translation'), {
  primary: 'hello',
  secondary: null,
});
eq('双语但该行缺译文时只渲染原文', displayText('hello', '  ', 'original_translation'), {
  primary: 'hello',
  secondary: null,
});
eq('译文首尾空白被去掉', displayText('hello', '  你好  ', 'original_translation'), {
  primary: 'hello',
  secondary: '你好',
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
