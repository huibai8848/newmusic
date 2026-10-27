import { useEffect } from 'react';
import { Form, Input, Modal, Select, Spin } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { socialApi, socialKeys } from '@/api/social';
import { toastSuccess } from '@/utils/feedback';
import type { Id } from '@/types/api';
import type { ReportTargetType } from '@/types/social';

interface ReportForm {
  reasonType: string;
  reason?: string;
  evidence?: string;
}

/**
 * 举报弹窗（FR-19-015）。
 *
 * 理由字典来自后端，前端不复制一份 17 项常量——否则后台新增理由时前端永远不显示。
 * 证据只接受图片 URL（后端按 URL 列表校验），这里把多行文本拆成数组。
 */
export function ReportDialog({
  open,
  targetType,
  targetId,
  targetName,
  onClose,
}: {
  open: boolean;
  targetType: ReportTargetType;
  targetId: Id;
  targetName?: string;
  onClose: () => void;
}): JSX.Element {
  const [form] = Form.useForm<ReportForm>();
  const queryClient = useQueryClient();

  const reasons = useQuery({
    queryKey: socialKeys.reportReasons,
    queryFn: socialApi.reportReasons,
    enabled: open,
    staleTime: 10 * 60_000,
  });

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const mutation = useMutation({
    mutationFn: (values: ReportForm) => {
      const urls = (values.evidence ?? '')
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean)
        .slice(0, 6);
      return socialApi.createReport({
        targetType,
        targetId,
        reasonType: values.reasonType,
        reason: values.reason?.trim() || undefined,
        evidenceUrls: urls.length > 0 ? urls : undefined,
      });
    },
    onSuccess: () => {
      toastSuccess('举报已提交，我们会尽快处理');
      void queryClient.invalidateQueries({ queryKey: socialKeys.myReports() });
      onClose();
    },
  });

  const options = (reasons.data ?? []).map((reason) => ({
    value: reason.code,
    label: `${reason.category} · ${reason.label}`,
  }));

  return (
    <Modal
      open={open}
      title="举报"
      okText="提交举报"
      cancelText="取消"
      confirmLoading={mutation.isPending}
      onCancel={onClose}
      onOk={() => {
        form.submit();
      }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)} preserve={false}>
        {targetName ? (
          <div style={{ marginBottom: 12, color: 'var(--color-text-secondary)', fontSize: 13 }}>
            举报对象：{targetName}
          </div>
        ) : null}
        <Form.Item
          name="reasonType"
          label="举报理由"
          rules={[{ required: true, message: '请选择举报理由' }]}
        >
          <Select
            placeholder="请选择"
            options={options}
            loading={reasons.isLoading}
            notFoundContent={reasons.isError ? '理由加载失败，请重试' : <Spin size="small" />}
          />
        </Form.Item>
        <Form.Item
          name="reason"
          label="补充说明"
          rules={[{ max: 500, message: '补充说明不能超过 500 字' }]}
        >
          <Input.TextArea rows={3} maxLength={500} showCount placeholder="选填，请描述具体问题" />
        </Form.Item>
        <Form.Item
          name="evidence"
          label="证据图片 URL"
          extra="每行一个，最多 6 个，需以 http:// 或 https:// 开头"
        >
          <Input.TextArea rows={2} placeholder="https://example.com/evidence-1.png" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
