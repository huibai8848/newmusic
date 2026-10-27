import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Pagination,
  Segmented,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd';
import { PlusOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { roomApi, roomKeys } from '@/api/room';
import type { RoomCreateReq, RoomVO } from '@/types/api';
import styles from './RoomListPage.module.less';

/**
 * 音乐房列表（模块 05 FR-14-001）。
 *
 * 公开 tab 只展示公开/密码房；私密房不进入推荐/热门/最新（PRD 14 BR-14-073）。
 * 「我的」是房主的管理入口，保留自己的私密房；他人仍只能通过分享链接进入。
 */

type RoomTab = 'recommend' | 'hot' | 'new' | 'mine';

const TABS: Array<{ label: string; value: RoomTab }> = [
  { label: '推荐', value: 'recommend' },
  { label: '热门', value: 'hot' },
  { label: '最新', value: 'new' },
  { label: '我的', value: 'mine' },
];

const PAGE_SIZE = 12;

const ROOM_TYPE_LABEL: Record<number, string> = { 2: '私密', 3: '密码' };

export function RoomListPage(): JSX.Element {
  const navigate = useNavigate();
  const [tab, setTab] = useState<RoomTab>('recommend');
  const [keyword, setKeyword] = useState('');
  const [pageNum, setPageNum] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);

  const query = { tab, keyword: keyword || undefined, pageNum, pageSize: PAGE_SIZE };
  const { data, isLoading, refetch } = useQuery({
    queryKey: roomKeys.list(query),
    queryFn: () => roomApi.list(query),
    placeholderData: keepPreviousData,
  });

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <Typography.Title level={3} className={styles.title}>
            <ThunderboltOutlined /> 音乐房
          </Typography.Title>
          <Typography.Text type="secondary">
            一起听歌、上麦聊天；房间关闭 24 小时内可重开
          </Typography.Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          创建房间
        </Button>
      </div>

      <div className={styles.toolbar}>
        <Segmented
          options={TABS}
          value={tab}
          onChange={(value) => {
            setTab(value as RoomTab);
            setPageNum(1);
          }}
        />
        <Input.Search
          allowClear
          placeholder="搜索房间名 / 房间号"
          prefix={<SearchOutlined />}
          className={styles.search}
          onSearch={(value) => {
            setKeyword(value.trim());
            setPageNum(1);
          }}
        />
      </div>

      {isLoading && !data ? (
        <div className={styles.empty}>加载中…</div>
      ) : !data || data.list.length === 0 ? (
        <Empty description={tab === 'mine' ? '你还没有创建或加入过房间' : '暂无房间'} />
      ) : (
        <>
          <div className={styles.grid}>
            {data.list.map((room) => (
              <RoomCard
                key={room.id}
                room={room}
                onOpen={() => navigate(`/room/${room.roomNo}`)}
              />
            ))}
          </div>
          <Pagination
            className={styles.pager}
            current={pageNum}
            pageSize={PAGE_SIZE}
            total={Number(data.total)}
            showSizeChanger={false}
            onChange={setPageNum}
          />
        </>
      )}

      <CreateRoomModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(roomNo) => {
          setCreateOpen(false);
          void refetch();
          navigate(`/room/${roomNo}`);
        }}
      />
    </div>
  );
}

function RoomCard({ room, onOpen }: { room: RoomVO; onOpen: () => void }): JSX.Element {
  return (
    <Card hoverable className={styles.card} onClick={onOpen}>
      <div className={styles.cover}>
        {room.cover ? (
          <img src={room.cover} alt={room.name} />
        ) : (
          <span className={styles.coverFallback}>{room.name.slice(0, 1)}</span>
        )}
        <span className={styles.online}>{room.onlineCount} 人在线</span>
        {ROOM_TYPE_LABEL[room.roomType] && (
          <Tag className={styles.typeTag} color="purple">
            {ROOM_TYPE_LABEL[room.roomType]}
          </Tag>
        )}
      </div>
      <div className={styles.name} title={room.name}>
        {room.name}
      </div>
      <div className={styles.meta}>
        <span>{room.ownerNickname ?? '房主'}</span>
        <span>#{room.roomNo}</span>
      </div>
      {room.currentSongName ? (
        <div className={styles.nowPlaying} title={room.currentSongName}>
          {room.playing ? '▶' : '⏸'} {room.currentSongName}
          {room.currentArtistName ? ` - ${room.currentArtistName}` : ''}
        </div>
      ) : (
        <div className={styles.idle}>暂无播放</div>
      )}
      {room.themeTags && (
        <div className={styles.tags}>
          {room.themeTags.split(',').filter(Boolean).slice(0, 3).map((tag) => (
            <Tag key={tag}>{tag}</Tag>
          ))}
        </div>
      )}
    </Card>
  );
}

interface CreateRoomModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: (roomNo: string) => void;
}

function CreateRoomModal({ open, onClose, onCreated }: CreateRoomModalProps): JSX.Element {
  const [form] = Form.useForm<RoomCreateReq>();
  const [submitting, setSubmitting] = useState(false);
  const roomType = Form.useWatch('roomType', form);

  const submit = async (): Promise<void> => {
    let values: RoomCreateReq;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      const detail = await roomApi.create(values);
      message.success('房间创建成功');
      form.resetFields();
      onCreated(detail.roomNo);
    } catch {
      // 请求层已统一提示
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="创建音乐房"
      open={open}
      onCancel={onClose}
      onOk={() => void submit()}
      okText="创建"
      confirmLoading={submitting}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" initialValues={{
        roomType: 1,
        micCount: 6,
        maxMembers: 100,
        playMode: 1,
        needApprove: 1,
        allowFreeMic: 0,
      }}>
        <Form.Item name="name" label="房间名" rules={[{ required: true, max: 30, message: '1-30 个字符' }]}>
          <Input placeholder="给房间起个名字" maxLength={30} showCount />
        </Form.Item>
        <Form.Item name="roomType" label="房间类型">
          <Select
            options={[
              { value: 1, label: '公开房（出现在列表中）' },
              { value: 2, label: '私密房（仅链接可进）' },
              { value: 3, label: '密码房' },
            ]}
          />
        </Form.Item>
        {roomType === 3 && (
          <Form.Item
            name="password"
            label="房间密码"
            rules={[{ required: true, min: 4, max: 16, message: '4-16 位' }]}
          >
            <Input.Password placeholder="4-16 位密码" maxLength={16} />
          </Form.Item>
        )}
        <Form.Item name="notice" label="公告">
          <Input.TextArea rows={2} maxLength={500} showCount placeholder="开播前说点什么" />
        </Form.Item>
        <Form.Item name="themeTags" label="主题标签">
          <Input placeholder="如：华语,怀旧,深夜（逗号分隔）" maxLength={200} />
        </Form.Item>
        <Space size="middle" wrap>
          <Form.Item name="micCount" label="麦位数">
            <Select
              style={{ width: 100 }}
              options={[4, 6, 8, 12].map((value) => ({ value, label: `${value} 个` }))}
            />
          </Form.Item>
          <Form.Item name="maxMembers" label="人数上限">
            <Select
              style={{ width: 110 }}
              options={[50, 100, 200, 500].map((value) => ({ value, label: `${value} 人` }))}
            />
          </Form.Item>
          <Form.Item name="playMode" label="播放模式">
            <Select
              style={{ width: 130 }}
              options={[
                { value: 1, label: '房主控播' },
                { value: 2, label: '自由播放' },
                { value: 3, label: '投票切歌' },
              ]}
            />
          </Form.Item>
        </Space>
        <Space size="large">
          <Form.Item name="needApprove" label="点歌/上麦需审核" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} normalize={(v) => v === 1}>
            <Switch />
          </Form.Item>
          <Form.Item name="allowFreeMic" label="允许自由上麦" valuePropName="checked" getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)} normalize={(v) => v === 1}>
            <Switch />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  );
}
