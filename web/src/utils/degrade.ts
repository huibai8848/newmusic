/**
 * 降级状态的纯函数（模块 19 FR-28-003 / FR-28-011）。
 *
 * 后端 `/system/degrade-status` 是公开接口，C 端未登录也要能拿到等级与关闭的功能；
 * 这里不碰 window / React，方便 Node 原生擦除 TS 后直接断言。
 */

export interface PublicDegradeStatus {
  level: number;
  levelName: string;
  degraded: boolean;
  disabledFeatures: string[];
  banner: string | null;
  updatedAt: number;
}

/** 功能键 → C 端可读名字；后端新增键时回退到原键，不吞掉信息 */
export const DEGRADE_FEATURE_LABELS: Record<string, string> = {
  'feature.search.suggest': '搜索联想',
  'feature.search.hot': '热搜榜',
  'feature.ai': 'AI 能力',
  'feature.recommend': '推荐流',
  'feature.room.gift': '音乐房礼物',
  'feature.tracking': '行为记录',
  'feature.upload': '上传',
  'feature.social.comment': '评论',
  'feature.social.post': '动态发布',
  'feature.room.chat': '房间聊天',
  'feature.social.message': '私信',
  'feature.write': '写操作',
  'feature.user.profile': '资料编辑',
  'feature.playlist': '歌单编辑',
  'feature.report': '举报',
  'feature.share': '分享',
  'feature.user.register': '注册',
  'feature.safety': '安全与申诉',
};

/** 导航路径依赖的功能；没列出的路径不受降级影响（浏览、播放始终可用） */
const NAV_FEATURE: Record<string, string> = {
  '/recommend': 'feature.recommend',
};

export function degradeFeatureLabel(key: string): string {
  return DEGRADE_FEATURE_LABELS[key] ?? key;
}

export function isFeatureDisabled(
  status: PublicDegradeStatus | null | undefined,
  featureKey: string,
): boolean {
  return Boolean(status?.disabledFeatures?.includes(featureKey));
}

/**
 * 过滤导航：只保留「没有依赖功能」或「依赖功能仍开启」的路径。
 * 未知路径保留——宁可多显示一个入口，也不要因为后端加了新开关把整站导航判空。
 */
export function visibleNavPaths(
  paths: string[],
  status: PublicDegradeStatus | null | undefined,
): string[] {
  return paths.filter((path) => {
    const feature = NAV_FEATURE[path];
    return !feature || !isFeatureDisabled(status, feature);
  });
}

/** 公告文案：优先用后端下发的 banner；后端没给时按已关闭功能兜底拼一句 */
export function degradeBannerText(status: PublicDegradeStatus | null | undefined): string | null {
  if (!status?.degraded) return null;
  if (status.banner) return status.banner;
  const names = (status.disabledFeatures ?? []).map(degradeFeatureLabel);
  return names.length > 0
    ? `系统当前处于${status.levelName}，${names.join('、')}暂不可用`
    : `系统当前处于${status.levelName}，部分功能暂不可用`;
}
