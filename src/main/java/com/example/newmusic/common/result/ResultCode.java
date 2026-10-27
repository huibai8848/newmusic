package com.example.newmusic.common.result;

/**
 * 全局错误码。
 *
 * <p>编码结构来自《05-通用设计规范》第 2 节：{2 位模块} + {3 位序号}。
 * HTTP 状态码默认 200（业务错误通过 code 区分），仅鉴权/无权限返回 401/403。
 *
 * @see <a href="../../../../../../../../../docs/PRD/05-通用设计规范.md">05-通用设计规范</a>
 */
public enum ResultCode {

    SUCCESS(0, "success"),

    // ---------- 10xxx 通用/参数 ----------
    PARAM_INVALID(10001, "参数校验失败"),
    BODY_PARSE_ERROR(10002, "请求体格式错误"),
    METHOD_NOT_SUPPORTED(10003, "请求方法不支持"),
    /** 模块19：限流是传输层语义，按 HTTP 429 返回，客户端/网关无需解析 body 即可退避。 */
    RATE_LIMITED(10004, "请求过于频繁，请稍后再试", 429),
    IDEMPOTENT_CONFLICT(10005, "请勿重复提交"),
    FILE_TOO_LARGE(10006, "文件大小超限"),
    FILE_TYPE_UNSUPPORTED(10007, "文件类型不支持"),
    CONCURRENT_MODIFY_CONFLICT(10008, "数据已被他人修改，请刷新后重试"),
    BATCH_SIZE_EXCEEDED(10009, "单次批量操作不能超过500条"),
    /** 扩展码：唯一键冲突的通用兜底（具体业务可预校验后抛出更精确的码）。 */
    DUPLICATE_DATA(10010, "数据已存在"),

    // ---------- 20xxx 认证与用户 ----------
    LOGIN_FAILED(20001, "账号或密码错误"),
    CAPTCHA_ERROR(20002, "验证码错误或已过期"),
    PHONE_REGISTERED(20003, "手机号已被注册"),
    ACCOUNT_NOT_FOUND(20004, "账号不存在"),
    ACCOUNT_BANNED(20005, "账号已被封禁"),
    ACCOUNT_STATUS_ABNORMAL(20006, "账号状态异常"),
    PASSWORD_WEAK(20007, "密码强度不足"),
    OLD_PASSWORD_ERROR(20008, "原密码错误"),
    NICKNAME_EXISTS(20009, "昵称已存在"),
    SMS_LIMIT(20010, "每日短信次数已达上限"),

    // ---------- 30xxx 内容与曲库 ----------
    SONG_NOT_FOUND(30001, "歌曲不存在或已下架"),
    SONG_NO_COPYRIGHT(30002, "当前歌曲无版权，无法播放"),
    AUDIO_QUALITY_UNAVAILABLE(30003, "该音质不可用"),
    PLAYLIST_NOT_FOUND(30004, "歌单不存在"),
    /** PRD 05 §2.2 规定该错误以 HTTP 403 返回 */
    NO_PERMISSION_EDIT_PLAYLIST(30005, "无权修改该歌单", 403),
    PLAYLIST_SONG_LIMIT(30006, "歌单歌曲数量已达上限"),
    LYRIC_NOT_FOUND(30007, "歌词不存在"),
    SONG_AUDITING(30008, "歌曲正在审核中"),

    /**
     * 模块 01 扩展码（30041~30049）。
     *
     * <p>PRD 核心错误码表仅定义到 30008，30010~30030 已被《25-模块16》占用，
     * 故模块 01 的新增错误码统一落在 30041~ 段，避免冲突。
     */
    ARTIST_NOT_FOUND(30041, "歌手不存在"),
    ALBUM_NOT_FOUND(30042, "专辑不存在"),
    SONG_NOT_PUBLISHABLE(30043, "歌曲不满足上架条件（需审核通过、已授权且存在标准音质）"),
    ARTIST_HAS_SONGS(30044, "该歌手下仍有歌曲，无法删除"),
    ALBUM_HAS_SONGS(30045, "该专辑下仍有歌曲，无法删除"),
    CATEGORY_HAS_CHILDREN(30046, "该分类下仍有子分类，无法删除"),
    CATEGORY_IN_USE(30047, "该分类仍被歌单或内容引用，无法删除"),
    PLAYLIST_SONG_NOT_EXISTS(30048, "该歌曲不在歌单中"),
    PLAYLIST_SORT_TARGET_INVALID(30049, "排序目标歌曲不在该歌单中"),
    CATEGORY_NOT_FOUND(30050, "分类不存在"),
    TAG_NOT_FOUND(30051, "标签不存在"),

    /**
     * 模块 12 扩展码（30061~30069）。
     *
     * <p>PRD 21 的下载错误码写作 80011/80012，但这两个码已被模块 09 的管理后台登录
     * 占用（ADMIN_LOGIN_FAILED / ADMIN_LOGIN_LOCKED）。同一进程里出现两个 80011
     * 会让前端无法判断是「下载空间不足」还是「后台密码错误」，故顺延到 30061 段。
     */
    PLAY_SETTING_INVALID(30061, "播放设置不合法"),
    PLAY_DEVICE_NOT_FOUND(30062, "设备不存在或已退出"),
    /** P2 下载能力预留；本期不开放下载接口 */
    PLAY_DOWNLOAD_QUOTA(30063, "下载空间不足"),
    PLAY_DOWNLOAD_LIMIT(30064, "下载数量已达上限"),

    // ---------- 30xxx 社交升级（模块 16，PRD 25 §4~§7 定义的 30010~30030 段） ----------
    CONTENT_ILLEGAL(30010, "内容包含违规信息"),
    PROFILE_UPDATE_TOO_FREQUENT(30011, "修改过于频繁，请稍后再试"),
    USER_CODE_DUPLICATED(30012, "该短码已被占用，请换一个"),
    PROFILE_FORBIDDEN(30015, "账号当前状态不可编辑资料"),
    POST_RATE_LIMITED(30020, "发布过于频繁，请稍后再试"),
    POST_CONTENT_EMPTY(30021, "请输入动态内容"),
    POST_ILLEGAL(30022, "动态内容包含违规信息"),
    POST_DUPLICATED(30023, "请勿重复发布相同内容"),
    POST_EDIT_EXPIRED(30024, "发布超过 5 分钟，无法编辑"),
    POST_NOT_FOUND(30025, "动态不存在或已删除"),
    POST_NOT_VISIBLE(30026, "无权查看该动态"),
    POST_IMAGE_LIMIT(30027, "图片最多 9 张"),
    POST_TOPIC_LIMIT(30028, "话题最多 3 个"),
    MENTION_LIMIT(30029, "单条内容最多 @ 10 个用户"),
    MENTION_RATE_LIMITED(30030, "@ 提及次数已达今日上限"),

    // ---------- 40xxx 音乐房（模块 05 使用） ----------
    ROOM_NOT_FOUND(40001, "房间不存在或已解散"),
    ROOM_FULL(40002, "房间已满员"),
    ROOM_PASSWORD_ERROR(40003, "房间密码错误"),
    ROOM_BLACKLISTED(40004, "你已被房主拉黑，无法进入"),
    ROOM_MUTED(40005, "你已被禁言"),
    ROOM_NO_PERMISSION(40006, "无权限执行该操作", 403),
    ROOM_OWNER_PROTECTED(40007, "房主不可被禁言/踢出"),
    ROOM_MIC_FULL(40008, "麦位已满"),
    ROOM_QUEUE_DUPLICATED(40009, "你已在麦位队列中"),
    ROOM_ADMIN_LIMIT(40010, "房管数量已达上限"),
    ROOM_STATUS_ABNORMAL(40011, "房间状态异常，无法操作"),
    /** 当日创建配额（3 个/日）或活跃房间上限（10 个） */
    ROOM_CREATE_LIMIT(40012, "创建房间次数已达上限"),
    /** 播放队列 100 首 */
    ROOM_QUEUE_FULL(40013, "点歌队列已满"),
    /** 排麦队列 50 人 */
    ROOM_MIC_QUEUE_FULL(40014, "排麦队列已满"),
    ROOM_MIC_NOT_AVAILABLE(40015, "没有可用的麦位"),
    /** 被踢出后的 5 分钟冷却 */
    ROOM_KICK_COOLDOWN(40016, "你已被移出房间，请稍后再试"),
    ROOM_BLACKLIST_FULL(40017, "黑名单已满，请先清理"),
    /** 每人最多 3 首待播/已通过的点歌 */
    ROOM_USER_QUEUE_LIMIT(40018, "你点歌数量已达上限"),
    /** 密码错误 3 次后锁定 5 分钟 */
    ROOM_PASSWORD_LOCKED(40019, "密码错误次数过多，请 5 分钟后再试"),
    ROOM_CLOSED(40020, "房间已关闭"),
    ROOM_NAME_ILLEGAL(40021, "房间名或公告包含违规内容"),
    /** 模块 13：同房间同一首歌只能发起一次投票 */
    ROOM_VOTE_DUPLICATED(40022, "该歌曲已发起过投票"),
    ROOM_VOTE_COOLDOWN(40023, "投票发起过于频繁，请稍后再试"),
    ROOM_VOTE_NOT_FOUND(40024, "投票不存在或已结束"),
    ROOM_VOTE_ALREADY(40025, "你已经投过票了"),
    ROOM_VOTE_NOT_ALLOWED(40026, "当前歌曲暂时不能发起跳过投票"),
    ROOM_RANK_TYPE_INVALID(40027, "不支持的榜单类型"),

    // ---------- 50xxx 社交（模块 10 使用） ----------
    COMMENT_NOT_FOUND(50001, "评论不存在或已删除"),
    COMMENT_ILLEGAL(50002, "评论内容包含违规信息"),
    COMMENT_TOO_FREQUENT(50003, "评论过于频繁"),
    SELF_INTERACTION_FORBIDDEN(50004, "不能给自己点赞/关注"),
    USER_BLACKLISTED(50005, "你已被对方拉黑"),
    MESSAGE_RECALLED(50006, "消息已撤回或不存在"),
    MESSAGE_RECALL_EXPIRED(50007, "超过撤回时间限制"),
    REPORT_DUPLICATED(50008, "该内容你已举报过"),
    COMMENT_DUPLICATED(50009, "请勿重复发表相同评论"),
    FOLLOW_LIMIT(50010, "关注人数已达上限"),
    FOLLOW_FORBIDDEN(50011, "对方设置不允许关注"),
    BLACKLIST_LIMIT(50012, "黑名单人数已达上限"),
    MESSAGE_FORBIDDEN(50013, "对方已将你拉黑，无法发送私信"),
    MESSAGE_PRIVATE_CLOSED(50014, "对方已关闭私信"),
    MESSAGE_NON_MUTUAL_LIMIT(50015, "非互关用户每天最多发送 3 条私信"),
    MESSAGE_TOO_FREQUENT(50016, "私信发送过于频繁"),
    REPORT_SELF_FORBIDDEN(50017, "不能举报自己"),
    REPORT_RATE_LIMITED(50018, "举报过于频繁"),
    REPORT_TARGET_NOT_FOUND(50019, "举报目标不存在或已下架"),
    CONTENT_NOT_FOUND(50020, "内容不存在或已删除"),
    CONTENT_OFFLINE(50021, "内容已下架"),
    COLLECT_PRIVATE_FORBIDDEN(50022, "私密歌单不可收藏"),
    FOLDER_LIMIT(50023, "收藏夹数量已达上限"),
    FOLDER_NAME_DUPLICATED(50024, "收藏夹名称已存在"),
    MESSAGE_NOT_FOUND(50025, "消息不存在"),
    REPORT_RESTRICTED(50026, "近期举报有效率过低，暂时无法举报"),
    REPORT_EVIDENCE_TOO_LARGE(50027, "举报凭证过大"),
    SHARE_TARGET_INVALID(50028, "分享目标不存在或已下架"),
    COMMENT_DISABLED(50029, "对方已关闭评论"),
    MESSAGE_ILLEGAL(50030, "消息内容包含违规信息"),
    FOLDER_DEFAULT_READONLY(50031, "默认收藏夹不能重命名或删除"),
    FOLDER_ITEM_LIMIT(50032, "收藏夹条目已达上限"),
    GROUP_MEMBER_LIMIT(50033, "群成员已达上限（20 人）"),
    GROUP_NAME_ILLEGAL(50034, "群名称不合法"),
    GROUP_OWNER_ONLY(50035, "仅群主可执行该操作", 403),
    CONVERSATION_PIN_LIMIT(50036, "最多置顶 3 个会话"),
    CONVERSATION_ARCHIVE_LIMIT(50037, "最多归档 100 个会话"),
    MESSAGE_SPAM_RESTRICTED(50038, "私信功能已被限制，请 7 天后再试"),

    // ---------- 70xxx 搜索（模块 06 使用；7000x 段由 PRD 05 §2.2 分配） ----------
    /** PRD 15 `EX-15-013` 第 5 级：所有搜索引擎都不可用 */
    SEARCH_UNAVAILABLE(70001, "搜索服务暂时不可用"),
    /** PRD 15 `EX-15-001-3` */
    SEARCH_KEYWORD_TOO_LONG(70002, "搜索关键词过长"),
    /**
     * PRD 05 的码表完整性占位。
     *
     * <p>「查询成功、只是没命中」不是错误：{@code EX-15-001-4} 的主行为是「返回空列表 +
     * 推荐搜索词」。若返回非 0 code，前端拦截器会按错误弹 Toast，与「展示推荐词」自相矛盾。
     * 所以空结果一律返回 code 0 + {@code suggestWords}，本码不用于返回（系分 D-41）。
     */
    SEARCH_NO_RESULT(70003, "无搜索结果"),
    /**
     * 搜索关键词命中敏感词。
     *
     * <p>PRD 15 `BR-15-002` 字面要求复用 {@code 50002}，但该码在 PRD 05 的文案是
     * 「**评论**内容包含违规信息」——搜个歌收到「评论内容违规」的提示是错的。70xxx 本就
     * 是搜索段，故在本段内新增（系分 §5.5）。
     */
    SEARCH_KEYWORD_ILLEGAL(70004, "搜索关键词包含违规内容"),
    /** PRD 15 `BR-15-010`：`from + size > 1000` 必须改用游标分页（系分 D-40） */
    SEARCH_CURSOR_REQUIRED(70005, "分页过深，请使用游标（cursor）分页"),

    // ---------- 71xxx 词典（模块 11） ----------
    /** EX-20-001-1 / EX-20-001-3：格式校验失败，message 由服务层补上具体行号 */
    DICT_WORD_INVALID(71001, "词条格式不合法"),
    DICT_WORD_DUPLICATED(71002, "词条已存在"),
    /** EX-20-001-5：主词典 10 万上限 */
    DICT_WORD_LIMIT(71003, "主词典条目已达上限（10 万）"),
    /** BR-20-061：自动同步词条不可手工删除 */
    DICT_AUTO_WORD_READONLY(71004, "自动同步词条不可手工修改或删除"),
    DICT_SYNONYM_INVALID(71005, "同义词组配置不合法"),
    DICT_SYNONYM_CONFLICT(71006, "同义词与停用词或已有分组冲突"),
    DICT_STOPWORD_LIMIT(71007, "停用词数量已达上限（5000）"),
    /** ES 未部署或不可达；后台仍可用本地分词测试 */
    DICT_ES_UNAVAILABLE(71008, "Elasticsearch 不可用"),
    /** BR-20-062：批量导入逐行校验，返回行号与原因 */
    DICT_IMPORT_INVALID(71009, "导入内容格式不正确"),
    DICT_PINYIN_INVALID(71010, "拼音格式不合法"),
    DICT_ENTRY_NOT_FOUND(71011, "词典条目不存在"),

    // ---------- 60xxx AI（模块 14；PRD 23 §11 只点名了 60002/60003，其余在本段顺延） ----------
    /** 用户级 / 全局 / 场景级限流，以及日预算熔断 */
    AI_QUOTA_EXCEEDED(60002, "AI 调用额度已用完，请稍后再试"),
    /** 用户输入命中敏感词（如 AI 歌单的自然语言描述） */
    AI_INPUT_ILLEGAL(60003, "输入内容包含违规信息"),
    /** 模型全部档位失败、输出校验连续失败等 */
    AI_UNAVAILABLE(60004, "AI 服务暂时不可用，请稍后再试"),
    /** AI 输出未通过内容审核（BR-23-010） */
    AI_OUTPUT_ILLEGAL(60005, "AI 生成内容未通过审核"),
    /** 中文歌曲翻译、无歌词等「不适用」而非故障的场景 */
    AI_NOT_APPLICABLE(60006, "当前内容不需要 AI 处理"),
    /** 同一时间只允许一个离线标注任务 */
    AI_JOB_RUNNING(60007, "AI 任务正在执行中，请稍后再试"),

    // ---------- 80xxx 管理后台 ----------
    ADMIN_NO_PERMISSION(80001, "无后台访问权限", 403),
    SENSITIVE_OPERATION_NEED_APPROVAL(80002, "敏感操作需审批"),
    AUDIT_TASK_HANDLED(80003, "审核任务已被他人处理"),
    /** PRD 17 EX-17-002-2：屏蔽列表上限 200 首 / 50 位歌手 */
    RECOMMEND_BLOCKLIST_FULL(80010, "屏蔽列表已满，请先清理"),

    /**
     * 模块 09 后台错误码。
     *
     * <p><b>为何不按 PRD 18 用 40003~40015</b>：40xxx 段已被模块 05 音乐房整段占用
     * （40001 房间不存在、40003 房间密码错误……）。同一进程里出现两个「40003」会让
     * 前端无法判断该跳登录还是提示房间密码错误。80xxx 是 PRD 05 划给管理后台的段，
     * 这里顺序延用，并以 {@code docs/系分/09-管理后台.md} D-02 记录偏差。
     */
    ADMIN_LOGIN_FAILED(80011, "账号或密码错误"),
    ADMIN_LOGIN_LOCKED(80012, "登录失败次数过多，账号已锁定"),
    ADMIN_CAPTCHA_ERROR(80013, "验证码错误或已过期"),
    ADMIN_ACCOUNT_DISABLED(80014, "管理员账号已被禁用"),
    ADMIN_TOTP_REQUIRED(80015, "请输入动态验证码"),
    ADMIN_TOTP_ERROR(80016, "动态验证码错误"),
    ADMIN_LAST_SUPER(80017, "系统必须保留至少一个可用的超级管理员"),
    ADMIN_SELF_DISABLE(80018, "不能禁用或删除当前登录账号"),
    ADMIN_USERNAME_EXISTS(80019, "管理员登录名已存在"),
    ADMIN_PASSWORD_WEAK(80020, "密码强度不足：至少 8 位，且包含字母与数字"),
    ROOM_CREATE_BANNED(80029, "您已被管理员限制创建房间"),
    AUDIT_TASK_CONFLICT(80030, "任务已被他人领取或状态已变更"),
    AUDIO_FORMAT_UNSUPPORTED(80021, "音频格式不支持，仅接受 mp3/wav/flac"),
    AUDIO_TOO_LARGE(80022, "音频文件超过大小限制"),
    CONFIG_VALUE_INVALID(80023, "配置值不符合该配置项的类型要求"),
    ADMIN_PASSWORD_EXPIRED(80024, "密码已超过 90 天未修改，请先修改密码"),
    REPORT_ALREADY_HANDLED(80025, "该举报已被处理"),
    CONFIG_SECRET_FORBIDDEN(80026, "禁止在系统配置中保存密码/密钥类敏感信息"),
    ADMIN_IP_NOT_ALLOWED(80027, "当前 IP 不在该账号的白名单内"),
    AUDIT_REASON_REQUIRED(80028, "拒绝时必须填写不少于 5 个字符的理由"),

    // ---------- 72xxx 内容安全（模块 18） ----------
    SENSITIVE_WORD_EXISTS(72001, "敏感词已存在"),
    SENSITIVE_WORD_NOT_FOUND(72002, "敏感词不存在"),
    WORD_IMPORT_INVALID(72003, "导入内容不合法"),
    WORD_IMPORT_LIMIT(72004, "单次导入不能超过 10000 条"),
    WORD_L3_PERMISSION_REQUIRED(72005, "L3 词操作需要超级管理员权限"),
    WORD_EXPORT_CONFIRM_REQUIRED(72006, "导出 L3 词需输入 EXPORT 二次确认"),
    REPORT_NOT_FOUND(72008, "举报不存在"),
    REPORT_SLA_OVERDUE(72009, "举报已超过处理时限，请优先处理"),
    APPEAL_WINDOW_EXPIRED(72010, "申诉已超过 7 天时效"),
    APPEAL_DUPLICATED(72011, "该处置已申诉过"),
    APPEAL_DAILY_LIMIT(72012, "今日申诉次数已达上限"),
    APPEAL_SELF_REVIEW(72013, "不能由原审核员复核该申诉"),
    APPEAL_NOT_FOUND(72014, "申诉不存在"),
    RESTRICTION_NOT_FOUND(72015, "处置记录不存在"),
    PERMANENT_BAN_CONFIRM_REQUIRED(72016, "永久封禁需二次确认"),
    COPYRIGHT_INCOMPLETE(72017, "版权信息不完整，歌曲不能上架"),
    COPYRIGHT_NOT_FOUND(72018, "版权信息不存在"),
    COMPLAINT_NOT_FOUND(72019, "侵权投诉不存在"),
    MINOR_MODE_PASSWORD_ERROR(72020, "青少年模式密码错误"),
    MINOR_MODE_USAGE_LIMIT(72021, "今日使用时长已达上限"),
    MINOR_MODE_TIME_FORBIDDEN(72022, "当前时段青少年模式不可使用"),
    MINOR_MODE_NOT_CLOSABLE(72023, "未成年人账号的青少年模式不可关闭"),
    AI_LABEL_REMOVAL_FORBIDDEN(72024, "AI 生成标识不可移除"),
    WORD_VERSION_NOT_FOUND(72025, "词库版本不存在"),
    AUDIT_RULE_NOT_FOUND(72026, "审核规则不存在"),
    WORD_EXPORT_EMPTY(72027, "没有可导出的词条"),
    IMAGE_AUDIT_FAILED(72028, "图片审核失败"),
    MINOR_MODE_FEATURE_FORBIDDEN(72029, "青少年模式下不可使用该功能"),
    MINOR_GUARDIAN_REQUIRED(72030, "仅监护人可操作"),

    // ---------- 73xxx 高可用与运维（模块 19） ----------
    OPS_RULE_NOT_FOUND(73001, "限流规则不存在"),
    OPS_DEGRADE_LEVEL_INVALID(73002, "降级等级只能是 0~5"),
    OPS_SWITCH_NOT_FOUND(73003, "降级开关不存在"),
    OPS_CHAOS_DISABLED(73004, "混沌实验未启用"),
    OPS_CHAOS_LIMIT(73005, "混沌实验超出安全上限（时长/爆炸半径）"),
    OPS_PUBLISH_NOT_FOUND(73006, "发布记录不存在"),
    OPS_ALERT_NOT_FOUND(73007, "告警记录不存在"),
    OPS_CACHE_PREFIX_FORBIDDEN(73008, "缓存操作只允许 ml: 下的明确前缀"),
    OPS_BACKUP_FAILED(73009, "备份清单生成失败"),
    OPS_RUNBOOK_NOT_FOUND(73010, "应急预案不存在"),
    OPS_STARTUP_CHECK_FAILED(73011, "启动自检失败"),
    OPS_ALERT_RULE_INVALID(73012, "告警规则不合法"),

    // ---------- 90xxx 系统/基础设施 ----------
    SYSTEM_BUSY(90001, "系统繁忙，请稍后再试"),
    DB_ERROR(90002, "数据库操作失败"),
    CACHE_ERROR(90003, "缓存服务异常"),
    MQ_ERROR(90004, "消息队列异常"),
    SERVICE_DEGRADED(90005, "服务已降级"),
    THIRD_PARTY_ERROR(90006, "第三方服务异常"),

    // ---------- 鉴权（HTTP 401 / 403） ----------
    UNAUTHORIZED(40101, "未登录或登录已过期", 401),
    TOKEN_INVALID(40102, "登录凭证无效，请重新登录", 401),
    TOKEN_EXPIRED(40103, "登录已过期，请重新登录", 401),
    NO_PERMISSION(40301, "无权限执行该操作", 403),

    // ---------- 99xxx 兜底 ----------
    UNKNOWN(99001, "未知异常");

    private final int code;
    private final String message;
    private final int httpStatus;

    ResultCode(int code, String message) {
        this(code, message, 200);
    }

    ResultCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
