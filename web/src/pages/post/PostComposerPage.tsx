import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Button,
  Card,
  Image,
  Input,
  Radio,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { CloseOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { playlistApi } from '@/api/playlist';
import { postApi, profileApi, socialUpgradeKeys, topicApi } from '@/api/socialUpgrade';
import { searchApi, type SearchPlaylistVO, type SearchSongVO } from '@/api/search';
import { ErrorState } from '@/components/common/ErrorState';
import { PageLoading } from '@/components/common/PageLoading';
import { useUserStore } from '@/stores/userStore';
import { track } from '@/services/track';
import { toastSuccess, toastWarning } from '@/utils/feedback';
import { buildMentionRefs, guessMentionQuery } from '@/utils/socialText';
import { privacyLevelLabel } from '@/utils/socialRules';
import type { UserBrief } from '@/types/social';
import type {
  MentionCandidate,
  PostSongCard,
  PostType,
  PostVisibility,
} from '@/types/socialUpgrade';
import styles from './PostComposerPage.module.less';

const TYPE_OPTIONS: Array<{ value: PostType; label: string; hint: string }> = [
  { value: 1, label: '文本', hint: '记录此刻' },
  { value: 2, label: '歌曲', hint: '分享一首歌' },
  { value: 3, label: '歌单', hint: '分享一个歌单' },
  { value: 4, label: '歌词', hint: '分享一段歌词' },
  { value: 5, label: '打卡', hint: '今天也在听歌' },
  { value: 6, label: '我的歌单', hint: '只分享自己创建的歌单' },
];

const MAX_IMAGES = 9;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/**
 * 发布/编辑动态（FR-25-002）。
 *
 * 六种类型共用一套正文与配图；类型 2/4 需要歌曲，3/6 需要歌单。
 * 编辑模式只允许改正文/图片/话题/@/可见范围——类型与关联内容在发布时就定了，
 * 中途换歌会让已投递到关注流的卡片语义错位。
 */
export function PostComposerPage(): JSX.Element {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const isLogin = useUserStore((s) => s.isLogin);
  const editId = params.get('editId');

  const detail = useQuery({
    queryKey: socialUpgradeKeys.post(editId ?? ''),
    queryFn: () => postApi.detail(editId ?? ''),
    enabled: Boolean(editId),
  });

  const [type, setType] = useState<PostType>(1);
  const [content, setContent] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [song, setSong] = useState<PostSongCard | null>(null);
  const [playlist, setPlaylist] = useState<{ id: string; name: string } | null>(null);
  const [lyric, setLyric] = useState('');
  const [topics, setTopics] = useState<string[]>([]);
  const [topicInput, setTopicInput] = useState('');
  const [visibility, setVisibility] = useState<PostVisibility>(0);
  const [mentions, setMentions] = useState<MentionCandidate[]>([]);
  const [mentionQuery, setMentionQuery] = useState<{ start: number; query: string } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [initialized, setInitialized] = useState(false);

  const [songKeyword, setSongKeyword] = useState('');
  const [playlistKeyword, setPlaylistKeyword] = useState('');

  const textareaRef = useRef<TextAreaRef>(null);
  const caretRef = useRef(0);

  useEffect(() => {
    if (editId) return;
    if (!isLogin) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
    }
  }, [editId, isLogin, location.pathname, location.search, navigate]);

  useEffect(() => {
    const post = detail.data;
    if (!post || initialized) return;
    setType(post.type);
    setContent(post.content ?? '');
    setImages(post.images ?? []);
    setLyric(post.lyricSnippet ?? '');
    setTopics(post.topics ?? []);
    setVisibility(post.visibility ?? 0);
    setSong(post.song ? { ...post.song } : null);
    setPlaylist(post.playlist ? { id: post.playlist.id, name: post.playlist.name } : null);
    setMentions((post.mentionedUsers ?? [])
      .filter((item) => item.nickname)
      .map((item) => ({ userId: item.userId, nickname: item.nickname as string })));
    setInitialized(true);
  }, [detail.data, initialized]);

  const hotTopics = useQuery({
    queryKey: socialUpgradeKeys.topicsHot,
    queryFn: () => topicApi.hot(20),
    staleTime: 5 * 60_000,
  });

  const songSearch = useQuery({
    queryKey: socialUpgradeKeys.userSearch(`song:${songKeyword}`),
    queryFn: () => searchApi.query({ keyword: songKeyword, type: 'song', pageSize: 6 }),
    enabled: songKeyword.trim().length > 0 && (type === 2 || type === 4),
  });

  const playlistSearch = useQuery({
    queryKey: socialUpgradeKeys.userSearch(`playlist:${playlistKeyword}`),
    queryFn: () => searchApi.query({ keyword: playlistKeyword, type: 'playlist', pageSize: 6 }),
    enabled: playlistKeyword.trim().length > 0 && type === 3,
  });

  const myPlaylists = useQuery({
    queryKey: ['playlist', 'list', { mine: true, composer: true }],
    queryFn: () => playlistApi.list({ mine: true, pageNum: 1, pageSize: 50 }),
    enabled: type === 6,
  });

  const userSearch = useQuery({
    queryKey: socialUpgradeKeys.userSearch(mentionQuery?.query ?? ''),
    queryFn: () => profileApi.searchUsers(mentionQuery?.query ?? '', 8),
    enabled: Boolean(mentionQuery && mentionQuery.query.trim().length > 0),
    staleTime: 30_000,
  });

  const createMutation = useMutation({
    mutationFn: () => postApi.create({
      type,
      content: content.trim() || undefined,
      images,
      songId: type === 2 || type === 4 ? song?.id : undefined,
      playlistId: type === 3 || type === 6 ? playlist?.id : undefined,
      lyricSnippet: type === 4 ? lyric.trim() : undefined,
      topics,
      mentions: buildMentionRefs(content, mentions),
      visibility,
    }),
    onSuccess: (post) => {
      toastSuccess(post.status === 0 ? '已提交，审核通过后对其他人可见' : '发布成功');
      track('post_publish_success', {
        post_id: String(post.id),
        post_type: type,
        has_images: images.length > 0,
        has_song: Boolean(song),
        topic_count: topics.length,
        pending: post.status === 0,
      });
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
      navigate(`/post/${post.id}`);
    },
  });

  const updateMutation = useMutation({
    mutationFn: () => postApi.update(editId ?? '', {
      content: content.trim() || undefined,
      images,
      topics,
      mentions: buildMentionRefs(content, mentions),
      visibility,
    }),
    onSuccess: (post) => {
      toastSuccess('已保存');
      queryClient.setQueryData(socialUpgradeKeys.post(post.id), post);
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
      navigate(`/post/${post.id}`);
    },
  });

  const changeType = (next: PostType): void => {
    setType(next);
    if (next !== 2 && next !== 4) setSong(null);
    if (next !== 4) setLyric('');
    if (next !== 3 && next !== 6) setPlaylist(null);
  };

  const updateMentionState = (value: string, caret: number): void => {
    setContent(value);
    caretRef.current = caret;
    setMentionQuery(guessMentionQuery(value, caret));
  };

  const insertMention = (user: UserBrief): void => {
    if (!mentionQuery) return;
    const nickname = user.nickname;
    if (!nickname) return;
    const caret = caretRef.current;
    const next = `${content.slice(0, mentionQuery.start)}@${nickname} ${content.slice(caret)}`;
    setContent(next);
    setMentions((prev) => (prev.some((item) => item.userId === user.id)
      ? prev
      : [...prev, { userId: user.id, nickname }]));
    setMentionQuery(null);
    const position = mentionQuery.start + nickname.length + 2;
    requestAnimationFrame(() => {
      const textarea = textareaRef.current?.resizableTextArea?.textArea;
      textarea?.focus();
      textarea?.setSelectionRange(position, position);
      caretRef.current = position;
    });
  };

  const addTopic = (raw?: string): void => {
    const name = (raw ?? topicInput).replace(/^#/, '').trim();
    if (!name) {
      setTopicInput('');
      return;
    }
    if (name.length < 2 || name.length > 20) {
      toastWarning('话题长度需在 2-20 字之间');
      return;
    }
    if (topics.includes(name)) {
      setTopicInput('');
      return;
    }
    if (topics.length >= 3) {
      toastWarning('最多添加 3 个话题');
      return;
    }
    setTopics((prev) => [...prev, name]);
    setTopicInput('');
  };

  const handleUpload = async (file: File): Promise<void> => {
    if (images.length >= MAX_IMAGES) {
      toastWarning(`最多上传 ${MAX_IMAGES} 张图片`);
      return;
    }
    if (!file.type.startsWith('image/')) {
      toastWarning('只能上传图片');
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      toastWarning('图片不能超过 5MB');
      return;
    }
    setUploading(true);
    try {
      const url = await postApi.uploadImage(file);
      setImages((prev) => [...prev, url].slice(0, MAX_IMAGES));
    } catch {
      // 请求层已提示
    } finally {
      setUploading(false);
    }
  };

  const validate = (): string | null => {
    if (content.length > 500) return '正文最多 500 字';
    if (type === 2 && !song) return '请选择要分享的歌曲';
    if (type === 4 && !song) return '请选择歌词所属的歌曲';
    if (type === 4 && !lyric.trim()) return '请填写歌词片段';
    if ((type === 3 || type === 6) && !playlist) return '请选择要分享的歌单';
    if (type === 1 && !content.trim() && images.length === 0) return '写点什么或上传一张图片';
    if (type === 5 && !content.trim()) return '打卡至少写一句话';
    return null;
  };

  const submit = (): void => {
    const error = validate();
    if (error) {
      toastWarning(error);
      return;
    }
    if (editId) updateMutation.mutate();
    else createMutation.mutate();
  };

  if (editId && detail.isLoading) return <PageLoading tip="动态加载中…" />;
  if (editId && detail.isError) {
    return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
  }

  const songs = (songSearch.data?.list ?? []) as SearchSongVO[];
  const playlists = (playlistSearch.data?.list ?? []) as SearchPlaylistVO[];
  const submitting = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="ml-page">
      <Card
        title={editId ? '编辑动态' : '发布动态'}
        extra={
          <Space>
            <Button onClick={() => navigate(-1)}>取消</Button>
            <Button type="primary" loading={submitting} onClick={submit}>
              {editId ? '保存' : '发布'}
            </Button>
          </Space>
        }
      >
        {!editId ? (
          <div className={styles.typeRow}>
            <Typography.Text type="secondary">类型</Typography.Text>
            <Radio.Group
              value={type}
              onChange={(e) => changeType(e.target.value as PostType)}
              optionType="button"
              buttonStyle="solid"
              options={TYPE_OPTIONS.map((option) => ({ value: option.value, label: option.label }))}
            />
            <Typography.Text type="secondary" className={styles.typeHint}>
              {TYPE_OPTIONS.find((option) => option.value === type)?.hint}
            </Typography.Text>
          </div>
        ) : null}

        <div className={styles.editorWrap}>
          <Input.TextArea
            ref={textareaRef}
            value={content}
            onChange={(e) => updateMentionState(e.target.value, e.target.selectionStart ?? e.target.value.length)}
            onKeyUp={(e) => updateMentionState(content, e.currentTarget.selectionStart ?? content.length)}
            onClick={(e) => updateMentionState(content, e.currentTarget.selectionStart ?? content.length)}
            placeholder="分享你的音乐瞬间… 输入 @ 可以提醒好友"
            maxLength={500}
            showCount
            autoSize={{ minRows: 5, maxRows: 10 }}
          />
          {mentionQuery ? (
            <div className={styles.mentionBox}>
              {mentionQuery.query.trim().length === 0 ? (
                <div className={styles.mentionHint}>继续输入昵称搜索用户</div>
              ) : userSearch.isLoading ? (
                <div className={styles.mentionHint}><Spin size="small" /> 搜索中…</div>
              ) : (userSearch.data ?? []).length === 0 ? (
                <div className={styles.mentionHint}>没有找到相关用户</div>
              ) : (
                (userSearch.data ?? []).map((user) => (
                  <button
                    key={user.id}
                    type="button"
                    className={styles.mentionItem}
                    onClick={() => insertMention(user)}
                  >
                    {user.nickname ?? '用户'}
                    {user.signature ? <span>{user.signature}</span> : null}
                  </button>
                ))
              )}
            </div>
          ) : null}
        </div>

        <div className={styles.imageRow}>
          {images.map((url) => (
            <div key={url} className={styles.imageItem}>
              <Image src={url} alt="配图" width={96} height={96} className={styles.image} />
              <Button
                type="text"
                size="small"
                className={styles.imageRemove}
                icon={<CloseOutlined />}
                aria-label="移除图片"
                onClick={() => setImages((prev) => prev.filter((item) => item !== url))}
              />
            </div>
          ))}
          {images.length < MAX_IMAGES ? (
            <Upload
              accept="image/*"
              showUploadList={false}
              beforeUpload={(file) => {
                void handleUpload(file);
                return Upload.LIST_IGNORE;
              }}
            >
              <button type="button" className={styles.uploadTile} disabled={uploading}>
                {uploading ? <Spin size="small" /> : <PlusOutlined />}
                <span>{images.length}/{MAX_IMAGES}</span>
              </button>
            </Upload>
          ) : null}
        </div>

        {type === 2 || type === 4 ? (
          <div className={styles.block}>
            <Typography.Text strong>选择歌曲</Typography.Text>
            {song ? (
              <div className={styles.selected}>
                <span>{song.name} - {song.artistName}</span>
                <Button type="link" size="small" onClick={() => setSong(null)}>更换</Button>
              </div>
            ) : (
              <>
                <Input.Search
                  placeholder="搜索歌曲"
                  enterButton={<SearchOutlined />}
                  value={songKeyword}
                  onChange={(e) => setSongKeyword(e.target.value)}
                  onSearch={() => undefined}
                  loading={songSearch.isFetching}
                />
                {songs.length > 0 ? (
                  <div className={styles.resultList}>
                    {songs.map((item) => (
                      <button
                        key={item.id}
                        type="button"
                        className={styles.resultItem}
                        onClick={() => setSong({
                          id: item.id,
                          name: item.name,
                          cover: item.cover,
                          artistName: item.artistName,
                          available: true,
                        })}
                      >
                        <span>{item.name}</span>
                        <span className={styles.resultSub}>{item.artistName}</span>
                      </button>
                    ))}
                  </div>
                ) : null}
              </>
            )}
            {type === 4 ? (
              <Input.TextArea
                value={lyric}
                onChange={(e) => setLyric(e.target.value)}
                placeholder="粘贴一段歌词"
                maxLength={100}
                showCount
                autoSize={{ minRows: 2, maxRows: 4 }}
              />
            ) : null}
          </div>
        ) : null}

        {type === 3 || type === 6 ? (
          <div className={styles.block}>
            <Typography.Text strong>{type === 6 ? '选择我的歌单' : '选择歌单'}</Typography.Text>
            {playlist ? (
              <div className={styles.selected}>
                <span>{playlist.name}</span>
                <Button type="link" size="small" onClick={() => setPlaylist(null)}>更换</Button>
              </div>
            ) : type === 6 ? (
              <Select
                showSearch
                placeholder="选择自己创建的歌单"
                style={{ width: '100%' }}
                loading={myPlaylists.isLoading}
                optionFilterProp="label"
                options={(myPlaylists.data?.list ?? []).map((item) => ({
                  value: item.id,
                  label: item.name,
                }))}
                onChange={(value) => {
                  const found = myPlaylists.data?.list.find((item) => item.id === value);
                  if (found) setPlaylist({ id: found.id, name: found.name });
                }}
              />
            ) : (
              <>
                <Input.Search
                  placeholder="搜索公开歌单"
                  enterButton={<SearchOutlined />}
                  value={playlistKeyword}
                  onChange={(e) => setPlaylistKeyword(e.target.value)}
                  loading={playlistSearch.isFetching}
                />
                {playlists.length > 0 ? (
                  <div className={styles.resultList}>
                    {playlists.map((item) => (
                      <button
                        key={item.id}
                        type="button"
                        className={styles.resultItem}
                        onClick={() => setPlaylist({ id: item.id, name: item.name })}
                      >
                        <span>{item.name}</span>
                        <span className={styles.resultSub}>{item.songCount} 首</span>
                      </button>
                    ))}
                  </div>
                ) : null}
              </>
            )}
          </div>
        ) : null}

        <div className={styles.block}>
          <Typography.Text strong>话题</Typography.Text>
          <Space size={[6, 6]} wrap>
            {topics.map((topic) => (
              <Tag
                key={topic}
                closable
                onClose={() => setTopics((prev) => prev.filter((item) => item !== topic))}
              >
                #{topic}
              </Tag>
            ))}
          </Space>
          <Input
            value={topicInput}
            onChange={(e) => setTopicInput(e.target.value)}
            onPressEnter={(e) => {
              e.preventDefault();
              addTopic();
            }}
            placeholder="输入话题后回车（2-20 字，最多 3 个）"
            maxLength={20}
            disabled={topics.length >= 3}
          />
          {(hotTopics.data ?? []).length > 0 ? (
            <Space size={[6, 6]} wrap className={styles.hotTopics}>
              <Typography.Text type="secondary">热门：</Typography.Text>
              {(hotTopics.data ?? []).slice(0, 8).map((topic) => (
                <Tag
                  key={topic.id}
                  className={styles.hotTopic}
                  onClick={() => addTopic(topic.name)}
                >
                  #{topic.name}
                </Tag>
              ))}
            </Space>
          ) : null}
        </div>

        <div className={styles.block}>
          <Typography.Text strong>谁可以看</Typography.Text>
          <Radio.Group
            value={visibility}
            onChange={(e) => setVisibility(e.target.value as PostVisibility)}
          >
            {[0, 1, 2].map((level) => (
              <Radio key={level} value={level}>{privacyLevelLabel(level)}</Radio>
            ))}
          </Radio.Group>
        </div>

        <div className={styles.footer}>
          <Button onClick={() => navigate(-1)}>取消</Button>
          <Button type="primary" loading={submitting} onClick={submit}>
            {editId ? '保存' : '发布'}
          </Button>
        </div>
      </Card>
    </div>
  );
}
