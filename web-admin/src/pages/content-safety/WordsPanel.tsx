import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { safetyApi, type RulePayload, type WordPayload } from '@/api/contentSafety';
import { errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import type {
  AuditRule,
  ImportPreview,
  SensitiveWord,
  WhiteWord,
  WordTestResult,
  WordVersion,
} from '@/api/types';

const LEVEL: Record<number, { text: string; color: string }> = {
  1: { text: 'L1 疑似', color: 'gold' },
  2: { text: 'L2 高危', color: 'orange' },
  3: { text: 'L3 禁止', color: 'red' },
};

const CATEGORY_OPTIONS = [
  '涉政',
  '涉黄',
  '暴恐',
  '广告',
  '辱骂',
  '竞品',
  'CUSTOM',
  'IMPORT',
].map((value) => ({ value, label: value }));

const SOURCE_OPTIONS = ['OFFICIAL', 'OPERATION', 'REPORT', 'IMPORT'].map((value) => ({
  value,
  label: value,
}));

const DECISION: Record<string, { text: string; color: string }> = {
  PASS: { text: '通过', color: 'green' },
  REVIEW: { text: '转人工', color: 'orange' },
  REJECT: { text: '拒绝', color: 'red' },
};

function maskWord(word: string, level: number, superAdmin: boolean): string {
  if (level < 3 || superAdmin) return word;
  if (word.length <= 1) return '*';
  return `${word.slice(0, 1)}${'*'.repeat(Math.max(1, word.length - 1))}`;
}

interface WordFormValues {
  word: string;
  level: number;
  category?: string;
  source?: string;
  status: number;
  remark?: string;
}

function WordListTab(): JSX.Element {
  const { hasPerm, admin } = useAuth();
  const superAdmin = (admin?.roles ?? []).includes('SUPER_ADMIN');
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<{
    level?: number;
    category?: string;
    source?: string;
    status?: number;
    keyword?: string;
  }>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [editing, setEditing] = useState<SensitiveWord | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form] = Form.useForm<WordFormValues>();
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState('');
  const [importLevel, setImportLevel] = useState(1);
  const [importCategory, setImportCategory] = useState('IMPORT');
  const [preview, setPreview] = useState<ImportPreview | null>(null);

  const query = useQuery({
    queryKey: ['safety-words', filters, pageNum, pageSize],
    queryFn: () => safetyApi.words.page({ ...filters, pageNum, pageSize }),
  });

  const save = useMutation({
    mutationFn: (values: WordFormValues) =>
      editing ? safetyApi.words.update(editing.id, values as WordPayload)
        : safetyApi.words.create(values as WordPayload),
    onSuccess: () => {
      message.success(editing ? '词条已更新，已热加载' : '词条已新增，已热加载');
      setFormOpen(false);
      setEditing(null);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: ['safety-words'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const remove = useMutation({
    mutationFn: (id: string | number) => safetyApi.words.remove(id),
    onSuccess: () => {
      message.success('词条已删除');
      void queryClient.invalidateQueries({ queryKey: ['safety-words'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const previewImport = useMutation({
    mutationFn: () =>
      safetyApi.words.previewImport({ content: importText, level: importLevel, category: importCategory }),
    onSuccess: setPreview,
    onError: (error) => message.error(errorMessage(error)),
  });

  const doImport = useMutation({
    mutationFn: () =>
      safetyApi.words.importWords({ content: importText, level: importLevel, category: importCategory }),
    onSuccess: (data) => {
      message.success(`导入完成：新增 ${data.inserted} 条，跳过 ${data.skipped} 条`);
      setImportOpen(false);
      setImportText('');
      setPreview(null);
      void queryClient.invalidateQueries({ queryKey: ['safety-words'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const openCreate = (): void => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ level: 1, status: 1, category: 'CUSTOM', source: 'OPERATION' });
    setFormOpen(true);
  };

  const openEdit = (row: SensitiveWord): void => {
    setEditing(row);
    form.setFieldsValue({
      word: row.word,
      level: row.level,
      category: row.category,
      source: row.source,
      status: row.status,
      remark: row.remark,
    });
    setFormOpen(true);
  };

  const exportWords = (): void => {
    const levelText = filters.level ? String(filters.level) : undefined;
    let confirm: string | undefined;
    if (!filters.level || filters.level === 3) {
      if (!superAdmin) {
        message.warning('导出含 L3 的词库需要超级管理员权限');
        return;
      }
      const input = window.prompt('导出含 L3 词条需要二次确认，请输入 EXPORT');
      if (input !== 'EXPORT') {
        message.warning('未输入 EXPORT，已取消导出');
        return;
      }
      confirm = 'EXPORT';
    }
    window.open(safetyApi.words.exportUrl({ level: levelText ? Number(levelText) : undefined, confirm }), '_blank');
  };

  const columns: ColumnsType<SensitiveWord> = [
    {
      title: '词条',
      dataIndex: 'word',
      render: (value: string, row) => (
        <Space>
          <Typography.Text code>{maskWord(value, row.level, superAdmin)}</Typography.Text>
          {row.rawWord && row.rawWord !== value ? (
            <Typography.Text type="secondary">原始：{maskWord(row.rawWord, row.level, superAdmin)}</Typography.Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '级别',
      dataIndex: 'level',
      width: 100,
      render: (value: number) => <Tag color={LEVEL[value]?.color}>{LEVEL[value]?.text ?? value}</Tag>,
    },
    { title: '分类', dataIndex: 'category', width: 110 },
    { title: '来源', dataIndex: 'source', width: 100 },
    {
      title: '命中',
      dataIndex: 'hitCount',
      width: 90,
      render: (value?: number) => value ?? 0,
    },
    {
      title: '最近命中',
      dataIndex: 'lastHitTime',
      width: 170,
      render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) => (value === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_, row) => (
        <Space>
          <Button
            size="small"
            disabled={!hasPerm('admin:audit:word:edit')}
            onClick={() => openEdit(row)}
          >
            编辑
          </Button>
          <Popconfirm
            title="删除词条？"
            description="删除后立即热加载；历史版本仍可回滚。"
            disabled={!hasPerm('admin:audit:word:edit')}
            onConfirm={() => remove.mutate(row.id)}
          >
            <Button size="small" danger disabled={!hasPerm('admin:audit:word:edit')}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        size="small"
        title="词库筛选"
        extra={
          <Space>
            <Button onClick={exportWords} disabled={!hasPerm('admin:audit:word:export')}>
              导出 CSV
            </Button>
            <Button onClick={() => setImportOpen(true)} disabled={!hasPerm('admin:audit:word:edit')}>
              批量导入
            </Button>
            <Button type="primary" onClick={openCreate} disabled={!hasPerm('admin:audit:word:edit')}>
              新增词条
            </Button>
          </Space>
        }
      >
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索词条"
            style={{ width: 220 }}
            onSearch={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, keyword: value || undefined }));
            }}
          />
          <Select
            allowClear
            placeholder="级别"
            style={{ width: 120 }}
            options={Object.entries(LEVEL).map(([value, item]) => ({ value: Number(value), label: item.text }))}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, level: value }));
            }}
          />
          <Select
            allowClear
            placeholder="分类"
            style={{ width: 140 }}
            options={CATEGORY_OPTIONS}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, category: value }));
            }}
          />
          <Select
            allowClear
            placeholder="来源"
            style={{ width: 140 }}
            options={SOURCE_OPTIONS}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, source: value }));
            }}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            options={[
              { value: 1, label: '启用' },
              { value: 0, label: '停用' },
            ]}
            onChange={(value) => {
              setPageNum(1);
              setFilters((prev) => ({ ...prev, status: value }));
            }}
          />
        </Space>
      </Card>

      {!superAdmin ? (
        <Alert
          type="info"
          showIcon
          message="L3 词条已脱敏展示"
          description="非超级管理员视图不返回 L3 明文；导出含 L3 的词库需要超级管理员二次确认。"
        />
      ) : null}

      <Table
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
        title={editing ? '编辑敏感词' : '新增敏感词'}
        open={formOpen}
        confirmLoading={save.isPending}
        onCancel={() => {
          setFormOpen(false);
          setEditing(null);
        }}
        onOk={() => {
          void form.validateFields().then((values) => save.mutate(values));
        }}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="word" label="词条" rules={[{ required: true, message: '请输入词条' }]}>
            <Input maxLength={128} placeholder="支持空格/符号绕过归一化" />
          </Form.Item>
          <Form.Item name="level" label="级别" rules={[{ required: true }]}>
            <Select options={Object.entries(LEVEL).map(([value, item]) => ({ value: Number(value), label: item.text }))} />
          </Form.Item>
          <Form.Item name="category" label="分类">
            <Select allowClear options={CATEGORY_OPTIONS} />
          </Form.Item>
          <Form.Item name="source" label="来源">
            <Select allowClear options={SOURCE_OPTIONS} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 1, label: '启用' },
                { value: 0, label: '停用' },
              ]}
            />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={256} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="批量导入敏感词"
        open={importOpen}
        width={640}
        confirmLoading={doImport.isPending}
        okText={preview ? '确认导入' : '先预览'}
        onOk={() => {
          if (preview) {
            doImport.mutate();
          } else {
            previewImport.mutate();
          }
        }}
        onCancel={() => {
          setImportOpen(false);
          setPreview(null);
        }}
        okButtonProps={{ disabled: !importText.trim() }}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space>
            <Select
              value={importLevel}
              style={{ width: 140 }}
              options={Object.entries(LEVEL).map(([value, item]) => ({ value: Number(value), label: item.text }))}
              onChange={setImportLevel}
            />
            <Select
              value={importCategory}
              style={{ width: 160 }}
              options={CATEGORY_OPTIONS}
              onChange={setImportCategory}
            />
          </Space>
          <Input.TextArea
            rows={8}
            value={importText}
            placeholder={'每行一个词，# 开头为注释\n单次最多 10000 条'}
            onChange={(event) => {
              setImportText(event.target.value);
              setPreview(null);
            }}
          />
          {preview ? (
            <Alert
              type="success"
              showIcon
              message="导入预览"
              description={`共 ${preview.total} 行：新增 ${preview.newCount}，重复 ${preview.duplicateCount}，非法 ${preview.invalidCount}`}
            />
          ) : (
            <Typography.Text type="secondary">先预览再导入，重复词会自动跳过。</Typography.Text>
          )}
        </Space>
      </Modal>
    </Space>
  );
}

function WhiteListTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [word, setWord] = useState('');
  const [reason, setReason] = useState('');

  const query = useQuery({ queryKey: ['safety-whites'], queryFn: () => safetyApi.whites.list() });

  const create = useMutation({
    mutationFn: () => safetyApi.whites.create({ word, reason: reason || undefined }),
    onSuccess: () => {
      message.success('白名单已新增');
      setWord('');
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['safety-whites'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const remove = useMutation({
    mutationFn: (id: string | number) => safetyApi.whites.remove(id),
    onSuccess: () => {
      message.success('白名单已删除');
      void queryClient.invalidateQueries({ queryKey: ['safety-whites'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="白名单优先于敏感词：整词匹配后跳过命中。" />
      <Space.Compact style={{ width: '100%' }}>
        <Input
          value={word}
          placeholder="白名单词"
          onChange={(event) => setWord(event.target.value)}
          style={{ width: 260 }}
        />
        <Input
          value={reason}
          placeholder="原因（可选）"
          onChange={(event) => setReason(event.target.value)}
        />
        <Button
          type="primary"
          disabled={!hasPerm('admin:audit:word:edit') || !word.trim()}
          loading={create.isPending}
          onClick={() => create.mutate()}
        >
          新增
        </Button>
      </Space.Compact>
      <Table<WhiteWord>
        rowKey={(row) => String(row.id)}
        size="small"
        loading={query.isLoading}
        dataSource={query.data ?? []}
        pagination={false}
        columns={[
          { title: '词条', dataIndex: 'word' },
          { title: '原因', dataIndex: 'reason' },
          {
            title: '创建时间',
            dataIndex: 'createTime',
            render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
          },
          {
            title: '操作',
            width: 100,
            render: (_, row) => (
              <Popconfirm title="删除白名单？" onConfirm={() => remove.mutate(row.id)}>
                <Button size="small" danger disabled={!hasPerm('admin:audit:word:edit')}>
                  删除
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />
    </Space>
  );
}

interface RuleFormValues {
  ruleName: string;
  ruleType: string;
  pattern: string;
  scene: string;
  action: string;
  priority: number;
  status: number;
  remark?: string;
}

function RuleListTab(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<AuditRule | null>(null);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<RuleFormValues>();

  const query = useQuery({ queryKey: ['safety-rules'], queryFn: () => safetyApi.rules.list() });

  const save = useMutation({
    mutationFn: (values: RuleFormValues) =>
      editing ? safetyApi.rules.update(editing.id, values as RulePayload)
        : safetyApi.rules.create(values as RulePayload),
    onSuccess: () => {
      message.success('规则已保存');
      setOpen(false);
      setEditing(null);
      form.resetFields();
      void queryClient.invalidateQueries({ queryKey: ['safety-rules'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const remove = useMutation({
    mutationFn: (id: string | number) => safetyApi.rules.remove(id),
    onSuccess: () => {
      message.success('规则已删除');
      void queryClient.invalidateQueries({ queryKey: ['safety-rules'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const openCreate = (): void => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      ruleType: 'REGEX', scene: 'ALL', action: 'REVIEW', priority: 100, status: 1,
    });
    setOpen(true);
  };

  const openEdit = (row: AuditRule): void => {
    setEditing(row);
    form.setFieldsValue({
      ruleName: row.ruleName,
      ruleType: row.ruleType ?? 'REGEX',
      pattern: row.pattern,
      scene: row.scene ?? 'ALL',
      action: row.action ?? 'REVIEW',
      priority: row.priority ?? 100,
      status: row.status,
      remark: row.remark,
    });
    setOpen(true);
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space>
        <Button type="primary" onClick={openCreate} disabled={!hasPerm('admin:audit:rule:edit')}>
          新增规则
        </Button>
        <Typography.Text type="secondary">
          REGEX 规则先于敏感词阈值生效；命中 REJECT 直接拒绝，命中 REVIEW 转人工。
        </Typography.Text>
      </Space>
      <Table<AuditRule>
        rowKey={(row) => String(row.id)}
        size="small"
        loading={query.isLoading}
        dataSource={query.data ?? []}
        pagination={false}
        columns={[
          { title: '规则名', dataIndex: 'ruleName', width: 160 },
          { title: '类型', dataIndex: 'ruleType', width: 100 },
          { title: '正则/模式', dataIndex: 'pattern', ellipsis: true },
          { title: '场景', dataIndex: 'scene', width: 100 },
          {
            title: '动作',
            dataIndex: 'action',
            width: 90,
            render: (value?: string) => (
              <Tag color={value === 'REJECT' ? 'red' : value === 'PASS' ? 'green' : 'orange'}>
                {value ?? 'REVIEW'}
              </Tag>
            ),
          },
          { title: '优先级', dataIndex: 'priority', width: 80 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 80,
            render: (value: number) => (value === 1 ? '启用' : '停用'),
          },
          {
            title: '操作',
            width: 140,
            render: (_, row) => (
              <Space>
                <Button size="small" onClick={() => openEdit(row)} disabled={!hasPerm('admin:audit:rule:edit')}>
                  编辑
                </Button>
                <Popconfirm title="删除规则？" onConfirm={() => remove.mutate(row.id)}>
                  <Button size="small" danger disabled={!hasPerm('admin:audit:rule:edit')}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={editing ? '编辑审核规则' : '新增审核规则'}
        open={open}
        confirmLoading={save.isPending}
        onCancel={() => {
          setOpen(false);
          setEditing(null);
        }}
        onOk={() => {
          void form.validateFields().then((values) => save.mutate(values));
        }}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="ruleName" label="规则名" rules={[{ required: true }]}>
            <Input maxLength={64} placeholder="如：手机号" />
          </Form.Item>
          <Form.Item name="ruleType" label="类型">
            <Select options={[{ value: 'REGEX' }, { value: 'KEYWORD' }]} />
          </Form.Item>
          <Form.Item name="pattern" label="正则/模式" rules={[{ required: true }]}>
            <Input.TextArea rows={3} maxLength={512} />
          </Form.Item>
          <Form.Item name="scene" label="场景">
            <Select
              options={['ALL', 'COMMENT', 'POST', 'NICKNAME', 'PLAYLIST', 'ROOM'].map((value) => ({
                value, label: value,
              }))}
            />
          </Form.Item>
          <Form.Item name="action" label="命中动作">
            <Select
              options={[
                { value: 'REVIEW', label: '转人工' },
                { value: 'REJECT', label: '直接拒绝' },
                { value: 'PASS', label: '放行' },
              ]}
            />
          </Form.Item>
          <Form.Item name="priority" label="优先级">
            <InputNumber min={1} max={9999} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 1, label: '启用' }, { value: 0, label: '停用' }]} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input maxLength={256} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function VersionsTab(): JSX.Element {
  const { hasPerm, admin } = useAuth();
  const superAdmin = (admin?.roles ?? []).includes('SUPER_ADMIN');
  const queryClient = useQueryClient();

  const versions = useQuery({ queryKey: ['safety-versions'], queryFn: () => safetyApi.words.versions() });
  const stats = useQuery({ queryKey: ['safety-word-stats'], queryFn: () => safetyApi.words.stats() });

  const reload = useMutation({
    mutationFn: () => safetyApi.words.reload(),
    onSuccess: (data) => {
      message.success(`热加载完成，共 ${data.wordCount} 条词`);
      void queryClient.invalidateQueries({ queryKey: ['safety-word-stats'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const rollback = useMutation({
    mutationFn: (version: number) => safetyApi.words.rollback(version),
    onSuccess: (data) => {
      message.success(`已回滚到 v${data.rolledBackTo}`);
      void queryClient.invalidateQueries({ queryKey: ['safety-versions'] });
      void queryClient.invalidateQueries({ queryKey: ['safety-word-stats'] });
      void queryClient.invalidateQueries({ queryKey: ['safety-words'] });
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const byLevel = (stats.data?.byLevel as { level: number; count: number }[] | undefined) ?? [];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        size="small"
        title="当前加载状态"
        extra={
          <Button
            type="primary"
            loading={reload.isPending}
            disabled={!hasPerm('admin:audit:word:edit')}
            onClick={() => reload.mutate()}
          >
            一键热加载
          </Button>
        }
      >
        <Descriptions size="small" column={3}>
          <Descriptions.Item label="词库版本">v{String(stats.data?.wordVersion ?? '-')}</Descriptions.Item>
          <Descriptions.Item label="已加载词条">{String(stats.data?.loadedWords ?? '-')}</Descriptions.Item>
          <Descriptions.Item label="降级状态">
            {stats.data?.fallback ? <Tag color="red">降级</Tag> : <Tag color="green">正常</Tag>}
          </Descriptions.Item>
          {byLevel.map((item) => (
            <Descriptions.Item key={item.level} label={LEVEL[item.level]?.text ?? `L${item.level}`}>
              {item.count}
            </Descriptions.Item>
          ))}
        </Descriptions>
      </Card>

      <Table<WordVersion>
        rowKey={(row) => String(row.id)}
        size="small"
        title={() => '版本快照（可回滚；快照内容不回传前端）'}
        loading={versions.isLoading}
        dataSource={versions.data ?? []}
        pagination={{ pageSize: 10 }}
        columns={[
          { title: '版本', dataIndex: 'version', width: 90, render: (value) => `v${value}` },
          { title: '词条数', dataIndex: 'wordCount', width: 100 },
          { title: '变更说明', dataIndex: 'changeDesc' },
          {
            title: '创建时间',
            dataIndex: 'createTime',
            width: 170,
            render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
          },
          {
            title: '操作',
            width: 110,
            render: (_, row) => (
              <Popconfirm
                title={`回滚到 v${row.version}？`}
                description="白名单与规则也会按快照恢复，操作会记录审计日志。"
                onConfirm={() => rollback.mutate(row.version)}
              >
                <Button size="small" disabled={!hasPerm('admin:audit:word:edit')}>
                  回滚
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      <Table
        size="small"
        title={() => '命中排行 TOP 20'}
        rowKey={(row) => `${String(row.word)}-${String(row.level)}`}
        dataSource={(stats.data?.topHits as Record<string, unknown>[] | undefined) ?? []}
        pagination={false}
        columns={[
          {
            title: '词条',
            dataIndex: 'word',
            render: (value: string, row: Record<string, unknown>) =>
              maskWord(value, Number(row.level ?? 1), superAdmin),
          },
          {
            title: '级别',
            dataIndex: 'level',
            width: 100,
            render: (value: number) => <Tag color={LEVEL[value]?.color}>{LEVEL[value]?.text ?? value}</Tag>,
          },
          { title: '分类', dataIndex: 'category', width: 120 },
          { title: '命中次数', dataIndex: 'hitCount', width: 110 },
          {
            title: '最近命中',
            dataIndex: 'lastHitTime',
            render: (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
          },
        ]}
      />
    </Space>
  );
}

function TestTab(): JSX.Element {
  const { admin } = useAuth();
  const superAdmin = (admin?.roles ?? []).includes('SUPER_ADMIN');
  const [text, setText] = useState('');
  const [scene, setScene] = useState('COMMENT');
  const [result, setResult] = useState<WordTestResult | null>(null);
  const [error, setError] = useState('');

  const test = useMutation({
    mutationFn: () => safetyApi.words.test({ text, scene }),
    onSuccess: (data) => {
      setResult(data);
      setError('');
    },
    onError: (err) => {
      setResult(null);
      setError(errorMessage(err));
    },
  });

  const highlighted = useMemo(() => {
    if (!result || result.matches.length === 0) return result?.normalized ?? '';
    const source = result.normalized;
    const parts: JSX.Element[] = [];
    let cursor = 0;
    result.matches
      .slice()
      .sort((a, b) => a.start - b.start)
      .forEach((match, index) => {
        if (match.start < cursor) return;
        parts.push(<span key={`t-${index}`}>{source.slice(cursor, match.start)}</span>);
        parts.push(
          <Typography.Text key={`m-${index}`} mark>
            {maskWord(source.slice(match.start, match.end), match.level, superAdmin)}
          </Typography.Text>,
        );
        cursor = match.end;
      });
    parts.push(<span key="tail">{source.slice(cursor)}</span>);
    return parts;
  }, [result, superAdmin]);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="与线上同一套 DFA + 归一化逻辑"
        description="测试结果只读，不写内容审核记录；需要真实机审请使用审核工作台。"
      />
      <Input.TextArea
        rows={5}
        value={text}
        maxLength={10000}
        placeholder="输入待检测文本，例如：加 微 信 聊"
        onChange={(event) => setText(event.target.value)}
      />
      <Space>
        <Select
          value={scene}
          style={{ width: 160 }}
          options={['COMMENT', 'POST', 'NICKNAME', 'PLAYLIST', 'ROOM', 'ALL'].map((value) => ({
            value, label: value,
          }))}
          onChange={setScene}
        />
        <Button type="primary" loading={test.isPending} disabled={!text.trim()} onClick={() => test.mutate()}>
          检测
        </Button>
      </Space>

      {error ? <Alert type="error" showIcon message="检测失败" description={error} /> : null}

      {result ? (
        <Card size="small" title="检测结果">
          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label="归一化文本" span={2}>
              {highlighted || '（空）'}
            </Descriptions.Item>
            <Descriptions.Item label="处置">
              <Tag color={DECISION[result.decision]?.color}>{DECISION[result.decision]?.text ?? result.decision}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="词库版本">v{result.wordVersion ?? '-'}</Descriptions.Item>
          </Descriptions>
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            敏感词命中（{result.matches.length}）
          </Typography.Title>
          <Table
            size="small"
            rowKey={(row) => `${row.word}-${row.start}`}
            pagination={false}
            dataSource={result.matches}
            columns={[
              {
                title: '词',
                dataIndex: 'word',
                render: (value: string, row) => maskWord(value, row.level, superAdmin),
              },
              {
                title: '级别',
                dataIndex: 'level',
                width: 100,
                render: (value: number) => <Tag color={LEVEL[value]?.color}>{LEVEL[value]?.text ?? value}</Tag>,
              },
              { title: '分类', dataIndex: 'category', width: 120 },
              {
                title: '位置',
                key: 'range',
                width: 120,
                render: (_, row) => `[${row.start}, ${row.end}]`,
              },
            ]}
          />
          <Typography.Title level={5} style={{ marginTop: 16 }}>
            正则规则命中（{result.rules.length}）
          </Typography.Title>
          <Table
            size="small"
            rowKey={(row) => row.ruleName}
            pagination={false}
            dataSource={result.rules}
            columns={[
              { title: '规则', dataIndex: 'ruleName' },
              {
                title: '动作',
                dataIndex: 'action',
                width: 100,
                render: (value: string) => (
                  <Tag color={value === 'REJECT' ? 'red' : 'orange'}>{value}</Tag>
                ),
              },
            ]}
          />
        </Card>
      ) : null}
    </Space>
  );
}

export function WordsPanel(): JSX.Element {
  return (
    <Tabs
      items={[
        { key: 'words', label: '词条', children: <WordListTab /> },
        { key: 'whites', label: '白名单', children: <WhiteListTab /> },
        { key: 'rules', label: '审核规则', children: <RuleListTab /> },
        { key: 'test', label: '测试工具', children: <TestTab /> },
        { key: 'versions', label: '版本与热加载', children: <VersionsTab /> },
      ]}
    />
  );
}
