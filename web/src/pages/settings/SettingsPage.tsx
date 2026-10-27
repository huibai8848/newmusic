import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Avatar,
  Button,
  Empty,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { socialApi, socialKeys } from '@/api/social';
import { safetyApi } from '@/api/safety';
import type { MinorStatus } from '@/types/safety';
import { playApi, type DeviceVO, type PlaySetting } from '@/api/play';
import { QUALITY_LABEL, type Quality } from '@/api/player';
import { usePlayerStore } from '@/stores/playerStore';
import { useThemeStore, type ThemeMode } from '@/stores/themeStore';
import { RATE_OPTIONS } from '@/components/player/playbackRate';
import { toastError, toastSuccess } from '@/utils/feedback';
import { formatMinorWindow, minorUsageLevel } from '@/utils/safety';
import type { NotificationSetting, PrivacySetting } from '@/types/social';
import type { Id } from '@/types/api';
import styles from './SettingsPage.module.less';

const MESSAGE_POLICY = [
  { value: 0, label: '所有人' },
  { value: 1, label: '仅互相关注' },
  { value: 2, label: '关闭私信' },
];

const COMMENT_POLICY = [
  { value: 0, label: '所有人' },
  { value: 1, label: '关注我的人' },
  { value: 2, label: '关闭评论' },
];

const HISTORY_VISIBILITY = [
  { value: 0, label: '所有人' },
  { value: 1, label: '仅互相关注' },
  { value: 2, label: '仅自己' },
];

interface SettingRowProps {
  label: string;
  hint: string;
  children: JSX.Element;
}

function SettingRow({ label, hint, children }: SettingRowProps): JSX.Element {
  return (
    <div className={styles.row}>
      <div>
        <div className={styles.rowLabel}>{label}</div>
        <Typography.Text type="secondary" className={styles.rowHint}>
          {hint}
        </Typography.Text>
      </div>
      {children}
    </div>
  );
}

function PrivacyTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<PrivacySetting | null>(null);

  const query = useQuery({
    queryKey: socialKeys.privacySettings,
    queryFn: socialApi.privacySettings,
  });

  useEffect(() => {
    if (query.data) setDraft(query.data);
  }, [query.data]);

  const mutation = useMutation({
    mutationFn: (settings: PrivacySetting) => socialApi.updatePrivacySettings(settings),
    onSuccess: (data) => {
      toastSuccess('隐私设置已保存');
      setDraft(data);
      queryClient.setQueryData(socialKeys.privacySettings, data);
    },
  });

  if (query.isLoading || !draft) return <div className={styles.loading}>加载中…</div>;
  if (query.isError) return <Empty description="隐私设置加载失败" />;

  const patch = (partial: Partial<PrivacySetting>): void => setDraft({ ...draft, ...partial });
  const onOff = (value: number, set: (v: number) => void): JSX.Element => (
    <Switch checked={value === 1} onChange={(checked) => set(checked ? 1 : 0)} />
  );

  return (
    <div className={styles.card}>
      <SettingRow label="谁可以给我发私信" hint="非互关用户的每日私信条数也有限制">
        <Select
          style={{ width: 150 }}
          value={draft.messagePolicy}
          options={MESSAGE_POLICY}
          onChange={(value) => patch({ messagePolicy: value })}
        />
      </SettingRow>
      <SettingRow label="谁可以评论我创建的内容" hint="歌单、音乐房等">
        <Select
          style={{ width: 150 }}
          value={draft.commentPolicy}
          options={COMMENT_POLICY}
          onChange={(value) => patch({ commentPolicy: value })}
        />
      </SettingRow>
      <SettingRow label="谁可以 @ 我" hint="评论与私信中的 @ 提醒">
        <Select
          style={{ width: 150 }}
          value={draft.mentionPolicy}
          options={COMMENT_POLICY}
          onChange={(value) => patch({ mentionPolicy: value })}
        />
      </SettingRow>
      <SettingRow label="播放历史可见范围" hint="控制他人能否看到你的最近播放">
        <Select
          style={{ width: 150 }}
          value={draft.playHistoryVisibility}
          options={HISTORY_VISIBILITY}
          onChange={(value) => patch({ playHistoryVisibility: value })}
        />
      </SettingRow>
      <SettingRow label="公开我的关注列表" hint="关闭后他人无法查看你关注了谁">
        {onOff(draft.showFollowing, (value) => patch({ showFollowing: value }))}
      </SettingRow>
      <SettingRow label="公开我的粉丝列表" hint="关闭后他人无法查看谁关注了你">
        {onOff(draft.showFollowers, (value) => patch({ showFollowers: value }))}
      </SettingRow>
      <SettingRow label="私信已读回执" hint="开启后对方能看到你已读消息">
        {onOff(draft.readReceipt, (value) => patch({ readReceipt: value }))}
      </SettingRow>
      <SettingRow label="新歌提醒" hint="关注的歌手发布新歌时通知我">
        {onOff(draft.newSongNotify, (value) => patch({ newSongNotify: value }))}
      </SettingRow>
      <SettingRow label="系统通知" hint="审核、举报、账号状态等通知">
        {onOff(draft.systemNotify, (value) => patch({ systemNotify: value }))}
      </SettingRow>
      <div className={styles.footer}>
        <Button type="primary" loading={mutation.isPending} onClick={() => mutation.mutate(draft)}>
          保存设置
        </Button>
      </div>
    </div>
  );
}

const NOTIFICATION_FIELDS: { key: keyof NotificationSetting; label: string; hint: string }[] = [
  { key: 'likeEnabled', label: '点赞', hint: '内容被点赞时通知我' },
  { key: 'commentEnabled', label: '评论与回复', hint: '收到评论或回复时通知我' },
  { key: 'followEnabled', label: '关注', hint: '被新用户关注时通知我' },
  { key: 'messageEnabled', label: '私信', hint: '收到新私信时通知我' },
  { key: 'systemEnabled', label: '系统通知', hint: '审核、举报、公告等系统消息' },
  { key: 'newSongEnabled', label: '新歌提醒', hint: '关注的歌手发布新歌时通知我' },
  { key: 'readReceiptEnabled', label: '已读回执', hint: '对方能看到我已读私信' },
];

function NotificationTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<NotificationSetting | null>(null);

  const query = useQuery({
    queryKey: socialKeys.notificationSettings,
    queryFn: socialApi.notificationSettings,
  });

  useEffect(() => {
    if (query.data) setDraft(query.data);
  }, [query.data]);

  const mutation = useMutation({
    mutationFn: (settings: NotificationSetting) => socialApi.updateNotificationSettings(settings),
    onSuccess: (data) => {
      toastSuccess('通知设置已保存');
      setDraft(data);
      queryClient.setQueryData(socialKeys.notificationSettings, data);
    },
  });

  if (query.isLoading || !draft) return <div className={styles.loading}>加载中…</div>;
  if (query.isError) return <Empty description="通知设置加载失败" />;

  return (
    <div className={styles.card}>
      {NOTIFICATION_FIELDS.map((field) => (
        <SettingRow key={field.key} label={field.label} hint={field.hint}>
          <Switch
            checked={Number(draft[field.key]) === 1}
            onChange={(checked) =>
              setDraft({ ...draft, [field.key]: checked ? 1 : 0 } as NotificationSetting)
            }
          />
        </SettingRow>
      ))}
      <div className={styles.footer}>
        <Button type="primary" loading={mutation.isPending} onClick={() => mutation.mutate(draft)}>
          保存设置
        </Button>
      </div>
    </div>
  );
}

function BlacklistTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [targetId, setTargetId] = useState('');

  const query = useQuery({
    queryKey: socialKeys.blacklist(),
    queryFn: () => socialApi.blacklist(1, 100),
  });

  const addMutation = useMutation({
    mutationFn: (id: Id) => socialApi.addBlacklist(id),
    onSuccess: () => {
      toastSuccess('已拉黑，双方关注关系已解除');
      setTargetId('');
      void queryClient.invalidateQueries({ queryKey: socialKeys.blacklist() });
      void queryClient.invalidateQueries({ queryKey: socialKeys.conversations });
    },
  });

  const removeMutation = useMutation({
    mutationFn: (id: Id) => socialApi.removeBlacklist(id),
    onSuccess: () => {
      toastSuccess('已解除拉黑');
      void queryClient.invalidateQueries({ queryKey: socialKeys.blacklist() });
    },
  });

  const list = query.data?.list ?? [];

  return (
    <div className={styles.card}>
      <div className={styles.blacklistAdd}>
        <Input
          value={targetId}
          onChange={(e) => setTargetId(e.target.value.trim())}
          placeholder="输入要拉黑的用户 ID"
          style={{ maxWidth: 280 }}
        />
        <Button
          danger
          loading={addMutation.isPending}
          disabled={!/^\d+$/.test(targetId)}
          onClick={() => addMutation.mutate(targetId)}
        >
          拉黑
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          拉黑后双方不能互相关注、评论或发私信
        </Typography.Text>
      </div>

      {query.isLoading ? (
        <div className={styles.loading}>加载中…</div>
      ) : list.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="黑名单为空" />
      ) : (
        <List
          dataSource={list}
          renderItem={(user) => (
            <List.Item
              actions={[
                <Popconfirm
                  key="remove"
                  title={`解除对 ${user.nickname ?? user.userId} 的拉黑？`}
                  okText="解除"
                  cancelText="取消"
                  onConfirm={() => removeMutation.mutate(user.userId)}
                >
                  <Button type="link">解除拉黑</Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                avatar={<Avatar src={user.avatar ?? undefined} icon={<UserOutlined />} />}
                title={
                  <Space>
                    {user.nickname ?? '用户'}
                    <Tag>{String(user.userId)}</Tag>
                  </Space>
                }
                description={`拉黑于 ${dayjs(user.createTime).format('YYYY-MM-DD HH:mm')}`}
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );
}

const QUALITY_OPTIONS: { value: Quality; label: string }[] = [
  { value: 'standard', label: QUALITY_LABEL.standard },
  { value: 'high', label: QUALITY_LABEL.high },
  { value: 'lossless', label: '无损（VIP）' },
];

const PLAY_MODE_OPTIONS = [
  { value: 'order', label: '顺序播放' },
  { value: 'loop', label: '列表循环' },
  { value: 'single', label: '单曲循环' },
  { value: 'shuffle', label: '随机播放' },
];

const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
  { value: 'system', label: '跟随系统' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
];

/** 播放设置（模块 12 FR-21-008）：只暴露真正会生效的项，避免「开关拨了没反应」 */
function PlaybackTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<PlaySetting | null>(null);

  const query = useQuery({ queryKey: ['play', 'settings'], queryFn: playApi.settings });

  useEffect(() => {
    if (query.data) setDraft(query.data);
  }, [query.data]);

  const mutation = useMutation({
    mutationFn: (settings: PlaySetting) => playApi.updateSettings(settings),
    onSuccess: (data) => {
      toastSuccess('播放设置已保存');
      setDraft(data);
      queryClient.setQueryData(['play', 'settings'], data);
      // 本地立即生效（BR-21-079）：音质/倍速/播放模式/自动降质/省流量/主题
      const player = usePlayerStore.getState();
      player.setQuality(data.defaultQuality);
      player.applyServerSettings({
        defaultQuality: data.defaultQuality,
        autoQuality: data.autoQuality,
        dataSaver: data.dataSaver,
      });
      player.setPlaybackRate(data.playbackRate);
      player.setPlayMode(data.playMode);
      useThemeStore.getState().setMode(data.theme);
    },
  });

  if (query.isLoading || !draft) return <div className={styles.loading}>加载中…</div>;
  if (query.isError) return <Empty description="播放设置加载失败" />;

  const patch = (partial: Partial<PlaySetting>): void => setDraft({ ...draft, ...partial });

  return (
    <div className={styles.card}>
      <SettingRow label="默认音质" hint="新歌优先尝试该档位；无音源或网络较差时自动降级">
        <Select
          style={{ width: 150 }}
          value={draft.defaultQuality}
          options={QUALITY_OPTIONS}
          onChange={(value) => patch({ defaultQuality: value })}
        />
      </SettingRow>
      <SettingRow label="默认播放速度" hint="音乐房内会临时锁定 1.0 倍速">
        <Select
          style={{ width: 110 }}
          value={draft.playbackRate}
          options={RATE_OPTIONS}
          onChange={(value) => patch({ playbackRate: value })}
        />
      </SettingRow>
      <SettingRow label="播放模式" hint="顺序 / 列表循环 / 单曲循环 / 随机">
        <Select
          style={{ width: 150 }}
          value={draft.playMode}
          options={PLAY_MODE_OPTIONS}
          onChange={(value) => patch({ playMode: value })}
        />
      </SettingRow>
      <SettingRow label="弱网自动降质" hint="连续缓冲超过 3 秒时自动降一档；只降不升，5 分钟内最多两次">
        <Switch
          checked={Number(draft.autoQuality) === 1}
          onChange={(checked) => patch({ autoQuality: checked ? 1 : 0 })}
        />
      </SettingRow>
      <SettingRow label="省流量模式" hint="始终使用标准音质，减少流量消耗">
        <Switch
          checked={Number(draft.dataSaver) === 1}
          onChange={(checked) => patch({ dataSaver: checked ? 1 : 0 })}
        />
      </SettingRow>
      <SettingRow label="主题" hint="跟随系统时会随操作系统的深浅色切换">
        <Select
          style={{ width: 150 }}
          value={draft.theme}
          options={THEME_OPTIONS}
          onChange={(value) => patch({ theme: value })}
        />
      </SettingRow>
      <div className={styles.footer}>
        <Button type="primary" loading={mutation.isPending} onClick={() => mutation.mutate(draft)}>
          保存设置
        </Button>
      </div>
    </div>
  );
}

const DEVICE_TYPE_LABEL: Record<DeviceVO['deviceType'], string> = {
  WEB: '网页',
  PWA: '应用',
  MOBILE_WEB: '移动网页',
};

/** 设备管理（模块 12 FR-21-064）：查看最近活跃设备并移除 */
function DevicesTab(): JSX.Element {
  const queryClient = useQueryClient();

  const query = useQuery({ queryKey: ['play', 'devices'], queryFn: playApi.devices });

  const logoutMutation = useMutation({
    mutationFn: (deviceId: string) => playApi.logoutDevice(deviceId),
    onSuccess: () => {
      toastSuccess('已登出该设备');
      void queryClient.invalidateQueries({ queryKey: ['play', 'devices'] });
    },
  });

  const logoutAllMutation = useMutation({
    mutationFn: playApi.logoutAllDevices,
    onSuccess: (count) => {
      toastSuccess(count > 0 ? `已登出 ${count} 台设备` : '没有在线设备');
      void queryClient.invalidateQueries({ queryKey: ['play', 'devices'] });
    },
  });

  const list = query.data ?? [];

  if (query.isLoading) return <div className={styles.loading}>加载中…</div>;
  if (query.isError) return <Empty description="设备列表加载失败" />;

  return (
    <div className={styles.card}>
      <div className={styles.row}>
        <div>
          <div className={styles.rowLabel}>已登录设备</div>
          <Typography.Text type="secondary" className={styles.rowHint}>
            最近 30 天内活跃过的设备；登出后该设备需要重新登录
          </Typography.Text>
        </div>
        <Popconfirm
          title="登出全部设备？当前设备也会被移除，下次请求会自动重新登记"
          okText="全部登出"
          cancelText="取消"
          disabled={list.length === 0}
          onConfirm={() => logoutAllMutation.mutate()}
        >
          <Button danger disabled={list.length === 0} loading={logoutAllMutation.isPending}>
            全部登出
          </Button>
        </Popconfirm>
      </div>

      {list.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无设备记录" />
      ) : (
        <List
          dataSource={list}
          renderItem={(device) => (
            <List.Item
              actions={[
                device.current ? (
                  <Tag key="current" color="green">
                    当前设备
                  </Tag>
                ) : (
                  <Popconfirm
                    key="logout"
                    title={`登出「${device.deviceName ?? device.deviceId}」？`}
                    okText="登出"
                    cancelText="取消"
                    onConfirm={() => logoutMutation.mutate(device.deviceId)}
                  >
                    <Button type="link" danger loading={logoutMutation.isPending}>
                      登出
                    </Button>
                  </Popconfirm>
                ),
              ]}
            >
              <List.Item.Meta
                title={
                  <Space>
                    {device.deviceName ?? '未知设备'}
                    <Tag>{DEVICE_TYPE_LABEL[device.deviceType] ?? device.deviceType}</Tag>
                  </Space>
                }
                description={`最近活跃 ${dayjs(device.lastActiveTime).format('YYYY-MM-DD HH:mm')}${
                  device.lastIp ? ` · IP ${device.lastIp}` : ''
                }`}
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );
}

/** 青少年模式（模块 18）：开启/关闭都需要密码；未成年人账号不允许关闭。 */
function MinorTab(): JSX.Element {
  const queryClient = useQueryClient();
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState<boolean | null>(null);

  const query = useQuery({
    queryKey: ['minor-status'],
    queryFn: safetyApi.minor.status,
    retry: false,
  });
  const wards = useQuery({
    queryKey: ['minor-wards'],
    queryFn: safetyApi.minor.wards,
    retry: false,
  });

  const toggle = useMutation({
    mutationFn: (enabled: boolean) => safetyApi.minor.toggle({ enabled, password }),
    onSuccess: (data) => {
      toastSuccess(data.mode ? '青少年模式已开启' : '青少年模式已关闭');
      setPending(null);
      setPassword('');
      queryClient.setQueryData(['minor-status'], data);
    },
  });

  if (query.isLoading) return <div className={styles.loading}>加载中…</div>;
  if (query.isError || !query.data) {
    return <Empty description="青少年模式状态加载失败，请稍后重试" />;
  }

  const status: MinorStatus = query.data;
  const openModal = (enabled: boolean): void => {
    setPassword('');
    setPending(enabled);
  };

  return (
    <div className={styles.card}>
      <Alert
        type="info"
        showIcon
        style={{ margin: '16px 0' }}
        message={status.forced ? '未成年人账号已强制开启青少年模式' : '青少年模式'}
        description={
          status.forced
            ? '根据账号年龄信息，青少年模式不可关闭；每日使用时长与禁用时段仍然生效。'
            : '开启后关闭社交、限制每日使用时长，并屏蔽营销与位置推荐。'
        }
      />

      <SettingRow label="青少年模式" hint={status.passwordSet ? '关闭需要输入密码' : '首次开启时设置密码（至少 4 位）'}>
        <Switch
          checked={status.mode}
          disabled={status.forced}
          loading={toggle.isPending}
          onChange={(checked) => openModal(checked)}
        />
      </SettingRow>

      <SettingRow label="每日使用时长" hint="达到上限后当天不可继续使用">
        <Typography.Text>
          {status.usedMinutes} / {status.dailyLimitMinutes} 分钟
          {minorUsageLevel(status.usedMinutes, status.dailyLimitMinutes) === 'warning' ? (
            <Tag color="orange" style={{ marginLeft: 8 }}>即将用完</Tag>
          ) : null}
          {minorUsageLevel(status.usedMinutes, status.dailyLimitMinutes) === 'exhausted' ? (
            <Tag color="red" style={{ marginLeft: 8 }}>今日已用尽</Tag>
          ) : null}
        </Typography.Text>
      </SettingRow>

      <SettingRow label="禁用时段" hint="该时段内不允许使用">
        <Typography.Text>
          {formatMinorWindow(status.disableStart, status.disableEnd)}
          {status.inDisableWindow ? <Tag color="red" style={{ marginLeft: 8 }}>当前处于禁用时段</Tag> : null}
        </Typography.Text>
      </SettingRow>

      <SettingRow label="社交与营销" hint="青少年模式下关闭私信、评论与营销推荐">
        <Space>
          <Tag color={status.socialAllowed ? 'green' : 'red'}>
            社交{status.socialAllowed ? '可用' : '已关闭'}
          </Tag>
          <Tag color={status.marketingAllowed ? 'green' : 'red'}>
            营销{status.marketingAllowed ? '可用' : '已屏蔽'}
          </Tag>
          <Tag color={status.locationAllowed ? 'green' : 'red'}>
            位置{status.locationAllowed ? '可用' : '已屏蔽'}
          </Tag>
        </Space>
      </SettingRow>

      <SettingRow
        label="我监护的未成年人"
        hint={
          status.guardianUserId
            ? `当前账号由 ${status.guardianUserId} 监护`
            : '绑定后可代为开启/关闭青少年模式（未成年人本人不可关闭）'
        }
      >
        {wards.data && wards.data.length > 0 ? (
          <Space wrap>
            {wards.data.map((ward, index) => (
              <Tag key={String(ward.userId ?? index)} color="blue">
                {String(ward.nickname ?? ward.userId ?? '-')}
              </Tag>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">暂无</Typography.Text>
        )}
      </SettingRow>

      <Modal
        title={pending ? (status.passwordSet ? '输入密码开启' : '设置青少年模式密码') : '关闭青少年模式'}
        open={pending !== null}
        confirmLoading={toggle.isPending}
        onCancel={() => {
          setPending(null);
          setPassword('');
        }}
        onOk={() => {
          if (!password.trim()) {
            toastError('请输入密码');
            return;
          }
          toggle.mutate(Boolean(pending));
        }}
      >
        <Typography.Paragraph type="secondary">
          {pending && !status.passwordSet
            ? '密码用于关闭青少年模式，请妥善保管，至少 4 位。'
            : '请输入青少年模式密码。'}
        </Typography.Paragraph>
        <Input.Password
          value={password}
          maxLength={32}
          placeholder="青少年模式密码"
          onChange={(event) => setPassword(event.target.value)}
          onPressEnter={() => {
            if (password.trim()) toggle.mutate(Boolean(pending));
          }}
        />
      </Modal>
    </div>
  );
}

/** 设置页（FR-19-012/013/016）：隐私、通知、黑名单集中在一处；模块 12 增加播放与设备。 */
export function SettingsPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') ?? 'privacy';

  return (
    <div className="ml-page">
      <Typography.Title level={4}>设置</Typography.Title>
      <Tabs
        activeKey={tab}
        onChange={(key) => setSearchParams({ tab: key }, { replace: true })}
        items={[
          { key: 'playback', label: '播放设置', children: <PlaybackTab /> },
          { key: 'devices', label: '设备管理', children: <DevicesTab /> },
          { key: 'minor', label: '青少年模式', children: <MinorTab /> },
          { key: 'privacy', label: '隐私设置', children: <PrivacyTab /> },
          { key: 'notification', label: '通知设置', children: <NotificationTab /> },
          { key: 'blacklist', label: '黑名单', children: <BlacklistTab /> },
        ]}
      />
    </div>
  );
}
