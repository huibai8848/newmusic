-- =============================================================================
-- 模块 18 内容安全与合规：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~17，只需补内容安全相关表、扩展字段与种子。
--
-- 安全约束（本机 3306 是共享实例，另有 emp_db/music_db）：
--   * 所有对象显式带 `newmusic.` 库名，不触碰其它库；
--   * 只做 CREATE TABLE IF NOT EXISTS / ADD COLUMN，不清表、不删数据；
--   * 不做全库级 DDL/DML；
--   * Redis 键全部以 `ml:` 开头，见 sql/README.md 的模块 18 小节。
--
-- 与 PRD 27 的刻意偏差：
--   1. PRD 的 `t_audit_record` 与既有表语义冲突（既有表是「任务动作流」，
--      PRD 要的是「内容机审/人审结果」）。本模块新增 `t_content_audit_record`，
--      两者并存互不覆盖。
--   2. PRD 的举报/申诉字段在既有 `t_report` / `t_appeal` 上增量扩展，不另建表。
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 0. 既有表扩展
--    MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断。
-- -----------------------------------------------------------------------------

-- 0.1 审核任务：场景、SLA 截止、关联举报
SET @add_task_scene := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_audit_task` ADD COLUMN `scene` VARCHAR(32) DEFAULT NULL COMMENT ''审核场景：COMMENT/POST/PLAYLIST/ROOM/DANMAKU/MESSAGE/IMAGE/APPEAL'' AFTER `task_type`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_audit_task' AND COLUMN_NAME='scene'
);
PREPARE stmt FROM @add_task_scene; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_task_sla := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_audit_task` ADD COLUMN `sla_deadline` DATETIME(3) DEFAULT NULL COMMENT ''SLA 截止时间'' AFTER `escalated_at`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_audit_task' AND COLUMN_NAME='sla_deadline'
);
PREPARE stmt FROM @add_task_sla; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_task_report := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_audit_task` ADD COLUMN `related_report_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''关联举报 ID'' AFTER `sla_deadline`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_audit_task' AND COLUMN_NAME='related_report_id'
);
PREPARE stmt FROM @add_task_report; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 0.2 举报：举报单号、被举报人、合并人数、举报人集合、监管转办、SLA
SET @add_report_no := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `report_no` VARCHAR(32) DEFAULT NULL COMMENT ''举报单号'' AFTER `id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='report_no'
);
PREPARE stmt FROM @add_report_no; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_target_user := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `target_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''被举报用户'' AFTER `target_id`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='target_user_id'
);
PREPARE stmt FROM @add_report_target_user; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_category := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `reason_category` VARCHAR(32) DEFAULT NULL COMMENT ''理由分类'' AFTER `reason_type`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='reason_category'
);
PREPARE stmt FROM @add_report_category; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_detail := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `reason_detail` VARCHAR(500) DEFAULT NULL COMMENT ''详细理由'' AFTER `reason_category`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='reason_detail'
);
PREPARE stmt FROM @add_report_detail; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_count := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `report_count` INT NOT NULL DEFAULT 1 COMMENT ''合并的举报人数'' AFTER `evidence_urls`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='report_count'
);
PREPARE stmt FROM @add_report_count; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_reporter_ids := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `reporter_ids` TEXT COMMENT ''所有举报人 JSON 数组'' AFTER `report_count`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='reporter_ids'
);
PREPARE stmt FROM @add_report_reporter_ids; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_handle_type := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `handle_type` TINYINT DEFAULT NULL COMMENT ''1成立 2不成立 3待补充'' AFTER `handle_result`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='handle_type'
);
PREPARE stmt FROM @add_report_handle_type; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_regulator := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `is_regulator` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=监管转办'' AFTER `handle_type`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='is_regulator'
);
PREPARE stmt FROM @add_report_regulator; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_sla := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `sla_deadline` DATETIME(3) DEFAULT NULL COMMENT ''SLA 截止时间'' AFTER `is_regulator`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='sla_deadline'
);
PREPARE stmt FROM @add_report_sla; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_hidden := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD COLUMN `auto_hidden` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=达到阈值已自动隐藏'' AFTER `sla_deadline`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND COLUMN_NAME='auto_hidden'
);
PREPARE stmt FROM @add_report_hidden; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_no_idx := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD UNIQUE KEY `uk_report_no` (`report_no`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND INDEX_NAME='uk_report_no'
);
PREPARE stmt FROM @add_report_no_idx; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_report_sla_idx := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_report` ADD KEY `idx_report_sla` (`status`, `sla_deadline`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_report' AND INDEX_NAME='idx_report_sla'
);
PREPARE stmt FROM @add_report_sla_idx; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 0.3 申诉：证据、结果、原审核员、SLA
-- 部分历史库只执行过模块 01~10 的增量脚本，没有建过 t_appeal（schema.sql 里有）。
-- 这里先按基础结构补建，再走下面的增量列，保证脚本在两种库上都能跑。
CREATE TABLE IF NOT EXISTS `newmusic`.`t_appeal` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(32) DEFAULT NULL,
  `target_id`   VARCHAR(64) DEFAULT NULL,
  `reason`      VARCHAR(1000) DEFAULT NULL,
  `status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1成立 2驳回',
  `handled_by`  BIGINT UNSIGNED DEFAULT NULL,
  `handled_at`  DATETIME(3) DEFAULT NULL,
  `reply`       VARCHAR(1000) DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_appeal_user_time` (`user_id`, `create_time`),
  KEY `idx_appeal_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号/内容申诉';

SET @add_appeal_evidence := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_appeal` ADD COLUMN `evidence_images` VARCHAR(2000) DEFAULT NULL COMMENT ''证据图片 JSON 数组'' AFTER `reason`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_appeal' AND COLUMN_NAME='evidence_images'
);
PREPARE stmt FROM @add_appeal_evidence; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_appeal_result := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_appeal` ADD COLUMN `result` VARCHAR(500) DEFAULT NULL COMMENT ''处理结果'' AFTER `status`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_appeal' AND COLUMN_NAME='result'
);
PREPARE stmt FROM @add_appeal_result; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_appeal_original := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_appeal` ADD COLUMN `original_operator_id` BIGINT UNSIGNED DEFAULT NULL COMMENT ''原审核员'' AFTER `handled_by`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_appeal' AND COLUMN_NAME='original_operator_id'
);
PREPARE stmt FROM @add_appeal_original; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_appeal_sla := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_appeal` ADD COLUMN `sla_deadline` DATETIME(3) DEFAULT NULL COMMENT ''SLA 截止时间'' AFTER `handled_at`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_appeal' AND COLUMN_NAME='sla_deadline'
);
PREPARE stmt FROM @add_appeal_sla; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_appeal_target_idx := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_appeal` ADD KEY `idx_appeal_target` (`target_type`, `target_id`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_appeal' AND INDEX_NAME='idx_appeal_target'
);
PREPARE stmt FROM @add_appeal_target_idx; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 0.4 歌单 AI 标识（FR-27-010）
SET @add_playlist_ai := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_playlist` ADD COLUMN `ai_generated` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=AI 生成，标识不可移除'' AFTER `status`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_playlist' AND COLUMN_NAME='ai_generated'
);
PREPARE stmt FROM @add_playlist_ai; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_playlist_ai_model := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_playlist` ADD COLUMN `ai_model` VARCHAR(64) DEFAULT NULL COMMENT ''生成模型'' AFTER `ai_generated`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_playlist' AND COLUMN_NAME='ai_model'
);
PREPARE stmt FROM @add_playlist_ai_model; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_playlist_ai_prompt := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_playlist` ADD COLUMN `ai_prompt_version` VARCHAR(32) DEFAULT NULL COMMENT ''Prompt 版本'' AFTER `ai_model`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_playlist' AND COLUMN_NAME='ai_prompt_version'
);
PREPARE stmt FROM @add_playlist_ai_prompt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_playlist_ai_at := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_playlist` ADD COLUMN `ai_generated_at` DATETIME(3) DEFAULT NULL COMMENT ''AI 生成时间'' AFTER `ai_prompt_version`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='newmusic' AND TABLE_NAME='t_playlist' AND COLUMN_NAME='ai_generated_at'
);
PREPARE stmt FROM @add_playlist_ai_at; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 1. 敏感词库 / 白名单 / 审核规则 / 词库版本
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_sensitive_word` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `word`          VARCHAR(128) NOT NULL COMMENT '归一化后的词（唯一）',
  `raw_word`      VARCHAR(128) DEFAULT NULL COMMENT '原始词',
  `level`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1疑似 2高危 3禁止',
  `category`      VARCHAR(32)  NOT NULL DEFAULT 'CUSTOM' COMMENT '涉政/涉黄/暴恐/广告/辱骂/竞品/自定义',
  `source`        VARCHAR(32)  NOT NULL DEFAULT 'OPERATION' COMMENT 'OFFICIAL/OPERATION/REPORT/IMPORT',
  `hit_count`     BIGINT       NOT NULL DEFAULT 0,
  `last_hit_time` DATETIME(3)  DEFAULT NULL,
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  `remark`        VARCHAR(256) DEFAULT NULL,
  `creator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `version`       INT          NOT NULL DEFAULT 1,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_level_category` (`level`, `category`),
  KEY `idx_hit_count` (`hit_count` DESC),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词库（DFA）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_white_word` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(128) NOT NULL,
  `reason`      VARCHAR(256) DEFAULT NULL,
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_white_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词白名单';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_audit_rule` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `rule_name`   VARCHAR(64)  NOT NULL,
  `rule_type`   VARCHAR(16)  NOT NULL DEFAULT 'REGEX' COMMENT 'REGEX/KEYWORD/MODEL',
  `pattern`     VARCHAR(512) NOT NULL,
  `scene`       VARCHAR(32)  NOT NULL DEFAULT 'ALL',
  `action`      VARCHAR(16)  NOT NULL DEFAULT 'REVIEW' COMMENT 'REJECT/REVIEW/PASS',
  `priority`    INT          NOT NULL DEFAULT 100,
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `remark`      VARCHAR(256) DEFAULT NULL,
  `creator_id`  BIGINT UNSIGNED DEFAULT NULL,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_rule_scene_status` (`scene`, `status`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机审正则/关键词规则';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_sensitive_word_version` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `version`       INT          NOT NULL,
  `word_count`    INT          NOT NULL DEFAULT 0,
  `snapshot_path` VARCHAR(256) DEFAULT NULL COMMENT 'db://sensitive-word/{version}',
  `snapshot_data` MEDIUMTEXT COMMENT '完整快照 JSON（回滚用）',
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `change_desc`   VARCHAR(500) DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_version` (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='词库版本快照';

-- -----------------------------------------------------------------------------
-- 2. 内容审核记录（与模块 09 的 t_audit_record「任务动作流」分开）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_content_audit_record` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `content_type`     VARCHAR(32)  NOT NULL COMMENT 'TEXT/IMAGE/COMMENT/POST/PLAYLIST/ROOM/DANMAKU/MESSAGE/NICKNAME',
  `content_id`       BIGINT UNSIGNED DEFAULT NULL,
  `user_id`          BIGINT UNSIGNED DEFAULT NULL COMMENT '内容作者',
  `scene`            VARCHAR(32)  DEFAULT NULL,
  `content_snapshot` TEXT COMMENT '脱敏后的内容快照',
  `audit_type`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1机审 2人审 3申诉',
  `decision`         TINYINT      NOT NULL COMMENT '1通过 2拒绝 3转人工',
  `reason`           VARCHAR(500) DEFAULT NULL,
  `hit_words`        VARCHAR(1000) DEFAULT NULL COMMENT '命中词（脱敏）',
  `risk_level`       INT          NOT NULL DEFAULT 0,
  `audit_source`     VARCHAR(32)  DEFAULT NULL COMMENT 'DFA/RULE/MODEL/OCR/MANUAL/THIRD_PARTY',
  `word_version`     INT          DEFAULT NULL,
  `model_version`    VARCHAR(64)  DEFAULT NULL,
  `operator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `cost_ms`          INT          DEFAULT NULL,
  `incomplete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '1=降级/审核不完整',
  `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_content` (`content_type`, `content_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_operator_time` (`operator_id`, `create_time`),
  KEY `idx_decision_time` (`decision`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容机审/人审结果';

-- -----------------------------------------------------------------------------
-- 3. 用户处置、信用分、信用分流水
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_restriction` (
  `id`                   BIGINT UNSIGNED NOT NULL,
  `user_id`              BIGINT UNSIGNED NOT NULL,
  `type`                 TINYINT      NOT NULL COMMENT '1限制发言 2禁言 3封禁 4永久封禁 5限制上传',
  `level`                TINYINT      NOT NULL DEFAULT 2 COMMENT '处置等级 L1~L5',
  `reason`               VARCHAR(500) NOT NULL,
  `reason_category`      VARCHAR(32)  DEFAULT NULL,
  `related_content_type` VARCHAR(32)  DEFAULT NULL,
  `related_content_id`   BIGINT UNSIGNED DEFAULT NULL,
  `related_report_id`    BIGINT UNSIGNED DEFAULT NULL,
  `start_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `end_time`             DATETIME(3)  DEFAULT NULL COMMENT 'NULL=永久',
  `status`               TINYINT      NOT NULL DEFAULT 1 COMMENT '0已解除 1生效中 2已过期',
  `operator_id`          BIGINT UNSIGNED DEFAULT NULL,
  `remark`               VARCHAR(500) DEFAULT NULL,
  `create_time`          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_end_time` (`status`, `end_time`),
  KEY `idx_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户处置（限制发言/禁言/封禁）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_user_credit` (
  `user_id`               BIGINT UNSIGNED NOT NULL,
  `score`                 INT          NOT NULL DEFAULT 100,
  `violation_count_90d`   INT          NOT NULL DEFAULT 0,
  `last_violation_time`   DATETIME(3)  DEFAULT NULL,
  `last_recover_time`     DATETIME(3)  DEFAULT NULL,
  `update_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信用分';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_credit_log` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `change_score` INT          NOT NULL,
  `before_score` INT          NOT NULL,
  `after_score`  INT          NOT NULL,
  `reason`       VARCHAR(500) DEFAULT NULL,
  `related_type` VARCHAR(32)  DEFAULT NULL,
  `related_id`   BIGINT UNSIGNED DEFAULT NULL,
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_credit_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='信用分变更流水';

-- -----------------------------------------------------------------------------
-- 4. 版权与侵权投诉
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_copyright` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `song_id`        BIGINT UNSIGNED NOT NULL,
  `copyright_owner` VARCHAR(256) NOT NULL,
  `license_type`   VARCHAR(32)  NOT NULL DEFAULT 'NON_EXCLUSIVE' COMMENT 'EXCLUSIVE/NON_EXCLUSIVE',
  `license_region` VARCHAR(128) DEFAULT NULL,
  `license_start`  DATE         DEFAULT NULL,
  `license_end`    DATE         DEFAULT NULL,
  `license_scope`  VARCHAR(128) DEFAULT NULL COMMENT '在线播放/下载/商用',
  `contract_no`    VARCHAR(128) DEFAULT NULL,
  `contract_file`  VARCHAR(512) DEFAULT NULL,
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0有效 1即将到期 2已过期 3已下架',
  `operator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_song` (`song_id`),
  KEY `idx_license_end` (`license_end`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌曲版权信息';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_copyright_complaint` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `complaint_no`      VARCHAR(32)  NOT NULL,
  `complainant_name`  VARCHAR(128) NOT NULL,
  `complainant_type`  VARCHAR(16)  NOT NULL DEFAULT 'PERSON' COMMENT 'PERSON/ORG',
  `contact_encrypted` VARCHAR(256) DEFAULT NULL COMMENT '加密后的联系方式',
  `proof_files`       VARCHAR(2000) DEFAULT NULL COMMENT '证明材料 JSON 数组',
  `song_ids`          VARCHAR(1000) DEFAULT NULL COMMENT '侵权歌曲 ID JSON 数组',
  `description`       VARCHAR(1000) DEFAULT NULL,
  `statement`         TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已确认真实性声明',
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已下架 3已驳回 4反通知',
  `result`            VARCHAR(500) DEFAULT NULL,
  `operator_id`       BIGINT UNSIGNED DEFAULT NULL,
  `handle_time`       DATETIME(3)  DEFAULT NULL,
  `uploader_notified` TINYINT      NOT NULL DEFAULT 0,
  `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_complaint_no` (`complaint_no`),
  KEY `idx_complaint_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='侵权投诉（DMCA-like，保留≥3年）';

-- -----------------------------------------------------------------------------
-- 5. 未成年人保护
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_minor_protection` (
  `user_id`             BIGINT UNSIGNED NOT NULL,
  `is_minor`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=未成年人（无法识别时默认保护）',
  `mode`                TINYINT      NOT NULL DEFAULT 0 COMMENT '青少年模式开关',
  `mode_password`       VARCHAR(128) DEFAULT NULL COMMENT '模式密码（BCrypt）',
  `daily_limit_minutes` INT          NOT NULL DEFAULT 40,
  `disable_start`       TIME         NOT NULL DEFAULT '22:00:00',
  `disable_end`         TIME         NOT NULL DEFAULT '08:00:00',
  `guardian_user_id`    BIGINT UNSIGNED DEFAULT NULL,
  `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='未成年人保护/青少年模式配置';

-- -----------------------------------------------------------------------------
-- 6. 后台权限点
--    ID 9210~9223 与模块 17 的 9200~9203 相邻；授权 9514+（9514-9513 已用）。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9210, 'admin:audit:word:view',   '敏感词库查看',       '内容安全', 1, 1100, 1),
  (9211, 'admin:audit:word:edit',   '敏感词库维护',       '内容安全', 1, 1101, 1),
  (9212, 'admin:audit:word:export', '敏感词库导出',       '内容安全', 1, 1102, 1),
  (9213, 'admin:audit:rule:edit',   '审核规则维护',       '内容安全', 1, 1103, 1),
  (9214, 'admin:audit:view',        '内容审核队列查看',   '内容安全', 0, 1104, 1),
  (9215, 'admin:audit:edit',        '内容审核处置',       '内容安全', 0, 1105, 1),
  (9216, 'admin:appeal:view',       '申诉列表查看',       '内容安全', 0, 1106, 1),
  (9217, 'admin:appeal:edit',       '申诉处理',           '内容安全', 0, 1107, 1),
  (9218, 'admin:user:restrict',     '限制发言/禁言/封禁', '内容安全', 1, 1108, 1),
  (9219, 'admin:user:ban:permanent','永久封禁',           '内容安全', 1, 1109, 1),
  (9220, 'admin:user:credit:edit',  '信用分调整',         '内容安全', 1, 1110, 1),
  (9221, 'admin:copyright:view',    '版权信息查看',       '内容安全', 0, 1111, 1),
  (9222, 'admin:copyright:edit',    '版权维护/侵权处置',  '内容安全', 1, 1112, 1),
  (9223, 'admin:audit:stats',       '内容安全统计',       '内容安全', 0, 1113, 1);

-- 角色 1（超管）：全部
INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9514, 1, 9210), (9515, 1, 9211), (9516, 1, 9212), (9517, 1, 9213),
  (9518, 1, 9214), (9519, 1, 9215), (9520, 1, 9216), (9521, 1, 9217),
  (9522, 1, 9218), (9523, 1, 9219), (9524, 1, 9220), (9525, 1, 9221),
  (9526, 1, 9222), (9527, 1, 9223);

-- 角色 2（ADMIN）：除永久封禁外的全部内容安全能力
INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9528, 2, 9210), (9529, 2, 9211), (9530, 2, 9212), (9531, 2, 9213),
  (9532, 2, 9214), (9533, 2, 9215), (9534, 2, 9216), (9535, 2, 9217),
  (9536, 2, 9218), (9537, 2, 9220), (9538, 2, 9221), (9539, 2, 9222),
  (9540, 2, 9223);

-- 角色 3（OPERATOR）：队列/举报/申诉/版权查看（不可看 L3 明文、不可永久封禁）
INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9541, 3, 9214), (9542, 3, 9215), (9543, 3, 9216), (9544, 3, 9217),
  (9545, 3, 9218), (9546, 3, 9221), (9547, 3, 9223),
  (9548, 3, 9060), (9549, 3, 9061);

-- -----------------------------------------------------------------------------
-- 7. 词库种子（演示数据；生产以官方合规词库替换）
--    种子使用显式 ID，避开雪花 ID 区间。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_sensitive_word`
  (`id`, `word`, `raw_word`, `level`, `category`, `source`, `hit_count`, `status`, `remark`, `version`) VALUES
  (1000000000000018101, '敏感词',   '敏感词',   3, 'CUSTOM', 'OFFICIAL', 0, 1, '演示用 L3 禁止词', 1),
  (1000000000000018102, '赌博网站', '赌博网站', 3, '违法',   'OFFICIAL', 0, 1, '演示：违法信息', 1),
  (1000000000000018103, '违禁品交易','违禁品交易',3,'违法',  'OFFICIAL', 0, 1, '演示：违禁品', 1),
  (1000000000000018104, '办证刷单', '办证刷单', 3, '广告',   'OFFICIAL', 0, 1, '演示：黑产广告', 1),
  (1000000000000018105, '加微信',   '加 微信',  2, '广告',   'OPERATION',0, 1, '演示：引流（归一化后去掉空格）', 1),
  (1000000000000018106, '代开发票', '代开发票', 2, '广告',   'OPERATION',0, 1, '演示：违规广告', 1),
  (1000000000000018107, '私彩',     '私彩',     2, '违法',   'OPERATION',0, 1, '演示：私彩', 1),
  (1000000000000018108, '裸聊',     '裸聊',     2, '涉黄',   'OFFICIAL', 0, 1, '演示：涉黄', 1),
  (1000000000000018109, '最便宜',   '最便宜',   1, '广告',   'OPERATION',0, 1, '演示：极限词', 1),
  (1000000000000018110, '全网最低价','全网最低价',1,'广告',   'OPERATION',0, 1, '演示：极限词', 1),
  (1000000000000018111, '兼职日结', '兼职日结', 1, '广告',   'OPERATION',0, 1, '演示：可疑兼职', 1);

INSERT IGNORE INTO `newmusic`.`t_white_word` (`id`, `word`, `reason`, `creator_id`) VALUES
  (1000000000000018121, '加微信支付官方', '包含「加微信」但属正常业务表述', NULL),
  (1000000000000018122, '人民大会堂',     '专有名词，避免误伤', NULL);

INSERT IGNORE INTO `newmusic`.`t_audit_rule`
  (`id`, `rule_name`, `rule_type`, `pattern`, `scene`, `action`, `priority`, `status`, `remark`) VALUES
  (1000000000000018131, '手机号', 'REGEX', '1[3-9]\\d{9}', 'ALL', 'REVIEW', 10, 1, '联系方式引流'),
  (1000000000000018132, 'URL',    'REGEX', '(?i)(https?://|www\\.)[\\w\\-./?%&=]+', 'ALL', 'REVIEW', 20, 1, '外链'),
  (1000000000000018133, '邮箱',   'REGEX', '\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b', 'ALL', 'REVIEW', 30, 1, '联系方式'),
  (1000000000000018134, 'QQ号',   'REGEX', '(?i)\\bqq\\s*[:：]?\\s*[1-9]\\d{4,10}\\b', 'ALL', 'REVIEW', 40, 1, '站外引流');

INSERT IGNORE INTO `newmusic`.`t_sensitive_word_version`
  (`id`, `version`, `word_count`, `snapshot_path`, `operator_id`, `change_desc`) VALUES
  (1000000000000018141, 1, 11, 'db://sensitive-word/1', NULL, '演示词库初始化（11 个敏感词 + 2 个白名单）');

-- -----------------------------------------------------------------------------
-- 8. 版权种子：给前 3 首上架歌曲补版权信息（幂等，不覆盖已有记录）
--    其中第 1 首设为 20 天后到期，便于演示到期预警。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_copyright`
  (`id`, `song_id`, `copyright_owner`, `license_type`, `license_region`, `license_start`,
   `license_end`, `license_scope`, `contract_no`, `status`)
SELECT s.id + 1000000000000018000, s.id, '演示版权方', 'NON_EXCLUSIVE', '中国大陆',
       DATE_SUB(CURDATE(), INTERVAL 1 YEAR),
       IF(s.id = (SELECT MIN(id) FROM `newmusic`.`t_song` WHERE status = 1),
          DATE_ADD(CURDATE(), INTERVAL 20 DAY),
          DATE_ADD(CURDATE(), INTERVAL 2 YEAR)),
       '在线播放', CONCAT('DEMO-', s.id), 0
FROM `newmusic`.`t_song` s
WHERE s.status = 1
ORDER BY s.id
LIMIT 3;

-- -----------------------------------------------------------------------------
-- 9. 兼容旧配置：搜索/AI 两处旧词表保留为空，统一由模块 18 词库接管。
--    （不改历史配置值，只在模块 18 启动时优先读取 t_sensitive_word。）
-- -----------------------------------------------------------------------------
