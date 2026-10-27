import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Modal, Select, Space, Typography } from 'antd';
import { artistApi, artistKeys } from '@/api/catalog';
import { recommendApi, recommendKeys, type QuestionnaireVO } from '@/api/recommend';
import { toastSuccess } from '@/utils/feedback';

/**
 * 冷启动口味问卷（模块 08 BR-17-048）。
 *
 * 风格 ≤3、语种 ≤6、歌手 ≤5，提交后服务端立即清空每日/猜你喜欢缓存。
 * 选项白名单由服务端下发，前端不硬编码——新增风格时不必改前端。
 */

interface Props {
  open: boolean;
  onClose: () => void;
}

export function QuestionnaireModal({ open, onClose }: Props): JSX.Element {
  const queryClient = useQueryClient();
  const [styles, setStyles] = useState<string[]>([]);
  const [languages, setLanguages] = useState<string[]>([]);
  const [artists, setArtists] = useState<string[]>([]);
  const [mood, setMood] = useState<string | undefined>(undefined);

  const questionnaire = useQuery({
    queryKey: recommendKeys.questionnaire(),
    queryFn: recommendApi.questionnaire,
    enabled: open,
  });

  // 热门歌手作为可选池：问卷不提供搜索，避免把冷启动流程做成一个搜索页
  const hotArtists = useQuery({
    queryKey: artistKeys.list({ pageSize: 50, sortBy: 'hotScore', sortOrder: 'desc' }),
    queryFn: () => artistApi.list({ pageSize: 50, sortBy: 'hotScore', sortOrder: 'desc' }),
    enabled: open,
  });

  useEffect(() => {
    if (!open) return;
    const current = questionnaire.data?.current;
    setStyles(current?.styles ?? []);
    setLanguages(current?.languages ?? []);
    setArtists((current?.artists ?? []).map((id) => String(id)));
    setMood(current?.mood ?? undefined);
  }, [open, questionnaire.data]);

  const save = useMutation({
    mutationFn: () =>
      recommendApi.saveQuestionnaire({
        styles,
        languages,
        artists,
        mood: mood ?? null,
      }),
    onSuccess: (data: QuestionnaireVO) => {
      queryClient.setQueryData(recommendKeys.questionnaire(), data);
      // 问卷直接改变画像权重：每日推荐与猜你喜欢都必须重算
      void queryClient.invalidateQueries({ queryKey: recommendKeys.all });
      toastSuccess('口味已保存，推荐已按新偏好更新');
      onClose();
    },
  });

  const loading = questionnaire.isLoading;
  const options = questionnaire.data;

  return (
    <Modal
      title="告诉我们你的口味"
      open={open}
      onCancel={onClose}
      destroyOnHidden
      confirmLoading={save.isPending}
      onOk={() => save.mutate()}
      okText="保存并更新推荐"
      cancelText="稍后再说"
      width={560}
    >
      {loading ? (
        <Typography.Text type="secondary">加载中…</Typography.Text>
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="行为数据不足 5 条时，推荐会以问卷偏好 + 热门混排；听的越多，推荐越准。"
          />
          <div>
            <Typography.Text strong>喜欢的风格（最多 3 个）</Typography.Text>
            <Select
              mode="multiple"
              allowClear
              maxCount={3}
              style={{ width: '100%', marginTop: 6 }}
              placeholder="例如：流行、摇滚"
              value={styles}
              onChange={setStyles}
              options={(options?.styleOptions ?? []).map((value) => ({ value, label: value }))}
            />
          </div>
          <div>
            <Typography.Text strong>喜欢的语种（最多 6 个）</Typography.Text>
            <Select
              mode="multiple"
              allowClear
              maxCount={6}
              style={{ width: '100%', marginTop: 6 }}
              placeholder="例如：国语、英语"
              value={languages}
              onChange={setLanguages}
              options={(options?.languageOptions ?? []).map((value) => ({ value, label: value }))}
            />
          </div>
          <div>
            <Typography.Text strong>常听的歌手（最多 5 位）</Typography.Text>
            <Select
              mode="multiple"
              allowClear
              maxCount={5}
              showSearch
              optionFilterProp="label"
              loading={hotArtists.isLoading}
              style={{ width: '100%', marginTop: 6 }}
              placeholder="从热门歌手里选"
              value={artists}
              onChange={setArtists}
              options={(hotArtists.data?.list ?? []).map((artist) => ({
                value: String(artist.id),
                label: artist.name,
              }))}
            />
          </div>
          <div>
            <Typography.Text strong>此刻的心情</Typography.Text>
            <Select
              allowClear
              style={{ width: '100%', marginTop: 6 }}
              placeholder="可不选"
              value={mood}
              onChange={setMood}
              options={Object.entries(options?.moodOptions ?? {}).map(([value, label]) => ({ value, label }))}
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            也可以随时在「推荐 → 口味偏好」里修改。
          </Typography.Text>
        </Space>
      )}
    </Modal>
  );
}
