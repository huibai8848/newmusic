import { useEffect } from 'react';
import { Alert, Button, Input, Modal, Space, Spin, Typography } from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { socialApi } from '@/api/social';
import { copyText } from '@/utils/clipboard';
import { toastSuccess, toastWarning } from '@/utils/feedback';
import type { Id } from '@/types/api';
import type { ShareTargetType } from '@/types/social';

/**
 * 分享弹窗：进入时生成一条短链（30 天有效），提供短链与目标页两种复制。
 *
 * 生成失败不伪造链接；错误由请求层统一提示，这里只展示失败态。
 */
export function ShareDialog({
  open,
  targetType,
  targetId,
  onClose,
}: {
  open: boolean;
  targetType: ShareTargetType;
  targetId: Id;
  onClose: () => void;
}): JSX.Element {
  const mutation = useMutation({
    mutationFn: () => socialApi.createShare(targetType, targetId),
  });

  useEffect(() => {
    if (open) mutation.mutate();
    // 每次打开只生成一次；mutation 对象每次渲染都是新引用，不能放进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, targetType, targetId]);

  const data = mutation.data;

  const copy = async (text: string, label: string): Promise<void> => {
    const ok = await copyText(text);
    if (ok) toastSuccess(`${label}已复制`);
    else toastWarning('复制失败，请手动选择文本复制');
  };

  return (
    <Modal open={open} title="分享" footer={null} onCancel={onClose} destroyOnHidden>
      {mutation.isPending ? (
        <div style={{ textAlign: 'center', padding: 32 }}>
          <Spin tip="生成分享链接…" />
        </div>
      ) : mutation.isError || !data ? (
        <Alert type="error" showIcon message="分享链接生成失败，请稍后重试" />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary">短链</Typography.Text>
            <Space.Compact style={{ width: '100%', marginTop: 6 }}>
              <Input readOnly value={data.shortUrl} onFocus={(e) => e.currentTarget.select()} />
              <Button icon={<CopyOutlined />} onClick={() => void copy(data.shortUrl, '短链')}>
                复制
              </Button>
            </Space.Compact>
          </div>
          <div>
            <Typography.Text type="secondary">页面地址</Typography.Text>
            <Space.Compact style={{ width: '100%', marginTop: 6 }}>
              <Input readOnly value={data.targetUrl} onFocus={(e) => e.currentTarget.select()} />
              <Button icon={<CopyOutlined />} onClick={() => void copy(data.targetUrl, '页面地址')}>
                复制
              </Button>
            </Space.Compact>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            短链有效期至 {data.expireTime?.slice(0, 10) ?? '—'}；访问会计入分享点击量。
          </Typography.Text>
        </Space>
      )}
    </Modal>
  );
}
