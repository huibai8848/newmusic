import { useEffect, useState } from 'react';
import { Alert, Input, Modal, Typography } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { conversationApi } from '@/api/socialUpgrade';
import { toastSuccess } from '@/utils/feedback';
import type { UserBrief } from '@/types/social';
import type { ConversationItemVO } from '@/types/socialUpgrade';
import { UserMultiSelect } from './UserMultiSelect';

/**
 * 创建群聊（FR-25-010）。
 *
 * 后端要求「创建者 + 至少 2 人」且总数 ≤20；这里只做和约束一致的本地校验，
 * 人数上限仍以后端 GROUP_MEMBER_LIMIT 为准。
 */
export function GroupCreateDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (conversation: ConversationItemVO) => void;
}): JSX.Element {
  const [name, setName] = useState('');
  const [members, setMembers] = useState<UserBrief[]>([]);

  useEffect(() => {
    if (open) {
      setName('');
      setMembers([]);
    }
  }, [open]);

  const mutation = useMutation({
    mutationFn: () =>
      conversationApi.createGroup({
        name: name.trim() || undefined,
        memberIds: members.map((user) => user.id),
      }),
    onSuccess: (conversation) => {
      toastSuccess('群聊已创建');
      onCreated(conversation);
      onClose();
    },
  });

  const total = members.length + 1;
  const invalid = members.length < 2 || total > 20;

  return (
    <Modal
      open={open}
      title="创建群聊"
      okText="创建"
      cancelText="取消"
      confirmLoading={mutation.isPending}
      okButtonProps={{ disabled: invalid }}
      onCancel={onClose}
      onOk={() => mutation.mutate()}
      destroyOnHidden
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div>
          <Typography.Text strong>群名称</Typography.Text>
          <Input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="选填，最多 30 个字"
            maxLength={30}
            showCount
            style={{ marginTop: 6 }}
          />
        </div>
        <div>
          <Typography.Text strong>群成员</Typography.Text>
          <div style={{ marginTop: 6 }}>
            <UserMultiSelect
              value={members}
              onChange={setMembers}
              maxCount={19}
              placeholder="搜索昵称，至少选择 2 人"
            />
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            已选 {total}/20 人（含你自己）
          </Typography.Text>
        </div>
        {invalid ? (
          <Alert
            type="info"
            showIcon
            message="群聊需要 3~20 人，请再选择一些成员"
          />
        ) : null}
      </div>
    </Modal>
  );
}
