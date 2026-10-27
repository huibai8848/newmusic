import type { Id, PageResult } from '@/types/api';
import type {
  ConversationItemVO,
  FeedVO,
  PostType,
  PostVO,
  PrivateMessageVO,
  ProfilePrivacy,
} from '@/types/socialUpgrade';

/**
 * 模块 16 的纯规则函数（可被 Node 直接加载，禁止运行时 import）。
 *
 * 这里集中三件最容易写错的事：
 * 1. **雪花 ID 比较**：19 位 ID 转 Number 会精度丢失，只能按字符串长度+字典序；
 * 2. **时间窗口**：动态编辑 5 分钟、私信撤回 120 秒，边界值必须包含；
 * 3. **分页合并**：会话消息是「最新一页在前、页内正序」，合并时要反向铺平并按 ID 去重。
 */

const POST_EDIT_WINDOW_MS = 5 * 60 * 1000;
const MESSAGE_REVOKE_WINDOW_MS = 120 * 1000;

/** 后端 LocalDateTime 形如 `2026-10-23 10:00:00`；Safari 对空格分隔解析不稳 */
export function parseServerTime(value: string | number | null | undefined): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (!value) return null;
  const parsed = Date.parse(value.replace(' ', 'T'));
  return Number.isNaN(parsed) ? null : parsed;
}

/** 发布后 5 分钟内可编辑；恰好 5 分钟仍可编辑 */
export function canEditPost(createTime: string | null | undefined, now = Date.now()): boolean {
  const created = parseServerTime(createTime);
  if (created === null) return false;
  return now - created <= POST_EDIT_WINDOW_MS;
}

/** 私信撤回窗口 120 秒；恰好 120 秒仍可撤回 */
export function canRevokeMessage(createTime: string | null | undefined, now = Date.now()): boolean {
  const created = parseServerTime(createTime);
  if (created === null) return false;
  return now - created <= MESSAGE_REVOKE_WINDOW_MS;
}

/** 去掉前导零后的数字串比较；不经过 Number，19 位 ID 也不会丢精度 */
function normalizeNumericId(id: string): string {
  const stripped = id.replace(/^0+(?=\d)/, '');
  return stripped;
}

export function compareIds(a: Id, b: Id): number {
  const left = normalizeNumericId(String(a));
  const right = normalizeNumericId(String(b));
  if (left.length !== right.length) return left.length - right.length;
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

/** 置顶优先；其余按最后消息时间倒序，时间相同用消息/会话 ID 倒序兜底 */
export function sortConversations(list: ConversationItemVO[]): ConversationItemVO[] {
  return list.slice().sort((a, b) => {
    if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
    const at = parseServerTime(a.lastMessageTime) ?? 0;
    const bt = parseServerTime(b.lastMessageTime) ?? 0;
    if (at !== bt) return bt - at;
    return compareIds(b.id, a.id);
  });
}

/**
 * 会话消息分页合并。
 *
 * `pages[0]` 是最新一页，页内按时间正序；更早的页在数组后面。
 * 输出正序（旧 → 新）且按消息 ID 去重——WS 新消息与重新拉取重叠时不会出现双气泡。
 */
export function flattenMessagePages(pages: PrivateMessageVO[][]): PrivateMessageVO[] {
  const seen = new Set<string>();
  const merged: PrivateMessageVO[] = [];
  for (let i = pages.length - 1; i >= 0; i -= 1) {
    for (const message of pages[i] ?? []) {
      if (seen.has(message.id)) continue;
      seen.add(message.id);
      merged.push(message);
    }
  }
  return merged.sort((a, b) => compareIds(a.id, b.id));
}

/** cursor 为空字符串时不要发给后端（后端会把它当非法 ID 拒绝） */
export function feedCursorParam(cursor: string | null | undefined): string | undefined {
  const trimmed = (cursor ?? '').trim();
  return trimmed || undefined;
}

/** 主页可见范围 */
export function privacyLevelLabel(level: number | null | undefined): string {
  switch (level) {
    case 1:
      return '仅关注者';
    case 2:
      return '仅自己';
    case 0:
    default:
      return '公开';
  }
}

export function groupRoleLabel(role: number | null | undefined): string {
  switch (role) {
    case 1:
      return '群主';
    case 2:
      return '管理员';
    case 3:
    default:
      return '成员';
  }
}

/** 后端 stats 接口已经按访客算好可见性，这里只做「明确 true 才显示」的收口 */
export function statsFieldVisible(visible: boolean | null | undefined): boolean {
  return visible === true;
}

/**
 * 个人主页隐私开关的两种编码别混用：
 * - 统计项（playDuration/weekCount/…）：0 显示、1 隐藏；
 * - 最近常听/粉丝榜：1 显示、0 隐藏。
 */
export function statsPrivacyEnabled(
  privacy: ProfilePrivacy | null | undefined,
  key: 'playDuration' | 'weekCount' | 'topArtist' | 'genre' | 'listenDays',
): boolean {
  return (privacy?.[key] ?? 0) !== 1;
}

export function displayPrivacyEnabled(
  privacy: ProfilePrivacy | null | undefined,
  key: 'recentListening' | 'fansRank',
): boolean {
  return (privacy?.[key] ?? 0) === 1;
}

export function intimacyVisible(privacy: ProfilePrivacy | null | undefined): boolean {
  // intimacyScore：1 隐藏、0 显示，默认显示
  return (privacy?.intimacyScore ?? 0) !== 1;
}

/** 通知里的数字 targetType → 站内路由；未知类型返回 null，不要瞎拼地址 */
export function notificationTargetLink(
  targetType: number | null | undefined,
  targetId: Id | null | undefined,
): string | null {
  if (targetId === null || targetId === undefined || targetId === '') return null;
  const id = String(targetId);
  switch (targetType) {
    case 1:
      return `/song/${id}`;
    case 2:
      return `/playlist/${id}`;
    case 3:
      return `/album/${id}`;
    case 5:
      return `/user/${id}`;
    case 6:
      // 房间通知只带雪花 roomId，而 C 端路由 `/room/:roomNo` 用的是 6 位房间号，
      // 直接拼会必然打不开；退回房间列表，让用户自己选。
      return '/rooms';
    case 8:
      return '/messages';
    case 9:
      return `/post/${id}`;
    default:
      // 4 评论 / 7 房间消息 / 10 话题：通知只带内部 ID，C 端没有可直达的稳定路由
      return null;
  }
}

export function postTypeLabel(type: PostType | number | null | undefined): string {
  switch (type) {
    case 2:
      return '歌曲';
    case 3:
      return '歌单';
    case 4:
      return '歌词';
    case 5:
      return '打卡';
    case 6:
      return '新建歌单';
    case 7:
      return '转发';
    case 1:
    default:
      return '动态';
  }
}

function mergePost(previous: PostVO, next: PostVO): PostVO {
  return previous.id === next.id ? { ...previous, ...next } : previous;
}

/** 用接口返回的最新 PostVO 就地替换无限流缓存里的同 ID 项（点赞/收藏后避免整页重拉） */
export function replacePostInInfiniteData<T extends { pages: FeedVO[] }>(
  data: T | undefined,
  post: PostVO,
): T | undefined {
  if (!data) return data;
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      list: page.list.map((item) => mergePost(item, post)),
    })),
  };
}

/** 删除/不感兴趣后从无限流里移除 */
export function removePostFromInfiniteData<T extends { pages: FeedVO[] }>(
  data: T | undefined,
  postId: Id,
): T | undefined {
  if (!data) return data;
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      list: page.list.filter((item) => item.id !== postId),
    })),
  };
}

/** 分页列表（个人主页/话题页）里的同 ID 替换 */
export function replacePostInPage<T extends PageResult<PostVO>>(
  data: T | undefined,
  post: PostVO,
): T | undefined {
  if (!data) return data;
  return { ...data, list: data.list.map((item) => mergePost(item, post)) };
}

export function removePostFromPage<T extends PageResult<PostVO>>(
  data: T | undefined,
  postId: Id,
): T | undefined {
  if (!data) return data;
  return { ...data, list: data.list.filter((item) => item.id !== postId) };
}
