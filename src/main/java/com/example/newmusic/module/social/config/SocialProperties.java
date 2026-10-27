package com.example.newmusic.module.social.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 社交模块配置（模块 10）。对应 {@code newmusic.social.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "newmusic.social")
public class SocialProperties {

    private Comment comment = new Comment();
    private Follow follow = new Follow();
    private Blacklist blacklist = new Blacklist();
    private Folder folder = new Folder();
    private Message message = new Message();
    private Share share = new Share();
    private Report report = new Report();

    @Data
    public static class Comment {
        /** 新用户注册后多少天内的评论需要先审后发 */
        private int newUserAuditDays = 7;
    }

    @Data
    public static class Follow {
        private int limit = 2000;
        /** 粉丝数超过该值后不再维护 Redis fans Set（避免大 key） */
        private int fansSetLimit = 10000;
    }

    @Data
    public static class Blacklist {
        private int limit = 500;
    }

    @Data
    public static class Folder {
        private int limit = 50;
        private int itemLimit = 5000;
    }

    @Data
    public static class Message {
        private int recallSeconds = 120;
        /** 非互关用户每天最多发几条 */
        private int nonMutualDailyLimit = 3;
    }

    @Data
    public static class Share {
        /** 短链前缀（后端重定向入口） */
        private String shortUrlPrefix = "http://localhost:8081/s/";
        /** 重定向后的 C 端页面地址 */
        private String webBaseUrl = "http://localhost:5173";
        private int ttlDays = 30;
    }

    @Data
    public static class Report {
        /** 同一用户同一目标同一理由的去重窗口（小时） */
        private int dedupHours = 24;
        /** 近 30 天举报达到该数量后开始检查有效率 */
        private int maliciousThreshold = 20;
        /** 有效率低于该百分比时拒绝继续举报 */
        private int maliciousValidPercent = 10;
    }
}
