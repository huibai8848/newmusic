#!/usr/bin/env node
/**
 * 模块 16 社交升级纯函数断言。
 *
 * 与其它脚本同一套路：Node 原生擦除 TS 类型，直接 import 两个只含
 * `import type` 的模块。断言重点是**线上最容易翻车的边界**：
 * - UTF-16 offset 与 emoji 代理对；
 * - 5 分钟编辑 / 120 秒撤回的临界值；
 * - 19 位雪花 ID 不能转 Number；
 * - 会话消息多页合并的顺序与去重；
 * - 通知 targetType 的数字编码。
 */

import {
  buildMentionRefs,
  guessMentionQuery,
  postExcerpt,
  splitMentions,
  topicPath,
} from '../src/utils/socialText.ts';
import {
  canEditPost,
  canRevokeMessage,
  compareIds,
  displayPrivacyEnabled,
  feedCursorParam,
  flattenMessagePages,
  groupRoleLabel,
  intimacyVisible,
  notificationTargetLink,
  postTypeLabel,
  privacyLevelLabel,
  removePostFromInfiniteData,
  replacePostInInfiniteData,
  replacePostInPage,
  sortConversations,
  statsFieldVisible,
  statsPrivacyEnabled,
} from '../src/utils/socialRules.ts';

let failed = 0;
let passed = 0;

function eq(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    failed += 1;
    console.log(`✗ ${name}\n    实际: ${a}\n    期望: ${e}`);
  } else {
    passed += 1;
    console.log(`✓ ${name}`);
  }
}

function ok(name, cond, detail) {
  if (cond) {
    passed += 1;
    console.log(`✓ ${name}`);
  } else {
    failed += 1;
    console.log(`✗ ${name}${detail ? `\n    ${detail}` : ''}`);
  }
}

const mention = (userId, offset, length, clickable = true) => ({
  userId, offset, length, clickable, nickname: null,
});

// ---------------------------------------------------------------------------
// 1. @ 片段切分
// ---------------------------------------------------------------------------
console.log('\n— @ 提及切分（UTF-16） —');

eq('无 @ → 单段普通文本', splitMentions('今天天气不错', []), [
  { type: 'text', text: '今天天气不错' },
]);
eq('空正文 → 空数组', splitMentions('', null), []);
eq('空正文但带脏提及 → 空数组', splitMentions(null, [mention('1', 0, 5)]), []);

// 「今天 @小明 一起听歌」：@ 的位置是 3，'@小明' 长度 3
eq('中文正文的单个 @', splitMentions('今天 @小明 一起听歌', [mention('100', 3, 3)]), [
  { type: 'text', text: '今天 ' },
  { type: 'mention', text: '@小明', userId: '100', clickable: true },
  { type: 'text', text: ' 一起听歌' },
]);

// emoji 🎵 占 2 个 UTF-16 码元：'🎵' 后 @ 的 offset 是 2
eq('emoji 代理对之后的 offset 正确', splitMentions('🎵@小明', [mention('7', 2, 3)]), [
  { type: 'text', text: '🎵' },
  { type: 'mention', text: '@小明', userId: '7', clickable: true },
]);

eq('多个 @ 按 offset 排序（输入乱序）', splitMentions('@A 和 @B', [
  mention('2', 5, 2),
  mention('1', 0, 2),
]), [
  { type: 'mention', text: '@A', userId: '1', clickable: true },
  { type: 'text', text: ' 和 ' },
  { type: 'mention', text: '@B', userId: '2', clickable: true },
]);

eq('越界的提及被丢弃', splitMentions('@小明', [mention('1', 3, 3)]), [
  { type: 'text', text: '@小明' },
]);
eq('重叠的提及只保留靠前的', splitMentions('@小明', [
  mention('1', 0, 2),
  mention('2', 1, 2),
]), [
  { type: 'mention', text: '@小', userId: '1', clickable: true },
  { type: 'text', text: '明' },
]);
eq('clickable=false 仍然展示', splitMentions('@机器人', [mention('9', 0, 4, false)]), [
  { type: 'mention', text: '@机器人', userId: '9', clickable: false },
]);

// ---------------------------------------------------------------------------
// 2. 提交前重算 offset
// ---------------------------------------------------------------------------
console.log('\n— buildMentionRefs 重算偏移 —');

eq('无 @ 用户 → 空', buildMentionRefs('你好', [{ userId: '1', nickname: '小明' }]), []);
eq('空昵称用户被忽略', buildMentionRefs('@小明', [{ userId: '1', nickname: '  ' }]), []);
eq('按最终正文重新定位', buildMentionRefs('今天听 @小明 的歌', [
  { userId: '100', nickname: '小明' },
]), [{ userId: '100', offset: 4, length: 3 }]);

eq('同一用户出现两次生成两条', buildMentionRefs('@小明 和 @小明', [
  { userId: '100', nickname: '小明' },
]), [
  { userId: '100', offset: 0, length: 3 },
  { userId: '100', offset: 6, length: 3 },
]);

eq('昵称前缀重叠时保留更长的', buildMentionRefs('@小明明 你好', [
  { userId: '1', nickname: '小明' },
  { userId: '2', nickname: '小明明' },
]), [{ userId: '2', offset: 0, length: 4 }]);

eq('emoji 不影响重算的码元位置', buildMentionRefs('🎵@小明', [
  { userId: '5', nickname: '小明' },
]), [{ userId: '5', offset: 2, length: 3 }]);

// ---------------------------------------------------------------------------
// 3. @ 自动补全触发
// ---------------------------------------------------------------------------
console.log('\n— @ 补全触发 —');

eq('光标在 @ 后', guessMentionQuery('你好@小', 4), { start: 2, query: '小' });
eq('刚输入 @', guessMentionQuery('你好@', 3), { start: 2, query: '' });
eq('@ 后有空格 → 不触发', guessMentionQuery('你好 @小 明', 6), null);
eq('光标在 @ 之前 → 不触发', guessMentionQuery('你好@小', 1), null);
eq('没有 @ → 不触发', guessMentionQuery('你好', 2), null);
eq('关键字超过 20 字 → 不触发', guessMentionQuery(`@${'a'.repeat(21)}`, 22), null);
eq('取最近的 @', guessMentionQuery('@A @B', 5), { start: 3, query: 'B' });
eq('caret 越界自动夹紧', guessMentionQuery('@小明', 999), { start: 0, query: '小明' });

// ---------------------------------------------------------------------------
// 4. 摘要与话题
// ---------------------------------------------------------------------------
console.log('\n— 摘要与话题 —');

eq('摘要压平空白', postExcerpt('第一行\n\n第二行', 80), '第一行 第二行');
ok('超长摘要截断加省略号', postExcerpt('a'.repeat(10), 3) === 'aaa…');
eq('刚好等于上限不截断', postExcerpt('abcde', 5), 'abcde');

const emojiExcerpt = postExcerpt(`${'a'.repeat(79)}🎵tail`, 80);
ok('截断不拆开代理对', emojiExcerpt === `${'a'.repeat(79)}…`, JSON.stringify(emojiExcerpt));
ok('截断结果不含孤立高代理', !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(emojiExcerpt));

eq('话题编码', topicPath('周杰伦 2026'), '/topic/%E5%91%A8%E6%9D%B0%E4%BC%A6%202026');
eq('空话题回退首页', topicPath('   '), '/feed');
eq('undefined 回退首页', topicPath(undefined), '/feed');

// ---------------------------------------------------------------------------
// 5. 时间窗口
// ---------------------------------------------------------------------------
console.log('\n— 5 分钟编辑 / 120 秒撤回 —');

const base = '2026-10-23 10:00:00';
const baseMs = Date.parse('2026-10-23T10:00:00');
eq('恰好 5 分钟仍可编辑', canEditPost(base, baseMs + 5 * 60 * 1000), true);
eq('5 分钟零 1 毫秒不可编辑', canEditPost(base, baseMs + 5 * 60 * 1000 + 1), false);
eq('刚发布可编辑', canEditPost(base, baseMs + 1000), true);
eq('时间解析失败不可编辑', canEditPost('not-a-time', baseMs), false);
eq('恰好 120 秒仍可撤回', canRevokeMessage(base, baseMs + 120 * 1000), true);
eq('120 秒零 1 毫秒不可撤回', canRevokeMessage(base, baseMs + 120 * 1000 + 1), false);
eq('空时间不可撤回', canRevokeMessage(null, baseMs), false);

// ---------------------------------------------------------------------------
// 6. 雪花 ID 与排序
// ---------------------------------------------------------------------------
console.log('\n— 19 位 ID 与排序 —');

ok('19 位 ID 按数值大小比较（不转 Number）',
  compareIds('1000000000000000001', '1000000000000000002') < 0);
ok('位数不同按位数比较', compareIds('999999999999999999', '1000000000000000000') < 0);
eq('前导零不影响相等', compareIds('0012', '12'), 0);
ok('同 ID 返回 0', compareIds('1000000000000000001', '1000000000000000001') === 0);

const conversations = [
  { id: '1000000000000000003', pinned: false, lastMessageTime: '2026-10-23 10:00:00' },
  { id: '1000000000000000001', pinned: false, lastMessageTime: '2026-10-23 10:00:00' },
  { id: '1000000000000000002', pinned: true, lastMessageTime: '2026-10-22 10:00:00' },
  { id: '1000000000000000004', pinned: false, lastMessageTime: '2026-10-24 10:00:00' },
];
eq('置顶优先，其余按时间倒序，同时间按 ID 倒序',
  sortConversations(conversations).map((item) => item.id),
  ['1000000000000000002', '1000000000000000004', '1000000000000000003', '1000000000000000001']);
ok('排序不修改原数组', conversations[0].id === '1000000000000000003');

// ---------------------------------------------------------------------------
// 7. 会话消息分页合并
// ---------------------------------------------------------------------------
console.log('\n— 消息分页合并 —');

const msg = (id, content = '') => ({ id, content, conversationId: '9' });
eq('最新页在前、页内正序 → 输出整体正序', flattenMessagePages([
  [msg('1000000000000000002'), msg('1000000000000000003')],
  [msg('1000000000000000001'), msg('1000000000000000002')],
]).map((item) => item.id), [
  '1000000000000000001',
  '1000000000000000002',
  '1000000000000000003',
]);
eq('单页保持顺序', flattenMessagePages([[msg('2'), msg('3')]]).map((m) => m.id), ['2', '3']);
eq('空页安全', flattenMessagePages([]), []);
eq('空页数组安全', flattenMessagePages([[]]), []);

// ---------------------------------------------------------------------------
// 8. 通知链接编码
// ---------------------------------------------------------------------------
console.log('\n— 通知 targetType → 路由 —');

eq('9 动态', notificationTargetLink(9, '100'), '/post/100');
eq('5 用户', notificationTargetLink(5, '100'), '/user/100');
eq('8 私信', notificationTargetLink(8, '100'), '/messages');
eq('1 歌曲', notificationTargetLink(1, '100'), '/song/100');
eq('6 房间（只有雪花 ID → 列表）', notificationTargetLink(6, '100'), '/rooms');
eq('10 话题暂不跳转', notificationTargetLink(10, '100'), null);
eq('未知类型不跳转', notificationTargetLink(99, '100'), null);
eq('缺 ID 不跳转', notificationTargetLink(9, null), null);

// ---------------------------------------------------------------------------
// 9. 隐私开关编码
// ---------------------------------------------------------------------------
console.log('\n— 主页隐私 —');

eq('统计项默认显示', statsPrivacyEnabled({}, 'playDuration'), true);
eq('统计项 0 显示', statsPrivacyEnabled({ playDuration: 0 }, 'playDuration'), true);
eq('统计项 1 隐藏', statsPrivacyEnabled({ playDuration: 1 }, 'playDuration'), false);
eq('最近常听默认隐藏', displayPrivacyEnabled({}, 'recentListening'), false);
eq('最近常听 1 显示', displayPrivacyEnabled({ recentListening: 1 }, 'recentListening'), true);
eq('粉丝榜 0 隐藏', displayPrivacyEnabled({ fansRank: 0 }, 'fansRank'), false);
eq('亲密度默认显示', intimacyVisible({}), true);
eq('亲密度 1 隐藏', intimacyVisible({ intimacyScore: 1 }), false);
eq('stats 接口 false 不显示', statsFieldVisible(false), false);
eq('stats 接口 undefined 不显示', statsFieldVisible(undefined), false);
eq('stats 接口 true 显示', statsFieldVisible(true), true);

eq('可见范围 0', privacyLevelLabel(0), '公开');
eq('可见范围 1', privacyLevelLabel(1), '仅关注者');
eq('可见范围 2', privacyLevelLabel(2), '仅自己');
eq('可见范围脏值回退公开', privacyLevelLabel(9), '公开');
eq('群角色 1', groupRoleLabel(1), '群主');
eq('群角色 3', groupRoleLabel(3), '成员');
eq('群角色空值', groupRoleLabel(null), '成员');
eq('动态类型 7', postTypeLabel(7), '转发');
eq('动态类型空值', postTypeLabel(null), '动态');

// ---------------------------------------------------------------------------
// 10. cursor 与缓存替换
// ---------------------------------------------------------------------------
console.log('\n— cursor 与缓存 —');

eq('空 cursor 不发送', feedCursorParam(''), undefined);
eq('空白 cursor 不发送', feedCursorParam('   '), undefined);
eq('undefined 不发送', feedCursorParam(undefined), undefined);
eq('正常 cursor 去空格', feedCursorParam(' 10_100 '), '10_100');
eq('发现流偏移 cursor 原样返回', feedCursorParam('20'), '20');

const feedData = {
  pages: [
    { list: [{ id: '1', likeCount: 0 }, { id: '2', likeCount: 0 }], nextCursor: null, hasMore: false },
    { list: [{ id: '3', likeCount: 0 }], nextCursor: null, hasMore: false },
  ],
  pageParams: [undefined],
};
const replaced = replacePostInInfiniteData(feedData, { id: '2', likeCount: 5 });
ok('无限流同 ID 替换', replaced.pages[0].list[1].likeCount === 5);
ok('无限流替换不影响其它项', replaced.pages[0].list[0].likeCount === 0);
ok('无限流替换保留分页参数', replaced.pageParams.length === 1);

const removed = removePostFromInfiniteData(feedData, '2');
ok('无限流删除指定项', removed.pages[0].list.length === 1 && removed.pages[0].list[0].id === '1');

const pageData = { list: [{ id: '1', commentCount: 0 }], total: 1, pageNum: 1 };
const pageReplaced = replacePostInPage(pageData, { id: '1', commentCount: 3 });
eq('分页列表同 ID 替换', pageReplaced.list[0].commentCount, 3);
eq('undefined 数据安全', replacePostInPage(undefined, { id: '1' }), undefined);

console.log(`\n${failed === 0 ? '✅' : '❌'} 通过 ${passed} / 失败 ${failed}`);
process.exit(failed === 0 ? 0 : 1);
