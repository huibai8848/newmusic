package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.dto.MessageSendReq;
import com.example.newmusic.module.social.entity.Conversation;
import com.example.newmusic.module.social.entity.PrivateMessage;
import com.example.newmusic.module.social.mapper.ConversationMapper;
import com.example.newmusic.module.social.mapper.PrivateMessageMapper;
import com.example.newmusic.module.social.support.ContentSanitizer;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.ConversationVO;
import com.example.newmusic.module.social.vo.PrivateMessageVO;
import com.example.newmusic.module.socialupgrade.mapper.ConversationMemberMapper;
import com.example.newmusic.module.socialupgrade.service.IntimacyService;
import com.example.newmusic.module.socialupgrade.support.MessageSpamGuard;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.WsPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 私信（模块 10 FR-19-011 / 系分 D-08）。
 *
 * <p>会话表按 {@code user_a_id < user_b_id} 的唯一键去重；未读数同时维护在会话行上，
 * 会话列表不需要对消息表做聚合。Redis 只用于「非互关每日条数」和「每分钟频率」，
 * 这两个限制在 Redis 不可用时自动放宽，不影响主流程。
 */
@Slf4j
@Service
public class MessageService {

    /** BR-19-090：每分钟最多 20 条。 */
    private static final int MINUTE_LIMIT = 20;

    private static final Set<String> MSG_TYPES = new HashSet<>(Arrays.asList(
            PrivateMessage.TYPE_TEXT, PrivateMessage.TYPE_IMAGE,
            PrivateMessage.TYPE_SONG, PrivateMessage.TYPE_PLAYLIST, PrivateMessage.TYPE_ROOM));

    private final ConversationMapper conversationMapper;
    private final PrivateMessageMapper messageMapper;
    private final SocialUserSupport users;
    private final SocialTargets targets;
    private final PrivacySettingService privacy;
    private final SocialNotifier notifier;
    private final ContentSanitizer sanitizer;
    private final RedisOps redisOps;
    private final SocialProperties properties;
    private final WsPushService wsPushService;
    private final ConversationMemberMapper memberMapper;
    private final MessageSpamGuard spamGuard;
    private final IntimacyService intimacyService;
    private final SensitiveWordSupport sensitiveWords;
    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    public MessageService(ConversationMapper conversationMapper, PrivateMessageMapper messageMapper,
                          SocialUserSupport users, SocialTargets targets,
                          PrivacySettingService privacy, SocialNotifier notifier,
                          ContentSanitizer sanitizer, RedisOps redisOps,
                          SocialProperties properties, WsPushService wsPushService,
                          ConversationMemberMapper memberMapper, MessageSpamGuard spamGuard,
                          IntimacyService intimacyService, SensitiveWordSupport sensitiveWords,
                          UserRestrictionService restrictionService,
                          MinorProtectionService minorService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.users = users;
        this.targets = targets;
        this.privacy = privacy;
        this.notifier = notifier;
        this.sanitizer = sanitizer;
        this.redisOps = redisOps;
        this.properties = properties;
        this.wsPushService = wsPushService;
        this.memberMapper = memberMapper;
        this.spamGuard = spamGuard;
        this.intimacyService = intimacyService;
        this.sensitiveWords = sensitiveWords;
        this.restrictionService = restrictionService;
        this.minorService = minorService;
    }

    @Transactional
    public PrivateMessageVO send(Long userId, MessageSendReq req) {
        User sender = users.requireUser(userId);
        if (userId.equals(req.getTargetUserId())) {
            throw new BizException(ResultCode.SELF_INTERACTION_FORBIDDEN, "不能给自己发私信");
        }
        User receiver = users.requireUser(req.getTargetUserId());
        if (users.isBanned(receiver)) {
            throw new BizException(ResultCode.MESSAGE_PRIVATE_CLOSED);
        }

        boolean mutual = users.isMutual(userId, receiver.getId());
        if (users.blockedBy(receiver.getId(), userId)) {
            throw new BizException(ResultCode.MESSAGE_FORBIDDEN);
        }
        if (users.blockedBy(userId, receiver.getId())) {
            throw new BizException(ResultCode.USER_BLACKLISTED);
        }
        if (privacy.messageForbidden(receiver.getId(), userId, mutual)) {
            throw new BizException(ResultCode.MESSAGE_PRIVATE_CLOSED);
        }

        spamGuard.check(userId);
        checkRate(userId);
        if (!mutual) {
            checkNonMutualDaily(userId, receiver.getId());
        }

        String msgType = normalizeType(req.getMsgType());
        String content = validateContent(msgType, req.getContent());
        checkContent(userId, content);

        Conversation conversation = ensureConversation(userId, receiver.getId());
        LocalDateTime now = LocalDateTime.now();

        PrivateMessage message = new PrivateMessage();
        message.setId(IdWorker.getId());
        message.setConversationId(conversation.getId());
        message.setSenderId(userId);
        message.setReceiverId(receiver.getId());
        message.setMsgType(msgType);
        message.setContent(content);
        message.setIsRead(0);
        message.setIsRevoked(0);
        message.setDeletedBySender(0);
        message.setDeletedByReceiver(0);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        messageMapper.insert(message);

        int unreadBefore = conversation.unreadOf(receiver.getId()) == null
                ? 0 : conversation.unreadOf(receiver.getId());
        conversationMapper.updateLastMessage(conversation.getId(), message.getId(), now,
                preview(msgType, content));
        if (receiver.getId().equals(conversation.getUserAId())) {
            conversationMapper.incrUnreadA(conversation.getId(), 1);
        } else {
            conversationMapper.incrUnreadB(conversation.getId(), 1);
        }

        spamGuard.record(userId, conversation.getId(), content);
        intimacyService.record(userId, receiver.getId(), IntimacyService.WEIGHT_MESSAGE);
        // 归档会话收到新消息时自动回到主列表（FR-25-010“可配置”，本期默认取消归档）
        memberMapper.unarchive(conversation.getId(), receiver.getId());

        PrivateMessageVO vo = toVO(message, userId);
        wsPushService.sendToUser(receiver.getId(), "msg.new", vo);

        // 只在接收者这条会话本来没有未读时落一条通知，避免每条私信都刷一遍通知中心
        if (unreadBefore == 0) {
            notifier.notify(receiver.getId(), Notification.TYPE_MESSAGE, "新私信",
                    sender.getNickname() + "：" + preview(msgType, content),
                    userId, sender, Notification.TARGET_MESSAGE, message.getId());
        }
        return vo;
    }

    public PageResult<ConversationVO> conversations(Long userId, int pageNum, int pageSize) {
        Page<Conversation> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 50));
        Page<Conversation> result = conversationMapper.selectPage(page,
                new QueryWrapper<Conversation>()
                        .eq("type", Conversation.TYPE_SINGLE)
                        .and(w -> w.eq("user_a_id", userId).or().eq("user_b_id", userId))
                        .orderByDesc("last_message_time").orderByDesc("id"));
        List<Long> otherIds = new ArrayList<>();
        for (Conversation c : result.getRecords()) {
            otherIds.add(c.otherUserId(userId));
        }
        Map<Long, User> userMap = users.loadUsers(otherIds);
        return PageResult.of(result, c -> {
            ConversationVO vo = new ConversationVO();
            vo.setId(c.getId());
            vo.setOtherUserId(c.otherUserId(userId));
            User other = userMap.get(c.otherUserId(userId));
            if (other != null) {
                vo.setNickname(other.getNickname());
                vo.setAvatar(other.getAvatar());
            }
            vo.setLastMessagePreview(c.getLastMessagePreview());
            vo.setLastMessageTime(c.getLastMessageTime());
            vo.setUnread(c.unreadOf(userId) == null ? 0 : c.unreadOf(userId));
            return vo;
        }, false);
    }

    public List<PrivateMessageVO> messages(Long userId, Long conversationId,
                                           Long beforeId, int limit) {
        Conversation conversation = requireConversation(userId, conversationId);
        int size = Math.min(Math.max(1, limit), 100);
        QueryWrapper<PrivateMessage> wrapper = new QueryWrapper<PrivateMessage>()
                .eq("conversation_id", conversation.getId())
                .and(w -> w.and(q -> q.eq("sender_id", userId).eq("deleted_by_sender", 0))
                        .or(q -> q.eq("receiver_id", userId).eq("deleted_by_receiver", 0)));
        if (beforeId != null && beforeId > 0) {
            wrapper.lt("id", beforeId);
        }
        wrapper.orderByDesc("id").last("LIMIT " + size);
        List<PrivateMessage> messages = messageMapper.selectList(wrapper);
        Collections.reverse(messages);
        List<PrivateMessageVO> list = new ArrayList<>(messages.size());
        for (PrivateMessage message : messages) {
            list.add(toVO(message, userId));
        }
        return list;
    }

    @Transactional
    public int read(Long userId, Long conversationId) {
        Conversation conversation = requireConversation(userId, conversationId);
        int changed = messageMapper.markRead(conversation.getId(), userId, LocalDateTime.now());
        if (userId.equals(conversation.getUserAId())) {
            conversationMapper.resetUnreadA(conversation.getId());
        } else {
            conversationMapper.resetUnreadB(conversation.getId());
        }
        Long other = conversation.otherUserId(userId);
        Integer readReceipt = privacy.get(userId).getReadReceipt();
        if (changed > 0 && (readReceipt == null || readReceipt == 1)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("conversationId", conversation.getId());
            payload.put("readerId", userId);
            payload.put("readTime", LocalDateTime.now());
            wsPushService.sendToUser(other, "msg.read", payload);
        }
        return changed;
    }

    public long unread(Long userId) {
        return conversationMapper.sumUnread(userId);
    }

    @Transactional
    public void revoke(Long userId, Long messageId) {
        PrivateMessage message = requireMessage(userId, messageId);
        if (!userId.equals(message.getSenderId())) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        if (Integer.valueOf(1).equals(message.getIsRevoked())) {
            throw new BizException(ResultCode.MESSAGE_RECALLED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (message.getCreateTime() != null && message.getCreateTime()
                .plusSeconds(properties.getMessage().getRecallSeconds()).isBefore(now)) {
            throw new BizException(ResultCode.MESSAGE_RECALL_EXPIRED);
        }
        int updated = messageMapper.revoke(messageId, userId, now);
        if (updated == 0) {
            throw new BizException(ResultCode.MESSAGE_RECALLED);
        }

        // 未读消息撤回后，接收者未读数要同步减一
        Conversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation != null && Integer.valueOf(0).equals(message.getIsRead())) {
            if (message.getReceiverId().equals(conversation.getUserAId())) {
                conversationMapper.incrUnreadA(conversation.getId(), -1);
            } else {
                conversationMapper.incrUnreadB(conversation.getId(), -1);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", message.getConversationId());
        payload.put("revokeTime", now);
        wsPushService.sendToUser(message.getReceiverId(), "msg.revoke", payload);
        wsPushService.sendToUser(userId, "msg.revoke", payload);
    }

    @Transactional
    public void delete(Long userId, Long messageId) {
        PrivateMessage message = requireMessage(userId, messageId);
        if (userId.equals(message.getSenderId())) {
            messageMapper.deleteBySender(messageId, userId);
        } else {
            messageMapper.deleteByReceiver(messageId, userId);
            if (Integer.valueOf(0).equals(message.getIsRead())) {
                Conversation conversation = conversationMapper.selectById(message.getConversationId());
                if (conversation != null) {
                    if (userId.equals(conversation.getUserAId())) {
                        conversationMapper.incrUnreadA(conversation.getId(), -1);
                    } else {
                        conversationMapper.incrUnreadB(conversation.getId(), -1);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Conversation ensureConversation(Long a, Long b) {
        Long userAId = Math.min(a, b);
        Long userBId = Math.max(a, b);
        Conversation conversation = conversationMapper.selectByPair(userAId, userBId);
        if (conversation != null) {
            return conversation;
        }
        conversationMapper.insertIgnore(IdWorker.getId(), userAId, userBId, LocalDateTime.now());
        conversation = conversationMapper.selectByPair(userAId, userBId);
        if (conversation == null) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND, "会话创建失败，请稍后重试");
        }
        return conversation;
    }

    private Conversation requireConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationId == null ? null
                : conversationMapper.selectById(conversationId);
        if (conversation == null || conversation.isGroup()
                || (!userId.equals(conversation.getUserAId())
                && !userId.equals(conversation.getUserBId()))) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        return conversation;
    }

    private PrivateMessage requireMessage(Long userId, Long messageId) {
        PrivateMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        if (message == null
                || (!userId.equals(message.getSenderId()) && !userId.equals(message.getReceiverId()))) {
            throw new BizException(ResultCode.MESSAGE_NOT_FOUND);
        }
        return message;
    }

    /** 供模块 16 的群聊发送复用，避免群聊绕过每分钟 20 条的限制。 */
    public void checkRate(Long userId) {
        long minute = System.currentTimeMillis() / 60000L;
        long count = redisOps.increment(RedisKeys.socialMessageRate(userId, minute), Duration.ofMinutes(2));
        if (count > MINUTE_LIMIT) {
            throw new BizException(ResultCode.MESSAGE_TOO_FREQUENT);
        }
    }

    private void checkNonMutualDaily(Long senderId, Long receiverId) {
        String key = RedisKeys.socialMessageDaily(senderId, receiverId,
                LocalDate.now(ZoneId.systemDefault()).toString());
        long count = redisOps.increment(key, Duration.ofDays(1));
        if (count > properties.getMessage().getNonMutualDailyLimit()) {
            throw new BizException(ResultCode.MESSAGE_NON_MUTUAL_LIMIT);
        }
    }

    /** 供模块 16 的群聊发送复用，避免两套消息类型校验漂移。 */
    /**
     * 模块 18：私信/群聊发送前的账号与内容校验。
     * 私信没有人工审核工作台入口，L2 及以上直接拒绝；L1 放行。
     */
    public void checkContent(Long userId, String content) {
        restrictionService.requireCanPublish(userId);
        minorService.requireSocialAllowed(userId);
        if (sensitiveWords.isRisky(content)) {
            throw new BizException(ResultCode.CONTENT_ILLEGAL, "消息包含违规内容");
        }
    }

    public static String normalizeType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return PrivateMessage.TYPE_TEXT;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if (!MSG_TYPES.contains(type)) {
            throw new BizException(ResultCode.MESSAGE_ILLEGAL, "不支持的消息类型");
        }
        return type;
    }

    /** 供模块 16 的群聊发送复用。 */
    public String validateContent(String msgType, String raw) {
        switch (msgType) {
            case PrivateMessage.TYPE_SONG:
                requireTarget(SocialTargets.SONG, raw);
                return raw.trim();
            case PrivateMessage.TYPE_PLAYLIST:
                requireTarget(SocialTargets.PLAYLIST, raw);
                return raw.trim();
            case PrivateMessage.TYPE_ROOM:
                requireTarget(SocialTargets.ROOM, raw);
                return raw.trim();
            case PrivateMessage.TYPE_IMAGE: {
                String url = sanitizer.clean(raw);
                if (url.length() > 1000 || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    throw new BizException(ResultCode.MESSAGE_ILLEGAL, "图片地址不合法");
                }
                return url;
            }
            default:
                return sanitizer.sanitizeMessage(raw, 500);
        }
    }

    private void requireTarget(String type, String raw) {
        try {
            targets.require(type, Long.valueOf(raw == null ? "" : raw.trim()), true);
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.MESSAGE_ILLEGAL, "分享内容不存在");
        }
    }

    /** 供模块 16 的群聊发送复用。 */
    public static String preview(String msgType, String content) {
        switch (msgType) {
            case PrivateMessage.TYPE_SONG:
                return "[歌曲]";
            case PrivateMessage.TYPE_PLAYLIST:
                return "[歌单]";
            case PrivateMessage.TYPE_ROOM:
                return "[房间]";
            case PrivateMessage.TYPE_IMAGE:
                return "[图片]";
            default:
                return content.length() > 50 ? content.substring(0, 50) : content;
        }
    }

    private PrivateMessageVO toVO(PrivateMessage message, Long viewerId) {
        PrivateMessageVO vo = new PrivateMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setSenderId(message.getSenderId());
        vo.setReceiverId(message.getReceiverId());
        vo.setMsgType(message.getMsgType());
        vo.setIsRead(Integer.valueOf(1).equals(message.getIsRead()));
        vo.setIsRevoked(Integer.valueOf(1).equals(message.getIsRevoked()));
        vo.setContent(Integer.valueOf(1).equals(message.getIsRevoked()) ? "消息已撤回" : message.getContent());
        vo.setMine(Objects.equals(viewerId, message.getSenderId()));
        vo.setForwarded(Integer.valueOf(1).equals(message.getForwarded()));
        vo.setCreateTime(message.getCreateTime());
        if (message.getQuoteMsgId() != null) {
            vo.setQuote(quote(message.getQuoteMsgId()));
        }
        return vo;
    }

    /** 引用摘要：群聊与单聊共用，避免两套文案漂移。 */
    public PrivateMessageVO.Quote quote(Long quoteId) {
        PrivateMessage quoted = quoteId == null ? null : messageMapper.selectById(quoteId);
        PrivateMessageVO.Quote quote = new PrivateMessageVO.Quote();
        quote.setMessageId(quoteId);
        if (quoted == null || Integer.valueOf(1).equals(quoted.getDeleted())
                || Integer.valueOf(1).equals(quoted.getIsRevoked())) {
            quote.setContent("原消息已撤回");
            quote.setRevoked(true);
            return quote;
        }
        quote.setSenderId(quoted.getSenderId());
        quote.setContent(quoted.getContent());
        quote.setRevoked(false);
        User sender = users.loadUsers(Collections.singletonList(quoted.getSenderId()))
                .get(quoted.getSenderId());
        quote.setSenderNickname(sender == null ? null : sender.getNickname());
        return quote;
    }
}
