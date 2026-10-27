-- =============================================================================
-- 模块 10 社交基础：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~09，只需补社交互动相关的 12 张表。
-- 全新库直接执行 schema.sql + data.sql 即可（schema.sql 已包含本节全部 DDL）。
--
-- 安全约束：
--   * 所有对象显式带 `newmusic.` 库名；本机 3306 是共享实例（还有 emp_db/music_db）
--   * 只做 CREATE TABLE IF NOT EXISTS，不清表、不删数据
--   * 本脚本无种子数据；社交数据全部由用户行为产生
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 0. 既有内容表增加互动计数列（模块 10）
--    MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断。
--    只加列、不删不改，默认 0，不影响存量数据。
-- -----------------------------------------------------------------------------
SET @add_pl_like := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_playlist` ADD COLUMN `like_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''点赞数[10]'' AFTER `collect_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_playlist' AND COLUMN_NAME = 'like_count'
);
PREPARE stmt FROM @add_pl_like; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_album_like := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_album` ADD COLUMN `like_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''点赞数[10]'' AFTER `song_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_album' AND COLUMN_NAME = 'like_count'
);
PREPARE stmt FROM @add_album_like; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_album_collect := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_album` ADD COLUMN `collect_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''收藏数[10]'' AFTER `like_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_album' AND COLUMN_NAME = 'collect_count'
);
PREPARE stmt FROM @add_album_collect; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_album_comment := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_album` ADD COLUMN `comment_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''评论数[10]'' AFTER `collect_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_album' AND COLUMN_NAME = 'comment_count'
);
PREPARE stmt FROM @add_album_comment; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_album_share := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_album` ADD COLUMN `share_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''分享数[10]'' AFTER `comment_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_album' AND COLUMN_NAME = 'share_count'
);
PREPARE stmt FROM @add_album_share; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 26. 社交互动域（模块 10）
-- 设计：docs/系分/10-社交基础.md
-- 说明：评论/会话/私信/设置用逻辑删除；点赞/收藏/关注/拉黑/歌手关注是关联表，
--       物理删除 + 唯一索引保证幂等（见 BaseLinkEntity 注释）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_comment` (
  `id`             BIGINT UNSIGNED NOT NULL COMMENT '评论ID(雪花)',
  `target_type`    VARCHAR(20)     NOT NULL COMMENT 'SONG/PLAYLIST/ALBUM/ROOM/COMMENT',
  `target_id`      BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '评论人',
  `root_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '根评论ID，顶级为0',
  `parent_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '直接父评论ID，顶级为0',
  `reply_user_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '被回复用户ID',
  `content`        VARCHAR(500)    NOT NULL COMMENT '纯文本（已剔除HTML标签）',
  `like_count`     INT             NOT NULL DEFAULT 0,
  `reply_count`    INT             NOT NULL DEFAULT 0,
  `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '0待审 1已发布 2已删除 3已屏蔽',
  `audit_reason`   VARCHAR(200)    DEFAULT NULL,
  `ip`             VARCHAR(64)     DEFAULT NULL,
  `user_agent`     VARCHAR(500)    DEFAULT NULL,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT         NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_comment_target` (`target_type`, `target_id`, `root_id`, `status`, `create_time`),
  KEY `idx_comment_root` (`root_id`, `create_time`),
  KEY `idx_comment_user` (`user_id`, `create_time`),
  KEY `idx_comment_hot` (`target_type`, `target_id`, `root_id`, `like_count`, `reply_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论（两级）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_like` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(20)     NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_like_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_like_target` (`target_type`, `target_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点赞关系（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_collect` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(20)     NOT NULL COMMENT 'SONG/PLAYLIST/ALBUM/ROOM',
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `folder_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL=默认收藏夹',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_collect_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_collect_user_time` (`user_id`, `target_type`, `create_time`),
  KEY `idx_collect_folder` (`folder_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏关系（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_collect_folder` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(30)     NOT NULL,
  `is_default`  TINYINT         NOT NULL DEFAULT 0,
  `item_count`  INT             NOT NULL DEFAULT 0,
  `sort_order`  INT             NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_folder_user_name` (`user_id`, `name`),
  KEY `idx_folder_user_sort` (`user_id`, `sort_order`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏夹（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_share` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `code`        VARCHAR(16)     NOT NULL COMMENT '短码Base62(6位)',
  `target_type` VARCHAR(20)     NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `sharer_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '未登录分享为NULL',
  `channel`     VARCHAR(20)     DEFAULT NULL COMMENT 'COPY_LINK/IN_APP/SYSTEM/THIRD_PARTY',
  `click_count` INT             NOT NULL DEFAULT 0,
  `expire_time` DATETIME(3)     DEFAULT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`code`),
  KEY `idx_share_target` (`target_type`, `target_id`),
  KEY `idx_share_sharer` (`sharer_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分享短链';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_follow` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `follower_id`  BIGINT UNSIGNED NOT NULL COMMENT '关注者',
  `following_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者',
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow_pair` (`follower_id`, `following_id`),
  KEY `idx_follow_following` (`following_id`, `create_time`),
  KEY `idx_follow_follower` (`follower_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注（单向，物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_artist_follow` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `artist_id`   BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artist_follow_pair` (`user_id`, `artist_id`),
  KEY `idx_artist_follow_artist` (`artist_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌手关注（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_blacklist` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '拉黑发起人',
  `blocked_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被拉黑者',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blacklist_pair` (`user_id`, `blocked_user_id`),
  KEY `idx_blacklist_blocked` (`blocked_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户黑名单（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_conversation` (
  `id`                   BIGINT UNSIGNED NOT NULL,
  `user_a_id`            BIGINT UNSIGNED NOT NULL COMMENT '较小的userId',
  `user_b_id`            BIGINT UNSIGNED NOT NULL COMMENT '较大的userId',
  `last_message_id`      BIGINT UNSIGNED DEFAULT NULL,
  `last_message_time`    DATETIME(3)     DEFAULT NULL,
  `last_message_preview` VARCHAR(100)    DEFAULT NULL,
  `unread_a`             INT             NOT NULL DEFAULT 0 COMMENT 'A未读数（B发来的）',
  `unread_b`             INT             NOT NULL DEFAULT 0 COMMENT 'B未读数（A发来的）',
  `create_time`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`            BIGINT UNSIGNED DEFAULT NULL,
  `update_by`            BIGINT UNSIGNED DEFAULT NULL,
  `deleted`              TINYINT         NOT NULL DEFAULT 0,
  `version`              INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_pair` (`user_a_id`, `user_b_id`),
  KEY `idx_conversation_a_time` (`user_a_id`, `last_message_time`),
  KEY `idx_conversation_b_time` (`user_b_id`, `last_message_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信会话';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_private_message` (
  `id`                  BIGINT UNSIGNED NOT NULL,
  `conversation_id`     BIGINT UNSIGNED NOT NULL,
  `sender_id`           BIGINT UNSIGNED NOT NULL,
  `receiver_id`         BIGINT UNSIGNED NOT NULL,
  `msg_type`            VARCHAR(20)     NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/SONG/PLAYLIST/ROOM',
  `content`             VARCHAR(1000)   NOT NULL,
  `is_read`             TINYINT         NOT NULL DEFAULT 0,
  `read_time`           DATETIME(3)     DEFAULT NULL,
  `is_revoked`          TINYINT         NOT NULL DEFAULT 0,
  `revoke_time`         DATETIME(3)     DEFAULT NULL,
  `deleted_by_sender`   TINYINT         NOT NULL DEFAULT 0,
  `deleted_by_receiver` TINYINT         NOT NULL DEFAULT 0,
  `create_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`           BIGINT UNSIGNED DEFAULT NULL,
  `update_by`           BIGINT UNSIGNED DEFAULT NULL,
  `deleted`             TINYINT         NOT NULL DEFAULT 0,
  `version`             INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_message_conv` (`conversation_id`, `create_time`),
  KEY `idx_message_receiver_read` (`receiver_id`, `is_read`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信消息';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_notification_setting` (
  `id`                 BIGINT UNSIGNED NOT NULL,
  `user_id`            BIGINT UNSIGNED NOT NULL,
  `like_enabled`       TINYINT         NOT NULL DEFAULT 1,
  `comment_enabled`    TINYINT         NOT NULL DEFAULT 1,
  `follow_enabled`     TINYINT         NOT NULL DEFAULT 1,
  `message_enabled`    TINYINT         NOT NULL DEFAULT 1,
  `system_enabled`     TINYINT         NOT NULL DEFAULT 1,
  `new_song_enabled`   TINYINT         NOT NULL DEFAULT 1,
  `read_receipt_enabled` TINYINT       NOT NULL DEFAULT 1,
  `create_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`          BIGINT UNSIGNED DEFAULT NULL,
  `update_by`          BIGINT UNSIGNED DEFAULT NULL,
  `deleted`            TINYINT         NOT NULL DEFAULT 0,
  `version`            INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_setting_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知设置';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_privacy_setting` (
  `id`                     BIGINT UNSIGNED NOT NULL,
  `user_id`                BIGINT UNSIGNED NOT NULL,
  `message_policy`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0所有人 1互关 2关闭',
  `comment_policy`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0所有人 1关注我的人 2关闭',
  `mention_policy`         TINYINT         NOT NULL DEFAULT 0,
  `show_following`         TINYINT         NOT NULL DEFAULT 1,
  `show_followers`         TINYINT         NOT NULL DEFAULT 1,
  `play_history_visibility` TINYINT        NOT NULL DEFAULT 2 COMMENT '0所有人 1互关 2仅自己',
  `read_receipt`           TINYINT         NOT NULL DEFAULT 1,
  `new_song_notify`        TINYINT         NOT NULL DEFAULT 1,
  `system_notify`          TINYINT         NOT NULL DEFAULT 1,
  `create_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`              BIGINT UNSIGNED DEFAULT NULL,
  `update_by`              BIGINT UNSIGNED DEFAULT NULL,
  `deleted`                TINYINT         NOT NULL DEFAULT 0,
  `version`                INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_privacy_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户隐私设置';
