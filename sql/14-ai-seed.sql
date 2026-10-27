-- =============================================================================
-- 模块 14 AI 能力增强：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已经初始化过模块 01~13，只需要补 AI 相关表 / 权限 / 配置 / 演示歌曲。
-- 全新库仍然按 sql/README.md 执行 schema.sql + data.sql 即可（两者已包含本文件的
-- 全部内容）。本文件与 schema.sql 使用相同的表结构定义，方便单独升级。
--
-- 安全约束：
--   * 所有对象显式带 `newmusic.` 库名；本机 3306 是共享实例（还有 emp_db/music_db）
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，不清表、不删数据、不改他人库
--   * 本脚本不写任何密钥：AI Provider 的 API Key 只允许走环境变量
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 歌曲情绪与场景标注（FR-23-004 / BR-23-039）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_song_emotion` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `song_id`        BIGINT UNSIGNED NOT NULL COMMENT '歌曲ID，唯一',
  `valence`        DECIMAL(3,2)    NOT NULL DEFAULT 0.50 COMMENT '情绪正负 0 极负 ~ 1 极正',
  `arousal`        DECIMAL(3,2)    NOT NULL DEFAULT 0.50 COMMENT '唤醒度 0 极静 ~ 1 极动',
  `energy`         DECIMAL(3,2)    NOT NULL DEFAULT 0.50 COMMENT '能量 0~1',
  `emotion_tags`   VARCHAR(256)    DEFAULT NULL COMMENT '情绪标签 JSON 数组（白名单内）',
  `scene_tags`     VARCHAR(256)    DEFAULT NULL COMMENT '场景标签 JSON 数组（白名单内）',
  `confidence`     DECIMAL(3,2)    NOT NULL DEFAULT 0.00 COMMENT '置信度 0~1',
  `source`         VARCHAR(16)     NOT NULL DEFAULT 'AI' COMMENT 'AI / MANUAL；MANUAL 不被批处理覆盖',
  `reason`         VARCHAR(200)    DEFAULT NULL COMMENT '识别理由（≤30 字）',
  `model_version`  VARCHAR(64)     DEFAULT NULL COMMENT '模型',
  `prompt_version` VARCHAR(32)     DEFAULT NULL COMMENT 'Prompt 版本',
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT         NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_emotion_song` (`song_id`),
  KEY `idx_emotion_confidence` (`source`, `confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌曲情绪与场景标注';

-- -----------------------------------------------------------------------------
-- 2. AI 调用日志（BR-23-003 / BR-23-011）
--    本环境不启用按月分表；表结构直接按 PRD 的日志字段定义，后续按 create_time 分区即可。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ai_call_log` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `scene`          VARCHAR(32)     NOT NULL COMMENT '场景',
  `model`          VARCHAR(64)     DEFAULT NULL COMMENT '模型名',
  `model_level`    VARCHAR(16)     DEFAULT NULL COMMENT 'SMALL/MEDIUM/LARGE',
  `prompt_version` VARCHAR(32)     DEFAULT NULL COMMENT 'Prompt 版本',
  `user_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '用户；离线任务为 NULL',
  `input_tokens`   INT             NOT NULL DEFAULT 0,
  `output_tokens`  INT             NOT NULL DEFAULT 0,
  `cost_cent`      INT             NOT NULL DEFAULT 0 COMMENT '成本（分）',
  `latency_ms`     INT             NOT NULL DEFAULT 0,
  `from_cache`     TINYINT         NOT NULL DEFAULT 0,
  `success`        TINYINT         NOT NULL DEFAULT 0,
  `degraded`       TINYINT         NOT NULL DEFAULT 0,
  `error_msg`      VARCHAR(500)    DEFAULT NULL,
  `biz_id`         VARCHAR(64)     DEFAULT NULL COMMENT '业务ID，如 songId',
  `trace_id`       VARCHAR(64)     DEFAULT NULL,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ai_log_scene_time` (`scene`, `create_time`),
  KEY `idx_ai_log_time` (`create_time`),
  KEY `idx_ai_log_user` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 调用日志';

-- -----------------------------------------------------------------------------
-- 3. 标注失败重试队列（BR-23-081 / EX-23-008-3）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ai_annotation_fail` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `scene`         VARCHAR(32)     NOT NULL COMMENT 'EMOTION_TAG / LYRIC_TRANS ...',
  `biz_id`        VARCHAR(64)     NOT NULL COMMENT '歌曲ID',
  `attempt_count` INT             NOT NULL DEFAULT 1,
  `last_error`    VARCHAR(500)    DEFAULT NULL,
  `next_retry_at` DATETIME(3)     DEFAULT NULL,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`     BIGINT UNSIGNED DEFAULT NULL,
  `update_by`     BIGINT UNSIGNED DEFAULT NULL,
  `deleted`       TINYINT         NOT NULL DEFAULT 0,
  `version`       INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_fail_scene_biz` (`scene`, `biz_id`),
  KEY `idx_ai_fail_retry` (`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 标注失败重试队列';

-- -----------------------------------------------------------------------------
-- 4. 翻译质量反馈（BR-23-024）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ai_feedback` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `scene`       VARCHAR(32)     NOT NULL DEFAULT 'LYRIC_TRANS',
  `song_id`     BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `issue`       VARCHAR(200)    NOT NULL COMMENT '用户描述的问题',
  `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT         NOT NULL DEFAULT 0,
  `version`     INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_ai_feedback_song` (`song_id`, `create_time`),
  KEY `idx_ai_feedback_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 结果反馈';

-- -----------------------------------------------------------------------------
-- 5. 后台权限点（FR-23 接口清单：admin:ai:view / admin:ai:edit）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9110, 'admin:ai:view', 'AI 成本与质量', 'AI', 0, 800, 1),
  (9111, 'admin:ai:edit', 'AI 标注/预算配置', 'AI', 0, 801, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9482, 1, 9110),
  (9483, 1, 9111),
  (9484, 2, 9110),
  (9485, 2, 9111);

-- -----------------------------------------------------------------------------
-- 6. 预算 / 路由配置（PRD §12；运行时可被后台覆盖）
--    注意：这里只放非密钥配置，API Key 禁止入库（SysConfigService 也会拒绝 secret 类键）。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_sys_config`
  (`id`, `config_key`, `config_value`, `value_type`, `config_group`, `description`, `is_public`, `status`) VALUES
  (1000000000000014001, 'ai.daily.budget.cent', '30000', 2, 'ai',
   'AI 日预算（分），默认 300 元；达到后熔断在线 AI（安全审核除外）', 0, 1),
  (1000000000000014002, 'ai.budget.alert.percent', '80', 2, 'ai',
   '预算告警阈值（百分比），达到后写告警标记', 0, 1),
  (1000000000000014003, 'ai.scene.budget.cent', '{"PLAYLIST_GEN":10000,"LYRIC_TRANS":5000,"SEARCH_PARSE":8000,"EMOTION_TAG":3000,"OTHER":14000}', 4, 'ai',
   '场景级日预算（分，JSON），防单场景吃掉全部预算', 0, 1),
  (1000000000000014004, 'ai.model.routing', '{"SEARCH_PARSE":"SMALL","SEARCH_EXPLAIN":"SMALL","LYRIC_TRANS":"MEDIUM","LYRIC_SUMMARY":"MEDIUM","EMOTION_TAG":"SMALL","PLAYLIST_GEN":"LARGE","REC_RERANK":"LARGE","AUDIT":"SMALL"}', 4, 'ai',
   '场景 → 模型档位（JSON）。LARGE 失败自动降 MEDIUM → SMALL', 0, 1),
  (1000000000000014005, 'ai.cache.enabled', 'true', 3, 'ai',
   'AI 结果缓存总开关；关闭后所有场景直连模型（成本上升）', 0, 1),
  (1000000000000014006, 'ai.sensitive.words', '', 1, 'security',
   'AI 输出敏感词，逗号分隔；为空表示不拦截（模块18词库就位后迁走）', 0, 1);

-- -----------------------------------------------------------------------------
-- 7. 演示歌曲：非中文歌曲用于歌词翻译（中文歌曲按 BR-23-023 不翻译）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_artist`
  (`id`, `name`, `alias`, `gender`, `country`, `styles`, `song_count`, `status`) VALUES
  (1000000000000003101, 'Demo Singer', 'Demo Singer', 0, '美国', '民谣', 1, 1);

INSERT IGNORE INTO `newmusic`.`t_song`
  (`id`, `name`, `artist_id`, `artist_name`, `album_id`, `album_name`, `language`, `genre`,
   `duration`, `bpm`, `release_date`, `era`, `isrc`,
   `audio_standard`, `audio_high`, `lyric_id`,
   `copyright_status`, `copyright_owner`, `copyright_expire`,
   `play_count`, `like_count`, `hot_score`, `audit_status`, `status`, `publish_time`, `source`) VALUES
  (1000000000000005101, 'You Are My Sunshine', 1000000000000003101, 'Demo Singer', NULL, NULL,
   '英语', '民谣', 120, 96, '2020-01-01', '20s', 'DEMO0000005101',
   '/media/demo/demo-01.wav', '/media/demo/demo-01.wav', 1000000000000006101,
   1, 'Demo Music', '2030-12-31', 520, 60, 300, 1, 1, NOW(3), 1),
  -- 无歌词的非中文歌：验证 EX-23-002-4「暂无歌词」
  (1000000000000005102, 'Instrumental Demo', 1000000000000003101, 'Demo Singer', NULL, NULL,
   '英语', '民谣', 90, 80, '2020-01-01', '20s', 'DEMO0000005102',
   '/media/demo/demo-02.wav', '/media/demo/demo-02.wav', NULL,
   1, 'Demo Music', '2030-12-31', 100, 5, 60, 1, 1, NOW(3), 1);

INSERT IGNORE INTO `newmusic`.`t_lyric`
  (`id`, `song_id`, `content`, `content_type`, `offset_ms`, `audit_status`) VALUES
  (1000000000000006101, 1000000000000005101,
   '[ti:You Are My Sunshine]\n[ar:Demo Singer]\n[00:00.00]You are my sunshine\n[00:05.00]My only sunshine\n[00:10.00]You make me happy\n[00:15.00]When skies are grey\n[00:20.00]You''ll never know, dear\n[00:25.00]How much I love you\n[00:30.00][00:45.00]Please don''t take my sunshine away',
   1, 0, 1);
