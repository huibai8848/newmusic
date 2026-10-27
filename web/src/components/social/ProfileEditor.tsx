import { useEffect, useState } from 'react';
import {
  Avatar,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Radio,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  Upload,
} from 'antd';
import { CameraOutlined, PictureOutlined, UserOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { profileApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { ApiError } from '@/api/request';
import { EmptyState } from '@/components/common/EmptyState';
import { ListSkeleton } from '@/components/common/PageLoading';
import { useUserStore } from '@/stores/userStore';
import { mediaUrl } from '@/utils/format';
import { toastSuccess, toastWarning } from '@/utils/feedback';
import type { UserVO } from '@/types/api';
import type { ProfilePrivacy, ProfileVO } from '@/types/socialUpgrade';
import styles from './ProfileEditor.module.less';

interface ProfileFormValues {
  nickname: string;
  bio?: string;
  gender?: number;
  birthday?: Dayjs | null;
  region?: string;
  tags?: string[];
  userCode?: string;
  profileVisibility: number;
}

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function validateImage(file: File): string | null {
  if (!file.type.startsWith('image/')) return '只能上传图片文件';
  if (file.size > MAX_IMAGE_BYTES) return '图片不能超过 5MB';
  return null;
}

const DEFAULT_PRIVACY: ProfilePrivacy = {
  playDuration: 0,
  weekCount: 0,
  topArtist: 0,
  genre: 0,
  listenDays: 0,
  recentListening: 0,
  fansRank: 0,
  intimacyScore: 0,
};

/**
 * 个人资料编辑（FR-25-001 / FR-25-002）。
 *
 * 隐私字段语义在后端是**反的**：统计项 0 显示 / 1 隐藏，
 * `recentListening`、`fansRank` 1 显示 / 0 隐藏，`intimacyScore` 1 隐藏 / 0 显示。
 * UI 统一用「对他人展示」的正向开关，保存时再映射回原始值，避免用户在两个页面看到相反语义。
 */
export function ProfileEditor(): JSX.Element {
  const user = useUserStore((s) => s.user);
  const isLogin = useUserStore((s) => s.isLogin);

  const profileQuery = useQuery({
    queryKey: socialUpgradeKeys.profile(user?.id ?? ''),
    queryFn: () => profileApi.profile(user?.id ?? ''),
    enabled: Boolean(user?.id),
  });

  if (!isLogin || !user) return <EmptyState description="未登录" />;
  if (profileQuery.isLoading) return <ListSkeleton rows={4} />;

  // 表单单独放进子组件：加载态提前 return 时不能创建 useForm 实例，
  // 否则 AntD 会报「Instance created by useForm is not connected to any Form element」。
  return <ProfileEditorBody user={user} profile={profileQuery.data} />;
}

function ProfileEditorBody({ user, profile }: { user: UserVO; profile?: ProfileVO }): JSX.Element {
  const refreshUser = useUserStore((s) => s.refreshUser);
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ProfileFormValues>();
  const [privacy, setPrivacy] = useState<ProfilePrivacy>(DEFAULT_PRIVACY);

  useEffect(() => {
    if (!user) return;
    form.setFieldsValue({
      nickname: user.nickname,
      bio: profile?.bio ?? user.signature ?? undefined,
      gender: profile?.gender ?? user.gender ?? 0,
      birthday: user.birthday ? dayjs(user.birthday) : null,
      region: profile?.region ?? undefined,
      tags: profile?.tags ?? [],
      userCode: profile?.userCode ?? undefined,
      profileVisibility: profile?.privacy?.profile ?? 0,
    });
  }, [user, profile, form]);

  useEffect(() => {
    if (profile?.privacy) {
      setPrivacy({ ...DEFAULT_PRIVACY, ...profile.privacy });
    }
  }, [profile?.privacy]);

  const invalidateProfile = (): void => {
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.all });
  };

  const saveMutation = useMutation({
    mutationFn: (values: ProfileFormValues) =>
      profileApi.updateProfile({
        nickname: values.nickname.trim(),
        bio: values.bio?.trim() ?? '',
        gender: values.gender ?? 0,
        birthday: values.birthday ? values.birthday.format('YYYY-MM-DD') : undefined,
        region: values.region?.trim() || undefined,
        tags: values.tags ?? [],
        userCode: values.userCode?.trim() || undefined,
        profileVisibility: values.profileVisibility as 0 | 1 | 2,
      }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(socialUpgradeKeys.profile(updated.userId), updated);
      await refreshUser();
      invalidateProfile();
      toastSuccess('资料已保存');
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) return;
      if (error.code === 30011) {
        const field = error.message.includes('简介') ? 'bio' : 'nickname';
        form.setFields([{ name: field, errors: [error.message] }]);
      } else if (error.code === 30012) {
        form.setFields([{ name: 'userCode', errors: [error.message] }]);
      }
    },
  });

  const avatarMutation = useMutation({
    mutationFn: (file: File) => profileApi.uploadAvatar(file),
    onSuccess: async () => {
      await refreshUser();
      invalidateProfile();
      toastSuccess('头像已更新');
    },
  });

  const coverMutation = useMutation({
    mutationFn: (file: File) => profileApi.uploadCover(file),
    onSuccess: () => {
      invalidateProfile();
      toastSuccess('封面已更新');
    },
  });

  const privacyMutation = useMutation({
    mutationFn: (body: ProfilePrivacy) => profileApi.updatePrivacy(body),
    onSuccess: (data) => {
      setPrivacy({ ...DEFAULT_PRIVACY, ...data });
      invalidateProfile();
      toastSuccess('隐私设置已保存');
    },
  });

  const setRaw = (key: keyof ProfilePrivacy, value: number): void => {
    setPrivacy((prev) => ({ ...prev, [key]: value }));
  };

  const savePrivacy = (): void => {
    const body: ProfilePrivacy = {
      playDuration: privacy.playDuration ?? 0,
      weekCount: privacy.weekCount ?? 0,
      topArtist: privacy.topArtist ?? 0,
      genre: privacy.genre ?? 0,
      listenDays: privacy.listenDays ?? 0,
      recentListening: privacy.recentListening ?? 0,
      fansRank: privacy.fansRank ?? 0,
      intimacyScore: privacy.intimacyScore ?? 0,
    };
    privacyMutation.mutate(body);
  };

  const avatarUpload = (file: File): void => {
    const error = validateImage(file);
    if (error) {
      toastWarning(error);
      return;
    }
    avatarMutation.mutate(file);
  };

  const coverUpload = (file: File): void => {
    const error = validateImage(file);
    if (error) {
      toastWarning(error);
      return;
    }
    coverMutation.mutate(file);
  };

  const showPlayDuration = (privacy.playDuration ?? 0) === 0;
  const showWeekCount = (privacy.weekCount ?? 0) === 0;
  const showTopArtist = (privacy.topArtist ?? 0) === 0;
  const showGenre = (privacy.genre ?? 0) === 0;
  const showListenDays = (privacy.listenDays ?? 0) === 0;
  const showRecent = (privacy.recentListening ?? 0) === 1;
  const showFansRank = (privacy.fansRank ?? 0) === 1;
  const showIntimacy = (privacy.intimacyScore ?? 0) === 0;

  return (
    <div className={styles.page}>
      <Card>
        <Row gutter={[24, 24]}>
          <Col xs={24} md={10} lg={8}>
            <div className={styles.identity}>
              <div
                className={styles.cover}
                style={
                  profile?.coverUrl
                    ? { backgroundImage: `url(${mediaUrl(profile.coverUrl)})` }
                    : undefined
                }
              >
                <Upload
                  showUploadList={false}
                  accept="image/*"
                  beforeUpload={(file) => {
                    coverUpload(file);
                    return Upload.LIST_IGNORE;
                  }}
                >
                  <Button
                    size="small"
                    icon={<PictureOutlined />}
                    loading={coverMutation.isPending}
                  >
                    更换封面
                  </Button>
                </Upload>
              </div>
              <div className={styles.avatarRow}>
                <Upload
                  showUploadList={false}
                  accept="image/*"
                  beforeUpload={(file) => {
                    avatarUpload(file);
                    return Upload.LIST_IGNORE;
                  }}
                >
                  <div className={styles.avatarWrap} role="button" tabIndex={0}>
                    <Avatar
                      size={96}
                      src={mediaUrl(profile?.avatar ?? user.avatar)}
                      icon={<UserOutlined />}
                    />
                    <span className={styles.avatarMask}>
                      <CameraOutlined />
                    </span>
                  </div>
                </Upload>
              </div>
              <Typography.Title level={4} className={styles.identityName}>
                {user.nickname}
              </Typography.Title>
              <Typography.Text type="secondary">@{user.username}</Typography.Text>
              <Space size={4} wrap className={styles.roles}>
                {user.roles.map((role) => (
                  <Tag key={role} color="blue" bordered={false}>
                    {role}
                  </Tag>
                ))}
              </Space>
              <Descriptions column={1} size="small" className={styles.accountInfo}>
                <Descriptions.Item label="注册时间">
                  {user.createTime?.slice(0, 10)}
                </Descriptions.Item>
                <Descriptions.Item label="VIP 等级">{user.vipLevel ?? 0}</Descriptions.Item>
                <Descriptions.Item label="账号状态">
                  {user.status === 1 ? '正常' : '受限'}
                </Descriptions.Item>
              </Descriptions>
            </div>
          </Col>

          <Col xs={24} md={14} lg={16}>
            <Form
              form={form}
              layout="vertical"
              onFinish={(values) => saveMutation.mutate(values)}
              className={styles.form}
            >
              <Form.Item
                name="nickname"
                label="昵称"
                extra="昵称 30 天内只能修改一次"
                rules={[
                  { required: true, message: '请输入昵称' },
                  {
                    pattern: /^[一-龥A-Za-z0-9_]{2,20}$/,
                    message: '昵称需为 2-20 位中英文、数字或下划线',
                  },
                ]}
              >
                <Input placeholder="昵称（全站唯一）" maxLength={20} />
              </Form.Item>

              <Form.Item
                name="bio"
                label="简介"
                extra="最多 100 字，每天最多修改 3 次"
                rules={[{ max: 100, message: '简介最多 100 字' }]}
              >
                <Input.TextArea rows={3} maxLength={100} showCount placeholder="介绍一下自己…" />
              </Form.Item>

              <Row gutter={16}>
                <Col xs={24} sm={12}>
                  <Form.Item name="gender" label="性别">
                    <Radio.Group>
                      <Radio value={0}>保密</Radio>
                      <Radio value={1}>男</Radio>
                      <Radio value={2}>女</Radio>
                    </Radio.Group>
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item name="birthday" label="生日">
                    <DatePicker
                      style={{ width: '100%' }}
                      placeholder="选择日期"
                      disabledDate={(date) => date.isAfter(dayjs(), 'day')}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item name="region" label="地区" rules={[{ max: 64 }]}>
                    <Input placeholder="例如：浙江杭州" maxLength={64} />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="userCode"
                    label="短码"
                    extra="4-20 位字母或数字，用于 @ 和主页链接"
                    rules={[
                      {
                        pattern: /^[A-Za-z0-9]{4,20}$/,
                        message: '短码需为 4-20 位字母或数字',
                      },
                    ]}
                  >
                    <Input placeholder="例如：musicfan01" maxLength={20} />
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item
                name="tags"
                label="个人标签"
                extra="最多 5 个，每个 1-16 字"
                rules={[
                  {
                    validator: (_rule, value?: string[]) => {
                      if (!value || value.length === 0) return Promise.resolve();
                      if (value.length > 5) return Promise.reject(new Error('最多 5 个标签'));
                      const invalid = value.find((tag) => !tag.trim() || tag.length > 16);
                      return invalid
                        ? Promise.reject(new Error('每个标签需为 1-16 字'))
                        : Promise.resolve();
                    },
                  },
                ]}
              >
                <Select
                  mode="tags"
                  placeholder="输入后回车添加，例如：摇滚、夜跑"
                  maxCount={5}
                  tokenSeparators={[',', '，']}
                  open={false}
                />
              </Form.Item>

              <Form.Item
                name="profileVisibility"
                label="主页可见范围"
                extra="「仅关注者」需要对方关注你；「仅自己」时其他访客只能看到头部信息"
              >
                <Radio.Group>
                  <Radio value={0}>公开</Radio>
                  <Radio value={1}>仅关注者</Radio>
                  <Radio value={2}>仅自己</Radio>
                </Radio.Group>
              </Form.Item>

              <Button type="primary" htmlType="submit" loading={saveMutation.isPending}>
                保存资料
              </Button>
            </Form>
          </Col>
        </Row>
      </Card>

      <Card
        title="主页隐私"
        extra={
          <Button type="primary" loading={privacyMutation.isPending} onClick={savePrivacy}>
            保存隐私设置
          </Button>
        }
      >
        <Typography.Paragraph type="secondary" className={styles.privacyHint}>
          以下开关只影响其他访客看到的内容；你自己始终可以看到完整数据。
        </Typography.Paragraph>
        <div className={styles.privacyGrid}>
          <PrivacyRow
            label="累计播放时长"
            description="展示总听歌小时数"
            checked={showPlayDuration}
            onChange={(checked) => setRaw('playDuration', checked ? 0 : 1)}
          />
          <PrivacyRow
            label="本周听歌数量"
            description="展示本周听过的歌曲数"
            checked={showWeekCount}
            onChange={(checked) => setRaw('weekCount', checked ? 0 : 1)}
          />
          <PrivacyRow
            label="常听歌手"
            description="展示最常听的歌手及次数"
            checked={showTopArtist}
            onChange={(checked) => setRaw('topArtist', checked ? 0 : 1)}
          />
          <PrivacyRow
            label="偏好风格"
            description="展示最常听的曲风"
            checked={showGenre}
            onChange={(checked) => setRaw('genre', checked ? 0 : 1)}
          />
          <PrivacyRow
            label="听歌天数"
            description="展示累计听歌天数"
            checked={showListenDays}
            onChange={(checked) => setRaw('listenDays', checked ? 0 : 1)}
          />
          <PrivacyRow
            label="最近常听"
            description="主页「最近常听」标签页"
            checked={showRecent}
            onChange={(checked) => setRaw('recentListening', checked ? 1 : 0)}
          />
          <PrivacyRow
            label="粉丝榜"
            description="主页「粉丝榜」标签页"
            checked={showFansRank}
            onChange={(checked) => setRaw('fansRank', checked ? 1 : 0)}
          />
          <PrivacyRow
            label="亲密度分数"
            description="关闭后好友只能看到等级，看不到具体分数"
            checked={showIntimacy}
            onChange={(checked) => setRaw('intimacyScore', checked ? 0 : 1)}
          />
        </div>
      </Card>
    </div>
  );
}

function PrivacyRow({
  label,
  description,
  checked,
  onChange,
}: {
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}): JSX.Element {
  return (
    <div className={styles.privacyRow}>
      <div className={styles.privacyText}>
        <span className={styles.privacyLabel}>{label}</span>
        <span className={styles.privacyDesc}>{description}</span>
      </div>
      <Switch checked={checked} onChange={onChange} checkedChildren="展示" unCheckedChildren="隐藏" />
    </div>
  );
}
