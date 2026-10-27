import { useEffect } from 'react';
import { usePlayerStore } from '@/stores/playerStore';
import { closePlayingOverlay } from '@/components/player/PlayingPage';

/**
 * 播放器全局快捷键（模块 07 T714 / `BR-16-081`~`BR-16-086`）。
 *
 * 三条硬性约束，每条都对应一类"看起来能用、实际很烦人"的问题：
 *
 * 1. **焦点在输入框内一律不响应**（`BR-16-081`、`EX-16-011-3`）。
 *    否则用户在搜索框里打 "sm" 会静音并切到随机——而且他自己不知道为什么。
 * 2. **带 Ctrl / Meta / Alt 的组合键一律放行**，不 preventDefault。
 *    这些是浏览器与输入法的地盘（`Ctrl+←` 是"按词移动光标"），抢过来是 bug 不是功能。
 * 3. **用 `event.code` 判断物理键**而不是 `event.key`：中文输入法激活时
 *    `key` 会变成 `Process`，大写锁定开着时字母会变成大写，用 `key` 会漏判。
 */

/** 方向键单次步长（秒） */
const SEEK_STEP = 5;
/** Shift + 方向键的步长（秒） */
const SEEK_STEP_LARGE = 30;
/** 音量单次步长 */
const VOLUME_STEP = 0.05;

/** 焦点是否在可编辑元素里 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (target.isContentEditable) return true;
  if (target.closest('[contenteditable="true"]') !== null) return true;
  // AntD 的部分组件会用 div 模拟输入框，靠 role 兜底
  return target.getAttribute('role') === 'textbox';
}

export interface UsePlayerHotkeysOptions {
  /** 传 `false` 临时禁用（例如全屏播放页自己接管键盘时） */
  enabled?: boolean;
}

export function usePlayerHotkeys({ enabled = true }: UsePlayerHotkeysOptions = {}): void {
  useEffect(() => {
    if (!enabled) return undefined;

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (isEditableTarget(event.target)) return;

      const state = usePlayerStore.getState();

      switch (event.code) {
        case 'Space':
          // 不 preventDefault 的话空格会滚动页面（BR-16-082）
          event.preventDefault();
          state.toggle();
          return;

        case 'ArrowRight':
        case 'ArrowLeft': {
          event.preventDefault();
          const step = event.shiftKey ? SEEK_STEP_LARGE : SEEK_STEP;
          const delta = event.code === 'ArrowRight' ? step : -step;
          // 时长未知（元数据还没到）时不限上限，让音频元素自己夹到 seekable 范围
          const max = state.duration > 0 ? state.duration : Number.POSITIVE_INFINITY;
          state.seek(Math.min(Math.max(0, state.currentTime + delta), max));
          return;
        }

        case 'ArrowUp':
          event.preventDefault();
          state.setVolume(state.volume + VOLUME_STEP);
          return;

        case 'ArrowDown':
          event.preventDefault();
          state.setVolume(state.volume - VOLUME_STEP);
          return;

        case 'KeyM':
          state.toggleMute();
          return;

        case 'KeyS':
          // 非随机 → 切到随机；已在随机 → 重新洗牌（BR-16-013 的按钮语义）
          state.cycleShuffleSlot();
          return;

        case 'KeyR':
          state.cyclePlayMode();
          return;

        case 'Escape':
          // 只有全屏页需要 Esc；队列抽屉与帮助弹窗由 AntD 自己处理 Esc 关闭
          if (state.overlayOpen) closePlayingOverlay();
          return;

        case 'Slash':
          // Shift + ? 打开快捷键帮助
          if (event.shiftKey) {
            event.preventDefault();
            state.setHotkeyHelpOpen(true);
          }
          return;

        default:
          return;
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled]);
}
