import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { opsApi, type ChaosExperiment } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { fmtFullTime, statusColor } from './format';

const FAULTS = [
  { value: 'REDIS_LIMITER_UNAVAILABLE', label: 'Redis 限流不可用（验证本地兜底）' },
  { value: 'AI_TIMEOUT', label: 'AI 超时（验证熔断与降级文案）' },
  { value: 'SLOW_DB', label: '慢 SQL（验证慢查询指标）' },
];

interface StartValues {
  experimentName: string;
  faultType: string;
  target: string;
  blastRadius: number;
  durationSeconds: number;
  hypothesis?: string;
}

/**
 * 混沌演练：只注入「应用内可安全回滚」的三类故障，不碰共享 MySQL/Redis 实例本身。
 *
 * 硬上限在服务端：同一时刻仅一个实验，时长 ≤ chaos-max-duration-seconds，
 * 爆炸半径 ≤ chaos-max-blast-radius；到点由调度任务自动结束并落库，不依赖人记得关。
 */
export function ChaosPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canChaos = hasPerm('admin:ops:chaos');
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [startOpen, setStartOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<StartValues>();

  const current = useQuery({
    queryKey: ['ops-chaos-current'],
    queryFn: opsApi.chaosCurrent,
    refetchInterval: 3000,
  });
  const list = useQuery({
    queryKey: ['ops-chaos-list', page],
    queryFn: () => opsApi.chaosExperiments({ page, size: 20 }),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-chaos-current'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-chaos-list'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-overview'] });
  };

  const start = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await opsApi.startChaos(values);
      message.success('混沌实验已启动，到点会自动结束');
      setStartOpen(false);
      form.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const stop = async (): Promise<void> => {
    try {
      const result = await opsApi.stopChaos('后台人工停止');
      message.success(`已停止 ${result.stopped} 个运行中的实验`);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<ChaosExperiment> = [
    { title: '实验', dataIndex: 'experimentName', ellipsis: true },
    { title: '故障', dataIndex: 'faultType', width: 210, render: (v: string) => <Tag>{v}</Tag> },
    { title: '目标', dataIndex: 'target', width: 100 },
    { title: '爆炸半径', dataIndex: 'blastRadius', width: 100, render: (v: number) => `${v}%` },
    { title: '时长', dataIndex: 'durationSeconds', width: 90, render: (v: number) => `${v}s` },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    { title: '结果', dataIndex: 'result', ellipsis: true },
    { title: '开始', dataIndex: 'startTime', width: 160, render: (v: string) => fmtFullTime(v) },
  ];

  const running = current.data;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message="演练会真实影响本进程的部分请求，请在低峰期执行"
        description="三个故障点都在应用内注入：限流器走 Redis 失败分支、AI 调用超时、SQL 前延迟 1 秒；不会修改共享 Redis/MySQL 数据。"
        action={
          canChaos &&
          (running ? (
            <Popconfirm title="停止当前混沌实验？" onConfirm={() => void stop()}>
              <Button danger size="small">
                停止实验
              </Button>
            </Popconfirm>
          ) : (
            <Button type="primary" size="small" onClick={() => setStartOpen(true)}>
              启动实验
            </Button>
          ))
        }
      />

      <Card size="small" title="当前实验" loading={current.isLoading}>
        {running ? (
          <Descriptions column={3} size="small">
            <Descriptions.Item label="名称">{running.experimentName}</Descriptions.Item>
            <Descriptions.Item label="故障">{running.faultType}</Descriptions.Item>
            <Descriptions.Item label="剩余时长">{running.durationSeconds}s</Descriptions.Item>
            <Descriptions.Item label="爆炸半径">{running.blastRadius}%</Descriptions.Item>
            <Descriptions.Item label="目标">{running.target}</Descriptions.Item>
            <Descriptions.Item label="开始时间">{fmtFullTime(running.startTime)}</Descriptions.Item>
          </Descriptions>
        ) : (
          <span style={{ color: '#8c8c8c' }}>当前没有运行中的实验</span>
        )}
      </Card>

      <Card size="small" title="实验历史">
        <Table
          rowKey="id"
          size="small"
          loading={list.isLoading}
          dataSource={list.data?.list ?? []}
          columns={columns}
          scroll={{ x: 1100 }}
          pagination={{
            current: page,
            pageSize: 20,
            total: list.data?.total ?? 0,
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </Card>

      <Modal
        open={startOpen}
        title="启动混沌实验"
        okText="启动"
        okButtonProps={{ danger: true }}
        confirmLoading={saving}
        onOk={start}
        onCancel={() => setStartOpen(false)}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={{ target: 'SERVICE', blastRadius: 10, durationSeconds: 30 }}
        >
          <Form.Item name="experimentName" label="实验名称" rules={[{ required: true }]}>
            <Input maxLength={100} placeholder="例如：验证 AI 熔断" />
          </Form.Item>
          <Form.Item name="faultType" label="故障类型" rules={[{ required: true }]}>
            <Select options={FAULTS} />
          </Form.Item>
          <Form.Item name="target" label="目标">
            <Select options={['SERVICE', 'LLM', 'DB', 'REDIS'].map((value) => ({ value, label: value }))} />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="blastRadius" label="爆炸半径（%）">
              <InputNumber min={1} max={100} style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="durationSeconds" label="时长（秒）">
              <InputNumber min={1} max={120} style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Form.Item name="hypothesis" label="假设（演练前写下预期结果）">
            <Input.TextArea rows={2} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
