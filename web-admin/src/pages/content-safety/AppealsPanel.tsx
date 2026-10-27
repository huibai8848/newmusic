import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Radio,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { safetyApi } from '@/api/contentSafety';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { ContentAppeal } from '@/api/types';

const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待处理', color: 'orange' },
  1: { text: '已通过', color: 'green' },
  2: { text: '已驳回', color: 'red' },
};

interface HandleValues {
  decision: 'APPROVE' | 'REJECT';
  result: string;
  restoreCredit: boolean;
}

export function AppealsPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<{ status?: number; targetType?: string; overdue?: boolean }>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [active, setActive] = useState<ContentAppeal | null>(null);
  const [handleOpen, setHandleOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form] = Form.useForm<HandleValues>();

  const query = useQuery({
    queryKey: ['safety-appeals', filters, pageNum, pageSize],
    queryFn: () => safetyApi.appeals.page({ ...filters, pageNum, pageSize }),
  });
  const stats = useQuery({ queryKey: ['safety-appeal-stats'], queryFn: () => safetyApi.appeals.stats(30) });

  const handle = useMutation({
    mutationFn: (values: HandleValues) => {
      if (!active) throw new Error('未选择申诉');
      return safetyApi.appeals.handle(active.id, values);
    },
    onSuccess: () => {
      message.success('申诉已处理');
      setHandleOpen(false);
      setActive(null);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: ['safety-appeals'] });
      void queryClient.invalidateQueries({ queryKey: ['safety-appeal-stats'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const openHandle = (row: ContentAppeal): void => {
    setActive(row);
    form.resetFields();
    form.setFieldsValue({ decision: 'APPROVE', restoreCredit: true });
    setHandleOpen(true);
  };

  const columns: ColumnsType<ContentAppeal> = [
    { title: '申诉号', dataIndex: 'id', width: 190, render: (value) => String(value) },
    { title: '用户', dataIndex: 'userId', width: 190, render: (value) => String(value) },
    {
      title: '类型',
      dataIndex: 'targetType',
      width: 100,
      render: (value: string) => <Tag color={value === 'CONTENT' ? 'blue' : 'purple'}>{value}</Tag>,
    },
    { title: '目标 ID', dataIndex: 'targetId', width: 190 },
    { title: '理由', dataIndex: 'reason', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: number) => <Tag color={STATUS[value]?.color}>{STATUS[value]?.text ?? value}</Tag>,
    },
    {
      title: 'SLA',
      dataIndex: 'slaDeadline',
      width: 170,
      render: (value?: string) =>
        value ? (
          <Typography.Text type={dayjs(value).isBefore(dayjs()) ? 'danger' : undefined}>
            {dayjs(value).format('MM-DD HH:mm')}
          </Typography.Text>
        ) : '-',
    },
    {
      title: '提交时间',
      dataIndex: 'createTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      width: 150,
      render: (_, row) => (
        <Space>
          <Button
            size="small"
            onClick={() => {
              setActive(row);
              setDrawerOpen(true);
            }}
          >
            详情
          </Button>
          <Button
            size="small"
            type="primary"
            disabled={!hasPerm('admin:appeal:edit') || row.status !== 0}
            onClick={() => openHandle(row)}
          >
            处理
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={4}><Card size="small"><Statistic title="近 30 天" value={stats.data?.total ?? 0} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="待处理" value={stats.data?.pending ?? 0} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="已通过" value={stats.data?.approved ?? 0} /></Card></Col>
        <Col span={4}><Card size="small"><Statistic title="已驳回" value={stats.data?.rejected ?? 0} /></Card></Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title="误判率" value={((stats.data?.misjudgeRate ?? 0) * 100).toFixed(1)} suffix="%" />
          </Card>
        </Col>
        <Col span={4}><Card size="small"><Statistic title="SLA 超时" value={stats.data?.overdue ?? 0} /></Card></Col>
      </Row>

      <Alert
        type="warning"
        showIcon
        message="复核回避"
        description="原处置审核员不能复核自己的申诉（72013），请换一个有 admin:appeal:edit 权限的账号处理。"
      />

      <Card size="small">
        <Space wrap>
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 130 }}
            options={Object.entries(STATUS).map(([value, item]) => ({ value: Number(value), label: item.text }))}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, status: value }));
            }}
          />
          <Select
            allowClear
            placeholder="申诉类型"
            style={{ width: 150 }}
            options={[
              { value: 'CONTENT', label: '内容申诉' },
              { value: 'ACCOUNT', label: '账号处置申诉' },
            ]}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, targetType: value }));
            }}
          />
          <Select
            allowClear
            placeholder="SLA"
            style={{ width: 140 }}
            options={[{ value: true, label: '仅超时' }]}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, overdue: value }));
            }}
          />
        </Space>
      </Card>

      <Table<ContentAppeal>
        rowKey={(row) => String(row.id)}
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.list ?? []}
        pagination={{
          current: pageNum,
          pageSize,
          total: query.data?.total ?? 0,
          showSizeChanger: true,
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
        }}
      />

      <Modal
        title="处理申诉"
        open={handleOpen}
        confirmLoading={handle.isPending}
        onCancel={() => setHandleOpen(false)}
        onOk={() => {
          void form.validateFields().then((values) => handle.mutate(values));
        }}
      >
        {active ? (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={`${active.targetType} · ${active.targetId}`}
            description={active.reason}
          />
        ) : null}
        <Form form={form} layout="vertical">
          <Form.Item name="decision" label="结论" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio.Button value="APPROVE">通过并恢复</Radio.Button>
              <Radio.Button value="REJECT">驳回</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="result"
            label="处理说明"
            rules={[{ required: true, message: '请填写处理说明' }, { min: 2, max: 500 }]}
          >
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Form.Item name="restoreCredit" label="通过时恢复信用分">
            <Radio.Group>
              <Radio value={true}>恢复</Radio>
              <Radio value={false}>不恢复（仅解除限制）</Radio>
            </Radio.Group>
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        width={520}
        title="申诉详情"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      >
        {active ? (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="申诉号">{String(active.id)}</Descriptions.Item>
            <Descriptions.Item label="用户">{String(active.userId)}</Descriptions.Item>
            <Descriptions.Item label="类型">{active.targetType}</Descriptions.Item>
            <Descriptions.Item label="目标 ID">{active.targetId}</Descriptions.Item>
            <Descriptions.Item label="理由">{active.reason}</Descriptions.Item>
            <Descriptions.Item label="证据">
              {active.evidenceImages
                ? active.evidenceImages.split(',').map((url) => (
                    <div key={url}>
                      <a href={url} target="_blank" rel="noreferrer">{url}</a>
                    </div>
                  ))
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={STATUS[active.status]?.color}>{STATUS[active.status]?.text}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="处理人">{active.handledBy ? String(active.handledBy) : '-'}</Descriptions.Item>
            <Descriptions.Item label="处理结果">{active.result ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="SLA 截止">
              {active.slaDeadline ? dayjs(active.slaDeadline).format('YYYY-MM-DD HH:mm') : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="提交时间">
              {active.createTime ? dayjs(active.createTime).format('YYYY-MM-DD HH:mm') : '-'}
            </Descriptions.Item>
          </Descriptions>
        ) : null}
      </Drawer>
    </Space>
  );
}
