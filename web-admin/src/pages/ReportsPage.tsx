import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { LinkOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { reportApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { PageResult, Report } from '@/api/types';

const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待处理', color: 'orange' },
  1: { text: '已成立', color: 'red' },
  2: { text: '不成立', color: 'default' },
  3: { text: '待补充', color: 'gold' },
  4: { text: '已合并', color: 'blue' },
};

const PRIORITY: Record<number, { text: string; color: string }> = {
  1: { text: '高', color: 'red' },
  2: { text: '普通', color: 'default' },
};

const TARGET_TYPE: Record<string, string> = {
  SONG: '歌曲',
  LYRIC: '歌词',
  COMMENT: '评论',
  USER: '用户',
  ROOM: '房间',
  PRIVATE_MESSAGE: '私信',
  PLAYLIST: '歌单',
};

const ACTION: Record<string, string> = {
  NONE: '仅记录结论，不做额外处置',
  OFFLINE_SONG: '下架歌曲',
  BAN_USER: '封禁用户（7 天）',
  CLOSE_ROOM: '强制关闭房间',
};

/** 目标类型与可用联动动作的对应关系（后端不匹配会直接拒绝） */
const ACTION_FOR_TARGET: Record<string, string> = {
  SONG: 'OFFLINE_SONG',
  USER: 'BAN_USER',
  ROOM: 'CLOSE_ROOM',
};

interface Filters {
  keyword?: string;
  status?: number;
  priority?: number;
  targetType?: string;
}

interface HandleValues {
  result: 'VALID' | 'INVALID' | 'NEED_MORE';
  reason: string;
  action?: string;
  mergeSiblings?: boolean;
}

function evidenceUrls(raw?: string): string[] {
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter((item): item is string => typeof item === 'string');
    }
  } catch {
    // 兼容逗号分隔的历史数据
  }
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

export function ReportsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [active, setActive] = useState<Report | null>(null);
  const [handling, setHandling] = useState<Report | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<HandleValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<Report>>({
    queryKey: ['admin-reports', query],
    queryFn: () => reportApi.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-reports'] });
  };

  const openHandle = (report: Report): void => {
    setHandling(report);
    form.resetFields();
    form.setFieldsValue({
      result: 'VALID',
      reason: '',
      action: ACTION_FOR_TARGET[report.targetType] ?? 'NONE',
      mergeSiblings: true,
    });
  };

  const submit = async (): Promise<void> => {
    if (!handling) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await reportApi.handle(handling.id, {
        result: values.result,
        reason: values.reason.trim(),
        action: values.result === 'VALID' ? values.action || 'NONE' : 'NONE',
        mergeSiblings: values.mergeSiblings === true && values.result !== 'INVALID',
      });
      message.success('举报已处理，处理结果将通知举报人');
      setHandling(null);
      setActive(null);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<Report> = [
    {
      title: '被举报对象',
      key: 'target',
      width: 240,
      render: (_, record) => (
        <div>
          <div>{record.targetName || `#${record.targetId}`}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {TARGET_TYPE[record.targetType] ?? record.targetType}#{record.targetId}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '举报人',
      key: 'reporter',
      width: 170,
      render: (_, record) => (
        <div>
          <div>{record.reporterName || '—'}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {String(record.reporterId)}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '原因',
      key: 'reason',
      width: 260,
      render: (_, record) => (
        <div>
          {record.reasonType && <Tag>{record.reasonType}</Tag>}
          <Typography.Paragraph style={{ marginBottom: 0 }} ellipsis={{ rows: 2 }}>
            {record.reason || '—'}
          </Typography.Paragraph>
        </div>
      ),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 90,
      render: (value: number) => {
        const priority = PRIORITY[value] ?? { text: String(value), color: 'default' };
        return <Tag color={priority.color}>{priority.text}</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: number) => {
        const status = STATUS[value] ?? { text: `未知(${value})`, color: 'default' };
        return <Tag color={status.color}>{status.text}</Tag>;
      },
    },
    {
      title: '提交时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 160,
      render: (_, record) => (
        <Space size={4} wrap>
          <Button type="link" size="small" onClick={() => setActive(record)}>
            详情
          </Button>
          {hasPerm('admin:report:handle') && (record.status === 0 || record.status === 3) && (
            <Button type="link" size="small" onClick={() => openHandle(record)}>
              处理
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
          placeholder="对象名称 / 举报人 / 理由"
          style={{ width: 240 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 130 }}
          options={Object.entries(STATUS).map(([value, status]) => ({
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
          placeholder="优先级"
          style={{ width: 120 }}
          options={Object.entries(PRIORITY).map(([value, priority]) => ({
            value: Number(value),
            label: priority.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, priority: value }));
          }}
        />
        <Select
          allowClear
          placeholder="对象类型"
          style={{ width: 140 }}
          options={Object.entries(TARGET_TYPE).map(([value, label]) => ({ value, label }))}
          onChange={(value?: string) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, targetType: value }));
          }}
        />
      </Space>

      <Table<Report>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1300 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 条举报`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Drawer
        title={`举报详情${active ? ` #${active.id}` : ''}`}
        width={640}
        open={active !== null}
        onClose={() => setActive(null)}
        extra={
          active && hasPerm('admin:report:handle') && (active.status === 0 || active.status === 3) ? (
            <Button type="primary" onClick={() => openHandle(active)}>
              处理举报
            </Button>
          ) : undefined
        }
      >
        {active && (
          <>
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="状态">{STATUS[active.status]?.text ?? active.status}</Descriptions.Item>
              <Descriptions.Item label="被举报对象">
                {TARGET_TYPE[active.targetType] ?? active.targetType} · {active.targetName || `#${active.targetId}`}
                （ID {active.targetId}）
              </Descriptions.Item>
              <Descriptions.Item label="举报人">
                {active.reporterName || '—'}（{String(active.reporterId)}）
              </Descriptions.Item>
              <Descriptions.Item label="举报原因">
                {active.reasonType && <Tag>{active.reasonType}</Tag>}
                {active.reason || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="优先级">
                {PRIORITY[active.priority]?.text ?? active.priority}
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">
                {active.createTime ? dayjs(active.createTime).format('YYYY-MM-DD HH:mm:ss') : '—'}
              </Descriptions.Item>
              {active.status !== 0 && (
                <>
                  <Descriptions.Item label="处理结论">
                    {STATUS[active.status]?.text ?? active.handleResult}
                  </Descriptions.Item>
                  <Descriptions.Item label="处理说明">{active.handleReason || '—'}</Descriptions.Item>
                  <Descriptions.Item label="联动动作">
                    {ACTION[active.actionTaken ?? 'NONE'] ?? active.actionTaken ?? '—'}
                  </Descriptions.Item>
                  <Descriptions.Item label="处理时间">
                    {active.handledAt ? dayjs(active.handledAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
                  </Descriptions.Item>
                </>
              )}
              {active.mergedIntoId != null && (
                <Descriptions.Item label="合并到">
                  举报 #{String(active.mergedIntoId)}
                </Descriptions.Item>
              )}
            </Descriptions>

            <Typography.Title level={5} style={{ marginTop: 24 }}>
              证据材料
            </Typography.Title>
            {evidenceUrls(active.evidenceUrls).length === 0 ? (
              <Typography.Text type="secondary">举报人未提供证据</Typography.Text>
            ) : (
              <Space direction="vertical">
                {evidenceUrls(active.evidenceUrls).map((url, index) => (
                  <a key={url} href={url} target="_blank" rel="noreferrer">
                    <LinkOutlined /> 证据 {index + 1}：{url}
                  </a>
                ))}
              </Space>
            )}
          </>
        )}
      </Drawer>

      <Modal
        title={`处理举报${handling ? ` #${handling.id}` : ''}`}
        open={handling !== null}
        onCancel={() => setHandling(null)}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="提交处理"
        width={560}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="result" label="判定" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio.Button value="VALID">举报成立</Radio.Button>
              <Radio.Button value="INVALID">不成立</Radio.Button>
              <Radio.Button value="NEED_MORE">需补充材料</Radio.Button>
            </Radio.Group>
          </Form.Item>

          <Form.Item
            noStyle
            shouldUpdate={(prev: HandleValues, next: HandleValues) => prev.result !== next.result}
          >
            {({ getFieldValue }) => {
              const result = getFieldValue('result') as HandleValues['result'];
              return (
                <>
                  <Form.Item
                    name="reason"
                    label="处理说明"
                    rules={[
                      { required: true, message: '请填写处理说明' },
                      { min: 2, max: 500, message: '处理说明需为 2-500 字' },
                    ]}
                    extra="处理说明会通知举报人"
                  >
                    <Input.TextArea rows={3} maxLength={500} showCount />
                  </Form.Item>

                  {result === 'VALID' && (
                    <>
                      <Alert
                        type="warning"
                        showIcon
                        style={{ marginBottom: 16 }}
                        message="联动处置会立即生效，并与举报状态在同一事务中提交"
                        description={
                          <span>
                            仅支持下架歌曲、封禁用户、关闭房间；目标类型不匹配时后端会拒绝。
                            当前目标类型：{handling ? TARGET_TYPE[handling.targetType] ?? handling.targetType : '—'}。
                          </span>
                        }
                      />
                      <Form.Item name="action" label="联动动作">
                        <Select
                          options={Object.entries(ACTION).map(([value, label]) => ({ value, label }))}
                        />
                      </Form.Item>
                      <Form.Item
                        name="mergeSiblings"
                        valuePropName="checked"
                        extra="把同一对象的其它待处理举报标记为已合并"
                      >
                        <Checkbox>合并同对象的其它待处理举报</Checkbox>
                      </Form.Item>
                    </>
                  )}
                </>
              );
            }}
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
