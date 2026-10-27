import { useCallback, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { auditApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { AuditRecord, AuditTask, PageResult } from '@/api/types';

const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待领取', color: 'orange' },
  1: { text: '处理中', color: 'blue' },
  2: { text: '已通过', color: 'green' },
  3: { text: '已拒绝', color: 'red' },
  4: { text: '已关闭', color: 'default' },
};

const PRIORITY: Record<number, { text: string; color: string }> = {
  1: { text: '高', color: 'red' },
  2: { text: '普通', color: 'default' },
};

const ACTION_TEXT: Record<string, string> = {
  CLAIM: '领取任务',
  RELEASE: '释放任务',
  APPROVE: '审核通过',
  REJECT: '审核拒绝',
};

interface Filters {
  keyword?: string;
  status?: number;
  priority?: number;
  taskType?: string;
  targetType?: string;
  onlyMine?: boolean;
  escalated?: boolean;
}

interface HandleValues {
  result: 'APPROVE' | 'REJECT';
  reason?: string;
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable;
}

export function AuditsPage(): JSX.Element {
  const { admin, hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [active, setActive] = useState<AuditTask | null>(null);
  const [handleOpen, setHandleOpen] = useState(false);
  const [handling, setHandling] = useState(false);
  const [claiming, setClaiming] = useState(false);
  const [handleForm] = Form.useForm<HandleValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<AuditTask>>({
    queryKey: ['admin-audits', query],
    queryFn: () => auditApi.page(query),
  });

  const recordsQuery = useQuery<AuditRecord[]>({
    queryKey: ['admin-audit-records', active?.id],
    queryFn: () => auditApi.records(active?.id ?? ''),
    enabled: active !== null,
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-audits'] });
    void queryClient.invalidateQueries({ queryKey: ['admin-audit-records'] });
  };

  const detail = async (task: AuditTask): Promise<void> => {
    try {
      const latest = await auditApi.detail(task.id);
      setActive(latest);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const isMine = (task: AuditTask): boolean =>
    task.claimedBy != null && String(task.claimedBy) === String(admin?.id);

  const isOpenTask = (task: AuditTask): boolean => task.status === 0 || task.status === 1;

  const claim = async (task: AuditTask): Promise<void> => {
    setClaiming(true);
    try {
      const claimed = await auditApi.claim(task.id);
      message.success('任务已领取，10 分钟内有效');
      if (active && String(active.id) === String(task.id)) {
        setActive(claimed);
      }
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
      refresh();
    } finally {
      setClaiming(false);
    }
  };

  const release = async (task: AuditTask): Promise<void> => {
    try {
      await auditApi.release(task.id);
      message.success('任务已放回待领池');
      refresh();
      if (active && String(active.id) === String(task.id)) {
        setActive(await auditApi.detail(task.id));
      }
    } catch (error) {
      message.error(errorMessage(error));
      refresh();
    }
  };

  const openHandle = useCallback(
    (result: 'APPROVE' | 'REJECT'): void => {
      if (!active) return;
      handleForm.setFieldsValue({ result, reason: active.rejectReason ?? '' });
      setHandleOpen(true);
    },
    [active, handleForm],
  );

  useEffect(() => {
    if (!active || handleOpen) return undefined;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (isTypingTarget(event.target)) return;
      if (!isOpenTask(active) || !hasPerm('admin:audit:handle')) return;
      const key = event.key.toLowerCase();
      if (key === 'a') {
        event.preventDefault();
        openHandle('APPROVE');
      } else if (key === 'r') {
        event.preventDefault();
        openHandle('REJECT');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [active, handleOpen, hasPerm, openHandle]);

  const submitHandle = async (): Promise<void> => {
    if (!active) return;
    const values = await handleForm.validateFields();
    setHandling(true);
    try {
      const updated = await auditApi.handle(active.id, {
        result: values.result,
        reason: values.reason?.trim() || undefined,
        version: active.version,
      });
      message.success(values.result === 'APPROVE' ? '已通过' : '已拒绝');
      setHandleOpen(false);
      setActive(updated);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
      refresh();
    } finally {
      setHandling(false);
    }
  };

  const columns: ColumnsType<AuditTask> = [
    {
      title: '任务',
      key: 'title',
      width: 260,
      render: (_, record) => (
        <div>
          <div>{record.title || `内容 #${record.targetId}`}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {record.taskType} · {record.targetType}#{record.targetId}
          </Typography.Text>
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
      width: 110,
      render: (value: number) => {
        const status = STATUS[value] ?? { text: `未知(${value})`, color: 'default' };
        return <Tag color={status.color}>{status.text}</Tag>;
      },
    },
    {
      title: '机审',
      key: 'machine',
      width: 150,
      render: (_, record) =>
        record.machineResult ? (
          <span>
            {record.machineResult}
            {record.machineScore != null && (
              <Typography.Text type="secondary">（{Number(record.machineScore).toFixed(2)}）</Typography.Text>
            )}
          </span>
        ) : (
          '—'
        ),
    },
    {
      title: '提交人',
      dataIndex: 'submitterId',
      width: 160,
      render: (value?: string) => (value ? String(value) : '系统'),
    },
    {
      title: '领取情况',
      key: 'claim',
      width: 170,
      render: (_, record) => {
        if (record.claimedBy == null) return '—';
        return (
          <span>
            {isMine(record) ? '我' : String(record.claimedBy)}
            <br />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {record.claimExpireAt
                ? `锁至 ${dayjs(record.claimExpireAt).format('HH:mm:ss')}`
                : '—'}
            </Typography.Text>
          </span>
        );
      },
    },
    {
      title: '提交时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '标记',
      key: 'escalated',
      width: 100,
      render: (_, record) =>
        record.escalated === 1 ? <Tag color="red">超时升级</Tag> : null,
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 200,
      render: (_, record) => (
        <Space size={4} wrap>
          <Button type="link" size="small" onClick={() => void detail(record)}>
            查看
          </Button>
          {hasPerm('admin:audit:handle') && record.status === 0 && (
            <Button type="link" size="small" loading={claiming} onClick={() => void claim(record)}>
              领取
            </Button>
          )}
          {hasPerm('admin:audit:handle') && isOpenTask(record) && (
            <Button
              type="link"
              size="small"
              onClick={() => {
                setActive(record);
                handleForm.setFieldsValue({ result: 'APPROVE', reason: '' });
                setHandleOpen(true);
              }}
            >
              处理
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const canHandleActive = active !== null && isOpenTask(active) && hasPerm('admin:audit:handle');

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="任务标题 / 目标 ID"
          style={{ width: 220 }}
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
        <Input
          allowClear
          placeholder="任务类型（如 SONG）"
          style={{ width: 180 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, taskType: value || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="目标类型（如 SONG）"
          style={{ width: 180 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, targetType: value || undefined }));
          }}
        />
        <Space size={4}>
          <Switch
            size="small"
            onChange={(checked) => {
              setPageNum(1);
              setFilters((current) => ({ ...current, onlyMine: checked || undefined }));
            }}
          />
          <Typography.Text>只看我的</Typography.Text>
        </Space>
        <Space size={4}>
          <Switch
            size="small"
            onChange={(checked) => {
              setPageNum(1);
              setFilters((current) => ({ ...current, escalated: checked || undefined }));
            }}
          />
          <Typography.Text>只看超时升级</Typography.Text>
        </Space>
      </Space>

      <Table<AuditTask>
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
          showTotal: (total) => `共 ${total} 个任务`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Drawer
        title={active?.title || (active ? `审核任务 #${active.id}` : '审核详情')}
        width={720}
        open={active !== null}
        onClose={() => setActive(null)}
        extra={
          canHandleActive ? (
            <Space>
              {active?.status === 0 && (
                <Button loading={claiming} onClick={() => active && void claim(active)}>
                  领取
                </Button>
              )}
              {active?.status === 1 && isMine(active) && (
                <Button onClick={() => active && void release(active)}>释放</Button>
              )}
              <Button type="primary" onClick={() => openHandle('APPROVE')}>
                通过 (A)
              </Button>
              <Button danger onClick={() => openHandle('REJECT')}>
                拒绝 (R)
              </Button>
            </Space>
          ) : undefined
        }
      >
        {active && (
          <>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="任务 ID">{String(active.id)}</Descriptions.Item>
              <Descriptions.Item label="状态">{STATUS[active.status]?.text ?? active.status}</Descriptions.Item>
              <Descriptions.Item label="任务类型">{active.taskType}</Descriptions.Item>
              <Descriptions.Item label="目标">
                {active.targetType}#{active.targetId}
              </Descriptions.Item>
              <Descriptions.Item label="优先级">{PRIORITY[active.priority]?.text ?? active.priority}</Descriptions.Item>
              <Descriptions.Item label="提交人">
                {active.submitterId ? String(active.submitterId) : '系统'}
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">
                {active.createTime ? dayjs(active.createTime).format('YYYY-MM-DD HH:mm:ss') : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="处理时间">
                {active.handledAt ? dayjs(active.handledAt).format('YYYY-MM-DD HH:mm:ss') : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="机审结果" span={2}>
                {active.machineResult || '—'}
                {active.machineScore != null && `（置信度 ${Number(active.machineScore).toFixed(2)}）`}
              </Descriptions.Item>
              {active.escalated === 1 && (
                <Descriptions.Item label="升级" span={2}>
                  <Tag color="red">超过 24 小时未处理，已升级</Tag>
                </Descriptions.Item>
              )}
              {active.rejectReason && (
                <Descriptions.Item label="拒绝原因" span={2}>
                  {active.rejectReason}
                </Descriptions.Item>
              )}
            </Descriptions>

            <Typography.Title level={5} style={{ marginTop: 24 }}>
              内容快照
            </Typography.Title>
            {active.contentSnapshot ? (
              <pre className="content-snapshot">{active.contentSnapshot}</pre>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该任务没有内容快照" />
            )}

            <Alert
              style={{ marginTop: 16 }}
              type="info"
              showIcon
              message="业务联动范围"
              description={
                active.taskType === 'SONG'
                  ? '歌曲类任务处理后会同步修改歌曲审核状态，并通知提交人。'
                  : '非歌曲类任务的结论只写入审核记录，不会改动尚未交付的业务表。'
              }
            />

            <Typography.Title level={5} style={{ marginTop: 24 }}>
              审核记录
            </Typography.Title>
            {recordsQuery.isLoading ? (
              <Typography.Text type="secondary">加载中…</Typography.Text>
            ) : (recordsQuery.data ?? []).length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无审核记录" />
            ) : (
              <Timeline
                items={(recordsQuery.data ?? []).map((record) => ({
                  key: String(record.id),
                  color: record.action === 'REJECT' ? 'red' : record.action === 'APPROVE' ? 'green' : 'blue',
                  children: (
                    <div>
                      <Space>
                        <Typography.Text strong>{ACTION_TEXT[record.action] ?? record.action}</Typography.Text>
                        <Typography.Text type="secondary">
                          {record.auditorName || (record.auditorId ? String(record.auditorId) : '系统')}
                        </Typography.Text>
                        <Typography.Text type="secondary">
                          {record.createTime ? dayjs(record.createTime).format('MM-DD HH:mm:ss') : ''}
                        </Typography.Text>
                      </Space>
                      {record.reason && <div>{record.reason}</div>}
                    </div>
                  ),
                }))}
              />
            )}
          </>
        )}
      </Drawer>

      <Modal
        title={`处理审核任务：${active?.title ?? ''}`}
        open={handleOpen}
        onCancel={() => setHandleOpen(false)}
        onOk={() => void submitHandle()}
        confirmLoading={handling}
        okText="提交结论"
      >
        <Form form={handleForm} layout="vertical">
          <Form.Item name="result" label="结论" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio.Button value="APPROVE">通过 (A)</Radio.Button>
              <Radio.Button value="REJECT">拒绝 (R)</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, next) => prev.result !== next.result}
          >
            {({ getFieldValue }) =>
              getFieldValue('result') === 'REJECT' ? (
                <Form.Item
                  name="reason"
                  label="拒绝原因"
                  rules={[
                    { required: true, message: '拒绝必须填写原因' },
                    { min: 5, max: 500, message: '原因需为 5-500 个字符' },
                  ]}
                >
                  <Input.TextArea rows={3} maxLength={500} showCount />
                </Form.Item>
              ) : (
                <Form.Item name="reason" label="备注（可选）" rules={[{ max: 500 }]}>
                  <Input.TextArea rows={2} maxLength={500} showCount />
                </Form.Item>
              )
            }
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
