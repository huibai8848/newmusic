import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { errorMessage } from '@/api/client';
import { opsApi, type CacheKeyItem } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';

const CONFIG_HINTS: Record<string, string> = {
  'ops.degrade.level': '当前降级等级 0~5，等价于「一键降级」的结果',
  'ops.rate.limit.enabled': '限流总开关；关闭后只观测不拦截',
  'ops.trace.sample-rate': '正常 2xx 请求进入链路缓冲的采样率 0~1',
  'ops.alert.eval.enabled': '告警调度总开关；关闭后不再自动评估',
  'ops.metrics.snapshot.enabled': '每分钟指标落库开关',
  'ops.cost.daily-budget-cent': '每日预算（分），成本记录没有预算时回退到这里',
  'ops.backup.retention-days': '逻辑备份清单保留天数',
  'ops.chaos.enabled': '混沌演练总开关；关闭后启动实验返回 73004',
};

/**
 * 运维配置中心 + Redis 缓存巡检。
 *
 * 配置写 `t_sys_config` 并落变更历史，进程内缓存 2~5 秒刷新；修改降级等级/限流开关
 * 不需要重启。缓存巡检只允许 `ml:` 前缀、只用 SCAN；清理要求前缀至少两段并原样输入确认。
 */
export function ConfigPanel(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:ops:edit');
  const queryClient = useQueryClient();

  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ configValue: string; remark?: string }>();

  const [prefix, setPrefix] = useState('ml:');
  const [inspectPrefix, setInspectPrefix] = useState('ml:');
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [deleting, setDeleting] = useState(false);

  const configs = useQuery({ queryKey: ['ops-configs'], queryFn: opsApi.configs });
  const cache = useQuery({
    queryKey: ['ops-cache', inspectPrefix],
    queryFn: () => opsApi.cacheKeys(inspectPrefix, 100),
  });

  const refreshAll = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-configs'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-degrade'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-rate-limits'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-overview'] });
    void queryClient.invalidateQueries({ queryKey: ['ops-metrics'] });
  };

  const openEdit = (key: string): void => {
    form.setFieldsValue({ configValue: configs.data?.[key] ?? '', remark: undefined });
    setEditingKey(key);
  };

  const submit = async (): Promise<void> => {
    if (!editingKey) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await opsApi.updateConfig(editingKey, values.configValue, values.remark);
      message.success('配置已更新，2~5 秒内热生效');
      setEditingKey(null);
      form.resetFields();
      refreshAll();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const deleteCache = async (): Promise<void> => {
    setDeleting(true);
    try {
      const result = await opsApi.deleteCacheKeys(deleteOpenPrefix, confirmText);
      message.success(`已清理 ${result.deleted} 个键`);
      setDeleteOpen(false);
      setConfirmText('');
      void queryClient.invalidateQueries({ queryKey: ['ops-cache'] });
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setDeleting(false);
    }
  };

  const deleteOpenPrefix = inspectPrefix;
  const colonCount = (value: string): number => (value.match(/:/g) ?? []).length;

  const cacheColumns = [
    { title: 'Key', dataIndex: 'key', ellipsis: true },
    { title: '类型', dataIndex: 'type', width: 100, render: (value: string) => <Tag>{value}</Tag> },
    {
      title: 'TTL',
      dataIndex: 'ttlSeconds',
      width: 120,
      render: (value: number) => (value < 0 ? '永久' : `${value}s`),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="配置写入 t_sys_config 并保留变更历史，进程内缓存 2~5 秒刷新"
        description="降级开关、限流开关、告警调度都可以在这里热更新；不需要重启服务。"
      />

      <Card size="small" title="运维配置">
        <Table
          rowKey="key"
          size="small"
          loading={configs.isLoading}
          pagination={false}
          dataSource={Object.entries(configs.data ?? {}).map(([key, value]) => ({ key, value }))}
          columns={[
            { title: '配置项', dataIndex: 'key', width: 280, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
            { title: '当前值', dataIndex: 'value', width: 180 },
            {
              title: '说明',
              key: 'hint',
              render: (_, row: { key: string }) => CONFIG_HINTS[row.key] ?? '—',
            },
            {
              title: '操作',
              key: 'action',
              width: 80,
              render: (_, row: { key: string }) =>
                canEdit ? (
                  <Button type="link" size="small" onClick={() => openEdit(row.key)}>
                    编辑
                  </Button>
                ) : (
                  '—'
                ),
            },
          ]}
        />
      </Card>

      <Card
        size="small"
        title="Redis 缓存巡检"
        extra={
          <Space>
            <Input.Search
              allowClear
              value={prefix}
              style={{ width: 240 }}
              placeholder="ml:search:"
              onChange={(event) => setPrefix(event.target.value)}
              onSearch={(value) => setInspectPrefix(value.trim() || 'ml:')}
            />
            {canEdit && (
              <Button
                danger
                size="small"
                disabled={colonCount(inspectPrefix) < 2}
                onClick={() => {
                  setConfirmText('');
                  setDeleteOpen(true);
                }}
              >
                清理此前缀
              </Button>
            )}
          </Space>
        }
      >
        <Typography.Paragraph type="secondary">
          只允许 <Typography.Text code>ml:</Typography.Text> 前缀，使用 SCAN 枚举；清理要求前缀至少两段，
          并原样输入确认。共享实例上的其他项目键（如 ranking:*）不可见也不可删。
        </Typography.Paragraph>
        <Space size={8} style={{ marginBottom: 12 }} wrap>
          <Tag color={cache.data?.redisAvailable ? 'green' : 'orange'}>
            Redis {cache.data?.redisAvailable ? '可用' : '不可用'}
          </Tag>
          <Tag>共 {cache.data?.count ?? 0} 个</Tag>
          {cache.data?.truncated && <Tag color="gold">已达上限，仅展示前 100 个</Tag>}
        </Space>
        <Table<CacheKeyItem>
          rowKey="key"
          size="small"
          loading={cache.isLoading}
          dataSource={cache.data?.keys ?? []}
          columns={cacheColumns}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </Card>

      <Modal
        open={editingKey !== null}
        title={`修改配置：${editingKey ?? ''}`}
        okText="保存"
        confirmLoading={saving}
        onOk={submit}
        onCancel={() => setEditingKey(null)}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">
          {editingKey ? CONFIG_HINTS[editingKey] ?? '' : ''}
        </Typography.Paragraph>
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="configValue" label="值" rules={[{ required: true }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="remark" label="变更说明">
            <Input maxLength={200} placeholder="例如：高峰期临时关闭限流" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={deleteOpen}
        title={`清理前缀：${deleteOpenPrefix}`}
        okText="确认清理"
        okButtonProps={{
          danger: true,
          disabled: confirmText !== deleteOpenPrefix || colonCount(deleteOpenPrefix) < 2,
        }}
        confirmLoading={deleting}
        onOk={deleteCache}
        onCancel={() => setDeleteOpen(false)}
        destroyOnClose
      >
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="此操作不可逆"
          description="只会删除该前缀下的键；请确认前缀足够具体（至少两段，例如 ml:search:）。"
        />
        <Typography.Paragraph>
          请输入 <Typography.Text code>{deleteOpenPrefix}</Typography.Text> 以确认：
        </Typography.Paragraph>
        <Input value={confirmText} onChange={(event) => setConfirmText(event.target.value)} />
      </Modal>
    </Space>
  );
}
