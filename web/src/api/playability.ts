import type { Song, SongBrief } from '@/types/api';

/**
 * 可播放性判定（纯函数，零运行时依赖）。
 *
 * <p>从 `player.ts` 拆出来的原因有两个：
 * <ol>
 *   <li>`player.ts` 顶层 import 了 axios 封装，Node 原生擦除类型无法加载，
 *       这块逻辑就没法做零依赖单测；</li>
 *   <li>「按钮是否可用」和「怎么拿签名地址」是两件事，混在一个文件里容易再次
 *       为了拿地址而绕过服务端判定。</li>
 * </ol>
 *
 * <p>**这里的结论只用于置灰/跳过**，真正播放必须走 `fetchPlayUrl`：
 * 签名、过期、审核态、版权到期都只有服务端知道。
 */

export type Quality = 'standard' | 'high' | 'lossless';

/** 音质中文名（播放器菜单展示） */
export const QUALITY_LABEL: Record<Quality, string> = {
  standard: '标准',
  high: '高清',
  lossless: '无损',
};

/** 降级阶梯：请求档位 → 依次尝试的档位（与后端 `PlayUrlServiceImpl.LADDER` 保持一致） */
export const QUALITY_LADDER: Record<Quality, Quality[]> = {
  lossless: ['lossless', 'high', 'standard'],
  high: ['high', 'standard'],
  standard: ['standard'],
};

export interface PlayableSource {
  url: string;
  quality: Quality;
  /** 是否为降级后的实际音质（请求无损但只有标准档时为 true） */
  downgraded: boolean;
}

const FIELD_BY_QUALITY: Record<Quality, keyof SongBrief> = {
  standard: 'audioStandard',
  high: 'audioHigh',
  lossless: 'audioLossless',
};

/**
 * 本地预判可播放地址；按请求音质逐级降级（lossless → high → standard）。
 *
 * **仅用于同步场景**（按钮置灰、从队列里跳过明显不可播的歌）。
 * 版权判断只看得懂 `copyrightStatus` 这一个字段，读不到的规则（审核态、版权到期、
 * 音源文件缺失）一律由服务端判定，所以这里返回非 null **不代表一定能播**。
 *
 * @returns 无可用地址时返回 null
 */
export function resolveSource(song: SongBrief | null, quality: Quality = 'standard'): PlayableSource | null {
  if (!song) return null;
  if (song.copyrightStatus === 0 || song.copyrightStatus === 3) return null;

  for (const q of QUALITY_LADDER[quality]) {
    const url = song[FIELD_BY_QUALITY[q]];
    // trim 后再判空：后台导入的空格串不能当成「有音源」；
    // 返回时也返回 trim 后的值——带首尾空格的 URL 交给 <audio> 一样加载失败
    const trimmed = typeof url === 'string' ? url.trim() : '';
    if (trimmed.length > 0) {
      return { url: trimmed, quality: q, downgraded: q !== quality };
    }
  }
  return null;
}

/**
 * 歌曲是否可播放（用于按钮禁用、「跳过明显不可播的歌」）。
 *
 * 两级判定：
 * 1. 带音源字段（详情/曲库实体）→ 走 {@link resolveSource} 的本地预判；
 * 2. 只有服务端结论（模块 06 搜索等列表 VO 不返回音源地址，但会带 `playable`）→ 以服务端为准。
 *
 * 第 2 条不能省：搜索结果点播放后，队列里存的是 `SearchSongVO`，
 * 没有 `audioStandard`；只看音源字段会把**正在播放**的歌判成不可播，
 * 于是播放/暂停按钮与进度条一起被禁用（表现为「进度条拉不动」）。
 *
 * 两边的否决都生效：
 * - 服务端明确 `playable === false` 时无条件否决：列表 VO 可能仍带一个历史音源地址，
 *   但版权/审核结论已经变了，不能靠本地 URL 把它"救活"。
 * - 本地 `copyrightStatus` 明确是 0/3 时同样否决：服务端标记若因缓存/降级短暂过期，
 *   不能只看 `playable=true` 就放行，否则用户点下去才收到 300xx。
 * 只有「服务端说可播、本地没有明确的版权否决」才放行。
 */
export function isPlayable(song: Song | SongBrief | null | undefined, quality: Quality = 'standard'): boolean {
  if (!song) return false;
  // 曲库实体（Song）没有 playable 字段，用 `in` 收窄后再读，避免为了一个可选字段
  // 给实体类型也塞一个服务端永远不返回的属性
  const serviceFlag = 'playable' in song ? song.playable : undefined;
  if (serviceFlag === false) return false;
  if (resolveSource(song, quality) !== null) return true;
  if (serviceFlag === true) {
    // 本地已有明确的版权否决时，服务端的 true 不能覆盖它（见上方注释）
    return song.copyrightStatus !== 0 && song.copyrightStatus !== 3;
  }
  return false;
}
