/**
 * 播放顺序的纯逻辑（模块 07 T723）。
 *
 * <p><b>为什么单独一个文件</b>：这些函数原本住在 `playerStore.ts` 里，而那个文件经
 * `toastWarning` 间接依赖 `antd`，Node 直接 import 会炸——于是 T717 那套「零依赖断言脚本」
 * 的经验用不上，洗牌去重、`single` 重播、`loop` 回绕这三处**最容易错且最难手工验**的逻辑
 * 一直没有回归网。搬到这个文件之后，本模块除 `import type` 外**没有任何 import**，
 * `node scripts/test-playorder.mjs` 靠 Node 原生擦除 TS 类型就能直接加载。
 *
 * <p><b>依赖方向</b>：`playerStore.ts` → `playOrder.ts`，反向没有。所以这里不许出现
 * zustand、antd、axios 或任何 `@/` 路径的运行时导入；加了就等于把测试门槛又抬回去。
 *
 * <p><b>随机源是入参</b>：`buildShuffleOrder` 的 `random` 默认 `Math.random`，
 * 断言脚本可以注入定种序列，把「洗牌结果」变成可复现的确定值；
 * 真实调用方一个参数都不用多传。
 */

/** 四种播放模式（`BR-16-013`）。`order` 播完即停，`loop` 列表循环，`single` 单曲重播，`shuffle` 随机 */
export type PlayMode = 'order' | 'loop' | 'single' | 'shuffle';

/** 随机源签名。默认 `Math.random`，测试注入定种实现 */
export type RandomFn = () => number;

/**
 * 洗牌：Fisher–Yates（`BR-16-014` 禁止"每次随机取索引"，那会重复播同一首）。
 *
 * @param length 队列长度
 * @param avoidFirst 尽量不放在首位的索引（一般是刚播完的那首）
 * @param random 随机源，测试用
 */
export function buildShuffleOrder(length: number, avoidFirst?: number, random: RandomFn = Math.random): number[] {
  const arr = Array.from({ length }, (_, i) => i);
  for (let i = arr.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  // 洗牌后首位若是刚播完的那首，与第二位交换：避免"随机完第一首还是它"
  if (arr.length > 1 && avoidFirst !== undefined && arr[0] === avoidFirst) {
    [arr[0], arr[1]] = [arr[1], arr[0]];
  }
  return arr;
}

/** 队列增删后重建洗牌序列，游标对齐到当前曲 */
export function reshuffle(
  queueLength: number,
  currentIndex: number,
  random: RandomFn = Math.random,
): { shuffleOrder: number[]; shufflePos: number } {
  const order = buildShuffleOrder(queueLength, undefined, random);
  const pos = order.indexOf(currentIndex);
  return { shuffleOrder: order, shufflePos: pos < 0 ? 0 : pos };
}

/**
 * 切歌落位时把 `shufflePos` 对齐到目标曲在序列里的位置。
 *
 * <p>独立成函数是为了让「游标对不上时退回 0」这条规则可测：`indexOf` 返回 -1
 * （目标曲不在**当前**序列里，例如一轮播完刚重新洗过牌）时退回 0，
 * 而不是让 `shufflePos` 变成负数。
 */
export function shufflePosOf(shuffleOrder: number[], index: number): number {
  return Math.max(0, shuffleOrder.indexOf(index));
}

/**
 * 「下一首」的完整决策结果。
 *
 * <p><b>为什么要连序列一起返回</b>：随机模式在一轮播完时会**重新洗牌**。
 * 如果只把目标索引交出去，调用方就会拿**旧**序列去算落位游标——而序列是 `0..n-1`
 * 的排列，任何合法索引都在里面，于是 `indexOf` 必然命中、落位到"新首曲在旧序列里的位置"，
 * 接下来的「下一首」又沿着旧序列走。结果就是**重新洗牌只决定了一首**，
 * 且紧接着会重播本周期刚放过的歌（违反 `BR-16-014`）。
 * 这个 bug 在 T723 写断言时才被看见，回归断言见 `test-playorder.mjs` 最后一节。
 */
export interface Step {
  /** 目标索引；null = 播放结束（`order` 模式走到末尾） */
  index: number | null;
  /** 这一步之后应当生效的序列（只有随机模式会换） */
  shuffleOrder: number[];
  /** 这一步之后应当生效的游标：目标曲在此序列里的位置 */
  shufflePos: number;
}

/**
 * 队列中的下一步（按播放模式）。**这是 store 唯一该用的入口**——
 * `shuffleOrder` / `shufflePos` 必须原样落位，不能自己再算一遍。
 */
export function advance(
  mode: PlayMode,
  currentIndex: number,
  length: number,
  shuffleOrder: number[],
  shufflePos: number,
  random: RandomFn = Math.random,
): Step {
  /** 序列没变的三种模式：原样带回，调用方无需分支判断 */
  const unchanged: Step = { index: null, shuffleOrder, shufflePos };
  if (length === 0) return unchanged;
  switch (mode) {
    // 单曲循环与列表循环在「切歌」这件事上**完全一致**，真正区分两者的是
    // 「本曲播完要不要重播」——那一段住在 `AudioEngine` 的 `ended` 里读 `repeatOne`
    // 决定就地重播还是 `next()`（D-15），根本不经过这里。
    // 手动点「下一首」必须**切歌**：`BR-16-016`（"单曲循环时点击下一首仍切换到下一首，
    // 不重播当前"）与 `AC-16-002-4` 都写明了。此处原先把 `single` 写成"返回自身"，
    // 导致 store 侧必须再加一段提前返回来"重播本曲"，与 PRD 相反（§9 R-11）。
    case 'single':
    case 'loop':
      return { ...unchanged, index: (currentIndex + 1) % length };
    case 'shuffle': {
      const order = shuffleOrder.length === length ? shuffleOrder : buildShuffleOrder(length, undefined, random);
      if (order.length <= 1) return { index: 0, shuffleOrder: order, shufflePos: 0 };
      const pos = shufflePos + 1;
      if (pos < order.length) return { index: order[pos], shuffleOrder: order, shufflePos: pos };
      // 一轮播完 → 重新洗牌，并排除刚播完的那首当首曲（D-10）。
      // 游标随之落在新序列的第 0 位：新首曲就是 `rebuilt[0]`，下一首从 `rebuilt[1]` 起。
      const rebuilt = buildShuffleOrder(length, currentIndex, random);
      return { index: rebuilt[0] ?? currentIndex, shuffleOrder: rebuilt, shufflePos: 0 };
    }
    case 'order':
    default:
      return { ...unchanged, index: currentIndex + 1 < length ? currentIndex + 1 : null };
  }
}

/**
 * 只关心「下一首是谁」时的投影。store 不要用这个——
 * 它丢掉了序列可能被重建的信息（见 {@link Step}）。
 */
export function nextIndexIn(
  mode: PlayMode,
  currentIndex: number,
  length: number,
  shuffleOrder: number[],
  shufflePos: number,
  random: RandomFn = Math.random,
): number | null {
  return advance(mode, currentIndex, length, shuffleOrder, shufflePos, random).index;
}

/**
 * 队列中的上一个索引（按播放模式）。返回 null 表示**没有上一首**，
 * 由调用方决定怎么办（`playerStore.prev` 的处理是"回到本曲开头"）。
 *
 * <p>与 {@link nextIndexIn} 有一处**刻意的不对称**，照实记下来免得后人以为是笔误：
 * `next` 在 `order` 模式走到末尾返回 `null`（播完即停），而 `prev` 在首个往回走是
 * **回绕到最后一首**（`order` 与 `loop` 都一样）。这是播放器的通行手感——
 * 多数播放器的「上一首」在首曲都回绕，而不是禁用。
 */
export function prevIndexIn(
  mode: PlayMode,
  currentIndex: number,
  length: number,
  shuffleOrder: number[],
  shufflePos: number,
): number | null {
  if (length === 0) return null;
  if (mode === 'shuffle') {
    const order = shuffleOrder.length === length ? shuffleOrder : [];
    const pos = order.length > 0 ? shufflePos - 1 : -1;
    return pos >= 0 ? order[pos] : null;
  }
  return currentIndex - 1 >= 0 ? currentIndex - 1 : length - 1;
}
