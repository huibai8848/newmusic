import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { opsApi, type DegradeSwitch } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { DEGRADE_LEVELS, degradeMeta, fmtTime } from './format';

/**
 * 一键降级与开关矩阵。
 *
 * 两个入口都保留：
 * - 「按级别」是应急主路径，一次改一整组开关，带确认与原因，落 `t_degrade_event`；
 * - 「单开关」用于临时关掉某个非核心功能（例如只关礼物），不改变全局等级。
 *
 * 服务端保证超级管理员与降级/登录入口本身不被拦截，所以即使降到 5 级，
 * 这个页面仍然能操作、能恢复（AC-28-003-6）。
 */
export function DegradePanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:ops:edit');
  const queryClient = useQueryClient();
  const [targetLevel, setTargetLevel] = useState<number | null>(null);
  const [changingSwitch, setChangingSwitch] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ reason?: string }>();

  const degrade = useQuery({
    queryKey: ['ops-degrade'],
    queryFn: opsApi.degrade,
    refetchInterval: 5000,
  });
  const switches = useQuery({
    queryKey: ['ops-degrade-switches'],
    queryFn: opsApi.degradeSwitches,
    refetchInterval: 5000,
  });

  const currentLevel = degrade.data?.level ?? 0;
  const current = degradeMeta(currentLevel);

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-degrade'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-degrade-switches'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-overview'] });
  };

  const applyLevel = async (level: number, reason?: string): Promise<void> => {
    setSaving(true);
    try {
      const result = await opsApi.setDegradeLevel(level, reason);
      message.success(`已切换到「${result.levelName}」，变更 ${result.switchesChanged} 个开关`);
      setTargetLevel(null);
      form.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const toggleSwitch = async (row: DegradeSwitch, enabled: boolean): Promise<void> => {
    setChangingSwitch(row.switchKey);
    try {
      await opsApi.updateDegradeSwitch(row.switchKey, enabled, `后台手动${enabled ? '开启' : '关闭'}`);
      message.success(`${row.switchName}已${enabled ? '开启' : '关闭'}`);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setChangingSwitch(null);
    }
  };

  const columns: ColumnsType<DegradeSwitch> = [
    {
      title: '开关',
      dataIndex: 'switchKey',
      width: 220,
      render: (value: string, row) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{row.switchName}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {value}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '触发等级',
      dataIndex: 'degradeLevel',
      width: 100,
      render: (value: number) =>
        value >= 99 ? <Tag color="green">永不关闭</Tag> : <Tag color={degradeMeta(value).color}>L{value}</Tag>,
    },
    {
      title: '当前状态',
      dataIndex: 'enabled',
      width: 120,
      render: (value: number, row) => (
        <Space size={4}>
          <Tag color={value === 1 ? 'green' : 'red'}>{value === 1 ? '开启' : '关闭'}</Tag>
          {row.activeAtCurrentLevel && <Tag color="orange">当前已生效</Tag>}
        </Space>
      ),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    { title: '更新时间', dataIndex: 'updateTime', width: 150, render: (value: string) => fmtTime(value) },
    {
      title: '手动控制',
      key: 'control',
      width: 100,
      render: (_, row) =>
        canEdit ? (
          <Switch
            size="small"
            checked={row.enabled === 1}
            loading={changingSwitch === row.switchKey}
            disabled={row.degradeLevel >= 99}
            onChange={(checked) => void toggleSwitch(row, checked)}
          />
        ) : (
          '—'
        ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="warning"
        showIcon
        message={`当前等级：${current.name}（L${currentLevel}）`}
        description={current.hint}
      />

      <Card size="small" title="一键降级" extra={<Tag color={current.color}>{current.name}</Tag>}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            {DEGRADE_LEVELS.map((item) => (
              <Button
                key={item.level}
                type={item.level === currentLevel ? 'primary' : 'default'}
                danger={item.level >= 4}
                disabled={!canEdit || item.level === currentLevel}
                onClick={() => setTargetLevel(item.level)}
              >
                L{item.level} · {item.name}
              </Button>
            ))}
          </Space>
          <Descriptions size="small" column={1}>
            {DEGRADE_LEVELS.filter((item) => item.level >= 1).map((item) => (
              <Descriptions.Item key={item.level} label={`L${item.level} ${item.name}`}>
                {item.hint}
              </Descriptions.Item>
            ))}
          </Descriptions>
          <Typography.Text type="secondary">
            降级是秒级热更新、可逆的；操作会记录操作人与原因（t_degrade_event），并进入后台操作日志。
          </Typography.Text>
        </Space>
      </Card>

      <Card size="small" title="降级开关矩阵" extra={<Button size="small" onClick={refresh}>刷新</Button>}>
        <Table
          rowKey="switchKey"
          size="small"
          loading={switches.isLoading}
          dataSource={switches.data ?? []}
          columns={columns}
          pagination={false}
          scroll={{ x: 1000 }}
        />
      </Card>

      <Modal
        open={targetLevel !== null}
        title={`确认切换到 L${targetLevel} · ${targetLevel === null ? '' : degradeMeta(targetLevel).name}`}
        okText="确认降级"
        okButtonProps={{ danger: (targetLevel ?? 0) >= 4 }}
        confirmLoading={saving}
        onCancel={() => setTargetLevel(null)}
        onOk={() => {
          void form.validateFields().then((values) => applyLevel(targetLevel as number, values.reason));
        }}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={targetLevel === null ? '' : degradeMeta(targetLevel).hint}
        />
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="reason" label="变更原因（建议填写，便于复盘）">
            <Input.TextArea rows={3} maxLength={200} showCount placeholder="例如：P99 超过 1s，持续 5 分钟" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
