-- 模块 12（播放体验升级）增量脚本：已有库升级用。
-- 全部 CREATE TABLE IF NOT EXISTS；不含演示数据，可重复执行。
-- 本机 3306 是共享实例，所有对象都显式带 newmusic. 库名。
USE `newmusic`;

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_play_setting` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `default_quality` VARCHAR(16)     NOT NULL DEFAULT 'high' COMMENT 'standard/high/lossless',
  `play_mode`       VARCHAR(16)     NOT NULL DEFAULT 'loop' COMMENT 'order/loop/single/shuffle',
  `auto_quality`    TINYINT         NOT NULL DEFAULT 1 COMMENT '自动降质开关',
  `data_saver`      TINYINT         NOT NULL DEFAULT 0 COMMENT '省流量模式',
  `fade_enabled`    TINYINT         NOT NULL DEFAULT 1 COMMENT '淡入淡出（P2 预留）',
  `equalizer_preset` VARCHAR(16)    NOT NULL DEFAULT 'default' COMMENT '均衡器预设（P2 预留）',
  `equalizer_bands` VARCHAR(128)    DEFAULT NULL COMMENT '自定义 EQ 频段 JSON（P2 预留）',
  `playback_rate`   DECIMAL(3,2)    NOT NULL DEFAULT 1.00 COMMENT '默认倍速 0.5~2.0',
  `lyric_mode`      VARCHAR(32)     NOT NULL DEFAULT 'original_translation' COMMENT '歌词显示模式',
  `lyric_offset`    INT             NOT NULL DEFAULT 0 COMMENT '全局歌词偏移（毫秒）',
  `theme`           VARCHAR(16)     NOT NULL DEFAULT 'system' COMMENT 'system/light/dark',
  `auto_download`   TINYINT         NOT NULL DEFAULT 0 COMMENT '自动下载（P2 预留）',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`       BIGINT UNSIGNED DEFAULT NULL,
  `update_by`       BIGINT UNSIGNED DEFAULT NULL,
  `deleted`         TINYINT         NOT NULL DEFAULT 0,
  `version`         INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_play_setting_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户播放设置';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_device` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `device_id`        VARCHAR(64)     NOT NULL COMMENT '前端生成的 UUID（localStorage）',
  `device_name`      VARCHAR(128)    DEFAULT NULL COMMENT '如 Chrome on Windows',
  `device_type`      VARCHAR(32)     NOT NULL DEFAULT 'WEB' COMMENT 'WEB/PWA/MOBILE_WEB',
  `last_active_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_ip`          VARCHAR(64)     DEFAULT NULL,
  `status`           TINYINT         NOT NULL DEFAULT 1 COMMENT '1活跃 2已登出',
  `create_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`        BIGINT UNSIGNED DEFAULT NULL,
  `update_by`        BIGINT UNSIGNED DEFAULT NULL,
  `deleted`          TINYINT         NOT NULL DEFAULT 0,
  `version`          INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_user` (`user_id`, `device_id`),
  KEY `idx_device_active` (`user_id`, `status`, `last_active_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户播放设备';
