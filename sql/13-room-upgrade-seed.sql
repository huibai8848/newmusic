-- 模块 13（音乐房功能升级）增量脚本：已有库升级用。
-- 全部 CREATE TABLE IF NOT EXISTS / 条件 ALTER，可重复执行；不含演示数据。
-- 本机 3306 是共享实例，所有对象都显式带 newmusic. 库名。

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. t_room_queue 升级：优先级、投票数、排序索引
--    PRD 22 的字段名是 requested_by；本库已有 user_id 承担同一语义，
--    为兼容模块 05 的接口与历史数据，不重命名。
-- -----------------------------------------------------------------------------

SET @add_priority = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_room_queue' AND COLUMN_NAME = 'priority') = 0,
  'ALTER TABLE `newmusic`.`t_room_queue` ADD COLUMN `priority` INT NOT NULL DEFAULT 0 COMMENT ''优先级：0普通 100房主置顶'' AFTER `source`',
  'SELECT 1');
PREPARE stmt FROM @add_priority;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_vote_count = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_room_queue' AND COLUMN_NAME = 'vote_count') = 0,
  'ALTER TABLE `newmusic`.`t_room_queue` ADD COLUMN `vote_count` INT NOT NULL DEFAULT 0 COMMENT ''合并点歌的投票数'' AFTER `priority`',
  'SELECT 1');
PREPARE stmt FROM @add_vote_count;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_order_index = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_room_queue' AND COLUMN_NAME = 'order_index') = 0,
  'ALTER TABLE `newmusic`.`t_room_queue` ADD COLUMN `order_index` BIGINT NOT NULL DEFAULT 0 COMMENT ''排序索引，越大越靠前'' AFTER `vote_count`',
  'SELECT 1');
PREPARE stmt FROM @add_order_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_queue_order_idx = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_room_queue' AND INDEX_NAME = 'idx_queue_room_order') = 0,
  'ALTER TABLE `newmusic`.`t_room_queue` ADD INDEX `idx_queue_room_order` (`room_id`, `queue_type`, `status`, `order_index` DESC)',
  'SELECT 1');
PREPARE stmt FROM @add_queue_order_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 旧数据补一次排序索引；只处理播放队列，排麦仍按 seq。
UPDATE `newmusic`.`t_room_queue`
SET `order_index` = `priority` * 10000 + `vote_count` * 100 + (1000 - MOD(`seq`, 1000))
WHERE `queue_type` = 2 AND `order_index` = 0;

-- -----------------------------------------------------------------------------
-- 2. 投票切歌
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `newmusic`.`t_room_vote` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `room_id`      BIGINT UNSIGNED NOT NULL,
  `song_id`      BIGINT UNSIGNED NOT NULL COMMENT '被投票跳过的歌曲',
  `initiator_id` BIGINT UNSIGNED NOT NULL COMMENT '发起人',
  `online_count` INT NOT NULL DEFAULT 0 COMMENT '发起时的在线人数快照',
  `agree_count`  INT NOT NULL DEFAULT 0 COMMENT '同意票数',
  `status`       TINYINT NOT NULL DEFAULT 1 COMMENT '1进行中 2通过 3未通过 4取消',
  `start_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `end_time`     DATETIME(3) NOT NULL COMMENT '投票截止时间',
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`    BIGINT UNSIGNED DEFAULT NULL,
  `update_by`    BIGINT UNSIGNED DEFAULT NULL,
  `deleted`      TINYINT NOT NULL DEFAULT 0,
  `version`      INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote_room_song` (`room_id`, `song_id`),
  KEY `idx_vote_room_status` (`room_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间投票切歌记录';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_room_vote_detail` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `vote_id`     BIGINT UNSIGNED NOT NULL,
  `room_id`     BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `agree`       TINYINT NOT NULL DEFAULT 1 COMMENT '1同意 0反对（本期只记录同意）',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote_detail_vote_user` (`vote_id`, `user_id`),
  KEY `idx_vote_detail_room` (`room_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间投票明细（Redis Set 的落库镜像）';

-- -----------------------------------------------------------------------------
-- 3. 房间回顾（P2 预留，仅建表，本期不写入）
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `newmusic`.`t_room_play_log` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `room_id`       BIGINT UNSIGNED NOT NULL,
  `song_id`       BIGINT UNSIGNED DEFAULT NULL,
  `song_name`     VARCHAR(200) DEFAULT NULL,
  `artist_name`   VARCHAR(100) DEFAULT NULL,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `play_duration` INT NOT NULL DEFAULT 0 COMMENT '实际播放秒数',
  `skip_type`     TINYINT NOT NULL DEFAULT 0 COMMENT '0正常播完 1房主跳过 2投票跳过',
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`     BIGINT UNSIGNED DEFAULT NULL,
  `update_by`     BIGINT UNSIGNED DEFAULT NULL,
  `deleted`       TINYINT NOT NULL DEFAULT 0,
  `version`       INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_play_log_room_time` (`room_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间播放记录（模块13 P2 预留）';

-- -----------------------------------------------------------------------------
-- 4. 说明
-- -----------------------------------------------------------------------------
-- 模块 13 新增 Redis 键（全部 ml: 前缀，与同实例其他项目的 ranking:* 互不影响）：
--   ml:room:play:{roomId}              Hash   权威播放态（含 startPositionMs/stateUpdateTimeMs/playStatus），TTL 24h
--   ml:room:ver:{roomId}               String 播放态版本号（沿用模块04的键），TTL 24h
--   ml:room:queue:{roomId}             String 已通过队列 JSON 快照（兼容模块05）
--   ml:room:vote:{roomId}:{songId}     Hash   投票元数据，TTL 2h
--   ml:room:vote:voters:{roomId}:{songId} Set 投票人，TTL 2h
--   ml:room:vote:active                Set    active vote keys，供定时过期任务使用，TTL 2h
--   ml:room:vote:cooldown:{roomId}     List   发起时间戳（最多10条），TTL 10min
--   ml:room:rank:{gift|active|request|listen}:{roomId} ZSet 榜单，TTL 24h
--   ml:room:rank:hidden:{roomId}       Set    选择不上榜的用户
--   ml:room:rank:notify:{roomId}:{type} String 榜单广播节流（30s）
--   ml:room:like:{roomId}:{songId}     String 点赞数，TTL 24h
--   ml:room:like:notify:{roomId}       String 点赞广播节流（2s）
--   ml:room:listen:last:{roomId}:{userId} String 收听时长上报校验，TTL 10min
