#!/usr/bin/env node
/**
 * 搜索页 URL 状态断言（模块 06 T614 / AC-15-006-4）。
 *
 * 与 `test-lrc.mjs` 同一套路：不引测试框架，靠 Node 原生擦除 TS 类型直接
 * import `src/pages/search/searchUrlState.ts`。那个模块除了一个 `import type`
 * 之外没有任何 import，所以能被 Node 直接加载（antd / axios 进来的模块不行）。
 *
 * 断言的重点是**需求本身**，不是实现细节：
 *   AC-15-006-4 说「刷新页面筛选条件保持」，等价于
 *   `readState(buildParams(state)) ≡ state`——所以下面把这条往返性质单独测一遍。
 */

import {
  buildParams,
  buildQuery,
  readState,
  splitCsv,
  joinCsv,
  toQueryString,
  FILTER_TYPES,
} from '../src/pages/search/searchUrlState.ts';

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
    console.log(`✗ ${name}${detail ? `\n    ${detail}` : ''}`);
  }
}

const stateOf = (qs) => readState(new URLSearchParams(qs));

// ---------------------------------------------------------------------------
// 1. 空 URL → 全默认（BR-15-001 的入口：/search 不带任何参数）
// ---------------------------------------------------------------------------
console.log('\n— 默认值与脏值回退 —');
eq('空 query → 默认状态', stateOf(''), {
  keyword: '',
  type: 'all',
  language: [],
  genre: [],
  era: [],
  duration: [],
  sort: 'relevance',
});

eq('type 未知 → 回退 all', stateOf('type=bogus').type, 'all');
eq('sort 未知 → 回退 relevance', stateOf('sort=bogus').sort, 'relevance');
eq('type=lyric 保留（合法值不被打回）', stateOf('type=lyric').type, 'lyric');
eq('sort=duration_asc 保留', stateOf('sort=duration_asc').sort, 'duration_asc');

// ---------------------------------------------------------------------------
// 2. 多值筛选（§4.4：维度内 OR，逗号分隔）
// ---------------------------------------------------------------------------
console.log('\n— 多值筛选的解析 —');
eq('splitCsv 去空格 / 去空项', splitCsv('国语, 粤语 ,, 英语'), ['国语', '粤语', '英语']);
eq('splitCsv null → []', splitCsv(null), []);
eq('joinCsv 空数组 → undefined', joinCsv([]), undefined);
eq('joinCsv 单值不加逗号', joinCsv(['国语']), '国语');
eq('joinCsv 多值逗号连接', joinCsv(['国语', '粤语']), '国语,粤语');

eq('URL 里的多值筛选解析成数组', stateOf('genre=摇滚,流行').genre, ['摇滚', '流行']);
eq('未出现的维度是空数组而不是 [""]', stateOf('language=').language, []);

// ---------------------------------------------------------------------------
// 3. 序列化：默认值不落 URL
// ---------------------------------------------------------------------------
console.log('\n— 状态 → URL —');
eq('全默认状态 → 空参数', buildParams(stateOf('')), {});
eq(
  '干净的关键词搜索 → 只有 keyword',
  buildParams({ ...stateOf(''), keyword: '晴天' }),
  { keyword: '晴天' },
);
eq('关键词首尾空白被去掉', buildParams({ ...stateOf(''), keyword: '  晴天  ' }), { keyword: '晴天' });
eq('全空白关键词当作没有关键词', buildParams({ ...stateOf(''), keyword: '   ' }), {});
eq(
  '非默认 type / sort 才落 URL',
  buildParams({ ...stateOf(''), type: 'song', sort: 'hot' }),
  { type: 'song', sort: 'hot' },
);
eq(
  '筛选数组序列化成逗号串',
  buildParams({ ...stateOf(''), genre: ['摇滚', '流行'], era: ['00s'] }),
  { genre: '摇滚,流行', era: '00s' },
);

// ---------------------------------------------------------------------------
// 4. AC-15-006-4 的核心性质：URL 往返稳定（刷新后筛选条件保持）
// ---------------------------------------------------------------------------
console.log('\n— AC-15-006-4：往返稳定 —');
const samples = [
  '',
  'keyword=晴天',
  'keyword=晴天&type=song',
  'keyword=晴天&type=song&genre=摇滚&era=00s&duration=gt5&sort=hot',
  'type=artist&language=国语,粤语',
  'keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&type=lyric&sort=duration_desc',
];

for (const qs of samples) {
  const once = readState(new URLSearchParams(qs));
  const roundTrip = toQueryString(once);
  const twice = readState(new URLSearchParams(roundTrip));
  eq(`往返稳定: ${qs || '(空)'}`, twice, once);

  // 再走一轮，确认序列化已经是不动点（不会每轮多长一个参数出来）
  eq(`序列化是不动点: ${qs || '(空)'}`, toQueryString(twice), roundTrip);
}

// ---------------------------------------------------------------------------
// 5. 筛选的作用域（发给后端时只给歌曲类 Tab）
// ---------------------------------------------------------------------------
console.log('\n— 筛选只发给歌曲类 Tab —');
const filtered = {
  ...stateOf(''),
  keyword: '的',
  genre: ['摇滚'],
  era: ['00s'],
  language: ['国语'],
  duration: ['gt5'],
};

eq('FILTER_TYPES 就是 all/song/lyric', FILTER_TYPES, ['all', 'song', 'lyric']);
eq('type=all 带筛选', buildQuery({ ...filtered, type: 'all' }).genre, '摇滚');
eq('type=song 带筛选', buildQuery({ ...filtered, type: 'song' }).genre, '摇滚');
eq('type=lyric 带筛选', buildQuery({ ...filtered, type: 'lyric' }).era, '00s');

for (const type of ['artist', 'album', 'playlist']) {
  const q = buildQuery({ ...filtered, type });
  ok(
    `type=${type} 不发任何筛选参数`,
    !('genre' in q) && !('era' in q) && !('language' in q) && !('duration' in q),
    `实际参数: ${JSON.stringify(q)}`,
  );
}

// ---------------------------------------------------------------------------
// 6. 默认排序不发给后端（后端默认就是 relevance）
// ---------------------------------------------------------------------------
console.log('\n— 后端参数的省略规则 —');
eq('默认排序不显式发送', 'sort' in buildQuery({ ...stateOf(''), keyword: '晴天' }), false);
eq('非默认排序发送', buildQuery({ ...stateOf(''), keyword: '晴天', sort: 'newest' }).sort, 'newest');
eq('空关键词发 undefined（而不是空串）', buildQuery(stateOf('')).keyword, undefined);

console.log(`\n${failed === 0 ? '✅' : '❌'} 通过 ${passed} / 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
