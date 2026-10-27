package com.example.newmusic.infra.redis;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis Key 命名（BR-12-001：{@code ml:{域}:{子域}:{标识}}）。
 *
 * <p><b>前缀是硬约束，不是风格问题。</b>本机 6379 是共享实例，db0 里躺着另一个项目的
 * {@code ranking:*} 键。所有 key 必须落在 {@code ml:} 下，才可能做到「按前缀清理而不误伤」
 * （系分 D-18）。{@link RedisOps#deleteByPrefix} 会强制校验这一点。
 */
public final class RedisKeys {

    /** 全局前缀。任何新 key 都必须以它开头 */
    public static final String PREFIX = "ml:";

    // ---------- 曲库 ----------
    /** 歌曲详情缓存（String/JSON）。兼作空值缓存，值为 {@code __NULL__} 时表示「确认不存在」 */
    public static final String SONG = PREFIX + "music:song:";
    /** 歌曲 ID 布隆过滤器 */
    public static final String BLOOM_SONG = PREFIX + "bloom:song";
    /** 缓存重建用的分布式锁（Redisson RLock） */
    public static final String LOCK_CACHE_SONG = PREFIX + "lock:cache:song:";

    // ---------- 登录态与会话（FR-12-004） ----------
    /** 已吊销的 accessToken（键为 jti），TTL = 该 token 的剩余有效期 */
    public static final String AUTH_BLACKLIST = PREFIX + "auth:blacklist:";
    /** refreshToken 登记，值 = userId；键一次性，用过即删（轮换 + 重放检测） */
    public static final String AUTH_REFRESH = PREFIX + "auth:refresh:";
    /** 某用户当前持有的 refreshTokenId 集合，用于「撤销该用户全部会话」而不必 SCAN */
    public static final String AUTH_REFRESH_USER = PREFIX + "auth:refresh:user:";
    /** 封禁标记，JWT 过滤器每请求校验（BR-12-023） */
    public static final String AUTH_USERBAN = PREFIX + "auth:userban:";
    /** 登录失败计数，连续 5 次锁定 15 分钟（BR-12-019） */
    public static final String AUTH_FAIL = PREFIX + "auth:fail:";

    // ---------- 限流（FR-12-005） ----------
    /** {@code ml:limit:{type}:{dimension}:{identifier}:{api}}（BR-12-026） */
    public static final String LIMIT = PREFIX + "limit:";

    // ---------- 搜索（模块 06） ----------
    /** 搜索联想缓存（String/JSON）。键是关键词的**前 N 个字符**，不是完整关键词（BR-15-020） */
    public static final String SEARCH_SUGGEST = PREFIX + "search:suggest:";
    /** 搜索历史（List），最多 20 条，去重置顶（BR-15-022/023） */
    public static final String SEARCH_HIS = PREFIX + "search:his:";
    /** 热搜实时计数（ZSet，ZINCRBY 累加，BR-15-028） */
    public static final String SEARCH_HOT_REALTIME = PREFIX + "search:hot:realtime";
    /** 热搜展示快照（String/JSON），5 分钟刷新一次（BR-15-032） */
    public static final String SEARCH_HOT_SNAPSHOT = PREFIX + "search:hot:snapshot";
    /** 上一份热搜快照，只用于算增速标记（BR-15-033），TTL 比 snapshot 长 */
    public static final String SEARCH_HOT_LAST = PREFIX + "search:hot:last";
    /** 刷榜去重：同一主体同一词在窗口内只计一次（EX-15-005-1） */
    public static final String SEARCH_HOT_DEDUP = PREFIX + "search:hot:dedup:";
    /** 「新」词标记：词在 24 小时内首次进榜（BR-15-033） */
    public static final String SEARCH_HOT_SEEN = PREFIX + "search:hot:seen:";

    // ---------- WebSocket（模块 04） ----------
    /**
     * 用户会话映射：{@code nodeId:connectionId}。
     *
     * <p><b>与 PRD 12 章的 {@code ml:ws:session:{userId}} 有一处偏差</b>：PRD 的键只能存
     * 一个设备，而 BR-13-001 允许多设备同时在线。这里追加 deviceId 作为后缀，
     * 每个设备一条（系分 04 D-04）。跨节点定向投递仍然只需读这些键里的 nodeId。
     */
    public static final String WS_SESSION = PREFIX + "ws:session:";
    /** 节点负载（连接数），TTL 60s，定时续期（PRD 12 章） */
    public static final String WS_NODE = PREFIX + "ws:node:";
    /** 客户端消息幂等账本（BR-13-035） */
    public static final String WS_DEDUP = PREFIX + "ws:dedup:";
    /** 跨实例广播频道（D-03：本机用 Redis Pub/Sub 代替不可用的 RocketMQ 广播） */
    public static final String WS_BUS = PREFIX + "ws:bus";
    /** 通知未读数（BR-13-031） */
    public static final String NOTIFY_UNREAD = PREFIX + "notify:unread:";

    // ---------- 房间（模块 04 只落地传输层用到的键；生命周期归模块 05） ----------
    /** 房间在线成员 Set（PRD 12 章 / BR-12-036） */
    public static final String ROOM_ONLINE = PREFIX + "room:online:";
    /** 房间在线数 */
    public static final String ROOM_ONLINE_COUNT = PREFIX + "room:online_count:";
    /** 用户当前所在房间，TTL 90s，心跳续期（BR-13-011） */
    public static final String ROOM_USER = PREFIX + "room:user:";
    /** 房间权威播放态 Hash（BR-13-020） */
    public static final String ROOM_PLAY = PREFIX + "room:play:";
    /** 房间状态版本号（BR-13-021） */
    public static final String ROOM_VER = PREFIX + "room:ver:";
    /** 禁言表 Hash：userId → 到期毫秒时间戳（读取；写入归模块 05） */
    public static final String ROOM_MUTE = PREFIX + "room:mute:";
    /** 黑名单 Set（读取；写入归模块 05） */
    public static final String ROOM_BLACKLIST = PREFIX + "room:blacklist:";
    /**
     * 房主占位键。
     *
     * <p>模块 05 起 {@code t_room.owner_id} 是唯一权威；本键只保留给模块 04 的
     * 演示房间（数据库里不存在的 roomId）做兼容，真实房间不再读取它（系分 05 D-02）。
     */
    public static final String ROOM_OWNER = PREFIX + "room:owner:";

    // ---------- 房间生命周期（模块 05） ----------
    /** 房间号分配序列，初始 99999，首个 INCR 得到 100000（BR-14-007） */
    public static final String ROOM_NO_SEQ = PREFIX + "room:no_seq";
    /** 密码验证通过缓存，30 分钟（BR-14-016） */
    public static final String ROOM_PWD = PREFIX + "room:pwd:";
    /** 密码错误计数，3 次后锁定 5 分钟（EX-14-002-3） */
    public static final String ROOM_PWD_FAIL = PREFIX + "room:pwd:fail:";
    /** 踢出冷却，5 分钟（BR-14-019） */
    public static final String ROOM_KICK = PREFIX + "room:kick:";
    /** 麦位镜像 Hash：pos → userId（权威在 t_room_mic，Redis 供实时读） */
    public static final String ROOM_MIC = PREFIX + "room:mic:";
    /** 排麦等待队列 ZSet：userId → seq（BR-14-033） */
    public static final String ROOM_MIC_QUEUE = PREFIX + "room:mic:queue:";
    /** 播放队列快照（JSON 数组），DB 为权威，变更后重建（系分 05 D-08） */
    public static final String ROOM_QUEUE = PREFIX + "room:queue:";
    /** 队列序号发号器，保证同一房间内 FIFO */
    public static final String ROOM_QUEUE_SEQ = PREFIX + "room:queue:seq:";
    /** 最近访问房间 ZSet：roomId → 访问时间戳（BR-14-020） */
    public static final String ROOM_RECENT = PREFIX + "room:recent:";
    /** 房间热度分缓存（PRD 14 §6） */
    public static final String ROOM_HOT = PREFIX + "room:hot:";
    /** 互动计数（聊天/弹幕/点歌/上麦），热度公式的“互动数”来源 */
    public static final String ROOM_INTERACTION = PREFIX + "room:interaction:";
    /** 麦位分配锁（BR-14-037） */
    public static final String LOCK_ROOM_MIC = PREFIX + "lock:room:mic:";
    /** 控播串行化锁（BR-14-052） */
    public static final String LOCK_ROOM_PLAY = PREFIX + "lock:room:play:";
    /** 投票发起/计票锁（模块 13，BR-22-036/EX-22-004-5） */
    public static final String LOCK_ROOM_VOTE = PREFIX + "lock:room:vote:";

    // ---------- 音乐房升级（模块 13） ----------
    /** 投票元数据 Hash：{@code ml:room:vote:{roomId}:{songId}} */
    public static final String ROOM_VOTE = PREFIX + "room:vote:";
    /** 投票进行中的 key 集合（roomId:songId），供定时过期任务读取 */
    public static final String ROOM_VOTE_ACTIVE = PREFIX + "room:vote:active";
    /** 投票发起冷却 List：房间 5 分钟最多 3 次 */
    public static final String ROOM_VOTE_COOLDOWN = PREFIX + "room:vote:cooldown:";
    /** 房间榜单 ZSet：gift / active / request / listen */
    public static final String ROOM_RANK = PREFIX + "room:rank:";
    /** 选择不上榜的用户 */
    public static final String ROOM_RANK_HIDDEN = PREFIX + "room:rank:hidden:";
    /** 榜单播报节流 */
    public static final String ROOM_RANK_NOTIFY = PREFIX + "room:rank:notify:";
    /** 当前歌曲点赞数 */
    public static final String ROOM_LIKE = PREFIX + "room:like:";
    /** 点赞广播节流 2 秒 */
    public static final String ROOM_LIKE_NOTIFY = PREFIX + "room:like:notify:";
    /** 收听时长上报校验 */
    public static final String ROOM_LISTEN_LAST = PREFIX + "room:listen:last:";

    // ---------- 播放行为（模块 07 的 FR-16-004，模块 08 消费） ----------
    /** 最近播放 ZSet：songId → 时间戳，最多 500 条（BR-16-026） */
    public static final String PLAY_RECENT = PREFIX + "play:user:";
    /** 播放进度 Hash：position/duration/updatedAt/playId，TTL 30 天 */
    public static final String PLAY_PROGRESS = PREFIX + "play:progress:";
    /** 播放上报 5 秒去重（BR-16-012），值 = 已创建的 historyId */
    public static final String PLAY_DEDUP = PREFIX + "play:dedup:";

    // ---------- 播放体验升级（模块 12） ----------
    /** 音质偏好（String），永久（BR-21-002） */
    public static final String PLAY_QUALITY = PREFIX + "play:quality:";
    /** 播放地址缓存（String/JSON），{@code songId:quality}，TTL 1h（BR-21-013） */
    public static final String PLAY_URL = PREFIX + "play:url:";
    /** 播放设置缓存（String/JSON），TTL 7 天（BR-21-075） */
    public static final String PLAY_SETTING = PREFIX + "play:setting:";
    /** 已下载歌曲 ID（Set，P2 预留） */
    public static final String PLAY_DOWNLOAD = PREFIX + "play:download:";
    /** 用户设备 ZSet：deviceId → 最后活跃时间戳（BR-21-071） */
    public static final String DEVICE = PREFIX + "device:";

    // ---------- AI 能力增强（模块 14） ----------
    /** AI 结果缓存：指纹 = sha256(模型 + Prompt 版本 + 提示词 + 参数)（BR-23-014） */
    public static final String AI_CACHE = PREFIX + "ai:cache:";
    /** 当日累计成本（分）：{@code ml:ai:cost:{yyyyMMdd}} */
    public static final String AI_COST = PREFIX + "ai:cost:";
    /** 用户级限流：{@code ml:ai:rate:{userId}:{scene}} 的公共前缀 */
    public static final String AI_RATE = PREFIX + "ai:rate:";
    /** 全局 QPS：{@code ml:ai:rate:global:{epochSecond}} */
    public static final String AI_RATE_GLOBAL = PREFIX + "ai:rate:global:";
    /** 场景 QPS：{@code ml:ai:rate:scene:{scene}:{epochSecond}} */
    public static final String AI_RATE_SCENE = PREFIX + "ai:rate:scene:";
    /** 日预算熔断标志（至次日 00:00） */
    public static final String AI_BUDGET_PAUSED = PREFIX + "ai:budget:paused";
    /** 80% 预算告警去重 */
    public static final String AI_BUDGET_ALERT = PREFIX + "ai:budget:alert:";
    /** 歌词翻译缓存（永久，随歌曲下架清除） */
    public static final String AI_LYRIC_TRANS = PREFIX + "ai:lyric:trans:";
    /** 歌词摘要缓存 */
    public static final String AI_LYRIC_SUMMARY = PREFIX + "ai:lyric:summary:";
    /** 情绪标注缓存 */
    public static final String AI_EMOTION = PREFIX + "ai:emotion:";
    /** AI 歌单生成结果（24h） */
    public static final String AI_PLAYLIST = PREFIX + "ai:playlist:gen:";
    /** 离线任务进度（7 天，支持断点续跑） */
    public static final String AI_JOB_PROGRESS = PREFIX + "ai:job:";

    // ---------- 管理后台（模块 09） ----------
    /** 图形验证码：{@code ml:admin:captcha:{captchaId}}，TTL 2 分钟 */
    public static final String ADMIN_CAPTCHA = PREFIX + "admin:captcha:";
    /** 后台 refreshToken 账本（一次性） */
    public static final String ADMIN_REFRESH = PREFIX + "admin:refresh:";
    /** 某管理员持有的 refreshTokenId 集合 */
    public static final String ADMIN_REFRESH_USER = PREFIX + "admin:refresh:user:";
    /** 后台 accessToken 吊销黑名单 */
    public static final String ADMIN_BLACKLIST = PREFIX + "admin:blacklist:";
    /**
     * 后台会话版本号。禁用账号/改密/强退时 INCR；JWT 里带 {@code sv}，
     * 过滤器逐请求比对，实现「1 小时 accessToken 也能立即失效」。
     */
    public static final String ADMIN_SESSION_VER = PREFIX + "admin:session:ver:";
    /** 后台登录失败窗口计数（库内 fail_count 是权威，这里只做滑动窗口） */
    public static final String ADMIN_LOGIN_FAIL = PREFIX + "admin:login:fail:";
    /** 禁止房主创建房间的标记（模块 09 房间治理），值=原因 */
    public static final String ADMIN_ROOM_CREATE_BAN = PREFIX + "admin:room:createban:";

    // ---------- 推荐（模块 08） ----------
    /** 每日推荐，Value=JSON，TTL 48h（BR-17-009） */
    public static final String REC_DAILY = PREFIX + "rec:daily:";
    /** 每日推荐理由（PRD 用 Hash；本实现把理由放在 JSON 里，保留独立前缀便于排查） */
    public static final String REC_DAILY_REASONS = PREFIX + "rec:daily:reasons:";
    /** 猜你喜欢，Value=JSON，TTL 30min */
    public static final String REC_GUESS = PREFIX + "rec:guess:";
    /** 相似歌曲缓存，Value=JSON，TTL 7d */
    public static final String REC_SIMILAR = PREFIX + "rec:similar:";
    /** 新人推荐，Value=JSON，TTL 1h */
    public static final String REC_NEWCOMER = PREFIX + "rec:newcomer:";
    /** 最近常听歌手，Value=JSON，TTL 1h */
    public static final String REC_FREQUENT = PREFIX + "rec:frequent:";
    /** 不喜欢的歌曲 Set（永久） */
    public static final String REC_DISLIKE = PREFIX + "rec:dislike:";
    /** 不喜欢的歌手 Set（永久） */
    public static final String REC_DISLIKE_ARTIST = PREFIX + "rec:dislike:artist:";
    /** 点赞歌曲 Set（用于判断是否需要累加 like_count） */
    public static final String REC_LIKE = PREFIX + "rec:like:";
    /** 屏蔽态已从 DB 回放到 Redis 的标记，避免每次请求重放反馈日志 */
    public static final String REC_BLOCK_LOADED = PREFIX + "rec:block:loaded:";
    /** 当日曝光 Set，TTL 24h（BR-17-003） */
    public static final String REC_EXPOSURE = PREFIX + "rec:exposure:";
    /** 继续播放里被用户移除的歌曲 Set，TTL 30d（BR-17-058） */
    public static final String REC_CONTINUE_HIDDEN = PREFIX + "rec:continue:hidden:";
    /** 全站热门歌曲 ZSet，TTL 1h（PRD 17 Redis 清单） */
    public static final String REC_HOT_SONGS = PREFIX + "rec:hot:songs";

    // ---------- 推荐升级（模块 15） ----------
    /** 场景配置：{@code ml:rec:config:{scene}:{type}}（String/JSON，热更新时删除） */
    public static final String REC_UPGRADE_CONFIG = PREFIX + "rec:config:";
    /** 模型参数：{@code ml:rec:model:{version}}（Hash，热加载） */
    public static final String REC_MODEL = PREFIX + "rec:model:";
    /** 兜底热门池（String/JSON），召回全部失败时使用（EX-24-001-5） */
    public static final String REC_FALLBACK_HOT = PREFIX + "rec:fallback:hot";
    /** 召回源健康度：{@code ml:rec:recall:status:{yyyyMMdd}}（Hash：source → JSON） */
    public static final String REC_RECALL_STATUS = PREFIX + "rec:recall:status:";
    /** 指标计数：{@code ml:rec:metric:{yyyyMMdd}:{scene}:{exp}:{group}}（Hash） */
    public static final String REC_METRIC = PREFIX + "rec:metric:";
    /** 实验分组缓存：{@code ml:rec:exp:assign:{userId}}（Hash：experimentKey → group） */
    public static final String REC_EXP_ASSIGN = PREFIX + "rec:exp:assign:";
    /** 实验元数据缓存：{@code ml:rec:exp:meta:{experimentKey}}（String/JSON） */
    public static final String REC_EXP_META = PREFIX + "rec:exp:meta:";
    /** 今日被强制探索的歌曲：{@code ml:rec:explore:{userId}:{yyyyMMdd}}（Set） */
    public static final String REC_EXPLORE = PREFIX + "rec:explore:";
    /** 关闭个性化探索的用户 Set（BR-24-068） */
    public static final String REC_EXPLORE_OPT_OUT = PREFIX + "rec:explore:optout";
    /** 负反馈歌曲：{@code ml:rec:negative:{userId}}（Set，30 天） */
    public static final String REC_NEGATIVE = PREFIX + "rec:negative:";
    /** 新歌曝光计数：{@code ml:rec:newsong:exposure:{songId}} */
    public static final String REC_NEWSONG_EXPOSURE = PREFIX + "rec:newsong:exposure:";
    /** 单用户单日新歌曝光计数：{@code ml:rec:newsong:user:{userId}:{yyyyMMdd}} */
    public static final String REC_NEWSONG_USER = PREFIX + "rec:newsong:user:";
    /** 新歌停止推广标记：{@code ml:rec:newsong:stopped:{yyyyMMdd}}（Set：songId） */
    public static final String REC_NEWSONG_STOPPED = PREFIX + "rec:newsong:stopped:";
    /** 当日已扶持新歌：{@code ml:rec:newsong:promoted:{yyyyMMdd}}（Set：songId，上限 50） */
    public static final String REC_NEWSONG_PROMOTED = PREFIX + "rec:newsong:promoted:";
    /** 特征：{@code ml:feat:user:{userId}} / {@code ml:feat:song:{songId}}（Hash，TTL 25h / 10min） */
    public static final String FEATURE = PREFIX + "feat:";
    /** 离线评估任务结果：{@code ml:rec:evaluate:{taskId}}（String/JSON） */
    public static final String REC_EVALUATE = PREFIX + "rec:evaluate:";
    /** 推荐链路快照：{@code ml:rec:trace:{traceId}}（String/JSON，TTL 1h，埋点归因用） */
    public static final String REC_TRACE = PREFIX + "rec:trace:";

    // ---------- 数据平台（模块 17） ----------
    /** 实时统计键前缀：{@code ml:stat:{metric}:{yyyyMMdd}}。 */
    public static final String STAT = PREFIX + "stat:";
    /** 埋点幂等账本（String，5 分钟）。 */
    public static final String TRACK_DEDUP = PREFIX + "track:dedup:";
    /** 采集网关单设备限流（固定窗口）。 */
    public static final String TRACK_RATE = PREFIX + "track:rate:";
    /** 采集链路当天汇总（Hash：received/accepted/dropped/dirty/duplicate/sampled）。 */
    public static final String TRACK_INGEST = PREFIX + "track:ingest:";

    private RedisKeys() {
    }

    /** 实时指标键：{@code ml:stat:{metric}:{yyyyMMdd}}。 */
    public static String stat(String metric, String date) {
        return STAT + metric + ':' + date;
    }

    public static String trackDedup(String eventId) {
        return TRACK_DEDUP + eventId;
    }

    public static String trackRate(String deviceId) {
        return TRACK_RATE + deviceId;
    }

    public static String trackIngest(String date) {
        return TRACK_INGEST + date;
    }

    public static String song(Long songId) {
        return SONG + songId;
    }

    public static String lockCacheSong(Long songId) {
        return LOCK_CACHE_SONG + songId;
    }

    public static String blacklist(String jti) {
        return AUTH_BLACKLIST + jti;
    }

    public static String refresh(String refreshTokenId) {
        return AUTH_REFRESH + refreshTokenId;
    }

    public static String refreshOfUser(Long userId) {
        return AUTH_REFRESH_USER + userId;
    }

    public static String userBan(Long userId) {
        return AUTH_USERBAN + userId;
    }

    public static String loginFail(String account) {
        return AUTH_FAIL + account;
    }

    /**
     * 限流键。{@code api} 建议用「HTTP 方法 + 路径」，因为键按 api 分片意味着
     * 「IP 级 300/分钟」是**每个接口**各自 300，而不是全站合计（BR-12-026）。
     */
    public static String limit(String type, String dimension, String identifier, String api) {
        return LIMIT + type + ':' + dimension + ':' + identifier + ':' + api;
    }

    /**
     * 联想缓存键。传入的应当是**已经截断到 prefixLength 的前缀**（BR-15-020），
     * 这样「周杰伦的歌」与「周杰伦的曲」共用一份缓存。
     */
    public static String searchSuggest(String prefix) {
        return SEARCH_SUGGEST + prefix;
    }

    public static String searchHistory(Long userId) {
        return SEARCH_HIS + userId;
    }

    /** {@code subject} 形如 {@code u:123} / {@code ip:1.2.3.4}；{@code keywordHash} 用定长摘要避免键里塞长词。 */
    public static String searchHotDedup(String subject, String keywordHash) {
        return SEARCH_HOT_DEDUP + subject + ':' + keywordHash;
    }

    public static String searchHotSeen(String keyword) {
        return SEARCH_HOT_SEEN + keyword;
    }

    // ---------- 模块 04 的键构造 ----------

    /** 每设备一条会话映射，见 {@link #WS_SESSION} 的偏差说明。 */
    public static String wsSession(Long userId, String deviceId) {
        return WS_SESSION + userId + ':' + deviceId;
    }

    public static String wsNode(String nodeId) {
        return WS_NODE + nodeId;
    }

    public static String wsDedup(Long userId, String clientMsgId) {
        return WS_DEDUP + userId + ':' + clientMsgId;
    }

    /** 总未读数（String）。PRD 16 把 {@code ml:notify:unread:{userId}} 定义为分类 Hash，总数额外放这里。 */
    public static String notifyUnread(Long userId) {
        return NOTIFY_UNREAD + "total:" + userId;
    }

    public static String roomOnline(Long roomId) {
        return ROOM_ONLINE + roomId;
    }

    public static String roomOnlineCount(Long roomId) {
        return ROOM_ONLINE_COUNT + roomId;
    }

    public static String roomUser(Long userId) {
        return ROOM_USER + userId;
    }

    public static String roomPlay(Long roomId) {
        return ROOM_PLAY + roomId;
    }

    public static String roomVersion(Long roomId) {
        return ROOM_VER + roomId;
    }

    public static String roomMute(Long roomId) {
        return ROOM_MUTE + roomId;
    }

    public static String roomBlacklist(Long roomId) {
        return ROOM_BLACKLIST + roomId;
    }

    public static String roomOwner(Long roomId) {
        return ROOM_OWNER + roomId;
    }

    // ---------- 模块 05 的键构造 ----------

    public static String roomPwd(Long roomId, Long userId) {
        return ROOM_PWD + roomId + ':' + userId;
    }

    public static String roomPwdFail(Long roomId, Long userId) {
        return ROOM_PWD_FAIL + roomId + ':' + userId;
    }

    public static String roomKick(Long roomId, Long userId) {
        return ROOM_KICK + roomId + ':' + userId;
    }

    public static String roomMic(Long roomId) {
        return ROOM_MIC + roomId;
    }

    public static String roomMicQueue(Long roomId) {
        return ROOM_MIC_QUEUE + roomId;
    }

    public static String roomQueue(Long roomId) {
        return ROOM_QUEUE + roomId;
    }

    public static String roomQueueSeq(Long roomId) {
        return ROOM_QUEUE_SEQ + roomId;
    }

    public static String roomRecent(Long userId) {
        return ROOM_RECENT + userId;
    }

    public static String roomHot(Long roomId) {
        return ROOM_HOT + roomId;
    }

    public static String roomInteraction(Long roomId) {
        return ROOM_INTERACTION + roomId;
    }

    public static String lockRoomMic(Long roomId) {
        return LOCK_ROOM_MIC + roomId;
    }

    public static String lockRoomPlay(Long roomId) {
        return LOCK_ROOM_PLAY + roomId;
    }

    // ---------- 模块 13 的键构造 ----------

    public static String lockRoomVote(Long roomId) {
        return LOCK_ROOM_VOTE + roomId;
    }

    public static String roomVote(Long roomId, Long songId) {
        return ROOM_VOTE + roomId + ':' + songId;
    }

    public static String roomVoteVoters(Long roomId, Long songId) {
        return ROOM_VOTE + "voters:" + roomId + ':' + songId;
    }

    public static String roomVoteCooldown(Long roomId) {
        return ROOM_VOTE_COOLDOWN + roomId;
    }

    /** {@code type} 取 gift/active/request/listen。 */
    public static String roomRank(Long roomId, String type) {
        return ROOM_RANK + type + ':' + roomId;
    }

    public static String roomRankHidden(Long roomId) {
        return ROOM_RANK_HIDDEN + roomId;
    }

    public static String roomRankNotify(Long roomId, String type) {
        return ROOM_RANK_NOTIFY + roomId + ':' + type;
    }

    public static String roomLike(Long roomId, Long songId) {
        return ROOM_LIKE + roomId + ':' + songId;
    }

    /** 点赞去重集合：同一用户对同一首歌只计一次。 */
    public static String roomLikeVoters(Long roomId, Long songId) {
        return ROOM_LIKE + roomId + ':' + songId + ":voters";
    }

    public static String roomLikeNotify(Long roomId) {
        return ROOM_LIKE_NOTIFY + roomId;
    }

    public static String roomListenLast(Long roomId, Long userId) {
        return ROOM_LISTEN_LAST + roomId + ':' + userId;
    }

    // ---------- 模块 07/08 的键构造 ----------

    /** 最近播放 ZSet：{@code ml:play:user:{userId}:recent}（PRD 16 §7） */
    public static String playRecent(Long userId) {
        return PLAY_RECENT + userId + ":recent";
    }

    /** 播放进度 Hash：{@code ml:play:progress:{userId}:{songId}} */
    public static String playProgress(Long userId, Long songId) {
        return PLAY_PROGRESS + userId + ':' + songId;
    }

    /** 播放上报去重键，主体是 userId 或 deviceId（游客） */
    public static String playDedup(String subject, Long songId) {
        return PLAY_DEDUP + subject + ':' + songId;
    }

    // ---------- 模块 12 的键构造 ----------

    public static String playQuality(Long userId) {
        return PLAY_QUALITY + userId;
    }

    public static String playUrl(Long songId, String quality) {
        return PLAY_URL + songId + ':' + quality;
    }

    public static String playSetting(Long userId) {
        return PLAY_SETTING + userId;
    }

    public static String playDownload(Long userId) {
        return PLAY_DOWNLOAD + userId;
    }

    public static String device(Long userId) {
        return DEVICE + userId;
    }

    public static String recDaily(Long userId, String date) {
        return REC_DAILY + userId + ':' + date;
    }

    public static String recDailyReasons(Long userId, String date) {
        return REC_DAILY_REASONS + userId + ':' + date;
    }

    public static String recGuess(Long userId) {
        return REC_GUESS + userId;
    }

    public static String recSimilar(Long songId) {
        return REC_SIMILAR + songId;
    }

    public static String recNewcomer(Long userId) {
        return REC_NEWCOMER + userId;
    }

    public static String recFrequent(Long userId) {
        return REC_FREQUENT + userId;
    }

    public static String recDislike(Long userId) {
        return REC_DISLIKE + userId;
    }

    public static String recDislikeArtist(Long userId) {
        return REC_DISLIKE_ARTIST + userId;
    }

    public static String recLike(Long userId) {
        return REC_LIKE + userId;
    }

    public static String recBlockLoaded(Long userId) {
        return REC_BLOCK_LOADED + userId;
    }

    public static String recExposure(Long userId, String date) {
        return REC_EXPOSURE + userId + ':' + date;
    }

    public static String recContinueHidden(Long userId) {
        return REC_CONTINUE_HIDDEN + userId;
    }

    // ---------- 模块 09 的键构造 ----------

    public static String adminCaptcha(String captchaId) {
        return ADMIN_CAPTCHA + captchaId;
    }

    public static String adminRefresh(String refreshTokenId) {
        return ADMIN_REFRESH + refreshTokenId;
    }

    public static String adminRefreshOfUser(Long adminId) {
        return ADMIN_REFRESH_USER + adminId;
    }

    public static String adminBlacklist(String jti) {
        return ADMIN_BLACKLIST + jti;
    }

    public static String adminSessionVersion(Long adminId) {
        return ADMIN_SESSION_VER + adminId;
    }

    public static String adminLoginFail(String username) {
        return ADMIN_LOGIN_FAIL + username;
    }

    public static String adminRoomCreateBan(Long userId) {
        return ADMIN_ROOM_CREATE_BAN + userId;
    }

    // ---------- 模块 10 社交的键构造 ----------

    /** 用户对某类目标的点赞集合：{@code ml:like:user:{userId}:{targetType}}。 */
    public static String socialLikeUser(Long userId, String targetType) {
        return PREFIX + "like:user:" + userId + ':' + targetType;
    }

    /** 某目标的点赞用户集合：{@code ml:like:{targetType}:{targetId}}。 */
    public static String socialLikeTarget(String targetType, Long targetId) {
        return PREFIX + "like:" + targetType + ':' + targetId;
    }

    /** 关注集合 / 粉丝集合。 */
    public static String socialFollow(Long userId) {
        return PREFIX + "follow:" + userId;
    }

    public static String socialFans(Long userId) {
        return PREFIX + "fans:" + userId;
    }

    /** 单日关注计数：超过阈值后不再逐条通知。 */
    public static String socialFollowDay(Long followerId, String date) {
        return PREFIX + "social:follow:day:" + followerId + ':' + date;
    }

    /** 我拉黑的人 / 拉黑我的人（双向过滤用）。 */
    public static String socialBlacklist(Long userId) {
        return PREFIX + "blacklist:" + userId;
    }

    public static String socialBlockedBy(Long userId) {
        return PREFIX + "blockedby:" + userId;
    }

    /** 举报去重：{@code ml:social:report:dedup:{userId}:{type}:{id}:{reason}}。 */
    public static String socialReportDedup(Long userId, String targetType, String targetId, String reasonType) {
        return PREFIX + "social:report:dedup:" + userId + ':' + targetType + ':' + targetId + ':' + reasonType;
    }

    /** 非互关私信每日计数。 */
    public static String socialMessageDaily(Long senderId, Long receiverId, String date) {
        return PREFIX + "social:msg:daily:" + senderId + ':' + receiverId + ':' + date;
    }

    /** 私信发送频率窗口（BR-19-090：每分钟不超过 20 条）。 */
    public static String socialMessageRate(Long senderId, long epochMinute) {
        return PREFIX + "social:msg:rate:" + senderId + ':' + epochMinute;
    }

    /** 短链映射与点击计数。 */
    public static String socialShort(String code) {
        return PREFIX + "social:short:" + code;
    }

    public static String socialShortClick(String code) {
        return PREFIX + "social:short:click:" + code;
    }

    /** 短链独立访客集合（BR-19-075 的 UV 统计）。 */
    public static String socialShortVisitor(String code) {
        return PREFIX + "social:short:visitor:" + code;
    }

    /** 评论里程碑去重：同一作者对同一评论只提醒一次。 */
    public static String socialMilestone(Long commentId, int milestone) {
        return PREFIX + "social:milestone:" + commentId + ':' + milestone;
    }

    /** 收藏里程碑去重。 */
    public static String socialCollectMilestone(String targetType, Long targetId, int milestone) {
        return PREFIX + "social:collect:milestone:" + targetType + ':' + targetId + ':' + milestone;
    }

    // ---------- 模块 15 的键构造 ----------

    /** {@code type} 取 RECALL/FUSION/RANK/RERANK/EXPERIMENT。 */
    public static String recUpgradeConfig(String scene, String type) {
        return REC_UPGRADE_CONFIG + scene + ':' + type;
    }

    public static String recModel(String version) {
        return REC_MODEL + version;
    }

    public static String recRecallStatus(String date) {
        return REC_RECALL_STATUS + date;
    }

    public static String recMetric(String date, String scene, String experimentKey, String group) {
        return REC_METRIC + date + ':' + scene + ':' + experimentKey + ':' + group;
    }

    public static String recExperimentAssign(Long userId) {
        return REC_EXP_ASSIGN + userId;
    }

    public static String recExperimentMeta(String experimentKey) {
        return REC_EXP_META + experimentKey;
    }

    public static String recExplore(Long userId, String date) {
        return REC_EXPLORE + userId + ':' + date;
    }

    public static String recExplore(String userId) {
        return REC_EXPLORE + userId;
    }

    public static String recNegative(Long userId) {
        return REC_NEGATIVE + userId;
    }

    public static String recNewSongExposure(Long songId) {
        return REC_NEWSONG_EXPOSURE + songId;
    }

    public static String recNewSongUser(Long userId, String date) {
        return REC_NEWSONG_USER + userId + ':' + date;
    }

    public static String recNewSongStopped(String date) {
        return REC_NEWSONG_STOPPED + date;
    }

    public static String recNewSongPromoted(String date) {
        return REC_NEWSONG_PROMOTED + date;
    }

    public static String featureUser(Long userId) {
        return FEATURE + "user:" + userId;
    }

    public static String featureSong(Long songId) {
        return FEATURE + "song:" + songId;
    }

    public static String recEvaluate(String taskId) {
        return REC_EVALUATE + taskId;
    }

    public static String recTrace(String traceId) {
        return REC_TRACE + traceId;
    }

    // ---------- 模块 16 社交升级的键构造 ----------

    /** 关注流收件箱 ZSet：member=postId，score=发布时间戳(ms)。 */
    public static String feedInbox(Long userId) {
        return PREFIX + "feed:inbox:" + userId;
    }

    /** 大 V 发件箱 ZSet。 */
    public static String feedOutbox(Long userId) {
        return PREFIX + "feed:outbox:" + userId;
    }

    /** 收件箱是否已构建。 */
    public static String feedBuilt(Long userId) {
        return PREFIX + "feed:built:" + userId;
    }

    /** 「N 条新动态」计数。 */
    public static String feedNewCount(Long userId) {
        return PREFIX + "feed:new_count:" + userId;
    }

    /** 关注流上次已读时间戳（ms），用于计算「N 条新动态」。 */
    public static String feedLastRead(Long userId) {
        return PREFIX + "feed:last_read:" + userId;
    }

    /** 动态点赞用户 Set。 */
    public static String postLikeUsers(Long postId) {
        return PREFIX + "post:like:" + postId;
    }

    /** 动态计数 Hash：like/comment/repost/collect/view。 */
    public static String postCount(Long postId) {
        return PREFIX + "post:count:" + postId;
    }

    /** @ 提及当日配额。 */
    public static String mentionRate(Long userId, String date) {
        return PREFIX + "mention:rate:" + userId + ':' + date;
    }

    /** 同一作者对同一用户当天的 @ 通知次数（第 4 次起不通知）。 */
    public static String mentionDaily(Long authorId, Long targetId, String date) {
        return PREFIX + "mention:daily:" + authorId + ':' + targetId + ':' + date;
    }

    /** 通知聚合记录 Hash。 */
    public static String notifyAggregate(Long userId, String aggregateKey) {
        return PREFIX + "notify:agg:" + userId + ':' + aggregateKey;
    }

    /** 通知聚合写锁（避免并发创建两条聚合消息）。 */
    public static String notifyAggregateLock(Long userId, String aggregateKey) {
        return PREFIX + "lock:notify:agg:" + userId + ':' + aggregateKey;
    }

    /** 通知大类未读数 Hash。 */
    public static String notifyCategoryUnread(Long userId) {
        return NOTIFY_UNREAD + "cat:" + userId;
    }

    /** 通知总未读数（与 Hash 分开，避免 WRONGTYPE；PRD 25 §9 的 total 键）。 */
    public static String notifyUnreadTotal(Long userId) {
        return NOTIFY_UNREAD + "total:" + userId;
    }

    /** 最后推送时间（防打扰：非私信每分钟最多 1 条）。 */
    public static String notifyLastPush(Long userId) {
        return PREFIX + "notify:last:" + userId;
    }

    /** 会话列表 ZSet（按最后消息时间）。 */
    public static String convList(Long userId) {
        return PREFIX + "conv:list:" + userId;
    }

    /** 会话最后已读消息。 */
    public static String convRead(Long conversationId, Long userId) {
        return PREFIX + "conv:read:" + conversationId + ':' + userId;
    }

    /** 正在输入 Set，TTL 10s。 */
    public static String convTyping(Long conversationId) {
        return PREFIX + "conv:typing:" + conversationId;
    }

    /** 亲密度缓存。 */
    public static String relationScore(Long userId, Long targetId) {
        return PREFIX + "relation:score:" + userId + ':' + targetId;
    }

    /** 亲密度防刷：同一对用户每天最多计 10 次。 */
    public static String intimacyDaily(Long userId, Long targetId, String date) {
        return PREFIX + "relation:daily:" + userId + ':' + targetId + ':' + date;
    }

    /** 推荐关注列表 ZSet。 */
    public static String relationRecommend(Long userId) {
        return PREFIX + "relation:rec:" + userId;
    }

    /** 已忽略的推荐关注 Set。 */
    public static String relationRecommendIgnore(Long userId) {
        return PREFIX + "relation:rec:ignore:" + userId;
    }

    /** 粉丝榜 ZSet。 */
    public static String fansRank(Long userId, int type, String period) {
        return PREFIX + "fansrank:" + userId + ':' + type + ':' + period;
    }

    /** 主页浏览计数。 */
    public static String profileView(Long userId, String date) {
        return PREFIX + "profile:view:" + userId + ':' + date;
    }

    /** 热门话题 ZSet。 */
    public static String topicHot() {
        return PREFIX + "topic:hot";
    }

    /** 动态内容去重：Hash field=fingerprint。 */
    public static String postDedup(Long userId) {
        return PREFIX + "post:dedup:" + userId;
    }

    /** 动态发布频率窗口。 */
    public static String postRate(Long userId, String window) {
        return PREFIX + "post:rate:" + userId + ':' + window;
    }

    /** 动态图片去重/去重指纹最近内容列表（相似度检测）。 */
    public static String postRecent(Long userId) {
        return PREFIX + "post:recent:" + userId;
    }

    /** 动态「不感兴趣」集合，30 天。 */
    public static String postDislike(Long userId) {
        return PREFIX + "post:dislike:" + userId;
    }

    /** 发现页曝光计数 Hash。 */
    public static String postSeen(Long userId) {
        return PREFIX + "post:seen:" + userId;
    }

    /** 资料修改每日次数（bio / avatar）。 */
    public static String profileEditRate(Long userId, String field, String date) {
        return PREFIX + "profile:edit:" + userId + ':' + field + ':' + date;
    }

    /** 私信营销检测：内容指纹 → 收到该内容的会话 ID 集合（按会话数判营销，不按条数）。 */
    public static String messageSpamFingerprint(Long senderId, String fingerprint, String date) {
        return PREFIX + "msg:spam:" + senderId + ':' + fingerprint + ':' + date;
    }

    /** 营销私信限制到期时间（String，值为到期毫秒）。 */
    public static String messageSpamRestrict(Long senderId) {
        return PREFIX + "msg:spam:restrict:" + senderId;
    }

    /** 群聊未读辅助：成员最后已读 ID 已落库，这里只缓存成员列表。 */
    public static String convMembers(Long conversationId) {
        return PREFIX + "conv:members:" + conversationId;
    }

    // ---------- 模块 18 内容安全 ----------

    /** 当前词库版本。 */
    public static String auditWordVersion() {
        return PREFIX + "audit:words:version";
    }

    /** 词库构建状态（10 分钟）。 */
    public static String auditWordBuffer() {
        return PREFIX + "audit:words:buffer";
    }

    /** 单日单用户命中计数（刷词风控）。 */
    public static String auditHit(Long userId, String date) {
        return PREFIX + "audit:hit:" + userId + ':' + date;
    }

    /** 图片审核 MD5 缓存（7 天）。 */
    public static String auditImageMd5(String md5) {
        return PREFIX + "audit:img:md5:" + md5;
    }

    /** 举报人去重/当日次数。 */
    public static String auditReporterDay(Long userId, String date) {
        return PREFIX + "audit:reporter:" + userId + ':' + date;
    }

    /** 某内容 24 小时内的举报合并键（值为首次举报 ID）。 */
    public static String auditReportMerge(String contentType, String contentId) {
        return PREFIX + "audit:report:merge:" + contentType + ':' + contentId;
    }

    /** 某内容 24 小时内的举报人数。 */
    public static String auditReportCount(String contentType, String contentId) {
        return PREFIX + "audit:report:count:" + contentType + ':' + contentId;
    }

    /** 举报人准确率 Hash（有效数/总数）。 */
    public static String auditReporterAccuracy(Long userId) {
        return PREFIX + "audit:reporter:accuracy:" + userId;
    }

    /** 信用分缓存（1 天）。 */
    public static String auditCredit(Long userId) {
        return PREFIX + "audit:credit:" + userId;
    }

    /** 用户生效中的处置缓存（Hash）。 */
    public static String auditRestriction(Long userId) {
        return PREFIX + "audit:restriction:" + userId;
    }

    /** 第三方审核调用计数/成本。 */
    public static String auditThirdParty(String date) {
        return PREFIX + "audit:third_party:" + date;
    }

    public static String auditCost(String date) {
        return PREFIX + "audit:cost:" + date;
    }

    /** 审核失败队列。 */
    public static String auditDlq() {
        return PREFIX + "audit:dlq";
    }

    /** 申诉每日次数。 */
    public static String auditAppealDay(Long userId, String date) {
        return PREFIX + "audit:appeal:" + userId + ':' + date;
    }

    /** 青少年模式当日已用分钟数。 */
    public static String minorUsage(Long userId, String date) {
        return PREFIX + "minor:usage:" + userId + ':' + date;
    }

    /** 青少年模式心跳去重（同一用户同一分钟只计一次）。 */
    public static String minorTick(Long userId, String minute) {
        return PREFIX + "minor:tick:" + userId + ':' + minute;
    }

    /** 版权到期提醒去重（同一歌曲同一天只提醒一次）。 */
    public static String auditCopyrightNotice(Long songId, String date) {
        return PREFIX + "audit:copyright:notice:" + songId + ':' + date;
    }

    /** 定时任务互斥锁。 */
    public static String auditJobLock(String job) {
        return PREFIX + "audit:job:" + job;
    }

    /**
     * TTL 抖动：{@code base × (1 + rand[0, ratio))}。
     *
     * <p><b>只加不减</b>（系分 D-30）：若按 ±10% 抖动，运营配的「30 分钟」会变成 27 分钟起，
     * 与配置的直觉不符；只加则保证最小 TTL 就是配置值。
     *
     * <p>防雪崩的原理：同一批写入的 key 不再同一秒集体过期。
     */
    public static Duration jitterTtl(long baseSeconds, double ratio) {
        if (baseSeconds <= 0) {
            return Duration.ZERO;
        }
        if (ratio <= 0) {
            return Duration.ofSeconds(baseSeconds);
        }
        long extra = (long) (baseSeconds * ratio * ThreadLocalRandom.current().nextDouble());
        return Duration.ofSeconds(baseSeconds + extra);
    }
}
