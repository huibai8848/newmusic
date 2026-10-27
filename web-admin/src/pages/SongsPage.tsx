import { useState, type Key } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DownloadOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import {
  albumApi,
  artistApi,
  songApi,
  type ImportResult,
  type SongPayload,
  type SongQuery,
} from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import { AudioUploadField } from '@/components/AudioUploadField';
import { ImageUploadField } from '@/components/ImageUploadField';
import { useAuth } from '@/auth/AuthContext';
import {
  normalizeLanguage,
  type AudioMetadata,
} from '@/utils/audioMetadata';
import type { Id, PageResult, Song } from '@/api/types';

const AUDIT_STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '待审核', color: 'orange' },
  1: { text: '已通过', color: 'green' },
  2: { text: '已拒绝', color: 'red' },
};

const COPYRIGHT_STATUS: Record<number, string> = {
  0: '未授权',
  1: '已授权',
  2: '已过期',
  3: '受限',
};

const MAX_LYRIC_FILE_MB = 2;

/** `.lrc` 常见编码是 UTF-8（含 BOM）或 GBK；先严格按 UTF-8 解，失败再按 GB18030 兜底。 */
function decodeLyricText(buffer: ArrayBuffer): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    return new TextDecoder('gb18030').decode(buffer);
  }
}

function sameText(a?: string | null, b?: string | null): boolean {
  return Boolean(a && b && a.trim().toLowerCase() === b.trim().toLowerCase());
}

function aliasMatches(alias: string | undefined, name: string): boolean {
  return (alias ?? '')
    .split(/[,，/、;；]/)
    .some((item) => sameText(item, name));
}

export function SongsPage(): JSX.Element {
  const { hasPerm } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<SongQuery>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [selected, setSelected] = useState<Key[]>([]);
  const [editingId, setEditingId] = useState<Id | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState<Song | null>(null);
  const [deleteReason, setDeleteReason] = useState('');
  const [form] = Form.useForm<SongPayload>();

  const query = { pageNum, pageSize, ...filters };
  const { data, isLoading } = useQuery<PageResult<Song>>({
    queryKey: ['admin-songs', query],
    queryFn: () => songApi.page(query),
  });

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['admin-songs'] });
  };

  const openCreate = (): void => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({
      copyrightStatus: 1,
      status: 0,
      auditStatus: 0,
      language: '国语',
      duration: 0,
    });
    setDrawerOpen(true);
  };

  const openEdit = async (record: Song): Promise<void> => {
    try {
      const [detail, lyric] = await Promise.all([
        songApi.detail(record.id),
        // 歌词是单独的表；读取失败（如旧数据）不该挡住编辑表单
        songApi.lyric(record.id).catch(() => null),
      ]);
      setEditingId(record.id);
      form.resetFields();
      form.setFieldsValue({
        lyric: lyric?.content ?? undefined,
        name: detail.name,
        subtitle: detail.subtitle,
        artistId: detail.artistId as Id,
        albumId: detail.albumId as Id | undefined,
        cover: detail.cover,
        language: detail.language,
        genre: detail.genre,
        duration: detail.duration,
        releaseDate: detail.releaseDate,
        audioStandard: detail.audioStandard,
        audioHigh: detail.audioHigh,
        audioLossless: detail.audioLossless,
        copyrightStatus: detail.copyrightStatus,
        auditStatus: detail.auditStatus,
        status: detail.status,
        version: detail.version,
      });
      setDrawerOpen(true);
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  /**
   * 新增歌曲时把音频内嵌标签填进表单。
   *
   * 只补空字段，不覆盖用户已经输入的内容；歌手/专辑标签只有名字，
   * 需要在后台按名称精确匹配出 ID（匹配不到就提示，不擅自建数据）。
   * 编辑已有歌曲时不做自动填充，避免替换音源时覆盖现有信息。
   */
  const applyAudioMetadata = async (metadata: AudioMetadata): Promise<void> => {
    if (editingId !== null) {
      return;
    }
    const filled: string[] = [];
    const warnings: string[] = [];
    const current = (field: keyof SongPayload): unknown => form.getFieldValue(field);

    const patch: Partial<SongPayload> = {};
    if (metadata.title && !current('name')) {
      patch.name = metadata.title;
      filled.push('歌名');
    }
    if (metadata.duration && !current('duration')) {
      patch.duration = metadata.duration;
      filled.push('时长');
    }
    if (metadata.genre && !current('genre')) {
      patch.genre = metadata.genre;
      filled.push('流派');
    }
    const language = normalizeLanguage(metadata.language);
    if (language && (!current('language') || current('language') === '国语')) {
      patch.language = language;
      if (current('language') !== language) {
        filled.push('语种');
      }
    }
    if (metadata.releaseDate && !current('releaseDate')) {
      patch.releaseDate = metadata.releaseDate;
      filled.push('发行日期');
    }
    if (metadata.lyric && !current('lyric')) {
      patch.lyric = metadata.lyric;
      filled.push('歌词');
    }
    if (Object.keys(patch).length > 0) {
      form.setFieldsValue(patch);
    }

    const artistName = metadata.artist;
    if (artistName && !current('artistId')) {
      try {
        const page = await artistApi.page({ pageNum: 1, pageSize: 20, keyword: artistName });
        const artist =
          page.list.find((item) => sameText(item.name, artistName)) ??
          page.list.find((item) => aliasMatches(item.alias, artistName));
        if (artist) {
          form.setFieldValue('artistId', artist.id);
          filled.push('歌手');
        } else {
          warnings.push(`未找到歌手「${artistName}」`);
        }
      } catch {
        warnings.push(`歌手「${artistName}」自动匹配失败（可能没有歌手管理权限）`);
      }
    }

    const albumName = metadata.album;
    if (albumName && !current('albumId')) {
      try {
        const artistId = form.getFieldValue('artistId') as Id | undefined;
        const page = await albumApi.page({
          pageNum: 1,
          pageSize: 20,
          keyword: albumName,
          artistId: artistId ? String(artistId) : undefined,
        });
        const album = page.list.find((item) => sameText(item.name, albumName));
        if (album) {
          form.setFieldValue('albumId', album.id);
          filled.push('专辑');
        } else {
          warnings.push(`未找到专辑「${albumName}」`);
        }
      } catch {
        warnings.push(`专辑「${albumName}」自动匹配失败（可能没有专辑管理权限）`);
      }
    }

    if (filled.length > 0) {
      message.success(`已从音频标签填入：${filled.join('、')}`);
    }
    if (warnings.length > 0) {
      message.warning(`${warnings.join('；')}，请手动补充或先在对应管理中创建`);
    }
    if (filled.length === 0 && warnings.length === 0) {
      message.info('音频里没有可用的标签信息，请手动填写歌曲信息');
    }
  };

  /** 读取 .lrc 文件填进歌词文本域；真正写库仍走歌曲保存，不额外调用接口。 */
  const importLyricFile = async (file: File): Promise<void> => {
    if (!file.name.toLowerCase().endsWith('.lrc')) {
      message.error('请选择 .lrc 歌词文件');
      return;
    }
    if (file.size > MAX_LYRIC_FILE_MB * 1024 * 1024) {
      message.error(
        `歌词文件不能超过 ${MAX_LYRIC_FILE_MB}MB（当前 ${(file.size / 1024 / 1024).toFixed(1)}MB）`,
      );
      return;
    }
    try {
      // TextDecoder 默认会去掉 UTF-8 BOM，无需再手工处理
      const content = decodeLyricText(await file.arrayBuffer());
      if (!content.trim()) {
        message.warning('歌词文件是空的');
        return;
      }
      form.setFieldValue('lyric', content);
      message.success(`已读取 ${file.name}（${content.length} 字符），保存歌曲后生效`);
    } catch {
      message.error('歌词文件读取失败，请确认编码为 UTF-8 或 GBK');
    }
  };

  const save = async (): Promise<void> => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editingId === null) {
        await songApi.create({ ...values, version: undefined });
        message.success('歌曲已创建，默认进入待审核状态');
      } else {
        await songApi.update(editingId, values);
        message.success('歌曲已更新');
      }
      setDrawerOpen(false);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const toggleStatus = async (record: Song): Promise<void> => {
    const next = record.status === 1 ? 0 : 1;
    try {
      await songApi.changeStatus(record.id, next);
      message.success(next === 1 ? '已上架' : '已下架');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const remove = async (): Promise<void> => {
    if (!deleting) return;
    if (deleteReason.trim().length < 5) {
      message.error('请填写至少 5 个字符的删除原因');
      return;
    }
    try {
      await songApi.remove(deleting.id, deleteReason.trim());
      message.success('歌曲已删除');
      setDeleting(null);
      setDeleteReason('');
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const batchStatus = async (status: number): Promise<void> => {
    if (selected.length === 0) return;
    try {
      const handled = await songApi.batchStatus(selected.map(String), status);
      message.success(`已处理 ${handled} 首歌曲`);
      setSelected([]);
      refresh();
    } catch (error) {
      message.error(errorMessage(error));
    }
  };

  const importFile = async (file: File): Promise<void> => {
    try {
      const text = await file.text();
      const parsed: unknown = JSON.parse(text);
      if (!Array.isArray(parsed) || parsed.length === 0) {
        message.error('文件内容必须是非空 JSON 数组');
        return;
      }
      const result: ImportResult = await songApi.importRows(parsed as SongPayload[]);
      Modal.info({
        title: '导入结果',
        width: 560,
        content: (
          <div>
            <Typography.Paragraph>
              成功 {result.success} 行，失败 {result.failed} 行。
            </Typography.Paragraph>
            {result.errors.length > 0 && (
              <ul style={{ maxHeight: 260, overflow: 'auto', paddingLeft: 18 }}>
                {result.errors.map((item) => (
                  <li key={item.row}>
                    第 {item.row} 行：{item.message}
                  </li>
                ))}
              </ul>
            )}
          </div>
        ),
        okText: '关闭',
      });
      refresh();
    } catch (error) {
      message.error(error instanceof SyntaxError ? 'JSON 解析失败' : errorMessage(error));
    }
  };

  const exportCsv = (): void => {
    window.open(songApi.exportUrl(query), '_blank', 'noopener');
  };

  const columns: ColumnsType<Song> = [
    { title: 'ID', dataIndex: 'id', width: 180, render: (value) => String(value) },
    { title: '歌曲名', dataIndex: 'name', width: 180 },
    { title: '副标题', dataIndex: 'subtitle', width: 140, render: (value) => value || '—' },
    { title: '歌手', dataIndex: 'artistName', width: 130 },
    { title: '专辑', dataIndex: 'albumName', width: 140, render: (value) => value || '—' },
    { title: '语种', dataIndex: 'language', width: 90, render: (value) => value || '—' },
    { title: '流派', dataIndex: 'genre', width: 100, render: (value) => value || '—' },
    {
      title: '时长',
      dataIndex: 'duration',
      width: 80,
      render: (value?: number) =>
        value ? `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}` : '—',
    },
    {
      title: '审核',
      dataIndex: 'auditStatus',
      width: 100,
      render: (value: number) => {
        const status = AUDIT_STATUS[value] ?? { text: `未知(${value})`, color: 'default' };
        return <Tag color={status.color}>{status.text}</Tag>;
      },
    },
    {
      title: '版权',
      dataIndex: 'copyrightStatus',
      width: 100,
      render: (value: number) => COPYRIGHT_STATUS[value] ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) =>
        value === 1 ? <Tag color="green">上架</Tag> : <Tag>下架</Tag>,
    },
    {
      title: '播放量',
      dataIndex: 'playCount',
      width: 100,
      render: (value?: number) => (value ?? 0).toLocaleString(),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 230,
      render: (_, record) => (
        <Space size={4} wrap>
          {hasPerm('admin:song:edit') && (
            <Button type="link" size="small" onClick={() => void openEdit(record)}>
              编辑
            </Button>
          )}
          {(hasPerm('admin:song:online') || hasPerm('admin:song:offline')) && (
            <Button type="link" size="small" onClick={() => void toggleStatus(record)}>
              {record.status === 1 ? '下架' : '上架'}
            </Button>
          )}
          {hasPerm('admin:song:delete') && (
            <Button type="link" size="small" danger onClick={() => setDeleting(record)}>
              删除
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          placeholder="歌曲名 / 歌手"
          style={{ width: 220 }}
          onSearch={(value) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, keyword: value.trim() || undefined }));
          }}
        />
        <Select
          allowClear
          placeholder="上架状态"
          style={{ width: 120 }}
          options={[
            { value: 1, label: '已上架' },
            { value: 0, label: '已下架' },
          ]}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, status: value }));
          }}
        />
        <Select
          allowClear
          placeholder="审核状态"
          style={{ width: 120 }}
          options={Object.entries(AUDIT_STATUS).map(([value, status]) => ({
            value: Number(value),
            label: status.text,
          }))}
          onChange={(value?: number) => {
            setPageNum(1);
            setFilters((current) => ({ ...current, auditStatus: value }));
          }}
        />
        {hasPerm('admin:song:create') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增歌曲
          </Button>
        )}
        {hasPerm('admin:song:create') && (
          <Upload
            accept=".json,application/json"
            showUploadList={false}
            beforeUpload={(file) => {
              void importFile(file);
              return false;
            }}
          >
            <Button icon={<UploadOutlined />}>JSON 导入</Button>
          </Upload>
        )}
        <Button icon={<DownloadOutlined />} onClick={exportCsv}>
          导出 CSV
        </Button>
      </Space>

      {(hasPerm('admin:song:online') || hasPerm('admin:song:offline')) && selected.length > 0 && (
        <Space style={{ marginBottom: 12 }}>
          <Typography.Text>已选 {selected.length} 首</Typography.Text>
          <Button size="small" onClick={() => void batchStatus(1)}>
            批量上架
          </Button>
          <Button size="small" onClick={() => void batchStatus(0)}>
            批量下架
          </Button>
          <Button size="small" type="link" onClick={() => setSelected([])}>
            取消选择
          </Button>
        </Space>
      )}

      <Table<Song>
        rowKey={(record) => String(record.id)}
        loading={isLoading}
        columns={columns}
        dataSource={data?.list ?? []}
        scroll={{ x: 1900 }}
        rowSelection={{ selectedRowKeys: selected, onChange: setSelected }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 首`,
          onChange: (nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          },
        }}
      />

      <Drawer
        title={editingId === null ? '新增歌曲' : '编辑歌曲'}
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Button type="primary" loading={saving} onClick={() => void save()}>
            保存
          </Button>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="歌曲名" rules={[{ required: true, message: '请输入歌曲名' }, { max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="subtitle" label="副标题" rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="artistId"
            label="主歌手 ID"
            rules={[{ required: true, message: '请输入歌手 ID' }]}
            extra="歌手管理在「内容管理」模块提供；当前先填写已有歌手 ID"
          >
            <InputNumber style={{ width: '100%' }} min={1} precision={0} stringMode />
          </Form.Item>
          <Form.Item name="albumId" label="专辑 ID（可空）">
            <InputNumber style={{ width: '100%' }} min={1} precision={0} stringMode />
          </Form.Item>
          <Form.Item name="language" label="语种">
            <Input placeholder="国语 / 粤语 / 英语…" />
          </Form.Item>
          <Form.Item name="genre" label="流派">
            <Input placeholder="流行 / 摇滚 / 民谣…" />
          </Form.Item>
          <Form.Item name="duration" label="时长（秒）">
            <InputNumber style={{ width: '100%' }} min={0} max={65535} precision={0} />
          </Form.Item>
          <Form.Item name="releaseDate" label="发行日期">
            <Input placeholder="yyyy-MM-dd" />
          </Form.Item>
          <Form.Item name="cover" label="封面">
            <ImageUploadField placeholder="上传封面，或填写图片 URL" />
          </Form.Item>
          <Form.Item
            name="audioStandard"
            label="标准音质"
            rules={[{ required: true, message: '标准音质是上架的必要条件' }]}
            extra="上架前必须填写。上传本地音频（≤50MB）会尝试自动读取歌名/歌手/专辑/时长等信息；也可填对象存储 URL"
          >
            <AudioUploadField
              placeholder="上传音频，或填写音频 URL"
              onMetadata={(metadata) => {
                void applyAudioMetadata(metadata);
              }}
            />
          </Form.Item>
          <Form.Item name="audioHigh" label="高清音质">
            <AudioUploadField placeholder="可选：上传独立的高清音源，或填写 URL" />
          </Form.Item>
          <Form.Item name="audioLossless" label="无损音质">
            <AudioUploadField placeholder="可选：上传无损音源，或填写 URL" />
          </Form.Item>
          <Form.Item
            name="lyric"
            label="歌词（LRC 或纯文本）"
            rules={[{ max: 200000, message: '歌词内容过长' }]}
            extra={
              <div>
                <div>可选。填写或上传 .lrc 后覆盖该歌曲现有歌词；留空则保持原样。</div>
                <Space size={8} wrap style={{ marginTop: 8 }}>
                  <Upload
                    accept=".lrc"
                    showUploadList={false}
                    beforeUpload={(file) => {
                      void importLyricFile(file);
                      // 只读取文件内容填进文本域，不把 .lrc 上传到服务器
                      return Upload.LIST_IGNORE;
                    }}
                  >
                    <Button size="small" icon={<UploadOutlined />}>
                      上传 .lrc 文件
                    </Button>
                  </Upload>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    支持 UTF-8 / GBK，≤ {MAX_LYRIC_FILE_MB}MB；文件内容只填入文本框，保存歌曲后生效
                  </Typography.Text>
                </Space>
              </div>
            }
          >
            <Input.TextArea rows={8} placeholder="[00:12.00]第一句歌词&#10;[00:16.50]第二句歌词" />
          </Form.Item>
          <Form.Item name="copyrightStatus" label="版权状态">
            <Select
              options={Object.entries(COPYRIGHT_STATUS).map(([value, label]) => ({
                value: Number(value),
                label,
              }))}
            />
          </Form.Item>
          <Form.Item name="status" label="上架状态">
            <Select
              options={[
                { value: 0, label: '下架' },
                { value: 1, label: '上架（仍需满足审核与版权条件）' },
              ]}
            />
          </Form.Item>
          {editingId !== null && (
            <>
              {/*
                审核状态由审核工作台维护，这里只做透传：`SongSaveReq` 是全量更新，
                后端会用请求值覆盖已有值再校验上架条件。没有注册这个字段时
                `validateFields()` 不会带上它，后端把审核状态当成 null，
                保存任何「已上架」歌曲都会报 30043（不满足上架条件）。
              */}
              <Form.Item name="auditStatus" hidden>
                <InputNumber />
              </Form.Item>
              <Form.Item name="version" label="版本号（乐观锁）" hidden>
                <InputNumber />
              </Form.Item>
            </>
          )}
        </Form>
      </Drawer>

      <Modal
        title={`删除歌曲：${deleting?.name ?? ''}`}
        open={deleting !== null}
        onCancel={() => {
          setDeleting(null);
          setDeleteReason('');
        }}
        onOk={() => void remove()}
        okText="确认删除"
        okButtonProps={{ danger: true }}
      >
        <Typography.Paragraph type="warning">
          删除后不可恢复。请填写删除原因（5-500 字），该操作会写入审计日志。
        </Typography.Paragraph>
        <Input.TextArea
          rows={3}
          maxLength={500}
          showCount
          value={deleteReason}
          onChange={(event) => setDeleteReason(event.target.value)}
        />
      </Modal>

    </Card>
  );
}
