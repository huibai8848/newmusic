-- =============================================================================
-- 模块 16 社交能力升级：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~15，只需补动态/主页/关注流/消息中心相关表。
--
-- 安全约束（本机 3306 是共享实例，另有 emp_db/music_db）：
--   * 所有对象显式带 `newmusic.` 库名，不触碰其它库；
--   * 只做 CREATE TABLE IF NOT EXISTS / ADD COLUMN，不清表、不删数据；
--   * Redis 侧键全部以 `ml:` 开头，见 sql/README.md 的模块 16 小节。
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 0. 既有表扩展
--    MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断。
-- -----------------------------------------------------------------------------
SET @add_conv_type := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_conversation` ADD COLUMN `type` TINYINT NOT NULL DEFAULT 1 COMMENT ''1单聊 2群聊'' AFTER `user_b_id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_conversation' AND COLUMN_NAME='type'
);
PREPARE stmt FROM @add_conv_type; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_conv_group_name := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_conversation` ADD COLUMN `group_name` VARCHAR(64) DEFAULT NULL AFTER `type`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_conversation' AND COLUMN_NAME='group_name'
);
PREPARE stmt FROM @add_conv_group_name; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_conv_group_avatar := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_conversation` ADD COLUMN `group_avatar` VARCHAR(512) DEFAULT NULL AFTER `group_name`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_conversation' AND COLUMN_NAME='group_avatar'
);
PREPARE stmt FROM @add_conv_group_avatar; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_conv_owner := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_conversation` ADD COLUMN `owner_id` BIGINT UNSIGNED DEFAULT NULL AFTER `group_avatar`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_conversation' AND COLUMN_NAME='owner_id'
);
PREPARE stmt FROM @add_conv_owner; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_conv_member_count := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_conversation` ADD COLUMN `member_count` INT NOT NULL DEFAULT 0 AFTER `owner_id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_conversation' AND COLUMN_NAME='member_count'
);
PREPARE stmt FROM @add_conv_member_count; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_msg_quote := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_private_message` ADD COLUMN `quote_msg_id` BIGINT UNSIGNED DEFAULT NULL AFTER `content`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_private_message' AND COLUMN_NAME='quote_msg_id'
);
PREPARE stmt FROM @add_msg_quote; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_msg_forwarded := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_private_message` ADD COLUMN `forwarded` TINYINT NOT NULL DEFAULT 0 AFTER `quote_msg_id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_private_message' AND COLUMN_NAME='forwarded'
);
PREPARE stmt FROM @add_msg_forwarded; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_msg_mentions := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_private_message` ADD COLUMN `mentioned_user_ids` VARCHAR(500) DEFAULT NULL AFTER `forwarded`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_private_message' AND COLUMN_NAME='mentioned_user_ids'
);
PREPARE stmt FROM @add_msg_mentions; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 1. 用户主页扩展
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_profile` (
  `user_id`               BIGINT UNSIGNED NOT NULL COMMENT '用户ID（主键，与 t_user 1:1）',
  `cover_url`             VARCHAR(512)    DEFAULT NULL COMMENT '主页封面',
  `bio`                   VARCHAR(200)    DEFAULT NULL COMMENT '简介（≤100字）',
  `gender`                TINYINT         NOT NULL DEFAULT 0 COMMENT '0保密 1男 2女',
  `birthday`              DATE            DEFAULT NULL,
  `region`                VARCHAR(64)     DEFAULT NULL COMMENT '省市',
  `tags`                  VARCHAR(500)    DEFAULT NULL COMMENT '个人标签 JSON 数组',
  `user_code`             VARCHAR(32)     DEFAULT NULL COMMENT '唯一短码 4-20 字母数字',
  `follow_count`          INT             NOT NULL DEFAULT 0,
  `fans_count`            INT             NOT NULL DEFAULT 0,
  `post_count`           INT             NOT NULL DEFAULT 0,
  `like_received_count`   INT             NOT NULL DEFAULT 0,
  `total_play_duration`   BIGINT          NOT NULL DEFAULT 0 COMMENT '累计听歌秒数',
  `listen_days`           INT             NOT NULL DEFAULT 0,
  `privacy_json`          VARCHAR(1000)   DEFAULT NULL COMMENT '听歌统计隐私与展示开关',
  `profile_visibility`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0公开 1仅关注者 2仅自己',
  `cert_type`             TINYINT         NOT NULL DEFAULT 0 COMMENT '0无 1音乐人 2达人 3官方',
  `cert_info`             VARCHAR(200)    DEFAULT NULL,
  `nickname_update_time`  DATETIME(3)     DEFAULT NULL,
  `bio_update_date`       DATE            DEFAULT NULL,
  `avatar_update_date`    DATE            DEFAULT NULL,
  `create_time`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_profile_code` (`user_code`),
  KEY `idx_profile_fans` (`fans_count`),
  KEY `idx_profile_region` (`region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户主页扩展';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_profile_log` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `field`       VARCHAR(32)     NOT NULL,
  `old_value`   VARCHAR(500)    DEFAULT NULL,
  `new_value`   VARCHAR(500)    DEFAULT NULL,
  `ip`          VARCHAR(64)     DEFAULT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_profile_log_user` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人资料修改日志';

-- -----------------------------------------------------------------------------
-- 2. 动态
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_post` (
  `id`                  BIGINT UNSIGNED NOT NULL COMMENT '雪花ID',
  `user_id`             BIGINT UNSIGNED NOT NULL,
  `type`                TINYINT         NOT NULL COMMENT '1纯文本 2分享歌曲 3分享歌单 4分享歌词 5听歌打卡 6创建歌单 7转发',
  `content`             VARCHAR(1000)   DEFAULT NULL,
  `images`              VARCHAR(2000)   DEFAULT NULL COMMENT '图片URL JSON 数组，≤9',
  `song_id`             BIGINT UNSIGNED DEFAULT NULL,
  `playlist_id`         BIGINT UNSIGNED DEFAULT NULL,
  `lyric_snippet`       VARCHAR(500)    DEFAULT NULL,
  `repost_post_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '直接转发的原动态',
  `root_post_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '转发链根动态（只保留1层）',
  `topics`              VARCHAR(500)    DEFAULT NULL COMMENT '话题 JSON 数组，≤3',
  `like_count`          INT             NOT NULL DEFAULT 0,
  `comment_count`       INT             NOT NULL DEFAULT 0,
  `repost_count`        INT             NOT NULL DEFAULT 0,
  `collect_count`       INT             NOT NULL DEFAULT 0,
  `view_count`          INT             NOT NULL DEFAULT 0,
  `visibility`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0公开 1仅关注者 2仅自己',
  `status`              TINYINT         NOT NULL DEFAULT 1 COMMENT '0审核中 1已发布 2已删除 3仅自己可见 4管理员删除',
  `audit_status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2拒绝',
  `audit_reason`        VARCHAR(200)    DEFAULT NULL,
  `image_audit_status`  TINYINT         NOT NULL DEFAULT 1 COMMENT '0待审 1通过 2拒绝',
  `edited`              TINYINT         NOT NULL DEFAULT 0,
  `ip_region`           VARCHAR(64)     DEFAULT NULL,
  `create_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`           BIGINT UNSIGNED DEFAULT NULL,
  `update_by`           BIGINT UNSIGNED DEFAULT NULL,
  `deleted`             TINYINT         NOT NULL DEFAULT 0,
  `version`             INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_post_user_time` (`user_id`, `create_time`),
  KEY `idx_post_create_time` (`create_time`),
  KEY `idx_post_song` (`song_id`, `create_time`),
  KEY `idx_post_status_time` (`status`, `create_time`),
  KEY `idx_post_repost` (`repost_post_id`),
  KEY `idx_post_root` (`root_post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户动态';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_post_mention` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `source_type`       TINYINT         NOT NULL COMMENT '1动态 2评论 3私信',
  `source_id`         BIGINT UNSIGNED NOT NULL,
  `author_id`         BIGINT UNSIGNED NOT NULL,
  `mentioned_user_id` BIGINT UNSIGNED NOT NULL,
  `offset`            INT             NOT NULL COMMENT 'UTF-16 偏移，与前端 JS 一致',
  `length`            INT             NOT NULL COMMENT '@昵称的字符长度',
  `notified`          TINYINT         NOT NULL DEFAULT 0,
  `create_time`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_mention_source` (`source_type`, `source_id`),
  KEY `idx_mention_user` (`mentioned_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='@提及';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_post_like` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `post_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_like` (`user_id`, `post_id`),
  KEY `idx_post_like_post` (`post_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态点赞（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_post_collect` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `post_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_collect` (`user_id`, `post_id`),
  KEY `idx_post_collect_post` (`post_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态收藏（物理删除）';

-- Redis 为主存储，本表只做持久化/重建兜底。10 万 DAU 时按 user_id 取模分 16 表，
-- 当前开发规模保持单表，避免在无分库中间件的情况下引入跨表路由复杂度。
CREATE TABLE IF NOT EXISTS `newmusic`.`t_feed_inbox` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `post_id`     BIGINT UNSIGNED NOT NULL,
  `author_id`   BIGINT UNSIGNED NOT NULL,
  `score`       BIGINT          NOT NULL COMMENT '发布时间戳（毫秒）',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feed_user_post` (`user_id`, `post_id`),
  KEY `idx_feed_user_score` (`user_id`, `score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关注流收件箱（Redis 兜底）';

-- -----------------------------------------------------------------------------
-- 3. 话题 / 亲密度 / 粉丝榜
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_topic` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(64)     NOT NULL,
  `post_count`  INT             NOT NULL DEFAULT 0,
  `view_count`  INT             NOT NULL DEFAULT 0,
  `hot_score`   INT             NOT NULL DEFAULT 0,
  `is_official` TINYINT         NOT NULL DEFAULT 0,
  `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0正常 1屏蔽',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_name` (`name`),
  KEY `idx_topic_hot` (`status`, `hot_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_relation_score` (
  `id`                 BIGINT UNSIGNED NOT NULL,
  `user_id`            BIGINT UNSIGNED NOT NULL,
  `target_user_id`     BIGINT UNSIGNED NOT NULL,
  `score`              INT             NOT NULL DEFAULT 0,
  `level`              TINYINT         NOT NULL DEFAULT 0,
  `last_interact_time` DATETIME(3)     DEFAULT NULL,
  `create_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation_pair` (`user_id`, `target_user_id`),
  KEY `idx_relation_target_score` (`target_user_id`, `score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='亲密度';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_fans_rank` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '榜主',
  `rank_type`   TINYINT         NOT NULL DEFAULT 1 COMMENT '1铁粉榜 2互动榜 3礼物榜',
  `period`      VARCHAR(16)     NOT NULL DEFAULT 'DAILY',
  `stat_date`   DATE            NOT NULL,
  `rank_data`   TEXT            NOT NULL COMMENT '[{userId,score,rank}]',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fans_rank` (`user_id`, `rank_type`, `period`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='粉丝榜快照';

-- -----------------------------------------------------------------------------
-- 4. 消息中心聚合 / 群聊成员
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_notification_aggregate` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `aggregate_key`   VARCHAR(128)    NOT NULL,
  `notification_id` BIGINT UNSIGNED NOT NULL,
  `participant_ids` VARCHAR(1000)   DEFAULT NULL COMMENT '参与者ID JSON 数组',
  `count`           INT             NOT NULL DEFAULT 0,
  `expire_time`     DATETIME(3)     DEFAULT NULL,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_agg` (`user_id`, `aggregate_key`),
  KEY `idx_notify_agg_notification` (`notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知聚合记录';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_conversation_member` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `conversation_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `role`             TINYINT         NOT NULL DEFAULT 3 COMMENT '1群主 2管理员 3成员',
  `pinned`           TINYINT         NOT NULL DEFAULT 0,
  `archived`         TINYINT         NOT NULL DEFAULT 0,
  `muted`            TINYINT         NOT NULL DEFAULT 0,
  `last_read_msg_id` BIGINT UNSIGNED DEFAULT NULL,
  `joined_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `quit_time`        DATETIME(3)     DEFAULT NULL,
  `create_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv_user` (`conversation_id`, `user_id`),
  KEY `idx_member_user` (`user_id`, `archived`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话成员';

-- -----------------------------------------------------------------------------
-- 5. 后台权限点（FR-25 接口清单）
--    ID 9130~9132 与模块 15 的 9120~9124 相邻但不冲突；授权 9498~9504。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9130, 'admin:content:view',  '动态与话题查看', '内容', 0, 950, 1),
  (9131, 'admin:content:audit', '动态审核',       '内容', 0, 951, 1),
  (9132, 'admin:content:edit',  '动态与话题管理', '内容', 1, 952, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9498, 1, 9130),
  (9499, 1, 9131),
  (9500, 1, 9132),
  (9501, 2, 9130),
  (9502, 2, 9131),
  (9503, 2, 9132),
  (9504, 3, 9130);
