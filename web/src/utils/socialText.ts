import type { Id } from '@/types/api';
import type { MentionRefBody, MentionedUser } from '@/types/socialUpgrade';

/**
 * 动态正文里的 @ 提及与话题文本处理。
 *
 * 后端下发的 `offset/length` 以 **UTF-16 码元**计，与 JS 的 `slice` 完全一致，
 * 所以中文、emoji 代理对都不需要额外换算；但越界、重叠、乱序必须在这里兜住，
 * 否则一条脏数据就能把整条动态渲染成半截。
 *
 * 本文件只允许 `import type`：测试脚本用 Node 原生擦除类型直接加载它，
 * 任何运行时 import（包括 `@/` 别名）都会让 `node scripts/test-social.mjs` 失败。
 */

export interface PostTextSegment {
  type: 'text' | 'mention';
  text: string;
  userId?: Id;
  clickable?: boolean;
}

/** 把正文按 @ 提及切成可渲染片段；非法/重叠的提及退化为普通文本 */
export function splitMentions(
  content: string | null | undefined,
  mentions?: MentionedUser[] | null,
): PostTextSegment[] {
  const text = content ?? '';
  if (!text) return [];

  const valid = (mentions ?? [])
    .filter((m) => Number.isInteger(m.offset)
      && Number.isInteger(m.length)
      && m.offset >= 0
      && m.length > 0
      && m.offset + m.length <= text.length)
    .slice()
    .sort((a, b) => a.offset - b.offset);

  const segments: PostTextSegment[] = [];
  let cursor = 0;
  for (const mention of valid) {
    if (mention.offset < cursor) continue; // 与已消费区间重叠，丢弃
    if (mention.offset > cursor) {
      segments.push({ type: 'text', text: text.slice(cursor, mention.offset) });
    }
    segments.push({
      type: 'mention',
      text: text.slice(mention.offset, mention.offset + mention.length),
      userId: mention.userId,
      clickable: mention.clickable !== false,
    });
    cursor = mention.offset + mention.length;
  }
  if (cursor < text.length) {
    segments.push({ type: 'text', text: text.slice(cursor) });
  }
  return segments;
}

export interface MentionCandidate {
  userId: Id;
  nickname: string;
}

/**
 * 提交前重算 @ 偏移。
 *
 * 用户在编辑框里可能先输入 `@昵称` 再改前面的字，前端缓存的 offset 早就失效了；
 * 以最终正文为准重新扫描，是唯一不会把昵称高亮错位的做法。
 * 同一昵称出现多次会生成多条引用；重叠区间只保留先出现的那条。
 */
export function buildMentionRefs(
  content: string | null | undefined,
  users: MentionCandidate[],
): MentionRefBody[] {
  const text = content ?? '';
  if (!text || users.length === 0) return [];

  const found: MentionRefBody[] = [];
  for (const user of users) {
    const nickname = user.nickname?.trim();
    if (!nickname) continue;
    const token = `@${nickname}`;
    let from = 0;
    for (;;) {
      const index = text.indexOf(token, from);
      if (index < 0) break;
      found.push({ userId: user.userId, offset: index, length: token.length });
      from = index + token.length;
    }
  }

  found.sort((a, b) => a.offset - b.offset || b.length - a.length);
  const refs: MentionRefBody[] = [];
  let cursor = 0;
  for (const item of found) {
    if (item.offset < cursor) continue;
    refs.push(item);
    cursor = item.offset + item.length;
  }
  return refs;
}

export interface MentionQuery {
  /** `@` 在正文中的位置 */
  start: number;
  /** `@` 之后到光标之间的关键字，可能为空 */
  query: string;
}

/**
 * 判断光标是否处于一个待补全的 @ 片段中。
 *
 * 只在「最近一个 @ 到光标之间没有空白」时触发；超过 20 字视为普通文本，
 * 避免用户打了一长串才发现弹出的是补全框。
 */
export function guessMentionQuery(
  text: string,
  caret: number,
): MentionQuery | null {
  if (!text) return null;
  const end = Number.isFinite(caret)
    ? Math.max(0, Math.min(text.length, Math.floor(caret)))
    : text.length;
  if (end === 0) return null;
  const start = text.lastIndexOf('@', end - 1);
  if (start < 0 || start >= end) return null;
  const query = text.slice(start + 1, end);
  if (/\s/.test(query)) return null;
  if (query.length > 20) return null;
  return { start, query };
}

/** 列表摘要：压平换行，按 UTF-16 安全截断（不切断 emoji 代理对） */
export function postExcerpt(content: string | null | undefined, max = 80): string {
  const flat = (content ?? '').replace(/\s+/g, ' ').trim();
  const limit = Math.max(0, Math.floor(max));
  if (flat.length <= limit) return flat;
  let end = limit;
  const code = flat.charCodeAt(end - 1);
  if (code >= 0xd800 && code <= 0xdbff) end -= 1;
  return `${flat.slice(0, end)}…`;
}

/** 话题详情路由；空话题回退到动态首页，避免生成 `/topic/` 这种半截地址 */
export function topicPath(name: string | null | undefined): string {
  const trimmed = (name ?? '').trim();
  return trimmed ? `/topic/${encodeURIComponent(trimmed)}` : '/feed';
}
