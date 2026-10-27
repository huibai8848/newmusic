import { Modal, Typography } from 'antd';
import { usePlayerStore } from '@/stores/playerStore';

/**
 * 快捷键帮助弹窗（模块 07 T714 / `BR-16-086`）。
 *
 * 用 `Shift + ?` 打开。把键位表**单独做一个弹窗**而不是挂在播放条上的 Tooltip：
 * 九个快捷键塞进 Tooltip 会变成一个必须悬停才看得见的长条，
 * 而快捷键本身是"用户主动想知道"的功能，值得一个能停下来看的面板。
 */

interface HotkeyRow {
  keys: string[];
  action: string;
  note?: string;
}

const ROWS: HotkeyRow[] = [
  { keys: ['空格'], action: '播放 / 暂停' },
  { keys: ['←', '→'], action: '后退 / 前进 5 秒' },
  { keys: ['Shift', '←/→'], action: '后退 / 前进 30 秒' },
  { keys: ['↑', '↓'], action: '音量 ±5%' },
  { keys: ['M'], action: '静音切换' },
  { keys: ['S'], action: '切到随机播放', note: '已在随机模式时重新洗牌' },
  { keys: ['R'], action: '循环模式轮换', note: '顺序 → 列表循环 → 单曲循环 → 随机' },
  { keys: ['Esc'], action: '关闭全屏播放页' },
  { keys: ['Shift', '?'], action: '打开 / 关闭本帮助' },
];

export function PlayerHotkeyHelp(): JSX.Element {
  const open = usePlayerStore((s) => s.hotkeyHelpOpen);
  const setOpen = usePlayerStore((s) => s.setHotkeyHelpOpen);

  return (
    <Modal
      title="播放器快捷键"
      open={open}
      onCancel={() => setOpen(false)}
      footer={null}
      width={420}
      centered
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {ROWS.map((row) => (
          <div key={row.action} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div style={{ flex: '0 0 130px', display: 'flex', gap: 4 }}>
              {row.keys.map((key) => (
                <kbd
                  key={key}
                  style={{
                    padding: '1px 6px',
                    fontSize: 12,
                    fontFamily: 'inherit',
                    color: 'var(--color-text)',
                    background: 'var(--color-bg-elevated)',
                    border: '1px solid var(--color-border)',
                    borderRadius: 4,
                    whiteSpace: 'nowrap',
                  }}
                >
                  {key}
                </kbd>
              ))}
            </div>
            <div style={{ flex: '1 1 auto', minWidth: 0 }}>
              <span style={{ fontSize: 13 }}>{row.action}</span>
              {row.note ? (
                <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 6 }}>
                  {row.note}
                </Typography.Text>
              ) : null}
            </div>
          </div>
        ))}
      </div>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 16, marginBottom: 0 }}>
        焦点在输入框内时快捷键不生效；带 Ctrl / ⌘ / Alt 的组合键不会被拦截。
      </Typography.Paragraph>
    </Modal>
  );
}
