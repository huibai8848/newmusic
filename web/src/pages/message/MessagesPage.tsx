import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Avatar,
  Badge,
  Button,
  Dropdown,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Segmented,
  Tag,
  Typography,
  type MenuProps,
} from 'antd';
import {
  ArrowDownOutlined,
  BellOutlined,
  CloseOutlined,
  CommentOutlined,
  DeleteOutlined,
  EllipsisOutlined,
  LockOutlined,
  PushpinOutlined,
  RollbackOutlined,
  SendOutlined,
  ShareAltOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import dayjs from 'dayjs';
import { socialApi } from '@/api/social';
import { conversationApi, socialUpgradeKeys } from '@/api/socialUpgrade';
import { GroupCreateDialog } from '@/components/social/GroupCreateDialog';
import { GroupMembersDrawer } from '@/components/social/GroupMembersDrawer';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { subscribeSocialMessageEvents } from '@/stores/wsStore';
import { toastSuccess, toastWarning } from '@/utils/feedback';
import { mediaUrl } from '@/utils/format';
import { canRevokeMessage, flattenMessagePages } from '@/utils/socialRules';
import type { Id } from '@/types/api';
import type {
  ConversationItemVO,
  ConversationSearchHit,
  ConversationType,
  MessageQuote,
  PrivateMessageVO,
} from '@/types/socialUpgrade';
import styles from './MessagesPage.module.less';

const PAGE_SIZE = 30;
const MESSAGE_PAGE_SIZE = 50;

type MessagePages = InfiniteData<PrivateMessageVO[], Id | null>;

interface Selection {
  id: Id | '';
  type: ConversationType;
  otherUserId: Id | null;
  nickname: string;
  avatar: string | null;
  muted: boolean;
  /** 群聊管理需要原始会话对象；新建单聊时为 null */
  raw: ConversationItemVO | null;
}

function fromConversation(conversation: ConversationItemVO): Selection {
  return {
    id: conversation.id,
    type: conversation.type,
    otherUserId: conversation.otherUserId,
    nickname: conversation.name ?? (conversation.type === 2 ? '群聊' : '用户'),
    avatar: conversation.avatar,
    muted: conversation.muted,
    raw: conversation,
  };
}

function appendMessage(data: MessagePages | undefined, message: PrivateMessageVO): MessagePages {
  if (!data || data.pages.length === 0) {
    return { pages: [[message]], pageParams: [null] };
  }
  const pages = data.pages.map((page, index) => {
    if (index !== 0) return page;
    if (page.some((item) => item.id === message.id)) return page;
    return [...page, message];
  });
  return { ...data, pages };
}

function conversationTime(value: string | null): string {
  if (!value) return '';
  const time = dayjs(value);
  if (!time.isValid()) return '';
  return time.isSame(dayjs(), 'day') ? time.format('HH:mm') : time.format('MM-DD');
}

/**
 * 私信页（FR-19-011 / FR-25-010，模块 16 原地升级）。
 *
 * - 会话列表切到 `/conversations`，同时包含单聊与群聊、置顶/归档/免打扰；
 * - 消息固定走 `/conversations/{id}/messages`，`cursor` 是当前最旧一条消息 ID；
 * - 分页第一页是**最新**一页，渲染前必须 `flattenMessagePages`（倒序页 + 去重 + 正序）；
 * - 引用/转发/群成员管理都在本页完成；群聊不展示撤回（新控制器未暴露）。
 */
export function MessagesPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const archived = searchParams.get('archived') === '1';

  const [selected, setSelected] = useState<Selection | null>(null);
  const [text, setText] = useState('');
  const [quote, setQuote] = useState<MessageQuote | null>(null);
  const [searchText, setSearchText] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [highlightId, setHighlightId] = useState<Id | null>(null);
  const [forwarding, setForwarding] = useState<PrivateMessageVO | null>(null);
  const [groupCreateOpen, setGroupCreateOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);

  const selectedIdRef = useRef<Id | ''>('');
  const atBottomRef = useRef(true);
  const prependRef = useRef<{ height: number; top: number } | null>(null);
  const pendingScrollRef = useRef<Id | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const keyword = useDebouncedValue(searchText.trim(), 250);
  const selectedId = selected?.id ?? '';

  const conversationsQuery = useInfiniteQuery({
    queryKey: socialUpgradeKeys.conversations(archived),
    queryFn: ({ pageParam }) => conversationApi.list(pageParam, PAGE_SIZE, archived),
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNext ? last.pageNum + 1 : undefined),
  });

  const unreadQuery = useQuery({
    queryKey: socialUpgradeKeys.conversationUnread,
    queryFn: conversationApi.unreadCount,
  });

  const searchQuery = useQuery({
    queryKey: socialUpgradeKeys.conversationSearch(keyword),
    queryFn: () => conversationApi.search(keyword, 10),
    enabled: keyword.length > 0,
  });

  const messagesQuery = useInfiniteQuery({
    queryKey: socialUpgradeKeys.conversationMessages(selectedId),
    queryFn: ({ pageParam }) =>
      conversationApi.messages(selectedId, pageParam, MESSAGE_PAGE_SIZE),
    initialPageParam: null as Id | null,
    getNextPageParam: (lastPage) =>
      lastPage.length < MESSAGE_PAGE_SIZE ? undefined : lastPage[0]?.id,
    enabled: Boolean(selectedId),
    staleTime: 15_000,
  });

  const conversationList = useMemo(() => {
    const seen = new Set<string>();
    const list: ConversationItemVO[] = [];
    for (const page of conversationsQuery.data?.pages ?? []) {
      for (const item of page.list) {
        if (seen.has(item.id)) continue;
        seen.add(item.id);
        list.push(item);
      }
    }
    return list;
  }, [conversationsQuery.data]);

  const messages = useMemo(
    () => flattenMessagePages(messagesQuery.data?.pages ?? []),
    [messagesQuery.data],
  );

  useEffect(() => {
    selectedIdRef.current = selected?.id ?? '';
  }, [selected?.id]);

  const invalidateConversations = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['social-upgrade', 'conversations'] });
    void queryClient.invalidateQueries({ queryKey: socialUpgradeKeys.conversationUnread });
  };

  const markRead = (conversationId: Id): void => {
    void conversationApi
      .read(conversationId)
      .then(invalidateConversations)
      .catch(() => {
        // 会话可能刚被解散/移除，已读失败不需要打断聊天
      });
  };

  const clearSelection = (): void => {
    setSelected(null);
    setText('');
    setQuote(null);
    setShowNew(false);
    setSearchParams(archived ? { archived: '1' } : {}, { replace: true });
  };

  const openConversation = (conversation: ConversationItemVO): void => {
    setSelected(fromConversation(conversation));
    setSearchText('');
    // 左侧列表切到该会话所属的视图（归档会话 / 普通会话）
    setSearchParams(
      conversation.archived
        ? { archived: '1', conversationId: conversation.id }
        : { conversationId: conversation.id },
      { replace: true },
    );
    if (conversation.unread > 0) markRead(conversation.id);
  };

  // 深链：?conversationId= 直接打开；?userId= 没有现成会话时进入「新会话」状态
  useEffect(() => {
    if (selected || conversationsQuery.isLoading) return;
    const conversationId = searchParams.get('conversationId');
    if (conversationId) {
      const found = conversationList.find((item) => String(item.id) === conversationId);
      if (found) {
        setSelected(fromConversation(found));
        if (found.unread > 0) markRead(found.id);
      }
      return;
    }
    const userId = searchParams.get('userId');
    if (userId) {
      const found = conversationList.find((item) => String(item.otherUserId) === userId);
      if (found) {
        setSelected(fromConversation(found));
        if (found.unread > 0) markRead(found.id);
        return;
      }
      setSelected({
        id: '',
        type: 1,
        otherUserId: userId,
        nickname: searchParams.get('nickname') ?? '用户',
        avatar: null,
        muted: false,
        raw: null,
      });
    }
  }, [conversationList, conversationsQuery.isLoading, searchParams, selected]);

  // 切换会话时清空输入与引用，避免把对上一个人说的话发到新会话
  useEffect(() => {
    setText('');
    setQuote(null);
    setShowNew(false);
    atBottomRef.current = true;
  }, [selected?.id]);

  // WS：新消息/已读/撤回失效对应查询；群聊被移除/解散时清空当前会话
  useEffect(() => {
    return subscribeSocialMessageEvents((event) => {
      if (event.type === 'group.removed' || event.type === 'group.dissolved') {
        invalidateConversations();
        const conversationId = String(event.data.conversationId ?? '');
        if (conversationId && selectedIdRef.current === conversationId) {
          setSelected(null);
          setText('');
          setQuote(null);
          setSearchParams({}, { replace: true });
          toastWarning(event.type === 'group.dissolved' ? '群聊已解散' : '你已被移出该群聊');
        }
        return;
      }

      invalidateConversations();
      const conversationId = String(event.data.conversationId ?? '');
      if (!conversationId || conversationId !== selectedIdRef.current) return;
      void queryClient.invalidateQueries({
        queryKey: socialUpgradeKeys.conversationMessages(conversationId),
      });
      if (event.type === 'msg.new') {
        markRead(conversationId);
        if (!atBottomRef.current) setShowNew(true);
      }
    });
    // markRead 只发请求并失效查询，不引入额外闭包状态
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryClient, setSearchParams]);

  const loadOlder = (): void => {
    const el = listRef.current;
    if (!el || messagesQuery.isFetchingNextPage) return;
    prependRef.current = { height: el.scrollHeight, top: el.scrollTop };
    void messagesQuery.fetchNextPage().catch(() => {
      prependRef.current = null;
    });
  };

  // 加载更早消息时保住滚动位置；在底部时新消息自动贴底
  useLayoutEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const pending = pendingScrollRef.current;
    if (pending) {
      pendingScrollRef.current = null;
      const node = document.getElementById(`msg-${pending}`);
      node?.scrollIntoView({ block: 'center' });
      return;
    }
    const prepend = prependRef.current;
    if (prepend) {
      prependRef.current = null;
      el.scrollTop = el.scrollHeight - prepend.height + prepend.top;
      return;
    }
    if (atBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages.length, selected?.id, messagesQuery.isLoading]);

  const handleScroll = (): void => {
    const el = listRef.current;
    if (!el) return;
    const bottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    atBottomRef.current = bottom;
    if (bottom) setShowNew(false);
    if (el.scrollTop < 40 && messagesQuery.hasNextPage && !messagesQuery.isFetchingNextPage) {
      loadOlder();
    }
  };

  const sendMutation = useMutation({
    mutationFn: async () => {
      if (!selected) throw new Error('尚未选择会话');
      let conversation = selected.raw;
      let conversationId = selected.id;
      if (!conversationId) {
        if (!selected.otherUserId) throw new Error('缺少私信对象');
        conversation = await conversationApi.create(selected.otherUserId);
        conversationId = conversation.id;
      }
      const message = await conversationApi.send(conversationId, {
        msgType: 'TEXT',
        content: text.trim(),
        quoteMsgId: quote?.messageId,
      });
      return { message, conversation };
    },
    onSuccess: ({ message, conversation }) => {
      const conversationId = message.conversationId;
      setText('');
      setQuote(null);
      setShowNew(false);
      atBottomRef.current = true;
      queryClient.setQueryData(
        socialUpgradeKeys.conversationMessages(conversationId),
        (old: MessagePages | undefined) => appendMessage(old, message),
      );
      // 发送期间用户可能已切到别的会话：只把「新会话」占位替换成真实会话
      setSelected((current) => {
        if (!current || current.id !== '') return current;
        return {
          ...current,
          id: conversationId,
          type: conversation?.type ?? current.type,
          otherUserId: conversation?.otherUserId ?? current.otherUserId,
          nickname: conversation?.name ?? current.nickname,
          avatar: conversation?.avatar ?? current.avatar,
          muted: conversation?.muted ?? current.muted,
          raw: conversation ?? current.raw,
        };
      });
      if (selectedIdRef.current === '') {
        setSearchParams({ conversationId }, { replace: true });
      }
      invalidateConversations();
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (id: Id) => socialApi.revokeMessage(id),
    onSuccess: () => {
      toastSuccess('消息已撤回');
      if (selectedId) {
        void queryClient.invalidateQueries({
          queryKey: socialUpgradeKeys.conversationMessages(selectedId),
        });
      }
      invalidateConversations();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: Id) => socialApi.deleteMessage(id),
    onSuccess: () => {
      if (selectedId) {
        void queryClient.invalidateQueries({
          queryKey: socialUpgradeKeys.conversationMessages(selectedId),
        });
      }
    },
  });

  const settingMutation = useMutation({
    mutationFn: async ({
      conversation,
      action,
    }: {
      conversation: ConversationItemVO;
      action: 'pin' | 'mute' | 'read' | 'archive';
    }) => {
      switch (action) {
        case 'pin':
          return conversationApi.pin(conversation.id, !conversation.pinned);
        case 'mute':
          return conversationApi.mute(conversation.id, !conversation.muted);
        case 'archive':
          return conversationApi.archive(conversation.id, !conversation.archived);
        case 'read':
        default:
          return conversationApi.read(conversation.id);
      }
    },
    onSuccess: (_data, { conversation, action }) => {
      invalidateConversations();
      if (action === 'archive') {
        // 归档/取消归档的是当前打开的会话时才清空右侧聊天
        if (selectedIdRef.current === conversation.id) clearSelection();
        toastSuccess(archived ? '已取消归档' : '已归档');
        return;
      }
      const label =
        action === 'pin'
          ? conversation.pinned
            ? '已取消置顶'
            : '已置顶'
          : action === 'mute'
            ? conversation.muted
              ? '已关闭免打扰'
              : '已开启免打扰'
            : '已标记为已读';
      toastSuccess(label);
    },
  });

  const forwardMutation = useMutation({
    mutationFn: ({ targetId, messageId }: { targetId: Id; messageId: Id }) =>
      conversationApi.forward(targetId, messageId),
    onSuccess: (_message, { targetId }) => {
      toastSuccess('已转发');
      setForwarding(null);
      invalidateConversations();
      if (targetId === selectedId) {
        void queryClient.invalidateQueries({
          queryKey: socialUpgradeKeys.conversationMessages(targetId),
        });
      }
    },
  });

  const jumpToMessage = async (hit: ConversationSearchHit): Promise<void> => {
    const target = conversationList.find((item) => String(item.id) === String(hit.conversationId));
    const key = socialUpgradeKeys.conversationMessages(hit.conversationId);
    await queryClient.cancelQueries({ queryKey: key });

    try {
      const pages: PrivateMessageVO[][] = [];
      let cursor: Id | null = null;
      let found = false;
      // 搜索只给消息 ID，没有页码；按 beforeId 最多回翻 5 页
      for (let i = 0; i < 5; i += 1) {
        const batch = await conversationApi.messages(hit.conversationId, cursor, MESSAGE_PAGE_SIZE);
        pages.push(batch);
        if (batch.some((item) => item.id === hit.messageId)) {
          found = true;
          break;
        }
        if (batch.length < MESSAGE_PAGE_SIZE) break;
        cursor = batch[0]?.id ?? null;
        if (!cursor) break;
      }
      const pageParams: (Id | null)[] = pages.map((_, index) =>
        index === 0 ? null : (pages[index - 1][0]?.id ?? null),
      );
      queryClient.setQueryData(key, { pages, pageParams });
      pendingScrollRef.current = found ? hit.messageId : null;
      setHighlightId(found ? hit.messageId : null);
      setSelected(
        target
          ? fromConversation(target)
          : {
              id: hit.conversationId,
              type: 1,
              otherUserId: null,
              nickname: hit.conversationName ?? '会话',
              avatar: null,
              muted: false,
              raw: null,
            },
      );
      setSearchText('');
      setSearchParams({ conversationId: hit.conversationId }, { replace: true });
      markRead(hit.conversationId);
      if (found) {
        window.setTimeout(() => setHighlightId(null), 2400);
      } else {
        toastWarning('消息在更早记录中，已打开最近消息');
      }
    } catch {
      // 请求层已提示具体错误
    }
  };

  const switchArchived = (value: number): void => {
    setSelected(null);
    setText('');
    setQuote(null);
    setSearchParams(value === 1 ? { archived: '1' } : {}, { replace: true });
  };

  const totalUnread = unreadQuery.data ?? 0;
  const searching = keyword.length > 0;
  const isGroup = selected?.type === 2;

  return (
    <div className="ml-page">
      <div className={styles.head}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          消息
          {totalUnread > 0 ? (
            <Badge count={totalUnread} overflowCount={99} style={{ marginLeft: 12 }} />
          ) : null}
        </Typography.Title>
        <Button icon={<LockOutlined />} onClick={() => navigate('/settings')}>
          隐私设置
        </Button>
      </div>

      <div className={styles.layout}>
        <aside className={styles.sidebar}>
          <div className={styles.sidebarTools}>
            <Segmented
              block
              size="small"
              value={archived ? 1 : 0}
              onChange={(value) => switchArchived(value as number)}
              options={[
                { label: '会话', value: 0 },
                { label: '归档', value: 1 },
              ]}
            />
            <Button
              size="small"
              icon={<TeamOutlined />}
              onClick={() => setGroupCreateOpen(true)}
              aria-label="创建群聊"
              title="创建群聊"
            />
          </div>
          <div className={styles.search}>
            <Input.Search
              allowClear
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="搜索会话或聊天记录"
              size="small"
            />
          </div>

          {searching ? (
            <div className={styles.sidebarScroll}>
              {searchQuery.isLoading ? (
                <div className={styles.hint}>搜索中…</div>
              ) : searchQuery.isError ? (
                <div className={styles.hint}>搜索失败，请稍后重试</div>
              ) : (
                <SearchResults
                  conversations={searchQuery.data?.conversations ?? []}
                  messages={searchQuery.data?.messages ?? []}
                  onOpenConversation={openConversation}
                  onOpenMessage={(hit) => void jumpToMessage(hit)}
                />
              )}
            </div>
          ) : conversationsQuery.isLoading ? (
            <div className={styles.hint}>加载中…</div>
          ) : conversationsQuery.isError ? (
            <div className={styles.hint}>会话加载失败</div>
          ) : conversationList.length === 0 ? (
            <div className={styles.hint}>
              {archived ? '没有归档会话' : '暂无私信，去个人主页找朋友聊聊吧'}
            </div>
          ) : (
            <div className={styles.sidebarScroll}>
              {conversationList.map((conversation) => (
                <ConversationRow
                  key={conversation.id}
                  conversation={conversation}
                  active={selectedId === conversation.id}
                  onClick={() => openConversation(conversation)}
                  onAction={(action) => settingMutation.mutate({ conversation, action })}
                />
              ))}
              {conversationsQuery.hasNextPage ? (
                <div className={styles.moreRow}>
                  <Button
                    size="small"
                    type="link"
                    loading={conversationsQuery.isFetchingNextPage}
                    onClick={() => void conversationsQuery.fetchNextPage()}
                  >
                    加载更多会话
                  </Button>
                </div>
              ) : null}
            </div>
          )}
        </aside>

        <section className={styles.chat}>
          {!selected ? (
            <div className={styles.placeholder}>
              <Empty description="选择一个会话开始聊天" />
            </div>
          ) : (
            <>
              <div className={styles.chatHead}>
                <Avatar
                  shape={isGroup ? 'square' : 'circle'}
                  src={mediaUrl(selected.avatar)}
                  icon={isGroup ? <TeamOutlined /> : <UserOutlined />}
                />
                <div className={styles.chatTitle}>
                  <Typography.Text strong>{selected.nickname}</Typography.Text>
                  {selected.id ? (
                    <span className={styles.chatSub}>
                      {isGroup
                        ? `群聊 · ${selected.raw?.memberCount ?? 0} 人`
                        : '私信'}
                      {selected.muted ? (
                        <Tag bordered={false} className={styles.muteTag}>
                          <BellOutlined /> 免打扰
                        </Tag>
                      ) : null}
                    </span>
                  ) : (
                    <span className={styles.chatSub}>新会话</span>
                  )}
                </div>
                {isGroup && selected.id ? (
                  <Button size="small" icon={<TeamOutlined />} onClick={() => setMembersOpen(true)}>
                    成员
                  </Button>
                ) : null}
              </div>

              <div className={styles.messages} ref={listRef} onScroll={handleScroll}>
                {!selected.id ? (
                  <div className={styles.hint}>发送第一条消息后创建会话</div>
                ) : messagesQuery.isLoading ? (
                  <div className={styles.hint}>加载中…</div>
                ) : messagesQuery.isError ? (
                  <div className={styles.hint}>消息加载失败，请稍后重试</div>
                ) : messages.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有消息" />
                ) : (
                  <>
                    {messagesQuery.hasNextPage ? (
                      <div className={styles.loadOlder}>
                        <Button
                          size="small"
                          type="link"
                          loading={messagesQuery.isFetchingNextPage}
                          onClick={loadOlder}
                        >
                          加载更早的消息
                        </Button>
                      </div>
                    ) : null}
                    {messages.map((message, index) => (
                      <MessageBubble
                        key={message.id}
                        message={message}
                        group={Boolean(isGroup)}
                        highlight={highlightId === message.id}
                        showDate={
                          index === 0 ||
                          dayjs(message.createTime).format('YYYY-MM-DD') !==
                            dayjs(messages[index - 1].createTime).format('YYYY-MM-DD')
                        }
                        onQuote={() =>
                          setQuote({
                            messageId: message.id,
                            senderId: message.senderId,
                            senderNickname: message.senderNickname,
                            content: message.content,
                            revoked: message.isRevoked,
                          })
                        }
                        onForward={() => setForwarding(message)}
                        onRevoke={() => revokeMutation.mutate(message.id)}
                        onDelete={() => deleteMutation.mutate(message.id)}
                      />
                    ))}
                  </>
                )}
              </div>

              {showNew ? (
                <button
                  type="button"
                  className={styles.newMessage}
                  onClick={() => {
                    const el = listRef.current;
                    if (el) el.scrollTop = el.scrollHeight;
                    atBottomRef.current = true;
                    setShowNew(false);
                  }}
                >
                  <ArrowDownOutlined /> 有新消息
                </button>
              ) : null}

              <div className={styles.composer}>
                {quote ? (
                  <div className={styles.quoteBar}>
                    <div className={styles.quoteBody}>
                      <span className={styles.quoteTitle}>
                        引用 {quote.senderNickname ?? '消息'}
                      </span>
                      <span className={styles.quoteText}>
                        {quote.revoked ? '原消息已撤回' : quote.content}
                      </span>
                    </div>
                    <Button
                      type="text"
                      size="small"
                      icon={<CloseOutlined />}
                      onClick={() => setQuote(null)}
                      aria-label="取消引用"
                    />
                  </div>
                ) : null}
                <div className={styles.composerRow}>
                  <Input.TextArea
                    value={text}
                    onChange={(event) => setText(event.target.value)}
                    placeholder="输入消息，Enter 发送，Shift+Enter 换行"
                    autoSize={{ minRows: 2, maxRows: 5 }}
                    maxLength={500}
                    showCount
                    onPressEnter={(event) => {
                      if (event.shiftKey) return;
                      event.preventDefault();
                      if (text.trim() && !sendMutation.isPending) sendMutation.mutate();
                    }}
                  />
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    loading={sendMutation.isPending}
                    disabled={!text.trim()}
                    onClick={() => sendMutation.mutate()}
                  >
                    发送
                  </Button>
                </div>
              </div>
            </>
          )}
        </section>
      </div>

      <GroupCreateDialog
        open={groupCreateOpen}
        onClose={() => setGroupCreateOpen(false)}
        onCreated={(conversation) => {
          queryClient.setQueryData(
            socialUpgradeKeys.conversations(false),
            (old: InfiniteData<{ list: ConversationItemVO[]; pageNum: number; hasNext: boolean }> | undefined) =>
              old
                ? {
                    ...old,
                    pages: old.pages.map((page, index) =>
                      index === 0 ? { ...page, list: [conversation, ...page.list] } : page,
                    ),
                  }
                : old,
          );
          // 新建的群一定在「会话」而不是「归档」里，打开时退出归档视图
          setSelected(fromConversation(conversation));
          setSearchText('');
          setSearchParams({ conversationId: conversation.id }, { replace: true });
          invalidateConversations();
        }}
      />

      <GroupMembersDrawer
        open={membersOpen}
        conversation={selected?.raw ?? null}
        onClose={() => setMembersOpen(false)}
        onLeft={clearSelection}
      />

      <Modal
        open={Boolean(forwarding)}
        title="转发消息"
        footer={null}
        onCancel={() => setForwarding(null)}
        destroyOnHidden
      >
        {conversationList.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可转发的会话" />
        ) : (
          <div className={styles.forwardList}>
            {conversationList.map((conversation) => (
              <div key={conversation.id} className={styles.forwardRow}>
                <Avatar
                  shape={conversation.type === 2 ? 'square' : 'circle'}
                  src={mediaUrl(conversation.avatar)}
                  icon={conversation.type === 2 ? <TeamOutlined /> : <UserOutlined />}
                />
                <span className={styles.forwardName}>
                  {conversation.name ?? '会话'}
                  {conversation.type === 2 ? <Tag bordered={false}>群</Tag> : null}
                </span>
                <Button
                  size="small"
                  type="primary"
                  loading={forwardMutation.isPending}
                  onClick={() => {
                    if (!forwarding) return;
                    forwardMutation.mutate({
                      targetId: conversation.id,
                      messageId: forwarding.id,
                    });
                  }}
                >
                  转发
                </Button>
              </div>
            ))}
          </div>
        )}
      </Modal>
    </div>
  );
}

function ConversationRow({
  conversation,
  active,
  onClick,
  onAction,
}: {
  conversation: ConversationItemVO;
  active: boolean;
  onClick: () => void;
  onAction: (action: 'pin' | 'mute' | 'read' | 'archive') => void;
}): JSX.Element {
  const isGroup = conversation.type === 2;
  const menu: MenuProps['items'] = [
    { key: 'pin', label: conversation.pinned ? '取消置顶' : '置顶' },
    { key: 'mute', label: conversation.muted ? '关闭免打扰' : '免打扰' },
    { key: 'read', label: '标记已读', disabled: conversation.unread === 0 },
    { type: 'divider' },
    { key: 'archive', label: conversation.archived ? '取消归档' : '归档' },
  ];

  return (
    <div
      className={`${styles.conversation} ${active ? styles.active : ''}`}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onClick();
        }
      }}
    >
      <Badge count={conversation.unread} size="small" offset={[-2, 2]}>
        <Avatar
          shape={isGroup ? 'square' : 'circle'}
          src={mediaUrl(conversation.avatar)}
          icon={isGroup ? <TeamOutlined /> : <UserOutlined />}
        />
      </Badge>
      <div className={styles.convBody}>
        <div className={styles.convTop}>
          <span className={styles.convName}>
            {conversation.pinned ? <PushpinOutlined className={styles.pinIcon} /> : null}
            {conversation.name ?? '会话'}
          </span>
          <span className={styles.time}>{conversationTime(conversation.lastMessageTime)}</span>
        </div>
        <div className={styles.convBottom}>
          <span className={styles.preview}>
            {conversation.muted ? <BellOutlined className={styles.muteIcon} /> : null}
            {conversation.lastMessagePreview ?? '暂无消息'}
          </span>
          <Dropdown
            trigger={['click']}
            menu={{
              items: menu,
              onClick: ({ key, domEvent }) => {
                domEvent.stopPropagation();
                onAction(key as 'pin' | 'mute' | 'read' | 'archive');
              },
            }}
          >
            <Button
              type="text"
              size="small"
              icon={<EllipsisOutlined />}
              aria-label="会话操作"
              onClick={(event) => event.stopPropagation()}
            />
          </Dropdown>
        </div>
      </div>
    </div>
  );
}

function SearchResults({
  conversations,
  messages,
  onOpenConversation,
  onOpenMessage,
}: {
  conversations: ConversationItemVO[];
  messages: ConversationSearchHit[];
  onOpenConversation: (conversation: ConversationItemVO) => void;
  onOpenMessage: (hit: ConversationSearchHit) => void;
}): JSX.Element {
  if (conversations.length === 0 && messages.length === 0) {
    return <div className={styles.hint}>没有找到相关会话或消息</div>;
  }
  return (
    <div>
      {conversations.length > 0 ? (
        <div className={styles.searchSection}>
          <div className={styles.searchTitle}>会话</div>
          {conversations.map((conversation) => (
            <div
              key={conversation.id}
              className={styles.searchHit}
              onClick={() => onOpenConversation(conversation)}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter') onOpenConversation(conversation);
              }}
            >
              <Avatar
                size={28}
                shape={conversation.type === 2 ? 'square' : 'circle'}
                src={mediaUrl(conversation.avatar)}
                icon={conversation.type === 2 ? <TeamOutlined /> : <UserOutlined />}
              />
              <span className={styles.searchHitBody}>
                <span className={styles.searchHitName}>{conversation.name ?? '会话'}</span>
                <span className={styles.searchHitText}>
                  {conversation.lastMessagePreview ?? '暂无消息'}
                </span>
              </span>
            </div>
          ))}
        </div>
      ) : null}

      {messages.length > 0 ? (
        <div className={styles.searchSection}>
          <div className={styles.searchTitle}>聊天记录</div>
          {messages.map((hit) => (
            <div
              key={hit.messageId}
              className={styles.searchHit}
              onClick={() => onOpenMessage(hit)}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter') onOpenMessage(hit);
              }}
            >
              <span className={styles.searchHitBody}>
                <span className={styles.searchHitName}>
                  {hit.conversationName ?? '会话'} · {hit.senderNickname ?? '成员'}
                </span>
                <span className={styles.searchHitText}>{hit.content}</span>
                <span className={styles.searchHitTime}>
                  {dayjs(hit.createTime).format('MM-DD HH:mm')}
                </span>
              </span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function MessageBubble({
  message,
  group,
  highlight,
  showDate,
  onQuote,
  onForward,
  onRevoke,
  onDelete,
}: {
  message: PrivateMessageVO;
  group: boolean;
  highlight: boolean;
  showDate: boolean;
  onQuote: () => void;
  onForward: () => void;
  onRevoke: () => void;
  onDelete: () => void;
}): JSX.Element {
  const canRevoke =
    !group && message.mine && !message.isRevoked && canRevokeMessage(message.createTime);
  const canForward = !message.isRevoked;
  const dateDivider = showDate ? (
    <div className={styles.dateDivider}>{dayjs(message.createTime).format('YYYY-MM-DD')}</div>
  ) : null;

  if (message.msgType === 'SYSTEM') {
    return (
      <>
        {dateDivider}
        <div className={styles.systemMessage}>{message.content}</div>
      </>
    );
  }

  const renderContent = (): JSX.Element => {
    if (message.isRevoked) return <span className={styles.revoked}>消息已撤回</span>;
    switch (message.msgType) {
      case 'IMAGE':
        return <img src={mediaUrl(message.content)} alt="图片消息" className={styles.image} />;
      case 'SONG':
        return (
          <Link to={`/song/${message.content}`} className={styles.link}>
            分享歌曲 #{String(message.content).slice(-6)}
          </Link>
        );
      case 'PLAYLIST':
        return (
          <Link to={`/playlist/${message.content}`} className={styles.link}>
            分享歌单 #{String(message.content).slice(-6)}
          </Link>
        );
      case 'ROOM':
        return (
          <Link to="/rooms" className={styles.link}>
            分享音乐房
          </Link>
        );
      default:
        return <span className={styles.text}>{message.content}</span>;
    }
  };

  return (
    <>
      {dateDivider}
      <div
        id={`msg-${message.id}`}
        className={`${styles.messageRow} ${message.mine ? styles.mine : ''} ${
          highlight ? styles.highlight : ''
        }`}
      >
        {!message.mine && group ? (
          <Avatar
            size={30}
            className={styles.msgAvatar}
            src={mediaUrl(message.senderAvatar)}
            icon={<UserOutlined />}
          />
        ) : null}
        <div className={styles.bubbleWrap}>
          {!message.mine && group ? (
            <span className={styles.senderName}>{message.senderNickname ?? '成员'}</span>
          ) : null}
          <div className={`${styles.bubble} ${message.isRevoked ? styles.bubbleRevoked : ''}`}>
            {message.forwarded ? <div className={styles.forwardedTag}>转发</div> : null}
            {message.quote ? (
              <div className={styles.quote}>
                <span className={styles.quoteSender}>
                  {message.quote.senderNickname ?? '消息'}
                </span>
                <span className={styles.quoteContent}>
                  {message.quote.revoked ? '原消息已撤回' : message.quote.content}
                </span>
              </div>
            ) : null}
            {renderContent()}
          </div>
          <div className={styles.messageMeta}>
            <span>{dayjs(message.createTime).format('HH:mm')}</span>
            {!group && message.mine && !message.isRevoked ? (
              <span>{message.isRead ? '已读' : '未读'}</span>
            ) : null}
            {!message.isRevoked ? (
              <Button
                type="link"
                size="small"
                icon={<CommentOutlined />}
                className={styles.metaBtn}
                onClick={onQuote}
              >
                引用
              </Button>
            ) : null}
            {canForward ? (
              <Button
                type="link"
                size="small"
                icon={<ShareAltOutlined />}
                className={styles.metaBtn}
                onClick={onForward}
              >
                转发
              </Button>
            ) : null}
            {canRevoke ? (
              <Popconfirm title="撤回这条消息？" okText="撤回" cancelText="取消" onConfirm={onRevoke}>
                <Button
                  type="link"
                  size="small"
                  icon={<RollbackOutlined />}
                  className={styles.metaBtn}
                >
                  撤回
                </Button>
              </Popconfirm>
            ) : null}
            {!group ? (
              <Popconfirm
                title="删除这条消息？"
                description="仅对你隐藏"
                okText="删除"
                cancelText="取消"
                onConfirm={onDelete}
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  className={styles.metaBtn}
                >
                  删除
                </Button>
              </Popconfirm>
            ) : null}
          </div>
        </div>
      </div>
    </>
  );
}
