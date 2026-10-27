-- =============================================================================
-- 模块 15 推荐系统升级：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已经初始化过模块 01~14，只需要补推荐升级相关表 / 权限 / 配置。
--
-- 与 PRD 24 的偏差（本环境没有 ClickHouse / Milvus / Kafka）：
--   * t_rec_log / t_rec_event 用 MySQL 承载，替代 ClickHouse 的 t_rec_log 与
--     t_experiment_log。字段与 PRD 对齐，保留 7 天由 job 清理；数据量大时可原样
--     迁到 ClickHouse，Java 侧只写这一张表。
--   * t_experiment 增加 whitelist_json 列：PRD 的白名单只在 Redis Set 里，
--     进程重启会丢；这里 DB 为权威，Redis 只是读取缓存。
--
-- 安全约束：
--   * 所有对象显式带 `newmusic.` 库名；本机 3306 是共享实例（还有 emp_db/music_db）
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，不清表、不删数据、不改他人库
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 推荐配置（按场景 + 类型 + 版本）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_rec_config` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `scene`       VARCHAR(32)     NOT NULL COMMENT '场景 HOME_FEED/DAILY/GLOBAL...',
  `config_type` VARCHAR(32)     NOT NULL COMMENT 'RECALL/FUSION/RANK/RERANK/EXPERIMENT',
  `config_json` TEXT            NOT NULL COMMENT '配置内容（JSON）',
  `version`     INT             NOT NULL DEFAULT 1 COMMENT '配置版本号（从 1 递增）',
  `status`      TINYINT         NOT NULL DEFAULT 1 COMMENT '0草稿 1生效 2历史',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '修改人（后台管理员）',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rec_config_ver` (`scene`, `config_type`, `version`),
  KEY `idx_rec_config_live` (`scene`, `config_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐配置（版本化）';

-- -----------------------------------------------------------------------------
-- 2. 实验（FR-24-008）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_experiment` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `experiment_key`  VARCHAR(64)     NOT NULL COMMENT '实验唯一标识',
  `name`            VARCHAR(128)    NOT NULL COMMENT '实验名',
  `description`     VARCHAR(500)    DEFAULT NULL,
  `layer`           VARCHAR(32)     NOT NULL DEFAULT 'rank_layer_v1' COMMENT '所属层',
  `mutex_group`     VARCHAR(64)     DEFAULT NULL COMMENT '互斥组',
  `traffic_percent` SMALLINT        NOT NULL DEFAULT 0 COMMENT '实验流量 0~100',
  `layer_salt`      VARCHAR(64)     NOT NULL DEFAULT 'rank_layer_v1' COMMENT '分层 salt',
  `groups_json`     TEXT            NOT NULL COMMENT '分组 [{name,ratio,configOverrides?}]',
  `primary_metric`  VARCHAR(64)     NOT NULL DEFAULT 'ctr' COMMENT '主指标（唯一）',
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿 1审批中 2运行中 3已停止 4已归档',
  `start_time`      DATETIME(3)     DEFAULT NULL,
  `end_time`        DATETIME(3)     DEFAULT NULL,
  `creator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `approver_id`     BIGINT UNSIGNED DEFAULT NULL,
  `conclusion`      VARCHAR(1000)   DEFAULT NULL,
  `whitelist_json`  TEXT            DEFAULT NULL COMMENT '白名单 {"subject":"groupName"}，DB 为权威，Redis 为缓存',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`       BIGINT UNSIGNED DEFAULT NULL,
  `update_by`       BIGINT UNSIGNED DEFAULT NULL,
  `deleted`         TINYINT         NOT NULL DEFAULT 0,
  `version`         INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_experiment_key` (`experiment_key`),
  KEY `idx_experiment_status_time` (`status`, `start_time`),
  KEY `idx_experiment_layer` (`layer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='A/B 实验';

-- -----------------------------------------------------------------------------
-- 3. 推荐请求日志（替代 ClickHouse t_rec_log；保留 7 天）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_rec_log` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `trace_id`        VARCHAR(64)     NOT NULL,
  `user_id`         BIGINT UNSIGNED DEFAULT NULL,
  `device_id`       VARCHAR(64)     DEFAULT NULL,
  `scene`           VARCHAR(32)     NOT NULL,
  `recall_result`   TEXT            DEFAULT NULL COMMENT '各源数量/耗时/成败 JSON',
  `candidate_count` INT             NOT NULL DEFAULT 0,
  `model_version`   VARCHAR(64)     DEFAULT NULL,
  `feature_version` VARCHAR(32)     DEFAULT NULL,
  `final_list`      MEDIUMTEXT      DEFAULT NULL COMMENT '最终列表 JSON（songId/score/sources）',
  `cost_ms`         TEXT            DEFAULT NULL COMMENT '各阶段耗时 JSON',
  `degraded`        TINYINT         NOT NULL DEFAULT 0,
  `degrade_reason`  VARCHAR(500)    DEFAULT NULL,
  `experiment_json` TEXT            DEFAULT NULL COMMENT '参与实验与分组 JSON',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_rec_log_trace` (`trace_id`),
  KEY `idx_rec_log_time` (`create_time`),
  KEY `idx_rec_log_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐请求日志（MySQL 兜底 ClickHouse）';

-- -----------------------------------------------------------------------------
-- 4. 推荐埋点事件（曝光/点击/播放/完成/跳过/喜欢/不喜欢/探索/新歌）
--    实验指标与显著性都从这张表聚合，口径只此一份（BR-24-092）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_rec_event` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `trace_id`       VARCHAR(64)     DEFAULT NULL,
  `user_id`        BIGINT UNSIGNED DEFAULT NULL,
  `device_id`      VARCHAR(64)     DEFAULT NULL,
  `scene`          VARCHAR(32)     NOT NULL DEFAULT 'HOME_FEED',
  `event_type`     VARCHAR(32)     NOT NULL COMMENT 'IMPRESSION/CLICK/PLAY/VALID_PLAY/COMPLETE/SKIP/LIKE/DISLIKE/EXPLORE_IMPRESSION/NEW_SONG_EXPOSURE',
  `experiment_key` VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '无实验为空串（便于唯一索引聚合）',
  `group_name`     VARCHAR(32)     NOT NULL DEFAULT '',
  `song_id`        BIGINT UNSIGNED DEFAULT NULL,
  `position`       INT             DEFAULT NULL,
  `play_duration`  INT             DEFAULT NULL,
  `user_type`      VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'NEW/OLD/UNKNOWN',
  `device_type`    VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'WEB/IOS/ANDROID/UNKNOWN',
  `hour_of_day`    TINYINT         NOT NULL DEFAULT 0,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_rec_event_exp` (`experiment_key`, `group_name`, `event_type`, `create_time`),
  KEY `idx_rec_event_trace` (`trace_id`),
  KEY `idx_rec_event_time` (`create_time`),
  KEY `idx_rec_event_scene_time` (`scene`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐埋点事件';

-- -----------------------------------------------------------------------------
-- 5. 推荐指标日报（由 t_rec_event 聚合；experiment_key 空串表示全量）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_rec_metric_daily` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `stat_date`       DATE            NOT NULL,
  `scene`           VARCHAR(32)     NOT NULL DEFAULT '',
  `experiment_key`  VARCHAR(64)     NOT NULL DEFAULT '',
  `group_name`      VARCHAR(32)     NOT NULL DEFAULT '',
  `exposure_cnt`    BIGINT          NOT NULL DEFAULT 0,
  `click_cnt`       BIGINT          NOT NULL DEFAULT 0,
  `play_cnt`        BIGINT          NOT NULL DEFAULT 0,
  `valid_play_cnt`  BIGINT          NOT NULL DEFAULT 0,
  `complete_cnt`    BIGINT          NOT NULL DEFAULT 0,
  `like_cnt`        BIGINT          NOT NULL DEFAULT 0,
  `dislike_cnt`     BIGINT          NOT NULL DEFAULT 0,
  `skip_cnt`        BIGINT          NOT NULL DEFAULT 0,
  `unique_users`    BIGINT          NOT NULL DEFAULT 0,
  `ctr`             DECIMAL(8,6)    NOT NULL DEFAULT 0,
  `completion_rate` DECIMAL(8,6)    NOT NULL DEFAULT 0,
  `avg_valid_play`  DECIMAL(8,4)    NOT NULL DEFAULT 0,
  `longtail_ratio`  DECIMAL(8,6)    NOT NULL DEFAULT 0,
  `diversity_score` DECIMAL(8,6)    NOT NULL DEFAULT 0,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rec_metric_daily` (`stat_date`, `scene`, `experiment_key`, `group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐指标日报';

-- -----------------------------------------------------------------------------
-- 6. 特征一致性校验记录（FR-24-005）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_rec_feature_consistency` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `check_date`    DATE            NOT NULL,
  `feature_name`  VARCHAR(64)     NOT NULL,
  `sample_size`   INT             NOT NULL DEFAULT 0,
  `offline_mean`  DECIMAL(12,6)   NOT NULL DEFAULT 0,
  `online_mean`   DECIMAL(12,6)   NOT NULL DEFAULT 0,
  `deviation`     DECIMAL(8,6)    NOT NULL DEFAULT 0 COMMENT '相对偏差',
  `passed`        TINYINT         NOT NULL DEFAULT 0,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_consistency` (`check_date`, `feature_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线上线下一日特征一致性校验';

-- -----------------------------------------------------------------------------
-- 7. 后台权限点（FR-24 接口清单）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9120, 'admin:rec:view',   '推荐配置与监控', 'RECOMMEND', 0, 900, 1),
  (9121, 'admin:rec:edit',   '推荐配置修改',   'RECOMMEND', 0, 901, 1),
  (9122, 'admin:exp:view',   '实验查看',       'RECOMMEND', 0, 902, 1),
  (9123, 'admin:exp:edit',   '实验创建与停止', 'RECOMMEND', 0, 903, 1),
  (9124, 'admin:exp:approve','实验审批',       'RECOMMEND', 1, 904, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9486, 1, 9120),
  (9487, 1, 9121),
  (9488, 1, 9122),
  (9489, 1, 9123),
  (9490, 1, 9124),
  (9491, 2, 9120),
  (9492, 2, 9121),
  (9493, 2, 9122),
  (9494, 2, 9123),
  (9495, 2, 9124),
  (9496, 3, 9120),
  (9497, 3, 9122);

-- -----------------------------------------------------------------------------
-- 8. 默认配置：HOME_FEED 的召回 / 融合 / 排序 / 重排 + GLOBAL 实验阈值
--    后台 PUT /admin/rec/config/{scene} 会写新版本并把旧版本置为历史。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_rec_config`
  (`id`, `scene`, `config_type`, `config_json`, `version`, `status`) VALUES
  (1000000000000015001, 'HOME_FEED', 'RECALL',
   '{"sources":[{"name":"hot","enabled":true,"limit":500},{"name":"cf_user","enabled":true,"limit":300},{"name":"cf_item","enabled":true,"limit":300},{"name":"vector","enabled":true,"limit":300},{"name":"vector_song","enabled":true,"limit":200},{"name":"tag_match","enabled":true,"limit":300},{"name":"follow_artist","enabled":true,"limit":200},{"name":"friend_listen","enabled":true,"limit":200},{"name":"new_song","enabled":true,"limit":200},{"name":"local","enabled":true,"limit":100},{"name":"recent_play","enabled":true,"limit":100},{"name":"long_tail","enabled":true,"limit":200},{"name":"editor","enabled":true,"limit":50},{"name":"playlist_match","enabled":true,"limit":200},{"name":"emotion_match","enabled":true,"limit":200}]}',
   1, 1),
  (1000000000000015002, 'HOME_FEED', 'FUSION',
   '{"normalize":"RANK","topN":500,"minCandidates":20,"artistMax":2,"albumMax":1,"categoryRatio":0.4,"longTailRatio":0.10,"sources":[{"name":"cf_user","weight":1.2,"limit":300,"decay":0.98},{"name":"vector","weight":1.0,"limit":300,"decay":0.98},{"name":"cf_item","weight":0.9,"limit":300,"decay":0.98},{"name":"vector_song","weight":0.9,"limit":200,"decay":0.95},{"name":"tag_match","weight":0.8,"limit":300,"decay":0.95},{"name":"hot","weight":0.6,"limit":500,"decay":0.90},{"name":"friend_listen","weight":0.8,"limit":200,"decay":0.95},{"name":"follow_artist","weight":0.9,"limit":200,"decay":0.95},{"name":"new_song","weight":0.7,"limit":200,"decay":0.95},{"name":"long_tail","weight":0.4,"limit":200,"decay":0.90},{"name":"editor","weight":2.0,"limit":50,"decay":1.00},{"name":"local","weight":0.5,"limit":100,"decay":0.90},{"name":"recent_play","weight":0.7,"limit":100,"decay":0.95},{"name":"playlist_match","weight":0.7,"limit":200,"decay":0.95},{"name":"emotion_match","weight":0.6,"limit":200,"decay":0.95}]}',
   1, 1),
  (1000000000000015003, 'HOME_FEED', 'RANK',
   '{"coarse":{"weights":{"recall":0.30,"hot":0.15,"tag":0.15,"artist":0.10,"freshness":0.10,"completion":0.08,"skip":0.07},"target":150,"skipBelow":30},"fine":{"a":0.5,"b":0.3,"c":0.15,"d":0.05,"modelVersion":"rule-lr-v1"}}',
   1, 1),
  (1000000000000015004, 'HOME_FEED', 'RERANK',
   '{"operators":[{"type":"FILTER_OFFLINE"},{"type":"DEDUP"},{"type":"PIN","positions":[1,8,15],"source":"editor"},{"type":"DIVERSIFY_ARTIST","window":8,"maxCount":2},{"type":"DIVERSIFY_ALBUM","maxCount":1},{"type":"DIVERSIFY_TAG","maxRatio":0.4},{"type":"BOOST_NEW","days":7,"boost":1.15},{"type":"LONGTAIL_INSERT","interval":10,"minRatio":0.10},{"type":"EXPLORE_INSERT","ratio":0.10},{"type":"TRUNCATE","size":30}]}',
   1, 1),
  (1000000000000015005, 'GLOBAL', 'EXPERIMENT',
   '{"minSample":10000,"alpha":0.05,"minRunDays":7}',
   1, 1);
