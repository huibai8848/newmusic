-- =============================================================================
-- MusicLive 在线音乐社交平台 — 建表脚本
-- 对应系分：docs/系分/01-数据库CRUD.md（模块 01）
-- 幂等：全部使用 CREATE TABLE IF NOT EXISTS，可重复执行
-- 规范：InnoDB / utf8mb4 / 无物理外键 / 逻辑删除 deleted / 乐观锁 version
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `newmusic`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `newmusic`;

-- -----------------------------------------------------------------------------
-- 1. 用户域
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user` (
  `id`              BIGINT UNSIGNED NOT NULL COMMENT '用户ID(雪花)',
  `username`        VARCHAR(50)     NOT NULL COMMENT '用户名(唯一,登录用)',
  `phone`           VARCHAR(100)    DEFAULT NULL COMMENT '手机号(加密存储,AES)',
  `phone_hash`      CHAR(64)        DEFAULT NULL COMMENT '手机号SHA256(唯一查询用)',
  `email`           VARCHAR(100)    DEFAULT NULL COMMENT '邮箱',
  `password`        VARCHAR(100)    NOT NULL COMMENT '密码(BCrypt,strength=10)',
  `force_change_password` TINYINT     NOT NULL DEFAULT 0 COMMENT '强制下次登录修改密码（模块09管理员重置后置1）',
  `nickname`        VARCHAR(50)     NOT NULL COMMENT '昵称',
  `avatar`          VARCHAR(500)    DEFAULT NULL COMMENT '头像URL',
  `gender`          TINYINT         NOT NULL DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `birthday`        DATE            DEFAULT NULL COMMENT '生日',
  `signature`       VARCHAR(200)    DEFAULT NULL COMMENT '个性签名',
  `user_type`       TINYINT         NOT NULL DEFAULT 1 COMMENT '用户类型 1普通 2运营 3管理员 4超管',
  `status`          TINYINT         NOT NULL DEFAULT 1 COMMENT '状态 0待验证 1正常 2封禁 3注销中 4已注销',
  `ban_reason`      VARCHAR(200)    DEFAULT NULL COMMENT '封禁原因',
  `ban_until`       DATETIME(3)     DEFAULT NULL COMMENT '封禁到期时间(NULL=永久)',
  `register_ip`     VARCHAR(45)     DEFAULT NULL COMMENT '注册IP',
  `last_login_time` DATETIME(3)     DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip`   VARCHAR(45)     DEFAULT NULL COMMENT '最后登录IP',
  `vip_level`       TINYINT         NOT NULL DEFAULT 0 COMMENT '会员等级 0非会员(预留)',
  `vip_expire_time` DATETIME(3)     DEFAULT NULL COMMENT '会员到期时间(预留)',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`       BIGINT UNSIGNED DEFAULT NULL,
  `update_by`       BIGINT UNSIGNED DEFAULT NULL,
  `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
  `version`         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_phone_hash` (`phone_hash`),
  KEY `idx_user_status` (`status`, `deleted`),
  KEY `idx_user_nickname` (`nickname`),
  KEY `idx_user_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `t_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `role_code`   VARCHAR(50)  NOT NULL COMMENT '角色编码 ROLE_ADMIN',
  `role_name`   VARCHAR(50)  NOT NULL COMMENT '角色名称',
  `description` VARCHAR(200) DEFAULT NULL,
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本(D-02：字典表同样继承 BaseEntity)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `t_permission` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `perm_code`   VARCHAR(100) NOT NULL COMMENT '权限码 music:song:create',
  `perm_name`   VARCHAR(100) NOT NULL,
  `perm_type`   TINYINT      NOT NULL DEFAULT 3 COMMENT '1菜单 2按钮 3接口',
  `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父权限ID',
  `path`        VARCHAR(200) DEFAULT NULL COMMENT '前端路由/接口路径',
  `icon`        VARCHAR(100) DEFAULT NULL COMMENT '菜单图标',
  `sort_order`  INT          NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本(D-02)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`),
  KEY `idx_perm_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `t_user_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表（物理删除）';

CREATE TABLE IF NOT EXISTS `t_role_permission` (
  `id`      BIGINT UNSIGNED NOT NULL,
  `role_id` BIGINT UNSIGNED NOT NULL,
  `perm_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联表（物理删除）';

-- -----------------------------------------------------------------------------
-- 2. 曲库域
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_artist` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `name`           VARCHAR(100) NOT NULL COMMENT '歌手名',
  -- 模块 06：拼音检索支持。本表原有 `first_letter`（首字母）就是这个思路的先例，
  -- 这里把它补全：全拼 + 首字母串，均由应用层用 pinyin4j 写时物化（系分 06 D-35）。
  `name_pinyin`       VARCHAR(200) DEFAULT NULL COMMENT '歌手名全拼(小写,无分隔) [06]',
  `name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT '歌手名拼音首字母(小写) [06]',
  `alias`          VARCHAR(200) DEFAULT NULL COMMENT '别名/英文名',
  `avatar`         VARCHAR(500) DEFAULT NULL COMMENT '头像',
  `cover`          VARCHAR(500) DEFAULT NULL COMMENT '封面大图',
  `gender`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女 3组合',
  `country`        VARCHAR(50)  DEFAULT NULL COMMENT '国家/地区',
  `birthday`       DATE         DEFAULT NULL,
  `debut_date`     DATE         DEFAULT NULL COMMENT '出道日期',
  `description`    TEXT         COMMENT '简介',
  `styles`         VARCHAR(200) DEFAULT NULL COMMENT '风格标签,逗号分隔',
  `song_count`     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '歌曲数(冗余)',
  `album_count`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '专辑数(冗余)',
  `follower_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '关注数(冗余)',
  `hot_score`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '热度分',
  `first_letter`   CHAR(1)      DEFAULT NULL COMMENT '首字母(拼音)',
  `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT      NOT NULL DEFAULT 0,
  `version`        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_artist_name` (`name`),
  KEY `idx_artist_letter` (`first_letter`, `status`),
  KEY `idx_artist_hot` (`hot_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌手表';

CREATE TABLE IF NOT EXISTS `t_album` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `name`         VARCHAR(200) NOT NULL COMMENT '专辑名',
  `name_pinyin`       VARCHAR(200) DEFAULT NULL COMMENT '专辑名全拼(小写,无分隔) [06]',
  `name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT '专辑名拼音首字母(小写) [06]',
  `artist_id`    BIGINT UNSIGNED NOT NULL COMMENT '主歌手ID',
  `artist_name`  VARCHAR(100) DEFAULT NULL COMMENT '歌手名(冗余)',
  `cover`        VARCHAR(500) DEFAULT NULL COMMENT '封面',
  `publish_date` DATE         DEFAULT NULL COMMENT '发行日期',
  `company`      VARCHAR(200) DEFAULT NULL COMMENT '发行公司',
  `description`  TEXT         COMMENT '专辑简介',
  `language`     VARCHAR(20)  DEFAULT NULL COMMENT '语种',
  `song_count`   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '歌曲数(冗余)',
  `like_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数[10]',
  `collect_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏数[10]',
  `comment_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数[10]',
  `share_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '分享数[10]',
  `hot_score`    INT UNSIGNED NOT NULL DEFAULT 0,
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`    BIGINT UNSIGNED DEFAULT NULL,
  `update_by`    BIGINT UNSIGNED DEFAULT NULL,
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  `version`      INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_album_artist` (`artist_id`),
  KEY `idx_album_name` (`name`),
  KEY `idx_album_publish` (`publish_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专辑表';

CREATE TABLE IF NOT EXISTS `t_song` (
  `id`                  BIGINT UNSIGNED NOT NULL,
  `name`                VARCHAR(200) NOT NULL COMMENT '歌曲名',
  -- 模块 06：拼音检索列，写时物化（系分 06 D-35）。PRD 用的是 ES analysis-pinyin
  -- 插件，本机无 ES，故落到列上；PRD BR-15-014 规定含中文的输入不查这几列。
  `name_pinyin`         VARCHAR(200) DEFAULT NULL COMMENT '歌曲名全拼(小写,无分隔) [06]',
  `name_pinyin_first`   VARCHAR(100) DEFAULT NULL COMMENT '歌曲名拼音首字母(小写) [06]',
  `subtitle`            VARCHAR(200) DEFAULT NULL COMMENT '副标题',
  `artist_id`           BIGINT UNSIGNED NOT NULL COMMENT '主歌手ID',
  `artist_name`         VARCHAR(100) NOT NULL COMMENT '歌手名(冗余,搜索用)',
  `artist_name_pinyin`       VARCHAR(200) DEFAULT NULL COMMENT '歌手名全拼(冗余) [06]',
  `artist_name_pinyin_first` VARCHAR(100) DEFAULT NULL COMMENT '歌手名首字母(冗余) [06]',
  `feat_artists`        VARCHAR(500) DEFAULT NULL COMMENT '合作歌手,逗号分隔ID',
  `feat_names`          VARCHAR(500) DEFAULT NULL COMMENT '合作歌手名(冗余)',
  `album_id`            BIGINT UNSIGNED DEFAULT NULL COMMENT '专辑ID',
  `album_name`          VARCHAR(200) DEFAULT NULL COMMENT '专辑名(冗余)',
  `cover`               VARCHAR(500) DEFAULT NULL COMMENT '封面',
  `language`            VARCHAR(20)  DEFAULT NULL COMMENT '语种',
  `genre`               VARCHAR(50)  DEFAULT NULL COMMENT '流派',
  `duration`            INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '时长(秒)',
  `bpm`                 SMALLINT UNSIGNED DEFAULT NULL COMMENT 'BPM',
  `release_date`        DATE         DEFAULT NULL COMMENT '发行日期',
  `era`                 VARCHAR(20)  DEFAULT NULL COMMENT '年代',
  `isrc`                VARCHAR(50)  DEFAULT NULL COMMENT 'ISRC',
  `audio_standard`      VARCHAR(500) DEFAULT NULL COMMENT '标准音质URL(128kbps mp3)',
  `audio_high`          VARCHAR(500) DEFAULT NULL COMMENT '高清音质URL(320kbps mp3)',
  `audio_lossless`      VARCHAR(500) DEFAULT NULL COMMENT '无损音质URL(flac)',
  `file_size_standard`  BIGINT UNSIGNED DEFAULT NULL,
  `file_size_high`      BIGINT UNSIGNED DEFAULT NULL,
  `file_size_lossless`  BIGINT UNSIGNED DEFAULT NULL,
  `lyric_id`            BIGINT UNSIGNED DEFAULT NULL COMMENT '歌词ID',
  `has_translation`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否有翻译歌词',
  `copyright_status`    TINYINT      NOT NULL DEFAULT 0 COMMENT '版权 0未授权 1已授权 2已过期 3受限',
  `copyright_owner`     VARCHAR(200) DEFAULT NULL COMMENT '版权方',
  `copyright_expire`    DATE         DEFAULT NULL COMMENT '版权到期日',
  `play_count`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '播放量',
  `like_count`          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数',
  `collect_count`       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏数',
  `comment_count`       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数',
  `share_count`         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '分享数',
  `download_count`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '下载数',
  `hot_score`           INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '热度分',
  `completion_rate`     DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '完播率',
  `audit_status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '审核 0待审 1通过 2拒绝',
  `status`              TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0下架 1上架',
  `publish_time`        DATETIME(3)  DEFAULT NULL COMMENT '上架时间',
  `sort_weight`         INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  `source`              TINYINT      NOT NULL DEFAULT 1 COMMENT '来源 1管理员录入 2第三方 3用户投稿',
  `create_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`           BIGINT UNSIGNED DEFAULT NULL,
  `update_by`           BIGINT UNSIGNED DEFAULT NULL,
  `deleted`             TINYINT      NOT NULL DEFAULT 0,
  `version`             INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_song_name` (`name`),
  KEY `idx_song_artist` (`artist_id`),
  KEY `idx_song_album` (`album_id`),
  KEY `idx_song_status_hot` (`status`, `audit_status`, `hot_score`),
  KEY `idx_song_publish` (`publish_time`),
  KEY `idx_song_genre_lang` (`genre`, `language`),
  UNIQUE KEY `uk_song_isrc` (`isrc`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌曲表';

CREATE TABLE IF NOT EXISTS `t_lyric` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `song_id`        BIGINT UNSIGNED NOT NULL COMMENT '歌曲ID',
  `content`        LONGTEXT     NOT NULL COMMENT 'LRC原文(含时间轴)',
  `content_type`   TINYINT      NOT NULL DEFAULT 1 COMMENT '1标准LRC 2逐字LRC 3纯文本',
  `translation`    LONGTEXT     DEFAULT NULL COMMENT '翻译歌词',
  `trans_lang`     VARCHAR(20)  DEFAULT NULL COMMENT '翻译语言',
  `romaji`         LONGTEXT     DEFAULT NULL COMMENT '罗马音/音译',
  `offset_ms`      INT          NOT NULL DEFAULT 0 COMMENT '歌词整体偏移(毫秒)',
  `summary`        VARCHAR(500) DEFAULT NULL COMMENT 'AI歌词摘要',
  `emotion`        VARCHAR(50)  DEFAULT NULL COMMENT 'AI情绪标注',
  `contributor_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '贡献者',
  `audit_status`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2拒绝',
  `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT      NOT NULL DEFAULT 0,
  `version`        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lyric_song` (`song_id`),
  KEY `idx_lyric_song` (`song_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌词表';

CREATE TABLE IF NOT EXISTS `t_category` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(50)  NOT NULL COMMENT '分类名',
  `code`        VARCHAR(50)  NOT NULL COMMENT '分类编码',
  `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父分类',
  `icon`        VARCHAR(500) DEFAULT NULL,
  `sort_order`  INT          NOT NULL DEFAULT 0,
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_code` (`code`),
  KEY `idx_category_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分类表';

CREATE TABLE IF NOT EXISTS `t_tag` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(50)  NOT NULL COMMENT '标签名',
  `tag_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '1风格 2场景 3情绪 4年代 5语种 6自定义',
  `use_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '使用次数',
  `is_hot`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否热门标签',
  `status`      TINYINT      NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `version`     INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name_type` (`name`, `tag_type`),
  KEY `idx_tag_type_hot` (`tag_type`, `is_hot`, `use_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签表';

CREATE TABLE IF NOT EXISTS `t_song_tag` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `song_id`     BIGINT UNSIGNED NOT NULL,
  `tag_id`      BIGINT UNSIGNED NOT NULL,
  `weight`      DECIMAL(6,4) NOT NULL DEFAULT 1.0000 COMMENT '标签权重',
  `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_song_tag` (`song_id`, `tag_id`),
  KEY `idx_st_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌曲标签关联表（物理删除）';

CREATE TABLE IF NOT EXISTS `t_playlist` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `name`          VARCHAR(100) NOT NULL COMMENT '歌单名',
  `cover`         VARCHAR(500) DEFAULT NULL,
  `description`   VARCHAR(1000) DEFAULT NULL,
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '创建者(0=系统歌单)',
  `playlist_type` TINYINT      NOT NULL DEFAULT 1 COMMENT '1用户歌单 2编辑歌单 3算法歌单 4场景歌单',
  `is_public`     TINYINT      NOT NULL DEFAULT 1 COMMENT '0私密 1公开',
  `tags`          VARCHAR(500) DEFAULT NULL COMMENT '标签,逗号分隔',
  `song_count`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '歌曲数',
  `play_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '播放数',
  `collect_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏数',
  `like_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数[10]',
  `comment_count` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `share_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `hot_score`     INT UNSIGNED NOT NULL DEFAULT 0,
  `is_recommend`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否首页推荐',
  `sort_weight`   INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  `audit_status`  TINYINT      NOT NULL DEFAULT 1 COMMENT '0待审 1通过 2拒绝',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0隐藏',
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`     BIGINT UNSIGNED DEFAULT NULL,
  `update_by`     BIGINT UNSIGNED DEFAULT NULL,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  `version`       INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_pl_user` (`user_id`, `deleted`),
  KEY `idx_pl_type_hot` (`playlist_type`, `is_public`, `hot_score`),
  KEY `idx_pl_recommend` (`is_recommend`, `sort_weight`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌单表';

CREATE TABLE IF NOT EXISTS `t_playlist_song` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `playlist_id` BIGINT UNSIGNED NOT NULL,
  `song_id`     BIGINT UNSIGNED NOT NULL,
  `sort_order`  INT NOT NULL DEFAULT 0 COMMENT '排序号(步长100)',
  `add_by`      BIGINT UNSIGNED DEFAULT NULL COMMENT '添加人',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pl_song` (`playlist_id`, `song_id`),
  KEY `idx_pls_playlist_sort` (`playlist_id`, `sort_order`),
  KEY `idx_pls_song` (`song_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌单歌曲关联表（物理删除）';

-- -----------------------------------------------------------------------------
-- 3. 系统域（模块 01 仅建表，管理接口在模块 09）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_sys_config` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `config_key`   VARCHAR(100) NOT NULL COMMENT '配置键',
  `config_value` TEXT COMMENT '配置值',
  `value_type`   TINYINT NOT NULL DEFAULT 1 COMMENT '1字符串 2数字 3布尔 4JSON',
  `config_group` VARCHAR(50) DEFAULT NULL COMMENT '分组',
  `description`  VARCHAR(200) DEFAULT NULL,
  `is_public`    TINYINT NOT NULL DEFAULT 0 COMMENT '是否下发前端',
  `status`       TINYINT NOT NULL DEFAULT 1,
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `update_by`    BIGINT UNSIGNED DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_config_group` (`config_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置表';

-- -----------------------------------------------------------------------------
-- 4. 搜索域（模块 06）
--    两张表的 DDL 照抄《04-数据模型与数据库设计》§10，不做增删 —— 它们是全文档
--    共用的契约（系分 06 D-47）。搜索域的表不在模块 01 的范围内，故模块 01 未建。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_search_history` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '仅登录用户记录（BR-15-006）',
  `keyword`      VARCHAR(200) NOT NULL,
  `search_type`  TINYINT NOT NULL DEFAULT 1 COMMENT '1关键词 2AI',
  `result_count` INT NOT NULL DEFAULT 0,
  `is_clicked`   TINYINT NOT NULL DEFAULT 0,
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sh_user` (`user_id`, `create_time`),
  KEY `idx_sh_type_time` (`search_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='搜索历史表';

CREATE TABLE IF NOT EXISTS `t_search_hot` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `keyword`      VARCHAR(200) NOT NULL,
  `search_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '搜索次数',
  `rank_no`      INT NOT NULL DEFAULT 0 COMMENT '排名',
  `is_top`       TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶(运营,BR-15-030)',
  `stat_date`    DATE NOT NULL COMMENT '统计日期',
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hot_date_word` (`stat_date`, `keyword`),
  KEY `idx_hot_date_rank` (`stat_date`, `rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='热搜词表';

-- -----------------------------------------------------------------------------
-- 5. 实时通信域（模块 04）
--    DDL 照抄《04-数据模型与数据库设计》§5.3 / §6，不做增删。
--    两张表都是**追加型**：没有 deleted/version/update_time，因此对应的实体
--    不继承 BaseEntity（见 Notification / RoomMessage 类注释）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_room_message` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `room_id`      BIGINT UNSIGNED NOT NULL,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `message_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1聊天 2弹幕 3系统 4礼物 5进房 6控播',
  `content`      VARCHAR(1000) DEFAULT NULL COMMENT '内容(敏感词过滤后)',
  `extra`        JSON DEFAULT NULL COMMENT '扩展(礼物/歌曲信息)',
  `audit_status` TINYINT NOT NULL DEFAULT 1 COMMENT '0待审 1通过 2拦截',
  `status`       TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0撤回',
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_rm_room_time` (`room_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间消息表';

CREATE TABLE IF NOT EXISTS `t_notification` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '接收者',
  `notify_type`   TINYINT NOT NULL COMMENT '1系统 2评论回复 3点赞 4关注 5私信 6@提醒 7房间邀请 8审核结果 9举报结果',
  `title`         VARCHAR(200) DEFAULT NULL,
  `content`       VARCHAR(1000) DEFAULT NULL,
  `from_user_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '触发者',
  `from_nickname` VARCHAR(50) DEFAULT NULL,
  `from_avatar`   VARCHAR(500) DEFAULT NULL,
  `target_type`   TINYINT DEFAULT NULL COMMENT '跳转目标类型',
  `target_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '跳转目标ID',
  `is_read`       TINYINT NOT NULL DEFAULT 0,
  `read_time`     DATETIME(3) DEFAULT NULL,
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_notify_user` (`user_id`, `is_read`, `create_time`),
  KEY `idx_notify_type` (`user_id`, `notify_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知表';

-- -----------------------------------------------------------------------------
-- 6. 音乐房域（模块 05）
--    模块 04 只落地了消息与通知两张表；房间主体、成员、麦位、队列在此补齐。
--    全部为追加 DDL（CREATE TABLE IF NOT EXISTS），只作用于 newmusic 库。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_room` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `room_no`           CHAR(6) NOT NULL COMMENT '6位数字房间号，全局唯一且解散后不可复用',
  `name`              VARCHAR(30) NOT NULL COMMENT '房间名(1-30字符，敏感词过滤后)',
  `cover`             VARCHAR(500) DEFAULT NULL COMMENT '封面URL，默认房主头像',
  `owner_id`          BIGINT UNSIGNED NOT NULL COMMENT '房主ID',
  `room_type`         TINYINT NOT NULL DEFAULT 1 COMMENT '1公开 2私密 3密码',
  `password_hash`     VARCHAR(100) DEFAULT NULL COMMENT '密码房BCrypt密文，绝不存明文',
  `notice`            VARCHAR(500) DEFAULT NULL COMMENT '房间公告(1-500字符)',
  `theme_tags`        VARCHAR(200) DEFAULT NULL COMMENT '主题标签，逗号分隔',
  `mic_count`         TINYINT NOT NULL DEFAULT 6 COMMENT '麦位数 4/6/8/12',
  `max_members`       INT NOT NULL DEFAULT 100 COMMENT '人数上限 50/100/200/500',
  `play_mode`         TINYINT NOT NULL DEFAULT 1 COMMENT '1房主控播 2自由播放 3投票切歌',
  `need_approve`      TINYINT NOT NULL DEFAULT 1 COMMENT '点歌/上麦是否需要审核',
  `allow_free_mic`    TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许自由上麦',
  `status`            TINYINT NOT NULL DEFAULT 1 COMMENT '1活跃 2已关闭 3已解散',
  `online_count`      INT NOT NULL DEFAULT 0 COMMENT '在线人数(Redis在线集合的落库镜像)',
  `hot_score`         INT NOT NULL DEFAULT 0 COMMENT '热度分，定时任务按PRD公式重算',
  `current_song_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '当前歌曲ID(冗余，权威态在Redis)',
  `last_active_time`  DATETIME(3) DEFAULT NULL COMMENT '最后活跃时间，用于空房/超时解散',
  `total_duration`    BIGINT NOT NULL DEFAULT 0 COMMENT '房间累计停留秒数(热度公式输入)',
  `create_time`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`         BIGINT UNSIGNED DEFAULT NULL,
  `update_by`         BIGINT UNSIGNED DEFAULT NULL,
  `deleted`           TINYINT NOT NULL DEFAULT 0,
  `version`           INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_no` (`room_no`),
  KEY `idx_room_status_type` (`status`, `room_type`, `hot_score`),
  KEY `idx_room_owner` (`owner_id`, `status`),
  KEY `idx_room_active` (`status`, `last_active_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='音乐房表';

CREATE TABLE IF NOT EXISTS `t_room_member` (
  `id`               BIGINT UNSIGNED NOT NULL,
  `room_id`          BIGINT UNSIGNED NOT NULL,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `room_role`        TINYINT NOT NULL DEFAULT 0 COMMENT '0成员 1房管 2房主 9黑名单',
  `mute_status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1禁言中',
  `mute_until`       DATETIME(3) DEFAULT NULL COMMENT '禁言到期时间',
  `join_time`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次加入时间',
  `last_enter_time`  DATETIME(3) DEFAULT NULL COMMENT '本次在线开始时间，离开时清空',
  `leave_time`       DATETIME(3) DEFAULT NULL COMMENT '最近离开时间',
  `total_duration`   BIGINT NOT NULL DEFAULT 0 COMMENT '累计停留秒数',
  `contribute`       INT NOT NULL DEFAULT 0 COMMENT '贡献值(房主交接排序用)',
  `create_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`        BIGINT UNSIGNED DEFAULT NULL,
  `update_by`        BIGINT UNSIGNED DEFAULT NULL,
  `deleted`          TINYINT NOT NULL DEFAULT 0,
  `version`          INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_member` (`room_id`, `user_id`),
  KEY `idx_member_user` (`user_id`, `deleted`),
  KEY `idx_member_room_role` (`room_id`, `room_role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间成员表';

CREATE TABLE IF NOT EXISTS `t_room_mic` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `room_id`     BIGINT UNSIGNED NOT NULL,
  `mic_pos`     TINYINT NOT NULL COMMENT '麦位序号，1为房主专属',
  `user_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '占用者，NULL表示空麦',
  `status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0空闲 1占用 2锁定',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_mic_pos` (`room_id`, `mic_pos`),
  KEY `idx_mic_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间麦位表';

CREATE TABLE IF NOT EXISTS `t_room_queue` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `room_id`     BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `song_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '排麦记录可为空',
  `song_name`   VARCHAR(200) DEFAULT NULL,
  `artist_name` VARCHAR(100) DEFAULT NULL,
  `cover`       VARCHAR(500) DEFAULT NULL,
  `duration`    INT DEFAULT NULL COMMENT '歌曲时长(秒)',
  `queue_type`  TINYINT NOT NULL DEFAULT 2 COMMENT '1排麦 2播放队列',
  `status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1已通过 2已拒绝 3已播放 4已移除',
  `seq`         BIGINT NOT NULL DEFAULT 0 COMMENT '同房间同类型内的排队序号',
  `source`      TINYINT NOT NULL DEFAULT 2 COMMENT '1房主 2成员点歌 3排麦 4系统推荐',
  `priority`    INT NOT NULL DEFAULT 0 COMMENT '优先级：0普通 100房主置顶（模块13）',
  `vote_count`  INT NOT NULL DEFAULT 0 COMMENT '重复点歌合并的投票数（模块13）',
  `order_index` BIGINT NOT NULL DEFAULT 0 COMMENT '排序索引，越大越靠前（模块13）',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_rq_room_type_status` (`room_id`, `queue_type`, `status`, `seq`),
  KEY `idx_rq_user` (`user_id`, `queue_type`, `status`),
  KEY `idx_queue_room_order` (`room_id`, `queue_type`, `status`, `order_index` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间排麦/点歌队列表';

CREATE TABLE IF NOT EXISTS `t_room_vote` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `room_id`      BIGINT UNSIGNED NOT NULL,
  `song_id`      BIGINT UNSIGNED NOT NULL COMMENT '被投票跳过的歌曲',
  `initiator_id` BIGINT UNSIGNED NOT NULL COMMENT '发起人',
  `online_count` INT NOT NULL DEFAULT 0 COMMENT '发起时的在线人数快照',
  `agree_count`  INT NOT NULL DEFAULT 0 COMMENT '同意票数',
  `status`       TINYINT NOT NULL DEFAULT 1 COMMENT '1进行中 2通过 3未通过 4取消',
  `start_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `end_time`     DATETIME(3) NOT NULL COMMENT '投票截止时间',
  `create_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`    BIGINT UNSIGNED DEFAULT NULL,
  `update_by`    BIGINT UNSIGNED DEFAULT NULL,
  `deleted`      TINYINT NOT NULL DEFAULT 0,
  `version`      INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote_room_song` (`room_id`, `song_id`),
  KEY `idx_vote_room_status` (`room_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间投票切歌记录';

CREATE TABLE IF NOT EXISTS `t_room_vote_detail` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `vote_id`     BIGINT UNSIGNED NOT NULL,
  `room_id`     BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `agree`       TINYINT NOT NULL DEFAULT 1 COMMENT '1同意（本期只记录同意）',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote_detail_vote_user` (`vote_id`, `user_id`),
  KEY `idx_vote_detail_room` (`room_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间投票明细';

-- -----------------------------------------------------------------------------
-- 7. 推荐域（模块 08）
--
-- 与 PRD 17 的差异（系分 08 D-01/D-02）：
--   * PRD 的 t_play_history 按月分表；本机 MVP 用单表 + play_time 索引，
--     分表路由属于模块 17（数据平台）的容量优化，不影响接口契约。
--   * PRD 的推荐位/效果统计（P1）尚未建表，模块 08 P0 不依赖它们。
-- 全部为追加 DDL（CREATE TABLE IF NOT EXISTS），只作用于 newmusic 库。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_play_history` (
  `id`             BIGINT UNSIGNED NOT NULL,
  `user_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '登录用户；游客为 NULL',
  `device_id`      VARCHAR(64) DEFAULT NULL COMMENT '游客设备标识',
  `song_id`        BIGINT UNSIGNED NOT NULL,
  -- 冗余快照：歌曲改名/下架后历史仍可展示（BR-16-033），也避免推荐聚合频繁 JOIN
  `song_name`      VARCHAR(200) DEFAULT NULL,
  `artist_id`      BIGINT UNSIGNED DEFAULT NULL,
  `artist_name`    VARCHAR(100) DEFAULT NULL,
  `genre`          VARCHAR(50) DEFAULT NULL,
  `language`       VARCHAR(20) DEFAULT NULL,
  `quality`        TINYINT NOT NULL DEFAULT 1 COMMENT '1标准 2高清 3无损',
  `play_duration`  INT NOT NULL DEFAULT 0 COMMENT '实际播放秒数',
  `song_duration`  INT NOT NULL DEFAULT 0 COMMENT '歌曲总时长(秒)',
  `last_position`  DECIMAL(10,1) NOT NULL DEFAULT 0 COMMENT '最近进度(秒)，继续播放用',
  `is_valid`       TINYINT NOT NULL DEFAULT 0 COMMENT '有效播放：>=30s 或 >=60%',
  `source`         VARCHAR(32) DEFAULT NULL COMMENT 'search/playlist/album/artist/home/room/recommend',
  `room_id`        BIGINT UNSIGNED DEFAULT NULL,
  `play_time`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `create_time`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT NOT NULL DEFAULT 0,
  `version`        INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_ph_user_time` (`user_id`, `deleted`, `play_time`),
  KEY `idx_ph_user_song_time` (`user_id`, `song_id`, `play_time`),
  KEY `idx_ph_song_time` (`song_id`, `play_time`),
  KEY `idx_ph_device` (`device_id`, `play_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='播放历史（模块 07 的行为数据，模块 08 消费）';

CREATE TABLE IF NOT EXISTS `t_recommend_feedback` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `song_id`     BIGINT UNSIGNED DEFAULT NULL,
  `artist_id`   BIGINT UNSIGNED DEFAULT NULL,
  `action`      VARCHAR(20) NOT NULL COMMENT 'LIKE/DISLIKE/DISLIKE_ARTIST/UNDO/UNDO_ARTIST',
  `scene`       VARCHAR(20) DEFAULT NULL COMMENT 'DAILY/GUESS/SIMILAR/HOME',
  `position`    INT DEFAULT NULL COMMENT '列表位置，用于偏差分析',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_rf_user_time` (`user_id`, `deleted`, `create_time`),
  KEY `idx_rf_user_action_song` (`user_id`, `action`, `song_id`),
  KEY `idx_rf_user_action_artist` (`user_id`, `action`, `artist_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐反馈（追加日志，当前屏蔽态由回放得出）';

CREATE TABLE IF NOT EXISTS `t_user_preference` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `styles`      VARCHAR(200) DEFAULT NULL COMMENT '风格，逗号分隔，最多 3 个',
  `languages`   VARCHAR(200) DEFAULT NULL COMMENT '语种，逗号分隔',
  `artists`     VARCHAR(500) DEFAULT NULL COMMENT '喜欢的歌手ID，逗号分隔，最多 5 个',
  `mood`        VARCHAR(32) DEFAULT NULL COMMENT '当前状态：focus/relax/sport/sad/any',
  `source`      TINYINT NOT NULL DEFAULT 1 COMMENT '1注册问卷 2设置页',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`   BIGINT UNSIGNED DEFAULT NULL,
  `update_by`   BIGINT UNSIGNED DEFAULT NULL,
  `deleted`     TINYINT NOT NULL DEFAULT 0,
  `version`     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pref_user` (`user_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷启动口味问卷';

CREATE TABLE IF NOT EXISTS `t_song_similar` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `song_id`         BIGINT UNSIGNED NOT NULL COMMENT '源歌曲',
  `similar_song_id` BIGINT UNSIGNED NOT NULL COMMENT '相似歌曲',
  `score`           DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '相似度分',
  `rank_no`         INT NOT NULL DEFAULT 0 COMMENT '源歌曲内的名次，1 开始',
  `create_time`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`       BIGINT UNSIGNED DEFAULT NULL,
  `update_by`       BIGINT UNSIGNED DEFAULT NULL,
  `deleted`         TINYINT NOT NULL DEFAULT 0,
  `version`         INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_similar_pair` (`song_id`, `similar_song_id`),
  KEY `idx_similar_song_rank` (`song_id`, `deleted`, `rank_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='相似歌曲离线预计算结果';

-- -----------------------------------------------------------------------------
-- 9. 管理后台域（模块 09）
--
-- 后台 RBAC 与 C 端 RBAC **完全独立**：t_admin_* 不读写 t_role / t_permission，
-- 权限码统一带 admin: 前缀。这样一次误操作最多影响一侧（BR-18-149）。
-- 所有表 CREATE IF NOT EXISTS，可重复执行。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_admin_user` (
  `id`                    BIGINT UNSIGNED NOT NULL,
  `username`              VARCHAR(64)     NOT NULL COMMENT '后台登录名，唯一',
  `password`              VARCHAR(100)    NOT NULL COMMENT 'BCrypt',
  `real_name`             VARCHAR(64)     DEFAULT NULL COMMENT '姓名',
  `email`                 VARCHAR(128)    DEFAULT NULL,
  `phone`                 VARCHAR(255)    DEFAULT NULL COMMENT '手机号（生产应加密；当前仅存原值，见系分 D-14）',
  `status`                TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常 2禁用',
  `totp_secret`           VARCHAR(255)    DEFAULT NULL COMMENT 'TOTP Base32 密钥；enc:v1: 前缀为 AES-GCM 密文',
  `totp_enabled`          TINYINT         NOT NULL DEFAULT 0,
  `force_change_password` TINYINT         NOT NULL DEFAULT 0 COMMENT '1=下次登录必须改密',
  `password_update_time`  DATETIME(3)     DEFAULT NULL COMMENT '用于 90 天强制改密',
  `last_login_time`       DATETIME(3)     DEFAULT NULL,
  `last_login_ip`         VARCHAR(64)     DEFAULT NULL,
  `fail_count`            INT             NOT NULL DEFAULT 0 COMMENT '连续失败次数（库内权威，Redis 只做窗口加速）',
  `lock_until`            DATETIME(3)     DEFAULT NULL COMMENT '锁定到期时间',
  `allow_ip_list`         VARCHAR(512)    DEFAULT NULL COMMENT '后台 IP 白名单，逗号分隔，空=不限制',
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

CREATE TABLE IF NOT EXISTS `t_admin_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `role_code`   VARCHAR(50)  NOT NULL COMMENT 'SUPER_ADMIN / ADMIN / OPERATOR',
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

CREATE TABLE IF NOT EXISTS `t_admin_permission` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `perm_code`   VARCHAR(100) NOT NULL COMMENT 'admin:user:list',
  `perm_name`   VARCHAR(100) NOT NULL,
  `perm_group`  VARCHAR(50)  DEFAULT NULL COMMENT '用户/内容/审核/房间/日志/系统',
  `description` VARCHAR(200) DEFAULT NULL,
  `sensitive`   TINYINT      NOT NULL DEFAULT 0 COMMENT '1=敏感权限，使用必须写详细日志并通知超管',
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

CREATE TABLE IF NOT EXISTS `t_admin_user_role` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_role` (`user_id`, `role_id`),
  KEY `idx_admin_ur_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员-角色（物理删除）';

CREATE TABLE IF NOT EXISTS `t_admin_role_permission` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `role_id`     BIGINT UNSIGNED NOT NULL,
  `perm_id`     BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_perm` (`role_id`, `perm_id`),
  KEY `idx_admin_rp_perm` (`perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色-权限（物理删除）';

CREATE TABLE IF NOT EXISTS `t_operation_log` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `operator_id`     BIGINT UNSIGNED NOT NULL COMMENT '管理员ID；0=系统/匿名',
  `operator_name`   VARCHAR(64)     DEFAULT NULL COMMENT '冗余用户名，避免日志与账号表关联',
  `op_type`         VARCHAR(32)     NOT NULL COMMENT 'CREATE/UPDATE/DELETE/BAN/UNBAN/AUDIT/CONFIG/EXPORT/VIEW_SENSITIVE/ACCESS_DENIED',
  `target_type`     VARCHAR(32)     DEFAULT NULL COMMENT 'USER/SONG/ARTIST/ALBUM/ROOM/REPORT/AUDIT/CONFIG/ADMIN_USER',
  `target_id`       VARCHAR(64)     DEFAULT NULL,
  `description`     VARCHAR(500)    DEFAULT NULL,
  `before_snapshot` TEXT            COMMENT '脱敏后的变更前 JSON',
  `after_snapshot`  TEXT            COMMENT '脱敏后的变更后 JSON',
  `ip`              VARCHAR(64)     DEFAULT NULL,
  `user_agent`      VARCHAR(512)    DEFAULT NULL,
  `result`          TINYINT         NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `error_msg`       VARCHAR(500)    DEFAULT NULL,
  `trace_id`        VARCHAR(64)     DEFAULT NULL,
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_oplog_operator_time` (`operator_id`, `create_time`),
  KEY `idx_oplog_type_time` (`op_type`, `create_time`),
  KEY `idx_oplog_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台操作日志（只增不改不删）';

CREATE TABLE IF NOT EXISTS `t_login_log` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '账号不存在时为 NULL，避免侧信道也避免假外键',
  `username`    VARCHAR(64)     DEFAULT NULL,
  `login_type`  TINYINT         NOT NULL DEFAULT 1 COMMENT '1 C端 2 后台',
  `ip`          VARCHAR(64)     DEFAULT NULL,
  `location`    VARCHAR(64)     DEFAULT NULL COMMENT 'IP 归属地；本机无 GeoIP 库时为空',
  `user_agent`  VARCHAR(512)    DEFAULT NULL,
  `device`      VARCHAR(100)    DEFAULT NULL,
  `result`      TINYINT         NOT NULL COMMENT '1成功 0失败 2锁定',
  `fail_reason` VARCHAR(200)    DEFAULT NULL,
  `suspicious`  TINYINT         NOT NULL DEFAULT 0 COMMENT '连续失败≥3/异地登录',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_login_user_time` (`user_id`, `create_time`),
  KEY `idx_login_type_time` (`login_type`, `create_time`),
  KEY `idx_login_ip_time` (`ip`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志（C端+后台）';

CREATE TABLE IF NOT EXISTS `t_sys_config_history` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `config_key`      VARCHAR(100) NOT NULL,
  `old_value`       TEXT,
  `new_value`       TEXT,
  `value_type`      TINYINT      NOT NULL DEFAULT 1,
  `change_type`     VARCHAR(16)  NOT NULL DEFAULT 'UPDATE' COMMENT 'UPDATE/ROLLBACK',
  `changed_by`      BIGINT UNSIGNED DEFAULT NULL,
  `changed_by_name` VARCHAR(64)  DEFAULT NULL,
  `remark`          VARCHAR(200) DEFAULT NULL,
  `create_time`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_cfg_his_key_time` (`config_key`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置变更历史';

CREATE TABLE IF NOT EXISTS `t_audit_task` (
  `id`                BIGINT UNSIGNED NOT NULL,
  `task_type`         VARCHAR(32)  NOT NULL COMMENT 'SONG/LYRIC/PLAYLIST/COMMENT/NICKNAME/ROOM_NOTICE/REPORT/PRIVATE_MESSAGE',
  `target_type`       VARCHAR(32)  NOT NULL,
  `target_id`         VARCHAR(64)  NOT NULL,
  `priority`          TINYINT      NOT NULL DEFAULT 2 COMMENT '1高 2中',
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '0待领 1已领 2通过 3拒绝 4自动关闭',
  `submitter_id`      BIGINT UNSIGNED DEFAULT NULL COMMENT '内容作者，拒绝时通知',
  `title`             VARCHAR(200) DEFAULT NULL,
  `content_snapshot`  TEXT         COMMENT '待审内容快照',
  `machine_result`    VARCHAR(500) DEFAULT NULL,
  `machine_score`     DECIMAL(5,4) DEFAULT NULL,
  `claimed_by`        BIGINT UNSIGNED DEFAULT NULL,
  `claimed_at`        DATETIME(3)  DEFAULT NULL,
  `claim_expire_at`   DATETIME(3)  DEFAULT NULL,
  `handled_by`        BIGINT UNSIGNED DEFAULT NULL,
  `handled_at`        DATETIME(3)  DEFAULT NULL,
  `handle_result`     VARCHAR(16)  DEFAULT NULL COMMENT 'APPROVE/REJECT',
  `reject_reason`     VARCHAR(500) DEFAULT NULL,
  `action_applied`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已联动业务动作，0=仅记录结论',
  `escalated`         TINYINT      NOT NULL DEFAULT 0 COMMENT '超过24小时未处理',
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

CREATE TABLE IF NOT EXISTS `t_audit_record` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `auditor_id`    BIGINT UNSIGNED DEFAULT NULL,
  `auditor_name`  VARCHAR(64)  DEFAULT NULL,
  `action`        VARCHAR(16)  NOT NULL COMMENT 'CLAIM/APPROVE/REJECT/RELEASE/AUTO_ESCALATE',
  `before_status` TINYINT      DEFAULT NULL,
  `after_status`  TINYINT      DEFAULT NULL,
  `reason`        VARCHAR(500) DEFAULT NULL,
  `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_record_task` (`task_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核留痕';

CREATE TABLE IF NOT EXISTS `t_report` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `reporter_id`   BIGINT UNSIGNED NOT NULL,
  `reporter_name` VARCHAR(64)  DEFAULT NULL,
  `target_type`   VARCHAR(32)  NOT NULL COMMENT 'SONG/COMMENT/USER/ROOM/MESSAGE/PLAYLIST/LYRIC',
  `target_id`     VARCHAR(64)  NOT NULL,
  `target_name`   VARCHAR(200) DEFAULT NULL,
  `reason_type`   VARCHAR(32)  DEFAULT NULL,
  `reason`        VARCHAR(500) DEFAULT NULL,
  `evidence_urls` VARCHAR(1000) DEFAULT NULL,
  `priority`      TINYINT      NOT NULL DEFAULT 2 COMMENT '1高（涉黄涉暴/私信） 2普通',
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

-- 申诉/审批/危险任务：PRD 13 章要求的数据模型先落表。
-- 申诉与危险任务属 P2/模块18，当前只保留表结构，不提供半成品接口（见系分 D-18）。
CREATE TABLE IF NOT EXISTS `t_appeal` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号/内容申诉（P2，表先就位）';

CREATE TABLE IF NOT EXISTS `t_approval` (
  `id`            BIGINT UNSIGNED NOT NULL,
  `apply_user_id` BIGINT UNSIGNED NOT NULL,
  `approve_type`  VARCHAR(32) NOT NULL COMMENT 'EXPORT/DANGER',
  `payload`       TEXT,
  `status`        TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2驳回',
  `approver_id`   BIGINT UNSIGNED DEFAULT NULL,
  `approve_time`  DATETIME(3) DEFAULT NULL,
  `reason`        VARCHAR(500) DEFAULT NULL,
  `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_approval_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感操作审批（P2，表先就位）';

CREATE TABLE IF NOT EXISTS `t_danger_task` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `op_type`     VARCHAR(32) NOT NULL,
  `status`      TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行 1执行中 2成功 3失败',
  `progress`    INT NOT NULL DEFAULT 0 COMMENT '0-100',
  `operator_id` BIGINT UNSIGNED DEFAULT NULL,
  `detail`      VARCHAR(1000) DEFAULT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_danger_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危险操作任务（P2，表先就位）';

-- -----------------------------------------------------------------------------
-- 26. 社交互动域（模块 10）
-- 设计：docs/系分/10-社交基础.md
-- 说明：评论/会话/私信/设置用逻辑删除；点赞/收藏/关注/拉黑/歌手关注是关联表，
--       物理删除 + 唯一索引保证幂等（见 BaseLinkEntity 注释）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_comment` (
  `id`             BIGINT UNSIGNED NOT NULL COMMENT '评论ID(雪花)',
  `target_type`    VARCHAR(20)     NOT NULL COMMENT 'SONG/PLAYLIST/ALBUM/ROOM/COMMENT',
  `target_id`      BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '评论人',
  `root_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '根评论ID，顶级为0',
  `parent_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '直接父评论ID，顶级为0',
  `reply_user_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '被回复用户ID',
  `content`        VARCHAR(500)    NOT NULL COMMENT '纯文本（已剔除HTML标签）',
  `like_count`     INT             NOT NULL DEFAULT 0,
  `reply_count`    INT             NOT NULL DEFAULT 0,
  `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '0待审 1已发布 2已删除 3已屏蔽',
  `audit_reason`   VARCHAR(200)    DEFAULT NULL,
  `ip`             VARCHAR(64)     DEFAULT NULL,
  `user_agent`     VARCHAR(500)    DEFAULT NULL,
  `create_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`      BIGINT UNSIGNED DEFAULT NULL,
  `update_by`      BIGINT UNSIGNED DEFAULT NULL,
  `deleted`        TINYINT         NOT NULL DEFAULT 0,
  `version`        INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_comment_target` (`target_type`, `target_id`, `root_id`, `status`, `create_time`),
  KEY `idx_comment_root` (`root_id`, `create_time`),
  KEY `idx_comment_user` (`user_id`, `create_time`),
  KEY `idx_comment_hot` (`target_type`, `target_id`, `root_id`, `like_count`, `reply_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论（两级）';

CREATE TABLE IF NOT EXISTS `t_like` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(20)     NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_like_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_like_target` (`target_type`, `target_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点赞关系（物理删除）';

CREATE TABLE IF NOT EXISTS `t_collect` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `target_type` VARCHAR(20)     NOT NULL COMMENT 'SONG/PLAYLIST/ALBUM/ROOM',
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `folder_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL=默认收藏夹',
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_collect_user_target` (`user_id`, `target_type`, `target_id`),
  KEY `idx_collect_user_time` (`user_id`, `target_type`, `create_time`),
  KEY `idx_collect_folder` (`folder_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏关系（物理删除）';

CREATE TABLE IF NOT EXISTS `t_collect_folder` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `name`        VARCHAR(30)     NOT NULL,
  `is_default`  TINYINT         NOT NULL DEFAULT 0,
  `item_count`  INT             NOT NULL DEFAULT 0,
  `sort_order`  INT             NOT NULL DEFAULT 0,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_folder_user_name` (`user_id`, `name`),
  KEY `idx_folder_user_sort` (`user_id`, `sort_order`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏夹（物理删除）';

CREATE TABLE IF NOT EXISTS `t_share` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `code`        VARCHAR(16)     NOT NULL COMMENT '短码Base62(6位)',
  `target_type` VARCHAR(20)     NOT NULL,
  `target_id`   BIGINT UNSIGNED NOT NULL,
  `sharer_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '未登录分享为NULL',
  `channel`     VARCHAR(20)     DEFAULT NULL COMMENT 'COPY_LINK/IN_APP/SYSTEM/THIRD_PARTY',
  `click_count` INT             NOT NULL DEFAULT 0,
  `expire_time` DATETIME(3)     DEFAULT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_share_code` (`code`),
  KEY `idx_share_target` (`target_type`, `target_id`),
  KEY `idx_share_sharer` (`sharer_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分享短链';

CREATE TABLE IF NOT EXISTS `t_follow` (
  `id`           BIGINT UNSIGNED NOT NULL,
  `follower_id`  BIGINT UNSIGNED NOT NULL COMMENT '关注者',
  `following_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注者',
  `create_time`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow_pair` (`follower_id`, `following_id`),
  KEY `idx_follow_following` (`following_id`, `create_time`),
  KEY `idx_follow_follower` (`follower_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注（单向，物理删除）';

CREATE TABLE IF NOT EXISTS `t_user_artist_follow` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `artist_id`   BIGINT UNSIGNED NOT NULL,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artist_follow_pair` (`user_id`, `artist_id`),
  KEY `idx_artist_follow_artist` (`artist_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='歌手关注（物理删除）';

CREATE TABLE IF NOT EXISTS `t_blacklist` (
  `id`              BIGINT UNSIGNED NOT NULL,
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '拉黑发起人',
  `blocked_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被拉黑者',
  `create_time`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blacklist_pair` (`user_id`, `blocked_user_id`),
  KEY `idx_blacklist_blocked` (`blocked_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户黑名单（物理删除）';

CREATE TABLE IF NOT EXISTS `t_conversation` (
  `id`                   BIGINT UNSIGNED NOT NULL,
  `user_a_id`            BIGINT UNSIGNED NOT NULL COMMENT '较小的userId',
  `user_b_id`            BIGINT UNSIGNED NOT NULL COMMENT '较大的userId',
  `last_message_id`      BIGINT UNSIGNED DEFAULT NULL,
  `last_message_time`    DATETIME(3)     DEFAULT NULL,
  `last_message_preview` VARCHAR(100)    DEFAULT NULL,
  `unread_a`             INT             NOT NULL DEFAULT 0 COMMENT 'A未读数（B发来的）',
  `unread_b`             INT             NOT NULL DEFAULT 0 COMMENT 'B未读数（A发来的）',
  `create_time`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`            BIGINT UNSIGNED DEFAULT NULL,
  `update_by`            BIGINT UNSIGNED DEFAULT NULL,
  `deleted`              TINYINT         NOT NULL DEFAULT 0,
  `version`              INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_pair` (`user_a_id`, `user_b_id`),
  KEY `idx_conversation_a_time` (`user_a_id`, `last_message_time`),
  KEY `idx_conversation_b_time` (`user_b_id`, `last_message_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信会话';

CREATE TABLE IF NOT EXISTS `t_private_message` (
  `id`                  BIGINT UNSIGNED NOT NULL,
  `conversation_id`     BIGINT UNSIGNED NOT NULL,
  `sender_id`           BIGINT UNSIGNED NOT NULL,
  `receiver_id`         BIGINT UNSIGNED NOT NULL,
  `msg_type`            VARCHAR(20)     NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/SONG/PLAYLIST/ROOM',
  `content`             VARCHAR(1000)   NOT NULL,
  `is_read`             TINYINT         NOT NULL DEFAULT 0,
  `read_time`           DATETIME(3)     DEFAULT NULL,
  `is_revoked`          TINYINT         NOT NULL DEFAULT 0,
  `revoke_time`         DATETIME(3)     DEFAULT NULL,
  `deleted_by_sender`   TINYINT         NOT NULL DEFAULT 0,
  `deleted_by_receiver` TINYINT         NOT NULL DEFAULT 0,
  `create_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`           BIGINT UNSIGNED DEFAULT NULL,
  `update_by`           BIGINT UNSIGNED DEFAULT NULL,
  `deleted`             TINYINT         NOT NULL DEFAULT 0,
  `version`             INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_message_conv` (`conversation_id`, `create_time`),
  KEY `idx_message_receiver_read` (`receiver_id`, `is_read`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信消息';

CREATE TABLE IF NOT EXISTS `t_notification_setting` (
  `id`                 BIGINT UNSIGNED NOT NULL,
  `user_id`            BIGINT UNSIGNED NOT NULL,
  `like_enabled`       TINYINT         NOT NULL DEFAULT 1,
  `comment_enabled`    TINYINT         NOT NULL DEFAULT 1,
  `follow_enabled`     TINYINT         NOT NULL DEFAULT 1,
  `message_enabled`    TINYINT         NOT NULL DEFAULT 1,
  `system_enabled`     TINYINT         NOT NULL DEFAULT 1,
  `new_song_enabled`   TINYINT         NOT NULL DEFAULT 1,
  `read_receipt_enabled` TINYINT       NOT NULL DEFAULT 1,
  `create_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`          BIGINT UNSIGNED DEFAULT NULL,
  `update_by`          BIGINT UNSIGNED DEFAULT NULL,
  `deleted`            TINYINT         NOT NULL DEFAULT 0,
  `version`            INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notify_setting_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知设置';

CREATE TABLE IF NOT EXISTS `t_user_privacy_setting` (
  `id`                     BIGINT UNSIGNED NOT NULL,
  `user_id`                BIGINT UNSIGNED NOT NULL,
  `message_policy`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0所有人 1互关 2关闭',
  `comment_policy`         TINYINT         NOT NULL DEFAULT 0 COMMENT '0所有人 1关注我的人 2关闭',
  `mention_policy`         TINYINT         NOT NULL DEFAULT 0,
  `show_following`         TINYINT         NOT NULL DEFAULT 1,
  `show_followers`         TINYINT         NOT NULL DEFAULT 1,
  `play_history_visibility` TINYINT        NOT NULL DEFAULT 2 COMMENT '0所有人 1互关 2仅自己',
  `read_receipt`           TINYINT         NOT NULL DEFAULT 1,
  `new_song_notify`        TINYINT         NOT NULL DEFAULT 1,
  `system_notify`          TINYINT         NOT NULL DEFAULT 1,
  `create_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time`            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `create_by`              BIGINT UNSIGNED DEFAULT NULL,
  `update_by`              BIGINT UNSIGNED DEFAULT NULL,
  `deleted`                TINYINT         NOT NULL DEFAULT 0,
  `version`                INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_privacy_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户隐私设置';

-- -----------------------------------------------------------------------------
-- 27. 词典与中文分词域（模块 11）
--     IK 词典内容由 MySQL 维护，通过 /api/v1/internal/dict/ik 暴露给 ES 远程词典；
--     应用层降级搜索复用同一份快照，保证「ES 没装也能用同义词/停用词/简繁」。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ik_dict_word` (
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

CREATE TABLE IF NOT EXISTS `t_ik_stopword` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(64)     NOT NULL,
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ik_stopword` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IK 停用词（只用于查询分析）';

CREATE TABLE IF NOT EXISTS `t_synonym` (
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

CREATE TABLE IF NOT EXISTS `t_pinyin_dict` (
  `id`          BIGINT UNSIGNED NOT NULL,
  `word`        VARCHAR(64)     NOT NULL COMMENT '多音字词',
  `pinyin`      VARCHAR(64)     NOT NULL COMMENT '无声调小写拼音，如 chongqing',
  `enabled`     TINYINT         NOT NULL DEFAULT 1,
  `create_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pinyin_dict_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拼音多音字词典';

CREATE TABLE IF NOT EXISTS `t_dict_change_log` (
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

CREATE TABLE IF NOT EXISTS `t_dict_sync_log` (
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
-- 28. 播放体验升级域（模块 12）
--     音质偏好、倍速、自动降质、省流量等设置落一张 JSON 化的宽表；
--     设备表用于「跨设备续播」与「设备管理」。播放进度本身仍以 Redis Hash 为主
--     （ml:play:progress:{userId}:{songId}），DB 的 last_position 只做持久化兜底。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user_play_setting` (
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

CREATE TABLE IF NOT EXISTS `t_user_device` (
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
