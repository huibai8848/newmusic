import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Avatar,
  Button,
  Dropdown,
  Empty,
  Input,
  List,
  Modal,
  Pagination,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  MessageOutlined,
  PlusOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { socialApi, socialKeys } from '@/api/social';
import { safetyApi } from '@/api/safety';
import { usePlayerStore } from '@/stores/playerStore';
import { toastSuccess } from '@/utils/feedback';
import { CoverImage } from '@/components/common/CoverImage';
import { EmptyState } from '@/components/common/EmptyState';
import { ListSkeleton } from '@/components/common/PageLoading';
import { SongTable } from '@/components/business/SongTable';
import { FollowButton } from './FollowButton';
import type { CollectItem } from '@/types/social';
import type { Id } from '@/types/api';
import {
  REPORT_STATUS_COLOR,
  REPORT_STATUS_LABEL,
  type UserBrief,
} from '@/types/social';
import styles from './UserSocialTabs.module.less';

const PAGE_SIZE = 12;

/** 收藏项按目标类型跳详情；房间没有详情路由，回列表页。 */
function collectPath(item: CollectItem): string | null {
  switch (item.targetType) {
    case 'SONG':
      return `/song/${item.targetId}`;
    case 'PLAYLIST':
      return `/playlist/${item.targetId}`;
    case 'ALBUM':
      return `/album/${item.targetId}`;
    case 'ROOM':
      return '/rooms';
    default:
      return null;
  }
}

/** 我的收藏：收藏夹 + 收藏列表；（0 表示默认收藏夹，与后端约定一致） */
export function CollectTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [folder, setFolder] = useState<'all' | '0' | string>('all');
  const [page, setPage] = useState(1);
  const [folderModal, setFolderModal] = useState<{ mode: 'create' | 'rename'; id?: Id } | null>(null);
  const [folderName, setFolderName] = useState('');

  const folders = useQuery({
    queryKey: socialKeys.collectFolders,
    queryFn: socialApi.collectFolders,
  });

  const collects = useQuery({
    queryKey: socialKeys.collects({
      folderId: folder === 'all' ? undefined : folder,
      page,
    }),
    queryFn: () =>
      socialApi.collects({
        folderId: folder === 'all' ? undefined : folder,
        page,
        size: PAGE_SIZE,
      }),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['social', 'collects'] });
    void queryClient.invalidateQueries({ queryKey: socialKeys.collectFolders });
  };

  const uncollect = useMutation({
    mutationFn: (item: CollectItem) => socialApi.uncollect(item.targetType, item.targetId),
    onSuccess: () => {
      toastSuccess('已取消收藏');
      refresh();
    },
  });

  const move = useMutation({
    mutationFn: ({ collectId, folderId }: { collectId: Id; folderId: Id | null }) =>
      socialApi.moveCollects([collectId], folderId),
    onSuccess: () => {
      toastSuccess('已移动');
      refresh();
    },
  });

  const createFolder = useMutation({
    mutationFn: (name: string) => socialApi.createFolder(name),
    onSuccess: () => {
      toastSuccess('收藏夹已创建');
      setFolderModal(null);
      setFolderName('');
      refresh();
    },
  });

  const renameFolder = useMutation({
    mutationFn: ({ id, name }: { id: Id; name: string }) => socialApi.renameFolder(id, name),
    onSuccess: () => {
      toastSuccess('已重命名');
      setFolderModal(null);
      setFolderName('');
      refresh();
    },
  });

  const deleteFolder = useMutation({
    mutationFn: (id: Id) => socialApi.deleteFolder(id),
    onSuccess: () => {
      toastSuccess('收藏夹已删除，内容已移入默认收藏夹');
      setFolder('all');
      refresh();
    },
  });

  const folderList = folders.data ?? [];
  const items = collects.data?.list ?? [];

  const submitFolder = (): void => {
    const name = folderName.trim();
    if (!name) return;
    if (folderModal?.mode === 'rename' && folderModal.id) {
      renameFolder.mutate({ id: folderModal.id, name });
    } else {
      createFolder.mutate(name);
    }
  };

  return (
    <div className={styles.collectLayout}>
      <aside className={styles.folderPane}>
        <Button
          block
          type={folder === 'all' ? 'primary' : 'text'}
          icon={<FolderOutlined />}
          onClick={() => {
            setFolder('all');
            setPage(1);
          }}
        >
          全部收藏
        </Button>
        {folderList.map((item) => {
          const value = item.id ? String(item.id) : '0';
          return (
            <div key={value} className={styles.folderRow}>
              <Button
                block
                type={folder === value ? 'primary' : 'text'}
                onClick={() => {
                  setFolder(value);
                  setPage(1);
                }}
              >
                <span className={styles.folderName}>{item.name}</span>
                <span className={styles.folderCount}>{item.itemCount}</span>
              </Button>
              {!item.isDefault ? (
                <Space size={0} className={styles.folderActions}>
                  <Button
                    type="text"
                    size="small"
                    icon={<EditOutlined />}
                    aria-label="重命名收藏夹"
                    onClick={() => {
                      setFolderName(item.name);
                      setFolderModal({ mode: 'rename', id: item.id as Id });
                    }}
                  />
                  <Popconfirm
                    title="删除收藏夹？"
                    description="夹内收藏会移入默认收藏夹"
                    okText="删除"
                    cancelText="取消"
                    onConfirm={() => item.id && deleteFolder.mutate(item.id)}
                  >
                    <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label="删除收藏夹" />
                  </Popconfirm>
                </Space>
              ) : null}
            </div>
          );
        })}
        <Button
          type="dashed"
          block
          icon={<PlusOutlined />}
          onClick={() => {
            setFolderName('');
            setFolderModal({ mode: 'create' });
          }}
        >
          新建收藏夹
        </Button>
      </aside>

      <div className={styles.collectMain}>
        {collects.isLoading ? (
          <ListSkeleton rows={4} />
        ) : collects.isError ? (
          <EmptyState description="收藏加载失败" />
        ) : items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这里还没有收藏" />
        ) : (
          <div className={styles.collectGrid}>
            {items.map((item) => {
              const path = collectPath(item);
              const moveItems = [
                {
                  key: 'uncollect',
                  label: '取消收藏',
                  danger: true,
                },
                { type: 'divider' as const },
                ...folderList
                  .filter((target) => (target.id ?? '0') !== (item.folderId ?? '0'))
                  .map((target) => ({
                    key: `move:${target.id ?? '0'}`,
                    label: `移到「${target.name}」`,
                  })),
              ];
              return (
                <div key={item.id ?? `${item.targetType}-${item.targetId}`} className={styles.collectCard}>
                  <CoverImage src={item.cover} alt={item.name} size={64} />
                  <div className={styles.collectInfo}>
                    <div className={styles.collectName}>
                      {path ? <Link to={path}>{item.name}</Link> : item.name}
                      {item.offline ? <Tag>已下架</Tag> : null}
                    </div>
                    <div className={styles.collectSub}>
                      {item.targetType} · {item.subtitle ?? ''}
                    </div>
                  </div>
                  <Dropdown
                    trigger={['click']}
                    menu={{
                      items: moveItems,
                      onClick: ({ key }) => {
                        if (!item.id) return;
                        if (key === 'uncollect') {
                          uncollect.mutate(item);
                          return;
                        }
                        const target = key.replace('move:', '');
                        move.mutate({ collectId: item.id, folderId: target === '0' ? null : target });
                      },
                    }}
                  >
                    <Button size="small">管理</Button>
                  </Dropdown>
                </div>
              );
            })}
          </div>
        )}

        {(collects.data?.total ?? 0) > PAGE_SIZE ? (
          <div className={styles.pagination}>
            <Pagination
              current={page}
              pageSize={PAGE_SIZE}
              total={collects.data?.total ?? 0}
              showSizeChanger={false}
              onChange={setPage}
            />
          </div>
        ) : null}
      </div>

      <Modal
        open={Boolean(folderModal)}
        title={folderModal?.mode === 'rename' ? '重命名收藏夹' : '新建收藏夹'}
        okText="保存"
        cancelText="取消"
        confirmLoading={createFolder.isPending || renameFolder.isPending}
        onCancel={() => setFolderModal(null)}
        onOk={submitFolder}
        destroyOnHidden
      >
        <Input
          value={folderName}
          maxLength={30}
          placeholder="收藏夹名称"
          onChange={(e) => setFolderName(e.target.value)}
          onPressEnter={submitFolder}
        />
      </Modal>
    </div>
  );
}

/** 关注/粉丝列表；列表项可直接私信或回关。 */
export function FollowTab(): JSX.Element {
  const navigate = useNavigate();
  const [kind, setKind] = useState<'following' | 'followers'>('following');
  const [page, setPage] = useState(1);

  const query = useQuery({
    queryKey:
      kind === 'following' ? socialKeys.following(undefined, page) : socialKeys.followers(undefined, page),
    queryFn: () =>
      kind === 'following' ? socialApi.following(undefined, page, 20) : socialApi.followers(undefined, page, 20),
  });

  const items = query.data?.list ?? [];

  return (
    <div>
      <div className={styles.tabToolbar}>
        <Segmented
          value={kind}
          onChange={(value) => {
            setKind(value as 'following' | 'followers');
            setPage(1);
          }}
          options={[
            { label: '我的关注', value: 'following' },
            { label: '我的粉丝', value: 'followers' },
          ]}
        />
      </div>

      {query.isLoading ? (
        <ListSkeleton rows={4} />
      ) : query.isError ? (
        <Empty description="列表不可见（对方可能设置了隐私）" />
      ) : items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={kind === 'following' ? '还没有关注任何人' : '还没有粉丝'} />
      ) : (
        <List
          dataSource={items}
          renderItem={(user: UserBrief) => (
            <List.Item
              actions={[
                <FollowButton key="follow" kind="USER" targetId={user.id} size="small" />,
                <Button
                  key="message"
                  size="small"
                  icon={<MessageOutlined />}
                  onClick={() =>
                    navigate(
                      `/messages?userId=${encodeURIComponent(user.id)}&nickname=${encodeURIComponent(
                        user.nickname ?? '用户',
                      )}`,
                    )
                  }
                >
                  私信
                </Button>,
              ]}
            >
              <List.Item.Meta
                avatar={<Avatar src={user.avatar ?? undefined} icon={<UserOutlined />} />}
                title={
                  <Space>
                    {user.nickname ?? '用户'}
                    {user.mutual ? <Tag color="green">互相关注</Tag> : null}
                  </Space>
                }
                description={user.signature ?? '这个人很懒，什么都没写'}
              />
            </List.Item>
          )}
        />
      )}

      {(query.data?.total ?? 0) > 20 ? (
        <div className={styles.pagination}>
          <Pagination
            current={page}
            pageSize={20}
            total={query.data?.total ?? 0}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      ) : null}
    </div>
  );
}

/** 我的举报记录（FR-19-015）。 */
export function ReportsTab(): JSX.Element {
  const [page, setPage] = useState(1);
  const reports = useQuery({
    queryKey: socialKeys.myReports(page),
    queryFn: () => socialApi.myReports(page, 20),
  });
  const reasons = useQuery({
    queryKey: socialKeys.reportReasons,
    queryFn: socialApi.reportReasons,
    staleTime: 10 * 60_000,
  });

  const reasonLabel = (code: string): string =>
    reasons.data?.find((item) => item.code === code)?.label ?? code;
  const items = reports.data?.list ?? [];

  if (reports.isLoading) return <ListSkeleton rows={4} />;
  if (reports.isError) return <Empty description="举报记录加载失败" />;
  if (items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有举报记录" />;
  }

  return (
    <div>
      <List
        dataSource={items}
        renderItem={(report) => (
          <List.Item>
            <List.Item.Meta
              title={
                <Space wrap>
                  <Tag color={REPORT_STATUS_COLOR[report.status] ?? 'default'}>
                    {REPORT_STATUS_LABEL[report.status] ?? `状态 ${report.status}`}
                  </Tag>
                  <Typography.Text strong>{reasonLabel(report.reasonType)}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {report.targetType} · {report.targetName ?? report.targetId}
                  </Typography.Text>
                </Space>
              }
              description={
                <div className={styles.reportBody}>
                  {report.reason ? <div>补充说明：{report.reason}</div> : null}
                  {report.handleReason ? <div>处理说明：{report.handleReason}</div> : null}
                  {report.actionTaken ? <div>处置结果：{report.actionTaken}</div> : null}
                  <div className={styles.reportTime}>
                    提交于 {dayjs(report.createTime).format('YYYY-MM-DD HH:mm')}
                    {report.handledAt
                      ? ` · 处理于 ${dayjs(report.handledAt).format('YYYY-MM-DD HH:mm')}`
                      : ''}
                  </div>
                </div>
              }
            />
          </List.Item>
        )}
      />
      {(reports.data?.total ?? 0) > 20 ? (
        <div className={styles.pagination}>
          <Pagination
            current={page}
            pageSize={20}
            total={reports.data?.total ?? 0}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      ) : null}
    </div>
  );
}

const APPEAL_STATUS: Record<number, { label: string; color: string }> = {
  0: { label: '待处理', color: 'orange' },
  1: { label: '已通过', color: 'green' },
  2: { label: '已驳回', color: 'red' },
};

/** 我的申诉（模块 18）：内容误判或账号处置申诉，7 天内每处置 1 次、每日最多 3 次。 */
export function AppealsTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState(false);
  const [targetType, setTargetType] = useState<'CONTENT' | 'ACCOUNT'>('CONTENT');
  const [targetId, setTargetId] = useState('');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');

  const appeals = useQuery({
    queryKey: ['safety', 'my-appeals', page],
    queryFn: () => safetyApi.appeals.my(page, 20),
  });

  const create = useMutation({
    mutationFn: () =>
      safetyApi.appeals.create({
        targetType,
        targetId: targetId.trim(),
        reason: reason.trim(),
        evidenceImages: evidence.trim()
          ? evidence.split(',').map((item) => item.trim()).filter(Boolean)
          : undefined,
      }),
    onSuccess: () => {
      toastSuccess('申诉已提交，等待人工复核');
      setOpen(false);
      setTargetId('');
      setReason('');
      setEvidence('');
      void queryClient.invalidateQueries({ queryKey: ['safety', 'my-appeals'] });
    },
  });

  const items = appeals.data?.list ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Typography.Text type="secondary">
          内容申诉填审核记录 ID，账号申诉填处置记录 ID；7 天内且每处置仅可申诉一次。
        </Typography.Text>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          发起申诉
        </Button>
      </div>

      {appeals.isLoading ? (
        <ListSkeleton rows={4} />
      ) : appeals.isError ? (
        <Empty description="申诉记录加载失败" />
      ) : items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有申诉记录" />
      ) : (
        <>
          <List
            dataSource={items}
            renderItem={(appeal) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <Tag color={APPEAL_STATUS[appeal.status]?.color}>
                        {APPEAL_STATUS[appeal.status]?.label ?? `状态 ${appeal.status}`}
                      </Tag>
                      <Tag color={appeal.targetType === 'CONTENT' ? 'blue' : 'purple'}>
                        {appeal.targetType === 'CONTENT' ? '内容申诉' : '账号处置申诉'}
                      </Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        目标 {appeal.targetId}
                      </Typography.Text>
                    </Space>
                  }
                  description={
                    <div className={styles.reportBody}>
                      <div>申诉理由：{appeal.reason}</div>
                      {appeal.result ? <div>处理结果：{appeal.result}</div> : null}
                      <div className={styles.reportTime}>
                        提交于 {dayjs(appeal.createTime).format('YYYY-MM-DD HH:mm')}
                        {appeal.handledAt
                          ? ` · 处理于 ${dayjs(appeal.handledAt).format('YYYY-MM-DD HH:mm')}`
                          : appeal.slaDeadline
                            ? ` · 承诺处理截止 ${dayjs(appeal.slaDeadline).format('MM-DD HH:mm')}`
                            : ''}
                      </div>
                    </div>
                  }
                />
              </List.Item>
            )}
          />
          {(appeals.data?.total ?? 0) > 20 ? (
            <div className={styles.pagination}>
              <Pagination
                current={page}
                pageSize={20}
                total={appeals.data?.total ?? 0}
                showSizeChanger={false}
                onChange={setPage}
              />
            </div>
          ) : null}
        </>
      )}

      <Modal
        title="发起申诉"
        open={open}
        confirmLoading={create.isPending}
        okButtonProps={{ disabled: !targetId.trim() || reason.trim().length < 2 }}
        onOk={() => create.mutate()}
        onCancel={() => setOpen(false)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%', marginTop: 12 }}>
          <Select
            value={targetType}
            style={{ width: '100%' }}
            options={[
              { value: 'CONTENT', label: '内容被拦截/删除（填审核记录 ID）' },
              { value: 'ACCOUNT', label: '账号处置（填处置记录 ID）' },
            ]}
            onChange={(value) => setTargetType(value as 'CONTENT' | 'ACCOUNT')}
          />
          <Input
            value={targetId}
            placeholder={targetType === 'CONTENT' ? '审核记录 ID' : '处置记录 ID'}
            onChange={(event) => setTargetId(event.target.value)}
          />
          <Input.TextArea
            rows={4}
            value={reason}
            maxLength={500}
            placeholder="请说明申诉理由（至少 2 字）"
            onChange={(event) => setReason(event.target.value)}
          />
          <Input
            value={evidence}
            placeholder="证据图片 URL，多个用逗号分隔（可选）"
            onChange={(event) => setEvidence(event.target.value)}
          />
        </Space>
      </Modal>
    </div>
  );
}

/** 我喜欢的歌曲（模块 10 点赞落库后的列表）。 */
export function LikedSongsTab(): JSX.Element {
  const [page, setPage] = useState(1);
  const playList = usePlayerStore((s) => s.playList);

  const query = useQuery({
    queryKey: ['social', 'liked-songs', page],
    queryFn: () => socialApi.likedSongs(page, 20),
  });

  if (query.isLoading) return <ListSkeleton rows={5} />;
  if (query.isError) return <Empty description="喜欢的歌曲加载失败" />;
  const songs = query.data?.list ?? [];
  if (songs.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有喜欢的歌曲" />;
  }

  return (
    <>
      <SongTable songs={songs} onPlay={(_song, index) => playList(songs, index, 'playlist')} />
      {(query.data?.total ?? 0) > 20 ? (
        <div className={styles.pagination}>
          <Pagination
            current={page}
            pageSize={20}
            total={query.data?.total ?? 0}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      ) : null}
    </>
  );
}
