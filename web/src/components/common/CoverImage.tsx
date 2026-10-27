import { useState } from 'react';
import { CustomerServiceOutlined } from '@ant-design/icons';
import { mediaUrl } from '@/utils/format';

/** 封面图：无地址或加载失败时展示占位（PRD EX-11-001-4，避免破图） */

interface Props {
  src?: string | null;
  alt?: string;
  size?: number;
  /** 圆角；方形封面传 size，圆形传 'circle' */
  shape?: 'square' | 'circle';
  className?: string;
}

export function CoverImage({ src, alt = '封面', size = 56, shape = 'square', className }: Props): JSX.Element {
  const [failed, setFailed] = useState(false);
  const url = mediaUrl(src);
  const radius = shape === 'circle' ? '50%' : 'var(--radius)';

  if (!url || failed) {
    return (
      <div
        className={className}
        role="img"
        aria-label={alt}
        style={{
          width: size,
          height: size,
          borderRadius: radius,
          background: 'var(--color-bg-elevated)',
          border: '1px solid var(--color-border)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--color-text-secondary)',
          flex: '0 0 auto',
        }}
      >
        <CustomerServiceOutlined style={{ fontSize: Math.max(14, size / 3) }} />
      </div>
    );
  }

  return (
    <img
      className={className}
      src={url}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      style={{ width: size, height: size, borderRadius: radius, objectFit: 'cover', flex: '0 0 auto' }}
    />
  );
}
