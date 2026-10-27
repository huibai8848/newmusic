-- =============================================================================
-- 模块 06（搜索）：存量数据库的迁移脚本
--
-- 为什么需要这个文件：`sql/schema.sql` 用的是 `CREATE TABLE IF NOT EXISTS`，
-- 对**已存在**的 `t_song`/`t_artist`/`t_album` 再跑一遍不会补上新增的拼音列。
-- 而 MySQL 8 **不支持** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的语法），
-- 所以这里用 information_schema 判存 + 动态 SQL 来做幂等。
--
-- 范围：只动 `newmusic` 库的表，绝不触碰同实例上的其它库（emp_db / music_db）。
-- 幂等：可重复执行，已存在的列会被跳过。
--
-- 用法：mysql -uroot -p < sql/06-search-seed.sql
--
-- 注意：`t_search_history` / `t_search_hot` 两张新表**不在这里建** ——
-- `CREATE TABLE IF NOT EXISTS` 对存量库本来就有效，直接重跑 schema.sql 即可，
-- 在此重复一份 DDL 只会制造改一处漏一处的漂移风险。
-- 也就是说完整流程是：先 `mysql < sql/schema.sql`（建新表 + 建库），再执行本文件（补列）。
-- =============================================================================

-- 本文件含中文（注释里的列注释、以及 CALL 参数里的 COMMENT 文本）。
-- Windows 控制台的 mysql 客户端默认按 GBK 解释文件字节，会把中文当成非法序列
-- 直接报 1366。开头显式 SET NAMES，就不必依赖调用方记得加 --default-character-set。
SET NAMES utf8mb4;

USE `newmusic`;

DROP PROCEDURE IF EXISTS `ml_add_column_if_absent`;
DELIMITER $$
CREATE PROCEDURE `ml_add_column_if_absent`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ml_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_definition);
        PREPARE ml_stmt FROM @ml_ddl;
        EXECUTE ml_stmt;
        DEALLOCATE PREPARE ml_stmt;
    END IF;
END$$
DELIMITER ;

-- -----------------------------------------------------------------------------
-- 拼音列（系分 06 D-35）
--
-- 这些列是**派生冗余数据**，由应用层在写入时用 pinyin4j 生成。
-- 存量数据由 `PinyinBackfill` 在应用启动时补齐（只补 NULL 行，且显式抑制
-- `ON UPDATE CURRENT_TIMESTAMP`，避免扰动模块 03 的缓存语义与将来 ES 的外部版本号）。
-- -----------------------------------------------------------------------------
CALL ml_add_column_if_absent('t_song', 'name_pinyin',
    '`name_pinyin` VARCHAR(200) DEFAULT NULL COMMENT ''歌曲名全拼(小写,无分隔) [06]'' AFTER `name`');
CALL ml_add_column_if_absent('t_song', 'name_pinyin_first',
    '`name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT ''歌曲名拼音首字母(小写) [06]'' AFTER `name_pinyin`');
CALL ml_add_column_if_absent('t_song', 'artist_name_pinyin',
    '`artist_name_pinyin` VARCHAR(200) DEFAULT NULL COMMENT ''歌手名全拼(冗余) [06]'' AFTER `artist_name`');
CALL ml_add_column_if_absent('t_song', 'artist_name_pinyin_first',
    '`artist_name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT ''歌手名首字母(冗余) [06]'' AFTER `artist_name_pinyin`');

CALL ml_add_column_if_absent('t_artist', 'name_pinyin',
    '`name_pinyin` VARCHAR(200) DEFAULT NULL COMMENT ''歌手名全拼(小写,无分隔) [06]'' AFTER `name`');
CALL ml_add_column_if_absent('t_artist', 'name_pinyin_first',
    '`name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT ''歌手名拼音首字母(小写) [06]'' AFTER `name_pinyin`');

CALL ml_add_column_if_absent('t_album', 'name_pinyin',
    '`name_pinyin` VARCHAR(200) DEFAULT NULL COMMENT ''专辑名全拼(小写,无分隔) [06]'' AFTER `name`');
CALL ml_add_column_if_absent('t_album', 'name_pinyin_first',
    '`name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT ''专辑名拼音首字母(小写) [06]'' AFTER `name_pinyin`');

DROP PROCEDURE IF EXISTS `ml_add_column_if_absent`;

-- -----------------------------------------------------------------------------
-- 运营配置的占位（不插数据，只说明该往哪写）
--
-- 敏感词词典：`t_sys_config` 里 `config_key = 'search.sensitive.words'`，
-- `config_value` 为逗号分隔的词表。**不预置该行**，因为预置一个空值行会让
-- 「DB 覆盖 YAML」的判定变得含糊；约定是「DB 里存在且非空白才覆盖 YAML」。
-- 真正的词库属模块 18（内容安全），当前默认空 = 不拦任何词（系分 06 D-38）。
--
--   示例（有词库后再执行）：
--   INSERT INTO t_sys_config (id, config_key, config_value, value_type, config_group,
--                             description, is_public, status)
--   VALUES (1000000000000012901, 'search.sensitive.words', '违禁词A,违禁词B', 1, 'search',
--           '搜索敏感词词典，逗号分隔', 0, 1)
--   ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
--
-- 热搜置顶词：往 `t_search_hot` 写 `is_top = 1` 的行（当天日期），最多 3 个生效
-- （BR-15-030）。注意 `stat_date` 要写当天，示例：
--   INSERT INTO t_search_hot (id, keyword, search_count, rank_no, is_top, stat_date)
--   VALUES (1000000000000013901, '华语经典', 0, 0, 1, CURDATE())
--   ON DUPLICATE KEY UPDATE is_top = 1;
-- -----------------------------------------------------------------------------
