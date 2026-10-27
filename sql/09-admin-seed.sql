-- =============================================================================
-- 模块 09 管理后台：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已经初始化过模块 01~08，只需要补后台相关表 / 列 / 种子。
-- 全新库仍然按 sql/README.md 执行 schema.sql + data.sql 即可（两者已包含本文件
-- 的全部内容）。本文件与 schema.sql 使用相同的表结构定义，方便单独升级。
--
-- 安全约束：
--   * 所有对象显式带 `newmusic.` 库名；本机 3306 是共享实例（还有 emp_db/music_db）
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE / 条件加列，不清表、不删数据
--   * 演示账号的生产密码与 TOTP 必须重置
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. t_user 增加强制改密标记（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用元数据判断）
-- -----------------------------------------------------------------------------
SET @add_force_change := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_user` ADD COLUMN `force_change_password` TINYINT NOT NULL DEFAULT 0 COMMENT ''强制下次登录修改密码（模块09管理员重置后置1）'' AFTER `password`',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'force_change_password'
);
PREPARE stmt FROM @add_force_change;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2. 后台 RBAC 与基础设施表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_admin_user` (
  `id`                    BIGINT UNSIGNED NOT NULL,
  `username`              VARCHAR(64)     NOT NULL COMMENT '后台登录名，唯一',
  `password`              VARCHAR(100)    NOT NULL COMMENT 'BCrypt',
  `real_name`             VARCHAR(64)     DEFAULT NULL,
  `email`                 VARCHAR(128)    DEFAULT NULL,
  `phone`                 VARCHAR(255)    DEFAULT NULL,
  `status`                TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常 2禁用',
  `totp_secret`           VARCHAR(255)    DEFAULT NULL COMMENT 'TOTP Base32；enc:v1: 前缀为 AES-GCM 密文',
  `totp_enabled`          TINYINT         NOT NULL DEFAULT 0,
  `force_change_password` TINYINT         NOT NULL DEFAULT 0,
  `password_update_time`  DATETIME(3)     DEFAULT NULL,
  `last_login_time`       DATETIME(3)     DEFAULT NULL,
  `last_login_ip`         VARCHAR(64)     DEFAULT NULL,
  `fail_count`            INT             NOT NULL DEFAULT 0,
  `lock_until`            DATETIME(3)     DEFAULT NULL,
  `allow_ip_list`         VARCHAR(512)    DEFAULT NULL,
  `remark`                VARCHAR(200)    DEFAULT NULL,
  `create_time`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`             BIGINT UNSIGNED DEFAULT NULL,
  `update_by`             BIGINT UNSIGNED DEFAULT NULL,
  `deleted`               TINYINT         NOT NULL DEFAULT 0,
  `version`               INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`),
  KEY `idx_admin_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台管理员账号';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_admin_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `role_code`   VARCHAR(50)  NOT NULL,
  `role_name`   VARCHAR(50)  NOT NULL,
  `description` VARCHAR(200) DEFAULT NULL,
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_admin_permission` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `perm_code`   VARCHAR(100) NOT NULL,
  `perm_name`   VARCHAR(100) NOT NULL,
  `perm_group`  VARCHAR(50)  DEFAULT NULL,
  `description` VARCHAR(200) DEFAULT NULL,
  `sensitive`   TINYINT      NOT NULL DEFAULT 0,
  `sort_order`  INT          NOT NULL DEFAULT 0,
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台权限定义';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_admin_user_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_role` (`user_id`, `role_id`),
  KEY `idx_admin_ur_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员-角色（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_admin_role_permission` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `perm_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_perm` (`role_id`, `perm_id`),
  KEY `idx_admin_rp_perm` (`perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色-权限（物理删除）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_operation_log` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `operator_id`     BIGINT UNSIGNED NOT NULL COMMENT '管理员ID；0=系统/匿名',
  `operator_name`   VARCHAR(64)     DEFAULT NULL,
  `op_type`         VARCHAR(32)     NOT NULL,
  `target_type`     VARCHAR(32)     DEFAULT NULL,
  `target_id`       VARCHAR(64)     DEFAULT NULL,
  `description`     VARCHAR(500)    DEFAULT NULL,
  `before_snapshot` TEXT,
  `after_snapshot`  TEXT,
  `ip`              VARCHAR(64)     DEFAULT NULL,
  `user_agent`      VARCHAR(512)    DEFAULT NULL,
  `result`          TINYINT         NOT NULL DEFAULT 1,
  `error_msg`       VARCHAR(500)    DEFAULT NULL,
  `trace_id`        VARCHAR(64)     DEFAULT NULL,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_oplog_operator_time` (`operator_id`, `create_time`),
  KEY `idx_oplog_type_time` (`op_type`, `create_time`),
  KEY `idx_oplog_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台操作日志（只增不改不删）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_login_log` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED DEFAULT NULL,
  `username`    VARCHAR(64)     DEFAULT NULL,
  `login_type`  TINYINT         NOT NULL DEFAULT 1 COMMENT '1 C端 2 后台',
  `ip`          VARCHAR(64)     DEFAULT NULL,
  `location`    VARCHAR(64)     DEFAULT NULL,
  `user_agent`  VARCHAR(512)    DEFAULT NULL,
  `device`      VARCHAR(100)    DEFAULT NULL,
  `result`      TINYINT         NOT NULL COMMENT '1成功 0失败 2锁定',
  `fail_reason` VARCHAR(200)    DEFAULT NULL,
  `suspicious`  TINYINT         NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_login_user_time` (`user_id`, `create_time`),
  KEY `idx_login_type_time` (`login_type`, `create_time`),
  KEY `idx_login_ip_time` (`ip`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志（C端+后台）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_sys_config_history` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `config_key`      VARCHAR(100) NOT NULL,
  `old_value`       TEXT,
  `new_value`       TEXT,
  `value_type`      TINYINT      NOT NULL DEFAULT 1,
  `change_type`     VARCHAR(16)  NOT NULL DEFAULT 'UPDATE',
  `changed_by`      BIGINT UNSIGNED DEFAULT NULL,
  `changed_by_name` VARCHAR(64)  DEFAULT NULL,
  `remark`          VARCHAR(200) DEFAULT NULL,
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_cfg_his_key_time` (`config_key`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置变更历史';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_audit_task` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `task_type`         VARCHAR(32)  NOT NULL,
  `target_type`       VARCHAR(32)  NOT NULL,
  `target_id`         VARCHAR(64)  NOT NULL,
  `priority`          TINYINT      NOT NULL DEFAULT 2,
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '0待领 1已领 2通过 3拒绝 4自动关闭',
  `submitter_id`      BIGINT UNSIGNED DEFAULT NULL,
  `title`             VARCHAR(200) DEFAULT NULL,
  `content_snapshot`  TEXT,
  `machine_result`    VARCHAR(500) DEFAULT NULL,
  `machine_score`     DECIMAL(5,4) DEFAULT NULL,
  `claimed_by`        BIGINT UNSIGNED DEFAULT NULL,
  `claimed_at`        DATETIME(3)  DEFAULT NULL,
  `claim_expire_at`   DATETIME(3)  DEFAULT NULL,
  `handled_by`        BIGINT UNSIGNED DEFAULT NULL,
  `handled_at`        DATETIME(3)  DEFAULT NULL,
  `handle_result`     VARCHAR(16)  DEFAULT NULL,
  `reject_reason`     VARCHAR(500) DEFAULT NULL,
  `action_applied`    TINYINT      NOT NULL DEFAULT 0,
  `escalated`         TINYINT      NOT NULL DEFAULT 0,
  `escalated_at`      DATETIME(3)  DEFAULT NULL,
  `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `version`           INT          NOT NULL DEFAULT 0,
  `open_key`          VARCHAR(128) GENERATED ALWAYS AS (
                        CASE WHEN `status` IN (0, 1)
                             THEN CONCAT(`task_type`, CHAR(58), `target_id`)
                             ELSE NULL END
                      ) STORED COMMENT '未完成任务唯一键；已完成/已关闭为 NULL',
  PRIMARY KEY (`id`),
  KEY `idx_audit_status_priority` (`status`, `priority`, `create_time`),
  KEY `idx_audit_target` (`target_type`, `target_id`),
  KEY `idx_audit_claimed` (`claimed_by`, `claim_expire_at`),
  UNIQUE KEY `uk_audit_open_target` (`open_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核任务（领取锁 10 分钟）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_audit_record` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `auditor_id`    BIGINT UNSIGNED DEFAULT NULL,
  `auditor_name`  VARCHAR(64)  DEFAULT NULL,
  `action`        VARCHAR(16)  NOT NULL,
  `before_status` TINYINT      DEFAULT NULL,
  `after_status`  TINYINT      DEFAULT NULL,
  `reason`        VARCHAR(500) DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_record_task` (`task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核留痕';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_report` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `reporter_id`   BIGINT UNSIGNED NOT NULL,
  `reporter_name` VARCHAR(64)  DEFAULT NULL,
  `target_type`   VARCHAR(32)  NOT NULL,
  `target_id`     VARCHAR(64)  NOT NULL,
  `target_name`   VARCHAR(200) DEFAULT NULL,
  `reason_type`   VARCHAR(32)  DEFAULT NULL,
  `reason`        VARCHAR(500) DEFAULT NULL,
  `evidence_urls` VARCHAR(1000) DEFAULT NULL,
  `priority`      TINYINT      NOT NULL DEFAULT 2,
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1成立 2不成立 3待补充 4已合并',
  `handled_by`    BIGINT UNSIGNED DEFAULT NULL,
  `handled_at`    DATETIME(3)  DEFAULT NULL,
  `handle_result` VARCHAR(500) DEFAULT NULL,
  `handle_reason` VARCHAR(500) DEFAULT NULL,
  `action_taken`  VARCHAR(32)  DEFAULT NULL,
  `merged_into_id` BIGINT UNSIGNED DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `version`       INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_report_status_priority` (`status`, `priority`, `create_time`),
  KEY `idx_report_target` (`target_type`, `target_id`),
  KEY `idx_report_reporter` (`reporter_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容举报';

-- -----------------------------------------------------------------------------
-- 3. 种子：角色 / 权限 / 角色授权
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_role` (`id`, `role_code`, `role_name`, `description`, `status`) VALUES
  (1, 'SUPER_ADMIN', '超级管理员', '全部权限 + 管理员账号管理 + 系统配置 + 危险操作', 1),
  (2, 'ADMIN',       '平台管理员', '用户/内容/房间/评论管理、审核、只读日志',             1),
  (3, 'OPERATOR',    '运营人员',   '数据看板、推荐位/Banner/热搜/场景歌单、仅自己的日志', 1);

INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9001, 'admin:dashboard:view',      '数据看板',          '数据看板', 0,  10, 1),
  (9010, 'admin:user:list',           '用户列表',          '用户',     0, 100, 1),
  (9011, 'admin:user:view',           '用户详情',          '用户',     0, 101, 1),
  (9012, 'admin:user:edit',           '编辑用户/重置密码', '用户',     0, 102, 1),
  (9013, 'admin:user:ban',            '封禁用户',          '用户',     0, 103, 1),
  (9014, 'admin:user:unban',          '解封用户',          '用户',     0, 104, 1),
  (9015, 'admin:user:phone:view',     '查看完整手机号',    '用户',     1, 105, 1),
  (9020, 'admin:song:list',           '歌曲列表',          '内容',     0, 200, 1),
  (9021, 'admin:song:create',         '新增歌曲/导入',     '内容',     0, 201, 1),
  (9022, 'admin:song:edit',           '编辑歌曲',          '内容',     0, 202, 1),
  (9023, 'admin:song:online',         '歌曲上架',          '内容',     0, 203, 1),
  (9024, 'admin:song:offline',        '歌曲下架',          '内容',     0, 204, 1),
  (9025, 'admin:song:delete',         '删除歌曲',          '内容',     0, 205, 1),
  (9030, 'admin:artist:manage',       '歌手管理',          '内容',     0, 210, 1),
  (9031, 'admin:album:manage',        '专辑管理',          '内容',     0, 211, 1),
  (9032, 'admin:playlist:manage',     '歌单管理',          '内容',     0, 212, 1),
  (9033, 'admin:category:manage',     '分类/标签管理',     '内容',     0, 213, 1),
  (9040, 'admin:room:list',           '房间列表/详情',     '房间',     0, 300, 1),
  (9041, 'admin:room:close',          '强制关闭房间',      '房间',     0, 301, 1),
  (9042, 'admin:room:message:view',   '查看房间消息',      '房间',     1, 302, 1),
  (9050, 'admin:audit:list',          '审核任务列表',      '审核',     0, 400, 1),
  (9051, 'admin:audit:handle',        '领取/处理审核任务', '审核',     0, 401, 1),
  (9060, 'admin:report:list',         '举报列表',          '审核',     0, 410, 1),
  (9061, 'admin:report:handle',       '处理举报',          '审核',     0, 411, 1),
  (9062, 'admin:comment:manage',      '评论查看',          '审核',     0, 420, 1),
  (9063, 'admin:comment:delete',      '评论删除',          '审核',     0, 421, 1),
  (9070, 'admin:config:banner',       'Banner 配置',       '运营配置', 0, 500, 1),
  (9071, 'admin:config:hotsearch',    '热搜配置',          '运营配置', 0, 501, 1),
  (9072, 'admin:config:scene',        '场景歌单配置',      '运营配置', 0, 502, 1),
  (9073, 'admin:recommend:slot',      '推荐位配置',        '运营配置', 0, 503, 1),
  (9074, 'admin:recommend:stats',     '推荐效果统计',      '运营配置', 0, 504, 1),
  (9080, 'admin:log:view',            '查看全部日志',      '日志',     0, 600, 1),
  (9081, 'admin:log:view:self',       '查看自己的日志',    '日志',     0, 601, 1),
  (9082, 'admin:log:export',          '导出操作日志',      '日志',     1, 602, 1),
  (9090, 'admin:sysconfig:view',      '系统配置查看',      '系统',     0, 700, 1),
  (9091, 'admin:sysconfig:edit',      '系统配置修改/回滚', '系统',     0, 701, 1),
  (9092, 'admin:adminuser:manage',    '管理员账号管理',    '系统',     0, 702, 1),
  (9093, 'admin:danger:reindex',      '重建索引',          '系统',     0, 710, 1),
  (9094, 'admin:danger:clearcache',   '清理缓存',          '系统',     0, 711, 1),
  (9100, '*:*:*',                     '全量权限（超管）',  '系统',     0, 999, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`)
SELECT 9300 + p.id, 1, p.id FROM `newmusic`.`t_admin_permission` p WHERE p.id BETWEEN 9001 AND 9094;
INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9999, 1, 9100);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9401, 2, 9001),
  (9410, 2, 9010), (9411, 2, 9011), (9412, 2, 9012), (9413, 2, 9013), (9414, 2, 9014), (9415, 2, 9015),
  (9420, 2, 9020), (9421, 2, 9021), (9422, 2, 9022), (9423, 2, 9023), (9424, 2, 9024),
  (9430, 2, 9030), (9431, 2, 9031), (9432, 2, 9032), (9433, 2, 9033),
  (9440, 2, 9040), (9441, 2, 9041), (9442, 2, 9042),
  (9450, 2, 9050), (9451, 2, 9051), (9460, 2, 9060), (9461, 2, 9061),
  (9462, 2, 9062), (9463, 2, 9063),
  (9470, 2, 9070), (9471, 2, 9071), (9472, 2, 9072), (9473, 2, 9073), (9474, 2, 9074),
  (9480, 2, 9080), (9481, 2, 9081);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9501, 3, 9001),
  (9570, 3, 9070), (9571, 3, 9071), (9572, 3, 9072), (9573, 3, 9073), (9574, 3, 9074),
  (9581, 3, 9081);

-- -----------------------------------------------------------------------------
-- 4. 演示管理员账号（生产必须重置密码与 TOTP）
--    superadmin / admin123     SUPER_ADMIN，TOTP 演示密钥 JBSWY3DPEHPK3PXP
--    admin      / admin123     ADMIN
--    operator   / operator123  OPERATOR
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_user`
  (`id`, `username`, `password`, `real_name`, `email`, `status`, `totp_secret`, `totp_enabled`,
   `force_change_password`, `password_update_time`, `remark`) VALUES
  (1000000000000020001, 'superadmin',
   '$2a$10$ttdk9FzHXNcEIquDlmp4l.LCaJ1W/iVk6FGxQjqw2IJPgLO.bedO6',
   '超级管理员', 'superadmin@example.com', 1, 'JBSWY3DPEHPK3PXP', 1, 0, NOW(3), '演示账号，生产必须重置密码与 TOTP'),
  (1000000000000020002, 'admin',
   '$2a$10$ttdk9FzHXNcEIquDlmp4l.LCaJ1W/iVk6FGxQjqw2IJPgLO.bedO6',
   '平台管理员', 'admin@example.com', 1, NULL, 0, 0, NOW(3), '演示账号，生产必须重置密码'),
  (1000000000000020003, 'operator',
   '$2a$10$pdF9p7nkfln6uebaUhp41.si6Db5FuOQhxEAS74tcMw2vfdeYctJK',
   '运营人员', 'operator@example.com', 1, NULL, 0, 0, NOW(3), '演示账号，生产必须重置密码');

INSERT IGNORE INTO `newmusic`.`t_admin_user_role` (`id`, `user_id`, `role_id`) VALUES
  (9601, 1000000000000020001, 1),
  (9602, 1000000000000020002, 2),
  (9603, 1000000000000020003, 3);

-- -----------------------------------------------------------------------------
-- 5. 系统配置：搜索敏感词词典（模块 06 按此 key 热读取）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_sys_config`
  (`id`, `config_key`, `config_value`, `value_type`, `config_group`, `description`, `is_public`, `status`) VALUES
  (1000000000000012004, 'search.sensitive.words', '', '1', 'security', '搜索关键词敏感词，逗号分隔；为空表示不拦截（模块18词库就位后迁走）', 0, 1);
