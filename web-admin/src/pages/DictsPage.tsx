import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CloudSyncOutlined,
  DownloadOutlined,
  ExperimentOutlined,
  ImportOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { dictApi } from '@/api/endpoints';
import type { DictSynonymPayload, DictWordPayload } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type {
  DictAnalyzeResult,
  DictChangeLog,
  DictImportResult,
  DictPinyin,
  DictStatus,
  DictStopword,
  DictSynonymGroup,
  DictWord,
  PageResult,
} from '@/api/types';

const DICT_TYPES: Record<string, string> = {
  MAIN: '主词典',
  NET_SLANG: '网络用语',
  REGION: '地域词',
  STOPWORD: '停用词',
  SYNONYM: '同义词',
  PINYIN: '拼音',
};

const SOURCES: Record<string, { label: string; color: string }> = {
  MANUAL: { label: '人工', color: 'blue' },
  AUTO_ARTIST: { label: '歌手同步', color: 'green' },
  AUTO_SONG: { label: '歌曲同步', color: 'cyan' },
};

const CATEGORIES: Record<string, string> = {
  ARTIST: '歌手',
  GENRE: '曲风',
  LANGUAGE: '语种',
  ERA: '年代',
  SCENE: '场景',
  EMOTION: '情绪',
  OTHER: '其他',
};

const ANALYZERS = [
  { value: 'ik_smart', label: 'ik_smart（粗粒度）' },
  { value: 'ik_max_word', label: 'ik_max_word（细粒度）' },
  { value: 'standard', label: 'standard（兜底）' },
];

const ENABLED_OPTIONS = [
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

function enabledTag(enabled: number): JSX.Element {
  return enabled === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>;
}

function withQuery(path: string): void {
  window.open(path, '_blank', 'noopener');
}

// ==================== 批量导入 ====================

interface ImportModalProps {
  open: boolean;
  title: string;
  hint: string;
  supportsOverwrite?: boolean;
  onClose: () => void;
  onSubmit: (content: string, overwrite: boolean) => Promise<DictImportResult>;
}

function ImportModal({
  open,
  title,
  hint,
  supportsOverwrite,
  onClose,
  onSubmit,
}: ImportModalProps): JSX.Element {
  const [content, setContent] = useState('');
  const [overwrite, setOverwrite] = useState(false);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<DictImportResult | null>(null);

  useEffect(() => {
    if (open) {
      setContent('');
      setOverwrite(false);
      setResult(null);
    }
  }, [open]);

  const submit = async (): Promise<void> => {
    if (!content.trim()) {
      message.warning('请粘贴要导入的内容');
      return;
    }
    setSaving(true);
    try {
      const value = await onSubmit(content, overwrite);
      setResult(value);
      message.success(
        `导入完成：新增 ${value.inserted}，更新 ${value.updated}，跳过 ${value.skipped}，失败 ${value.failed}`,
      );
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={title}
      open={open}
      onCancel={onClose}
      width={720}
      footer={[
        <Button key="close" onClick={onClose}>
          {result ? '完成' : '取消'}
        </Button>,
        <Button key="submit" type="primary" loading={saving} onClick={() => void submit()}>
          开始导入
        </Button>,
      ]}
    >
      <Alert type="info" showIcon style={{ marginBottom: 12 }} message={hint} />
      <Input.TextArea
        rows={10}
        value={content}
        onChange={(event) => setContent(event.target.value)}
        placeholder="每行一条；# 开头为注释"
      />
      {supportsOverwrite && (
        <Space style={{ marginTop: 12 }}>
          <Switch checked={overwrite} onChange={setOverwrite} />
          <Typography.Text>已存在的词条：更新词频/词性（关闭则跳过）</Typography.Text>
        </Space>
      )}
      {result && (
        <>
          <Divider orientation="left">导入报告</Divider>
          <Space size="large" wrap>
            <Typography.Text>共 {result.total} 行</Typography.Text>
            <Typography.Text type="success">新增 {result.inserted}</Typography.Text>
            <Typography.Text type="warning">更新 {result.updated}</Typography.Text>
            <Typography.Text>跳过 {result.skipped}</Typography.Text>
            <Typography.Text type="danger">失败 {result.failed}</Typography.Text>
          </Space>
          {result.errors.length > 0 && (
            <Table
              style={{ marginTop: 12 }}
              size="small"
              rowKey={(row) => `${row.line}-${row.value}`}
              pagination={false}
              dataSource={result.errors}
              columns={[
                { title: '行号', dataIndex: 'line', width: 70 },
                { title: '内容', dataIndex: 'value', ellipsis: true },
                { title: '原因', dataIndex: 'reason' },
              ]}
            />
          )}
        </>
      )}
    </Modal>
  );
}

// ==================== 生效状态 ====================

function StatusTab({ canEdit, canReindex }: { canEdit: boolean; canReindex: boolean }): JSX.Element {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState<string | null>(null);

  const { data, isLoading, refetch } = useQuery<DictStatus>({
    queryKey: ['admin-dict-status'],
    queryFn: () => dictApi.status(),
  });

  const run = async (key: string, action: () => Promise<unknown>, success: string): Promise<void> => {
    setBusy(key);
    try {
      const result = await action();
      const detail =
        result && typeof result === 'object' && 'message' in result
          ? `：${String((result as { message: unknown }).message)}`
          : '';
      message.success(success + detail);
      await refetch();
      void queryClient.invalidateQueries({ queryKey: ['admin-dict-words'] });
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setBusy(null);
    }
  };

  const statsColumns: ColumnsType<NonNullable<DictStatus['dicts']>[number]> = [
    { title: '词典', dataIndex: 'type', render: (value: string) => DICT_TYPES[value] ?? value },
    { title: '条目数', dataIndex: 'count', width: 100 },
    { title: '版本（ETag）', dataIndex: 'version', ellipsis: true, render: (value) => value || '—' },
    {
      title: '最后变更',
      dataIndex: 'lastModified',
      width: 260,
      render: (value) => value || '—',
    },
    {
      title: 'ES 上次拉取',
      dataIndex: 'lastPullTime',
      width: 180,
      render: (value) => value || '尚未拉取',
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        loading={isLoading}
        title="词典生效状态"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refetch()}>
              刷新
            </Button>
            <Tooltip title={canEdit ? undefined : '需要 admin:dict:edit 权限'}>
              <Button
                icon={<CloudSyncOutlined />}
                disabled={!canEdit}
                loading={busy === 'sync'}
                onClick={() =>
                  void run('sync', () => dictApi.syncArtists(), '歌手词条同步完成')
                }
              >
                同步歌手
              </Button>
            </Tooltip>
            <Tooltip title={canReindex ? undefined : '需要 admin:dict:reindex 权限'}>
              <Button
                icon={<ThunderboltOutlined />}
                disabled={!canReindex}
                loading={busy === 'reload'}
                onClick={() => void run('reload', () => dictApi.reload(), '分析器重载已触发')}
              >
                重载分析器
              </Button>
            </Tooltip>
            <Popconfirm
              title="重建 ES 索引？"
              description="将全量写入新索引并原子切换别名，期间旧索引仍可搜索。"
              okText="重建"
              disabled={!canReindex}
              onConfirm={() => void run('reindex', () => dictApi.reindex(), '索引重建完成')}
            >
              <Tooltip title={canReindex ? undefined : '需要 admin:dict:reindex 权限'}>
                <Button
                  danger
                  disabled={!canReindex}
                  loading={busy === 'reindex'}
                  icon={<ExperimentOutlined />}
                >
                  重建索引
                </Button>
              </Tooltip>
            </Popconfirm>
          </Space>
        }
      >
        {data && (
          <>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="结论">
                <Tag color={data.effectiveStatus === '已生效' ? 'green' : 'orange'}>
                  {data.effectiveStatus}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="搜索引擎">{data.searchEngine}</Descriptions.Item>
              <Descriptions.Item label="ES 配置">
                {data.esEnabled ? data.esUrl : '未启用'}
              </Descriptions.Item>
              <Descriptions.Item label="ES 连通">
                {data.esAvailable ? `可用 ${data.esVersion ?? ''}` : '不可达'}
              </Descriptions.Item>
              <Descriptions.Item label="analysis-pinyin">
                {data.pinyinPlugin ? <Tag color="green">已安装</Tag> : <Tag>未安装</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="analysis-stconvert">
                {data.stconvertPlugin ? <Tag color="green">已安装</Tag> : <Tag>未安装</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="ES 上次拉取" span={2}>
                {data.lastPullTime || '尚未拉取'}
              </Descriptions.Item>
            </Descriptions>
            <Alert
              style={{ marginTop: 16 }}
              type={data.esEnabled && !data.esAvailable ? 'warning' : 'info'}
              showIcon
              message={data.notice}
            />
            {data.warnings.length > 0 && (
              <Alert
                style={{ marginTop: 12 }}
                type="warning"
                showIcon
                message="注意事项"
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {data.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                }
              />
            )}
            {data.lastSync && (
              <Descriptions style={{ marginTop: 16 }} size="small" title="上次同步/重载" column={3}>
                <Descriptions.Item label="类型">{data.lastSync.syncType}</Descriptions.Item>
                <Descriptions.Item label="结果">{data.lastSync.status}</Descriptions.Item>
                <Descriptions.Item label="条目">{data.lastSync.itemCount ?? 0}</Descriptions.Item>
                <Descriptions.Item label="耗时">{data.lastSync.costMs ?? 0} ms</Descriptions.Item>
                <Descriptions.Item label="时间" span={2}>
                  {data.lastSync.createTime || '—'}
                </Descriptions.Item>
                <Descriptions.Item label="说明" span={3}>
                  {data.lastSync.message || '—'}
                </Descriptions.Item>
              </Descriptions>
            )}
          </>
        )}
      </Card>

      <Card title="各类词典快照">
        <Table
          rowKey="type"
          size="small"
          pagination={false}
          dataSource={data?.dicts ?? []}
          columns={statsColumns}
        />
      </Card>
    </Space>
  );
}

// ==================== 主词典 ====================

interface WordFormValues {
  word: string;
  frequency: number;
  pos: string;
  dictType: string;
  enabled: boolean;
}

function WordsTab({ canEdit }: { canEdit: boolean }): JSX.Element {
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState<string | undefined>();
  const [dictType, setDictType] = useState<string | undefined>();
  const [source, setSource] = useState<string | undefined>();
  const [enabled, setEnabled] = useState<number | undefined>();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<DictWord | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<WordFormValues>();

  const query = { pageNum, pageSize, keyword, dictType, source, enabled };
  const { data, isLoading } = useQuery<PageResult<DictWord>>({
    queryKey: ['admin-dict-words', query],
    queryFn: () => dictApi.words.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-dict-words'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ frequency: 100, pos: 'n', dictType: 'MAIN', enabled: true });
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (row: DictWord): void => {
    form.resetFields();
    form.setFieldsValue({
      word: row.word,
      frequency: row.frequency,
      pos: row.pos,
      dictType: row.dictType,
      enabled: row.enabled === 1,
    });
    setEditing(row);
    setFormOpen(true);
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    const payload: DictWordPayload = {
      word: values.word.trim(),
      frequency: values.frequency,
      pos: values.pos,
      dictType: values.dictType,
      enabled: values.enabled ? 1 : 0,
    };
    setSaving(true);
    try {
      if (editing) {
        await dictApi.words.update(editing.id, payload);
        message.success('词条已更新');
      } else {
        await dictApi.words.create(payload);
        message.success('词条已新增');
      }
      setFormOpen(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row: DictWord): Promise<void> => {
    try {
      await dictApi.words.remove(row.id);
      message.success('词条已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<DictWord> = [
    { title: '词条', dataIndex: 'word', width: 180 },
    { title: '词频', dataIndex: 'frequency', width: 90 },
    { title: '词性', dataIndex: 'pos', width: 80 },
    {
      title: '词库',
      dataIndex: 'dictType',
      width: 110,
      render: (value: string) => DICT_TYPES[value] ?? value,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 110,
      render: (value: string) => {
        const sourceMeta = SOURCES[value] ?? { label: value, color: 'default' };
        return <Tag color={sourceMeta.color}>{sourceMeta.label}</Tag>;
      },
    },
    { title: '状态', dataIndex: 'enabled', width: 80, render: enabledTag },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      width: 180,
      render: (value) => value || '—',
    },
    {
      title: '操作',
      width: 150,
      render: (_, row) => {
        const auto = row.source !== 'MANUAL';
        return (
          <Space>
            <Tooltip title={auto ? '自动同步词条不可手工修改' : undefined}>
              <Button
                size="small"
                disabled={!canEdit || auto}
                onClick={() => openEdit(row)}
              >
                编辑
              </Button>
            </Tooltip>
            <Popconfirm
              title="删除该词条？"
              disabled={!canEdit || auto}
              onConfirm={() => void remove(row)}
            >
              <Tooltip title={auto ? '自动同步词条不可手工删除' : undefined}>
                <Button size="small" danger disabled={!canEdit || auto}>
                  删除
                </Button>
              </Tooltip>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索词条"
            style={{ width: 220 }}
            onSearch={(value) => {
              setKeyword(value || undefined);
              setPageNum(1);
            }}
          />
          <Select
            allowClear
            placeholder="词库"
            style={{ width: 140 }}
            value={dictType}
            onChange={(value) => {
              setDictType(value);
              setPageNum(1);
            }}
            options={Object.entries(DICT_TYPES)
              .filter(([key]) => ['MAIN', 'NET_SLANG', 'REGION'].includes(key))
              .map(([value, label]) => ({ value, label }))}
          />
          <Select
            allowClear
            placeholder="来源"
            style={{ width: 140 }}
            value={source}
            onChange={(value) => {
              setSource(value);
              setPageNum(1);
            }}
            options={Object.entries(SOURCES).map(([value, meta]) => ({
              value,
              label: meta.label,
            }))}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={enabled}
            onChange={(value) => {
              setEnabled(value);
              setPageNum(1);
            }}
            options={ENABLED_OPTIONS}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!canEdit}
            onClick={openCreate}
          >
            新增词条
          </Button>
          <Button
            icon={<ImportOutlined />}
            disabled={!canEdit}
            onClick={() => setImportOpen(true)}
          >
            批量导入
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() =>
              withQuery(dictApi.words.exportUrl({ dictType, source, enabled }))
            }
          >
            导出
          </Button>
        </Space>
      </Card>

      <Card>
        <Table
          rowKey={(row) => String(row.id)}
          loading={isLoading}
          dataSource={data?.list ?? []}
          columns={columns}
          scroll={{ x: 1000 }}
          pagination={{
            current: pageNum,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
            },
          }}
        />
      </Card>

      <Modal
        title={editing ? `编辑词条：${editing.word}` : '新增主词典词条'}
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="word"
            label="词条"
            rules={[
              { required: true, message: '请输入词条' },
              { min: 2, max: 15, message: '词条长度需为 2~15 个字符' },
            ]}
          >
            <Input placeholder="如：周杰伦" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="frequency" label="词频">
                <InputNumber min={1} max={100000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="pos" label="词性">
                <Input maxLength={8} placeholder="n" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="dictType" label="词库">
                <Select
                  options={[
                    { value: 'MAIN', label: '主词典' },
                    { value: 'NET_SLANG', label: '网络用语' },
                    { value: 'REGION', label: '地域词' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <ImportModal
        open={importOpen}
        title="批量导入主词典"
        hint="每行格式：词条 [词频] [词性]，用空格或 Tab 分隔。词条 2~15 字；# 开头为注释。"
        supportsOverwrite
        onClose={() => setImportOpen(false)}
        onSubmit={async (content, overwrite) => {
          const result = await dictApi.words.importWords({
            content,
            dictType: dictType || 'MAIN',
            overwrite,
          });
          refresh();
          return result;
        }}
      />
    </Space>
  );
}

// ==================== 停用词 ====================

function StopwordsTab({ canEdit }: { canEdit: boolean }): JSX.Element {
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState<string | undefined>();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<DictStopword | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ word: string; enabled: boolean }>();

  const query = { pageNum, pageSize, keyword };
  const { data, isLoading } = useQuery<PageResult<DictStopword>>({
    queryKey: ['admin-dict-stopwords', query],
    queryFn: () => dictApi.stopwords.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-dict-stopwords'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ enabled: true });
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (row: DictStopword): void => {
    form.resetFields();
    form.setFieldsValue({ word: row.word, enabled: row.enabled === 1 });
    setEditing(row);
    setFormOpen(true);
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const payload = { word: values.word.trim(), enabled: values.enabled ? 1 : 0 };
      if (editing) {
        await dictApi.stopwords.update(editing.id, payload);
        message.success('停用词已更新');
      } else {
        await dictApi.stopwords.create(payload);
        message.success('停用词已新增');
      }
      setFormOpen(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row: DictStopword): Promise<void> => {
    try {
      await dictApi.stopwords.remove(row.id);
      message.success('停用词已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="停用词只用于查询分析，且只裁掉首尾；1980~2029 的年份不能作为停用词（BR-20-021）。"
      />
      <Card>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索停用词"
            style={{ width: 220 }}
            onSearch={(value) => {
              setKeyword(value || undefined);
              setPageNum(1);
            }}
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={openCreate}>
            新增停用词
          </Button>
          <Button
            icon={<ImportOutlined />}
            disabled={!canEdit}
            onClick={() => setImportOpen(true)}
          >
            批量导入
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() => withQuery(dictApi.stopwords.exportUrl())}
          >
            导出
          </Button>
        </Space>
      </Card>
      <Card>
        <Table
          rowKey={(row) => String(row.id)}
          loading={isLoading}
          dataSource={data?.list ?? []}
          columns={[
            { title: '停用词', dataIndex: 'word' },
            { title: '状态', dataIndex: 'enabled', width: 100, render: enabledTag },
            {
              title: '更新时间',
              dataIndex: 'updateTime',
              width: 200,
              render: (value) => value || '—',
            },
            {
              title: '操作',
              width: 160,
              render: (_: unknown, row: DictStopword) => (
                <Space>
                  <Button size="small" disabled={!canEdit} onClick={() => openEdit(row)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="删除该停用词？"
                    disabled={!canEdit}
                    onConfirm={() => void remove(row)}
                  >
                    <Button size="small" danger disabled={!canEdit}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
          pagination={{
            current: pageNum,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
            },
          }}
        />
      </Card>

      <Modal
        title={editing ? '编辑停用词' : '新增停用词'}
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="word"
            label="停用词"
            rules={[{ required: true, message: '请输入停用词' }]}
          >
            <Input placeholder="如：我想听" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <ImportModal
        open={importOpen}
        title="批量导入停用词"
        hint="每行一个停用词；# 开头为注释。已存在的词会自动跳过。"
        onClose={() => setImportOpen(false)}
        onSubmit={async (content) => {
          const result = await dictApi.stopwords.importWords({ content });
          refresh();
          return result;
        }}
      />
    </Space>
  );
}

// ==================== 同义词 ====================

interface SynonymFormValues {
  words: string[];
  primaryWord?: string;
  synonymType: string;
  category: string;
  enabled: boolean;
}

function SynonymsTab({ canEdit }: { canEdit: boolean }): JSX.Element {
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState<string | undefined>();
  const [category, setCategory] = useState<string | undefined>();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<DictSynonymGroup | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<SynonymFormValues>();
  const watchedWords = Form.useWatch('words', form) ?? [];

  const query = { pageNum, pageSize, keyword, category };
  const { data, isLoading } = useQuery<PageResult<DictSynonymGroup>>({
    queryKey: ['admin-dict-synonyms', query],
    queryFn: () => dictApi.synonyms.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-dict-synonyms'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({
      words: [],
      synonymType: 'EQUIVALENT',
      category: 'OTHER',
      enabled: true,
    });
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (row: DictSynonymGroup): void => {
    form.resetFields();
    form.setFieldsValue({
      words: row.words.map((word) => word.word),
      primaryWord: row.primaryWord,
      synonymType: row.synonymType,
      category: row.category,
      enabled: row.enabled === 1,
    });
    setEditing(row);
    setFormOpen(true);
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    if (values.words.length < 2) {
      message.warning('同义词组至少需要 2 个词');
      return;
    }
    const payload: DictSynonymPayload = {
      words: values.words,
      primaryWord: values.primaryWord,
      synonymType: values.synonymType,
      category: values.category,
      enabled: values.enabled ? 1 : 0,
    };
    setSaving(true);
    try {
      if (editing) {
        await dictApi.synonyms.update(editing.groupNo, payload);
        message.success('同义词组已更新');
      } else {
        await dictApi.synonyms.create(payload);
        message.success('同义词组已新增');
      }
      setFormOpen(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row: DictSynonymGroup): Promise<void> => {
    try {
      await dictApi.synonyms.remove(row.groupNo);
      message.success('同义词组已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const columns: ColumnsType<DictSynonymGroup> = [
    { title: '组号', dataIndex: 'groupNo', width: 80 },
    {
      title: '词条',
      dataIndex: 'words',
      render: (_, row) => (
        <Space size={[4, 4]} wrap>
          {row.words.map((word) => (
            <Tag key={String(word.id)} color={word.word === row.primaryWord ? 'blue' : undefined}>
              {word.word}
              {word.word === row.primaryWord ? '（主）' : ''}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'synonymType',
      width: 110,
      render: (value: string) => (value === 'ONE_WAY' ? '单向' : '等价'),
    },
    {
      title: '分类',
      dataIndex: 'category',
      width: 90,
      render: (value: string) => CATEGORIES[value] ?? value,
    },
    { title: '状态', dataIndex: 'enabled', width: 80, render: enabledTag },
    {
      title: '操作',
      width: 150,
      render: (_, row) => (
        <Space>
          <Button size="small" disabled={!canEdit} onClick={() => openEdit(row)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该同义词组？"
            disabled={!canEdit}
            onConfirm={() => void remove(row)}
          >
            <Button size="small" danger disabled={!canEdit}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="等价组内任意词都会改写成主词；单向组只把主词改写为目标词。同一个词不能属于多个组，也不能同时是停用词。"
      />
      <Card>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索组内词条"
            style={{ width: 220 }}
            onSearch={(value) => {
              setKeyword(value || undefined);
              setPageNum(1);
            }}
          />
          <Select
            allowClear
            placeholder="分类"
            style={{ width: 130 }}
            value={category}
            onChange={(value) => {
              setCategory(value);
              setPageNum(1);
            }}
            options={Object.entries(CATEGORIES).map(([value, label]) => ({ value, label }))}
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={openCreate}>
            新增同义词组
          </Button>
          <Button
            icon={<ImportOutlined />}
            disabled={!canEdit}
            onClick={() => setImportOpen(true)}
          >
            批量导入
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() => withQuery(dictApi.synonyms.exportUrl())}
          >
            导出
          </Button>
        </Space>
      </Card>
      <Card>
        <Table
          rowKey={(row) => String(row.groupNo)}
          loading={isLoading}
          dataSource={data?.list ?? []}
          columns={columns}
          pagination={{
            current: pageNum,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 组`,
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
            },
          }}
        />
      </Card>

      <Modal
        title={editing ? `编辑同义词组 #${editing.groupNo}` : '新增同义词组'}
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
        width={620}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="words"
            label="组内词条（2~20 个）"
            rules={[{ required: true, message: '请输入至少 2 个词' }]}
            extra="输入后按回车添加，也可用逗号分隔粘贴"
          >
            <Select mode="tags" tokenSeparators={[',', '，']} placeholder="如：周杰伦、周董、杰伦" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="primaryWord" label="主词">
                <Select
                  allowClear
                  placeholder="默认第一个"
                  options={(watchedWords as string[]).map((word) => ({ value: word, label: word }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="synonymType" label="类型">
                <Select
                  options={[
                    { value: 'EQUIVALENT', label: '等价（互相命中）' },
                    { value: 'ONE_WAY', label: '单向（主词→其他）' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="category" label="分类">
                <Select
                  options={Object.entries(CATEGORIES).map(([value, label]) => ({
                    value,
                    label,
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <ImportModal
        open={importOpen}
        title="批量导入同义词组"
        hint="每行一组。等价组写 A,B,C；单向组写 A => B,C。每组 2~20 个词。"
        onClose={() => setImportOpen(false)}
        onSubmit={async (content) => {
          const result = await dictApi.synonyms.importWords({ content });
          refresh();
          return result;
        }}
      />
    </Space>
  );
}

// ==================== 拼音词典 ====================

function PinyinTab({ canEdit }: { canEdit: boolean }): JSX.Element {
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState<string | undefined>();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<DictPinyin | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ word: string; pinyin: string; enabled: boolean }>();

  const query = { pageNum, pageSize, keyword };
  const { data, isLoading } = useQuery<PageResult<DictPinyin>>({
    queryKey: ['admin-dict-pinyin', query],
    queryFn: () => dictApi.pinyin.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-dict-pinyin'] });
  };

  const openCreate = (): void => {
    form.resetFields();
    form.setFieldsValue({ enabled: true });
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (row: DictPinyin): void => {
    form.resetFields();
    form.setFieldsValue({ word: row.word, pinyin: row.pinyin, enabled: row.enabled === 1 });
    setEditing(row);
    setFormOpen(true);
  };

  const submit = async (): Promise<void> => {
    const values = await form.validateFields();
    const payload = {
      word: values.word.trim(),
      pinyin: values.pinyin.trim().toLowerCase(),
      enabled: values.enabled ? 1 : 0,
    };
    setSaving(true);
    try {
      if (editing) {
        await dictApi.pinyin.update(editing.id, payload);
        message.success('拼音词条已更新');
      } else {
        await dictApi.pinyin.create(payload);
        message.success('拼音词条已新增');
      }
      setFormOpen(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (row: DictPinyin): Promise<void> => {
    try {
      await dictApi.pinyin.remove(row.id);
      message.success('拼音词条已删除');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="多音字词典优先级高于 pinyin4j 的默认读音。保存后会立即重算受影响的歌手/歌曲/专辑拼音列，并让查询别名即时生效。"
      />
      <Card>
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索词"
            style={{ width: 220 }}
            onSearch={(value) => {
              setKeyword(value || undefined);
              setPageNum(1);
            }}
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!canEdit} onClick={openCreate}>
            新增拼音词条
          </Button>
          <Button
            icon={<ImportOutlined />}
            disabled={!canEdit}
            onClick={() => setImportOpen(true)}
          >
            批量导入
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() => withQuery(dictApi.pinyin.exportUrl())}
          >
            导出
          </Button>
        </Space>
      </Card>
      <Card>
        <Table
          rowKey={(row) => String(row.id)}
          loading={isLoading}
          dataSource={data?.list ?? []}
          columns={[
            { title: '词', dataIndex: 'word', width: 180 },
            { title: '拼音', dataIndex: 'pinyin', width: 180 },
            { title: '状态', dataIndex: 'enabled', width: 100, render: enabledTag },
            {
              title: '更新时间',
              dataIndex: 'updateTime',
              width: 200,
              render: (value) => value || '—',
            },
            {
              title: '操作',
              width: 160,
              render: (_: unknown, row: DictPinyin) => (
                <Space>
                  <Button size="small" disabled={!canEdit} onClick={() => openEdit(row)}>
                    编辑
                  </Button>
                  <Popconfirm
                    title="删除该拼音词条？"
                    disabled={!canEdit}
                    onConfirm={() => void remove(row)}
                  >
                    <Button size="small" danger disabled={!canEdit}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
          pagination={{
            current: pageNum,
            pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (page, size) => {
              setPageNum(page);
              setPageSize(size);
            },
          }}
        />
      </Card>

      <Modal
        title={editing ? '编辑拼音词条' : '新增拼音词条'}
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => void submit()}
        confirmLoading={saving}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="word"
            label="词"
            rules={[{ required: true, message: '请输入词' }]}
          >
            <Input placeholder="如：重庆" />
          </Form.Item>
          <Form.Item
            name="pinyin"
            label="拼音（无声调小写）"
            rules={[
              { required: true, message: '请输入拼音' },
              { pattern: /^[a-z]+$/, message: '只能包含小写字母 a~z' },
            ]}
          >
            <Input placeholder="如：chongqing" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <ImportModal
        open={importOpen}
        title="批量导入拼音词典"
        hint="每行格式：词 [空格/Tab] 拼音，例如「重庆 chongqing」。拼音只能是小写字母。"
        onClose={() => setImportOpen(false)}
        onSubmit={async (content) => {
          const result = await dictApi.pinyin.importWords({ content });
          refresh();
          return result;
        }}
      />
    </Space>
  );
}

// ==================== 分词测试 ====================

function TokenList({ result }: { result: DictAnalyzeResult }): JSX.Element {
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap>
        <Tag color={result.source === 'ES' ? 'blue' : 'orange'}>{result.source}</Tag>
        {result.notice && <Typography.Text type="secondary">{result.notice}</Typography.Text>}
      </Space>
      <Space size={[6, 6]} wrap>
        {result.tokens.length === 0 && <Typography.Text type="secondary">无 token</Typography.Text>}
        {result.tokens.map((token, index) => (
          <Tooltip key={`${token.startOffset}-${index}`} title={`${token.type} @${token.startOffset}`}>
            <Tag color={token.type === 'CN_WORD' ? 'green' : undefined}>
              {token.token}
              <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 4 }}>
                {token.startOffset}-{token.endOffset}
              </Typography.Text>
            </Tag>
          </Tooltip>
        ))}
      </Space>
    </Space>
  );
}

function AnalyzeTab(): JSX.Element {
  const [text, setText] = useState('周杰伦的稻香');
  const [analyzer, setAnalyzer] = useState('ik_smart');
  const [previewWords, setPreviewWords] = useState('');
  const [base, setBase] = useState<DictAnalyzeResult | null>(null);
  const [preview, setPreview] = useState<DictAnalyzeResult | null>(null);
  const [loading, setLoading] = useState(false);

  const run = async (): Promise<void> => {
    if (!text.trim()) {
      message.warning('请输入要分词的文本');
      return;
    }
    setLoading(true);
    try {
      const words = previewWords
        .split(/[,，]/)
        .map((word) => word.trim())
        .filter(Boolean);
      const [current, after] = await Promise.all([
        dictApi.analyze({ text: text.trim(), analyzer }),
        words.length > 0
          ? dictApi.analyze({ text: text.trim(), analyzer, previewWords: words.join(',') })
          : Promise.resolve(null),
      ]);
      setBase(current);
      setPreview(after);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card title="分词测试（保存前预览）">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Input.TextArea
          rows={3}
          value={text}
          maxLength={500}
          showCount
          onChange={(event) => setText(event.target.value)}
          placeholder="输入歌名、歌手名或一句自然语言"
        />
        <Space wrap>
          <Select
            style={{ width: 220 }}
            value={analyzer}
            onChange={setAnalyzer}
            options={ANALYZERS}
          />
          <Input
            style={{ width: 320 }}
            value={previewWords}
            onChange={(event) => setPreviewWords(event.target.value)}
            placeholder="预览词条，逗号分隔（如：稻香,七里香）"
          />
          <Button type="primary" loading={loading} icon={<ExperimentOutlined />} onClick={() => void run()}>
            开始分词
          </Button>
        </Space>
        {base && (
          <>
            <Divider orientation="left">当前词典</Divider>
            <TokenList result={base} />
          </>
        )}
        {preview && (
          <>
            <Divider orientation="left">加入预览词后</Divider>
            <TokenList result={preview} />
          </>
        )}
        {!base && (
          <Alert
            type="info"
            showIcon
            message="ES 可用时使用真实 IK 分词；本机没有 ES 时退回本地正向最大匹配，source 字段会如实标记。"
          />
        )}
      </Space>
    </Card>
  );
}

// ==================== 变更历史 ====================

function ChangeLogTab(): JSX.Element {
  const [dictType, setDictType] = useState<string | undefined>();
  const [changeType, setChangeType] = useState<string | undefined>();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const query = { pageNum, pageSize, dictType, changeType };
  const { data, isLoading } = useQuery<PageResult<DictChangeLog>>({
    queryKey: ['admin-dict-change-log', query],
    queryFn: () => dictApi.changeLog(query),
  });

  const columns: ColumnsType<DictChangeLog> = [
    { title: '时间', dataIndex: 'createTime', width: 180, render: (value) => value || '—' },
    {
      title: '词典',
      dataIndex: 'dictType',
      width: 100,
      render: (value: string) => DICT_TYPES[value] ?? value,
    },
    { title: '操作', dataIndex: 'changeType', width: 100 },
    { title: '目标 ID', dataIndex: 'targetId', width: 180, render: (value) => value || '—' },
    { title: '操作人', dataIndex: 'operatorName', width: 130, render: (value) => value || 'SYSTEM' },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (value: string) =>
        value === 'SUCCESS' ? <Tag color="green">成功</Tag> : <Tag color="red">{value}</Tag>,
    },
    { title: '说明', dataIndex: 'remark', ellipsis: true, render: (value) => value || '—' },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="词典"
          style={{ width: 130 }}
          value={dictType}
          onChange={(value) => {
            setDictType(value);
            setPageNum(1);
          }}
          options={['MAIN', 'STOPWORD', 'SYNONYM', 'PINYIN'].map((value) => ({
            value,
            label: DICT_TYPES[value],
          }))}
        />
        <Select
          allowClear
          placeholder="操作"
          style={{ width: 140 }}
          value={changeType}
          onChange={(value) => {
            setChangeType(value);
            setPageNum(1);
          }}
          options={['CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'RELOAD', 'REINDEX'].map((value) => ({
            value,
            label: value,
          }))}
        />
      </Space>
      <Table
        rowKey={(row) => String(row.id)}
        loading={isLoading}
        dataSource={data?.list ?? []}
        columns={columns}
        expandable={{
          expandedRowRender: (row) => (
            <Row gutter={16}>
              <Col span={12}>
                <Typography.Text type="secondary">变更前</Typography.Text>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{row.beforeValue || '—'}</pre>
              </Col>
              <Col span={12}>
                <Typography.Text type="secondary">变更后</Typography.Text>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{row.afterValue || '—'}</pre>
              </Col>
            </Row>
          ),
          rowExpandable: (row) => Boolean(row.beforeValue || row.afterValue),
        }}
        pagination={{
          current: pageNum,
          pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
        }}
      />
    </Card>
  );
}

// ==================== 页面 ====================

export function DictsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const canEdit = hasPerm('admin:dict:edit');
  const canReindex = hasPerm('admin:dict:reindex');

  return (
    <Tabs
      defaultActiveKey="overview"
      items={[
        {
          key: 'overview',
          label: '生效状态',
          children: <StatusTab canEdit={canEdit} canReindex={canReindex} />,
        },
        {
          key: 'words',
          label: '主词典',
          children: <WordsTab canEdit={canEdit} />,
        },
        {
          key: 'stopwords',
          label: '停用词',
          children: <StopwordsTab canEdit={canEdit} />,
        },
        {
          key: 'synonyms',
          label: '同义词',
          children: <SynonymsTab canEdit={canEdit} />,
        },
        {
          key: 'pinyin',
          label: '拼音词典',
          children: <PinyinTab canEdit={canEdit} />,
        },
        {
          key: 'analyze',
          label: '分词测试',
          children: <AnalyzeTab />,
        },
        {
          key: 'change-log',
          label: '变更历史',
          children: <ChangeLogTab />,
        },
      ]}
    />
  );
}
