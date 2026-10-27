# 数据库初始化

## 1. 前置

- MySQL 8.0+（本机 3306），字符集 `utf8mb4` / 排序规则 `utf8mb4_0900_ai_ci`
- 账号需要具备 `CREATE DATABASE` 权限（`schema.sql` 会自行建库 `newmusic`）

## 2. 执行顺序

```bash
# 方式 A：命令行（推荐，可重复执行）
mysql -uroot -p < sql/schema.sql
mysql -uroot -p < sql/data.sql

# 方式 B：Navicat / DataGrip 打开文件后依次执行
```

两个脚本都是**幂等**的：`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，重复执行不会报错、不会产生重复数据。

### 既有库升级到模块 09（管理后台）

已经跑过模块 01~08 的库，执行一次：

```bash
mysql -uroot -p < sql/09-admin-seed.sql
```

该脚本只做三件事：给 `t_user` 条件补 `force_change_password` 列、`CREATE TABLE IF NOT EXISTS`
后台相关表（含 `t_admin_*`、审核、举报、日志、配置历史）、`INSERT IGNORE` 后台角色/权限/演示账号。
所有对象都显式带 `newmusic.` 库名——本机 3306 是共享实例，脚本不会触碰其他项目的库。

### 既有库升级到模块 10 / 11 / 12

```bash
mysql -uroot -p < sql/10-social-seed.sql
mysql -uroot -p < sql/11-ik-seed.sql
mysql -uroot -p < sql/12-play-seed.sql
```

- `10-social-seed.sql`：12 张社交表 + 内容表互动计数列，不含行为种子数据。
- `11-ik-seed.sql`：6 张词典表（`t_ik_dict_word` / `t_ik_stopword` / `t_synonym` /
  `t_pinyin_dict` / `t_dict_change_log` / `t_dict_sync_log`）、`admin:dict:view|edit|reindex`
  权限与演示词库。全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，可重复执行。
  自动词条 ID 约定：歌手 `2×10^18 + t_artist.id`，歌曲 `3×10^18 + t_song.id`，
  两个偏移不能复用（两张表的自增 ID 会重叠）。
- `12-play-seed.sql`：播放设置 `t_user_play_setting` 与设备 `t_user_device` 两张表。
  所有对象显式带 `newmusic.` 前缀。注意 `lyric_mode` 为 `VARCHAR(32)`：
  默认值 `original_translation` 有 20 个字符，`VARCHAR(16)` 会报 MySQL 1067。

模块 12 涉及的 Redis 键（全部 `ml:` 前缀，与同实例其他项目的 `ranking:*` 互不影响）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:play:setting:{userId}` | String(JSON) | 7 天 + 抖动 | 播放设置缓存 |
| `ml:play:quality:{userId}` | String | 永久 | 音质选择（PRD 预留） |
| `ml:device:{userId}` | ZSet | 30 天 | 设备最近活跃列表 |
| `ml:play:progress:{userId}:{songId}` | Hash | 30 天 | 跨设备续播进度 |

### 既有库升级到模块 13（音乐房升级）

```bash
mysql -uroot -p < sql/13-room-upgrade-seed.sql
```

- `t_room_queue` 条件新增 `priority` / `vote_count` / `order_index` 与
  `idx_queue_room_order`，并按 `priority*10000 + vote_count*100 + (1000-seq)` 回填旧数据。
- 新建 `t_room_vote`（投票切歌，`unique(room_id, song_id)` 保证同歌一次）与
  `t_room_rank_hidden`（榜单隐私开关）。
- 所有 DDL 都是条件执行，可重复运行；全部对象显式带 `newmusic.` 库名。

模块 13 的 Redis 键（同样全部 `ml:` 前缀）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:room:play:{roomId}` | Hash | 24h | 权威播放态（毫秒制，兼容秒制字段） |
| `ml:room:ver:{roomId}` | String | 24h | 播放态版本号（PRD 写作 `ml:room:play:version:`，沿用模块 04 的键） |
| `ml:room:vote:{roomId}:{songId}` | Hash | 60s | 投票计数、截止时间、状态 |
| `ml:room:vote:voters:{roomId}:{songId}` | Set | 60s | 已投票用户（幂等） |
| `ml:room:vote:active` | Set | — | 待过期扫描的投票 token |
| `ml:room:vote:cooldown:{roomId}` | String | 5 分钟 | 同一房间发起冷却 |
| `ml:room:rank:{type}:{roomId}` | ZSet | — | 礼物/活跃/点歌/收听榜 |
| `ml:room:rank:hidden:{roomId}` | Set | — | 选择不上榜的用户 |
| `ml:room:like:{roomId}:{songId}` | String | — | 当前歌曲点赞数 |
| `ml:room:like:{roomId}:{songId}:voters` | Set | — | 每人每歌一次 |
| `ml:room:listen:last:{roomId}:{userId}` | String | — | 收听上报截断基准 |

### 既有库升级到模块 14（AI 能力增强）

```bash
mysql -uroot -p < sql/14-ai-seed.sql
```

- 新建 4 张表：`t_song_emotion`（歌曲情绪/场景标注，`unique(song_id)`）、
  `t_ai_call_log`（调用日志与成本，只增不改）、`t_ai_annotation_fail`（失败重试队列）、
  `t_ai_feedback`（译文质量反馈）。
- 新增 `admin:ai:view` / `admin:ai:edit` 两个后台权限并授权给 SUPER_ADMIN / ADMIN。
- 写入 6 个 `ai.*` 系统配置：日预算、告警阈值、场景预算、模型路由、缓存开关、敏感词。
  **API Key 不入库**，只从环境变量 `AI_API_KEY` 读取；`SysConfigService` 也会拒绝 secret 类键。
- 演示歌曲 `1000000000000005101`（英文、有歌词）与 `...5102`（英文、无歌词）用于走查
  「生成译文」与「无歌词 60006」；`verify-14` 会在开头/结尾清空这两首的 AI 数据，可重复执行。

模块 14 的 Redis 键（同样全部 `ml:` 前缀，绝不触碰 `ranking:*`）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:ai:cache:{sha256}` | String(JSON) | 按场景（-1 永久 / 0 不缓存） | 网关结果缓存，指纹含模型+Prompt 版本+输入+参数；调用方显式给 `cacheKey` 时为 `ml:ai:cache:custom:{scene}:{key}` |
| `ml:ai:cost:{yyyyMMdd}` | String | 到零点 | 当日总成本（分） |
| `ml:ai:cost:{yyyyMMdd}:{scene}` | String | 到零点 | 当日场景成本（分） |
| `ml:ai:budget:paused` | String | 到零点 | 日预算熔断标记；调高预算时清除 |
| `ml:ai:budget:alert:{yyyyMMdd}` | String | 到零点 | 80% 告警只写一次 |
| `ml:ai:rate:{userId}:{scene}:h:{yyyyMMddHH}` | String | 2h | 用户小时额度 |
| `ml:ai:rate:{userId}:{scene}:d:{yyyyMMdd}` | String | 到次日 | 用户日额度 |
| `ml:ai:rate:global:{epochSecond}` | String | 2s | 全局 QPS |
| `ml:ai:rate:scene:{scene}:{epochSecond}` | String | 2s | 场景 QPS |
| `ml:ai:lyric:trans:{songId}` | String | 永久 | 已生成译文（BR-23-019） |
| `ml:ai:job:{jobId}` | Hash | 24h | 批量任务进度 |
| `ml:ai:emotion:{songId}` / `ml:ai:playlist:gen:{...}` / `ml:ai:lyric:summary:{...}` | String | 预留 | P2 场景占位，当前代码未写入 |

### 既有库升级到模块 15（推荐系统升级）

```bash
mysql -uroot -p < sql/15-rec-upgrade-seed.sql
```

- 新建 6 张表：
  - `t_rec_config`：按 `scene + config_type` 保存 RECALL / FUSION / RANK / RERANK / EXPERIMENT
    配置的多版本 JSON，`status` 0 草稿 / 1 生效 / 2 历史，更新即新增版本、回滚即原地激活目标版本；
  - `t_experiment`：实验（流量、分组比例、层与层容量、互斥组、`whitelist_json`、审核/运行/归档状态）；
  - `t_rec_log`：每次推荐请求的 traceId、耗时、降级原因、召回摘要、最终列表；
  - `t_rec_event`：曝光/点击/播放/完播/有效播放等埋点，服务端按 trace 快照归因实验分组；
  - `t_rec_metric_daily`：按日 × 场景 × 实验 × 分组的指标汇总；
  - `t_rec_feature_consistency`：在线/离线特征一致性抽检记录。
- 新增权限 `admin:rec:view` / `admin:rec:edit` / `admin:exp:view` / `admin:exp:edit` /
  `admin:exp:approve`（ID 9120~9124），并授权给 SUPER_ADMIN / ADMIN（9486~9497）。
- 写入 5 条默认配置：15 源召回、WRF 融合 Top500、粗排 150、10 个重排算子、
  实验最小样本 10000 / α=0.05。全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，可重复执行，
  且所有对象显式带 `newmusic.` 库名。

模块 15 的 Redis 键（同样全部 `ml:` 前缀，绝不触碰 `ranking:*`）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:rec:config:{scene}:{type}` | String(JSON) | 10s | 配置缓存，后台修改后最迟 10 秒生效 |
| `ml:rec:recall:status:{yyyyMMdd}` | Hash | 到次日 | 各召回源 calls/fail/empty/totalCost/last* |
| `ml:rec:metric:{yyyyMMdd}:{scene}:{exp}:{group}` | Hash | 保留期 | 当日曝光/点击/播放/完播计数 |
| `ml:rec:exp:assign:{userId}` | String | 实验期 | 用户实验分组（停止实验时清空） |
| `ml:rec:exp:meta:{experimentKey}` | String(JSON) | 30 分钟 | 实验配置/白名单元数据缓存 |
| `ml:rec:explore:{userId}` / `ml:rec:explore:{userId}:{yyyyMMdd}` | Set | 7 天 / 24h | 已探索歌曲，避免 7 天内重复 |
| `ml:rec:explore:optout` | Set | — | 主动关闭探索的用户 |
| `ml:rec:negative:{userId}` | Set | 30 天 | 负反馈歌曲（不参与探索/召回） |
| `ml:rec:newsong:exposure:{songId}` | String | 24h | 新歌当日曝光计数 |
| `ml:rec:newsong:user:{userId}:{yyyyMMdd}` | Set | 24h | 用户当日新歌曝光数（每人上限） |
| `ml:rec:newsong:stopped:{yyyyMMdd}` / `ml:rec:newsong:promoted:{yyyyMMdd}` | Set | 24h | 当日停止扶持 / 已扶持歌曲 |
| `ml:rec:fallback:hot` | ZSet | 5 分钟 | 召回全失败时的兜底热门榜 |
| `ml:rec:evaluate:{taskId}` | String(JSON) | 7 天 | 离线评估档案 |
| `ml:rec:trace:{traceId}` | String(JSON) | 1 小时 | 推荐链路快照，埋点按此归因实验分组 |

> 验收脚本 `scripts/verify-15.mjs` 只会读取/删除自己创建的用户、实验、日志、trace 与
> 带探针用户 ID 的 `ml:*` 键；开头与结尾都会比对 `ranking:*` 键集合以及其他库的表数量。

### 既有库升级到模块 16（社交能力升级）

```bash
mysql -uroot -p < sql/16-social-upgrade-seed.sql
```

- `t_conversation` 增加 `type`（1 单聊 / 2 群聊）、`group_name`、`group_avatar`、`owner_id`、
  `member_count`；群聊沿用 `user_a_id = owner_id`、`user_b_id = conversationId`，避免破坏模块 10
  的 `uk(user_a_id, user_b_id)`。
- `t_private_message` 增加 `quote_msg_id`、`forwarded`、`mentioned_user_ids`。
- 新建 12 张表：
  - `t_user_profile` / `t_user_profile_log`：资料与修改留痕；
  - `t_user_post` / `t_post_mention` / `t_post_like` / `t_post_collect`：动态、@ 关系与互动；
  - `t_feed_inbox`：关注流收件箱落库备份（Redis 为加速层）；
  - `t_topic`：动态话题；
  - `t_user_relation_score`：亲密度（`user_id` → `target_user_id` 单向累计）；
  - `t_fans_rank`：粉丝榜每日快照；
  - `t_notification_aggregate`：通知聚合；
  - `t_conversation_member`：群成员与单聊的置顶/归档/免打扰/已读位置。
- 新增后台权限 9130~9132（动态审核/删除、话题管理）并授权给 SUPER_ADMIN / ADMIN（9498~9504）。
- 全部 `CREATE TABLE IF NOT EXISTS` / 条件 `ADD COLUMN` / `INSERT IGNORE`，可重复执行；
  所有对象显式带 `newmusic.` 库名。

模块 16 的 Redis 键（全部 `ml:` 前缀）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:feed:inbox:{userId}` | ZSet | 30 天 | 关注流收件箱（粉丝数低于 5000 时发布直推） |
| `ml:feed:outbox:{userId}` | ZSet | 30 天 | 大 V 发件箱，粉丝读取时拉取 |
| `ml:feed:built:{userId}` | String | 30 天 | 收件箱是否已按关注列表回填 |
| `ml:feed:last_read:{userId}` | String | 30 天 | 上次已读位置（用于 `newCount`） |
| `ml:post:seen:{userId}` | Hash | 7 天 | 动态曝光次数（≥3 次退出发现流） |
| `ml:post:dislike:{userId}` | Set | 30 天 | 不感兴趣的动态，30 天内不再推荐 |
| `ml:post:dedup:{userId}` | String | 24 小时 | 发布内容去重 |
| `ml:post:rate:{userId}:m/h/d:{窗口}` | String | 1 分钟 / 1 小时 / 1 天 | 发布频率 |
| `ml:relation:daily:{userId}:{targetId}:{yyyyMMdd}` | String | 2 天 | 亲密度单日单目标上限 |
| `ml:relation:rec:ignore:{userId}` | Set | 30 天 | 推荐关注的「不感兴趣」名单 |
| `ml:msg:spam:{senderId}:{指纹}:{yyyyMMdd}` | Set | 2 天 | 相同内容触达的会话 ID 集合（>10 个会话触发限制） |
| `ml:msg:spam:restrict:{senderId}` | String | 7 天 | 营销私信限制标记 |
| `ml:notify:agg:{userId}:{聚合键}` | Hash | 按通知类型 | 通知聚合计数与去重 |
| `ml:profile:view:{userId}:{yyyyMMdd}` | String | 2 天 | 主页浏览计数 |
| `ml:profile:edit:{userId}:{字段}:{yyyyMMdd}` | String | 1 天 | 资料编辑频率 |
| `ml:topic:hot` | ZSet | 1 小时 | 话题热度 |

> `scripts/verify-16.mjs` 只注册 4 个带随机后缀的用户，并按精确 ID 清理动态、会话、成员关系、
> 亲密度、粉丝榜与 `ml:*` 键；开头/结尾比对 `ranking:*` 键集合与 `emp_db` / `music_db` 表数量。

### 既有库升级到模块 17（数据平台与运营分析）

```bash
mysql -uroot -p < sql/17-data-platform-seed.sql
```

- 新建 16 张表：
  - 埋点：`t_event_def`（定义与版本）、`t_track_event`（ODS 明细）、`t_track_dirty`（脏数据隔离）；
  - 指标：`t_metric_def`（唯一口径与口径版本）、`t_metric_change_log`（变更审批）；
  - 画像与人群：`t_user_profile_tag`、`t_audience`；
  - 治理：`t_data_quality_record`、`t_reconciliation`、`t_data_lineage`；
  - 报表：`t_data_report`、`t_report_subscription`；
  - 分层：`t_dwd_play_detail`、`t_dws_user_daily`、`t_dws_song_daily`、`t_ads_daily_metrics`。
- 新增权限 9200~9203（`admin:data:view|edit|approve|export`），授权 9505~9513 给
  SUPER_ADMIN / ADMIN / OPERATOR。
- 写入 42 条埋点定义、22 个指标口径、15 条表级 / 字段级血缘。
- `t_metric_def.version` 是**口径版本**（如 `v1.0`），不是乐观锁字段；该实体因此不继承 `BaseEntity`。
- 全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，可重复执行；对象显式带 `newmusic.` 库名。

模块 17 的 Redis 键（全部 `ml:` 前缀，绝不触碰 `ranking:*`）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:stat:{metric}:{yyyyMMdd}` | HyperLogLog / String / ZSet / Hash | 到次日（可配） | 实时指标：DAU / DAU-Play / 房间与互动用户用 HLL，计数用 INCR，热门歌曲 / 活跃房间用 ZSet，DAU-Play 明细用 Hash |
| `ml:track:dedup:{eventId}` | String | 5 分钟（可配） | 埋点幂等账本；Redis 不可用时由 `t_track_event.uk_track_event_id` 兜底 |
| `ml:track:rate:{subject}:{minute}` | String | 70 秒 | 单设备 / 单用户采集限流（固定分钟窗口） |
| `ml:track:ingest:{yyyyMMdd}` | Hash | 实时指标保留期 | 当日接入汇总：received / accepted / dropped / dirty / duplicate / sampled / rate_limited / write_error / last_ts |

> `scripts/verify-17.mjs` 只发送带随机 `RUN_TAG` 的探针事件，结束时按精确键名删除
> `ml:track:dedup:*` / `ml:track:rate:*` 探针键，并比对 `ranking:*` 键集合与 `emp_db` / `music_db`
> 表数量。ODS 探针数据刻意保留：埋点是追加型数据，删掉会破坏对账与留存口径。

### 既有库升级到模块 18（内容安全与合规）

```bash
mysql -uroot -p < sql/18-content-safety-seed.sql
```

- 新建 11 张表：
  - 词库：`t_sensitive_word`、`t_white_word`、`t_audit_rule`、`t_sensitive_word_version`；
  - 审核：`t_content_audit_record`、`t_audit_task`（工作台）、`t_audit_record`（操作轨迹，复用）；
  - 处置：`t_user_restriction`、`t_user_credit`、`t_credit_log`、`t_appeal`（扩展）；
  - 合规：`t_copyright_info`、`t_copyright_complaint`、`t_minor_protection`。
- 新增权限 9210~9223（`admin:audit:view|edit`、`admin:audit:word:*`、`admin:audit:rule:edit`、
  `admin:audit:stats`、`admin:appeal:view|edit`、`admin:user:restrict`、`admin:user:ban:permanent`、
  `admin:user:credit:edit`、`admin:copyright:*`）并授权给三个后台角色；
  `admin:user:ban:permanent` 只给 SUPER_ADMIN。
- 幂等补充列（均先查 `information_schema` 再 `PREPARE/EXECUTE`）：
  - `t_appeal.original_operator_id`、`t_appeal.sla_deadline`、索引 `idx_appeal_target`；
  - `t_playlist.ai_generated` / `ai_model` / `ai_prompt_version` / `ai_generated_at`；
  - `t_user_credit.violation_count_90d`（若旧库没有）。
- 种子：11 个敏感词（L1 全网最低价、L2 加微信、L3 赌博网站/敏感词等）、1 条白名单
  `加微信支付官方`、5 条正则规则（手机号 / QQ / 微信 / URL / 邮箱）。
- 全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，可重复执行；对象显式带 `newmusic.` 库名，
  没有库级 DDL/DML。

模块 18 的 Redis 键（全部 `ml:` 前缀，绝不触碰 `ranking:*`）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:audit:appeal:{userId}:{yyyy-MM-dd}` | String | 2 天 | 每日申诉次数计数（上限 3） |
| `ml:audit:credit:{userId}` | String | 到次日 | 信用分快速读取缓存（DB 为准） |
| `ml:audit:restriction:{userId}` | String | 到次日 | 生效中限制的快速判断缓存 |
| `ml:minor:usage:{userId}:{yyyyMMdd}` | String | 2 天 | 青少年模式当日已用分钟数 |
| `ml:minor:tick:{userId}:{yyyyMMddHHmm}` | String | 90 秒 | 心跳按分钟去重 |

> `scripts/verify-18.mjs` 使用随机 `RUN_TAG` 注册探针账号与词条，结束时按精确键名删除探针 `ml:*` 键，
> 并比对 `ranking:*` 键集合与 `emp_db` / `music_db` 表数量。敏感词版本快照会保留（回滚依赖）。
> **注意**：`t_user_credit` 的库列名是 `violation_count_90d`，实体用 `@TableField` 显式映射；
> 不要依赖 MyBatis-Plus 默认驼峰转换（会得到 `violation_count90d`）。

### 既有库升级到模块 19（高可用与运维）

```bash
mysql -uroot -p < sql/19-ops-seed.sql
```

- 新建 12 张表：
  - 流量治理：`t_ops_rate_limit_rule`（`uk_ops_rate_resource`，每个资源一条规则）、
    `t_degrade_switch`、`t_degrade_event`；
  - 告警：`t_ops_alert_rule`、`t_ops_alert_silence`、`t_ops_alert_record`；
  - 发布容灾：`t_publish_record`、`t_backup_record`、`t_chaos_experiment`；
  - 其他：`t_cost_record`、`t_ops_runbook`、`t_ops_metric_snapshot`。
- 新增 `t_sys_config` 配置 8 个：`ops.rate.limit.enabled`、`ops.degrade.level`、
  `ops.trace.sample-rate`、`ops.alert.eval.enabled`、`ops.metrics.snapshot.enabled`、
  `ops.cost.daily-budget-cent`、`ops.backup.retention-days`、`ops.chaos.enabled`。
- 新增权限 9230~9238（`admin:ops:view|edit|publish|chaos|backup|alert:handle|trace:view|metrics:view`、
  `admin:cost:view`）并授权：SUPER_ADMIN 全部；ADMIN 除 `admin:ops:chaos`；
  OPERATOR 只有 `admin:ops:view` / `admin:cost:view` / `admin:ops:metrics:view`。
- 种子：8 条限流规则（GLOBAL 12000/60s 兜底，PLAY/SEARCH 2400，AI 1200，COMMENT 600，
  POST 300，LOGIN 120）、18 个降级开关（1~5 级，`feature.safety` 级别 99 永不关闭）、
  10 条告警规则、11 条应急预案（P0 5 条）。
- 全部 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` / `ON DUPLICATE KEY UPDATE`；
  对象显式带 `newmusic.` 库名，没有库级 DDL/DML。

模块 19 的 Redis 键（全部 `ml:` 前缀，绝不触碰 `ranking:*`）：

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `ml:limit:ops:{dimension}:{identifier}:{resource}:{api}` | ZSet | 窗口长度 | 运维限流滑动窗口；**资源名必须在键里**，否则 URI 前缀重叠的规则会共用计数器 |
| `ml:limit:search:{dimension}:{identifier}:{api}` | ZSet | 60 秒 | 搜索域限流（模块 19 之前的既有键，格式兼容） |
| `ml:ops:trace:*` | — | — | 链路数据在进程内环形缓冲，不落 Redis |
| `ml:ops:cost:*` | — | — | 成本按日聚合走 DB，不落 Redis |

> 限流键的资源段不是冗余：`/api/v1/player` 会同时命中 PLAY 与 PLAY_REPORT 两条规则，
> 缺资源段时两条规则共用一个窗口，一次请求计两次，阈值相当于被减半。
> `GLOBAL` 维度在服务端特判为 `api=all`、按资源聚合，才是真正的「全站 QPS」，
> 而不是「每个接口各自限流」。
>
> `scripts/verify-19.mjs` 的探针 IP / 账号 / 服务名每轮唯一，结束时恢复降级等级、SEARCH 限流规则、
> 运维配置与采样率，并按精确键名删除探针 `ml:*`。发布、备份、告警、混沌记录属于审计证据，刻意保留。
> 共享实例红线：MySQL 只操作 `newmusic.`，Redis 只读写 `ml:`，前后比对 `ranking:*` 与
> `emp_db` / `music_db` 表数量。

> **启动自检**：`StartupSelfCheck` 会校验上述 12 张表与关键配置，缺表直接启动失败并打印本脚本路径——
> 因为限流/降级/告警静默失效比启动失败更危险。只跑单测时可用
> `newmusic.ops.startup-check-enabled=false` 关闭。

### 既有库升级：审核任务唯一索引（歌词/审核修复，2026-10-26）

```bash
mysql -uroot -p < sql/20-audit-task-open-unique.sql
```

- 给 `t_audit_task` 增加 STORED 生成列 `open_key`：
  `status IN (0,1)` 时为 `task_type:target_id`，其余状态为 `NULL`；
- 增加唯一索引 `uk_audit_open_target (open_key)`。MySQL 允许多个 `NULL`，
  所以已完成/已关闭的历史任务不受影响；
- 加索引前会先把重复的**未完成**任务置为「4 自动关闭」（保留审核轨迹，不物理删除），
  每组只保留 `id` 最大的一条；
- 先查 `information_schema` 再加列/加索引，脚本可重复执行；所有对象显式带 `newmusic.` 库名。

> 应用层 `SongAuditTaskService` 仍保留「先查再插」，用于避免正常路径上的无谓异常；
> 唯一索引只负责并发兜底。启动回填之外，`AdminScheduledJobs.reconcileSongAuditTasks`
> 每 5 分钟补建丢失任务并关闭「歌曲已删除/已审核」的孤儿任务。
> 新库直接执行最新 `schema.sql` 即可，本脚本只用于既有库。

## 3. 应用侧配置

`src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/newmusic?...
    username: root
    password: ${MYSQL_PASSWORD:root}   # 环境变量注入，勿提交真实密码
server:
  port: 8081                            # D-07：本机 8080 被占用
```

启动前设置密码（PowerShell）：

```powershell
$env:MYSQL_PASSWORD = "你的root密码"
mvn spring-boot:run
```

## 4. 种子账号

| 账号 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `admin123` | ROLE_ADMIN + ROLE_USER | 后台管理（除用户删除/角色分配） |
| `operator` | `operator123` | ROLE_OPERATOR + ROLE_USER | 曲库录入/审核，无删除权 |
| `demo` | `demo123456` | ROLE_USER | C 端演示账号，拥有私密歌单 |

### 后台演示账号（`t_admin_*`，与 C 端账号体系完全独立）

| 登录名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `superadmin` | `admin123` | SUPER_ADMIN | 全量权限；已开启 TOTP，演示密钥 `JBSWY3DPEHPK3PXP` |
| `admin` | `admin123` | ADMIN | 除管理员账号/系统配置/危险操作外的管理权限 |
| `operator` | `operator123` | OPERATOR | 看板、运营配置、仅自己的日志 |

后台登录入口 `/api/v1/admin/auth/login`，凭证只走 httpOnly Cookie，不使用
`localStorage` 保存 Token。**生产必须重置这三个账号的密码与 TOTP 密钥。**

密码为 BCrypt（strength=10）密文，如需重新生成：

```bash
mvn -q test -Dtest=PasswordHashGenerator
# 输出 SEED_HASH <账号> <明文> <密文>，复制密文替换 data.sql
```

## 5. ID 约定

| 范围 | 用途 | 说明 |
|---|---|---|
| 1 ~ 4 | `t_role.id` | RBAC 字典表使用小整数（不对外暴露，响应中只出现 `role_code`） |
| 1001 ~ 1100 | `t_permission.id` | 权限点；id 连续，便于角色授权用 `基数 + (perm_id - 1000)` 生成关联 id |
| 20000+/30000+/40000+ | `t_role_permission.id` | 各角色授权块的独立基数，避免主键冲突 |
| 1000000000000002xxx | `t_user.id` | 19 位固定 ID，模拟雪花 |
| 1000000000000003xxx | `t_artist.id` | |
| 1000000000000004xxx | `t_album.id` | |
| 1000000000000005xxx | `t_song.id` | |
| 1000000000000006xxx | `t_lyric.id` | |
| 1000000000000007xxx | `t_category.id` | |
| 1000000000000008xxx | `t_tag.id` | |
| 1000000000000009xxx | `t_song_tag.id` | |
| 1000000000000010xxx | `t_playlist.id` | |
| 1000000000000011xxx | `t_playlist_song.id` | |
| 1000000000000012xxx | `t_sys_config.id` | |

**为什么业务表用 19 位 ID**：ID 是雪花算法生成的 `BIGINT`，超过 JS `Number.MAX_SAFE_INTEGER`（2^53-1）会精度丢失。项目用「阈值 Long 序列化器」把**所有大于 2^53-1 的 Long 序列化为字符串**（AC-10-104），因此种子数据的 ID 也必须 > 2^53-1，否则前端会拿到数字型 ID，与真实数据行为不一致。

## 6. 权限码约定

- 格式 `{模块}:{资源}:{动作}`，如 `music:song:create`
- 资源级通配 `music:artist:*` 在**构建登录态时**由 `PermissionCatalogue.expand()` 展开为具体动作码（`Spring Security` 的 `hasAuthority` 只做精确匹配）
- `*:*:*` 仅 ROLE_SUPER_ADMIN 拥有

## 7. 演示音频

`sql/data.sql` 中歌曲的 `audio_standard` 指向 `/media/demo/demo-0N.wav`（本地演示音频，模块 07 提供生成脚本与静态资源映射）。
生产环境应替换为对象存储 URL（PRD 04）。未放置音频文件时播放器仍可渲染，仅播放失败。

## 8. 与 PRD 的偏差

| 项 | PRD | 本实现 | 原因 |
|---|---|---|---|
| `t_role`/`t_permission` 的 `version` | 未定义 | 增加 `version INT DEFAULT 0` | D-02：实体统一继承 `BaseEntity`，避免为字典表单独抽象基类 |
| 演示音质 | mp3/flac | wav | 环境无 ffmpeg，MVP 用无损占位音频验证播放链路 |
