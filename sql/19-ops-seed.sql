-- =============================================================================
-- 模块 19 高可用与运维：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~18，只需补运维相关表、权限与种子。
--
-- 安全约束（本机 3306 是共享实例，另有 emp_db/music_db）：
--   * 所有对象显式带 `newmusic.` 库名，不触碰其它库；
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE / ON DUPLICATE KEY UPDATE；
--   * 不清表、不删数据、不做全库级 DDL/DML；
--   * Redis 键全部以 `ml:` 开头，见 sql/README.md 的模块 19 小节。
--
-- 本模块的本地边界（与 PRD 28 的刻意偏差）：
--   1. 本机没有 Prometheus/Grafana/SkyWalking/RocketMQ/Redis Cluster/K8s，
--      因此交付「应用内可执行的运维面」：规则热更新、限流/熔断/降级、
--      指标与 Prometheus 文本导出、告警引擎、trace 环形缓冲、逻辑备份清单、
--      混沌实验的记录与安全注入；真实中间件指标由部署侧接入。
--   2. 发布/回滚在这里是**记录与状态流转**，不执行真实部署；
--      备份是**清单 + 校验和**，不是 mysqldump。两者都在接口响应里写明。
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 限流规则（FR-28-001 / AC-28-100~106）
--    阈值刻意留得比验收脚本的请求量大一个数量级：默认规则用于保护，
--    不是用来把本机冒烟打成 429。验收脚本会临时把 SEARCH 调到 2 再恢复。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_rate_limit_rule` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `resource`       VARCHAR(64)  NOT NULL COMMENT 'GLOBAL/PLAY/SEARCH/AI/COMMENT/POST/LOGIN',
  `match_pattern`  VARCHAR(255) NOT NULL COMMENT 'URI 前缀，最长匹配优先',
  `http_method`    VARCHAR(16)  DEFAULT NULL COMMENT '为空表示全部方法',
  `dimension`      VARCHAR(16)  NOT NULL DEFAULT 'IP' COMMENT 'GLOBAL/IP/USER',
  `threshold`      INT          NOT NULL COMMENT '窗口内允许次数',
  `window_seconds` INT          NOT NULL COMMENT '滑动窗口秒数',
  `enabled`        TINYINT      NOT NULL DEFAULT 1,
  `whitelist_ips`  VARCHAR(1000) DEFAULT NULL COMMENT '逗号分隔；仅 IP 维度生效',
  `description`    VARCHAR(255) DEFAULT NULL,
  `operator_id`    BIGINT UNSIGNED DEFAULT NULL,
  `version`        INT          NOT NULL DEFAULT 0,
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ops_rate_resource` (`resource`),
  KEY `idx_ops_rate_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 接口限流规则（热更新）';

INSERT IGNORE INTO `newmusic`.`t_ops_rate_limit_rule`
  (`id`, `resource`, `match_pattern`, `http_method`, `dimension`, `threshold`, `window_seconds`,
   `enabled`, `description`) VALUES
  (1000000000000019101, 'GLOBAL',  '/api/v1/',          NULL, 'GLOBAL', 12000, 60, 1, '全站限流兜底（按资源聚合，不按 path 分片；本机验收不触发）'),
  (1000000000000019102, 'PLAY',    '/api/v1/player',    'GET', 'IP',    2400, 60, 1, '播放地址签发'),
  (1000000000000019103, 'PLAY_REPORT', '/api/v1/play',  NULL, 'IP',    2400, 60, 1, '播放进度/上报'),
  (1000000000000019104, 'SEARCH',  '/api/v1/search',    NULL, 'IP',    2400, 60, 1, '搜索/联想/热搜'),
  (1000000000000019105, 'AI',      '/api/v1/ai',        NULL, 'USER',  1200, 60, 1, 'AI 能力'),
  (1000000000000019106, 'COMMENT', '/api/v1/comments',  NULL, 'USER',   600, 60, 1, '评论/回复'),
  (1000000000000019107, 'POST',    '/api/v1/posts',     NULL, 'USER',   300, 60, 1, '动态发布'),
  (1000000000000019108, 'LOGIN',   '/api/v1/auth/login','POST', 'IP',    120, 60, 1, '登录爆破保护');

-- -----------------------------------------------------------------------------
-- 2. 降级开关（FR-28-003 / AC-28-111~116）
--    degrade_level = 从第几级开始关闭该功能；enabled=1 表示功能正常。
--    等级：0 正常 / 1 轻 / 2 中 / 3 重 / 4 只读前夜 / 5 只读
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_degrade_switch` (
  `switch_key`    VARCHAR(64)  NOT NULL,
  `switch_name`   VARCHAR(100) NOT NULL,
  `enabled`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=功能正常 0=已降级关闭',
  `degrade_level` TINYINT      NOT NULL DEFAULT 2 COMMENT '达到该等级时关闭；99=永不关闭',
  `description`   VARCHAR(255) DEFAULT NULL,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `version`       INT          NOT NULL DEFAULT 0,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`switch_key`),
  KEY `idx_degrade_level` (`degrade_level`),
  KEY `idx_degrade_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 五级降级开关';

INSERT IGNORE INTO `newmusic`.`t_degrade_switch`
  (`switch_key`, `switch_name`, `enabled`, `degrade_level`, `description`) VALUES
  ('feature.search.suggest', '搜索联想',        1, 1, '关闭后搜索框只提交完整关键词'),
  ('feature.search.hot',     '热搜榜',          1, 1, '关闭后热搜区域显示缓存/占位'),
  ('feature.ai',             'AI 能力',         1, 2, '歌词翻译、情绪标签、AI 歌单'),
  ('feature.recommend',      '推荐流',          1, 2, '关闭后首页回退到编辑精选/热门'),
  ('feature.room.gift',      '音乐房礼物',      1, 2, '礼物动画与榜单'),
  ('feature.tracking',       '埋点采集',        1, 3, '关闭后前端不再上报，减少写入压力'),
  ('feature.upload',         '上传能力',        1, 3, '头像/封面/音频上传'),
  ('feature.social.comment', '评论',            1, 4, '关闭后发评论/回复'),
  ('feature.social.post',    '动态发布',        1, 4, '关闭后发动态/转发'),
  ('feature.room.chat',      '房间聊天/弹幕',   1, 4, 'WS 聊天与弹幕'),
  ('feature.social.message', '私信',            1, 4, '私信与会话'),
  ('feature.write',          '全部写操作',      1, 5, '只读模式：除登录/播放上报外的写请求全部拒绝'),
  ('feature.user.profile',   '资料编辑',        1, 5, '昵称/简介/头像修改'),
  ('feature.playlist',       '歌单编辑',        1, 5, '新建/编辑/收藏歌单'),
  ('feature.report',         '举报提交',        1, 5, '内容举报'),
  ('feature.share',          '分享创建',        1, 5, '分享短链'),
  ('feature.user.register',  '新用户注册',      1, 5, '注册通道'),
  ('feature.safety',         '安全与申诉',      1, 99, '合规能力永不关闭');

CREATE TABLE IF NOT EXISTS `newmusic`.`t_degrade_event` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `from_level`        TINYINT      NOT NULL,
  `to_level`          TINYINT      NOT NULL,
  `switches_changed`  INT          NOT NULL DEFAULT 0,
  `reason`            VARCHAR(255) DEFAULT NULL,
  `operator_id`       BIGINT UNSIGNED DEFAULT NULL,
  `operator_name`     VARCHAR(64)  DEFAULT NULL,
  `create_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_degrade_event_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 降级等级变更记录';

INSERT IGNORE INTO `newmusic`.`t_degrade_event`
  (`id`, `from_level`, `to_level`, `switches_changed`, `reason`, `operator_name`)
  VALUES (1000000000000019151, 0, 0, 0, '初始化：全部功能正常', 'system');

-- -----------------------------------------------------------------------------
-- 3. 告警规则 / 记录 / 静默（FR-28-007 / AC-28-138~143）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_alert_rule` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `rule_name`        VARCHAR(64)  NOT NULL,
  `metric`           VARCHAR(64)  NOT NULL COMMENT 'OpsMetrics 暴露的指标键',
  `operator`         VARCHAR(4)   NOT NULL DEFAULT 'GT' COMMENT 'GT/GE/LT/LE',
  `threshold_value`  DOUBLE       NOT NULL,
  `duration_seconds` INT          NOT NULL DEFAULT 60 COMMENT '防抖：连续满足多久才触发',
  `severity`         VARCHAR(4)   NOT NULL DEFAULT 'P2' COMMENT 'P0/P1/P2/P3',
  `enabled`          TINYINT      NOT NULL DEFAULT 1,
  `runbook_key`      VARCHAR(64)  DEFAULT NULL,
  `description`      VARCHAR(255) DEFAULT NULL,
  `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ops_alert_name` (`rule_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 告警规则（for 防抖）';

INSERT IGNORE INTO `newmusic`.`t_ops_alert_rule`
  (`id`, `rule_name`, `metric`, `operator`, `threshold_value`, `duration_seconds`, `severity`,
   `enabled`, `runbook_key`, `description`) VALUES
  (1000000000000019201, 'service_error_rate',  'http.errorRate',       'GT', 0.05,  60, 'P0', 1, 'app-all-down', '5xx 错误率 > 5%'),
  (1000000000000019202, 'http_p99_latency',    'http.p99Ms',           'GT', 1000, 180, 'P1', 1, 'app-slow',     'P99 > 1s'),
  (1000000000000019203, 'database_unavailable','db.available',         'LT', 0.5,   30, 'P0', 1, 'mysql-down',   '数据库不可用'),
  (1000000000000019204, 'redis_unavailable',   'redis.available',      'LT', 0.5,   60, 'P1', 1, 'redis-down',   'Redis 不可用（有降级路径，不重启实例）'),
  (1000000000000019205, 'jvm_heap_high',       'jvm.heapUsedRatio',    'GT', 0.9,  300, 'P1', 1, 'app-slow',     '堆内存使用率 > 90%'),
  (1000000000000019206, 'degrade_level_raised','degrade.level',        'GT', 1,      5, 'P1', 1, 'degrade',      '降级等级 > 1（人工或自动）'),
  (1000000000000019207, 'rate_limit_surge',    'rate.rejectedLastMin', 'GT', 200,   60, 'P2', 1, 'app-slow',     '限流拒绝量异常'),
  (1000000000000019208, 'ai_cost_budget',      'cost.budgetRatio',     'GT', 0.8,   60, 'P1', 1, 'ai-cost',      'AI 日成本达到预算 80%'),
  (1000000000000019209, 'disk_space_low',      'disk.freeRatio',       'LT', 0.15, 300, 'P1', 1, 'disk-full',    '磁盘可用 < 15%'),
  (1000000000000019210, 'chaos_running',       'chaos.running',        'GT', 0.5,   30, 'P2', 1, 'chaos',        '混沌实验进行中（有人值守）');

CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_alert_silence` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `rule_name`   VARCHAR(64)  DEFAULT NULL COMMENT '为空表示按级别静默',
  `severity`    VARCHAR(4)   DEFAULT NULL,
  `until_time`  DATETIME(3)  NOT NULL,
  `reason`      VARCHAR(255) DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED DEFAULT NULL,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_alert_silence_until` (`until_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 告警静默（维护期）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_alert_record` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `rule_id`        BIGINT UNSIGNED NOT NULL,
  `alert_name`     VARCHAR(64)  NOT NULL,
  `severity`       VARCHAR(4)   NOT NULL,
  `instance`       VARCHAR(128) DEFAULT NULL,
  `summary`        VARCHAR(500) DEFAULT NULL,
  `metric_value`   DOUBLE       DEFAULT NULL,
  `threshold_value` DOUBLE      DEFAULT NULL,
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'firing' COMMENT 'firing/resolved/silenced',
  `fire_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `resolve_time`   DATETIME(3)  DEFAULT NULL,
  `handler_id`     BIGINT UNSIGNED DEFAULT NULL,
  `handler_name`   VARCHAR(64)  DEFAULT NULL,
  `handle_note`    VARCHAR(500) DEFAULT NULL,
  `dedup_key`      VARCHAR(128) DEFAULT NULL,
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_alert_record_status` (`status`, `fire_time`),
  KEY `idx_alert_record_name` (`alert_name`, `fire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 告警记录';

-- -----------------------------------------------------------------------------
-- 4. 发布记录（FR-28-009 / AC-28-150~154）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_publish_record` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `version`         VARCHAR(32)  NOT NULL,
  `service`         VARCHAR(64)  NOT NULL DEFAULT 'newmusic',
  `environment`     VARCHAR(32)  NOT NULL DEFAULT 'local',
  `publish_type`    VARCHAR(16)  NOT NULL DEFAULT 'CANARY' COMMENT 'ROLLING/CANARY/BLUE_GREEN/AB',
  `git_commit`      VARCHAR(64)  DEFAULT NULL,
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/SUCCESS/FAILED/ROLLED_BACK',
  `publisher`       VARCHAR(64)  DEFAULT NULL,
  `start_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `end_time`        DATETIME(3)  DEFAULT NULL,
  `remark`          VARCHAR(500) DEFAULT NULL,
  `rollback_version` VARCHAR(32) DEFAULT NULL,
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_publish_version` (`version`),
  KEY `idx_publish_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 发布记录（本机只记录不部署）';

INSERT IGNORE INTO `newmusic`.`t_publish_record`
  (`id`, `version`, `service`, `environment`, `publish_type`, `git_commit`, `status`, `publisher`,
   `start_time`, `end_time`, `remark`) VALUES
  (1000000000000019251, 'v0.19.0', 'newmusic', 'local', 'ROLLING', 'local-snapshot',
   'SUCCESS', 'system', NOW(3), NOW(3), '模块19 初始化基线（记录模式，不执行真实部署）');

-- -----------------------------------------------------------------------------
-- 5. 备份记录（FR-28-012 / AC-28-162~163）
--    本机不执行 mysqldump：记录「表数 + 行数 + ml:* 键数 + 校验和」。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_backup_record` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `backup_type`   VARCHAR(16)  NOT NULL DEFAULT 'FULL' COMMENT 'FULL/BINLOG/REDIS',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'RUNNING/SUCCESS/FAILED',
  `size_bytes`    BIGINT       NOT NULL DEFAULT 0,
  `location`      VARCHAR(255) DEFAULT NULL,
  `checksum`      VARCHAR(64)  DEFAULT NULL,
  `verify_status` VARCHAR(16)  NOT NULL DEFAULT 'PASS' COMMENT 'PASS/FAIL',
  `table_count`   INT          NOT NULL DEFAULT 0,
  `row_count`     BIGINT       NOT NULL DEFAULT 0,
  `started_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at`   DATETIME(3)  DEFAULT NULL,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL,
  `message`       VARCHAR(500) DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_backup_time` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 备份清单与校验';

-- -----------------------------------------------------------------------------
-- 6. 混沌实验（FR-28-011 / AC-28-160~161，P2）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_chaos_experiment` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `experiment_name`  VARCHAR(100) NOT NULL,
  `target`           VARCHAR(64)  NOT NULL,
  `fault_type`       VARCHAR(32)  NOT NULL COMMENT 'REDIS_LIMITER_UNAVAILABLE/AI_TIMEOUT/SLOW_DB',
  `hypothesis`       VARCHAR(500) DEFAULT NULL,
  `blast_radius`     INT          NOT NULL DEFAULT 1 COMMENT '最多影响的请求数',
  `duration_seconds` INT          NOT NULL DEFAULT 30,
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/FINISHED/STOPPED',
  `result`           VARCHAR(16)  DEFAULT NULL COMMENT 'PASS/FAIL',
  `findings`         VARCHAR(1000) DEFAULT NULL,
  `operator_id`      BIGINT UNSIGNED DEFAULT NULL,
  `start_time`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `end_time`         DATETIME(3)  DEFAULT NULL,
  `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_chaos_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 混沌实验记录（爆炸半径受控）';

-- -----------------------------------------------------------------------------
-- 7. 成本记录（FR-28-013 / AC-28-167~170）
--    按 PRD 的月度估算拆到「日」，便于验证预算比例与趋势。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_cost_record` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `cost_date`    DATE         NOT NULL,
  `category`     VARCHAR(32)  NOT NULL COMMENT 'CDN/SERVER/DATABASE/REDIS/STORAGE/AI/AUDIT/SMS/LOG',
  `service`      VARCHAR(64)  NOT NULL DEFAULT 'newmusic',
  `amount_cent`  BIGINT       NOT NULL DEFAULT 0,
  `budget_cent`  BIGINT       NOT NULL DEFAULT 0,
  `detail`       VARCHAR(255) DEFAULT NULL,
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_date_category` (`cost_date`, `category`, `service`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 成本日记录';

-- id 用 UUID_SHORT()：脚本可能在不同日期重复执行，固定 ID 会让第二天的
-- INSERT 撞上第一天的旧行，导致「今天的成本」永远不落库。
INSERT INTO `newmusic`.`t_cost_record`
  (`id`, `cost_date`, `category`, `service`, `amount_cent`, `budget_cent`, `detail`) VALUES
  (UUID_SHORT(), CURDATE(), 'CDN',      'newmusic', 50000, 60000, '音频 CDN 流量（按 PRD 月估算拆日）'),
  (UUID_SHORT(), CURDATE(), 'SERVER',   'newmusic', 40000, 50000, '应用与中间件服务器'),
  (UUID_SHORT(), CURDATE(), 'DATABASE', 'newmusic', 16667, 20000, 'MySQL RDS'),
  (UUID_SHORT(), CURDATE(), 'REDIS',    'newmusic', 10000, 12000, 'Redis 集群'),
  (UUID_SHORT(), CURDATE(), 'STORAGE',  'newmusic',  6667,  8000, '对象存储'),
  (UUID_SHORT(), CURDATE(), 'AI',       'newmusic', 26667, 30000, 'LLM 调用（mock 演示同样计成本）'),
  (UUID_SHORT(), CURDATE(), 'AUDIT',    'newmusic',  6667,  8000, '内容审核'),
  (UUID_SHORT(), CURDATE(), 'SMS',      'newmusic',  3333,  4000, '短信/邮件'),
  (UUID_SHORT(), CURDATE(), 'LOG',      'newmusic',  5000,  6000, '日志与监控存储')
ON DUPLICATE KEY UPDATE
  `amount_cent` = VALUES(`amount_cent`),
  `budget_cent` = VALUES(`budget_cent`),
  `detail`      = VALUES(`detail`);

-- -----------------------------------------------------------------------------
-- 8. 应急预案 Runbook（FR-28-011 runbook / AC-28-179，≥7 个场景）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_runbook` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `runbook_key` VARCHAR(64)  NOT NULL,
  `title`       VARCHAR(100) NOT NULL,
  `severity`    VARCHAR(4)   NOT NULL DEFAULT 'P1',
  `symptom`     VARCHAR(500) DEFAULT NULL,
  `steps`       TEXT,
  `owner`       VARCHAR(64)  DEFAULT 'SRE',
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_runbook_key` (`runbook_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 应急预案';

INSERT IGNORE INTO `newmusic`.`t_ops_runbook`
  (`id`, `runbook_key`, `title`, `severity`, `symptom`, `steps`, `owner`) VALUES
  (1000000000000019401, 'app-all-down', '应用实例全部不可用', 'P0', '健康检查失败，所有实例 5xx',
   '1. 查 Pod/进程状态与启动日志；2. 查最近发布记录，必要时一键回滚；3. 查 DB/Redis 依赖；4. 资源不足则扩容；5. 无法快速恢复时启用静态兜底页。', 'SRE'),
  (1000000000000019402, 'app-slow', '接口 P99 突增', 'P1', 'P99 > 1s 或慢 SQL 告警',
   '1. 看 /admin/system/metrics 找慢接口；2. 查慢 SQL 与连接池；3. 查 GC 与线程池；4. 必要时开启降级等级 1/2；5. 保留 traceId 复盘。', '开发'),
  (1000000000000019403, 'redis-down', 'Redis 全量不可用', 'P0', '缓存 miss 暴增、DB 压力上升',
   '1. 查 Redis 状态与内存；2. 限流切换为单机滑动窗口（本模块已内置）；3. 锁降级为 DB 乐观锁；4. Feed 降级为全拉；5. 恢复后预热热 key。关键是先保护 DB。', 'SRE'),
  (1000000000000019404, 'mysql-down', 'MySQL 主库不可用', 'P0', '写操作失败，连接池耗尽',
   '1. 查主库进程/复制/磁盘；2. 触发主从切换（生产 Orchestrator，本地无）；3. 应用重连；4. 校验数据一致性；5. 前端提示稍后重试。', 'DBA'),
  (1000000000000019405, 'play-drop', '播放量骤降', 'P0', '播放指标环比下降 > 30%',
   '1. 先确认埋点是否正常；2. 查 CDN 404/超时；3. 查播放签名与接口错误；4. 查前端 JS 错误；5. 判断是否全站故障。', 'SRE'),
  (1000000000000019406, 'content-leak', '高危内容漏出', 'P0', '发现违规内容已公开',
   '1. 立即下架；2. 定位词库/机审/人工误判；3. 热更新词库；4. 全量回溯同类内容；5. 30 分钟初步报告、2 小时复盘；6. 涉及监管红线按流程上报。', '内容安全'),
  (1000000000000019407, 'ai-cost', 'AI 成本突增', 'P1', 'AI 日成本达到预算 80%',
   '1. 按场景聚合 t_ai_call_log；2. 查缓存命中率；3. 查刷量用户并封禁；4. 降场景限流；5. 必要时熔断该场景 AI。', '算法/运营'),
  (1000000000000019408, 'ws-storm', 'WebSocket 连接数异常', 'P1', '连接数远超预期或骤降',
   '1. 查客户端重连风暴；2. 查实例分布；3. 下发退避重连间隔；4. 调整一致性 hash；5. 扩容 WS 实例。', '开发'),
  (1000000000000019409, 'disk-full', '磁盘写满', 'P1', '日志/备份写入失败，磁盘可用 < 15%',
   '1. 清理滚动日志；2. 检查大文件与备份保留期；3. 扩容磁盘；4. 验证日志采集恢复。', 'SRE'),
  (1000000000000019410, 'degrade', '人工降级/恢复', 'P1', '错误率或依赖故障需要降级',
   '1. 在后台选择降级等级；2. 确认前端降级横幅出现；3. 观察 DB/Redis/错误率；4. 恢复前逐项确认依赖；5. 记录等级变更事件。', 'SRE'),
  (1000000000000019411, 'chaos', '混沌演练异常', 'P1', '演练期间错误率/延迟超出预期或实验未按时结束',
   '1. 确认当前实验的故障类型与爆炸半径；2. 超出爆炸半径立即停止实验（/ops/chaos/stop）；3. 观察降级开关与限流兜底是否按预期生效；4. 实验最长 120s 自动结束，超时未结束按 P1 上报；5. 复盘记录演练结论与改进项。', 'SRE');

-- -----------------------------------------------------------------------------
-- 9. 指标快照（用于趋势与验收；定时任务每分钟写一条）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ops_metric_snapshot` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `metric_name`   VARCHAR(64)  NOT NULL,
  `metric_value`  DOUBLE       NOT NULL DEFAULT 0,
  `tags`          VARCHAR(255) DEFAULT NULL,
  `snapshot_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_metric_name_time` (`metric_name`, `snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模块19 指标快照';

-- -----------------------------------------------------------------------------
-- 10. 运维配置种子（FR-28-014 / AC-28-171~173）
--     沿用模块 09 的 t_sys_config + 变更历史 + 回滚，不另建配置中心。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_sys_config`
  (`id`, `config_key`, `config_value`, `value_type`, `config_group`, `description`, `is_public`, `status`) VALUES
  (1000000000000019501, 'ops.rate.limit.enabled',     'true', 3, 'OPS', '接口限流总开关（秒级热更新）', 0, 1),
  (1000000000000019502, 'ops.degrade.level',          '0',    2, 'OPS', '当前降级等级 0~5', 1, 1),
  (1000000000000019503, 'ops.trace.sample-rate',      '0.1',  2, 'OPS', '正常请求 trace 采样率（错误请求始终 100%）', 0, 1),
  (1000000000000019504, 'ops.alert.eval.enabled',     'true', 3, 'OPS', '告警评估开关', 0, 1),
  (1000000000000019505, 'ops.metrics.snapshot.enabled','true',3, 'OPS', '指标快照开关', 0, 1),
  (1000000000000019506, 'ops.cost.daily-budget-cent', '50000',2, 'OPS', '日成本预算（分）', 0, 1),
  (1000000000000019507, 'ops.backup.retention-days',  '7',    2, 'OPS', '本地备份清单保留天数', 0, 1),
  (1000000000000019508, 'ops.chaos.enabled',          'true', 3, 'OPS', '混沌实验开关（生产默认应关闭）', 0, 1);

-- -----------------------------------------------------------------------------
-- 11. 后台权限点与角色授权
--     ID 9230~9238 接在模块 18 的 9223 之后；授权 9550+（9549 已用）。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9230, 'admin:ops:view',        '运维面板查看',     '运维', 0, 1200, 1),
  (9231, 'admin:ops:edit',        '限流/降级/告警操作','运维', 1, 1201, 1),
  (9232, 'admin:ops:publish',     '发布与回滚',       '运维', 1, 1202, 1),
  (9233, 'admin:ops:chaos',       '混沌实验',         '运维', 1, 1203, 1),
  (9234, 'admin:ops:backup',      '备份与缓存管理',   '运维', 1, 1204, 1),
  (9235, 'admin:cost:view',       '成本查看',         '运维', 0, 1205, 1),
  (9236, 'admin:ops:alert:handle','告警处理',         '运维', 0, 1206, 1),
  (9237, 'admin:ops:trace:view',  '链路查询',         '运维', 0, 1207, 1),
  (9238, 'admin:ops:metrics:view','指标查看',         '运维', 0, 1208, 1);

INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9550, 1, 9230), (9551, 1, 9231), (9552, 1, 9232), (9553, 1, 9233),
  (9554, 1, 9234), (9555, 1, 9235), (9556, 1, 9236), (9557, 1, 9237),
  (9558, 1, 9238),
  -- ADMIN：可限流/降级/告警/发布/备份，但混沌实验仍归超管
  (9559, 2, 9230), (9560, 2, 9231), (9561, 2, 9232), (9562, 2, 9234),
  (9563, 2, 9235), (9564, 2, 9236), (9565, 2, 9237), (9566, 2, 9238),
  -- OPERATOR：只读面板 + 成本
  (9567, 3, 9230), (9568, 3, 9235), (9569, 3, 9238);
