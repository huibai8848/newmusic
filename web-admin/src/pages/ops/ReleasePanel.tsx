import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
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
import { opsApi, type BackupRecord, type PublishRecord } from '@/api/ops';
import { useAuth } from '@/auth/AuthContext';
import { fmtBytes, fmtFullTime, fmtNumber, statusColor } from './format';

interface PublishValues {
  version: string;
  service: string;
  environment: string;
  publishType: string;
  gitCommit?: string;
  remark?: string;
}

/**
 * 发布与备份。
 *
 * 两者都是「记录 + 状态机」而不是真的执行部署/导出：本机没有流水线与制品库，
 * 也没有独立备份从库。接真实流水线时替换 finish/trigger 的实现即可，页面不变。
 */
export function ReleasePanel(): JSX.Element {
  return (
    <Tabs
      destroyInactiveTabPane
      items={[
        { key: 'publish', label: '发布与回滚', children: <PublishSection /> },
        { key: 'backup', label: '备份与校验', children: <BackupSection /> },
      ]}
    />
  );
}

function PublishSection(): JSX.Element {
  const { hasPerm } = useAuth();
  const canPublish = hasPerm('admin:ops:publish');
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<string | undefined>();
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [finishing, setFinishing] = useState<PublishRecord | null>(null);
  const [rollingBack, setRollingBack] = useState<PublishRecord | null>(null);
  const [saving, setSaving] = useState(false);
  const [createForm] = Form.useForm<PublishValues>();
  const [finishForm] = Form.useForm<{ success: boolean; message?: string }>();
  const [rollbackForm] = Form.useForm<{ targetVersion?: string; reason?: string }>();

  const query = useQuery({
    queryKey: ['ops-publish', status, page],
    queryFn: () => opsApi.publishRecords({ status, page, size: 20 }),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-publish'] });
  };

  const submitCreate = async (): Promise<void> => {
    const values = await createForm.validateFields();
    setSaving(true);
    try {
      await opsApi.createPublish(values);
      message.success('发布记录已创建（状态 IN_PROGRESS）');
      setCreateOpen(false);
      createForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const submitFinish = async (): Promise<void> => {
    if (!finishing) return;
    const values = await finishForm.validateFields();
    setSaving(true);
    try {
      await opsApi.finishPublish(finishing.id, values.success, values.message);
      message.success(values.success ? '发布已标记为成功' : '发布已标记为失败');
      setFinishing(null);
      finishForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const submitRollback = async (): Promise<void> => {
    if (!rollingBack) return;
    const values = await rollbackForm.validateFields();
    setSaving(true);
    try {
      const result = await opsApi.rollbackPublish(rollingBack.id, values);
      message.success(`已回滚到 ${result.targetVersion}，并生成回滚记录 #${result.rollbackRecord.id}`);
      setRollingBack(null);
      rollbackForm.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<PublishRecord> = [
    { title: '版本', dataIndex: 'version', width: 130 },
    { title: '服务', dataIndex: 'service', width: 140 },
    { title: '环境', dataIndex: 'environment', width: 90 },
    { title: '类型', dataIndex: 'publishType', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    { title: '发布人', dataIndex: 'publisher', width: 110, render: (v: string | null) => v || '—' },
    { title: '开始', dataIndex: 'startTime', width: 160, render: (v: string) => fmtFullTime(v) },
    { title: '结束', dataIndex: 'endTime', width: 160, render: (v: string | null) => fmtFullTime(v) },
    { title: '备注', dataIndex: 'remark', ellipsis: true },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_, row) => (
        <Space size={4}>
          {canPublish && row.status === 'IN_PROGRESS' && (
            <>
              <Button type="link" size="small" onClick={() => setFinishing(row)}>
                完成
              </Button>
            </>
          )}
          {canPublish && (row.status === 'SUCCESS' || row.status === 'FAILED') && (
            <Button type="link" size="small" danger onClick={() => setRollingBack(row)}>
              回滚
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="发布状态机：IN_PROGRESS → SUCCESS/FAILED → ROLLED_BACK"
        description="本机没有 K8s/制品库，不执行真实部署；记录用于审计与演练回滚流程。接流水线后由流水线回调完成/回滚。"
      />
      <Card
        size="small"
        title="发布记录"
        extra={
          <Space>
            <Select
              allowClear
              placeholder="状态"
              style={{ width: 150 }}
              value={status}
              onChange={(value) => {
                setStatus(value);
                setPage(1);
              }}
              options={['IN_PROGRESS', 'SUCCESS', 'FAILED', 'ROLLED_BACK'].map((value) => ({
                value,
                label: value,
              }))}
            />
            {canPublish && (
              <Button type="primary" size="small" onClick={() => setCreateOpen(true)}>
                创建发布
              </Button>
            )}
          </Space>
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={query.isLoading}
          dataSource={query.data?.list ?? []}
          columns={columns}
          scroll={{ x: 1300 }}
          pagination={{
            current: page,
            pageSize: 20,
            total: query.data?.total ?? 0,
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </Card>

      <Modal
        open={createOpen}
        title="创建发布记录"
        okText="创建"
        confirmLoading={saving}
        onOk={submitCreate}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Form
          form={createForm}
          layout="vertical"
          preserve={false}
          initialValues={{ service: 'newmusic-api', environment: 'prod', publishType: 'GRAY' }}
        >
          <Form.Item name="version" label="版本号" rules={[{ required: true }]}>
            <Input placeholder="v0.19.0" maxLength={64} />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="service" label="服务">
              <Input style={{ width: 190 }} maxLength={64} />
            </Form.Item>
            <Form.Item name="environment" label="环境">
              <Select
                style={{ width: 130 }}
                options={['prod', 'gray', 'staging'].map((value) => ({ value, label: value }))}
              />
            </Form.Item>
            <Form.Item name="publishType" label="发布方式">
              <Select
                style={{ width: 140 }}
                options={['GRAY', 'FULL', 'CANARY', 'ROLLING', 'BLUE_GREEN', 'AB'].map((value) => ({
                  value,
                  label: value,
                }))}
              />
            </Form.Item>
          </Space>
          <Form.Item name="gitCommit" label="Git commit（可选）">
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={finishing !== null}
        title={`完成发布：${finishing?.version ?? ''}`}
        okText="确认"
        confirmLoading={saving}
        onOk={submitFinish}
        onCancel={() => setFinishing(null)}
        destroyOnClose
      >
        <Form form={finishForm} layout="vertical" preserve={false} initialValues={{ success: true }}>
          <Form.Item name="success" label="结果" valuePropName="checked">
            <Switch checkedChildren="成功" unCheckedChildren="失败" />
          </Form.Item>
          <Form.Item name="message" label="说明">
            <Input.TextArea rows={2} maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={rollingBack !== null}
        title={`回滚发布：${rollingBack?.version ?? ''}`}
        okText="确认回滚"
        okButtonProps={{ danger: true }}
        confirmLoading={saving}
        onOk={submitRollback}
        onCancel={() => setRollingBack(null)}
        destroyOnClose
      >
        <Form form={rollbackForm} layout="vertical" preserve={false}>
          <Form.Item
            name="targetVersion"
            label="目标版本（留空则取最近一次成功发布）"
            initialValue={undefined}
          >
            <Input placeholder="v0.18.0" maxLength={64} />
          </Form.Item>
          <Form.Item name="reason" label="回滚原因">
            <Input.TextArea rows={2} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function BackupSection(): JSX.Element {
  const { hasPerm } = useAuth();
  const canBackup = hasPerm('admin:ops:backup');
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [previewOf, setPreviewOf] = useState<BackupRecord | null>(null);
  const [preview, setPreview] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ backupType: string; message?: string }>();

  const query = useQuery({
    queryKey: ['ops-backups', page],
    queryFn: () => opsApi.backups({ page, size: 20 }),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['ops-backups'] });
  };

  const submitCreate = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const record = await opsApi.createBackup(values);
      message.success(`备份清单已生成：${record.location ?? ''}`);
      setCreateOpen(false);
      form.resetFields();
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const verify = async (row: BackupRecord): Promise<void> => {
    try {
      const result = await opsApi.verifyBackup(row.id);
      if (result.verifyStatus === 'VERIFIED') {
        message.success('校验通过：SHA-256 与清单一致');
      } else {
        message.warning(`校验结果：${result.verifyStatus}`);
      }
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const openPreview = async (row: BackupRecord): Promise<void> => {
    setPreviewOf(row);
    setPreview([]);
    try {
      setPreview(await opsApi.previewBackup(row.id, 50));
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<BackupRecord> = [
    { title: '类型', dataIndex: 'backupType', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>,
    },
    {
      title: '校验',
      dataIndex: 'verifyStatus',
      width: 110,
      render: (value: string) => <Tag color={statusColor(value)}>{value ?? '—'}</Tag>,
    },
    { title: '表数', dataIndex: 'tableCount', width: 80, render: (v: number | null) => fmtNumber(v ?? 0) },
    { title: '行数', dataIndex: 'rowCount', width: 110, render: (v: number | null) => fmtNumber(v ?? 0) },
    { title: '大小', dataIndex: 'sizeBytes', width: 100, render: (v: number | null) => fmtBytes(v ?? 0) },
    { title: '文件', dataIndex: 'location', ellipsis: true },
    { title: '开始', dataIndex: 'startedAt', width: 160, render: (v: string) => fmtFullTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, row) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => void openPreview(row)}>
            预览
          </Button>
          {canBackup && (
            <Button type="link" size="small" onClick={() => void verify(row)}>
              校验
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="逻辑备份清单 + SHA-256（不执行 mysqldump）"
        description="清单落在 newmusic.ops.backup-dir（默认 .tmp/backups），包含每张表的行数；从库/对象存储接入由部署层负责。"
      />
      <Card
        size="small"
        title="备份记录"
        extra={
          canBackup && (
            <Button type="primary" size="small" onClick={() => setCreateOpen(true)}>
              生成备份清单
            </Button>
          )
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={query.isLoading}
          dataSource={query.data?.list ?? []}
          columns={columns}
          scroll={{ x: 1200 }}
          pagination={{
            current: page,
            pageSize: 20,
            total: query.data?.total ?? 0,
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </Card>

      <Modal
        open={createOpen}
        title="生成备份清单"
        okText="开始"
        confirmLoading={saving}
        onOk={submitCreate}
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false} initialValues={{ backupType: 'FULL' }}>
          <Form.Item name="backupType" label="备份类型">
            <Select options={['FULL', 'INCREMENTAL'].map((value) => ({ value, label: value }))} />
          </Form.Item>
          <Form.Item name="message" label="备注">
            <Input.TextArea rows={2} maxLength={300} showCount />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={previewOf !== null}
        title={`备份清单预览 #${previewOf?.id ?? ''}`}
        width={560}
        onClose={() => setPreviewOf(null)}
      >
        <Descriptions size="small" column={1} style={{ marginBottom: 16 }}>
          <Descriptions.Item label="文件">{previewOf?.location}</Descriptions.Item>
          <Descriptions.Item label="SHA-256">{previewOf?.checksum}</Descriptions.Item>
        </Descriptions>
        <Typography.Paragraph type="secondary">前 {preview.length} 行（table&lt;TAB&gt;rows）</Typography.Paragraph>
        <pre style={{ fontSize: 12, lineHeight: 1.6 }}>{preview.join('\n') || '加载中…'}</pre>
      </Drawer>
    </Space>
  );
}
