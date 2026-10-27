import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { roomApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { Id, PageResult, Room, RoomMessage } from '@/api/types';

const ROOM_STATUS: Record<number, { text: string; color: string }> = {
  1: { text: '活跃', color: 'green' },
  2: { text: '已关闭', color: 'orange' },
  3: { text: '已解散', color: 'default' },
};

const ROOM_TYPE: Record<number, string> = { 1: '公开', 2: '私密', 3: '密码' };

const MESSAGE_TYPE: Record<number, string> = {
  1: '聊天',
  2: '弹幕',
  3: '系统',
  4: '礼物',
  5: '进房',
  6: '控播',
};

export function RoomsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<{ keyword?: string; status?: number; roomType?: number }>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [detail, setDetail] = useState<Room | null>(null);
  const [messagesOf, setMessagesOf] = useState<Room | null>(null);
  const [messagePage, setMessagePage] = useState(1);
  const [closing, setClosing] = useState<Room | null>(null);
  const [banning, setBanning] = useState<Room | null>(null);
  const [saving, setSaving] = useState(false);
  const [closeForm] = Form.useForm<{ reason: string }>();
  const [banForm] = Form.useForm<{ reason: string; hours?: number }>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<Room>>({
    queryKey: ['admin-rooms', query],
    queryFn: () => roomApi.page(query),
  });

  const messagesQuery = useQuery<PageResult<RoomMessage>>({
    queryKey: ['admin-room-messages', messagesOf?.id, messagePage],
    queryFn: () =>
      roomApi.messages(messagesOf?.id ?? '', { pageNum: messagePage, pageSize: 50 }),
    enabled: messagesOf !== null,
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-rooms'] });
  };

  const openDetail = async (room: Room): Promise<void> => {
    try {
      setDetail(await roomApi.detail(room.id));
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const submitClose = async (): Promise<void> => {
    if (!closing) return;
    const values = await closeForm.validateFields();
    setSaving(true);
    try {
      await roomApi.forceClose(closing.id, values.reason.trim());
      message.success('房间已强制关闭');
      setClosing(null);
      closeForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const submitBan = async (): Promise<void> => {
    if (!banning) return;
    const values = await banForm.validateFields();
    setSaving(true);
    try {
      await roomApi.banCreate(banning.ownerId, {
        reason: values.reason.trim(),
        hours: values.hours,
      });
      message.success('已限制该用户创建房间');
      setBanning(null);
      banForm.resetFields();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const unbanCreate = async (room: Room): Promise<void> => {
    try {
      await roomApi.unbanCreate(room.ownerId);
      message.success('已解除创建房间限制');
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<Room> = [
    { title: '房间号', dataIndex: 'roomNo', width: 100 },
    { title: '房间名', dataIndex: 'name', width: 180 },
    {
      title: '房主',
      key: 'owner',
      width: 180,
      render: (_, record) => `${record.ownerNickname || '—'}（${String(record.ownerId)}）`,
    },
    {
      title: '类型',
      dataIndex: 'roomType',
      width: 90,
      render: (value: number) => ROOM_TYPE[value] ?? value,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: number) => {
        const status = ROOM_STATUS[value] ?? { text: `未知(${value})`, color: 'default' };
        return <Tag color={status.color}>{status.text}</Tag>;
      },
    },
    { title: '在线', dataIndex: 'onlineCount', width: 80 },
    { title: '热度', dataIndex: 'hotScore', width: 80 },
    {
      title: '当前歌曲',
      key: 'song',
      width: 180,
      render: (_, record) =>
        record.currentSongName
          ? `${record.currentSongName}${record.currentArtistName ? ` - ${record.currentArtistName}` : ''}`
          : '—',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 300,
      render: (_, record) => (
        <Space size={4} wrap>
          <Button type="link" size="small" onClick={() => void openDetail(record)}>
            详情
          </Button>
          {hasPerm('admin:room:message:view') && (
            <Button
              type="link"
              size="small"
              onClick={() => {
                setMessagePage(1);
                setMessagesOf(record);
              }}
            >
              消息记录
            </Button>
          )}
          {hasPerm('admin:room:close') && record.status === 1 && (
            <Button type="link" size="small" danger onClick={() => setClosing(record)}>
              强制关闭
            </Button>
          )}
          {hasPerm('admin:room:close') && (
            <Button type="link" size="small" danger onClick={() => setBanning(record)}>
              限制开房
            </Button>
          )}
          {hasPerm('admin:room:close') && (
            <Button type="link" size="small" onClick={() => void unbanCreate(record)}>
              解除开房限制
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="房间名 / 房间号"
          style={{ width: 220 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="房间状态"
          style={{ width: 130 }}
          options={Object.entries(ROOM_STATUS).map(([value, status]) => ({
            value: Number(value),
            label: status.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        <Select
          allowClear
          placeholder="房间类型"
          style={{ width: 130 }}
          options={Object.entries(ROOM_TYPE).map(([value, label]) => ({
            value: Number(value),
            label,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, roomType: value }));
          }}
        />
      </Space>

      <Table<Room>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1500 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 个房间`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Drawer title="房间详情" width={560} open={detail !== null} onClose={() => setDetail(null)}>
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="房间 ID">{String(detail.id)}</Descriptions.Item>
            <Descriptions.Item label="房间号">{detail.roomNo}</Descriptions.Item>
            <Descriptions.Item label="房间名">{detail.name}</Descriptions.Item>
            <Descriptions.Item label="房主 ID">{String(detail.ownerId)}</Descriptions.Item>
            <Descriptions.Item label="房主昵称">{detail.ownerNickname || '—'}</Descriptions.Item>
            <Descriptions.Item label="类型">{ROOM_TYPE[detail.roomType] ?? detail.roomType}</Descriptions.Item>
            <Descriptions.Item label="状态">{ROOM_STATUS[detail.status]?.text ?? detail.status}</Descriptions.Item>
            <Descriptions.Item label="在线人数">{detail.onlineCount}</Descriptions.Item>
            <Descriptions.Item label="热度">{detail.hotScore ?? 0}</Descriptions.Item>
            <Descriptions.Item label="公告">{detail.notice || '—'}</Descriptions.Item>
            <Descriptions.Item label="主题标签">{detail.themeTags || '—'}</Descriptions.Item>
            <Descriptions.Item label="最近活跃">
              {detail.lastActiveTime ? dayjs(detail.lastActiveTime).format('YYYY-MM-DD HH:mm') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {detail.createTime ? dayjs(detail.createTime).format('YYYY-MM-DD HH:mm') : '—'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      <Drawer
        title={`消息记录：${messagesOf?.name ?? ''}`}
        width={720}
        open={messagesOf !== null}
        onClose={() => setMessagesOf(null)}
      >
        <Typography.Paragraph type="secondary">
          查看房间消息属于敏感操作，本次查看已写入操作日志。
        </Typography.Paragraph>
        <Table<RoomMessage>
          rowKey={(record) => String(record.id)}
          size="small"
          loading={messagesQuery.isLoading}
          dataSource={messagesQuery.data?.list ?? []}
          pagination={{
            current: messagePage,
            pageSize: 50,
            total: messagesQuery.data?.total ?? 0,
            onChange: setMessagePage,
            showSizeChanger: false,
          }}
          columns={[
            { title: '时间', dataIndex: 'createTime', width: 160, render: (value?: string) => value ? dayjs(value).format('MM-DD HH:mm:ss') : '—' },
            { title: '用户', dataIndex: 'userId', width: 160, render: (value?: Id) => value ? String(value) : '系统' },
            { title: '类型', dataIndex: 'messageType', width: 80, render: (value?: number) => MESSAGE_TYPE[value ?? 0] ?? value },
            { title: '内容', dataIndex: 'content' },
            {
              title: '审核',
              dataIndex: 'auditStatus',
              width: 80,
              render: (value?: number) => (value === 2 ? <Tag color="red">拦截</Tag> : value === 0 ? <Tag color="orange">待审</Tag> : '通过'),
            },
          ]}
        />
      </Drawer>

      <Modal
        title={`强制关闭房间：${closing?.name ?? ''}`}
        open={closing !== null}
        onCancel={() => setClosing(null)}
        onOk={() => void submitClose()}
        confirmLoading={saving}
        okText="确认关闭"
        okButtonProps={{ danger: true }}
      >
        <Typography.Paragraph type="warning">
          关闭后房间内成员会立即断开，该操作不可撤销（24 小时后自动解散）。
        </Typography.Paragraph>
        <Form form={closeForm} layout="vertical">
          <Form.Item
            name="reason"
            label="关闭原因"
            rules={[
              { required: true, message: '请填写关闭原因' },
              { min: 5, max: 500, message: '原因需为 5-500 个字符' },
            ]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`限制创建房间：${banning?.ownerNickname ?? banning?.ownerId ?? ''}`}
        open={banning !== null}
        onCancel={() => setBanning(null)}
        onOk={() => void submitBan()}
        confirmLoading={saving}
        okText="确认限制"
        okButtonProps={{ danger: true }}
      >
        <Form form={banForm} layout="vertical">
          <Form.Item
            name="reason"
            label="限制原因"
            rules={[
              { required: true, message: '请填写原因' },
              { min: 5, max: 500, message: '原因需为 5-500 个字符' },
            ]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
          <Form.Item name="hours" label="限制时长（小时）" extra="留空或 0 表示永久">
            <InputNumber min={0} max={8760} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
