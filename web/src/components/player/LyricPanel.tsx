import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Tooltip, Typography } from 'antd';
import { VerticalAlignBottomOutlined } from '@ant-design/icons';
import { usePlayerStore, type LyricSnapshot } from '@/stores/playerStore';
import type { SongBrief } from '@/types/api';
import { displayText, type LyricDisplayMode } from '@/utils/lyricTranslation';
import styles from './LyricPanel.module.less';

/**
 * 歌词面板（模块 07 T707 / `FR-16-007`~`FR-16-011`）。
 *
 * 三个关键实现选择：
 *
 * 1. **行号由 `AudioEngine` 算好写进 store**，这里只订阅 `currentLine`（§5.4）。
 *    面板在播放条与全屏页同时挂两份时，不会有两份二分查找在跑。
 * 2. 跟随播放时滚动靠 `transform: translateY` 而**不是** `scrollTop`：`translateY` 走合成层，
 *    不会因为每秒 4 次的 `timeupdate` 触发布局重算。
 * 3. 用户手动滚动后暂停自动跟随 5 秒（`BR-16-059`），并浮出「恢复自动滚动」按钮——
 *    否则用户会以为自己把歌词滚坏了。
 *
 * 三种模式，只用 `follows` 一个开关区分：
 *
 * | 场景 | `follows` | 行为 |
 * |---|---|---|
 * | 播放中（播放条 / 全屏页） | `true` | `translateY` 跟随 + 点击跳转 |
 * | 歌曲详情页预览（`readOnly`） | `false` | 可滚动、不跟随、点击无效，仅在播放本曲时淡淡高亮当前行 |
 * | 无时间轴（`contentType=3`） | `false` | 纯文本滚动区 |
 */

/** 单行高度（px）。必须与 `.less` 里的 `line-height` 一致，否则居中会偏 */
const LINE_HEIGHT = 36;
/** 可见行数：奇数才能让当前行正好居中 */
const VISIBLE_LINES = 7;
/** 手动滚动后暂停自动跟随的时长（`BR-16-059`） */
const MANUAL_HOLD_MS = 5_000;

export interface LyricPanelProps {
  /** 歌曲信息：无歌词时展示（`BR-16-056`），前奏时显示在首行 */
  song?: SongBrief | null;
  variant?: 'mini' | 'full';
  /**
   * 显式给定歌词数据，**不传则用播放器当前曲目的歌词**。
   *
   * 存在的理由：歌曲详情页要展示的是"这一页这首歌"的歌词，而 store 里的歌词
   * 属于"正在播放的那首"——两者经常不是同一首。传了就用它，能让同一个面板
   * 既做播放器歌词、又做详情页预览。
   */
  lyric?: LyricSnapshot | null;
  /**
   * 只读态：不跟随播放进度、不可点击跳转。用于歌曲详情页的歌词预览（T716）。
   *
   * 只读态在「正在播的就是这首歌」时仍会高亮当前行，因为那时这个高亮是有用的信息；
   * 但**不会**自动滚动，以免用户正在看别处时把视口拽走。
   */
  readOnly?: boolean;
  /**
   * 与 `lyric.lines` **等长**的译文数组，由 `alignTranslations` 算好。
   * 面板不自己对齐：同一个结果要在详情页、播放页共用，对齐规则只该有一份。
   */
  translations?: (string | null)[] | null;
  /** 原文 / 双语 / 仅译文；默认原文 */
  translationMode?: LyricDisplayMode;
  className?: string;
}

export function LyricPanel({
  song,
  variant = 'mini',
  lyric: lyricOverride,
  readOnly = false,
  translations,
  translationMode = 'original',
  className,
}: LyricPanelProps): JSX.Element {
  const storeLyric = usePlayerStore((s) => s.lyric);
  const currentLine = usePlayerStore((s) => s.currentLine);
  const playingSongId = usePlayerStore((s) => s.currentSong?.id);
  const seek = usePlayerStore((s) => s.seek);

  const lyric = lyricOverride ?? storeLyric;

  const [holding, setHolding] = useState(false);
  const holdTimer = useRef<number | null>(null);
  const viewportRef = useRef<HTMLDivElement | null>(null);

  const { lines, timed } = lyric;
  const hasTimeline = timed && lines.length > 0;

  /**
   * 译文数组长度必须与 `lines` 一致才用：长度不一致说明页面拿到的歌词已经换了一首
   * （React Query 数据与路由切换之间的那一帧），此时按行号渲染会把上一首的译文配到这一首。
   */
  const alignedTranslations = translations && translations.length === lines.length ? translations : null;
  const bilingual = translationMode !== 'original' && alignedTranslations !== null &&
    alignedTranslations.some((text) => text !== null);

  /** 只有「播放中 + 有时间轴 + 非只读」才自动跟随 */
  const follows = hasTimeline && !readOnly;

  /**
   * 轨道停在哪个行号。
   *
   * 单独一个 state 而不是直接用 `currentLine`：用户手动滚动（`holding`）期间必须
   * **冻住当前位置**，否则把手从滚轮上松开前就被拽回去了。
   */
  const [pinnedLine, setPinnedLine] = useState(-1);
  useEffect(() => {
    if (follows && !holding) setPinnedLine(currentLine);
  }, [follows, holding, currentLine]);

  /**
   * 面板上的歌就是正在播放的那首吗。
   *
   * 不是的话（详情页在看另一首歌）**绝不高亮、绝不跟随**——store 里的 `currentLine`
   * 属于另一首歌，拿来用会把别人歌里的第 12 行点亮。
   */
  const isPlayingSong = Boolean(song && playingSongId === song.id);

  /** 当前应高亮的行：只有"在放这首"且（跟随中 或 只读预览）时才点亮 */
  const activeLine = isPlayingSong ? (follows ? (holding ? -1 : currentLine) : readOnly ? currentLine : -1) : -1;

  /** 用户手动滚动 → 暂停自动跟随 5 秒 */
  const holdAutoScroll = useCallback(() => {
    if (!follows) return;
    setHolding(true);
    if (holdTimer.current !== null) window.clearTimeout(holdTimer.current);
    holdTimer.current = window.setTimeout(() => {
      holdTimer.current = null;
      setHolding(false);
    }, MANUAL_HOLD_MS);
  }, [follows]);

  useEffect(
    () => () => {
      if (holdTimer.current !== null) window.clearTimeout(holdTimer.current);
    },
    [],
  );

  /**
   * 只读态的兜底滚动：不跟随进度，但把"正在播放的那行"滚进视野一次。
   *
   * 只在只读态 + 在放这首时生效——跟随态由 `translateY` 负责，两种滚动方式混用会互相打架。
   */
  useEffect(() => {
    if (follows || !hasTimeline || !isPlayingSong || currentLine < 0) return;
    const el = viewportRef.current?.querySelector<HTMLElement>(`[data-line="${currentLine}"]`);
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [follows, hasTimeline, isPlayingSong, currentLine]);

  /**
   * 轨道位移：让第 `pinnedLine` 行落在容器正中（容器高 = `VISIBLE_LINES` 行）。
   * 容器中心在 `VISIBLE_LINES/2 * LINE_HEIGHT`，该行中心在 `line * LINE_HEIGHT + LINE_HEIGHT/2`。
   *
   * 前奏时 `pinnedLine === -1`，正好对应插在歌词第 0 行之前的那条歌曲信息行。
   */
  const offset = follows
    ? (VISIBLE_LINES / 2) * LINE_HEIGHT - (pinnedLine * LINE_HEIGHT + LINE_HEIGHT / 2)
    : 0;

  const stopHolding = (): void => {
    setHolding(false);
    if (holdTimer.current !== null) {
      window.clearTimeout(holdTimer.current);
      holdTimer.current = null;
    }
  };

  const handleLineClick = (time: number): void => {
    if (!follows) return;
    seek(time);
    // 点击跳转后立刻恢复自动跟随（BR-16-058）
    stopHolding();
  };

  // ---------------------------------------------------------------- 无歌词兜底（BR-16-056）
  if (lines.length === 0) {
    return (
      <div className={[styles.panel, styles[variant], className].filter(Boolean).join(' ')}>
        <div className={styles.empty}>
          <Typography.Text type="secondary">该歌曲暂无歌词</Typography.Text>
          {song ? (
            <div className={styles.emptySong}>
              <div className={styles.emptyName}>{song.name}</div>
              <div className={styles.emptyArtist}>{song.artistName}</div>
            </div>
          ) : null}
        </div>
      </div>
    );
  }

  return (
    <div className={[styles.panel, styles[variant], className].filter(Boolean).join(' ')}>
      <div
        ref={viewportRef}
        className={follows ? styles.viewportFollow : styles.viewportScroll}
        onWheel={holdAutoScroll}
        onTouchMove={holdAutoScroll}
      >
        {follows ? (
          // follows ⇒ hasTimeline，所以这里一定有时间轴
          <div className={styles.track} style={{ transform: `translateY(${offset}px)` }}>
            {pinnedLine < 0 ? (
              <div className={`${styles.line} ${styles.lineIntro}`}>
                {song ? `${song.name} · ${song.artistName}` : '前奏'}
              </div>
            ) : null}
            {lines.map((line, index) => (
              <div
                key={`${line.rawTime}-${index}`}
                className={lineClass(index, activeLine, currentLine, true)}
                onClick={() => handleLineClick(line.time)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') handleLineClick(line.time);
                }}
              >
                <LineText
                  text={line.text}
                  translation={bilingual ? alignedTranslations?.[index] : null}
                  mode={translationMode}
                />
              </div>
            ))}
          </div>
        ) : (
          <div className={styles.plainList}>
            {lines.map((line, index) => (
              <div key={`${line.rawTime}-${index}`} data-line={index} className={lineClass(index, activeLine, currentLine, false)}>
                <LineText
                  text={line.text}
                  translation={bilingual ? alignedTranslations?.[index] : null}
                  mode={translationMode}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {noteText(lyric.hasWordLevel, timed, readOnly, bilingual) ? (
        <div className={styles.note}>{noteText(lyric.hasWordLevel, timed, readOnly, bilingual)}</div>
      ) : null}

      {holding ? (
        <div className={styles.resumeBar}>
          <Button size="small" type="primary" icon={<VerticalAlignBottomOutlined />} onClick={stopHolding}>
            恢复自动滚动
          </Button>
        </div>
      ) : null}

      {follows && !holding ? (
        <Tooltip title="手动滚动歌词后会自动暂停跟随 5 秒">
          <div className={styles.hint}>点击歌词可跳转到该句</div>
        </Tooltip>
      ) : null}
    </div>
  );
}

/**
 * 单行内容：双语时主行 + 副行**叠在同一行容器内**。
 *
 * 不能用两个 36px 的行去放原文与译文——`translateY` 的居中位置是按
 * `LINE_HEIGHT` 写死算的，行高变了整段歌词就会偏出容器中心。
 */
function LineText({
  text,
  translation,
  mode,
}: {
  text: string;
  translation: string | null | undefined;
  mode: LyricDisplayMode;
}): JSX.Element {
  const { primary, secondary } = displayText(text, translation, mode);
  if (!secondary) return <>{primary}</>;
  return (
    <span className={styles.bilingual}>
      <span className={styles.bilingualMain}>{primary}</span>
      <span className={styles.bilingualSub}>{secondary}</span>
    </span>
  );
}

/** 行样式：当前行 / 已播过 / 可点击。抽出来是因为跟随列表和只读列表都要用 */
function lineClass(index: number, activeLine: number, currentLine: number, clickable: boolean): string {
  return [
    styles.line,
    index === activeLine ? styles.lineActive : '',
    activeLine >= 0 && index < currentLine ? styles.linePast : '',
    clickable ? styles.lineClickable : '',
  ]
    .filter(Boolean)
    .join(' ');
}

/** 面板底部的说明文字；几种提示互斥但可叠加，为空时不渲染那一行 */
function noteText(hasWordLevel: boolean, timed: boolean, readOnly: boolean, bilingual: boolean): string {
  return [
    bilingual ? '译文由 AI 生成，仅供参考' : '',
    !timed ? '该歌词没有时间轴，仅按原文展示' : '',
    timed && hasWordLevel ? '逐字歌词本期按整行高亮显示' : '',
    readOnly ? '同步滚动与点击跳转在播放页可用' : '',
  ]
    .filter(Boolean)
    .join(' · ');
}
