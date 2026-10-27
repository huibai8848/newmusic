import { RedoOutlined, RetweetOutlined, StepForwardOutlined, SwapOutlined } from '@ant-design/icons';
import type { PlayMode } from '@/stores/playerStore';

/**
 * 播放模式的展示元数据（模块 07 T713）。
 *
 * 抽出来是因为播放条与全屏播放页都要用同一套图标/文案：
 * 两处各写一份必然漂移（改了图标忘了改另一处，用户会以为是两个不同的模式）。
 */

export const MODE_LABEL: Record<PlayMode, string> = {
  order: '顺序播放',
  loop: '列表循环',
  single: '单曲循环',
  shuffle: '随机播放',
};

/**
 * 随机模式用 `SwapOutlined`（交叉双箭头）而不是想象中的 `ShuffleOutlined`：
 * `@ant-design/icons` v5 **没有** shuffle 图标（已核对 `es/icons/` 全量清单），
 * 交叉箭头是官方图标集里语义最接近的一个。将来若要更贴切，只能自绘 SVG。
 */
export const MODE_ICON: Record<PlayMode, JSX.Element> = {
  order: <StepForwardOutlined />,
  loop: <RetweetOutlined />,
  single: <RedoOutlined />,
  shuffle: <SwapOutlined />,
};

/** 四档轮换顺序；与 `playerStore.cyclePlayMode` 的顺序必须一致 */
export const MODE_CYCLE: PlayMode[] = ['order', 'loop', 'single', 'shuffle'];

/**
 * 模式按钮的提示文案。
 *
 * 明确写出「下一档是什么」而不是只显示当前模式：只显示"顺序播放"时，
 * 用户不知道点下去会变成什么（尤其四档轮换里"无空"是反直觉的）。
 */
export function modeTooltip(mode: PlayMode): string {
  const next = MODE_CYCLE[(MODE_CYCLE.indexOf(mode) + 1) % MODE_CYCLE.length];
  return `${MODE_LABEL[mode]}（点击切换到${MODE_LABEL[next]}）`;
}
