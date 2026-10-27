import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/** 首页/详情页的区块外壳：标题 + 「更多」链接 + 内容 */

interface Props {
  title: string;
  /** 标题右侧说明（如「MVP 暂用热度排序」） */
  subtitle?: ReactNode;
  moreText?: string;
  moreTo?: string;
  extra?: ReactNode;
  children: ReactNode;
}

export function Section({ title, subtitle, moreText, moreTo, extra, children }: Props): JSX.Element {
  return (
    <section className="ml-section">
      <div className="ml-section__head">
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, minWidth: 0 }}>
          <h2 className="ml-section__title">{title}</h2>
          {subtitle ? <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{subtitle}</span> : null}
        </div>
        {extra}
        {moreText && moreTo ? (
          <Link className="ml-section__more" to={moreTo}>
            {moreText} ›
          </Link>
        ) : null}
      </div>
      {children}
    </section>
  );
}
