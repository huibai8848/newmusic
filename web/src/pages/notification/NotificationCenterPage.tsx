import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge,
  Button,
  Empty,
  List,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, MessageOutlined, SettingOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { messageCenterApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { socialApi, socialKeys } from '@/api/social';
import { useWsStore } from '@/stores/wsStore';
import { notificationTargetLink } from '@/utils/socialRules';
import { toastSuccess } from '@/utils/feedback';
import {
  NOTIFICATION_TYPE_LABEL,
  type NotificationSetting,
} from '@/types/social';
import type { MessageCategory, MessageCenterItem } from '@/types/socialUpgrade';
import styles from './NotificationCenterPage.module.less';

const PAGE_SIZE = 20;

const TABS: { key: MessageCategory; label: string; empty: string }[] = [
  { key: 'interaction', label: '互动', empty: '暂无互动消息' },
  { key: 'follow', label: '粉丝', empty: '还没有新粉丝' },
  { key: 'message', label: '私信', empty: '暂无私信通知' },
  { key: 'system', label: '系统', empty: '暂无系统消息' },
  { key: 'music', label: '音乐', empty: '暂无音乐动态' },
];

const SETTING_FIELDS: { key: keyof NotificationSetting; label: string; hint: string }[] = [
  { key: 'likeEnabled', label: '点赞', hint: '内容被点赞时通知我' },
  { key: 'commentEnabled', label: '评论与回复', hint: '收到评论或回复时通知我' },
  { key: 'followEnabled', label: '关注', hint: '被新用户关注时通知我' },
  { key: 'messageEnabled', label: '私信', hint: '收到新私信时通知我' },
  { key: 'systemEnabled', label: '系统通知', hint: '审核、举报、公告等系统消息' },
  { key: 'newSongEnabled', label: '新歌提醒', hint: '关注的歌手发布新歌时通知我' },
  { key: 'readReceiptEnabled', label: '已读回执', hint: '对方能看到我已读私信' },
];

/**
 * 消息中心（模块 16 原地升级，FR-19-014 / FR-25-012）。
 *
 * - 五个分类：互动 / 粉丝 / 私信 / 系统 / 音乐，取消「全部」（铃铛仍是全部通知入口）；
 * - 列表走 `/messages/{category}`，私信分类复用模块 10 `/notifications?tab=message`；
 * - 未读数以 `/messages/unread` 为准，并同步到 `wsStore.unread` 供头部铃铛使用；
 * - 全部已读不能传 `category=all`，只能**省略** category。
 */
export function NotificationCenterPage(): JSX.Element {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<MessageCategory>('interaction');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [draft, setDraft] = useState<NotificationSetting | null>(null);

  const wsNotifications = useWsStore((state) => state.notifications);
  const latestNotificationId = wsNotifications[0]?.id ?? '';

  const unreadQuery = useQuery({
    queryKey: socialUpgradeKeys.messageUnread,
    queryFn: messageCenterApi.unread,
    refetchOnWindowFocus: true,
  });

  useEffect(() => {
    if (typeof unreadQuery.data?.total === 'number') {
      useWsStore.setState({ unread: unreadQuery.data.total });
    }
  }, [unreadQuery.data]);

  const query = useInfiniteQuery({
    queryKey: [...socialUpgradeKeys.messageCenter(tab), 'infinite'],
    queryFn: ({ pageParam }) => messageCenterApi.list(tab, pageParam, PAGE_SIZE),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
  });

  const items = useMemo(() => query.data?.pages.flatMap((page) => page.list) ?? [], [query.data]);

  const refresh = (): void => {
    void queryClient.invalidateQueries({
      queryKey: [...socialUpgradeKeys.messageCenter(tab), 'infinite'],
    });
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.messageUnread });
  };

  // 收到 WS 通知时刷新当前分类与角标，不依赖轮询
  useEffect(() => {
    if (!latestNotificationId) return;
    refresh();
    // refresh 只操作 queryClient，不引入额外闭包状态
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [latestNotificationId, tab, queryClient]);

  const readMutation = useMutation({
    mutationFn: (ids: string[]) => messageCenterApi.read(ids),
    onSuccess: refresh,
  });

  const readAllMutation = useMutation({
    mutationFn: () => messageCenterApi.readAll(),
    onSuccess: () => {
      toastSuccess('已全部标记为已读');
      queryClient.invalidateQueries({
        predicate: (q) => q.queryKey[0] === 'social-upgrade' && q.queryKey[1] === 'message-center',
      });
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.messageUnread });
      useWsStore.setState({ unread: 0 });
    },
  });

  const readCategoryMutation = useMutation({
    mutationFn: (category: MessageCategory) => messageCenterApi.readAll(category),
    onSuccess: () => {
      toastSuccess('本分类已标记为已读');
      refresh();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => socialApi.deleteNotification(id),
    onSuccess: refresh,
  });

  const clearMutation = useMutation({
    mutationFn: () => socialApi.clearNotifications(),
    onSuccess: () => {
      toastSuccess('通知已清空');
      queryClient.invalidateQueries({
        predicate: (q) => q.queryKey[0] === 'social-upgrade' && q.queryKey[1] === 'message-center',
      });
      void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.messageUnread });
      useWsStore.setState({ unread: 0 });
    },
  });

  const settingsQuery = useQuery({
    queryKey: socialKeys.notificationSettings,
    queryFn: socialApi.notificationSettings,
    enabled: settingsOpen,
  });

  useEffect(() => {
    if (settingsOpen && settingsQuery.data) setDraft(settingsQuery.data);
  }, [settingsOpen, settingsQuery.data]);

  const saveSettings = useMutation({
    mutationFn: (settings: NotificationSetting) => socialApi.updateNotificationSettings(settings),
    onSuccess: (data) => {
      toastSuccess('通知设置已保存');
      queryClient.setQueryData(socialKeys.notificationSettings, data);
      setSettingsOpen(false);
    },
  });

  const totalUnread = unreadQuery.data?.total ?? 0;
  const categoryUnread = unreadQuery.data?.categories?.[tab] ?? 0;

  const handleOpen = (item: MessageCenterItem): void => {
    if (Number(item.isRead) !== 1) readMutation.mutate([item.id]);
    const link = notificationTargetLink(item.targetType, item.targetId);
    if (link) navigate(link);
  };

  const activeTab = TABS.find((item) => item.key === tab) ?? TABS[0];

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          消息中心
          {totalUnread > 0 ? (
            <Badge count={totalUnread} overflowCount={99} style={{ marginLeft: 12 }} />
          ) : null}
        </Typography.Title>
        <Space>
          <Button
            loading={readAllMutation.isPending}
            disabled={totalUnread === 0}
            onClick={() => readAllMutation.mutate()}
          >
            全部已读
          </Button>
          <Popconfirm
            title="清空所有通知？"
            description="清空后不可恢复"
            okText="清空"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => clearMutation.mutate()}
          >
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={items.length === 0 && totalUnread === 0}
            >
              清空
            </Button>
          </Popconfirm>
          <Button icon={<SettingOutlined />} onClick={() => setSettingsOpen(true)}>
            通知设置
          </Button>
        </Space>
      </div>

      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as MessageCategory)}
        tabBarExtraContent={
          <Space size={8}>
            {tab === 'message' ? (
              <Button
                type="link"
                size="small"
                icon={<MessageOutlined />}
                onClick={() => navigate('/messages')}
              >
                前往私信
              </Button>
            ) : null}
            <Button
              size="small"
              loading={readCategoryMutation.isPending}
              disabled={categoryUnread === 0}
              onClick={() => readCategoryMutation.mutate(tab)}
            >
              本分类已读
            </Button>
          </Space>
        }
        items={TABS.map((item) => ({
          key: item.key,
          label: (
            <span>
              {item.label}
              <Badge
                count={unreadQuery.data?.categories?.[item.key] ?? 0}
                size="small"
                overflowCount={99}
                style={{ marginLeft: 6 }}
              />
            </span>
          ),
        }))}
      />

      {query.isLoading ? (
        <div className={styles.loading}>加载中…</div>
      ) : query.isError ? (
        <Empty description="消息加载失败，请稍后重试" />
      ) : items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={activeTab.empty} />
      ) : (
        <List
          className={styles.list}
          dataSource={items}
          renderItem={(item) => (
            <List.Item
              className={`${styles.item} ${Number(item.isRead) === 1 ? styles.read : ''}`}
              actions={[
                <Popconfirm
                  key="delete"
                  title="删除这条消息？"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => deleteMutation.mutate(item.id)}
                >
                  <Button
                    type="text"
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label="删除消息"
                  />
                </Popconfirm>,
              ]}
            >
              <div
                className={styles.row}
                role="button"
                tabIndex={0}
                onClick={() => handleOpen(item)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') handleOpen(item);
                }}
              >
                <div className={styles.titleLine}>
                  {Number(item.isRead) !== 1 ? <span className={styles.dot} /> : null}
                  <Tag color="blue" bordered={false}>
                    {NOTIFICATION_TYPE_LABEL[item.notifyType] ?? '通知'}
                  </Tag>
                  <Typography.Text strong ellipsis className={styles.title}>
                    {item.title ?? '通知'}
                  </Typography.Text>
                  <Typography.Text type="secondary" className={styles.time}>
                    {dayjs(item.createTime).format('MM-DD HH:mm')}
                  </Typography.Text>
                </div>
                {item.content ? (
                  <Typography.Paragraph
                    type="secondary"
                    ellipsis={{ rows: 2 }}
                    className={styles.content}
                  >
                    {item.fromNickname ? `${item.fromNickname}：` : ''}
                    {item.content}
                  </Typography.Paragraph>
                ) : null}
              </div>
            </List.Item>
          )}
        />
      )}

      {query.hasNextPage ? (
        <div className={styles.more}>
          <Button loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            加载更多
          </Button>
        </div>
      ) : null}

      <Modal
        open={settingsOpen}
        title="通知设置"
        okText="保存"
        cancelText="取消"
        confirmLoading={saveSettings.isPending}
        onCancel={() => setSettingsOpen(false)}
        onOk={() => {
          if (draft) saveSettings.mutate(draft);
        }}
        destroyOnHidden
      >
        {settingsQuery.isLoading || !draft ? (
          <div className={styles.loading}>加载中…</div>
        ) : (
          <div className={styles.settings}>
            {SETTING_FIELDS.map((field) => (
              <div key={field.key} className={styles.settingRow}>
                <div>
                  <div>{field.label}</div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {field.hint}
                  </Typography.Text>
                </div>
                <Switch
                  checked={Number(draft[field.key]) === 1}
                  onChange={(checked) =>
                    setDraft({ ...draft, [field.key]: checked ? 1 : 0 } as NotificationSetting)
                  }
                />
              </div>
            ))}
          </div>
        )}
      </Modal>
    </div>
  );
}
