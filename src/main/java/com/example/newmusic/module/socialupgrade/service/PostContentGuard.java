package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.user.entity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 动态发布前置校验：频率、长度、敏感词分级、重复内容、推广拦截。
 *
 * <p>敏感词库沿用模块 11 的 {@link SensitiveWordSupport}（当前默认空词典），
 * 模块 16 另外支持在配置里声明「高危/疑似」两组词做分级：高危直接拒绝，
 * 疑似先发但仅自己可见。没有配置时不误伤任何正常内容。
 */
@Slf4j
@Component
public class PostContentGuard {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOPIC = Pattern.compile("#([^#\\s]{2,20})#");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SocialUpgradeProperties properties;
    private final RedisOps redisOps;
    private final SensitiveWordSupport sensitiveWords;

    public PostContentGuard(SocialUpgradeProperties properties, RedisOps redisOps,
                            SensitiveWordSupport sensitiveWords) {
        this.properties = properties;
        this.redisOps = redisOps;
        this.sensitiveWords = sensitiveWords;
    }

    /** @return 0 审核中/仅自己可见，1 正常发布 */
    public int check(User user, UserProfile profile, String content, List<String> images,
                     Long songId, Long playlistId, String lyricSnippet, List<String> topics) {
        checkRate(user, profile);
        return validate(user.getId(), content, images, songId, playlistId, lyricSnippet, topics, true);
    }

    /**
     * 编辑已有动态时的校验：不消耗发布配额，也不与自己的原内容判重
     * （否则「不做修改地点保存」会被自己的历史指纹拦下）。
     */
    public int recheck(Long userId, String content, List<String> images,
                       Long songId, Long playlistId, String lyricSnippet, List<String> topics) {
        return validate(userId, content, images, songId, playlistId, lyricSnippet, topics, false);
    }

    /** 转发附言只做长度与违规校验，不消耗独立发布配额。 */
    public void checkRepost(String content) {
        if (content != null && content.length() > properties.getPost().getRepostCommentMax()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "转发附言最多 " + properties.getPost().getRepostCommentMax() + " 字");
        }
        if (sensitiveWords.isBlocked(content)
                || containsAny(content, properties.getPost().getHighRiskWords())) {
            throw new BizException(ResultCode.POST_ILLEGAL);
        }
        if (StringUtils.hasText(properties.getPost().getContactPattern())
                && Pattern.compile(properties.getPost().getContactPattern()).matcher(
                content == null ? "" : content).find()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容包含联系方式或推广信息");
        }
        if (StringUtils.hasText(properties.getPost().getLinkPattern())
                && Pattern.compile(properties.getPost().getLinkPattern()).matcher(
                content == null ? "" : content).find()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容包含外部链接");
        }
    }

    private int validate(Long userId, String content, List<String> images,
                         Long songId, Long playlistId, String lyricSnippet,
                         List<String> topics, boolean checkDuplicate) {
        int textLength = content == null ? 0 : content.length();
        if (textLength > properties.getPost().getTextMax()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "动态正文最多 " + properties.getPost().getTextMax() + " 字");
        }
        boolean empty = !StringUtils.hasText(content)
                && (images == null || images.isEmpty())
                && songId == null && playlistId == null
                && !StringUtils.hasText(lyricSnippet);
        if (empty) {
            throw new BizException(ResultCode.POST_CONTENT_EMPTY);
        }
        if (images != null && images.size() > properties.getPost().getImagesMax()) {
            throw new BizException(ResultCode.POST_IMAGE_LIMIT);
        }
        if (topics != null && topics.size() > properties.getPost().getTopicsMax()) {
            throw new BizException(ResultCode.POST_TOPIC_LIMIT);
        }
        for (String topic : topics == null ? Collections.<String>emptyList() : topics) {
            if (!StringUtils.hasText(topic) || topic.length() < 2 || topic.length() > 20
                    || sensitiveWords.isBlocked(topic)) {
                throw new BizException(ResultCode.PARAM_INVALID, "话题名需为 2-20 字且不能包含违规词");
            }
        }
        if (lyricSnippet != null && lyricSnippet.length() > 100) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌词片段最多 100 字");
        }

        String all = (content == null ? "" : content) + " " + (lyricSnippet == null ? "" : lyricSnippet);
        if (containsAny(all, properties.getPost().getHighRiskWords()) || sensitiveWords.isBlocked(all)) {
            throw new BizException(ResultCode.POST_ILLEGAL);
        }
        if (containsAny(all, properties.getPost().getSuspectWords())) {
            return UserPost.STATUS_SELF_ONLY;
        }
        if (StringUtils.hasText(properties.getPost().getContactPattern())
                && Pattern.compile(properties.getPost().getContactPattern()).matcher(all).find()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容包含联系方式或推广信息");
        }
        if (StringUtils.hasText(properties.getPost().getLinkPattern())
                && Pattern.compile(properties.getPost().getLinkPattern()).matcher(all).find()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容包含外部链接");
        }
        if (checkDuplicate && StringUtils.hasText(content) && duplicated(userId, content)) {
            throw new BizException(ResultCode.POST_DUPLICATED);
        }
        return UserPost.STATUS_PUBLISHED;
    }

    public List<String> extractTopics(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptyList();
        }
        Set<String> topics = new java.util.LinkedHashSet<>();
        Matcher matcher = TOPIC.matcher(content);
        while (matcher.find()) {
            topics.add(matcher.group(1));
        }
        return new ArrayList<>(topics);
    }

    private void checkRate(User user, UserProfile profile) {
        LocalDate today = LocalDate.now();
        String day = today.format(DAY);
        String hour = today + "_" + LocalDateTime.now().getHour();
        String minute = LocalDateTime.now().withSecond(0).withNano(0).toString();

        boolean newUser = user.getCreateTime() != null
                && user.getCreateTime().isAfter(LocalDateTime.now()
                .minusDays(properties.getPost().getNewUserDays()));
        boolean certified = profile.getCertType() != null && profile.getCertType() > 0;
        int dailyLimit = newUser ? properties.getPost().getNewUserDaily()
                : (certified ? properties.getPost().getCertifiedDaily()
                : properties.getPost().getNormalDaily());

        long daily = redisOps.increment(RedisKeys.postRate(user.getId(), "d:" + day),
                Duration.ofDays(1));
        if (daily > dailyLimit) {
            throw new BizException(ResultCode.POST_RATE_LIMITED,
                    "今日发布配额已用完（" + dailyLimit + " 条）");
        }
        if (!newUser && !certified) {
            long hourly = redisOps.increment(RedisKeys.postRate(user.getId(), "h:" + hour),
                    Duration.ofHours(1));
            if (hourly > properties.getPost().getNormalHourly()) {
                throw new BizException(ResultCode.POST_RATE_LIMITED, "每小时最多发布 5 条");
            }
        }
        long perMinute = redisOps.increment(RedisKeys.postRate(user.getId(), "m:" + minute),
                Duration.ofMinutes(1));
        if (perMinute > properties.getPost().getMinuteGuard()) {
            throw new BizException(ResultCode.POST_RATE_LIMITED, "发布过于频繁，请稍后再试");
        }
    }

    private boolean duplicated(Long userId, String content) {
        String key = RedisKeys.postDedup(userId);
        List<String> recent = recentContents(key);
        String normalized = normalize(content);
        for (String old : recent) {
            if (similarity(normalized, old) > properties.getPost().getDuplicateSimilarity()) {
                return true;
            }
        }
        recent.add(normalized);
        while (recent.size() > 20) {
            recent.remove(0);
        }
        redisOps.delete(key);
        try {
            String json = JSON.writeValueAsString(recent);
            redisOps.set(key, json, Duration.ofHours(24));
        } catch (Exception e) {
            log.debug("动态去重指纹写入失败 userId={}", userId);
        }
        return false;
    }

    private List<String> recentContents(String key) {
        return redisOps.get(key).map(json -> {
            try {
                return new ArrayList<>(JSON.readValue(json, new TypeReference<List<String>>() {
                }));
            } catch (Exception e) {
                return new ArrayList<String>();
            }
        }).orElseGet(ArrayList::new);
    }

    private static String normalize(String content) {
        return content.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s，。！？、；：“”‘’（）【】]", "");
    }

    /** 字符二元组 Jaccard 相似度，足以拦住「复制后再加几个表情」的重复发布。 */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    private static boolean containsAny(String text, String words) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(words)) {
            return false;
        }
        return Arrays.stream(words.split("[,，\\s]+"))
                .filter(StringUtils::hasText)
                .anyMatch(text::contains);
    }
}
