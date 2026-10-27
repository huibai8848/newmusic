import { useEffect, useState } from 'react';
import { Alert, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select, Tabs } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { albumApi, artistApi, tagApi } from '@/api/catalog';
import { ApiError } from '@/api/request';
import { songApi, songKeys } from '@/api/song';
import type { Id, Song, SongSaveReq } from '@/types/api';
import { toastSuccess } from '@/utils/feedback';

/**
 * 歌曲新增 / 编辑弹窗（模块 02 T116 后台）。
 *
 * 与模块 01 §4 的 `SongSaveReq` 一一对应；编辑时**必须回传 version**（乐观锁），
 * 否则后端返回 10001；版本不一致返回 10008（说明被他人改过）。
 * `tagIds` 是全量覆盖语义：不传 = 清空标签。
 */

interface Props {
  open: boolean;
  song?: Song | null;
  onClose: () => void;
}

interface FormValues extends Omit<SongSaveReq, 'releaseDate' | 'copyrightExpire' | 'artistId'> {
  artistId?: Id;
  releaseDate?: Dayjs;
  copyrightExpire?: Dayjs;
}

const OPTION_LIMIT = 100;

export function SongFormModal({ open, song, onClose }: Props): JSX.Element {
  const [form] = Form.useForm<FormValues>();
  const queryClient = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const isEdit = Boolean(song);

  // 下拉选项：MVP 取前 100 条本地过滤；数据量上去后改为服务端搜索（模块 09）
  const artists = useQuery({
    queryKey: ['artist', 'options'],
    queryFn: () => artistApi.list({ pageSize: OPTION_LIMIT }),
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const albums = useQuery({
    queryKey: ['album', 'options'],
    queryFn: () => albumApi.list({ pageSize: OPTION_LIMIT }),
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const tags = useQuery({
    queryKey: ['tag', 'list', { pageSize: OPTION_LIMIT }],
    queryFn: () => tagApi.list({ pageSize: OPTION_LIMIT }),
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const songTags = useQuery({
    queryKey: songKeys.tags(song?.id ?? ''),
    queryFn: () => songApi.tags(song?.id as string),
    enabled: open && Boolean(song?.id),
  });

  const selectedArtistId = Form.useWatch('artistId', form);

  useEffect(() => {
    if (!open) return;
    if (song) {
      form.setFieldsValue({
        name: song.name,
        subtitle: song.subtitle ?? undefined,
        artistId: song.artistId,
        albumId: song.albumId ?? undefined,
        cover: song.cover ?? undefined,
        language: song.language ?? undefined,
        genre: song.genre ?? undefined,
        duration: song.duration ?? undefined,
        bpm: song.bpm ?? undefined,
        releaseDate: song.releaseDate ? dayjs(song.releaseDate) : undefined,
        era: song.era ?? undefined,
        isrc: song.isrc ?? undefined,
        audioStandard: song.audioStandard ?? undefined,
        audioHigh: song.audioHigh ?? undefined,
        audioLossless: song.audioLossless ?? undefined,
        copyrightStatus: song.copyrightStatus,
        copyrightOwner: song.copyrightOwner ?? undefined,
        copyrightExpire: song.copyrightExpire ? dayjs(song.copyrightExpire) : undefined,
        auditStatus: song.auditStatus,
        status: song.status,
        sortWeight: song.sortWeight,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ copyrightStatus: 1, auditStatus: 0, status: 0, sortWeight: 0 });
    }
  }, [open, song, form]);

  // 标签是该歌曲的独立关联表，需单独拉取后回填
  useEffect(() => {
    if (!open || !song) return;
    const ids = (songTags.data ?? []).map((t) => t.id);
    form.setFieldsValue({ tagIds: ids });
  }, [open, song, songTags.data, form]);

  const handleOk = async (): Promise<void> => {
    const values = await form.validateFields();
    const payload: SongSaveReq = {
      name: values.name,
      subtitle: values.subtitle,
      artistId: values.artistId as Id,
      albumId: values.albumId,
      cover: values.cover,
      language: values.language,
      genre: values.genre,
      duration: values.duration,
      bpm: values.bpm,
      releaseDate: values.releaseDate ? values.releaseDate.format('YYYY-MM-DD') : undefined,
      era: values.era,
      isrc: values.isrc,
      audioStandard: values.audioStandard,
      audioHigh: values.audioHigh,
      audioLossless: values.audioLossless,
      copyrightStatus: values.copyrightStatus,
      copyrightOwner: values.copyrightOwner,
      copyrightExpire: values.copyrightExpire ? values.copyrightExpire.format('YYYY-MM-DD') : undefined,
      auditStatus: values.auditStatus,
      status: values.status,
      sortWeight: values.sortWeight,
      tagIds: values.tagIds,
      ...(song ? { version: song.version } : {}),
    };

    setSubmitting(true);
    try {
      if (song) {
        await songApi.update(song.id, payload);
        toastSuccess('歌曲已保存');
      } else {
        await songApi.create(payload);
        toastSuccess('歌曲已创建');
      }
      await queryClient.invalidateQueries({ queryKey: songKeys.all });
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === 10008) {
        form.setFields([{ name: 'name', errors: ['该歌曲已被他人修改，请关闭后重新打开再改'] }]);
      }
      // 其它错误请求层已 Toast
    } finally {
      setSubmitting(false);
    }
  };

  const artistOptions = (artists.data?.list ?? []).map((a) => ({ value: a.id, label: a.name }));
  const albumOptions = (albums.data?.list ?? [])
    .filter((a) => !selectedArtistId || a.artistId === selectedArtistId)
    .map((a) => ({ value: a.id, label: a.name }));
  const tagOptions = (tags.data?.list ?? []).map((t) => ({ value: t.id, label: t.name }));

  return (
    <Modal
      title={isEdit ? `编辑歌曲：${song?.name}` : '新增歌曲'}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="保存"
      cancelText="取消"
      width={860}
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="上架（status=1）需要审核通过且具备可用音质与版权；否则后端返回业务错误码并在下方提示。"
      />
      <Form form={form} layout="vertical" initialValues={{ copyrightStatus: 1, auditStatus: 0, status: 0 }}>
        <Tabs
          items={[
            {
              key: 'basic',
              label: '基本信息',
              forceRender: true,
              children: (
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="name" label="歌曲名" rules={[{ required: true, message: '请输入歌曲名' }, { max: 200 }]}>
                      <Input placeholder="必填" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="subtitle" label="副标题" rules={[{ max: 200 }]}>
                      <Input placeholder="选填" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="artistId" label="主歌手" rules={[{ required: true, message: '请选择歌手' }]}>
                      <Select
                        showSearch
                        optionFilterProp="label"
                        placeholder="选择歌手"
                        options={artistOptions}
                        loading={artists.isLoading}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="albumId" label="专辑" tooltip="专辑管理页面将在后续模块提供，此处只能选择已有专辑">
                      <Select
                        allowClear
                        showSearch
                        optionFilterProp="label"
                        placeholder="选择专辑（选填）"
                        options={albumOptions}
                        loading={albums.isLoading}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="genre" label="流派">
                      <Input placeholder="如：流行" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="language" label="语种">
                      <Input placeholder="如：国语" />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="duration" label="时长（秒）">
                      <InputNumber min={0} max={65535} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="bpm" label="BPM">
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="releaseDate" label="发行日期">
                      <DatePicker style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="era" label="年代">
                      <Input placeholder="如：00s" />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item
                      name="isrc"
                      label="ISRC"
                      rules={[{ pattern: /^[a-zA-Z0-9一-龥]{0,50}$/, message: '仅支持字母/数字/中文，最长 50' }]}
                    >
                      <Input placeholder="选填" />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="cover" label="封面地址">
                      <Input placeholder="URL 或 /media/... 相对路径" />
                    </Form.Item>
                  </Col>
                  <Col span={24}>
                    <Form.Item name="tagIds" label="标签" tooltip="全量覆盖：保存时以此处选择为准，最多 10 个">
                      <Select
                        mode="multiple"
                        allowClear
                        optionFilterProp="label"
                        placeholder="选择标签"
                        options={tagOptions}
                        loading={tags.isLoading || songTags.isLoading}
                        maxTagCount={10}
                      />
                    </Form.Item>
                  </Col>
                </Row>
              ),
            },
            {
              key: 'audio',
              label: '音频与版权',
              forceRender: true,
              children: (
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="audioStandard" label="标准音质地址（128kbps）">
                      <Input placeholder="/media/demo/demo-01.wav" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="audioHigh" label="高清音质地址（320kbps）">
                      <Input placeholder="选填" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="audioLossless" label="无损音质地址（flac）">
                      <Input placeholder="选填" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="copyrightStatus" label="版权状态">
                      <Select
                        options={[
                          { value: 0, label: '未授权' },
                          { value: 1, label: '已授权' },
                          { value: 2, label: '已过期' },
                          { value: 3, label: '受限' },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="copyrightOwner" label="版权方">
                      <Input placeholder="选填" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="copyrightExpire" label="版权到期日">
                      <DatePicker style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                </Row>
              ),
            },
            {
              key: 'publish',
              label: '发布状态',
              forceRender: true,
              children: (
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item name="status" label="上架状态">
                      <Select
                        options={[
                          { value: 0, label: '下架' },
                          { value: 1, label: '上架' },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="auditStatus" label="审核状态">
                      <Select
                        options={[
                          { value: 0, label: '待审核' },
                          { value: 1, label: '审核通过' },
                          { value: 2, label: '审核驳回' },
                        ]}
                      />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="sortWeight" label="排序权重" tooltip="数值越大越靠前">
                      <InputNumber min={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                </Row>
              ),
            },
          ]}
        />
      </Form>
    </Modal>
  );
}
