package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.service.SocialNotifier;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.dto.MentionRef;
import com.example.newmusic.module.socialupgrade.entity.PostMention;
import com.example.newmusic.module.socialupgrade.mapper.PostMentionMapper;
import com.example.newmusic.module.socialupgrade.mapper.SocialUserQueryMapper;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @ 提及（FR-25-007）。
 *
 * <p>校验失败一律「静默忽略」而不是报错：@ 到不存在/拉黑自己的用户时，
 * PRD 要求正文照常发布，只是该 @ 不生效。真正会报错的只有数量与频率上限。
 */
@Slf4j
@Service
public class MentionService {

    private static final Pattern AT = Pattern.compile("@([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})");

    private final PostMentionMapper mentionMapper;
    private final SocialUserSupport users;
    private final SocialUserQueryMapper userQuery;
    private final FollowMapper followMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialNotifier notifier;
    private final SocialUpgradeProperties properties;
    private final IntimacyService intimacyService;

    public MentionService(PostMentionMapper mentionMapper, SocialUserSupport users,
                          SocialUserQueryMapper userQuery, FollowMapper followMapper,
                          BlacklistMapper blacklistMapper, SocialNotifier notifier,
                          SocialUpgradeProperties properties, IntimacyService intimacyService) {
        this.mentionMapper = mentionMapper;
        this.users = users;
        this.userQuery = userQuery;
        this.followMapper = followMapper;
        this.blacklistMapper = blacklistMapper;
        this.notifier = notifier;
        this.properties = properties;
        this.intimacyService = intimacyService;
    }

    /**
     * 保存 @ 关系并通知。
     *
     * @return 实际生效的 @ 用户 ID（被拉黑/不存在/自己 的已剔除）
     */
    public List<Long> save(User author, int sourceType, Long sourceId, String content,
                           List<MentionRef> refs) {
        List<MentionRef> candidates = refs == null ? Collections.emptyList() : refs;
        if (candidates.size() > properties.getMention().getMaxPerContent()) {
            throw new BizException(ResultCode.MENTION_LIMIT);
        }
        if (candidates.isEmpty()) {
            candidates = parseFromText(content);
            if (candidates.size() > properties.getMention().getMaxPerContent()) {
                throw new BizException(ResultCode.MENTION_LIMIT);
            }
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        if (mentionMapper.countByAuthorSince(author.getId(), startOfDay)
                + candidates.size() > properties.getMention().getDailyLimit()) {
            throw new BizException(ResultCode.MENTION_RATE_LIMITED);
        }

        Set<Long> seen = new HashSet<>();
        List<Long> accepted = new ArrayList<>();
        for (MentionRef ref : candidates) {
            if (ref == null || ref.getUserId() == null || !seen.add(ref.getUserId())) {
                continue;
            }
            Long targetId = ref.getUserId();
            if (targetId.equals(author.getId())) {
                continue;
            }
            User target;
            try {
                target = users.requireUser(targetId);
            } catch (BizException e) {
                continue;
            }
            if (users.blockedEither(author.getId(), targetId)) {
                continue;
            }

            PostMention row = new PostMention();
            row.setId(IdWorker.getId());
            row.setSourceType(sourceType);
            row.setSourceId(sourceId);
            row.setAuthorId(author.getId());
            row.setMentionedUserId(targetId);
            row.setOffset(ref.getOffset() == null ? 0 : Math.max(0, ref.getOffset()));
            row.setLength(ref.getLength() == null ? 0 : Math.max(0, ref.getLength()));
            row.setCreateTime(LocalDateTime.now());
            row.setNotified(0);

            int pairToday = mentionMapper.countPairSince(author.getId(), targetId, startOfDay);
            if (pairToday < properties.getMention().getNotifyPerTargetPerDay()) {
                row.setNotified(1);
                mentionMapper.insert(row);
                notifier.notify(targetId, Notification.TYPE_MENTION, "有人提到了你",
                        author.getNickname() + " 在动态中提到了你", author.getId(), author,
                        Notification.TARGET_POST, sourceId);
            } else {
                mentionMapper.insert(row);
            }
            accepted.add(targetId);
            intimacyService.record(author.getId(), targetId, IntimacyService.WEIGHT_MENTION);
        }
        return accepted;
    }

    public void deleteBySource(int sourceType, Long sourceId) {
        mentionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PostMention>()
                .eq("source_type", sourceType).eq("source_id", sourceId));
    }

    public List<PostVO.MentionedUser> render(int sourceType, Long sourceId, Long viewerId) {
        List<PostMention> rows = mentionMapper.selectBySource(sourceType, sourceId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> map = users.loadUsers(rows.stream()
                .map(PostMention::getMentionedUserId).collect(Collectors.toList()));
        List<PostVO.MentionedUser> out = new ArrayList<>();
        for (PostMention row : rows) {
            User target = map.get(row.getMentionedUserId());
            PostVO.MentionedUser item = new PostVO.MentionedUser();
            item.setUserId(row.getMentionedUserId());
            item.setOffset(row.getOffset());
            item.setLength(row.getLength());
            if (target == null || Integer.valueOf(4).equals(target.getStatus())) {
                item.setNickname("已注销用户");
                item.setClickable(false);
            } else {
                item.setNickname(target.getNickname());
                boolean blocked = viewerId != null && users.blockedEither(viewerId, target.getId());
                item.setClickable(!blocked && !Integer.valueOf(2).equals(target.getStatus()));
            }
            out.add(item);
        }
        return out;
    }

    /** @ 自动补全：优先最近互动过的人，其次关注的人，最后全站昵称匹配。 */
    public List<UserBriefVO> search(Long viewerId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        List<Long> recent = mentionMapper.selectRecentTargets(viewerId, 30);
        List<Long> following = followMapper.selectFollowingAll(viewerId);
        LinkedHashSet<Long> preferred = new LinkedHashSet<>();
        preferred.addAll(recent);
        preferred.addAll(following);
        // 候选里要剔除拉黑关系（双向），否则会推荐到用户明确不想看到的人
        Set<Long> blocked = new HashSet<>();
        if (viewerId != null) {
            blocked.addAll(blacklistMapper.selectBlockedIds(viewerId));
            blocked.addAll(blacklistMapper.selectBlockerIds(viewerId));
        }
        String lower = keyword.toLowerCase();
        List<Long> matched = new ArrayList<>();
        for (Long id : preferred) {
            User u = users.loadUsers(Collections.singletonList(id)).get(id);
            if (u != null && u.getNickname() != null
                    && u.getNickname().toLowerCase().contains(lower)
                    && !u.getId().equals(viewerId)) {
                matched.add(id);
            }
        }
        if (matched.size() < limit) {
            for (SocialUserQueryMapper.UserSearchRow row : userQuery.search(keyword, limit)) {
                if (!row.getId().equals(viewerId) && !blocked.contains(row.getId())
                        && !matched.contains(row.getId())) {
                    matched.add(row.getId());
                }
            }
        }
        return users.briefs(matched.stream().limit(Math.max(1, limit)).collect(Collectors.toList()),
                viewerId).stream()
                .filter(v -> v.getId() == null || !Objects.equals(v.getId(), viewerId))
                .collect(Collectors.toList());
    }

    private List<MentionRef> parseFromText(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptyList();
        }
        Matcher matcher = AT.matcher(content);
        List<MentionRef> refs = new ArrayList<>();
        while (matcher.find()) {
            String nickname = matcher.group(1);
            List<SocialUserQueryMapper.UserSearchRow> rows = userQuery.search(nickname, 5);
            List<SocialUserQueryMapper.UserSearchRow> exact = rows.stream()
                    .filter(r -> nickname.equals(r.getNickname()))
                    .collect(Collectors.toList());
            if (exact.size() != 1) {
                continue;
            }
            MentionRef ref = new MentionRef();
            ref.setUserId(exact.get(0).getId());
            ref.setOffset(matcher.start());
            ref.setLength(matcher.end() - matcher.start());
            refs.add(ref);
        }
        return refs;
    }
}
