import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { configApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type { PageResult, SysConfig, SysConfigHistory } from '@/api/types';

const VALUE_TYPE: Record<number, string> = {
  1: '字符串',
  2: '整数',
  3: '布尔',
  4: 'JSON',
};

const CHANGE_TYPE: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  ROLLBACK: '回滚',
};

interface Filters {
  keyword?: string;
  group?: string;
}

interface CreateValues {
  configKey: string;
  valueType: number;
  configGroup?: string;
  configValue: string;
  description?: string;
}

interface EditValues {
  configValue: string;
  remark?: string;
}

export function ConfigsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<SysConfig | null>(null);
  const [historyOf, setHistoryOf] = useState<SysConfig | null>(null);
  const [saving, setSaving] = useState(false);
  const [createForm] = Form.useForm<CreateValues>();
  const [editForm] = Form.useForm<EditValues>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<SysConfig>>({
    queryKey: ['admin-configs', query],
    queryFn: () => configApi.page(query),
  });

  const historyQuery = useQuery<SysConfigHistory[]>({
    queryKey: ['admin-config-history', historyOf?.id],
    queryFn: () => configApi.history(historyOf?.id ?? ''),
    enabled: historyOf !== null,
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-configs'] });
  };

  const openCreate = (): void => {
    createForm.resetFields();
    createForm.setFieldsValue({ valueType: 1 });
    setCreating(true);
  };

  const openEdit = (config: SysConfig): void => {
    editForm.resetFields();
    editForm.setFieldsValue({ configValue: config.configValue ?? '', remark: undefined });
    setEditing(config);
  };

  const submitCreate = async (): Promise<void> => {
    const values = await createForm.validateFields();
    setSaving(true);
    try {
      await configApi.create({
        configKey: values.configKey.trim(),
        configValue: values.configValue,
        valueType: values.valueType,
        configGroup: values.configGroup?.trim() || undefined,
        description: values.description?.trim() || undefined,
      });
      message.success('配置已新增');
      setCreating(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const submitEdit = async (): Promise<void> => {
    if (!editing) return;
    const values = await editForm.validateFields();
    setSaving(true);
    try {
      await configApi.update(editing.id, {
        configValue: values.configValue,
        remark: values.remark?.trim() || undefined,
      });
      message.success('配置已更新，变更已写入历史');
      setEditing(null);
      refresh();
      void queryClient.invalidateQueries({ queryKey: ['admin-config-history'] });
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const rollback = async (history: SysConfigHistory): Promise<void> => {
    try {
      await configApi.rollback(history.id);
      message.success('已回滚到该历史值');
      refresh();
      void queryClient.invalidateQueries({ queryKey: ['admin-config-history'] });
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<SysConfig> = [
    { title: '配置键', dataIndex: 'configKey', width: 240 },
    {
      title: '配置值',
      dataIndex: 'configValue',
      width: 280,
      render: (value?: string) => (
        <Typography.Paragraph
          copyable={value ? { text: value } : false}
          ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
          style={{ marginBottom: 0 }}
        >
          {value || '—'}
        </Typography.Paragraph>
      ),
    },
    {
      title: '类型',
      dataIndex: 'valueType',
      width: 90,
      render: (value: number) => VALUE_TYPE[value] ?? value,
    },
    { title: '分组', dataIndex: 'configGroup', width: 120, render: (value?: string) => value || '—' },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    {
      title: '公开',
      dataIndex: 'isPublic',
      width: 80,
      render: (value?: number) => (value === 1 ? <Tag color="blue">公开</Tag> : '—'),
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 160,
      render: (_, record) => (
        <Space size={4}>
          {hasPerm('admin:sysconfig:edit') && (
            <Button type="link" size="small" onClick={() => openEdit(record)}>
              编辑
            </Button>
          )}
          <Button type="link" size="small" onClick={() => setHistoryOf(record)}>
            历史
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="配置键 / 说明"
          style={{ width: 240 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Input
          allowClear
          placeholder="分组"
          style={{ width: 160 }}
          onChange={(event) => {
            const value = event.target.value.trim();
            setPageNum(1);
            setFilters((current) => ({ ...current, group: value || undefined }));
          }}
        />
        {hasPerm('admin:sysconfig:edit') && (
          <Button type="primary" onClick={openCreate}>
            新增配置
          </Button>
        )}
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="配置不是密钥仓库"
        description="键名包含 password / secret / token / private key / credential 的配置会被拒绝。修改会写入历史，可回滚；目前只有 search.sensitive.words 会立即触发词典重载，其它配置在重启后生效。"
      />

      <Table<SysConfig>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1400 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 项配置`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Modal
        title="新增系统配置"
        open={creating}
        onCancel={() => setCreating(false)}
        onOk={() => void submitCreate()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="configKey"
            label="配置键"
            rules={[
              { required: true, message: '请输入配置键' },
              {
                pattern: /^[a-zA-Z0-9_.-]{2,100}$/,
                message: '只能包含字母、数字、点、下划线与短横线（2-100 位）',
              },
            ]}
          >
            <Input placeholder="如 search.sensitive.words" />
          </Form.Item>
          <Form.Item name="valueType" label="值类型" rules={[{ required: true }]}>
            <Select options={Object.entries(VALUE_TYPE).map(([value, label]) => ({ value: Number(value), label }))} />
          </Form.Item>
          <Form.Item name="configGroup" label="分组" rules={[{ max: 50 }]}>
            <Input placeholder="如 search / notice" />
          </Form.Item>
          <Form.Item
            name="configValue"
            label="配置值"
            rules={[{ required: true, message: '请输入配置值' }]}
            extra="整数/布尔/JSON 会按所选类型校验格式；字符串 10,000 字符以内"
          >
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item name="description" label="说明" rules={[{ max: 200 }]}>
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`修改配置：${editing?.configKey ?? ''}`}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={() => void submitEdit()}
        confirmLoading={saving}
        okText="保存并记录历史"
      >
        <Form form={editForm} layout="vertical">
          <Form.Item label="值类型">
            <Typography.Text>{VALUE_TYPE[editing?.valueType ?? 1] ?? editing?.valueType}</Typography.Text>
          </Form.Item>
          <Form.Item
            name="configValue"
            label="配置值"
            rules={[{ max: 10000, message: '配置值不能超过 10,000 字符' }]}
            extra="字符串型配置可以保存为空（用于清空）；整数/布尔/JSON 型为空会被后端拒绝"
          >
            <Input.TextArea rows={5} />
          </Form.Item>
          <Form.Item name="remark" label="变更备注" rules={[{ max: 200 }]}>
            <Input.TextArea rows={2} maxLength={200} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`变更历史：${historyOf?.configKey ?? ''}`}
        width={640}
        open={historyOf !== null}
        onClose={() => setHistoryOf(null)}
      >
        <Typography.Paragraph type="secondary">
          最多展示最近 100 条变更；回滚本身也会写入一条历史。
        </Typography.Paragraph>
        {historyQuery.isLoading ? (
          <Typography.Text type="secondary">加载中…</Typography.Text>
        ) : (historyQuery.data ?? []).length === 0 ? (
          <Typography.Text type="secondary">暂无变更记录</Typography.Text>
        ) : (
          <Timeline
            items={(historyQuery.data ?? []).map((history) => ({
              key: String(history.id),
              color: history.changeType === 'ROLLBACK' ? 'orange' : history.changeType === 'CREATE' ? 'green' : 'blue',
              children: (
                <div>
                  <Space wrap>
                    <Tag>{CHANGE_TYPE[history.changeType] ?? history.changeType}</Tag>
                    <Typography.Text type="secondary">
                      {history.changedByName || (history.changedBy ? String(history.changedBy) : '系统')}
                    </Typography.Text>
                    <Typography.Text type="secondary">
                      {history.createTime ? dayjs(history.createTime).format('YYYY-MM-DD HH:mm:ss') : ''}
                    </Typography.Text>
                    {hasPerm('admin:sysconfig:edit') && history.changeType !== 'CREATE' && (
                      <Popconfirm
                        title="回滚到该历史值？"
                        description="当前值会被覆盖，并写入一条新的回滚历史。"
                        onConfirm={() => void rollback(history)}
                      >
                        <Button size="small" type="link">
                          回滚到此值
                        </Button>
                      </Popconfirm>
                    )}
                  </Space>
                  {history.remark && <div>备注：{history.remark}</div>}
                  <div className="history-values">
                    <div>
                      <Typography.Text type="secondary">旧值</Typography.Text>
                      <pre className="content-snapshot">{history.oldValue ?? '（空）'}</pre>
                    </div>
                    <div>
                      <Typography.Text type="secondary">新值</Typography.Text>
                      <pre className="content-snapshot">{history.newValue ?? '（空）'}</pre>
                    </div>
                  </div>
                </div>
              ),
            }))}
          />
        )}
      </Drawer>
    </Card>
  );
}
