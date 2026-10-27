import { Button, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType, TableProps } from 'antd/es/table';
import { CaretRightOutlined, PauseOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import type { SongBrief } from '@/types/api';
import { formatCount, formatDuration } from '@/utils/format';
import { CoverImage } from '@/components/common/CoverImage';
import { HighlightText } from '@/components/common/HighlightText';
import { usePlayerStore } from '@/stores/playerStore';

/**
 * 歌曲列表（歌曲详情页 / 歌单页 / 搜索页 / 后台共用）。
 *
 * 行内播放按钮在当前曲目上切换为暂停态；点击行本身也触发播放（AC-11-001-4 的列表形态）。
 *
 * 泛型 `T extends SongBrief`：后台传入完整的 `Song` 时，`rowActions` 里的实体
 * 也带完整字段（`version`、`status` 等），无需在调用处做类型断言。
 */

/**
 * `lyricFragment` 是可选的：只有搜索接口会带回命中歌词片段，
 * 曲库列表没有这个字段——做成可选属性，`Song` 之类的类型不必为了满足约束而伪造它。
 */
interface Props<T extends SongBrief & { lyricFragment?: string | null }> {
  songs: T[];
  loading?: boolean;
  /** 搜索关键词，用于歌名/歌手高亮 */
  keyword?: string;
  /** 序号起始值（分页时传 (pageNum-1)*pageSize + 1） */
  startIndex?: number;
  onPlay?: (song: T, index: number) => void;
  /** 服务端分页配置；不传则不分页（无限滚动场景由外层 loadMore 驱动） */
  pagination?: TableProps<T>['pagination'];
  /** 多选（后台批量操作） */
  rowSelection?: TableProps<T>['rowSelection'];
  /** 行尾操作区（收藏/编辑/删除…） */
  rowActions?: (song: T, index: number) => ReactNode;
  showAlbum?: boolean;
  showCover?: boolean;
  /** 后台列表：展示上架/审核状态列 */
  showStatus?: boolean;
  /** 搜索页的「歌词」Tab：多一列展示命中的歌词片段 */
  showLyric?: boolean;
  emptyText?: string;
}

/** 审核状态展示文案与颜色（0待审 1通过 2拒绝） */
function auditText(auditStatus?: number): string {
  if (auditStatus === 1) return '审核通过';
  if (auditStatus === 2) return '审核驳回';
  return '待审核';
}

function auditColor(auditStatus?: number): string {
  if (auditStatus === 1) return 'blue';
  if (auditStatus === 2) return 'red';
  return 'gold';
}

export function SongTable<T extends SongBrief & { lyricFragment?: string | null }>({
  songs,
  loading = false,
  keyword,
  startIndex = 1,
  onPlay,
  pagination,
  rowSelection,
  rowActions,
  showAlbum = true,
  showCover = true,
  showStatus = false,
  showLyric = false,
  emptyText = '暂无歌曲',
}: Props<T>): JSX.Element {
  const currentSongId = usePlayerStore((s) => s.currentSong?.id);
  const isPlaying = usePlayerStore((s) => s.isPlaying);

  const columns: ColumnsType<T> = [
    {
      title: '#',
      key: 'index',
      width: 64,
      render: (_v, _record, index) => {
        const isCurrent = songs[index]?.id === currentSongId;
        return (
          <Space size={6}>
            <Button
              type="text"
              size="small"
              aria-label={isCurrent && isPlaying ? '暂停' : '播放'}
              icon={isCurrent && isPlaying ? <PauseOutlined /> : <CaretRightOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                onPlay?.(songs[index], index);
              }}
            />
            {!isCurrent && <span style={{ color: 'var(--color-text-secondary)' }}>{startIndex + index}</span>}
          </Space>
        );
      },
    },
    {
      title: '歌曲',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      render: (_v, record) => (
        <Space size={12}>
          {showCover && <CoverImage src={record.cover} alt={record.name} size={40} />}
          <div style={{ minWidth: 0 }}>
            <div style={{ color: 'var(--color-text)' }}>
              <HighlightText text={record.name} keyword={keyword} />
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>
              <HighlightText text={record.artistName} keyword={keyword} />
            </div>
          </div>
        </Space>
      ),
    },
  ];

  if (showAlbum) {
    columns.push({
      title: '专辑',
      dataIndex: 'albumName',
      key: 'albumName',
      width: 200,
      ellipsis: true,
      responsive: ['lg'],
      render: (value: string | null) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {value ?? '—'}
        </Typography.Text>
      ),
    });
  }

  if (showLyric) {
    // 命中片段由后端在 SQL 里切好并剥掉时间轴（`SearchHit.lyricFragment`），
    // 高亮仍走前端 HighlightText —— 与上面歌名/歌手保持同一套高亮实现，
    // 不必为了一个列去 dangerouslySetInnerHTML 消费后端的 <em> 串
    columns.push({
      title: '歌词',
      key: 'lyric',
      ellipsis: true,
      responsive: ['lg'],
      render: (_v, record) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {record.lyricFragment ? <HighlightText text={record.lyricFragment} keyword={keyword} /> : '—'}
        </Typography.Text>
      ),
    });
  }

  columns.push(
    {
      title: '时长',
      dataIndex: 'duration',
      key: 'duration',
      width: 84,
      align: 'right',
      render: (value: number | null) => (
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {formatDuration(value)}
        </Typography.Text>
      ),
    },
    {
      title: '播放量',
      dataIndex: 'playCount',
      key: 'playCount',
      width: 100,
      align: 'right',
      responsive: ['xl'],
      render: (_v, record) => {
        const count = record.playCount;
        return (
          <Tooltip title={count ? String(count) : undefined}>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              {formatCount(count)}
            </Typography.Text>
          </Tooltip>
        );
      },
    },
  );

  if (showStatus) {
    columns.push({
      title: '状态',
      key: 'status',
      width: 150,
      render: (_v, record) => (
        <Space size={4}>
          <Tag color={record.status === 1 ? 'green' : 'default'}>{record.status === 1 ? '已上架' : '已下架'}</Tag>
          <Tag color={auditColor(record.auditStatus)}>{auditText(record.auditStatus)}</Tag>
        </Space>
      ),
    });
  }

  if (rowActions) {
    columns.push({
      title: '操作',
      key: 'actions',
      width: 220,
      align: 'right',
      render: (_v, record, index) => <Space size={0}>{rowActions(record, index)}</Space>,
    });
  }

  return (
    <Table<T>
      rowKey="id"
      size="middle"
      columns={columns}
      dataSource={songs}
      loading={loading}
      pagination={pagination ?? false}
      rowSelection={rowSelection}
      locale={{ emptyText }}
      onRow={(record) => ({
        onClick: () => {
          const index = songs.findIndex((s) => s.id === record.id);
          onPlay?.(record, index);
        },
        style: { cursor: onPlay ? 'pointer' : 'default' },
      })}
    />
  );
}
