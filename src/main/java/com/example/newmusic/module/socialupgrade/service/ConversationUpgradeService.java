package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.social.dto.MessageSendReq;
import com.example.newmusic.module.social.entity.Conversation;
import com.example.newmusic.module.social.entity.PrivateMessage;
import com.example.newmusic.module.social.mapper.ConversationMapper;
import com.example.newmusic.module.social.mapper.PrivateMessageMapper;
import com.example.newmusic.module.social.service.MessageService;
import com.example.newmusic.module.social.service.PrivacySettingService;
import com.example.newmusic.module.social.service.SocialNotifier;
import com.example.newmusic.module.social.support.ContentSanitizer;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.PrivateMessageVO;
import com.example.newmusic.module.socialupgrade.dto.AddMembersReq;
import com.example.newmusic.module.socialupgrade.dto.GroupCreateReq;
import com.example.newmusic.module.socialupgrade.dto.GroupMessageReq;
import com.example.newmusic.module.socialupgrade.entity.ConversationMember;
import com.example.newmusic.module.socialupgrade.mapper.ConversationMemberMapper;
import com.example.newmusic.module.socialupgrade.mapper.ConversationUpgradeMapper;
import com.example.newmusic.module.socialupgrade.support.MessageSpamGuard;
import com.example.newmusic.module.socialupgrade.vo.ConversationItemVO;
import com.example.newmusic.module.socialupgrade.vo.ConversationSearchVO;
import com.example.newmusic.module.socialupgrade.vo.GroupMemberVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.WsPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 私信增强（FR-25-010）。
 *
 * <p>单聊继续复用模块 10 的 {@link MessageService}（发送校验、未读计数、撤回都在那里）；
 * 本服务只补齐会话列表/搜索/归档/置顶/免打扰、引用、转发和群聊。
 *
 * <p>群聊不维护每个成员的未读计数，读取时用 {@code last_read_msg_id} 现算，
 * 避免 20 个成员的计数在并发发送时互相覆盖。
 */
@Slf4j
@Service
public class ConversationUpgradeService {

    private static final int GROUP_MIN = 3;
    private static final int GROUP_MAX = 20;
    private static final int PIN_LIMIT = 3;
    private static final int ARCHIVE_LIMIT = 100;
    private static final int SEARCH_CONVERSATION_LIMIT = 100;
    private static final int SEARCH_MESSAGE_LIMIT = 20;
    private static final String MSG_SYSTEM = "SYSTEM";

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ConversationUpgradeMapper upgradeMapper;
    private final PrivateMessageMapper messageMapper;
    private final MessageService messageService;
    private final PrivacySettingService privacy;
    private final SocialUserSupport users;
    private final ContentSanitizer sanitizer;
    private final MessageSpamGuard spamGuard;
    private final WsPushService wsPushService;
    private final SocialNotifier notifier;

    public ConversationUpgradeService(ConversationMapper conversationMapper,
                                      ConversationMemberMapper memberMapper,
                                      ConversationUpgradeMapper upgradeMapper,
                                      PrivateMessageMapper messageMapper,
                                      MessageService messageService,
                                      PrivacySettingService privacy,
                                      SocialUserSupport users,
                                      ContentSanitizer sanitizer,
                                      MessageSpamGuard spamGuard,
                                      WsPushService wsPushService,
                                      SocialNotifier notifier) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.upgradeMapper = upgradeMapper;
        this.messageMapper = messageMapper;
        this.messageService = messageService;
        this.privacy = privacy;
        this.users = users;
        this.sanitizer = sanitizer;
        this.spamGuard = spamGuard;
        this.wsPushService = wsPushService;
        this.notifier = notifier;
    }

    // ------------------------------------------------------------------ list

    public PageResult<ConversationItemVO> conversations(Long userId, int pageNum, int pageSize,
                                                       boolean archived) {
        int num = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), 50);
        List<Conversation> single = upgradeMapper.selectSingleConversations(userId);
        List<Conversation> groups = upgradeMapper.selectGroupConversations(userId);
        List<Conversation> all = new ArrayList<>(single.size() + groups.size());
        all.addAll(single);
        all.addAll(groups);
        if (all.isEmpty()) {
            return PageResult.empty(num, size);
        }

        List<Long> ids = all.stream().map(Conversation::getId).collect(Collectors.toList());
        Map<Long, ConversationMember> memberships = new HashMap<>();
        for (ConversationMember member : upgradeMapper.selectMemberships(userId, ids)) {
            memberships.put(member.getConversationId(), member);
        }
        Set<Long> groupIds = groups.stream().map(Conversation::getId).collect(Collectors.toSet());
        Map<Long, Integer> groupUnread = groupIds.isEmpty() ? Collections.emptyMap()
                : groupUnread(userId, groupIds);

        int archivedFlag = archived ? 1 : 0;
        List<ConversationItemVO> items = new ArrayList<>();
        for (Conversation conversation : all) {
            ConversationMember member = memberships.get(conversation.getId());
            int archivedValue = member == null || member.getArchived() == null ? 0 : member.getArchived();
            if (archivedValue != archivedFlag) {
                continue;
            }
            ConversationItemVO vo = toItem(conversation, userId, member,
                    conversation.isGroup() ? groupUnread.getOrDefault(conversation.getId(), 0) : null);
            items.add(vo);
        }
        items.sort(Comparator
                .comparing((ConversationItemVO v) -> Boolean.TRUE.equals(v.getPinned()) ? 1 : 0)
                .reversed()
                .thenComparing(ConversationItemVO::getLastMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ConversationItemVO::getId, Comparator.reverseOrder()));

        int total = items.size();
        int from = Math.min((num - 1) * size, total);
        int to = Math.min(from + size, total);
        PageResult<ConversationItemVO> result = new PageResult<>();
        result.setList(new ArrayList<>(items.subList(from, to)));
        result.setTotal(total);
        result.setPageNum(num);
        result.setPageSize(size);
        result.setPages((total + size - 1) / size);
        result.setHasNext(to < total);
        result.setTruncated(false);
        return result;
    }

    public long unreadTotal(Long userId) {
        long single = conversationMapper.sumUnread(userId);
        List<Conversation> groups = upgradeMapper.selectGroupConversations(userId);
        if (groups.isEmpty()) {
            return single;
        }
        Set<Long> ids = groups.stream().map(Conversation::getId).collect(Collectors.toSet());
        long group = groupUnread(userId, ids).values().stream().mapToLong(Integer::longValue).sum();
        return single + group;
    }

    private Map<Long, Integer> groupUnread(Long userId, Collection<Long> ids) {
        Map<Long, Integer> map = new HashMap<>();
        for (ConversationUpgradeMapper.GroupUnreadRow row
                : upgradeMapper.countGroupUnreadBatch(userId, ids)) {
            map.put(row.getConversationId(), row.getCnt() == null ? 0 : row.getCnt().intValue());
        }
        return map;
    }

    // ------------------------------------------------------------------ search

    public ConversationSearchVO search(Long userId, String keyword, int limit) {
        ConversationSearchVO vo = new ConversationSearchVO();
        vo.setConversations(Collections.emptyList());
        vo.setMessages(Collections.emptyList());
        if (!StringUtils.hasText(keyword)) {
            return vo;
        }
        String word = keyword.trim();
        int size = Math.min(Math.max(1, limit), 20);

        List<Conversation> all = ownConversations(userId);
        List<ConversationItemVO> nameHits = new ArrayList<>();
        for (Conversation conversation : all) {
            ConversationItemVO item = toItem(conversation, userId,
                    memberMapper.selectActive(conversation.getId(), userId), null);
            if (item.getName() != null && item.getName().contains(word)) {
                nameHits.add(item);
            }
            if (nameHits.size() >= size) {
                break;
            }
        }
        vo.setConversations(nameHits);

        List<ConversationSearchVO.MessageHitVO> hits = new ArrayList<>();
        for (Conversation conversation : all) {
            List<PrivateMessage> rows = messageMapper.searchInConversation(
                    conversation.getId(), word, 5);
            if (rows.isEmpty()) {
                continue;
            }
            String name = nameHits.stream()
                    .filter(i -> Objects.equals(i.getId(), conversation.getId()))
                    .map(ConversationItemVO::getName).findFirst()
                    .orElseGet(() -> toItem(conversation, userId,
                            memberMapper.selectActive(conversation.getId(), userId), null).getName());
            for (PrivateMessage message : rows) {
                if (!visibleToViewer(message, userId, conversation.isGroup())) {
                    continue;
                }
                ConversationSearchVO.MessageHitVO hit = new ConversationSearchVO.MessageHitVO();
                hit.setMessageId(message.getId());
                hit.setConversationId(conversation.getId());
                hit.setConversationName(name);
                hit.setSenderId(message.getSenderId());
                hit.setContent(Integer.valueOf(1).equals(message.getIsRevoked())
                        ? "消息已撤回" : message.getContent());
                hit.setCreateTime(message.getCreateTime());
                hits.add(hit);
            }
        }
        Map<Long, User> senders = users.loadUsers(hits.stream()
                .map(ConversationSearchVO.MessageHitVO::getSenderId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        for (ConversationSearchVO.MessageHitVO hit : hits) {
            User sender = senders.get(hit.getSenderId());
            hit.setSenderNickname(sender == null ? null : sender.getNickname());
        }
        hits.sort(Comparator.comparing(ConversationSearchVO.MessageHitVO::getMessageId,
                Comparator.nullsLast(Comparator.reverseOrder())));
        vo.setMessages(hits.stream().limit(SEARCH_MESSAGE_LIMIT).collect(Collectors.toList()));
        return vo;
    }

    private List<Conversation> ownConversations(Long userId) {
        List<Conversation> all = new ArrayList<>();
        all.addAll(upgradeMapper.selectSingleConversations(userId));
        all.addAll(upgradeMapper.selectGroupConversations(userId));
        if (all.size() > SEARCH_CONVERSATION_LIMIT) {
            return all.subList(0, SEARCH_CONVERSATION_LIMIT);
        }
        return all;
    }

    /**
     * 打开单聊会话（不发送消息）。校验与发送私信一致：拉黑、封禁、隐私策略任一不通过都拒绝，
     * 避免用户通过「先建会话」绕过对方设置的私信权限。
     */
    @Transactional
    public ConversationItemVO createSingle(Long userId, Long targetUserId) {
        if (targetUserId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "请选择对方用户");
        }
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.SELF_INTERACTION_FORBIDDEN, "不能和自己建立会话");
        }
        User target = users.requireUser(targetUserId);
        if (users.isBanned(target)) {
            throw new BizException(ResultCode.MESSAGE_PRIVATE_CLOSED);
        }
        if (users.blockedBy(targetUserId, userId)) {
            throw new BizException(ResultCode.MESSAGE_FORBIDDEN);
        }
        if (users.blockedBy(userId, targetUserId)) {
            throw new BizException(ResultCode.USER_BLACKLISTED);
        }
        if (privacy.messageForbidden(targetUserId, userId, users.isMutual(userId, targetUserId))) {
            throw new BizException(ResultCode.MESSAGE_PRIVATE_CLOSED);
        }

        Long userAId = Math.min(userId, targetUserId);
        Long userBId = Math.max(userId, targetUserId);
        Conversation conversation = conversationMapper.selectByPair(userAId, userBId);
        if (conversation == null) {
            conversationMapper.insertIgnore(IdWorker.getId(), userAId, userBId, LocalDateTime.now());
            conversation = conversationMapper.selectByPair(userAId, userBId);
        }
        if (conversation == null) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND, "会话创建失败，请稍后重试");
        }
        return toItem(conversation, userId,
                memberMapper.selectActive(conversation.getId(), userId), null);
    }

    // ------------------------------------------------------------------ settings

    public ConversationItemVO pin(Long userId, Long conversationId, boolean enabled) {
        Conversation conversation = requireAccess(conversationId, userId);
        ConversationMember member = ensureMember(conversation, userId);
        if (enabled && !Integer.valueOf(1).equals(member.getPinned())
                && memberMapper.countPinned(userId) >= PIN_LIMIT) {
            throw new BizException(ResultCode.CONVERSATION_PIN_LIMIT);
        }
        memberMapper.updatePinned(conversationId, userId, enabled ? 1 : 0);
        member.setPinned(enabled ? 1 : 0);
        return toItem(conversation, userId, member, null);
    }

    public ConversationItemVO archive(Long userId, Long conversationId, boolean enabled) {
        Conversation conversation = requireAccess(conversationId, userId);
        ConversationMember member = ensureMember(conversation, userId);
        if (enabled && !Integer.valueOf(1).equals(member.getArchived())
                && memberMapper.countArchived(userId) >= ARCHIVE_LIMIT) {
            throw new BizException(ResultCode.CONVERSATION_ARCHIVE_LIMIT);
        }
        memberMapper.updateArchived(conversationId, userId, enabled ? 1 : 0);
        member.setArchived(enabled ? 1 : 0);
        return toItem(conversation, userId, member, null);
    }

    public ConversationItemVO mute(Long userId, Long conversationId, boolean enabled) {
        Conversation conversation = requireAccess(conversationId, userId);
        ConversationMember member = ensureMember(conversation, userId);
        memberMapper.updateMuted(conversationId, userId, enabled ? 1 : 0);
        member.setMuted(enabled ? 1 : 0);
        return toItem(conversation, userId, member, null);
    }

    // ------------------------------------------------------------------ messages

    public List<PrivateMessageVO> messages(Long userId, Long conversationId, Long beforeId, int limit) {
        Conversation conversation = requireAccess(conversationId, userId);
        int size = Math.min(Math.max(1, limit), 100);
        ConversationMember member = memberMapper.selectActive(conversationId, userId);
        if (!conversation.isGroup()) {
            return messageService.messages(userId, conversationId, beforeId, size);
        }
        List<PrivateMessage> rows = messageMapper.selectGroupMessages(
                conversationId, userId, beforeId, size);
        Collections.reverse(rows);
        return toVOs(rows, userId, member == null ? null : member.getLastReadMsgId());
    }

    @Transactional
    public PrivateMessageVO send(Long userId, Long conversationId, GroupMessageReq req) {
        return send(userId, conversationId, req, false);
    }

    @Transactional
    public PrivateMessageVO send(Long userId, Long conversationId, GroupMessageReq req,
                                 boolean forwarded) {
        Conversation conversation = requireAccess(conversationId, userId);
        User sender = users.requireUser(userId);
        if (!conversation.isGroup()) {
            // MessageService 负责单聊的校验/限流/未读；引用与转发标记在落库后补写
            PrivateMessageVO vo = messageService.send(userId, singleReq(conversation, userId, req));
            if (req.getQuoteMsgId() != null) {
                Long quoteId = requireQuote(conversationId, req.getQuoteMsgId());
                messageMapper.updateQuote(vo.getId(), quoteId);
                vo.setQuote(quote(quoteId));
            }
            if (forwarded) {
                messageMapper.markForwarded(vo.getId());
                vo.setForwarded(true);
            }
            return vo;
        }

        spamGuard.check(userId);
        messageService.checkRate(userId);
        String msgType = MessageService.normalizeType(req.getMsgType());
        String content = messageService.validateContent(msgType, req.getContent());
        messageService.checkContent(userId, content);
        Long quoteId = req.getQuoteMsgId() == null ? null
                : requireQuote(conversationId, req.getQuoteMsgId());

        List<ConversationMember> members = memberMapper.selectMembers(conversationId);
        Set<Long> mentioned = parseMentions(content, members, userId);
        LocalDateTime now = LocalDateTime.now();
        PrivateMessage message = new PrivateMessage();
        message.setId(IdWorker.getId());
        message.setConversationId(conversationId);
        message.setSenderId(userId);
        message.setReceiverId(0L);
        message.setMsgType(msgType);
        message.setContent(content);
        message.setQuoteMsgId(quoteId);
        message.setForwarded(forwarded ? 1 : 0);
        message.setMentionedUserIds(mentioned.isEmpty() ? null
                : mentioned.stream().map(String::valueOf).collect(Collectors.joining(",")));
        message.setIsRead(0);
        message.setIsRevoked(0);
        message.setDeletedBySender(0);
        message.setDeletedByReceiver(0);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);
        conversationMapper.updateLastMessage(conversationId, message.getId(), now,
                MessageService.preview(msgType, content));

        spamGuard.record(userId, conversationId, content);
        for (ConversationMember member : members) {
            if (!member.getUserId().equals(userId)) {
                memberMapper.unarchive(conversationId, member.getUserId());
                wsPushService.sendToUser(member.getUserId(), "msg.new",
                        toVO(message, member.getUserId(), member.getLastReadMsgId(), sender));
            }
        }
        for (Long targetId : mentioned) {
            notifier.notify(targetId, Notification.TYPE_MENTION, "群聊中被提及",
                    sender.getNickname() + " 在群聊中提到了你", userId, sender,
                    Notification.TARGET_MESSAGE, message.getId());
        }
        return toVO(message, userId, null, sender);
    }

    @Transactional
    public void read(Long userId, Long conversationId) {
        Conversation conversation = requireAccess(conversationId, userId);
        if (!conversation.isGroup()) {
            messageService.read(userId, conversationId);
            return;
        }
        Long maxId = messageMapper.selectMaxId(conversationId);
        if (maxId != null) {
            memberMapper.updateLastRead(conversationId, userId, maxId);
        }
    }

    // ------------------------------------------------------------------ forward

    @Transactional
    public PrivateMessageVO forward(Long userId, Long messageId, Long targetConversationId) {
        PrivateMessage original = messageId == null ? null : messageMapper.selectById(messageId);
        if (original == null || Integer.valueOf(1).equals(original.getDeleted())) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        Conversation source = requireAccess(original.getConversationId(), userId);
        if (!visibleToViewer(original, userId, source.isGroup())) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (Integer.valueOf(1).equals(original.getIsRevoked())) {
            throw new BizException(ResultCode.MESSAGE_RECALLED);
        }
        Conversation target = requireAccess(targetConversationId, userId);
        GroupMessageReq req = new GroupMessageReq();
        req.setMsgType(original.getMsgType());
        req.setContent(original.getContent());
        // 转发结果只展示“当前用户发了这条内容”，不携带原发送者（BR-25-111）
        return send(userId, target.getId(), req, true);
    }

    // ------------------------------------------------------------------ group

    @Transactional
    public ConversationItemVO createGroup(Long userId, GroupCreateReq req) {
        users.requireUser(userId);
        LinkedHashSet<Long> invitees = new LinkedHashSet<>();
        if (req.getMemberIds() != null) {
            for (Long id : req.getMemberIds()) {
                if (id != null && !id.equals(userId)) {
                    invitees.add(id);
                }
            }
        }
        int total = invitees.size() + 1;
        if (total < GROUP_MIN) {
            throw new BizException(ResultCode.PARAM_INVALID, "群聊至少 3 人");
        }
        if (total > GROUP_MAX) {
            throw new BizException(ResultCode.GROUP_MEMBER_LIMIT);
        }

        Map<Long, User> loaded = users.loadUsers(invitees);
        List<Long> valid = new ArrayList<>();
        for (Long id : invitees) {
            User user = loaded.get(id);
            if (user == null || Integer.valueOf(2).equals(user.getStatus())
                    || Integer.valueOf(4).equals(user.getStatus())) {
                continue;
            }
            if (users.blockedEither(userId, id)) {
                throw new BizException(ResultCode.USER_BLACKLISTED, "存在拉黑关系，无法拉入群聊");
            }
            valid.add(id);
        }
        if (valid.size() + 1 < GROUP_MIN) {
            throw new BizException(ResultCode.PARAM_INVALID, "有效群成员不足 3 人");
        }

        String name = StringUtils.hasText(req.getName())
                ? sanitizer.clean(req.getName()) : "群聊";
        if (!StringUtils.hasText(name)) {
            throw new BizException(ResultCode.GROUP_NAME_ILLEGAL);
        }
        if (name.codePointCount(0, name.length()) > 30) {
            throw new BizException(ResultCode.GROUP_NAME_ILLEGAL, "群名称最多 30 个字符");
        }
        Long conversationId = IdWorker.getId();
        LocalDateTime now = LocalDateTime.now();
        conversationMapper.insertGroup(conversationId, userId, name, null,
                valid.size() + 1, now);
        insertMember(conversationId, userId, ConversationMember.ROLE_OWNER, now);
        for (Long id : valid) {
            insertMember(conversationId, id, ConversationMember.ROLE_MEMBER, now);
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        ConversationItemVO vo = toItem(conversation, userId,
                memberMapper.selectActive(conversationId, userId), null);
        vo.setMemberCount(valid.size() + 1);
        return vo;
    }

    public List<GroupMemberVO> members(Long userId, Long conversationId) {
        Conversation conversation = requireAccess(conversationId, userId);
        requireGroup(conversation);
        List<ConversationMember> rows = memberMapper.selectMembers(conversationId);
        Map<Long, User> loaded = users.loadUsers(rows.stream()
                .map(ConversationMember::getUserId).collect(Collectors.toSet()));
        List<GroupMemberVO> out = new ArrayList<>();
        for (ConversationMember row : rows) {
            GroupMemberVO vo = new GroupMemberVO();
            vo.setUserId(row.getUserId());
            vo.setRole(row.getRole());
            vo.setOwner(Integer.valueOf(ConversationMember.ROLE_OWNER).equals(row.getRole()));
            User user = loaded.get(row.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            out.add(vo);
        }
        return out;
    }

    @Transactional
    public void addMembers(Long userId, Long conversationId, AddMembersReq req) {
        Conversation conversation = requireAccess(conversationId, userId);
        requireGroup(conversation);
        List<ConversationMember> current = memberMapper.selectMembers(conversationId);
        Set<Long> existing = current.stream().map(ConversationMember::getUserId)
                .collect(Collectors.toSet());
        if (req.getUserIds() == null || req.getUserIds().isEmpty()) {
            return;
        }
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        for (Long id : req.getUserIds()) {
            if (id != null && !id.equals(userId) && !existing.contains(id)) {
                targets.add(id);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (current.size() + targets.size() > GROUP_MAX) {
            throw new BizException(ResultCode.GROUP_MEMBER_LIMIT);
        }
        Map<Long, User> loaded = users.loadUsers(targets);
        for (Long id : targets) {
            User user = loaded.get(id);
            if (user == null || Integer.valueOf(4).equals(user.getStatus())) {
                continue;
            }
            if (users.blockedEither(userId, id)) {
                throw new BizException(ResultCode.USER_BLACKLISTED, "存在拉黑关系，无法邀请入群");
            }
            ConversationMember row = memberMapper.selectAny(conversationId, id);
            if (row == null) {
                insertMember(conversationId, id, ConversationMember.ROLE_MEMBER, LocalDateTime.now());
            } else {
                memberMapper.reactivate(conversationId, id);
            }
        }
        int count = memberMapper.selectMembers(conversationId).size();
        conversationMapper.updateMemberCount(conversationId, count);
        User actor = users.requireUser(userId);
        systemMessage(conversation, actor, actor.getNickname() + " 邀请了新成员加入群聊");
    }

    @Transactional
    public void removeMember(Long userId, Long conversationId, Long targetUserId) {
        Conversation conversation = requireAccess(conversationId, userId);
        requireGroup(conversation);
        requireOwner(conversation, userId);
        if (Objects.equals(userId, targetUserId)) {
            throw new BizException(ResultCode.GROUP_OWNER_ONLY, "群主不能移除自己，请使用解散群聊");
        }
        if (memberMapper.quit(conversationId, targetUserId) == 0) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND, "该成员不在群聊中");
        }
        int count = memberMapper.selectMembers(conversationId).size();
        conversationMapper.updateMemberCount(conversationId, count);
        User actor = users.requireUser(userId);
        systemMessage(conversation, actor, "有成员被移出群聊");
        wsPushService.sendToUser(targetUserId, "group.removed", conversationId);
    }

    @Transactional
    public void quit(Long userId, Long conversationId) {
        Conversation conversation = requireAccess(conversationId, userId);
        requireGroup(conversation);
        if (Objects.equals(conversation.getOwnerId(), userId)) {
            throw new BizException(ResultCode.GROUP_OWNER_ONLY, "群主请先解散群聊");
        }
        memberMapper.quit(conversationId, userId);
        int count = memberMapper.selectMembers(conversationId).size();
        conversationMapper.updateMemberCount(conversationId, count);
        User actor = users.requireUser(userId);
        systemMessage(conversation, actor, actor.getNickname() + " 退出了群聊");
    }

    @Transactional
    public void dissolve(Long userId, Long conversationId) {
        Conversation conversation = requireAccess(conversationId, userId);
        requireGroup(conversation);
        requireOwner(conversation, userId);
        for (ConversationMember member : memberMapper.selectMembers(conversationId)) {
            wsPushService.sendToUser(member.getUserId(), "group.dissolved", conversationId);
            memberMapper.quit(conversationId, member.getUserId());
        }
        conversationMapper.updateMemberCount(conversationId, 0);
        // BaseEntity 的 @TableLogic：这里只置 deleted=1，消息记录保留用于审计
        conversationMapper.deleteById(conversationId);
    }

    private void systemMessage(Conversation conversation, User actor, String content) {
        LocalDateTime now = LocalDateTime.now();
        PrivateMessage message = new PrivateMessage();
        message.setId(IdWorker.getId());
        message.setConversationId(conversation.getId());
        message.setSenderId(actor.getId());
        message.setReceiverId(0L);
        message.setMsgType(MSG_SYSTEM);
        message.setContent(content);
        message.setForwarded(0);
        message.setIsRead(0);
        message.setIsRevoked(0);
        message.setDeletedBySender(0);
        message.setDeletedByReceiver(0);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);
        conversationMapper.updateLastMessage(conversation.getId(), message.getId(), now, content);
        for (ConversationMember member : memberMapper.selectMembers(conversation.getId())) {
            if (!member.getUserId().equals(actor.getId())) {
                wsPushService.sendToUser(member.getUserId(), "msg.new",
                        toVO(message, member.getUserId(), member.getLastReadMsgId(), actor));
            }
        }
    }

    private void insertMember(Long conversationId, Long userId, int role, LocalDateTime now) {
        ConversationMember member = new ConversationMember();
        member.setId(IdWorker.getId());
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setRole(role);
        member.setPinned(0);
        member.setArchived(0);
        member.setMuted(0);
        member.setJoinedTime(now);
        member.setCreateTime(now);
        member.setUpdateTime(now);
        memberMapper.insert(member);
    }

    // ------------------------------------------------------------------ helpers

    private Conversation requireAccess(Long conversationId, Long userId) {
        Conversation conversation = conversationId == null ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (conversation.isGroup()) {
            if (memberMapper.selectActive(conversationId, userId) == null) {
                throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
            }
            return conversation;
        }
        if (!userId.equals(conversation.getUserAId()) && !userId.equals(conversation.getUserBId())) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        return conversation;
    }

    /** 单聊的用户设置存在一个懒创建的成员行里；群聊必须已经入群。 */
    private ConversationMember ensureMember(Conversation conversation, Long userId) {
        ConversationMember active = memberMapper.selectActive(conversation.getId(), userId);
        if (active != null) {
            return active;
        }
        if (conversation.isGroup()) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        ConversationMember existing = memberMapper.selectAny(conversation.getId(), userId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        insertMember(conversation.getId(), userId, ConversationMember.ROLE_MEMBER, now);
        return memberMapper.selectActive(conversation.getId(), userId);
    }

    private Long requireQuote(Long conversationId, Long quoteId) {
        PrivateMessage quote = messageMapper.selectById(quoteId);
        if (quote == null || !Objects.equals(quote.getConversationId(), conversationId)
                || Integer.valueOf(1).equals(quote.getDeleted())) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND, "只能引用当前会话内的消息");
        }
        return quote.getId();
    }

    private void requireGroup(Conversation conversation) {
        if (!conversation.isGroup()) {
            throw new BizException(ResultCode.PARAM_INVALID, "该会话不是群聊");
        }
    }

    private void requireOwner(Conversation conversation, Long userId) {
        if (!Objects.equals(conversation.getOwnerId(), userId)) {
            throw new BizException(ResultCode.GROUP_OWNER_ONLY);
        }
    }

    private MessageSendReq singleReq(Conversation conversation, Long userId, GroupMessageReq req) {
        MessageSendReq single = new MessageSendReq();
        single.setTargetUserId(conversation.otherUserId(userId));
        single.setMsgType(req.getMsgType());
        single.setContent(req.getContent());
        return single;
    }

    private ConversationItemVO toItem(Conversation conversation, Long userId,
                                      ConversationMember member, Integer groupUnread) {
        ConversationItemVO vo = new ConversationItemVO();
        vo.setId(conversation.getId());
        vo.setType(conversation.getType() == null ? Conversation.TYPE_SINGLE : conversation.getType());
        vo.setLastMessagePreview(conversation.getLastMessagePreview());
        vo.setLastMessageTime(conversation.getLastMessageTime());
        vo.setPinned(member != null && Integer.valueOf(1).equals(member.getPinned()));
        vo.setArchived(member != null && Integer.valueOf(1).equals(member.getArchived()));
        vo.setMuted(member != null && Integer.valueOf(1).equals(member.getMuted()));
        if (conversation.isGroup()) {
            vo.setName(conversation.getGroupName());
            vo.setAvatar(conversation.getGroupAvatar());
            vo.setOwnerId(conversation.getOwnerId());
            vo.setMemberCount(conversation.getMemberCount());
            Long lastRead = member == null || member.getLastReadMsgId() == null
                    ? 0L : member.getLastReadMsgId();
            vo.setUnread(groupUnread == null
                    ? upgradeMapper.countGroupUnread(conversation.getId(), userId, lastRead)
                    : groupUnread);
        } else {
            Long otherId = conversation.otherUserId(userId);
            vo.setOtherUserId(otherId);
            User other = users.loadUsers(Collections.singletonList(otherId)).get(otherId);
            if (other != null) {
                vo.setName(other.getNickname());
                vo.setAvatar(other.getAvatar());
            }
            vo.setMemberCount(2);
            vo.setUnread(conversation.unreadOf(userId) == null ? 0 : conversation.unreadOf(userId));
        }
        return vo;
    }

    private List<PrivateMessageVO> toVOs(List<PrivateMessage> rows, Long viewerId, Long lastReadMsgId) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> senderIds = rows.stream().map(PrivateMessage::getSenderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> senders = users.loadUsers(senderIds);
        List<PrivateMessageVO> out = new ArrayList<>(rows.size());
        for (PrivateMessage row : rows) {
            out.add(toVO(row, viewerId, lastReadMsgId, senders.get(row.getSenderId())));
        }
        return out;
    }

    private PrivateMessageVO toVO(PrivateMessage message, Long viewerId, Long lastReadMsgId,
                                  User sender) {
        PrivateMessageVO vo = new PrivateMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setSenderId(message.getSenderId());
        vo.setReceiverId(message.getReceiverId());
        vo.setMsgType(message.getMsgType());
        vo.setIsRevoked(Integer.valueOf(1).equals(message.getIsRevoked()));
        vo.setContent(Integer.valueOf(1).equals(message.getIsRevoked())
                ? "消息已撤回" : message.getContent());
        vo.setMine(Objects.equals(viewerId, message.getSenderId()));
        vo.setForwarded(Integer.valueOf(1).equals(message.getForwarded()));
        vo.setSenderNickname(sender == null ? null : sender.getNickname());
        vo.setSenderAvatar(sender == null ? null : sender.getAvatar());
        vo.setCreateTime(message.getCreateTime());
        if (lastReadMsgId != null) {
            vo.setIsRead(vo.getMine() || message.getId() <= lastReadMsgId);
        } else {
            vo.setIsRead(Boolean.TRUE.equals(vo.getMine())
                    || Integer.valueOf(1).equals(message.getIsRead()));
        }
        if (message.getQuoteMsgId() != null) {
            vo.setQuote(quote(message.getQuoteMsgId()));
        }
        return vo;
    }

    /** 单聊/群聊共用同一份引用摘要实现。 */
    private PrivateMessageVO.Quote quote(Long quoteId) {
        return messageService.quote(quoteId);
    }

    private boolean visibleToViewer(PrivateMessage message, Long viewerId, boolean group) {
        if (Integer.valueOf(1).equals(message.getDeleted())) {
            return false;
        }
        if (Objects.equals(message.getSenderId(), viewerId)) {
            return !Integer.valueOf(1).equals(message.getDeletedBySender());
        }
        if (group) {
            return true;
        }
        return !Integer.valueOf(1).equals(message.getDeletedByReceiver());
    }

    /** 在群成员昵称中解析 @，只保留真实成员；逗号分隔返回。 */
    private Set<Long> parseMentions(String content, List<ConversationMember> members, Long senderId) {
        Set<Long> out = new LinkedHashSet<>();
        if (!StringUtils.hasText(content)) {
            return out;
        }
        Map<Long, User> loaded = users.loadUsers(members.stream()
                .map(ConversationMember::getUserId).collect(Collectors.toSet()));
        for (ConversationMember member : members) {
            if (member.getUserId().equals(senderId)) {
                continue;
            }
            User user = loaded.get(member.getUserId());
            if (user == null || !StringUtils.hasText(user.getNickname())) {
                continue;
            }
            if (content.contains("@" + user.getNickname())) {
                out.add(member.getUserId());
            }
        }
        return out;
    }
}
