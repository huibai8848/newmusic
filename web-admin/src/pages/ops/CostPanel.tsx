import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { opsApi, type CostRecord } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { fmtCent, fmtRatio, fmtTime } from './format';

const CATEGORIES = [
  'CDN',
  'SERVER',
  'DATABASE',
  'REDIS',
  'STORAGE',
  'AI',
  'AUDIT',
  'SMS',
  'LOG',
  'DB',
  'CACHE',
  'MQ',
  'SEARCH',
  'MONITOR',
  'OTHER',
];

interface AddValues {
  category: string;
  service: string;
  amountCent: number;
  budgetCent?: number;
  detail?: string;
}

/**
 * 成本核算：金额以「分」存整数，展示时换元，避免浮点累加误差。
 *
 * 预算取当日 `budget_cent` 合计，没有记录时回退配置中心
 * `ops.cost.daily-budget-cent`；使用率 ≥80% 与告警规则 `ai_cost_budget` 同阈值。
 */
export function CostPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:ops:edit');
  const queryClient = useQueryClient();
  const [days, setDays] = useState(30);
  const [addOpen, setAddOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<AddValues>();

  const overview = useQuery({
    queryKey: ['ops-costs'],
    queryFn: opsApi.costs,
    refetchInterval: 30000,
  });
  const records = useQuery({
    queryKey: ['ops-cost-records', days],
    queryFn: () => opsApi.costRecords({ days, limit: 200 }),
  });

  const data = overview.data;
  const ratio = data?.todayUsageRatio ?? 0;

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-costs'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-cost-records'] });
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await opsApi.addCost(values);
      message.success('成本记录已补录');
      setAddOpen(false);
      form.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const recordColumns: ColumnsType<CostRecord> = [
    { title: '日期', dataIndex: 'costDate', width: 110 },
    { title: '分类', dataIndex: 'category', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: '服务', dataIndex: 'service', width: 160 },
    { title: '金额', dataIndex: 'amountCent', width: 110, render: (v: number) => fmtCent(v) },
    { title: '预算', dataIndex: 'budgetCent', width: 110, render: (v: number) => fmtCent(v) },
    { title: '明细', dataIndex: 'detail', ellipsis: true },
    { title: '录入时间', dataIndex: 'createTime', width: 160, render: (v: string) => fmtTime(v) },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {data?.alert && (
        <Alert
          type="warning"
          showIcon
          message={`今日成本已用掉预算的 ${fmtRatio(ratio)}`}
          description="超过 80% 会触发 ai_cost_budget 告警；请检查 AI/带宽/存储等大头，必要时启动降级。"
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="今日成本" value={fmtCent(data?.todayAmountCent ?? 0)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="今日预算" value={fmtCent(data?.todayBudgetCent ?? 0)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="剩余预算" value={fmtCent(data?.todayRemainingCent ?? 0)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="近 7 天" value={fmtCent(data?.last7DaysCent ?? 0)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic title="近 30 天" value={fmtCent(data?.last30DaysCent ?? 0)} />
          </Card>
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <Card size="small">
            <Statistic
              title="预算使用率"
              value={fmtRatio(ratio)}
              valueStyle={{ color: ratio >= 0.8 ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="今日预算水位">
        <Progress
          percent={Math.min(100, Math.round(ratio * 100))}
          status={ratio >= 0.8 ? 'exception' : 'normal'}
          format={(percent) => `${percent}%`}
        />
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={8}>
          <Card size="small" title="按分类（近 30 天）">
            <Table
              rowKey="category"
              size="small"
              pagination={false}
              dataSource={data?.byCategory ?? []}
              columns={[
                { title: '分类', dataIndex: 'category' },
                { title: '金额', dataIndex: 'amount', width: 120, render: (v: number) => fmtCent(v) },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="按服务（近 30 天）">
            <Table
              rowKey="service"
              size="small"
              pagination={false}
              dataSource={data?.byService ?? []}
              columns={[
                { title: '服务', dataIndex: 'service', ellipsis: true },
                { title: '金额', dataIndex: 'amount', width: 120, render: (v: number) => fmtCent(v) },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="按天（近 30 天）">
            <Table
              rowKey="date"
              size="small"
              pagination={{ pageSize: 5, showSizeChanger: false }}
              dataSource={data?.daily ?? []}
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '成本', dataIndex: 'amount', width: 100, render: (v: number) => fmtCent(v) },
                { title: '预算', dataIndex: 'budget', width: 100, render: (v: number) => fmtCent(v) },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card
        size="small"
        title="成本明细"
        extra={
          <Space>
            <Select
              style={{ width: 130 }}
              value={days}
              onChange={setDays}
              options={[7, 30, 90].map((value) => ({ value, label: `近 ${value} 天` }))}
            />
            {canEdit && (
              <Button type="primary" size="small" onClick={() => setAddOpen(true)}>
                补录成本
              </Button>
            )}
          </Space>
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={records.isLoading}
          dataSource={records.data ?? []}
          columns={recordColumns}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </Card>

      <Modal
        open={addOpen}
        title="补录成本"
        okText="保存"
        confirmLoading={saving}
        onOk={submit}
        onCancel={() => setAddOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false} initialValues={{ budgetCent: 0 }}>
          <Form.Item name="category" label="分类" rules={[{ required: true }]}>
            <Select options={CATEGORIES.map((value) => ({ value, label: value }))} />
          </Form.Item>
          <Form.Item name="service" label="服务/资源" rules={[{ required: true }]}>
            <Input maxLength={64} placeholder="例如 cdn-main、llm-api" />
          </Form.Item>
          <Form.Item name="amountCent" label="金额（分）" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="budgetCent" label="当日预算（分，0 表示不设预算）">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="detail" label="明细">
            <Input.TextArea rows={2} maxLength={300} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
