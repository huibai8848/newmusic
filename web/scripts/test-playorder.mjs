#!/usr/bin/env node
/**
 * 播放顺序断言（模块 07 T723，对应 AC-16-103 的四种播放模式）。
 *
 * 与 `test-lrc.mjs` / `test-search-url.mjs` 同一套路：不引测试框架，
 * 靠 Node 原生擦除 TS 类型直接 import `src/stores/playOrder.ts`。
 * 那个模块除了一个 `import type` 之外没有任何 import，所以能被 Node 直接加载
 * （`playerStore.ts` 不行——它经 `toastWarning` 间接依赖 antd）。
 *
 * 断言的重点是**最容易错且最难手工验**的三处：洗牌不重复（BR-16-014）、
 * 各模式的切歌语义（含单曲循环点「下一首」必须切歌，BR-16-016）、循环回绕。
 * 随机源由 `buildShuffleOrder` 的第三个参数注入定种实现，
 * 所以「洗牌结果」在这里是可复现的确定值，而不是碰运气。
 *
 * 本文件已抓到两处真缺陷：R-10（重新洗牌不生效）与 R-11（单曲循环点下一首变重播），
 * 两处都有专门的回归断言，见 §8 与「R-11 回归」一节。
 */

import {
  advance,
  buildShuffleOrder,
  reshuffle,
  shufflePosOf,
  nextIndexIn,
  prevIndexIn,
} from '../src/stores/playOrder.ts';

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

// ---------------------------------------------------------------------------
// 定种随机源：xorshift32。同一个 seed 必然给出同一串数，
// 于是「洗牌结果」可以被写成等值断言，而不是只敢断言"是排列"。
// ---------------------------------------------------------------------------
function seeded(seed) {
  let s = (seed >>> 0) || 1;
  return () => {
    s ^= (s << 13) >>> 0; s >>>= 0;
    s ^= s >>> 17; s >>>= 0;
    s ^= (s << 5) >>> 0; s >>>= 0;
    return s / 4294967296;
  };
}

/**
 * 找一个**见证 seed**：使得某个性质成立的种子。
 * 比硬编码一个魔法数字好——魔法数字会在别人改实现后变成"无法解释的期望值"，
 * 而见证是**当场搜出来的**，搜不到就说明该性质在新实现下不成立了（断言会红）。
 */
function findSeed(pred, maxSeeds = 50000) {
  for (let s = 1; s <= maxSeeds; s += 1) {
    if (pred(seeded(s))) return s;
  }
  return null;
}

const isPermutation = (arr, n) =>
  arr.length === n && [...arr].sort((a, b) => a - b).every((v, i) => v === i);

// ---------------------------------------------------------------------------
// 1. 洗牌必须是一个排列（BR-16-014：禁止"每次随机取索引"，那会重复播同一首）
// ---------------------------------------------------------------------------
console.log('\n— 洗牌是一个排列（无重复、无遗漏）—');
eq('长度 0 → 空序列', buildShuffleOrder(0), []);
eq('长度 1 → [0]', buildShuffleOrder(1), [0]);

for (const n of [2, 3, 5, 7, 50]) {
  ok(`长度 ${n} → 0..${n - 1} 的排列`, isPermutation(buildShuffleOrder(n), n), buildShuffleOrder(n));
}

// 真实 Math.random 下重复多次：单次是排列说明不了什么，"每次都"才是那条需求
let allPerm = true;
for (let i = 0; i < 500; i += 1) {
  if (!isPermutation(buildShuffleOrder(6), 6)) { allPerm = false; break; }
}
ok('真实随机下连洗 500 次，每次都是排列（BR-16-014 的回归网）', allPerm);

// 洗牌确实在洗：2000 次里每个索引都至少当过一回首位
const firstCount = [0, 0, 0, 0, 0, 0];
for (let i = 0; i < 2000; i += 1) firstCount[buildShuffleOrder(6)[0]] += 1;
ok('2000 次洗牌中每个索引都当过首位（不是"永远同一个开头"）',
  firstCount.every((c) => c > 0), firstCount);

// ---------------------------------------------------------------------------
// 2. avoidFirst：刚播完的那首不要又排在第一位（D-10）
// ---------------------------------------------------------------------------
console.log('\n— 洗牌首曲排除「刚播完的那首」—');
const seedHead3 = findSeed((random) => buildShuffleOrder(5, undefined, random)[0] === 3);
ok('找到「首位恰好是 3」的见证 seed（搜不到就说明性质变了）', seedHead3 !== null);
if (seedHead3 !== null) {
  const raw = buildShuffleOrder(5, undefined, seeded(seedHead3));
  const avoided = buildShuffleOrder(5, 3, seeded(seedHead3));
  eq('见证成立：不排除时首位确实是 3', raw[0], 3);
  eq('排除后首位不再是 3', avoided[0] === 3, false);
  eq('被换到第二位（交换而非重抽，不消耗额外随机数）', avoided[1], 3);
  ok('交换后仍是排列', isPermutation(avoided, 5), avoided);
}

// 首位本来就不是它 → 不该做任何改动
const seedHeadNot3 = findSeed((random) => buildShuffleOrder(5, undefined, random)[0] !== 3);
if (seedHeadNot3 !== null) {
  eq('首位本来就不是它时，结果与不传 avoidFirst 完全一致',
    buildShuffleOrder(5, 3, seeded(seedHeadNot3)),
    buildShuffleOrder(5, undefined, seeded(seedHeadNot3)));
}
eq('长度 1 时 avoidFirst 不越界（arr[1] 不存在，交换分支必须跳过）',
  buildShuffleOrder(1, 0, seeded(7)), [0]);

// ---------------------------------------------------------------------------
// 3. reshuffle：游标必须指向当前曲
// ---------------------------------------------------------------------------
console.log('\n— 重建序列时游标对齐当前曲 —');
{
  let aligned = true;
  const bad = [];
  for (let idx = 0; idx < 6; idx += 1) {
    const { shuffleOrder, shufflePos } = reshuffle(6, idx, seeded(idx + 1));
    if (shuffleOrder[shufflePos] !== idx) { aligned = false; bad.push({ idx, shuffleOrder, shufflePos }); }
  }
  ok('队列长度 6 时，对每个 currentIndex 都有 order[shufflePos] === currentIndex', aligned, bad);
}
eq('同一个 seed 两次调用结果完全相同（可复现）',
  reshuffle(6, 2, seeded(11)), reshuffle(6, 2, seeded(11)));
eq('空队列 → 空序列 + 游标 0', reshuffle(0, 0, seeded(3)), { shuffleOrder: [], shufflePos: 0 });
eq('currentIndex 越界（-1）→ 游标退回 0', reshuffle(4, -1, seeded(3)).shufflePos, 0);
eq('currentIndex 越界（== length）→ 游标退回 0', reshuffle(4, 4, seeded(3)).shufflePos, 0);

// ---------------------------------------------------------------------------
// 4. shufflePosOf：落位时游标对不上就退回 0
// ---------------------------------------------------------------------------
console.log('\n— 切歌落位的游标规则 —');
{
  const order = buildShuffleOrder(6, undefined, seeded(42));
  eq('目标曲在序列里 → 返回它的位置', shufflePosOf(order, order[3]), 3);
  eq('目标曲在序列首 → 0', shufflePosOf(order, order[0]), 0);
  eq('空序列 → 0（indexOf 返回 -1，被 max(0,·) 收住，不会变成负数）', shufflePosOf([], 5), 0);
}

// ---------------------------------------------------------------------------
// 5. nextIndexIn：四种模式各自的语义
// ---------------------------------------------------------------------------
console.log('\n— nextIndexIn：order 播完即停 —');
eq('中间 → 下一首', nextIndexIn('order', 0, 5, [], 0), 1);
eq('倒数第二 → 最后一首', nextIndexIn('order', 3, 5, [], 0), 4);
eq('最后一首 → null（播完即停，这是与 prev 的不对称处）', nextIndexIn('order', 4, 5, [], 0), null);
eq('空队列 → null', nextIndexIn('order', 0, 0, [], 0), null);

console.log('\n— nextIndexIn：loop 列表循环 —');
eq('中间 → 下一首', nextIndexIn('loop', 0, 5, [], 0), 1);
eq('最后一首 → 回到 0', nextIndexIn('loop', 4, 5, [], 0), 0);
eq('空队列 → null', nextIndexIn('loop', 0, 0, [], 0), null);

console.log('\n— nextIndexIn：single 单曲 —');
eq('中间 → 下一首（BR-16-016：单曲循环下点「下一首」仍切歌，不重播当前）',
  nextIndexIn('single', 2, 5, [], 0), 3);
eq('最后一首 → 回到 0（切歌；"本曲播完重播"是另一条路径，住在 AudioEngine 的 ended 里）',
  nextIndexIn('single', 4, 5, [], 0), 0);
eq('空队列 → null', nextIndexIn('single', 2, 0, [], 0), null);

// R-11 的回归断言：`single` 在「切歌」这件事上必须与 `loop` 逐点相同。
// 原先 `advance('single')` 返回**当前索引**（"重播本曲"），这正是 R-11 —— 与
// BR-16-016 / AC-16-002-4 相反，也让 store 不得不加一段提前返回来配合它。
// 谁把 single 改回"返回自身"，下面这几条立刻变红。
console.log('\n— R-11 回归：single 与 loop 的切歌语义必须逐点相同 —');
{
  let same = true;
  const bad = [];
  for (const len of [1, 2, 5, 6]) {
    for (let i = 0; i < len; i += 1) {
      const a = advance('single', i, len, []);
      const b = advance('loop', i, len, []);
      if (a.index !== b.index) { same = false; bad.push({ len, i, single: a.index, loop: b.index }); }
    }
  }
  ok('对每个 (队列长度, 当前索引) 组合，single 的下一首与 loop 相同', same, bad);
  ok('single 不再返回当前索引（除队列只有一首这种只能重播的情形）',
    advance('single', 2, 5, []).index !== 2);
  ok('队列只有一首时 single 与 loop 都返回 0 —— store 侧据此走 replayToken 重播',
    advance('single', 0, 1, []).index === 0 && advance('loop', 0, 1, []).index === 0);
}

console.log('\n— nextIndexIn：shuffle 随机 —');
{
  const order = buildShuffleOrder(6, undefined, seeded(99));
  eq('游标未到尾 → 序列里的下一首', nextIndexIn('shuffle', order[0], 6, order, 0, seeded(99)), order[1]);
  eq('游标在倒数第二位 → 序列最后一首',
    nextIndexIn('shuffle', order[4], 6, order, 4, seeded(99)), order[5]);
  eq('队列只有 1 首 → 0（不做洗牌，也没有"下一首"可言）',
    nextIndexIn('shuffle', 0, 1, [0], 0, seeded(1)), 0);
  eq('nextIndexIn 就是 advance 的索引投影（同一份逻辑，不会各自漂移）',
    nextIndexIn('shuffle', order[0], 6, order, 0, seeded(99)),
    advance('shuffle', order[0], 6, order, 0, seeded(99)).index);
}

// advance 契约：序列没变时原样带回，变了就必须一起带出来
console.log('\n— advance：序列与游标必须一起交出去 —');
{
  const order = buildShuffleOrder(6, undefined, seeded(99));
  const mid = advance('shuffle', order[2], 6, order, 2, seeded(99));
  eq('序列内推进 → 原样带回同一个序列', mid.shuffleOrder, order);
  eq('序列内推进 → 游标在该序列里的位置恰好是目标曲的位置',
    mid.shuffleOrder[mid.shufflePos], mid.index);
  eq('序列内推进 → 游标就是"当前位置 + 1"', mid.shufflePos, 3);

  const stopped = advance('order', 4, 5, [], 0);
  eq('播放结束 → index 为 null', stopped.index, null);
  eq('播放结束 → 序列原样带回（调用方无需分支）', stopped.shuffleOrder, []);

  // 传入的序列长度与队列对不上（队列增删后漏了重建）→ 内部重建，不能返回越界索引
  let inRange = true;
  for (let i = 0; i < 200; i += 1) {
    const s = advance('shuffle', 0, 4, [0, 1, 2, 3, 4, 5], 0);
    if (!(s.index >= 0 && s.index < 4) || !isPermutation(s.shuffleOrder, 4)) { inRange = false; break; }
  }
  ok('序列长度与队列不符时内部重建（索引不越界，且交出的新序列长度正确）', inRange);
}

// ---------------------------------------------------------------------------
// 6. 随机模式一轮之内不重复（把 next 串起来走一遍）
// ---------------------------------------------------------------------------
console.log('\n— shuffle 一轮之内不重复 —');
{
  const n = 6;
  const order = buildShuffleOrder(n, undefined, seeded(777));
  const visited = [];
  let cur = order[0];
  let pos = 0;
  for (let step = 0; step < n - 1; step += 1) {
    visited.push(cur);
    const s = advance('shuffle', cur, n, order, pos, seeded(777));
    eq(`第 ${step + 2} 首恰好是序列里的那一首`, s.index, order[step + 1]);
    cur = s.index;
    pos = s.shufflePos;
  }
  visited.push(cur);
  ok(`一轮走完 ${n} 首互不重复（BR-16-014）`,
    new Set(visited).size === n, visited);
  ok('这一轮就是整个序列本身', JSON.stringify(visited) === JSON.stringify(order), visited);
}

// ---------------------------------------------------------------------------
// 7. prevIndexIn：与 next 的刻意不对称
// ---------------------------------------------------------------------------
console.log('\n— prevIndexIn —');
eq('order：中间 → 上一首', prevIndexIn('order', 3, 5, [], 0), 2);
eq('order：首曲往回 → 回绕到最后一首（与 next 返回 null 不对称，这是播放器通行手感）',
  prevIndexIn('order', 0, 5, [], 0), 4);
eq('loop：首曲往回 → 回绕到最后一首', prevIndexIn('loop', 0, 5, [], 0), 4);
eq('single：中间 → 上一首（BR-16-016 只写了 next；prev 按对称处理，否则「上一首」会变成重播）',
  prevIndexIn('single', 3, 5, [], 0), 2);
eq('空队列 → null', prevIndexIn('order', 0, 0, [], 0), null);

{
  const order = buildShuffleOrder(6, undefined, seeded(31));
  eq('shuffle：游标在中间 → 序列里的上一首', prevIndexIn('shuffle', order[3], 6, order, 3), order[2]);
  eq('shuffle：游标已在序列首 → null（调用方据此回到本曲开头）',
    prevIndexIn('shuffle', order[0], 6, order, 0), null);
  eq('shuffle：序列长度与队列不符（按空序列处理）→ null',
    prevIndexIn('shuffle', 0, 4, [0, 1, 2, 3, 4, 5], 2), null);
}

// ---------------------------------------------------------------------------
// 8. 一轮播完：重新洗牌必须**真正生效**（R-10 的回归断言）
//
// 这是一处曾经真实存在的 bug，由本文件写出来时才看见，已按 D-10 修正：
// 原 `nextIndexIn` 只返回索引，store 的 goTo 便拿**旧** shuffleOrder 去算落位游标——
// 而 shuffleOrder 是 0..n-1 的排列，任何合法索引都在里面，indexOf 必然命中，
// 于是新首曲被落位到它在旧序列里的位置 p，接下来的"下一首"又沿旧序列走：
// 重新洗牌只决定了周期交界处的那一首，且紧接着会重播本周期刚放过的歌（违反 BR-16-014）。
// 修法是让 `advance` 把重建后的序列与游标一起返回，store 原样落位。
// ---------------------------------------------------------------------------
console.log('\n— 一轮播完：重新洗牌必须真正生效（R-10 回归）—');
{
  const n = 6;
  const order0 = buildShuffleOrder(n, undefined, seeded(555));
  const last = order0[n - 1];
  const step = advance('shuffle', last, n, order0, n - 1, seeded(31337));

  ok('advance 把重建后的序列一起交出来（不是旧的那个数组）', step.shuffleOrder !== order0);
  ok('重建后的序列仍是 0..n-1 的排列', isPermutation(step.shuffleOrder, n), step.shuffleOrder);
  eq('游标指向返回的索引（落位后 order[shufflePos] 就是当前曲）',
    step.shuffleOrder[step.shufflePos], step.index);
  ok('新首曲不是刚播完那首（D-10）', step.index !== last, { head: step.index, last });

  // 模拟 playerStore.next → goTo：序列与游标**原样**取 advance 的返回值。
  // 新实现下 store 这一步只可能是"照着赋值"，所以这个模拟是忠实的。
  let order = order0;
  let pos = n - 1;
  let cur = last;
  const cycle2 = [];
  for (let i = 0; i < n; i += 1) {
    const s = advance('shuffle', cur, n, order, pos, seeded(31337));
    cur = s.index;
    order = s.shuffleOrder;
    pos = s.shufflePos;
    cycle2.push(cur);
  }
  eq('第二个周期恰好按新序列的顺序走完 6 首', cycle2, step.shuffleOrder);
  ok('第二个周期是完整排列（每首恰好一次，BR-16-014）', isPermutation(cycle2, n), cycle2);

  // 反面形状：旧实现下第二个周期会是"旧序列从新首曲处起的一段旋转"。
  // 断言它**不等于**新序列——谁把 R-10 那个 bug 重新引入，这一条立刻变红。
  const p = order0.indexOf(step.index);
  const rotation = [...order0.slice(p + 1), ...order0.slice(0, p + 1)];
  ok('第二个周期不再是旧序列的旋转（R-10 回归断言）',
    JSON.stringify(cycle2) !== JSON.stringify(rotation),
    { 实际: cycle2, 旧实现会是: rotation });
  note(`样例：旧序列 [${order0}]，播完 ${last} → 新首曲 ${step.index}，`
    + `新序列 [${step.shuffleOrder}]；旧实现下第二周期会是 [${rotation}]。`);
}

// ---------------------------------------------------------------------------
// 9. 未覆盖的部分（说明白，不用含糊的通过代替）
// ---------------------------------------------------------------------------
console.log('\n— 未覆盖 —');
note('single 单曲循环的「自动重播」住在 AudioEngine（ended 里读 repeatOne 就地重播，D-15），');
note('  不在本文件的纯函数范围内；本文件覆盖的是「手动点下一首/上一首 → 切歌」（BR-16-016）。');
note('「目标就是本曲时走 replayToken 重播」这条 store 侧分支也只能靠浏览器走查：');
note('  纯函数层只能断言 advance 会给出什么索引，断言不了引擎有没有真的重新 play()。');
note('断点续播、熔断计数（failStreak）、队列上限 500 同样是 store/引擎级行为，属手工走查项。');
note('goTo 的「跳过不可播歌曲」递归护栏（hops）也是 store 级，需构造"整队列无版权"才能验。');
note('playerStore 对 advance 的「落位动作」本身没法在这里单测（store 经 toastWarning 依赖 antd，');
note('  Node 加载不了），只由 tsc + build 与后续浏览器走查覆盖；本文件锁的是它依赖的契约。');

console.log(`\n${failed === 0 ? '✅' : '❌'} 通过 ${passed} / 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
