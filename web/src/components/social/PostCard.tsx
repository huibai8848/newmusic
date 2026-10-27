import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Avatar, Image, Space, Tag, Typography } from 'antd';
import { CustomerServiceOutlined, EnvironmentOutlined, UserOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { mediaUrl } from '@/utils/format';
import { postExcerpt, splitMentions, topicPath } from '@/utils/socialText';
import { postTypeLabel } from '@/utils/socialRules';
import type { Id } from '@/types/api';
import type { PostVO } from '@/types/socialUpgrade';
import { PostActions } from './PostActions';
import styles from './PostCard.module.less';

function timeText(value: string | null | undefined): string {
  if (!value) return '';
  const time = dayjs(value);
  if (!time.isValid()) return '';
  const diff = dayjs().diff(time, 'minute');
  if (diff < 1) return '刚刚';
  if (diff < 60) return `${diff} 分钟前`;
  if (diff < 60 * 24) return `${Math.floor(diff / 60)} 小时前`;
  if (diff < 60 * 24 * 7) return `${Math.floor(diff / (60 * 24))} 天前`;
  return time.format('YYYY-MM-DD');
}

/** 正文中的 @ 提及与换行；只把 clickable 的提及渲染成链接 */
function PostContent({ post }: { post: PostVO }): JSX.Element | null {
  if (!post.content) return null;
  const segments = splitMentions(post.content, post.mentionedUsers);
  return (
    <div className={styles.content}>
      {segments.map((segment, index) => {
        if (segment.type === 'mention' && segment.clickable && segment.userId) {
          return (
            <Link key={index} to={`/user/${segment.userId}`} className={styles.mention}>
              {segment.text}
            </Link>
          );
        }
        return <span key={index}>{segment.text}</span>;
      })}
    </div>
  );
}

function PostImages({ images }: { images: string[] | null }): JSX.Element | null {
  const list = (images ?? []).filter(Boolean).slice(0, 9);
  if (list.length === 0) return null;
  return (
    <Image.PreviewGroup>
      <div className={`${styles.images} ${list.length === 1 ? styles.singleImage : ''}`}>
        {list.map((src) => (
          <Image key={src} src={mediaUrl(src)} alt="动态配图" className={styles.image} />
        ))}
      </div>
    </Image.PreviewGroup>
  );
}

function PostResources({ post }: { post: PostVO }): JSX.Element | null {
  const { song, playlist } = post;
  if (!song && !playlist && !post.lyricSnippet) return null;
  return (
    <div className={styles.resources}>
      {song ? (
        <div className={`${styles.resource} ${song.available === false ? styles.unavailable : ''}`}>
          {song.cover ? (
            <img src={mediaUrl(song.cover)} alt="" className={styles.resourceCover} />
          ) : (
            <span className={styles.resourceCoverPlaceholder}>
              <CustomerServiceOutlined />
            </span>
          )}
          <div className={styles.resourceBody}>
            <Typography.Text strong ellipsis>{song.name}</Typography.Text>
            <Typography.Text type="secondary" ellipsis>
              {song.artistName}
            </Typography.Text>
          </div>
          {song.available === false ? (
            <Tag>已下架</Tag>
          ) : (
            <Link to={`/song/${song.id}`}>去听</Link>
          )}
        </div>
      ) : null}

      {playlist ? (
        <div className={`${styles.resource} ${playlist.available === false ? styles.unavailable : ''}`}>
          {playlist.cover ? (
            <img src={mediaUrl(playlist.cover)} alt="" className={styles.resourceCover} />
          ) : (
            <span className={styles.resourceCoverPlaceholder}>
              <CustomerServiceOutlined />
            </span>
          )}
          <div className={styles.resourceBody}>
            <Typography.Text strong ellipsis>{playlist.name}</Typography.Text>
            <Typography.Text type="secondary">歌单</Typography.Text>
          </div>
          {playlist.available === false ? (
            <Tag>已下架</Tag>
          ) : (
            <Link to={`/playlist/${playlist.id}`}>查看</Link>
          )}
        </div>
      ) : null}

      {post.lyricSnippet ? (
        <blockquote className={styles.lyric}>{post.lyricSnippet}</blockquote>
      ) : null}
    </div>
  );
}

function OriginalPost({ post }: { post: PostVO }): JSX.Element {
  if (post.deleted) {
    return <div className={styles.originalDeleted}>原动态已删除</div>;
  }
  return (
    <Link to={`/post/${post.id}`} className={styles.original}>
      <div className={styles.originalHead}>
        <Avatar size={20} src={mediaUrl(post.author?.avatar)} icon={<UserOutlined />} />
        <Typography.Text strong>{post.author?.nickname ?? '用户'}</Typography.Text>
        <Typography.Text type="secondary" className={styles.originalTime}>
          {timeText(post.createTime)}
        </Typography.Text>
      </div>
      <div className={styles.originalContent}>{postExcerpt(post.content, 140) || '分享了动态'}</div>
      {post.images && post.images.length > 0 ? (
        <div className={styles.originalImages}>
          {post.images.slice(0, 3).map((src) => (
            <img key={src} src={mediaUrl(src)} alt="" />
          ))}
          {post.images.length > 3 ? <span>+{post.images.length - 3}</span> : null}
        </div>
      ) : null}
      {post.song ? (
        <div className={styles.originalResource}>🎵 {post.song.name} - {post.song.artistName}</div>
      ) : null}
      {post.playlist ? (
        <div className={styles.originalResource}>🗂 {post.playlist.name}</div>
      ) : null}
    </Link>
  );
}

interface Props {
  post: PostVO;
  /** detail 模式没有「查看详情」入口，正文与图片全量展示 */
  variant?: 'feed' | 'detail';
  onChange?: (post: PostVO) => void;
  onDeleted?: (id: Id) => void;
  onDislike?: () => void;
}

/**
 * 动态卡片（P0）。
 *
 * 转发只向下渲染一层：后端 `original.original` 可能也存在，但递归渲染会
 * 让一条「转发转发转发」撑爆页面；第二层统一压成摘要。
 */
export function PostCard({
  post,
  variant = 'feed',
  onChange,
  onDeleted,
  onDislike,
}: Props): JSX.Element {
  const navigate = useNavigate();
  const [override, setOverride] = useState<PostVO | null>(null);

  useEffect(() => {
    setOverride(null);
  }, [post]);

  const current = override && override.id === post.id ? override : post;
  const author = current.author;

  const handleChange = (next: PostVO): void => {
    setOverride(next);
    onChange?.(next);
  };

  const scrollToComments = (): void => {
    if (variant === 'detail') {
      document.getElementById('post-comments')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    navigate(`/post/${current.id}`);
  };

  return (
    <article className={`${styles.card} ${variant === 'detail' ? styles.detail : ''}`}>
      <header className={styles.head}>
        <Link to={`/user/${current.userId}`}>
          <Avatar size={variant === 'detail' ? 48 : 40} src={mediaUrl(author?.avatar)} icon={<UserOutlined />} />
        </Link>
        <div className={styles.headBody}>
          <div className={styles.nameRow}>
            <Link to={`/user/${current.userId}`} className={styles.nickname}>
              {author?.nickname ?? '用户'}
            </Link>
            {author?.mutual ? <Tag color="blue">互关</Tag> : null}
            {current.type !== 1 ? <Tag>{postTypeLabel(current.type)}</Tag> : null}
            {current.status === 0 ? <Tag color="gold">审核中</Tag> : null}
            {current.visibility === 2 ? <Tag>仅自己可见</Tag> : null}
            {current.visibility === 1 ? <Tag>仅关注者可见</Tag> : null}
            {current.edited ? <Tag>已编辑</Tag> : null}
          </div>
          <div className={styles.meta}>
            <Link to={`/post/${current.id}`} className={styles.timeLink}>
              {timeText(current.createTime)}
            </Link>
            {current.ipRegion ? (
              <span className={styles.ipRegion}>
                <EnvironmentOutlined /> {current.ipRegion}
              </span>
            ) : null}
          </div>
        </div>
      </header>

      <PostContent post={current} />
      <PostImages images={current.images} />
      <PostResources post={current} />

      {current.topics && current.topics.length > 0 ? (
        <Space size={[6, 6]} wrap className={styles.topics}>
          {current.topics.map((topic) => (
            <Link key={topic} to={topicPath(topic)} className={styles.topic}>
              #{topic}
            </Link>
          ))}
        </Space>
      ) : null}

      {current.original || current.repostPostId ? (
        current.original ? (
          <OriginalPost post={current.original} />
        ) : (
          <div className={styles.originalDeleted}>原动态已删除</div>
        )
      ) : null}

      <footer className={styles.footer}>
        <PostActions
          post={current}
          onChange={handleChange}
          onDeleted={onDeleted}
          onComment={scrollToComments}
          onDislike={onDislike}
          compact={variant === 'feed'}
        />
        {current.viewCount > 0 && variant === 'detail' ? (
          <Typography.Text type="secondary" className={styles.views}>
            {current.viewCount} 次浏览
          </Typography.Text>
        ) : null}
      </footer>
    </article>
  );
}
