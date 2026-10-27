-- =============================================================================
-- 审核任务「同一目标只允许一条未完成任务」：既有库升级脚本（幂等，可重复执行）
--
-- 背景：SongAuditTaskService 原来靠「先查后插」去重，两个并发提交（或事务提交后
--       连点两次）都能查到「没有未完成任务」，于是各插一条，审核台出现重复任务。
--       应用层去重只能降低概率，最终一致性必须由数据库唯一约束兜底。
--
-- 做法：新增 STORED 生成列 open_key：
--         status IN (0,1)（待领/已领）→ "task_type:target_id"
--         其余状态                     → NULL
--       再对 open_key 建唯一索引。MySQL 唯一索引允许多个 NULL，
--       所以已完成/已关闭的任务（无论多少条历史记录）都不受影响。
--
-- 安全约束（本机 3306 是共享实例，另有 emp_db/music_db）：
--   * 所有语句显式带 `newmusic.` 库名，不触碰其它库；
--   * 只有一条 UPDATE，且 WHERE status IN (0,1) + JOIN 精确 id，只关闭重复任务；
--   * 不删任何行、不清表、不做库级 DDL/DML；
--   * 可重复执行：去重 UPDATE 在索引建好后自然匹配 0 行，加列/加索引均先查
--     information_schema。
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 0. 预检（只读，方便执行前后对照）
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS open_tasks,
       (SELECT COUNT(*) FROM (
          SELECT 1
          FROM `newmusic`.`t_audit_task`
          WHERE `status` IN (0, 1)
          GROUP BY `task_type`, `target_id`
          HAVING COUNT(*) > 1
        ) d) AS duplicate_groups
FROM `newmusic`.`t_audit_task`
WHERE `status` IN (0, 1);

-- -----------------------------------------------------------------------------
-- 1. 去重：每组 (task_type, target_id) 的未完成任务只保留 id 最大的一条，
--    其余置为「4 自动关闭」而不是物理删除——审核轨迹要留痕。
--
--    派生表包一层是 MySQL 的限制：不能在 UPDATE 的子查询里直接读同一张目标表。
-- -----------------------------------------------------------------------------
UPDATE `newmusic`.`t_audit_task` t
JOIN (
  SELECT id
  FROM (
    SELECT `id`,
           ROW_NUMBER() OVER (PARTITION BY `task_type`, `target_id` ORDER BY `id` DESC) AS rn
    FROM `newmusic`.`t_audit_task`
    WHERE `status` IN (0, 1)
  ) ranked
  WHERE ranked.rn > 1
) dup ON dup.id = t.id
SET t.`status`         = 4,
    t.`handled_at`     = COALESCE(t.`handled_at`, NOW(3)),
    t.`handle_result`  = COALESCE(t.`handle_result`, 'DUP_MERGE'),
    t.`reject_reason`  = COALESCE(t.`reject_reason`, '重复任务迁移自动关闭'),
    t.`action_applied` = 0,
    t.`update_time`    = NOW(3)
WHERE t.`status` IN (0, 1);

-- -----------------------------------------------------------------------------
-- 2. 生成列 open_key（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
--    表达式必须与 sql/schema.sql 完全一致，否则新库与旧库的指纹不同。
-- -----------------------------------------------------------------------------
SET @add_open_key := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_audit_task` ADD COLUMN `open_key` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `status` IN (0, 1) THEN CONCAT(`task_type`, CHAR(58), `target_id`) ELSE NULL END) STORED COMMENT ''未完成任务唯一键；已完成/已关闭为 NULL''',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_audit_task' AND COLUMN_NAME = 'open_key'
);
PREPARE stmt FROM @add_open_key; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3. 唯一索引：并发插入的第二条会得到 DuplicateKeyException，
--    SongAuditTaskService 已把它当作「任务已存在」处理（记日志、返回 false），
--    不会冒泡成 500。
-- -----------------------------------------------------------------------------
SET @add_open_uk := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `newmusic`.`t_audit_task` ADD UNIQUE KEY `uk_audit_open_target` (`open_key`)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_audit_task' AND INDEX_NAME = 'uk_audit_open_target'
);
PREPARE stmt FROM @add_open_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 4. 校验：唯一索引存在，且未完成任务不再有重复组
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = 'newmusic' AND TABLE_NAME = 't_audit_task'
          AND INDEX_NAME = 'uk_audit_open_target') AS index_columns,
       (SELECT COUNT(*) FROM (
          SELECT 1
          FROM `newmusic`.`t_audit_task`
          WHERE `status` IN (0, 1)
          GROUP BY `task_type`, `target_id`
          HAVING COUNT(*) > 1
        ) d) AS duplicate_groups_after;
