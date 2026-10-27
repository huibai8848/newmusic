import { useEffect, useState } from 'react';
import {
  Avatar,
  Button,
  Drawer,
  Empty,
  List,
  Modal,
  Popconfirm,
  Space,
  Tag,
  Typography,
} from 'antd';
import { LogoutOutlined, UserAddOutlined, UserDeleteOutlined, UserOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { ListSkeleton } from '@/components/common/PageLoading';
import { groupRoleLabel } from '@/utils/socialRules';
import { useUserStore } from '@/stores/userStore';
import { toastSuccess } from '@/utils/feedback';
import { mediaUrl } from '@/utils/format';
import type { Id } from '@/types/api';
import type { ConversationItemVO } from '@/types/socialUpgrade';
import type { UserBrief } from '@/types/social';
import { UserMultiSelect } from './UserMultiSelect';

/**
 * 群成员管理（群主可移除/解散，成员可邀请/退出）。
 *
 * 后端没有「改群名/群头像」接口，抽屉里不做好看但无效的入口。
 */
export function GroupMembersDrawer({
  open,
  conversation,
  onClose,
  onLeft,
}: {
  open: boolean;
  conversation: ConversationItemVO | null;
  onClose: () => void;
  onLeft: () => void;
}): JSX.Element {
  const queryClient = useQueryClient();
  const selfId = useUserStore((s) => s.user?.id);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [invitees, setInvitees] = useState<UserBrief[]>([]);

  const conversationId = conversation?.id ?? '';
  const query = useQuery({
    queryKey: socialUpgradeKeys.groupMembers(conversationId),
    queryFn: () => conversationApi.members(conversationId),
    enabled: open && Boolean(conversationId),
  });

  useEffect(() => {
    if (!open) {
      setInviteOpen(false);
      setInvitees([]);
    }
  }, [open]);

  const members = query.data ?? [];
  const self = members.find((member) => String(member.userId) === String(selfId));
  const isOwner = Boolean(
    self?.owner ||
      (conversation?.ownerId && selfId && String(conversation.ownerId) === String(selfId)),
  );

  const invalidate = (): void => {
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.groupMembers(conversationId) });
    void queryClient.invalidateQueries({ queryKey: ['social-upgrade', 'conversations'] });
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.conversationUnread });
  };

  const inviteMutation = useMutation({
    mutationFn: () => conversationApi.addMembers(conversationId, invitees.map((user) => user.id)),
    onSuccess: () => {
      toastSuccess('已邀请入群');
      setInviteOpen(false);
      setInvitees([]);
      invalidate();
    },
  });

  const removeMutation = useMutation({
    mutationFn: (userId: Id) => conversationApi.removeMember(conversationId, userId),
    onSuccess: () => {
      toastSuccess('已移出群聊');
      invalidate();
    },
  });

  const quitMutation = useMutation({
    mutationFn: () => conversationApi.quit(conversationId),
    onSuccess: () => {
      toastSuccess('已退出群聊');
      invalidate();
      onClose();
      onLeft();
    },
  });

  const dissolveMutation = useMutation({
    mutationFn: () => conversationApi.dissolve(conversationId),
    onSuccess: () => {
      toastSuccess('群聊已解散');
      invalidate();
      onClose();
      onLeft();
    },
  });

  const existingIds = members.map((member) => member.userId);
  const memberCount = conversation?.memberCount ?? members.length;

  return (
    <>
      <Drawer
        open={open}
        title={`群成员${memberCount > 0 ? `（${memberCount}）` : ''}`}
        width={400}
        onClose={onClose}
        extra={
          <Button
            size="small"
            icon={<UserAddOutlined />}
            onClick={() => setInviteOpen(true)}
            disabled={members.length >= 20}
          >
            邀请
          </Button>
        }
      >
        {query.isLoading ? (
          <ListSkeleton rows={4} />
        ) : query.isError ? (
          <Empty description="成员列表加载失败" />
        ) : members.length === 0 ? (
          <Empty description="暂无成员" />
        ) : (
          <List
            dataSource={members}
            renderItem={(member) => (
              <List.Item
                actions={
                  isOwner && !member.owner && String(member.userId) !== String(selfId)
                    ? [
                        <Popconfirm
                          key="remove"
                          title={`将 ${member.nickname ?? '该成员'} 移出群聊？`}
                          okText="移出"
                          okButtonProps={{ danger: true }}
                          cancelText="取消"
                          onConfirm={() => removeMutation.mutate(member.userId)}
                        >
                          <Button
                            type="text"
                            danger
                            size="small"
                            icon={<UserDeleteOutlined />}
                            loading={removeMutation.isPending}
                          >
                            移出
                          </Button>
                        </Popconfirm>,
                      ]
                    : undefined
                }
              >
                <List.Item.Meta
                  avatar={
                    <Avatar src={mediaUrl(member.avatar)} icon={<UserOutlined />} />
                  }
                  title={
                    <Space size={6}>
                      <span>{member.nickname ?? '用户'}</span>
                      {member.owner ? <Tag color="gold">群主</Tag> : null}
                      {String(member.userId) === String(selfId) ? <Tag>我</Tag> : null}
                    </Space>
                  }
                  description={groupRoleLabel(member.role)}
                />
              </List.Item>
            )}
          />
        )}

        <div style={{ marginTop: 24, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          {isOwner ? (
            <Popconfirm
              title="解散群聊？"
              description="所有成员都会被移出，消息记录仅保留用于审计。"
              okText="解散"
              okButtonProps={{ danger: true }}
              cancelText="取消"
              onConfirm={() => {
                if (dissolveMutation.isPending) return;
                dissolveMutation.mutate();
              }}
            >
              <Button danger loading={dissolveMutation.isPending}>
                解散群聊
              </Button>
            </Popconfirm>
          ) : (
            <Popconfirm
              title="退出群聊？"
              description="退出后将不再接收该群消息。"
              okText="退出"
              okButtonProps={{ danger: true }}
              cancelText="取消"
              onConfirm={() => quitMutation.mutate()}
            >
              <Button icon={<LogoutOutlined />} loading={quitMutation.isPending}>
                退出群聊
              </Button>
            </Popconfirm>
          )}
          {isOwner ? (
            <Typography.Text type="secondary" style={{ fontSize: 12, alignSelf: 'center' }}>
              群主需解散群聊后才能退出
            </Typography.Text>
          ) : null}
        </div>
      </Drawer>

      <Modal
        open={inviteOpen}
        title="邀请成员"
        okText="邀请"
        cancelText="取消"
        okButtonProps={{ disabled: invitees.length === 0 }}
        confirmLoading={inviteMutation.isPending}
        onCancel={() => setInviteOpen(false)}
        onOk={() => inviteMutation.mutate()}
        destroyOnHidden
      >
        <UserMultiSelect
          value={invitees}
          onChange={setInvitees}
          excludeIds={existingIds}
          maxCount={Math.max(1, 20 - members.length)}
          placeholder="搜索昵称邀请入群"
        />
        {members.length >= 20 ? (
          <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
            群成员已达上限 20 人
          </Typography.Text>
        ) : null}
      </Modal>
    </>
  );
}
