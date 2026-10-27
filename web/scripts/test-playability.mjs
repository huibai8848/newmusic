#!/usr/bin/env node
/**
 * 可播放性判定断言（本次「歌曲在播但进度条拉不动」的回归）。
 *
 * 与 test-search-url.mjs 同一套路：Node 原生擦除 TS 类型直接 import。
 * playability.ts 只有一个 `import type`，不依赖 axios / antd / 浏览器，
 * 所以能把「搜索结果只有 playable、没有音源地址」这个真实场景完整复现出来。
 *
 * 锁定的规则：
 * - 服务端明确 playable=false → 无条件不可播（版权/审核已经变了）；
 * - 有音源字段 → 本地按版权与降级阶梯预判；
 * - 只有 playable=true、没有音源字段（SearchSongVO）→ 可播，不能误判成不可播。
 */

import {
  QUALITY_LADDER,
  isPlayable,
  resolveSource,
} from '../src/api/playability.ts';

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

function ok(name, condition, detail = '') {
  if (condition) {
    passed += 1;
    console.log(`✓ ${name}`);
  } else {
    failed += 1;
    console.log(`✗ ${name}${detail ? `\n    ${detail}` : ''}`);
  }
}

// 曲库实体（Song）：有音源字段，没有 playable
const song = (over = {}) => ({
  id: '2114675433760747522',
  name: '花海',
  copyrightStatus: 1,
  audioStandard: '/audio/huahai.mp3',
  ...over,
});

// 搜索结果（SearchSongVO）：没有音源字段，只有服务端结论
const searchHit = (over = {}) => ({
  id: '2114675433760747522',
  name: '花海',
  playable: true,
  ...over,
});

console.log('— 空值 —');
eq('null 不可播', isPlayable(null), false);
eq('undefined 不可播', isPlayable(undefined), false);

console.log('\n— 曲库实体：本地预判 —');
eq('标准音源 + 已授权 → 可播', isPlayable(song()), true);
eq('未授权(0) → 不可播', isPlayable(song({ copyrightStatus: 0 })), false);
eq('受限(3) → 不可播', isPlayable(song({ copyrightStatus: 3 })), false);
eq('已过期(2) 仍可由服务端判定 → 本地不拦', isPlayable(song({ copyrightStatus: 2 })), true);
eq('音源为空串 → 不可播', isPlayable(song({ audioStandard: '' })), false);
eq('没有音源字段也没有 playable → 不可播',
  isPlayable({ id: '1', name: 'x', copyrightStatus: 1 }), false);

console.log('\n— 搜索结果：只有服务端 playable（本次回归） —');
eq('playable=true 且无音源地址 → 可播（正在播放的歌不能被判死）',
  isPlayable(searchHit()), true);
eq('playable=true 请求无损音质 → 仍可播', isPlayable(searchHit(), 'lossless'), true);
eq('playable=false 即使带历史音源地址 → 不可播',
  isPlayable(searchHit({ playable: false, audioStandard: '/audio/old.mp3' })), false);
eq('playable=false 且版权未授权 → 不可播',
  isPlayable(searchHit({ playable: false, copyrightStatus: 0 })), false);
eq('playable=true 但本地明确未授权(0) → 本地否决，不可播',
  isPlayable(searchHit({ copyrightStatus: 0 })), false);
eq('playable=true 但本地明确受限(3) → 本地否决，不可播',
  isPlayable(searchHit({ copyrightStatus: 3 })), false);
eq('playable=true 且本地已授权(1) → 可播',
  isPlayable(searchHit({ copyrightStatus: 1 })), true);
eq('playable=undefined 且无音源 → 不可播',
  isPlayable(searchHit({ playable: undefined })), false);
eq('playable=true 且音源为空串 → 可播（地址由 /player/url 现签）',
  isPlayable(searchHit({ audioStandard: '' })), true);

console.log('\n— 音源降级阶梯（sync 预判） —');
eq('标准档只认标准音源', QUALITY_LADDER.standard, ['standard']);
eq('高清档：高清 → 标准', QUALITY_LADDER.high, ['high', 'standard']);
eq('无损档：无损 → 高清 → 标准', QUALITY_LADDER.lossless, ['lossless', 'high', 'standard']);

eq('请求无损但只有标准音源 → 降级为标准且标记 downgraded',
  resolveSource(song({ audioHigh: null, audioLossless: null }), 'lossless'),
  { url: '/audio/huahai.mp3', quality: 'standard', downgraded: true });
eq('请求高清且有高清音源 → 用高清、不降级',
  resolveSource(song({ audioHigh: '/audio/huahai-hi.mp3' }), 'high'),
  { url: '/audio/huahai-hi.mp3', quality: 'high', downgraded: false });
eq('请求标准不会拿高清地址顶上（降级只由服务端做）',
  resolveSource(song({ audioStandard: '', audioHigh: '/audio/huahai-hi.mp3' }), 'standard'),
  null);
eq('空字符串音源不算可用地址',
  resolveSource(song({ audioStandard: ' ' }), 'standard'), null);
eq('音源 URL 首尾空格会被 trim，避免交给 <audio> 后加载失败',
  resolveSource(song({ audioStandard: '  /audio/huahai.mp3  ' }), 'standard'),
  { url: '/audio/huahai.mp3', quality: 'standard', downgraded: false });
ok('resolveSource(null) → null', resolveSource(null) === null);

console.log(`\n结果：通过 ${passed} / 失败 ${failed}`);
process.exitCode = failed === 0 ? 0 : 1;
