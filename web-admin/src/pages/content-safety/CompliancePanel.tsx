import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { safetyApi } from '@/api/contentSafety';
import { errorMessage, post } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { CopyrightComplaint, CopyrightInfo } from '@/api/types';

const COPYRIGHT_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '有效', color: 'green' },
  1: { text: '即将到期', color: 'orange' },
  2: { text: '已过期', color: 'red' },
  3: { text: '已下架', color: 'default' },
};

const COMPLAINT_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待处理', color: 'orange' },
  1: { text: '处理中', color: 'blue' },
  2: { text: '已下架', color: 'red' },
  3: { text: '已驳回', color: 'default' },
  4: { text: '反通知', color: 'purple' },
};

function StatsTab(): JSX.Element {
  const audit = useQuery({ queryKey: ['safety-audit-stats'], queryFn: () => safetyApi.audit.stats(7) });
  const sla = useQuery({ queryKey: ['safety-sla-stats'], queryFn: () => safetyApi.audit.slaStats() });
  const daily = useQuery({ queryKey: ['safety-daily-report'], queryFn: () => safetyApi.audit.dailyReport() });
  const queue = useQuery({ queryKey: ['safety-queue-stats'], queryFn: () => safetyApi.audit.queueStats() });
  const copyright = useQuery({ queryKey: ['safety-copyright-stats'], queryFn: () => safetyApi.copyrights.stats() });

  const renderMap = (data?: Record<string, unknown>): JSX.Element => {
    if (!data) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
    return (
      <Descriptions size="small" column={2} bordered>
        {Object.entries(data).map(([key, value]) => (
          <Descriptions.Item key={key} label={key}>
            {typeof value === 'object' ? (
              <Typography.Text code style={{ whiteSpace: 'pre-wrap' }}>
                {JSON.stringify(value, null, 2)}
              </Typography.Text>
            ) : String(value ?? '-')}
          </Descriptions.Item>
        ))}
      </Descriptions>
    );
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="待审核队列"
              value={Number(queue.data?.pending ?? queue.data?.total ?? 0)}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="今日自动拒绝"
              value={Number((audit.data?.byDecision as Record<string, unknown> | undefined)?.REJECT ?? 0)}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="今日转人工"
              value={Number((audit.data?.byDecision as Record<string, unknown> | undefined)?.REVIEW ?? 0)}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="版权即将到期" value={Number(copyright.data?.expiring ?? 0)} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="近 7 天内容审核统计">{renderMap(audit.data)}</Card>
      <Card size="small" title="审核 SLA">{renderMap(sla.data)}</Card>
      <Card size="small" title="今日审核日报">{renderMap(daily.data)}</Card>
      <Card size="small" title="版权统计">{renderMap(copyright.data)}</Card>
    </Space>
  );
}

function CopyrightTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<number | undefined>();
  const [keyword, setKeyword] = useState('');
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<Record<string, unknown>>();

  const query = useQuery({
    queryKey: ['safety-copyrights', pageNum, pageSize, status, keyword],
    queryFn: () => safetyApi.copyrights.page({ pageNum, pageSize, status, keyword }),
  });
  const expiring = useQuery({ queryKey: ['safety-expiring'], queryFn: () => safetyApi.copyrights.expiring(30) });

  const save = useMutation({
    mutationFn: (values: Record<string, unknown>) => safetyApi.copyrights.save(values),
    onSuccess: () => {
      message.success('版权信息已保存');
      setOpen(false);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: ['safety-copyrights'] });
      void queryClient.invalidateQueries({ queryKey: ['safety-expiring'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const check = useMutation({
    mutationFn: () => safetyApi.copyrights.check(),
    onSuccess: (data) => {
      message.success(`到期检查完成：${JSON.stringify(data)}`);
      void queryClient.invalidateQueries({ queryKey: ['safety-copyrights'] });
      void queryClient.invalidateQueries({ queryKey: ['safety-expiring'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const columns: ColumnsType<CopyrightInfo> = [
    { title: '歌曲 ID', dataIndex: 'songId', width: 190, render: (value) => String(value) },
    { title: '版权方', dataIndex: 'copyrightOwner', ellipsis: true },
    { title: '授权区域', dataIndex: 'licenseRegion', width: 120 },
    {
      title: '授权截止',
      dataIndex: 'licenseEnd',
      width: 120,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD') : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: number) => <Tag color={COPYRIGHT_STATUS[value]?.color}>{COPYRIGHT_STATUS[value]?.text}</Tag>,
    },
    { title: '合同号', dataIndex: 'contractNo', width: 160 },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        size="small"
        title="版权信息"
        extra={
          <Space>
            <Button loading={check.isPending} disabled={!hasPerm('admin:copyright:edit')} onClick={() => check.mutate()}>
              立即检查到期
            </Button>
            <Button
              type="primary"
              disabled={!hasPerm('admin:copyright:edit')}
              onClick={() => {
                form.resetFields();
                form.setFieldsValue({ licenseType: 'EXCLUSIVE', licenseRegion: 'CN' });
                setOpen(true);
              }}
            >
              新增版权
            </Button>
          </Space>
        }
      >
        <Space wrap style={{ marginBottom: 12 }}>
          <Input.Search
            allowClear
            placeholder="歌曲 ID / 版权方"
            style={{ width: 240 }}
            onSearch={(value) => {
              setPageNum(1);
              setKeyword(value.trim());
            }}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 140 }}
            options={Object.entries(COPYRIGHT_STATUS).map(([value, item]) => ({
              value: Number(value), label: item.text,
            }))}
            onChange={(value) => {
              setPageNum(1);
              setStatus(value);
            }}
          />
        </Space>
        <Table<CopyrightInfo>
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
      </Card>

      <Card size="small" title="30 天内即将到期">
        <Table<CopyrightInfo>
          rowKey={(row) => String(row.id)}
          size="small"
          loading={expiring.isLoading}
          pagination={false}
          dataSource={expiring.data ?? []}
          columns={columns}
          locale={{ emptyText: '30 天内没有到期版权' }}
        />
      </Card>

      <Modal
        title="新增版权信息"
        open={open}
        confirmLoading={save.isPending}
        onCancel={() => setOpen(false)}
        onOk={() => {
          void form.validateFields().then((values) => save.mutate(values));
        }}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="songId" label="歌曲 ID" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="copyrightOwner" label="版权方" rules={[{ required: true }]}>
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item name="licenseType" label="授权类型">
            <Select
              options={['EXCLUSIVE', 'NON_EXCLUSIVE', 'ORIGINAL', 'COVER'].map((value) => ({
                value, label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="licenseRegion" label="授权区域">
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="licenseStart" label="授权开始（YYYY-MM-DD）">
            <Input placeholder="2026-01-01" />
          </Form.Item>
          <Form.Item name="licenseEnd" label="授权结束（YYYY-MM-DD）" rules={[{ required: true }]}>
            <Input placeholder="2027-01-01" />
          </Form.Item>
          <Form.Item name="licenseScope" label="授权范围">
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item name="contractNo" label="合同号">
            <Input maxLength={128} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function ComplaintsTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<number | undefined>();
  const [active, setActive] = useState<CopyrightComplaint | null>(null);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ action: string; result: string; notify: boolean }>();

  const query = useQuery({
    queryKey: ['safety-complaints', pageNum, pageSize, status],
    queryFn: () => safetyApi.complaints.page({ pageNum, pageSize, status }),
  });

  const handle = useMutation({
    mutationFn: (values: { action: string; result: string; notify: boolean }) => {
      if (!active) throw new Error('未选择投诉');
      return safetyApi.complaints.handle(active.id, values);
    },
    onSuccess: () => {
      message.success('投诉已处理');
      setOpen(false);
      setActive(null);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: ['safety-complaints'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="投诉人联系方式加密存储"
        description="后台列表与详情都不会回传联系方式明文；下架动作会同步版权状态并通知上传者。"
      />
      <Card size="small">
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 150, marginBottom: 12 }}
          options={Object.entries(COMPLAINT_STATUS).map(([value, item]) => ({
            value: Number(value), label: item.text,
          }))}
          onChange={(value) => {
            setPageNum(1);
            setStatus(value);
          }}
        />
        <Table<CopyrightComplaint>
          rowKey={(row) => String(row.id)}
          size="small"
          loading={query.isLoading}
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
          columns={[
            { title: '投诉号', dataIndex: 'complaintNo', width: 180 },
            { title: '投诉人', dataIndex: 'complainantName', width: 140 },
            { title: '歌曲 ID', dataIndex: 'songIds', width: 180, ellipsis: true },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (value: number) => (
                <Tag color={COMPLAINT_STATUS[value]?.color}>{COMPLAINT_STATUS[value]?.text}</Tag>
              ),
            },
            {
              title: '提交时间',
              dataIndex: 'createTime',
              width: 170,
              render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
            },
            {
              title: '操作',
              width: 100,
              render: (_, row) => (
                <Button
                  size="small"
                  disabled={!hasPerm('admin:copyright:edit') || row.status === 2 || row.status === 3}
                  onClick={() => {
                    setActive(row);
                    form.setFieldsValue({ action: 'TAKE_DOWN', notify: true });
                    setOpen(true);
                  }}
                >
                  处理
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="处理侵权投诉"
        open={open}
        confirmLoading={handle.isPending}
        onCancel={() => setOpen(false)}
        onOk={() => {
          void form.validateFields().then((values) => handle.mutate(values));
        }}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="action" label="处理动作" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio.Button value="TAKE_DOWN">下架</Radio.Button>
              <Radio.Button value="REJECT">驳回</Radio.Button>
              <Radio.Button value="NEED_MORE">补充材料</Radio.Button>
              <Radio.Button value="COUNTER_NOTICE">反通知</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="result" label="处理说明" rules={[{ required: true }, { min: 2, max: 500 }]}>
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
          <Form.Item name="notify" label="通知上传者">
            <Radio.Group>
              <Radio value={true}>通知</Radio>
              <Radio value={false}>不通知</Radio>
            </Radio.Group>
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function AiLabelTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const [playlistId, setPlaylistId] = useState('');
  const [model, setModel] = useState('');
  const [promptVersion, setPromptVersion] = useState('');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);

  const mark = useMutation({
    mutationFn: () => {
      const query = new URLSearchParams();
      if (model) query.set('model', model);
      if (promptVersion) query.set('promptVersion', promptVersion);
      const suffix = query.toString();
      return post<Record<string, unknown>>(
        `/playlists/${encodeURIComponent(playlistId.trim())}/ai-label${suffix ? `?${suffix}` : ''}`,
      );
    },
    onSuccess: (data) => {
      setResult(data);
      message.success('已标记为 AI 生成并触发审核');
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="AI 标识不可移除"
        description="标记后 C 端会展示「AI 生成」角标，任何角色调用移除接口都会返回 72024。"
      />
      <Card size="small" title="标记歌单为 AI 生成">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input
            value={playlistId}
            placeholder="歌单 ID"
            style={{ maxWidth: 320 }}
            onChange={(event) => setPlaylistId(event.target.value)}
          />
          <Input
            value={model}
            placeholder="生成模型（如 claude-sonnet-5-5）"
            style={{ maxWidth: 320 }}
            onChange={(event) => setModel(event.target.value)}
          />
          <Input
            value={promptVersion}
            placeholder="Prompt 版本（如 v1）"
            style={{ maxWidth: 320 }}
            onChange={(event) => setPromptVersion(event.target.value)}
          />
          <Button
            type="primary"
            loading={mark.isPending}
            disabled={!hasPerm('admin:audit:edit') || !playlistId.trim()}
            onClick={() => mark.mutate()}
          >
            标记并送审
          </Button>
        </Space>
      </Card>
      {result ? (
        <Card size="small" title="标记结果">
          <Descriptions size="small" column={1} bordered>
            {Object.entries(result).map(([key, value]) => (
              <Descriptions.Item key={key} label={key}>
                {typeof value === 'object' ? JSON.stringify(value) : String(value ?? '-')}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </Card>
      ) : null}
    </Space>
  );
}

export function CompliancePanel(): JSX.Element {
  return (
    <Tabs
      items={[
        { key: 'stats', label: '合规统计', children: <StatsTab /> },
        { key: 'copyright', label: '版权信息', children: <CopyrightTab /> },
        { key: 'complaints', label: '侵权投诉', children: <ComplaintsTab /> },
        { key: 'ai', label: 'AI 生成标识', children: <AiLabelTab /> },
      ]}
    />
  );
}
