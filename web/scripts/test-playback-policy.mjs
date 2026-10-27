#!/usr/bin/env node
/**
 * 播放体验纯策略断言（模块 12）。
 *
 * 与 test-playorder.mjs 同一套路：Node 原生擦除 TS 类型直接 import，
 * `playbackPolicy.ts` 只有一个 `import type`，不依赖浏览器/antd。
 *
 * 锁定的都是「手工很难复现」的规则：
 * - 弱网降级：2 次长缓冲才降、只降不升、5 分钟窗口最多 2 次、房间内禁用；
 * - 跨设备续播：本机/开头/结尾/已有本地断点不提示；
 * - 倍速展示格式（整数补一位小数）。
 */
import {
  DOWNGRADE_WINDOW_MS,
  MAX_DOWNGRADES_IN_WINDOW,
  formatRate,
  lowerQuality,
  planAutoDowngrade,
  shouldPromptRemoteResume,
} from '../src/components/player/playbackPolicy.ts';

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

function ok(name, condition) {
  eq(name, Boolean(condition), true);
}

console.log('— 倍速展示 —');
eq('整数档补一位小数', formatRate(1), '1.0x');
eq('2 倍速补一位小数', formatRate(2), '2.0x');
eq('半速', formatRate(0.5), '0.5x');
eq('两位小数原样', formatRate(1.25), '1.25x');

console.log('\n— 音质降档 —');
eq('无损 → 高清', lowerQuality('lossless'), 'high');
eq('高清 → 标准', lowerQuality('high'), 'standard');
eq('标准已是最低', lowerQuality('standard'), null);

console.log('\n— 弱网自动降级 —');
const NOW = 1_700_000_000_000;
const base = {
  stallCount: 2,
  effectiveQuality: 'lossless',
  autoQuality: true,
  inRoom: false,
  downgradeAt: [],
  now: NOW,
};

const first = planAutoDowngrade(base);
eq('2 次长缓冲 → 降一档', first?.quality, 'high');
eq('降级时间写入窗口', first?.downgradeAt, [NOW]);
eq('只缓冲 1 次不降', planAutoDowngrade({ ...base, stallCount: 1 }), null);
eq('关闭自动降质时不降', planAutoDowngrade({ ...base, autoQuality: false }), null);
eq('音乐房内不降', planAutoDowngrade({ ...base, inRoom: true }), null);
eq('标准档无法再降', planAutoDowngrade({ ...base, effectiveQuality: 'standard' }), null);
eq('高清档降到标准', planAutoDowngrade({ ...base, effectiveQuality: 'high' })?.quality, 'standard');

const twoRecent = [NOW - 1000, NOW - 2000];
eq(
  '5 分钟内已降 2 次 → 不再降',
  planAutoDowngrade({ ...base, downgradeAt: twoRecent }),
  null,
);
eq(
  '窗口恰好满 5 分钟的旧记录被剔除后可再降',
  planAutoDowngrade({ ...base, downgradeAt: [NOW - DOWNGRADE_WINDOW_MS, NOW - 1000] })?.downgradeAt,
  [NOW - 1000, NOW],
);
eq(
  '旧记录被剔除后只保留窗口内的',
  planAutoDowngrade({
    ...base,
    downgradeAt: [NOW - DOWNGRADE_WINDOW_MS - 1, NOW - 1000],
  })?.downgradeAt,
  [NOW - 1000, NOW],
);
ok('窗口上限常量是 2', MAX_DOWNGRADES_IN_WINDOW === 2);

console.log('\n— 跨设备续播提示 —');
const remote = { found: true, position: 42, duration: 200, fromCurrentDevice: false };
ok('正常远端进度 → 提示', shouldPromptRemoteResume(remote, 0, false));
eq('本机上报 → 不提示', shouldPromptRemoteResume({ ...remote, fromCurrentDevice: true }, 0, false), false);
eq('没有记录 → 不提示', shouldPromptRemoteResume({ ...remote, found: false }, 0, false), false);
eq('本地有待应用的断点 → 不提示', shouldPromptRemoteResume(remote, 0, true), false);
eq('刚开始 5 秒内 → 不提示', shouldPromptRemoteResume({ ...remote, position: 4.9 }, 0, false), false);
eq('刚好 5 秒 → 提示', shouldPromptRemoteResume({ ...remote, position: 5 }, 0, false), true);
eq(
  '离结尾恰好 10 秒 → 不提示',
  shouldPromptRemoteResume({ ...remote, position: 190, duration: 200 }, 0, false),
  false,
);
eq(
  '离结尾 11 秒 → 提示',
  shouldPromptRemoteResume({ ...remote, position: 189, duration: 200 }, 0, false),
  true,
);
eq('本地已播到 5 秒 → 不提示', shouldPromptRemoteResume(remote, 5, false), false);
eq('本地播到 4.9 秒 → 仍提示', shouldPromptRemoteResume(remote, 4.9, false), true);
eq('位置非数字 → 不提示', shouldPromptRemoteResume({ ...remote, position: Number.NaN }, 0, false), false);
ok(
  '时长未知（0）时只看位置',
  shouldPromptRemoteResume({ ...remote, duration: 0 }, 0, false),
);
ok('本地位置非数字时按 0 处理', shouldPromptRemoteResume(remote, Number.NaN, false));

console.log(`\n${failed === 0 ? '✅' : '❌'} 通过 ${passed} / 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
