-- =============================================================================
-- 模块 17 数据平台与运营分析：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~16，只需补埋点、指标、画像、报表、
--          数据质量与数仓分层相关表。
--
-- 安全约束（本机 3306 是共享实例，另有 emp_db/music_db）：
--   * 所有对象显式带 `newmusic.` 库名，不触碰其它库；
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，不清表、不删数据；
--   * Redis 侧键全部以 `ml:stat:` / `ml:track:` 开头，见 sql/README.md 模块 17 小节。
--
-- 与 PRD 26 的偏差（详见 docs/待办.md 交付记录）：
--   * 本机没有 Kafka / ClickHouse / Flink：ODS 落在 MySQL `t_track_event`，
--     实时指标用 Redis（HyperLogLog / INCR / ZSet），DWD/DWS/ADS 用 MySQL 明细与汇总表；
--   * t_metric_def.version 承载「口径版本」（VARCHAR），与 BaseEntity 的乐观锁 version
--     不是同一个概念，故实体不继承 BaseEntity；
--   * 额外新增 t_dwd_play_detail / t_dws_user_daily / t_dws_song_daily / t_ads_daily_metrics
--     四张分层表，用于把 ODS 加工成可对账的汇总（PRD 只给了 ClickHouse 表结构）。
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 埋点定义（BR-26-001：先有文档再采集）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_event_def` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `event_name`     VARCHAR(64)     NOT NULL COMMENT '事件名 {domain}_{object}_{action}',
  `event_cn_name`  VARCHAR(128)    NOT NULL COMMENT '中文名',
  `domain`         VARCHAR(32)     NOT NULL COMMENT '域：user/player/search/social/room/ai/track/data',
  `trigger_desc`   VARCHAR(500)    DEFAULT NULL COMMENT '触发时机',
  `properties`     TEXT            COMMENT '业务属性 JSON Schema',
  `priority`       TINYINT         NOT NULL DEFAULT 2 COMMENT '1核心 2重要 3辅助',
  `is_sampled`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否采样',
  `sample_rate`    DECIMAL(4,3)    NOT NULL DEFAULT 1.000 COMMENT '采样率 0~1',
  `owner`          VARCHAR(64)     DEFAULT NULL,
  `version`        VARCHAR(16)     NOT NULL DEFAULT 'v1' COMMENT '埋点定义版本（只增不减）',
  `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '0草稿 1生效 2废弃',
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_name` (`event_name`),
  KEY `idx_event_domain` (`domain`, `status`),
  KEY `idx_event_priority` (`priority`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='埋点定义';

-- -----------------------------------------------------------------------------
-- 2. 埋点明细（ODS 层）+ 脏数据隔离
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_track_event` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `event_id`      VARCHAR(64)     NOT NULL COMMENT '客户端生成的唯一 ID（5 分钟幂等）',
  `event_name`    VARCHAR(64)     NOT NULL,
  `event_time`    BIGINT          NOT NULL COMMENT '客户端毫秒时间戳（不可信，仅排行为顺序）',
  `server_time`   DATETIME(3)     NOT NULL COMMENT '服务端接收时间（分析口径）',
  `user_id`       BIGINT UNSIGNED DEFAULT NULL COMMENT '未登录为 NULL',
  `device_id`     VARCHAR(64)     DEFAULT NULL,
  `session_id`    VARCHAR(64)     DEFAULT NULL,
  `platform`      VARCHAR(20)     DEFAULT 'web',
  `app_version`   VARCHAR(32)     DEFAULT NULL,
  `os`            VARCHAR(64)     DEFAULT NULL,
  `browser`       VARCHAR(64)     DEFAULT NULL,
  `screen`        VARCHAR(32)     DEFAULT NULL,
  `network`       VARCHAR(16)     DEFAULT NULL,
  `ip`            VARCHAR(45)     DEFAULT NULL,
  `region`        VARCHAR(32)     DEFAULT NULL,
  `page_id`       VARCHAR(64)     DEFAULT NULL,
  `page_url`      VARCHAR(512)    DEFAULT NULL,
  `referrer`      VARCHAR(512)    DEFAULT NULL,
  `trace_id`      VARCHAR(64)     DEFAULT NULL,
  `ab_tests`      VARCHAR(500)    DEFAULT NULL COMMENT '参与的实验与分组 JSON',
  `is_new_user`   TINYINT         NOT NULL DEFAULT 0,
  `props`         TEXT            COMMENT '已脱敏的业务属性 JSON',
  `dt`            DATE            NOT NULL COMMENT '服务端自然日（Asia/Shanghai）',
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_track_event_id` (`event_id`),
  KEY `idx_track_event_dt` (`event_name`, `dt`),
  KEY `idx_track_user_dt` (`user_id`, `dt`),
  KEY `idx_track_device_dt` (`device_id`, `dt`),
  KEY `idx_track_dt` (`dt`),
  KEY `idx_track_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='埋点明细 ODS';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_track_dirty` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `event_id`    VARCHAR(64)     DEFAULT NULL,
  `event_name`  VARCHAR(64)     DEFAULT NULL,
  `raw`         TEXT            COMMENT '原始事件 JSON',
  `reason`      VARCHAR(200)    NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_dirty_time` (`create_time`),
  KEY `idx_dirty_reason` (`reason`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='埋点脏数据';

-- -----------------------------------------------------------------------------
-- 3. 指标定义与口径变更（BR-26-027 / BR-26-028）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_metric_def` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `metric_id`     VARCHAR(64)     NOT NULL COMMENT '指标 ID，如 metric_dau_play',
  `metric_name`   VARCHAR(128)    NOT NULL,
  `category`      VARCHAR(32)     NOT NULL COMMENT '用户/播放/留存/推荐/搜索/房间/社交/AI/技术/运营',
  `definition`    VARCHAR(1000)   NOT NULL COMMENT '口径定义（唯一口径）',
  `calculation`   VARCHAR(2000)   DEFAULT NULL COMMENT '计算逻辑（SQL 或描述）',
  `data_source`   VARCHAR(256)    DEFAULT NULL,
  `dimensions`    VARCHAR(500)    DEFAULT NULL COMMENT '可用维度 JSON',
  `update_freq`   VARCHAR(16)     NOT NULL DEFAULT 'T+1',
  `owner`         VARCHAR(64)     DEFAULT NULL,
  `version`       VARCHAR(16)     NOT NULL DEFAULT 'v1.0' COMMENT '口径版本',
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '0草稿 1生效 2废弃',
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metric_id` (`metric_id`),
  KEY `idx_metric_category` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标定义';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_metric_change_log` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `metric_id`       VARCHAR(64)     NOT NULL,
  `old_definition`  VARCHAR(1000)   DEFAULT NULL,
  `new_definition`  VARCHAR(1000)   NOT NULL,
  `reason`          VARCHAR(500)    DEFAULT NULL,
  `applicant_id`    BIGINT UNSIGNED DEFAULT NULL,
  `approver`        VARCHAR(64)     DEFAULT NULL,
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0待审批 1已通过 2已驳回',
  `effective_date`  DATE            DEFAULT NULL,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_metric_change_metric` (`metric_id`, `status`),
  KEY `idx_metric_change_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标口径变更记录';

-- -----------------------------------------------------------------------------
-- 4. 用户画像标签（PRD 用 ClickHouse，本机落 MySQL）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_profile_tag` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `tag_type`      VARCHAR(32)     NOT NULL COMMENT '基础属性/设备属性/行为属性/音乐偏好/消费能力/社交属性/生命周期/价值分层/兴趣标签/渠道来源',
  `tag_name`      VARCHAR(64)     NOT NULL,
  `tag_value`     VARCHAR(500)    DEFAULT NULL,
  `tag_version`   VARCHAR(16)     NOT NULL DEFAULT 'v1',
  `update_date`   DATE            NOT NULL,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_user_name` (`user_id`, `tag_name`),
  KEY `idx_tag_name_value` (`tag_name`, `tag_value`),
  KEY `idx_tag_date` (`update_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户画像标签';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_audience` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `name`          VARCHAR(128)    NOT NULL,
  `conditions`    TEXT            NOT NULL COMMENT '圈选条件 JSON',
  `user_count`    INT             NOT NULL DEFAULT 0,
  `user_ids`      MEDIUMTEXT      DEFAULT NULL COMMENT '>=100 人时保存；<100 人为 NULL（防去匿名化）',
  `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0计算中 1完成 2失败',
  `creator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audience_creator` (`creator_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人群圈选';

-- -----------------------------------------------------------------------------
-- 5. 数据质量 / 对账 / 血缘
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_data_quality_record` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `check_date`    DATE            NOT NULL,
  `table_name`    VARCHAR(64)     NOT NULL,
  `partition_val` VARCHAR(32)     DEFAULT NULL,
  `rule_type`     VARCHAR(32)     NOT NULL COMMENT 'row_count/null_rate/value_range/duplicate/anomaly',
  `rule_expr`     VARCHAR(200)    NOT NULL,
  `passed`        TINYINT         NOT NULL,
  `actual_value`  VARCHAR(200)    DEFAULT NULL,
  `severity`      VARCHAR(16)     NOT NULL DEFAULT 'warning' COMMENT 'critical/warning',
  `check_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_quality_date` (`check_date`),
  KEY `idx_quality_table` (`table_name`, `check_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据质量校验记录';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_reconciliation` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `check_date`   DATE            NOT NULL,
  `item`         VARCHAR(64)     NOT NULL,
  `dw_value`     BIGINT          NOT NULL DEFAULT 0,
  `biz_value`    BIGINT          NOT NULL DEFAULT 0,
  `deviation`    DECIMAL(10,4)   NOT NULL DEFAULT 0,
  `threshold`    DECIMAL(10,4)   NOT NULL DEFAULT 0,
  `passed`       TINYINT         NOT NULL,
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recon_date_item` (`check_date`, `item`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数仓与业务库对账';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_data_lineage` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `table_name`      VARCHAR(64)     NOT NULL,
  `column_name`     VARCHAR(64)     DEFAULT NULL,
  `upstream_table`  VARCHAR(64)     NOT NULL,
  `upstream_column` VARCHAR(64)     DEFAULT NULL,
  `job_name`        VARCHAR(64)     DEFAULT NULL,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lineage_edge` (`table_name`, `column_name`, `upstream_table`, `upstream_column`),
  KEY `idx_lineage_table` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据血缘';

-- -----------------------------------------------------------------------------
-- 6. 运营报表与订阅
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_data_report` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `report_type`   VARCHAR(32)     NOT NULL COMMENT 'DAILY/WEEKLY/MONTHLY/CONTENT/USER/ROOM/SOCIAL/RECOMMEND/SEARCH/SECURITY/COST/CHANNEL',
  `report_date`   DATE            NOT NULL,
  `title`         VARCHAR(200)    NOT NULL,
  `content`       MEDIUMTEXT      COMMENT '报表内容 JSON',
  `status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '0生成中 1完成 2失败',
  `sent_count`    INT             NOT NULL DEFAULT 0,
  `generated_at`  DATETIME(3)     DEFAULT NULL,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_type_date` (`report_type`, `report_date`),
  KEY `idx_report_date` (`report_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运营报表';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_report_subscription` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `admin_id`      BIGINT UNSIGNED NOT NULL,
  `report_types`  VARCHAR(200)    NOT NULL COMMENT '逗号分隔',
  `frequency`     VARCHAR(16)     NOT NULL DEFAULT 'DAILY',
  `channel`       VARCHAR(32)     NOT NULL DEFAULT 'IN_APP' COMMENT 'IN_APP/EMAIL/WECHAT/DINGTALK',
  `status`        TINYINT         NOT NULL DEFAULT 1,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sub_admin` (`admin_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表订阅';

-- -----------------------------------------------------------------------------
-- 7. 数仓分层（ODS=t_track_event；DWD/DWS/ADS 如下）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_dwd_play_detail` (
  `id`                  BIGINT UNSIGNED NOT NULL,
  `event_id`            VARCHAR(64)     NOT NULL,
  `dt`                  DATE            NOT NULL,
  `user_id`             BIGINT UNSIGNED DEFAULT NULL,
  `device_id`           VARCHAR(64)     DEFAULT NULL,
  `song_id`             BIGINT UNSIGNED NOT NULL,
  `artist_id`           BIGINT UNSIGNED DEFAULT NULL,
  `genre`               VARCHAR(50)     DEFAULT NULL,
  `language`            VARCHAR(20)     DEFAULT NULL,
  `play_duration`       INT             NOT NULL DEFAULT 0,
  `song_duration`       INT             NOT NULL DEFAULT 0,
  `completion_rate`     DECIMAL(6,4)    NOT NULL DEFAULT 0,
  `is_valid`            TINYINT         NOT NULL DEFAULT 0,
  `from_source`         VARCHAR(32)     DEFAULT NULL,
  `position`            INT             DEFAULT NULL,
  `recommend_trace_id`  VARCHAR(64)     DEFAULT NULL,
  `quality`             VARCHAR(16)     DEFAULT NULL,
  `server_time`         DATETIME(3)     NOT NULL,
  `create_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dwd_play_event` (`event_id`),
  KEY `idx_dwd_play_dt_song` (`dt`, `song_id`),
  KEY `idx_dwd_play_dt_user` (`dt`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DWD 播放明细';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_dws_user_daily` (
  `id`                     BIGINT UNSIGNED NOT NULL,
  `dt`                     DATE            NOT NULL,
  `user_id`                BIGINT UNSIGNED NOT NULL,
  `play_cnt`               INT             NOT NULL DEFAULT 0,
  `valid_play_cnt`         INT             NOT NULL DEFAULT 0,
  `play_duration`          BIGINT          NOT NULL DEFAULT 0 COMMENT '秒',
  `distinct_song_cnt`      INT             NOT NULL DEFAULT 0,
  `like_cnt`               INT             NOT NULL DEFAULT 0,
  `comment_cnt`            INT             NOT NULL DEFAULT 0,
  `follow_cnt`             INT             NOT NULL DEFAULT 0,
  `post_cnt`               INT             NOT NULL DEFAULT 0,
  `room_cnt`               INT             NOT NULL DEFAULT 0,
  `search_cnt`             INT             NOT NULL DEFAULT 0,
  `recommend_click_cnt`    INT             NOT NULL DEFAULT 0,
  `recommend_exposure_cnt` INT             NOT NULL DEFAULT 0,
  `session_cnt`            INT             NOT NULL DEFAULT 0,
  `create_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dws_user_day` (`dt`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DWS 用户日汇总';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_dws_song_daily` (
  `id`                     BIGINT UNSIGNED NOT NULL,
  `dt`                     DATE            NOT NULL,
  `song_id`                BIGINT UNSIGNED NOT NULL,
  `artist_id`              BIGINT UNSIGNED DEFAULT NULL,
  `play_cnt`               INT             NOT NULL DEFAULT 0,
  `valid_play_cnt`         INT             NOT NULL DEFAULT 0,
  `play_duration`          BIGINT          NOT NULL DEFAULT 0,
  `distinct_user_cnt`      INT             NOT NULL DEFAULT 0,
  `complete_cnt`           INT             NOT NULL DEFAULT 0,
  `skip_cnt`               INT             NOT NULL DEFAULT 0,
  `like_cnt`               INT             NOT NULL DEFAULT 0,
  `collect_cnt`            INT             NOT NULL DEFAULT 0,
  `comment_cnt`            INT             NOT NULL DEFAULT 0,
  `share_cnt`              INT             NOT NULL DEFAULT 0,
  `recommend_exposure_cnt` INT             NOT NULL DEFAULT 0,
  `recommend_click_cnt`    INT             NOT NULL DEFAULT 0,
  `create_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dws_song_day` (`dt`, `song_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DWS 歌曲日汇总';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_ads_daily_metrics` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `stat_date`        DATE            NOT NULL,
  `metric_id`        VARCHAR(64)     NOT NULL,
  `metric_name`      VARCHAR(128)    NOT NULL,
  `metric_value`     DECIMAL(20,4)   NOT NULL DEFAULT 0,
  `dimension`        VARCHAR(32)     NOT NULL DEFAULT 'ALL',
  `dimension_value`  VARCHAR(64)     NOT NULL DEFAULT '',
  `extra`            VARCHAR(500)    DEFAULT NULL,
  `create_time`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ads_metric` (`stat_date`, `metric_id`, `dimension`, `dimension_value`),
  KEY `idx_ads_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ADS 日指标';

-- -----------------------------------------------------------------------------
-- 8. 后台权限点（FR-26 接口清单）
--    ID 9200~9203 与模块 15/16 的 912x/913x 相邻但不冲突；授权 9505~9513。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9200, 'admin:data:view',    '数据看板与报表查看', '数据平台', 0, 1000, 1),
  (9201, 'admin:data:edit',    '指标/埋点/人群维护', '数据平台', 1, 1001, 1),
  (9202, 'admin:data:approve', '指标口径审批',       '数据平台', 1, 1002, 1),
  (9203, 'admin:data:export',  '报表导出',           '数据平台', 0, 1003, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9505, 1, 9200),
  (9506, 1, 9201),
  (9507, 1, 9202),
  (9508, 1, 9203),
  (9509, 2, 9200),
  (9510, 2, 9201),
  (9511, 2, 9203),
  (9512, 3, 9200),
  (9513, 3, 9203);

-- -----------------------------------------------------------------------------
-- 9. 埋点定义种子（BR-26-001：先文档后埋点）
--    priority: 1 核心（不采样） 2 重要 3 辅助
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_event_def`
  (`id`, `event_name`, `event_cn_name`, `domain`, `trigger_desc`, `properties`, `priority`, `is_sampled`, `sample_rate`, `owner`, `version`, `status`) VALUES
  (1000000000000017001, 'user_register_success', '注册成功', 'user', '账号注册接口返回成功后', '{"user_id":"用户ID","channel":"注册渠道"}', 1, 0, 1.000, 'growth', 'v1', 1),
  (1000000000000017002, 'user_login_success', '登录成功', 'user', '登录接口返回成功后', '{"user_id":"用户ID","login_type":"密码/短信"}', 1, 0, 1.000, 'growth', 'v1', 1),
  (1000000000000017003, 'page_view', '页面曝光', 'user', '路由进入页面后', '{"page_id":"页面标识","page_url":"页面地址","referrer":"来源页"}', 2, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017004, 'page_leave', '页面离开', 'user', '路由切走或页面卸载（sendBeacon）', '{"page_id":"页面标识","stay_ms":"停留毫秒"}', 2, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017005, 'player_song_play', '开始播放', 'player', '音频真正开始播放时', '{"song_id":"歌曲ID","from_source":"来源","position":"列表位置","quality":"音质","is_cache":"是否缓存"}', 1, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017006, 'player_song_end', '播放结束', 'player', '切歌/播完/离开页面时结算', '{"song_id":"歌曲ID","play_duration":"实际秒数","song_duration":"总秒数","completion_rate":"完成度","is_valid_play":"是否有效播放"}', 1, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017007, 'player_song_pause', '暂停播放', 'player', '点击暂停', '{"song_id":"歌曲ID","position_sec":"进度秒"}', 2, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017008, 'player_song_skip', '跳过歌曲', 'player', '播放不足 30 秒切到下一首', '{"song_id":"歌曲ID","play_duration":"实际秒数"}', 2, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017009, 'player_quality_change', '切换音质', 'player', '音质下拉选择后', '{"from":"原音质","to":"目标音质","song_id":"歌曲ID"}', 2, 0, 1.000, 'frontend', 'v1', 1),
  (1000000000000017010, 'player_speed_change', '切换倍速', 'player', '倍速选择后', '{"speed":"倍速值","song_id":"歌曲ID"}', 3, 1, 0.500, 'frontend', 'v1', 1),
  (1000000000000017011, 'search_submit', '搜索提交', 'search', '回车或点击搜索', '{"keyword":"关键词（脱敏）","scene":"搜索场景"}', 1, 0, 1.000, 'search', 'v1', 1),
  (1000000000000017012, 'search_result_click', '搜索结果点击', 'search', '点击结果项', '{"keyword":"关键词","target_type":"结果类型","target_id":"结果ID","position":"位置"}', 1, 0, 1.000, 'search', 'v1', 1),
  (1000000000000017013, 'recommend_exposure', '推荐曝光', 'recommend', '推荐列表进入视口', '{"song_id":"歌曲ID","scene":"推荐场景","position":"位置","trace_id":"推荐链路"}', 2, 0, 1.000, 'algo', 'v1', 1),
  (1000000000000017014, 'recommend_click', '推荐点击', 'recommend', '点击推荐项', '{"song_id":"歌曲ID","scene":"推荐场景","position":"位置","trace_id":"推荐链路"}', 1, 0, 1.000, 'algo', 'v1', 1),
  (1000000000000017015, 'post_publish_success', '发布动态', 'social', '动态发布成功后', '{"post_id":"动态ID","type":"动态类型","image_count":"图片数"}', 1, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017016, 'post_like_click', '动态点赞', 'social', '点赞/取消点赞', '{"post_id":"动态ID","action":"LIKE/UNLIKE"}', 1, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017017, 'post_comment_submit', '发表评论', 'social', '评论提交成功后', '{"post_id":"动态ID","comment_id":"评论ID"}', 1, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017018, 'post_repost_click', '转发动态', 'social', '转发成功后', '{"post_id":"动态ID","source_post_id":"原动态"}', 2, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017019, 'user_follow_click', '关注用户', 'social', '关注成功后', '{"target_user_id":"被关注用户"}', 2, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017020, 'playlist_collect_click', '收藏歌单', 'social', '收藏歌单成功后', '{"playlist_id":"歌单ID"}', 2, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017021, 'room_enter_success', '进入音乐房', 'room', '进房成功后', '{"room_id":"房间ID","room_no":"房间号"}', 1, 0, 1.000, 'room', 'v1', 1),
  (1000000000000017022, 'room_exit', '离开音乐房', 'room', '退房或断线', '{"room_id":"房间ID","stay_sec":"停留秒数"}', 2, 0, 1.000, 'room', 'v1', 1),
  (1000000000000017023, 'room_song_order', '房间点歌', 'room', '点歌入队成功后', '{"room_id":"房间ID","song_id":"歌曲ID"}', 1, 0, 1.000, 'room', 'v1', 1),
  (1000000000000017024, 'room_danmaku_send', '房间弹幕', 'room', '弹幕发送成功后', '{"room_id":"房间ID","length":"字数"}', 2, 0, 1.000, 'room', 'v1', 1),
  (1000000000000017025, 'room_emoji_send', '房间表情', 'room', '表情发送成功后', '{"room_id":"房间ID","emoji":"表情编码"}', 3, 1, 0.500, 'room', 'v1', 1),
  (1000000000000017026, 'room_like_click', '房间点赞', 'room', '点赞成功后', '{"room_id":"房间ID","song_id":"歌曲ID"}', 2, 0, 1.000, 'room', 'v1', 1),
  (1000000000000017027, 'private_message_send', '发送私信', 'social', '私信发送成功后', '{"conversation_id":"会话ID","type":"单聊/群聊"}', 2, 0, 1.000, 'social', 'v1', 1),
  (1000000000000017028, 'notification_click', '通知点击', 'social', '点击消息中心通知', '{"category":"分类","target_type":"目标类型"}', 3, 1, 0.500, 'social', 'v1', 1),
  (1000000000000017029, 'ai_lyric_translate', 'AI 歌词翻译', 'ai', '生成译文成功后', '{"song_id":"歌曲ID","cached":"是否命中缓存","cost_cent":"成本分"}', 2, 0, 1.000, 'ai', 'v1', 1),
  (1000000000000017030, 'ai_emotion_annotate', 'AI 情绪标注', 'ai', '标注成功后', '{"song_id":"歌曲ID","emotion":"情绪","scene":"场景","cost_cent":"成本分"}', 2, 0, 1.000, 'ai', 'v1', 1),
  (1000000000000017031, 'ai_call', 'AI 调用', 'ai', '网关每次调用结束', '{"scene":"场景","status":"SUCCESS/DEGRADED","cost_cent":"成本分","latency_ms":"耗时"}', 2, 0, 1.000, 'ai', 'v1', 1),
  (1000000000000017032, 'track_report', '埋点上报', 'track', '采集网关每次响应后（服务端）', '{"count":"接收条数","accepted":"接收条数","dropped":"丢弃条数","dirty":"脏数据条数"}', 1, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017033, 'track_dropped', '埋点丢弃', 'track', '限流/采样/缓冲区满（服务端）', '{"reason":"原因","count":"条数"}', 2, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017034, 'data_quality_check', '数据质量校验', 'data', '质量规则执行后（服务端）', '{"table":"表名","passed":"通过数","failed":"失败数"}', 2, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017035, 'reconciliation_result', '数据对账', 'data', '每日对账后（服务端）', '{"item":"对账项","passed":"是否通过","deviation":"偏差"}', 2, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017036, 'etl_job_run', '数仓任务', 'data', 'ETL 任务执行后（服务端）', '{"job":"任务名","status":"状态","rows":"行数","cost_ms":"耗时"}', 2, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017037, 'report_generate', '报表生成', 'data', '报表生成后（服务端）', '{"type":"报表类型","date":"报表日期"}', 2, 0, 1.000, 'data', 'v1', 1),
  (1000000000000017038, 'report_send', '报表分发', 'data', '报表分发后（服务端）', '{"type":"报表类型","channel":"渠道","success":"是否成功"}', 3, 1, 0.500, 'data', 'v1', 1),
  (1000000000000017039, 'dashboard_view', '看板访问', 'data', '后台打开实时看板', '{"admin_id":"管理员ID"}', 3, 1, 0.500, 'data', 'v1', 1),
  (1000000000000017040, 'metric_query', '指标查询', 'data', '指标/趋势/下钻查询', '{"metric_id":"指标ID","scene":"场景"}', 3, 1, 0.500, 'data', 'v1', 1),
  (1000000000000017041, 'audience_create', '人群圈选', 'data', '创建人群后', '{"audience_id":"人群ID","user_count":"人数"}', 3, 1, 0.500, 'data', 'v1', 1),
  (1000000000000017042, 'player_valid_play', '有效播放', 'player', '服务端判定单次播放有效后（服务端）', '{"song_id":"歌曲ID","play_duration":"秒","completion_rate":"完成度"}', 1, 0, 1.000, 'data', 'v1', 1);

-- -----------------------------------------------------------------------------
-- 10. 指标定义种子（每个指标唯一 ID 与唯一口径，BR-26-027）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_metric_def`
  (`id`, `metric_id`, `metric_name`, `category`, `definition`, `calculation`, `data_source`, `dimensions`, `update_freq`, `owner`, `version`, `status`) VALUES
  (1000000000000017101, 'metric_dau', '日活跃用户数', '用户', '当日有任意有效埋点的登录用户数，按 userId 去重', 'COUNT(DISTINCT user_id) FROM t_track_event WHERE dt=? AND user_id IS NOT NULL', 't_track_event', '["date","channel","platform","region"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017102, 'metric_dau_play', 'DAU-Play（北极星）', '用户', '当日有效播放（时长>=30s 或完成度>=60%）>=3 首的用户数', 'COUNT(DISTINCT user_id) FROM t_dws_user_daily WHERE valid_play_cnt>=3', 't_dws_user_daily', '["date","channel","platform"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017103, 'metric_play_cnt', '播放次数', '播放', '当日 player_song_play 事件数', 'COUNT(*) FROM t_track_event WHERE event_name=''player_song_play''', 't_track_event', '["date","song","artist","source"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017104, 'metric_valid_play_cnt', '有效播放次数', '播放', '单次播放时长>=30s 或完成度>=60% 的次数', 'COUNT(*) FROM t_dwd_play_detail WHERE is_valid=1', 't_dwd_play_detail', '["date","song","artist"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017105, 'metric_play_duration', '播放时长', '播放', '当日播放秒数总和', 'SUM(play_duration) FROM t_dwd_play_detail', 't_dwd_play_detail', '["date","song","artist"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017106, 'metric_avg_play_per_user', '人均播放次数', '播放', '总播放次数 / DAU', 'metric_play_cnt / metric_dau', 'ADS', '["date"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017107, 'metric_new_user_cnt', '新增用户数', '用户', '当日首次注册用户数，按 userId 去重', 'COUNT(*) FROM t_user WHERE DATE(create_time)=?', 't_user', '["date","channel"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017108, 'metric_retention_d1', '次日留存率', '留存', 'D 日新增用户中 D+1 日有有效播放的比例', 'cohort(d) INTERSECT active(d+1) / cohort(d)', 't_user + t_dwd_play_detail', '["date","channel"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017109, 'metric_retention_d7', '7 日留存率', '留存', 'D 日新增用户中 D+7 日有有效播放的比例', 'cohort(d) INTERSECT active(d+7) / cohort(d)', 't_user + t_dwd_play_detail', '["date","channel"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017110, 'metric_completion_rate', '播放完成率', '播放', '完成度>=90% 的播放次数 / 总播放次数', 'SUM(completion_rate>=0.9)/COUNT(*)', 't_dwd_play_detail', '["date","song"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017111, 'metric_song_coverage', '曲库有效播放覆盖率', '曲库', '当日被有效播放过的歌曲数 / 曲库总数', 'COUNT(DISTINCT song_id)/COUNT(t_song)', 't_dwd_play_detail + t_song', '["date"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017112, 'metric_recommend_ctr', '推荐点击率', '推荐', '推荐曝光后点击的比例，曝光按 (userId,songId,位置) 去重', 'click/exposure', 't_track_event', '["date","scene"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017113, 'metric_search_success_rate', '搜索成功率', '搜索', '有结果点击的搜索次数 / 总搜索次数', 'search_result_click/search_submit', 't_track_event', '["date","keyword"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017114, 'metric_room_active_user', '房间活跃用户数', '音乐房', '当日进入过任意音乐房的用户数，按 userId 去重', 'COUNT(DISTINCT user_id) FROM room_enter_success', 't_track_event', '["date","room"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017115, 'metric_interaction_user', '互动用户数', '社交', '当日有点赞/评论/关注/发动态的用户数', 'COUNT(DISTINCT user_id) FROM 社交事件', 't_track_event', '["date"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017116, 'metric_content_produce', '内容生产量', '社交', '当日动态发布数 + 评论数 + 歌单创建数', 'COUNT(post)+COUNT(comment)+COUNT(playlist)', '业务库 + t_track_event', '["date"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017117, 'metric_ai_cost', 'AI 调用成本（元）', 'AI', '当日 AI 调用成本合计，单位元', 'SUM(cost_cent)/100', 't_ai_call_log', '["date","scene"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017118, 'metric_online_user', '当前在线用户数', '技术', '当前 WebSocket 连接数（本机节点现算）', 'WsSessionRegistry.totalConnections()', 'WebSocket', '["node"]', '实时', 'data', 'v1.0', 1),
  (1000000000000017119, 'metric_track_success_rate', '埋点上报成功率', '技术', '成功入库埋点数 / 接收埋点数', 'accepted/(accepted+dropped+dirty)', 't_track_event', '["date"]', '实时+T+1', 'data', 'v1.0', 1),
  (1000000000000017120, 'metric_funnel_activation', '新用户激活漏斗转化率', '运营', '打开首页 -> 播放第一首 -> 有效播放>=3 首 -> 次日回访 的整体转化率', 'last_step_users/first_step_users', 't_track_event', '["date","channel"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017121, 'metric_content_report_cnt', '内容报表产出数', '运营', '当日生成的报表份数', 'COUNT(*) FROM t_data_report WHERE report_date=?', 't_data_report', '["date","type"]', 'T+1', 'data', 'v1.0', 1),
  (1000000000000017122, 'metric_data_quality_score', '数据质量得分', '技术', '当日质量校验通过率（通过规则数/总规则数）', 'passed_rules/total_rules', 't_data_quality_record', '["date","table"]', 'T+1', 'data', 'v1.0', 1);

-- -----------------------------------------------------------------------------
-- 11. 数据血缘种子（表级 + 字段级）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_data_lineage`
  (`id`, `table_name`, `column_name`, `upstream_table`, `upstream_column`, `job_name`) VALUES
  (1000000000000017201, 't_dwd_play_detail', 'event_id', 't_track_event', 'event_id', 'dwd_play_detail'),
  (1000000000000017202, 't_dwd_play_detail', 'song_id', 't_track_event', 'props', 'dwd_play_detail'),
  (1000000000000017203, 't_dwd_play_detail', 'is_valid', 't_track_event', 'props', 'dwd_play_detail'),
  (1000000000000017204, 't_dws_user_daily', 'play_cnt', 't_dwd_play_detail', 'event_id', 'dws_user_daily'),
  (1000000000000017205, 't_dws_user_daily', 'valid_play_cnt', 't_dwd_play_detail', 'is_valid', 'dws_user_daily'),
  (1000000000000017206, 't_dws_song_daily', 'play_cnt', 't_dwd_play_detail', 'event_id', 'dws_song_daily'),
  (1000000000000017207, 't_dws_song_daily', 'valid_play_cnt', 't_dwd_play_detail', 'is_valid', 'dws_song_daily'),
  (1000000000000017208, 't_ads_daily_metrics', 'metric_value', 't_dws_user_daily', 'valid_play_cnt', 'ads_daily_metrics'),
  (1000000000000017209, 't_ads_daily_metrics', 'metric_value', 't_dws_song_daily', 'play_cnt', 'ads_daily_metrics'),
  (1000000000000017210, 't_ads_daily_metrics', 'metric_value', 't_user', 'id', 'ads_daily_metrics'),
  (1000000000000017211, 't_user_profile_tag', 'tag_value', 't_track_event', 'event_name', 'profile_tag_daily'),
  (1000000000000017212, 't_user_profile_tag', 'tag_value', 't_dws_user_daily', 'play_cnt', 'profile_tag_daily'),
  (1000000000000017213, 't_user_profile_tag', 'tag_value', 't_user_profile', 'region', 'profile_tag_daily'),
  (1000000000000017214, 't_data_report', 'content', 't_ads_daily_metrics', 'metric_value', 'report_daily'),
  (1000000000000017215, 't_reconciliation', 'dw_value', 't_ads_daily_metrics', 'metric_value', 'reconciliation_daily');
