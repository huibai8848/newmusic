package com.example.newmusic.module.socialupgrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 模块 16 配置，对应 {@code newmusic.social-upgrade.*}。 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.social-upgrade")
public class SocialUpgradeProperties {

    private Feed feed = new Feed();
    private Post post = new Post();
    private Mention mention = new Mention();
    private Notification notification = new Notification();
    private Intimacy intimacy = new Intimacy();

    @Data
    public static class Feed {
        /** 粉丝数达到该值的作者走拉模式（BR-25-047） */
        private int fanPushThreshold = 5000;
        /** 收件箱保留条数（BR-25-048） */
        private int inboxCapacity = 1000;
        private int inboxTtlDays = 30;
        /** 单次最多扫描多少条再做过滤（BR-25-052） */
        private int maxScan = 200;
        private int scanChunk = 60;
    }

    @Data
    public static class Post {
        private int textMax = 500;
        private int imagesMax = 9;
        private int topicsMax = 3;
        private int repostCommentMax = 200;
        private int newUserDays = 7;
        private int newUserDaily = 3;
        private int normalDaily = 20;
        private int normalHourly = 5;
        private int certifiedDaily = 50;
        private int minuteGuard = 5;
        private int editMinutes = 5;
        private double duplicateSimilarity = 0.9;
        /** 高危词：命中直接拒绝 */
        private String highRiskWords = "";
        /** 疑似词：命中先发但仅自己可见 */
        private String suspectWords = "";
        /** 推广/联系方式拦截正则 */
        private String contactPattern = "(?i)(微信|weixin|wechat|qq|vx)\\s*[:：]?\\s*[a-z0-9_-]{5,}|1[3-9]\\d{9}";
        /** 外部链接拦截 */
        private String linkPattern = "(?i)https?://|www\\.";
    }

    @Data
    public static class Mention {
        private int maxPerContent = 10;
        private int dailyLimit = 50;
        /** 同一作者对同一用户每天最多触发几次通知（BR-25-078） */
        private int notifyPerTargetPerDay = 3;
    }

    @Data
    public static class Notification {
        /** 非私信通知最小推送间隔（秒），BR-25-093 */
        private int minPushIntervalSeconds = 60;
        /** 互动聚合时间桶 */
        private int interactionBucketHours = 1;
        /** 关注/音乐聚合时间桶 */
        private int relationBucketHours = 24;
        private int retainDays = 90;
    }

    @Data
    public static class Intimacy {
        private int dailyCapPerTarget = 10;
        private double decayLambda = 0.0077;
    }
}
