import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button, Input, message, Modal, Segmented, Space, Tag, Tooltip, Typography } from 'antd';
import { CaretRightOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { recommendApi, recommendKeys } from '@/api/recommend';
import { songApi, songKeys } from '@/api/song';
import type { Song } from '@/types/api';
import { formatCount, formatDuration } from '@/utils/format';
import { isPlayable, QUALITY_LABEL, resolveSource } from '@/api/player';
import { usePlayerStore, type LyricSnapshot } from '@/stores/playerStore';
import { useLyric } from '@/hooks/useLyric';
import { useLyricTranslation, useSongEmotion } from '@/hooks/useLyricTranslation';
import { alignTranslations, type LyricDisplayMode } from '@/utils/lyricTranslation';
import { useUserStore } from '@/stores/userStore';
import { StorageKey, readRaw, writeRaw } from '@/utils/storage';
import { CoverImage } from '@/components/common/CoverImage';
import { PageLoading } from '@/components/common/PageLoading';
import { ErrorState } from '@/components/common/ErrorState';
import { SectionBoundary } from '@/components/common/SectionBoundary';
import { AddToPlaylistButton } from '@/components/business/AddToPlaylistButton';
import { ActionButtons } from '@/components/social/ActionButtons';
import { CommentSection } from '@/components/social/CommentSection';
import { RecommendSongCard } from '@/components/business/RecommendSongCard';
import { SongTable } from '@/components/business/SongTable';
import { LyricPanel } from '@/components/player/LyricPanel';
import styles from './SongDetailPage.module.less';

/**
 * 歌曲详情页（模块 02 T113 / PRD FR-11-003；模块 07 T716 改歌词区）。
 *
 * 歌词区从「内联正则去掉时间戳、输出一段纯文本」换成复用 `<LyricPanel readOnly>`：
 * 原来那份正则只认 `.` 或 `:` 分隔的毫秒、不认 `[offset:]`、不排序，与 `utils/lrc.ts`
 * 是两套实现，必然漂移。现在解析只有一处（`utils/lrc.ts`），页面只负责把数据传进去。
 */

const COPYRIGHT_TEXT: Record<number, string> = {
  0: '未授权（不可播放）',
  1: '已授权',
  2: '授权已过期',
  3: '受限（不可播放）',
};

/** 歌词展示模式持久化：用户切到双语后，下一首歌不该又变回原文 */
function readDisplayMode(): LyricDisplayMode {
  const raw = readRaw(StorageKey.LYRIC_DISPLAY_MODE);
  return raw === 'original_translation' || raw === 'translation' ? raw : 'original';
}

/**
 * 译文问题反馈（`BR-23-024`）。
 *
 * 单独一个组件只为把「输入框 + 提交中 + 关闭后清空」三件事收在一起；
 * 反馈正文长度由后端校验（≤200 字），前端也限一下，避免白跑一次请求。
 */
function TranslationFeedback({ onSubmit }: { onSubmit: (issue: string) => Promise<void> }): JSX.Element {
  const [open, setOpen] = useState(false);
  const [issue, setIssue] = useState('');
  const [sending, setSending] = useState(false);

  const submit = async (): Promise<void> => {
    const text = issue.trim();
    if (!text) return;
    setSending(true);
    try {
      await onSubmit(text);
      message.success('感谢反馈，我们会据此改进译文');
      setOpen(false);
      setIssue('');
    } catch {
      // 请求层已 toast，保留弹窗让用户重试
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <Button type="link" size="small" onClick={() => setOpen(true)}>
        译文有问题？
      </Button>
      <Modal
        title="反馈翻译问题"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => void submit()}
        okText="提交"
        confirmLoading={sending}
        okButtonProps={{ disabled: !issue.trim() }}
      >
        <Input.TextArea
          value={issue}
          onChange={(e) => setIssue(e.target.value)}
          rows={4}
          maxLength={200}
          showCount
          placeholder="例如：第 3 句意思译错了 / 译文与原文对不上"
        />
      </Modal>
    </>
  );
}

function MoreSongs({ song }: { song: Song }): JSX.Element {
  const playList = usePlayerStore((s) => s.playList);
  const query = useQuery({
    queryKey: songKeys.list({ artistId: song.artistId, pageSize: 10 }),
    queryFn: () => songApi.list({ artistId: song.artistId, pageSize: 10 }),
  });

  if (query.isLoading) return <PageLoading />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const list = (query.data?.list ?? []).filter((item) => item.id !== song.id);
  if (list.length === 0) return <div className={styles.empty}>暂无同歌手的其它作品</div>;

  return (
    <SongTable
      songs={list}
      showCover={false}
      startIndex={1}
      onPlay={(_s, index) => playList(list, index, 'artist')}
    />
  );
}

/**
 * 相似歌曲（模块 08 BR-17-031）：离线预计算优先，新歌实时计算。
 * 理由统一是「和《当前歌曲》很像」，由服务端生成。
 */
function SimilarSongs({ song }: { song: Song }): JSX.Element {
  const query = useQuery({
    queryKey: recommendKeys.similar(song.id),
    queryFn: () => recommendApi.similar(song.id),
  });

  if (query.isLoading) return <PageLoading />;
  if (query.isError) return <ErrorState compact error={query.error} onRetry={() => void query.refetch()} />;
  const items = query.data?.items ?? [];
  if (items.length === 0) return <div className={styles.empty}>暂无相似歌曲</div>;
  const list = items.map((item) => item.song);

  return (
    <div className={styles.similarGrid}>
      {items.map((item, index) => (
        <RecommendSongCard
          key={String(item.song.id)}
          item={item}
          scene="SIMILAR"
          position={index + 1}
          list={list}
          showFeedback={false}
        />
      ))}
    </div>
  );
}

export function SongDetailPage(): JSX.Element {
  const { id } = useParams<{ id: string }>();
  const playSong = usePlayerStore((s) => s.playSong);
  const currentSongId = usePlayerStore((s) => s.currentSong?.id);

  const songQuery = useQuery({
    queryKey: songKeys.detail(id ?? ''),
    queryFn: () => songApi.detail(id as string),
    enabled: Boolean(id),
  });

  const tagsQuery = useQuery({
    queryKey: songKeys.tags(id ?? ''),
    queryFn: () => songApi.tags(id as string),
    enabled: Boolean(id),
  });

  // 本页的歌词自己取（走 useLyric 的缓存与解析），不吃播放器 store 里那首的数据
  const lyric = useLyric(id);

  const lyricForPanel: LyricSnapshot = {
    lines: lyric.lines,
    timed: lyric.timed,
    hasWordLevel: lyric.hasWordLevel,
    empty: lyric.empty,
    skippedLines: lyric.skippedLines,
  };

  // ---- 模块 14 P1：歌词翻译 / 情绪标注
  const isLogin = useUserStore((s) => s.isLogin);
  const translation = useLyricTranslation(id);
  const emotion = useSongEmotion(id);
  const [displayMode, setDisplayMode] = useState<LyricDisplayMode>(readDisplayMode);

  // 对齐只依赖「歌词行 + 后端译文行」，两者不变就不重算
  const aligned = useMemo(
    () => alignTranslations(lyric.lines, translation.data?.lines),
    [lyric.lines, translation.data?.lines],
  );
  const hasTranslation = Boolean(translation.data?.generated) && aligned.matched > 0;
  // 没有译文时强制回到原文：持久化的「双语」不能把一首还没翻译的歌渲染成空白
  const effectiveMode: LyricDisplayMode = hasTranslation ? displayMode : 'original';

  const changeDisplayMode = (value: string | number): void => {
    const mode = value as LyricDisplayMode;
    setDisplayMode(mode);
    writeRaw(StorageKey.LYRIC_DISPLAY_MODE, mode);
  };

  if (songQuery.isLoading) return <PageLoading />;
  if (songQuery.isError) {
    return <ErrorState title="歌曲加载失败" error={songQuery.error} onRetry={() => void songQuery.refetch()} />;
  }

  const song = songQuery.data;
  if (!song) return <ErrorState title="歌曲不存在" error={new Error('该歌曲可能已下架')} />;

  const playable = isPlayable(song);
  const availability = (['lossless', 'high', 'standard'] as const).map((q) => ({
    quality: q,
    available: resolveSource(song, q) !== null,
  }));

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <CoverImage src={song.cover} alt={song.name} size={200} />
        <div className={styles.info}>
          <h1 className={styles.title}>{song.name}</h1>
          {song.subtitle ? (
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              {song.subtitle}
            </Typography.Text>
          ) : null}

          <div className={styles.meta}>
            <span>
              歌手：
              <Link to={`/artist/${song.artistId}`} style={{ color: 'var(--color-primary)' }}>
                {song.artistName}
              </Link>
              {song.featNames ? ` / ${song.featNames}` : ''}
            </span>
            <span>
              专辑：
              {song.albumId ? (
                <Link to={`/album/${song.albumId}`} style={{ color: 'var(--color-primary)' }}>
                  {song.albumName ?? '未知专辑'}
                </Link>
              ) : (
                '—'
              )}
            </span>
            <span>时长：{formatDuration(song.duration)}</span>
            <span>播放：{formatCount(song.playCount)}</span>
            {song.releaseDate ? <span>发行：{song.releaseDate}</span> : null}
          </div>

          <Space size={6} style={{ marginBottom: 16 }} wrap>
            {song.genre ? <Tag color="blue">{song.genre}</Tag> : null}
            {song.language ? <Tag>{song.language}</Tag> : null}
            {song.era ? <Tag>{song.era}</Tag> : null}
            {(tagsQuery.data ?? []).map((tag) => (
              <Tag key={tag.id} color="geekblue" bordered={false}>
                {tag.name}
              </Tag>
            ))}
          </Space>

          {/*
            情绪/场景标注（FR-23-004）。只在 `annotated=true` 时展示：
            未标注时后端返回的是中性默认值（0.5/0.5），把它当结果展示等于编造数据。
          */}
          {emotion.data?.annotated ? (
            <div className={styles.emotionRow}>
              <span className={styles.emotionLabel}>AI 情绪</span>
              {emotion.data.emotionTags.map((tag) => (
                <Tag key={`e-${tag}`} color="magenta" bordered={false}>
                  {tag}
                </Tag>
              ))}
              {emotion.data.sceneTags.map((tag) => (
                <Tag key={`s-${tag}`} color="cyan" bordered={false}>
                  {tag}
                </Tag>
              ))}
              {emotion.data.source === 'MANUAL' ? <Tag bordered={false}>人工校正</Tag> : null}
              {emotion.data.lowConfidence ? (
                <Tooltip title="AI 对这首歌的判断把握不大，结果仅供参考">
                  <Tag color="orange" bordered={false}>
                    低置信
                  </Tag>
                </Tooltip>
              ) : null}
            </div>
          ) : null}

          <div className={styles.actions}>
            <Button
              type="primary"
              icon={<CaretRightOutlined />}
              disabled={!playable}
              onClick={() => playSong(song)}
            >
              {currentSongId === song.id ? '重新播放' : '播放'}
            </Button>
            <AddToPlaylistButton songIds={[song.id]} />
            <ActionButtons
              targetType="SONG"
              targetId={song.id}
              likeCount={song.likeCount}
              collectCount={song.collectCount}
            />
          </div>

          <div className={styles.meta} style={{ marginBottom: 0 }}>
            <span>版权：{COPYRIGHT_TEXT[song.copyrightStatus] ?? '未知'}</span>
            {song.copyrightOwner ? <span>版权方：{song.copyrightOwner}</span> : null}
            <span>
              可用音质：
              {availability.filter((a) => a.available).map((a) => QUALITY_LABEL[a.quality]).join(' / ') || '无'}
            </span>
          </div>
        </div>
      </div>

      <SectionBoundary title="歌词">
        {/*
          翻译入口只在「适用且有歌词」时出现：中文歌/无歌词后端返回 60006，
          `notApplicable` 为 true 时连按钮都不渲染，避免用户点了才知道没有意义。
        */}
        {!translation.notApplicable && !lyric.empty ? (
          <div className={styles.lyricToolbar}>
            <Segmented
              size="small"
              value={effectiveMode}
              disabled={!hasTranslation}
              onChange={changeDisplayMode}
              options={[
                { label: '原文', value: 'original' },
                { label: '双语', value: 'original_translation' },
                { label: '仅译文', value: 'translation' },
              ]}
            />
            <span className={styles.lyricActions}>
              {hasTranslation ? (
                <>
                  <Typography.Text type="secondary" className={styles.translationMeta}>
                    {translation.data?.fromCache ? '译文（缓存）' : 'AI 译文'}
                  </Typography.Text>
                  <TranslationFeedback onSubmit={translation.submitFeedback} />
                </>
              ) : (
                <Tooltip title={isLogin ? '生成约需数秒，同一首歌只会生成一次' : '登录后可生成 AI 译文'}>
                  <Button
                    size="small"
                    loading={translation.isGenerating}
                    disabled={!isLogin || translation.isLoading}
                    onClick={translation.generate}
                  >
                    生成 AI 译文
                  </Button>
                </Tooltip>
              )}
            </span>
          </div>
        ) : null}

        {lyric.isLoading ? (
          <PageLoading tip="歌词加载中" />
        ) : (
          <LyricPanel
            song={song}
            readOnly
            lyric={lyricForPanel}
            translations={aligned.translations}
            translationMode={effectiveMode}
          />
        )}
      </SectionBoundary>

      <SectionBoundary title="评论">
        <CommentSection targetType="SONG" targetId={song.id} />
      </SectionBoundary>

      <SectionBoundary title="相似歌曲">
        <SimilarSongs song={song} />
      </SectionBoundary>

      <SectionBoundary title="同歌手作品">
        <MoreSongs song={song} />
      </SectionBoundary>
    </div>
  );
}
