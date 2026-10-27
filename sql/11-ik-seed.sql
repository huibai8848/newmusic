-- =============================================================================
-- 模块 11 IK 分词与中文搜索优化：既有库升级脚本（幂等，可重复执行）
--
-- 适用场景：数据库已初始化过模块 01~10，补 6 张词典表、后台权限与演示词库。
-- 全新库直接执行 schema.sql + data.sql 即可（schema.sql / data.sql 已含本节全部内容）。
--
-- 安全约束：
--   * 所有对象显式带 `newmusic.` 库名；本机 3306 是共享实例（还有 emp_db/music_db）
--   * 只做 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，不清表、不删数据
--   * 本脚本可重复执行：已存在的词条与授权不会被覆盖
-- =============================================================================

USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 建表（与 schema.sql 第 27 节一致）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `newmusic`.`t_ik_dict_word` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(64)     NOT NULL COMMENT '词条',
  `frequency`   INT             NOT NULL DEFAULT 100 COMMENT '词频，越高优先级越高',
  `pos`         VARCHAR(8)      NOT NULL DEFAULT 'n' COMMENT '词性',
  `source`      VARCHAR(20)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTO_ARTIST/AUTO_SONG',
  `ref_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '自动同步关联对象 ID',
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `dict_type`   VARCHAR(20)     NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN/NET_SLANG/REGION',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ik_dict_word` (`word`),
  KEY `idx_ik_word_source` (`source`),
  KEY `idx_ik_word_type` (`dict_type`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IK 主词典词条';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_ik_stopword` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(64)     NOT NULL,
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ik_stopword` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IK 停用词（只用于查询分析）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_synonym` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `group_no`      INT             NOT NULL COMMENT '组号，同组共享',
  `word`          VARCHAR(64)     NOT NULL,
  `is_primary`    TINYINT         NOT NULL DEFAULT 0,
  `synonym_type`  VARCHAR(20)     NOT NULL DEFAULT 'EQUIVALENT' COMMENT 'EQUIVALENT/ONE_WAY',
  `category`      VARCHAR(32)     NOT NULL DEFAULT 'OTHER' COMMENT 'ARTIST/GENRE/LANGUAGE/ERA/SCENE/EMOTION/OTHER',
  `enabled`       TINYINT         NOT NULL DEFAULT 1,
  `sort_order`    INT             NOT NULL DEFAULT 0,
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_synonym_group_word` (`group_no`, `word`),
  KEY `idx_synonym_word` (`word`),
  KEY `idx_synonym_group` (`group_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同义词组（一行一个词）';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_pinyin_dict` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(64)     NOT NULL COMMENT '多音字词',
  `pinyin`      VARCHAR(64)     NOT NULL COMMENT '无声调小写拼音，如 chongqing',
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pinyin_dict_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拼音多音字词典';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_dict_change_log` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `dict_type`     VARCHAR(20)     NOT NULL COMMENT 'MAIN/STOPWORD/SYNONYM/PINYIN',
  `change_type`   VARCHAR(20)     NOT NULL COMMENT 'CREATE/UPDATE/DELETE/IMPORT/SYNC/RELOAD/REINDEX',
  `target_id`     BIGINT UNSIGNED DEFAULT NULL,
  `before_value`  TEXT            DEFAULT NULL,
  `after_value`   TEXT            DEFAULT NULL,
  `operator_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '后台管理员 ID',
  `operator_name` VARCHAR(64)     DEFAULT NULL,
  `remark`        VARCHAR(500)    DEFAULT NULL,
  `result`        VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',
  `create_time`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_dict_log_type` (`dict_type`, `create_time`),
  KEY `idx_dict_log_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='词典变更历史';

CREATE TABLE IF NOT EXISTS `newmusic`.`t_dict_sync_log` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `sync_type`   VARCHAR(20)     NOT NULL COMMENT 'ES_PULL/RELOAD/REINDEX/ARTIST_SYNC',
  `status`      VARCHAR(20)     NOT NULL COMMENT 'SUCCESS/FAIL/SKIPPED',
  `item_count`  INT             NOT NULL DEFAULT 0,
  `cost_ms`     BIGINT          NOT NULL DEFAULT 0,
  `message`     VARCHAR(500)    DEFAULT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_dict_sync_type` (`sync_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='词典同步/重载日志';

-- -----------------------------------------------------------------------------
-- 2. 后台权限（模块 11）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_admin_permission`
  (`id`, `perm_code`, `perm_name`, `perm_group`, `sensitive`, `sort_order`, `status`) VALUES
  (9095, 'admin:dict:view',    '词典查看/分词测试', '搜索词典', 0, 720, 1),
  (9096, 'admin:dict:edit',    '词典编辑/导入',     '搜索词典', 0, 721, 1),
  (9097, 'admin:dict:reindex', '重载分析器/重建索引', '搜索词典', 1, 722, 1);

-- 超管：全量（既有的 9095~9097 也补进 93xx 段，避免只依赖 *:*:*）
INSERT IGNORE INTO `newmusic`.`t_admin_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  (9395, 1, 9095),
  (9396, 1, 9096),
  (9397, 1, 9097),
  (9485, 2, 9095),
  (9486, 2, 9096),
  (9487, 2, 9097),
  (9585, 3, 9095),
  (9586, 3, 9096);

-- -----------------------------------------------------------------------------
-- 3. 主词典：业务词
--    自动词条使用 `2000000000000000000 + 源表 id` 作为固定 ID，
--    重复执行不会生成新行（word 唯一键兜底）。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_ik_dict_word`
  (`id`, `word`, `frequency`, `pos`, `source`, `ref_id`, `enabled`, `dict_type`) VALUES
  (1100000000000000001, '周杰伦',   100, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000002, '林俊杰',   100, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000003, '陈奕迅',   100, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000004, '邓紫棋',   100, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000005, '五月天',   100, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000006, '稻香',      80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000007, '青花瓷',    80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000008, '七里香',    80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000009, '晴天',      80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000010, '说唱',      90, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000011, '电音',      90, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000012, '民谣',      90, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000013, '摇滚',      90, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000014, '古风',      90, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000015, 'City Pop',  50, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000016, '音乐房',    60, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000017, '歌单',      60, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000018, '收藏夹',    60, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000019, '私人电台',  50, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000020, '绝绝子',    30, 'n', 'MANUAL', NULL, 1, 'NET_SLANG'),
  (1100000000000000021, 'yyds',      30, 'n', 'MANUAL', NULL, 1, 'NET_SLANG'),
  (1100000000000000022, '破防了',    30, 'n', 'MANUAL', NULL, 1, 'NET_SLANG'),
  (1100000000000000023, '社会摇',    30, 'n', 'MANUAL', NULL, 1, 'NET_SLANG'),
  (1100000000000000024, '华语流行',  80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000025, '粤语',      80, 'n', 'MANUAL', NULL, 1, 'MAIN'),
  (1100000000000000026, '纯音乐',    80, 'n', 'MANUAL', NULL, 1, 'MAIN');

-- 歌手名自动同步（只同步未删除的歌手；冲突时保留人工词条）
INSERT IGNORE INTO `newmusic`.`t_ik_dict_word`
  (`id`, `word`, `frequency`, `pos`, `source`, `ref_id`, `enabled`, `dict_type`)
SELECT 2000000000000000000 + a.id, a.name, 100, 'n', 'AUTO_ARTIST', a.id, 1, 'MAIN'
FROM `newmusic`.`t_artist` a
WHERE a.deleted = 0 AND a.name IS NOT NULL AND CHAR_LENGTH(a.name) BETWEEN 2 AND 15;

-- 歌曲名自动同步（数据量上线前需评估 10 万上限，超过时只同步人工词）
-- 注意 ID 偏移用 3e18，不能与歌手的 2e18 复用：t_artist.id 与 t_song.id 会重叠，
-- 复用同一个偏移会让两张表的自动词条在主键上互相覆盖。
INSERT IGNORE INTO `newmusic`.`t_ik_dict_word`
  (`id`, `word`, `frequency`, `pos`, `source`, `ref_id`, `enabled`, `dict_type`)
SELECT 3000000000000000000 + s.id, s.name, 80, 'n', 'AUTO_SONG', s.id, 1, 'MAIN'
FROM `newmusic`.`t_song` s
WHERE s.deleted = 0 AND s.name IS NOT NULL AND CHAR_LENGTH(s.name) BETWEEN 2 AND 15;

-- -----------------------------------------------------------------------------
-- 4. 停用词（仅查询分析使用；年份/年代刻意不收录）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_ik_stopword` (`id`, `word`, `enabled`) VALUES
  (1300000000000000001, '的', 1),
  (1300000000000000002, '了', 1),
  (1300000000000000003, '和', 1),
  (1300000000000000004, '与', 1),
  (1300000000000000005, '及', 1),
  (1300000000000000006, '或', 1),
  (1300000000000000007, '在', 1),
  (1300000000000000008, '是', 1),
  (1300000000000000009, '就', 1),
  (1300000000000000010, '也', 1),
  (1300000000000000011, '都', 1),
  (1300000000000000012, '我想听', 1),
  (1300000000000000013, '帮我找', 1),
  (1300000000000000014, '有没有', 1),
  (1300000000000000015, '请', 1),
  (1300000000000000016, '来个', 1),
  (1300000000000000017, '推荐', 1),
  (1300000000000000018, '歌', 1),
  (1300000000000000019, '歌曲', 1),
  (1300000000000000020, '音乐', 1),
  (1300000000000000021, '一首', 1),
  (1300000000000000022, 'a', 1),
  (1300000000000000023, 'an', 1),
  (1300000000000000024, 'the', 1),
  (1300000000000000025, 'of', 1),
  (1300000000000000026, 'and', 1),
  (1300000000000000027, 'or', 1),
  (1300000000000000028, 'to', 1),
  (1300000000000000029, 'in', 1);

-- -----------------------------------------------------------------------------
-- 5. 同义词（查询时等价扩展；一行一个词）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_synonym`
  (`id`, `group_no`, `word`, `is_primary`, `synonym_type`, `category`, `enabled`, `sort_order`) VALUES
  (1200000000000000101, 101, '周杰伦', 1, 'EQUIVALENT', 'ARTIST',   1, 0),
  (1200000000000000102, 101, '周董',   0, 'EQUIVALENT', 'ARTIST',   1, 1),
  (1200000000000000103, 101, '杰伦',   0, 'EQUIVALENT', 'ARTIST',   1, 2),
  (1200000000000000104, 101, 'Jay',    0, 'EQUIVALENT', 'ARTIST',   1, 3),
  (1200000000000000201, 102, '陈奕迅', 1, 'EQUIVALENT', 'ARTIST',   1, 0),
  (1200000000000000202, 102, 'Eason',  0, 'EQUIVALENT', 'ARTIST',   1, 1),
  (1200000000000000203, 102, '医生',   0, 'EQUIVALENT', 'ARTIST',   1, 2),
  (1200000000000000301, 103, '林俊杰', 1, 'EQUIVALENT', 'ARTIST',   1, 0),
  (1200000000000000302, 103, 'JJ',     0, 'EQUIVALENT', 'ARTIST',   1, 1),
  (1200000000000000401, 104, '五月天', 1, 'EQUIVALENT', 'ARTIST',   1, 0),
  (1200000000000000402, 104, 'Mayday', 0, 'EQUIVALENT', 'ARTIST',   1, 1),
  (1200000000000000501, 105, '邓紫棋', 1, 'EQUIVALENT', 'ARTIST',   1, 0),
  (1200000000000000502, 105, 'GEM',    0, 'EQUIVALENT', 'ARTIST',   1, 1),
  (1200000000000000601, 106, '说唱',   1, 'EQUIVALENT', 'GENRE',    1, 0),
  (1200000000000000602, 106, 'rap',    0, 'EQUIVALENT', 'GENRE',    1, 1),
  (1200000000000000603, 106, '嘻哈',   0, 'EQUIVALENT', 'GENRE',    1, 2),
  (1200000000000000604, 106, 'hiphop', 0, 'EQUIVALENT', 'GENRE',    1, 3),
  (1200000000000000605, 106, 'hip-hop',0, 'EQUIVALENT', 'GENRE',    1, 4),
  (1200000000000000701, 107, '电子',   1, 'EQUIVALENT', 'GENRE',    1, 0),
  (1200000000000000702, 107, 'EDM',    0, 'EQUIVALENT', 'GENRE',    1, 1),
  (1200000000000000703, 107, '电音',   0, 'EQUIVALENT', 'GENRE',    1, 2),
  (1200000000000000801, 108, '民谣',   1, 'EQUIVALENT', 'GENRE',    1, 0),
  (1200000000000000802, 108, 'folk',   0, 'EQUIVALENT', 'GENRE',    1, 1),
  (1200000000000000901, 109, '摇滚',   1, 'EQUIVALENT', 'GENRE',    1, 0),
  (1200000000000000902, 109, 'rock',   0, 'EQUIVALENT', 'GENRE',    1, 1),
  (1200000000000000903, 109, '摇滚乐', 0, 'EQUIVALENT', 'GENRE',    1, 2),
  (1200000000000001001, 110, '国语',   1, 'EQUIVALENT', 'LANGUAGE', 1, 0),
  (1200000000000001002, 110, '华语',   0, 'EQUIVALENT', 'LANGUAGE', 1, 1),
  (1200000000000001003, 110, '中文',   0, 'EQUIVALENT', 'LANGUAGE', 1, 2),
  (1200000000000001004, 110, '普通话', 0, 'EQUIVALENT', 'LANGUAGE', 1, 3),
  (1200000000000001101, 111, '粤语',   1, 'EQUIVALENT', 'LANGUAGE', 1, 0),
  (1200000000000001102, 111, '广东话', 0, 'EQUIVALENT', 'LANGUAGE', 1, 1),
  (1200000000000001103, 111, 'Cantonese', 0, 'EQUIVALENT', 'LANGUAGE', 1, 2),
  (1200000000000001201, 112, '纯音乐', 1, 'EQUIVALENT', 'LANGUAGE', 1, 0),
  (1200000000000001202, 112, '轻音乐', 0, 'EQUIVALENT', 'LANGUAGE', 1, 1),
  (1200000000000001203, 112, '器乐',   0, 'EQUIVALENT', 'LANGUAGE', 1, 2),
  (1200000000000001204, 112, '无人声', 0, 'EQUIVALENT', 'LANGUAGE', 1, 3),
  (1200000000000001301, 113, '80年代',  1, 'EQUIVALENT', 'ERA',     1, 0),
  (1200000000000001302, 113, '80s',     0, 'EQUIVALENT', 'ERA',     1, 1),
  (1200000000000001303, 113, '八十年代', 0, 'EQUIVALENT', 'ERA',     1, 2),
  (1200000000000001401, 114, '运动',   1, 'EQUIVALENT', 'SCENE',   1, 0),
  (1200000000000001402, 114, '健身',   0, 'EQUIVALENT', 'SCENE',   1, 1),
  (1200000000000001403, 114, '跑步',   0, 'EQUIVALENT', 'SCENE',   1, 2),
  (1200000000000001501, 115, '睡眠',   1, 'EQUIVALENT', 'SCENE',   1, 0),
  (1200000000000001502, 115, '睡前',   0, 'EQUIVALENT', 'SCENE',   1, 1),
  (1200000000000001503, 115, '助眠',   0, 'EQUIVALENT', 'SCENE',   1, 2),
  (1200000000000001601, 116, '伤感',   1, 'EQUIVALENT', 'EMOTION', 1, 0),
  (1200000000000001602, 116, '悲伤',   0, 'EQUIVALENT', 'EMOTION', 1, 1),
  (1200000000000001603, 116, '难过',   0, 'EQUIVALENT', 'EMOTION', 1, 2),
  (1200000000000001701, 117, '治愈',   1, 'EQUIVALENT', 'EMOTION', 1, 0),
  (1200000000000001702, 117, '暖心',   0, 'EQUIVALENT', 'EMOTION', 1, 1),
  (1200000000000001801, 118, '神曲',   1, 'EQUIVALENT', 'OTHER',   1, 0),
  (1200000000000001802, 118, '洗脑歌', 0, 'EQUIVALENT', 'OTHER',   1, 1),
  (1200000000000001901, 119, '冷门',   1, 'EQUIVALENT', 'OTHER',   1, 0),
  (1200000000000001902, 119, '小众',   0, 'EQUIVALENT', 'OTHER',   1, 1),
  (1200000000000001903, 119, '宝藏',   0, 'EQUIVALENT', 'OTHER',   1, 2);

-- -----------------------------------------------------------------------------
-- 6. 拼音多音字词典
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `newmusic`.`t_pinyin_dict` (`id`, `word`, `pinyin`, `enabled`) VALUES
  (1400000000000000001, '重庆', 'chongqing', 1),
  (1400000000000000002, '音乐', 'yinyue',    1),
  (1400000000000000003, '乐器', 'yueqi',     1),
  (1400000000000000004, '曾毅', 'zengyi',    1),
  (1400000000000000005, '曾经', 'cengjing',  1),
  (1400000000000000006, '银行', 'yinhang',   1),
  (1400000000000000007, '行走', 'xingzou',   1);
