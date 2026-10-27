import type { ReactNode } from 'react';

/**
 * 关键词高亮（PRD BR-11-022）。
 *
 * 用 React 节点切分，**不用** `dangerouslySetInnerHTML`：
 * 搜索词来自用户输入，拼 HTML 就是 XSS 入口。
 */

interface Props {
  text?: string | null;
  keyword?: string;
  className?: string;
}

function escapeRegExp(input: string): string {
  return input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function HighlightText({ text, keyword, className = 'ml-kw' }: Props): JSX.Element {
  const content = text ?? '';
  const kw = keyword?.trim();
  if (!kw || !content) return <>{content}</>;

  const parts = content.split(new RegExp(`(${escapeRegExp(kw)})`, 'gi'));
  const lowerKw = kw.toLowerCase();

  return (
    <>
      {parts.map((part, index) =>
        part.toLowerCase() === lowerKw ? (
          <em key={`${part}-${index}`} className={className}>
            {part}
          </em>
        ) : (
          <span key={`${part}-${index}`}>{part}</span>
        ),
      )}
    </>
  );
}

/** 便捷断言：文本是否命中关键词（用于选择渲染方式） */
export function containsKeyword(text: string | null | undefined, keyword?: string): boolean {
  if (!text || !keyword) return false;
  return text.toLowerCase().includes(keyword.trim().toLowerCase());
}

export type HighlightNode = ReactNode;
