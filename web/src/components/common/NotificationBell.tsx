import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button, Empty, List, Popover, Tag, Typography } from 'antd';
import { BellOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useWsStore, type NotificationView } from '@/stores/wsStore';
import styles from './NotificationBell.module.less';

/**
 * 顶栏通知铃铛（模块 04 FR-13-005）。
 *
 * 未读数来自 WS 的 `notify.unread`；列表走 HTTP（分页、离线可拉）。
 * 点击未读项标记已读，本地立即反馈，服务端只重算 Redis 缓存——
 * 下次任何一次推送都会带回权威未读数，不需要为「已读」再造一条推送。
 */

const STATUS_TEXT: Record<string, string> = {
  idle: '未连接',
  connecting: '连接中',
  open: '实时在线',
  reconnecting: '重连中',
  closed: '连接已断开',
};

function NotificationRow({
  item,
  onRead,
}: {
  item: NotificationView;
  onRead: (id: string) => void;
}): JSX.Element {
  return (
    <div
      className={`${styles.item} ${item.isRead ? styles.read : ''}`}
      onClick={() => {
        if (!item.isRead) onRead(item.id);
      }}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && !item.isRead) onRead(item.id);
      }}
    >
      <div className={styles.itemHead}>
        {!item.isRead && <span className={styles.dot} />}
        <Typography.Text strong ellipsis className={styles.title}>
          {item.title}
        </Typography.Text>
        <Typography.Text type="secondary" className={styles.time}>
          {dayjs(item.createTime).format('MM-DD HH:mm')}
        </Typography.Text>
      </div>
      {item.content && (
        <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} className={styles.body}>
          {item.content}
        </Typography.Paragraph>
      )}
      {item.fromNickname && <Tag className={styles.tag}>{item.fromNickname}</Tag>}
    </div>
  );
}

export function NotificationBell(): JSX.Element {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const unread = useWsStore((s) => s.unread);
  const notifications = useWsStore((s) => s.notifications);
  const status = useWsStore((s) => s.status);
  const gaveUp = useWsStore((s) => s.gaveUp);
  const retry = useWsStore((s) => s.retry);
  const loadNotifications = useWsStore((s) => s.loadNotifications);
  const markRead = useWsStore((s) => s.markRead);
  const markAllRead = useWsStore((s) => s.markAllRead);

  const handleOpenChange = (next: boolean): void => {
    setOpen(next);
    if (next) void loadNotifications();
  };

  const content = (
    <div className={styles.panel}>
      <div className={styles.head}>
        <Typography.Text strong>通知</Typography.Text>
        <Button size="small" type="link" disabled={unread === 0} onClick={() => void markAllRead()}>
          全部已读
        </Button>
      </div>

      {notifications.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无通知" />
      ) : (
        <List
          className={styles.list}
          dataSource={notifications}
          renderItem={(item) => (
            <List.Item className={styles.listItem}>
              <NotificationRow item={item} onRead={(id) => void markRead(id)} />
            </List.Item>
          )}
        />
      )}

      <div className={styles.footer}>
        <Button
          type="link"
          block
          size="small"
          onClick={() => {
            setOpen(false);
            navigate('/notifications');
          }}
        >
          查看全部通知
        </Button>
      </div>

      {status !== 'open' && (
        <div className={styles.status}>
          <span className={`${styles.statusDot} ${gaveUp ? styles.danger : ''}`} />
          <Typography.Text type="secondary" className={styles.statusText}>
            {gaveUp ? '实时连接失败' : STATUS_TEXT[status] ?? status}
          </Typography.Text>
          <Button
            size="small"
            type="link"
            icon={<ReloadOutlined />}
            onClick={() => retry()}
            disabled={status === 'connecting' || status === 'reconnecting'}
          >
            重试
          </Button>
        </div>
      )}
    </div>
  );

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={handleOpenChange}
      placement="bottomRight"
      arrow={false}
      overlayClassName={styles.popover}
    >
      <Badge count={unread} size="small" overflowCount={99} offset={[-2, 4]}>
        <Button type="text" icon={<BellOutlined />} aria-label={unread > 0 ? `通知，${unread} 条未读` : '通知'} />
      </Badge>
    </Popover>
  );
}
