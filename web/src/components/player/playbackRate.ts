import { PLAYBACK_RATES } from '@/stores/playerStore';
import { formatRate } from '@/components/player/playbackPolicy';

/** 倍速下拉选项（模块 12 FR-21-002）；展示格式见 `playbackPolicy.formatRate` */
export const RATE_OPTIONS = PLAYBACK_RATES.map((rate) => ({ value: rate, label: formatRate(rate) }));

export { formatRate };
