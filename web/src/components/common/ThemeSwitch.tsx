import { Dropdown, Button, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import { CheckOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import { useThemeStore, type ThemeMode } from '@/stores/themeStore';

/** 主题切换（PRD §6.2：浅色 / 深色 / 跟随系统） */

const OPTIONS: { key: ThemeMode; label: string }[] = [
  { key: 'light', label: '浅色' },
  { key: 'dark', label: '深色' },
  { key: 'system', label: '跟随系统' },
];

export function ThemeSwitch(): JSX.Element {
  const mode = useThemeStore((s) => s.mode);
  const resolved = useThemeStore((s) => s.resolved);
  const setMode = useThemeStore((s) => s.setMode);

  const items: MenuProps['items'] = OPTIONS.map((opt) => ({
    key: opt.key,
    label: opt.label,
    icon: opt.key === mode ? <CheckOutlined /> : <span style={{ display: 'inline-block', width: 14 }} />,
  }));

  return (
    <Dropdown
      menu={{ items, onClick: ({ key }) => setMode(key as ThemeMode), selectedKeys: [mode] }}
      placement="bottomRight"
    >
      <Tooltip title="主题">
        <Button
          type="text"
          aria-label="切换主题"
          icon={resolved === 'dark' ? <MoonOutlined /> : <SunOutlined />}
        />
      </Tooltip>
    </Dropdown>
  );
}
