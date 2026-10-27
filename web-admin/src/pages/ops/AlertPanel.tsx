import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { opsApi, type AlertRecord, type AlertRule, type AlertSilence } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { fmtFullTime, statusColor } from './format';

interface RuleValues {
  thresholdValue: number;
  operator: string;
  durationSeconds: number;
  severity: string;
  enabled: boolean;
  runbookKey: string;
  description?: string;
}

interface SilenceValues {
  targetType: 'rule' | 'severity';
  ruleName?: string;
  severity?: string;
  durationMinutes: number;
  reason?: string;
}

/**
 * 告警：记录（处理）、规则（阈值/持续时长）、静默（按规则名或级别）。
 *
 * 「for」语义在服务端实现：指标首次满足后要持续 N 秒才落库，避免毛刺造成告警风暴。
 * 静默期仍然留一条 silenced 记录可追溯，静默到期后若指标仍异常会自动提升为 firing。
 */
export function AlertPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:ops:edit');
  const canHandle = hasPerm('admin:ops:alert:handle');
  const queryClient = useQueryClient();

  const [status, setStatus] = useState<string | undefined>('firing');
  const [severity, setSeverity] = useState<string | undefined>();
  const [page, setPage] = useState(1);
  const [handling, setHandling] = useState<AlertRecord | null>(null);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [silenceOpen, setSilenceOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [handleForm] = Form.useForm<{ note?: string }>();
  const [ruleForm] = Form.useForm<RuleValues>();
  const [silenceForm] = Form.useForm<SilenceValues>();

  const records = useQuery({
    queryKey: ['ops-alert-records', status, severity, page],
    queryFn: () => opsApi.alertRecords({ status, severity, page, size: 20 }),
    refetchInterval: 10000,
  });
  const rules = useQuery({ queryKey: ['ops-alert-rules'], queryFn: opsApi.alertRules });
  const silences = useQuery({ queryKey: ['ops-alert-silences'], queryFn: opsApi.alertSilences });
  const overview = useQuery({ queryKey: ['ops-alert-overview'], queryFn: opsApi.alertOverview, refetchInterval: 10000 });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-alert-records'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-alert-rules'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-alert-silences'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-alert-overview'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-overview'] });
  };

  const evaluate = async (): Promise<void> => {
    try {
      const result = await opsApi.evaluateAlerts();
      message.success(
        `评估 ${result.evaluated} 条规则：触发 ${result.fired}，静默 ${result.silenced}，恢复 ${result.resolved}`,
      );
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const submitHandle = async (): Promise<void> => {
    if (!handling) return;
    const values = await handleForm.validateFields();
    setSaving(true);
    try {
      await opsApi.handleAlert(handling.id, values.note);
      message.success('告警已标记为已处理');
      setHandling(null);
      handleForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const openRule = (rule: AlertRule): void => {
    ruleForm.setFieldsValue({
      thresholdValue: rule.thresholdValue,
      operator: rule.operator,
      durationSeconds: rule.durationSeconds,
      severity: rule.severity,
      enabled: rule.enabled === 1,
      runbookKey: rule.runbookKey ?? '',
      description: rule.description ?? undefined,
    });
    setEditingRule(rule);
  };

  const submitRule = async (): Promise<void> => {
    if (!editingRule) return;
    const values = await ruleForm.validateFields();
    setSaving(true);
    try {
      await opsApi.updateAlertRule(editingRule.id, {
        thresholdValue: values.thresholdValue,
        operator: values.operator,
        durationSeconds: values.durationSeconds,
        severity: values.severity,
        enabled: values.enabled ? 1 : 0,
        runbookKey: values.runbookKey,
        description: values.description ?? '',
      });
      message.success('告警规则已更新');
      setEditingRule(null);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const submitSilence = async (): Promise<void> => {
    const values = await silenceForm.validateFields();
    if (values.targetType === 'rule' && !values.ruleName) {
      message.error('请选择要静默的规则');
      return;
    }
    setSaving(true);
    try {
      await opsApi.createSilence({
        ruleName: values.targetType === 'rule' ? values.ruleName : undefined,
        severity: values.targetType === 'severity' ? values.severity : undefined,
        durationMinutes: values.durationMinutes,
        reason: values.reason,
      });
      message.success('静默已生效');
      setSilenceOpen(false);
      silenceForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const cancelSilence = async (row: AlertSilence): Promise<void> => {
    try {
      await opsApi.cancelSilence(row.id);
      message.success('静默已取消');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const recordColumns: ColumnsType<AlertRecord> = [
    {
      title: '级别',
      dataIndex: 'severity',
      width: 70,
      render: (value: string) => <Tag color={value === 'P0' ? 'red' : value === 'P1' ? 'volcano' : 'gold'}>{value}</Tag>,
    },
    { title: '告警', dataIndex: 'alertName', width: 180 },
    { title: '摘要', dataIndex: 'summary', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    { title: '触发时间', dataIndex: 'fireTime', width: 160, render: (v: string) => fmtFullTime(v) },
    {
      title: '处理人',
      dataIndex: 'handlerName',
      width: 110,
      render: (v: string | null) => v || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, row) =>
        canHandle && row.status !== 'resolved' ? (
          <Button type="link" size="small" onClick={() => setHandling(row)}>
            处理
          </Button>
        ) : (
          '—'
        ),
    },
  ];

  const ruleColumns: ColumnsType<AlertRule> = [
    {
      title: '级别',
      dataIndex: 'severity',
      width: 70,
      render: (value: string) => <Tag color={value === 'P0' ? 'red' : value === 'P1' ? 'volcano' : 'gold'}>{value}</Tag>,
    },
    { title: '规则', dataIndex: 'ruleName', width: 180 },
    { title: '指标', dataIndex: 'metric', width: 180 },
    {
      title: '条件',
      key: 'condition',
      width: 180,
      render: (_, row) => `${row.metric} ${row.operator} ${row.thresholdValue}`,
    },
    { title: '持续', dataIndex: 'durationSeconds', width: 90, render: (v: number) => `${v}s` },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 80,
      render: (v: number) => <Tag color={v === 1 ? 'green' : 'default'}>{v === 1 ? '启用' : '停用'}</Tag>,
    },
    { title: '预案', dataIndex: 'runbookKey', width: 130 },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, row) =>
        canEdit ? (
          <Button type="link" size="small" onClick={() => openRule(row)}>
            编辑
          </Button>
        ) : (
          '—'
        ),
    },
  ];

  const silenceColumns: ColumnsType<AlertSilence> = [
    { title: '规则', dataIndex: 'ruleName', render: (v: string | null) => v || '（按级别）' },
    { title: '级别', dataIndex: 'severity', width: 90, render: (v: string | null) => v || '—' },
    { title: '到期时间', dataIndex: 'untilTime', width: 170, render: (v: string) => fmtFullTime(v) },
    { title: '原因', dataIndex: 'reason', ellipsis: true },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, row) =>
        canEdit ? (
          <Popconfirm title="取消这条静默？" onConfirm={() => void cancelSilence(row)}>
            <Button type="link" size="small" danger>
              取消
            </Button>
          </Popconfirm>
        ) : (
          '—'
        ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type={(overview.data?.firing ?? 0) > 0 ? 'error' : 'success'}
        showIcon
        message={`当前告警中 ${overview.data?.firing ?? 0} 条 · 近 24h 共 ${overview.data?.last24h ?? 0} 条 · 静默 ${overview.data?.silences ?? 0} 条`}
        description="规则每 5 秒评估一次；已处理/恢复的告警不会重复通知，指标恢复后服务端会自动 resolved。"
        action={
          canEdit && (
            <Space>
              <Button size="small" onClick={evaluate}>
                立即评估
              </Button>
              <Button size="small" type="primary" onClick={() => setSilenceOpen(true)}>
                新建静默
              </Button>
            </Space>
          )
        }
      />

      <Tabs
        items={[
          {
            key: 'records',
            label: '告警记录',
            children: (
              <Card size="small">
                <Space style={{ marginBottom: 12 }} wrap>
                  <Select
                    allowClear
                    placeholder="状态"
                    style={{ width: 140 }}
                    value={status}
                    onChange={(value) => {
                      setStatus(value);
                      setPage(1);
                    }}
                    options={[
                      { value: 'firing', label: 'firing' },
                      { value: 'silenced', label: 'silenced' },
                      { value: 'resolved', label: 'resolved' },
                    ]}
                  />
                  <Select
                    allowClear
                    placeholder="级别"
                    style={{ width: 120 }}
                    value={severity}
                    onChange={(value) => {
                      setSeverity(value);
                      setPage(1);
                    }}
                    options={['P0', 'P1', 'P2', 'P3'].map((value) => ({ value, label: value }))}
                  />
                  <Button onClick={refresh}>刷新</Button>
                </Space>
                <Table
                  rowKey="id"
                  size="small"
                  loading={records.isLoading}
                  dataSource={records.data?.list ?? []}
                  columns={recordColumns}
                  scroll={{ x: 1000 }}
                  pagination={{
                    current: page,
                    pageSize: 20,
                    total: records.data?.total ?? 0,
                    onChange: setPage,
                    showSizeChanger: false,
                  }}
                />
              </Card>
            ),
          },
          {
            key: 'rules',
            label: `规则（${rules.data?.length ?? 0}）`,
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  size="small"
                  loading={rules.isLoading}
                  dataSource={rules.data ?? []}
                  columns={ruleColumns}
                  pagination={false}
                  scroll={{ x: 1100 }}
                />
              </Card>
            ),
          },
          {
            key: 'silences',
            label: `静默（${silences.data?.length ?? 0}）`,
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  size="small"
                  loading={silences.isLoading}
                  dataSource={silences.data ?? []}
                  columns={silenceColumns}
                  pagination={false}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        open={handling !== null}
        title={`处理告警：${handling?.alertName ?? ''}`}
        okText="标记已处理"
        confirmLoading={saving}
        onOk={submitHandle}
        onCancel={() => setHandling(null)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">{handling?.summary}</Typography.Paragraph>
        <Form form={handleForm} layout="vertical" preserve={false}>
          <Form.Item name="note" label="处理说明">
            <Input.TextArea rows={3} maxLength={500} showCount placeholder="例如：已扩容并确认错误率回落" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={editingRule !== null}
        title={`编辑告警规则：${editingRule?.ruleName ?? ''}`}
        okText="保存"
        confirmLoading={saving}
        onOk={submitRule}
        onCancel={() => setEditingRule(null)}
        destroyOnClose
      >
        <Form form={ruleForm} layout="vertical" preserve={false}>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="operator" label="比较符" rules={[{ required: true }]}>
              <Select
                style={{ width: 100 }}
                options={['GT', 'GE', 'LT', 'LE'].map((value) => ({ value, label: value }))}
              />
            </Form.Item>
            <Form.Item name="thresholdValue" label="阈值" rules={[{ required: true }]} style={{ flex: 1 }}>
              <InputNumber style={{ width: '100%' }} step={0.01} />
            </Form.Item>
            <Form.Item name="durationSeconds" label="持续（秒）" rules={[{ required: true }]}>
              <InputNumber min={0} max={86400} style={{ width: 120 }} />
            </Form.Item>
          </Space>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="severity" label="级别" rules={[{ required: true }]}>
              <Select
                style={{ width: 120 }}
                options={['P0', 'P1', 'P2', 'P3'].map((value) => ({ value, label: value }))}
              />
            </Form.Item>
            <Form.Item name="runbookKey" label="关联预案 key" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Input placeholder="例如 mysql-down" />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} maxLength={255} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={silenceOpen}
        title="新建告警静默"
        okText="创建"
        confirmLoading={saving}
        onOk={submitSilence}
        onCancel={() => setSilenceOpen(false)}
        destroyOnClose
      >
        <Form
          form={silenceForm}
          layout="vertical"
          preserve={false}
          initialValues={{ targetType: 'rule', durationMinutes: 30 }}
        >
          <Form.Item name="targetType" label="静默范围">
            <Select
              options={[
                { value: 'rule', label: '按规则名' },
                { value: 'severity', label: '按级别（该级别全部规则）' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(prev, next) => prev.targetType !== next.targetType}>
            {({ getFieldValue }) =>
              getFieldValue('targetType') === 'rule' ? (
                <Form.Item name="ruleName" label="规则名" rules={[{ required: true }]}>
                  <Select
                    showSearch
                    options={(rules.data ?? []).map((rule) => ({
                      value: rule.ruleName,
                      label: `${rule.ruleName}（${rule.severity}）`,
                    }))}
                  />
                </Form.Item>
              ) : (
                <Form.Item name="severity" label="级别" rules={[{ required: true }]}>
                  <Select options={['P0', 'P1', 'P2', 'P3'].map((value) => ({ value, label: value }))} />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="durationMinutes" label="静默时长（分钟）" rules={[{ required: true }]}>
            <InputNumber min={1} max={1440} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="静默原因">
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
