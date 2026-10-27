/**
 * 与后端模块 01 的响应契约一一对应。
 *
 * 关键约定（见 docs/系分/01-数据库CRUD.md）：
 * - 所有实体 ID 由「阈值 Long 序列化器」输出为 **string**（雪花 ID > 2^53-1），
 *   因此 Id 一律声明为 string，任何一处漏改都会造成精度丢失。
 * - 内部字段 `deleted/createBy/updateBy` 不下发；`version` **会**下发且更新时必须回传。
 * - HTTP 200 只代表「请求被处理」，业务结果看 `code`（0 为成功）。
 */

/** 实体主键（字符串形式，见 D-12） */
export type Id = string;

export interface R<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
  timestamp: number;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
  pages: number;
  hasNext: boolean;
  /** pageSize 被后端强制截断为 100 */
  truncated: boolean;
}

export interface PageQuery {
  pageNum?: number;
  pageSize?: number;
  /** 接口级白名单内的字段，非法值后端会静默回退默认排序 */
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  keyword?: string;
}

/** 全部业务实体共有的审计字段 */
export interface BaseEntity {
  id: Id;
  createTime: string;
  updateTime: string;
  /** 乐观锁版本号，更新（PUT）时必须原样回传 */
  version: number;
}

// ---------------------------------------------------------------- 用户与鉴权

export interface UserVO {
  id: Id;
  username: string;
  nickname: string;
  avatar: string | null;
  gender: number | null;
  birthday: string | null;
  signature: string | null;
  userType: number;
  status: number;
  vipLevel: number | null;
  createTime: string;
  /** 角色码集合，如 ['ROLE_ADMIN'] */
  roles: string[];
  /** 权限码集合，已含通配展开后的具体动作码 */
  perms: string[];
}

export interface LoginReq {
  /** 用户名或手机号 */
  account: string;
  password: string;
}

export interface RegisterReq {
  username: string;
  password: string;
  nickname: string;
}

export interface LoginVO {
  accessToken: string;
  refreshToken: string;
  /** accessToken 有效期（秒） */
  expiresIn: number;
  user: UserVO;
}

/**
 * 修改个人资料（`PUT /users/me`）。
 *
 * 注意：服务端从数据库读取 version 做乐观锁，**不需要**前端回传；
 * 仅字段名与 `UserVO` 不同（没有 username/roles/perms）。
 */
export interface UserUpdateReq {
  nickname?: string;
  avatar?: string;
  /** 0未知 1男 2女 */
  gender?: number;
  /** yyyy-MM-dd */
  birthday?: string;
  signature?: string;
}

// ---------------------------------------------------------------- 曲库

export interface Song extends BaseEntity {
  name: string;
  subtitle: string | null;
  artistId: Id;
  artistName: string;
  featArtists: string | null;
  featNames: string | null;
  albumId: Id | null;
  albumName: string | null;
  cover: string | null;
  language: string | null;
  genre: string | null;
  /** 时长（秒） */
  duration: number | null;
  bpm: number | null;
  releaseDate: string | null;
  era: string | null;
  isrc: string | null;
  audioStandard: string | null;
  audioHigh: string | null;
  audioLossless: string | null;
  lyricId: Id | null;
  hasTranslation: number;
  /** 0未授权 1已授权 2已过期 3受限 */
  copyrightStatus: number;
  copyrightOwner: string | null;
  copyrightExpire: string | null;
  playCount: number;
  likeCount: number;
  collectCount: number;
  commentCount: number;
  shareCount: number;
  downloadCount: number;
  hotScore: number;
  /** 0待审 1通过 2拒绝 */
  auditStatus: number;
  /** 0下架 1上架 */
  status: number;
  publishTime: string | null;
  sortWeight: number;
  source: number;
}

/**
 * 列表/播放器渲染所需的最小歌曲字段。
 *
 * `Song` 结构化满足本类型，因此接口返回的完整实体可以直接传入；
 * 本地播放历史（`services/history.ts`）等只有部分字段的场景用它，避免伪造完整实体。
 */
export interface SongBrief {
  id: Id;
  name: string;
  artistId?: Id;
  artistName: string;
  albumId?: Id | null;
  albumName?: string | null;
  cover?: string | null;
  duration?: number | null;
  /** 0未授权 1已授权 2已过期 3受限 */
  copyrightStatus?: number;
  /**
   * 服务端判定的「可播放」（模块 06 搜索等列表 VO 返回）。
   *
   * 列表接口出于体积考虑**不返回音源地址**，所以 `audioStandard/audioHigh/audioLossless`
   * 三个字段在搜索结果里是缺省的；只看音源字段会把正在播放的歌误判成「不可播放」，
   * 进而把播放/暂停按钮与进度条一起禁用。有该字段时以它为准。
   */
  playable?: boolean;
  audioStandard?: string | null;
  audioHigh?: string | null;
  audioLossless?: string | null;
  /** 列表展示用统计字段 */
  playCount?: number;
  hotScore?: number;
  /** 0下架 1上架（后台列表展示） */
  status?: number;
  /** 0待审 1通过 2拒绝（后台列表展示） */
  auditStatus?: number;
  releaseDate?: string | null;
}

/**
 * 卡片渲染所需的最小歌手/专辑/歌单字段（同 {@link SongBrief} 的理由）。
 *
 * 存在的直接原因：模块 06 的搜索接口返回的是 `SearchArtistVO` / `SearchAlbumVO` /
 * `SearchPlaylistVO`——**只有展示需要的几列**（`songCount`、`cover`…），
 * 没有 `alias`/`gender`/`tags` 这些详情字段。卡片组件若坚持要完整实体，
 * 调用处就只能 `as unknown as Artist` 硬转，等于把类型检查关掉。
 *
 * `Artist` / `Album` / `Playlist` 结构化满足这些类型，因此原有调用处不受影响；
 * 字段做成可选的，是为了让「搜索没有这个字段」时组件能优雅降级而不是显示 `undefined`。
 */
export interface ArtistBrief {
  id: Id;
  name: string;
  avatar?: string | null;
  cover?: string | null;
  songCount?: number;
  albumCount?: number;
}

export interface AlbumBrief {
  id: Id;
  name: string;
  cover?: string | null;
  artistName?: string;
  songCount?: number;
  /** 曲库实体用 publishDate；搜索 VO 用 publishTime */
  publishDate?: string | null;
  publishTime?: string | null;
}

export interface PlaylistBrief {
  id: Id;
  name: string;
  cover?: string | null;
  songCount?: number;
  playCount?: number;
  tags?: string | null;
  /** 0私密 1公开；搜索 VO 不带这一列 */
  isPublic?: number;
}

export interface Artist extends BaseEntity {
  name: string;
  alias: string | null;
  avatar: string | null;
  cover: string | null;
  /** 0未知 1男 2女 3组合 */
  gender: number | null;
  country: string | null;
  birthday: string | null;
  debutDate: string | null;
  description: string | null;
  styles: string | null;
  songCount: number;
  albumCount: number;
  followerCount: number;
  hotScore: number;
  firstLetter: string | null;
  status: number;
}

export interface Album extends BaseEntity {
  name: string;
  artistId: Id;
  artistName: string;
  cover: string | null;
  publishDate: string | null;
  company: string | null;
  description: string | null;
  language: string | null;
  songCount: number;
  hotScore: number;
  status: number;
  /** 模块 10 起由后端下发；旧缓存数据可能没有 */
  likeCount?: number;
  collectCount?: number;
  commentCount?: number;
  shareCount?: number;
}

export interface Playlist extends BaseEntity {
  name: string;
  cover: string | null;
  description: string | null;
  /** 创建者；编辑/算法歌单为 0 */
  userId: Id;
  /** 1用户 2编辑 3算法 4场景 */
  playlistType: number;
  /** 0私密 1公开 */
  isPublic: number;
  tags: string | null;
  songCount: number;
  playCount: number;
  /** 模块 10 新增；未迁移的旧数据可能为 undefined */
  likeCount?: number;
  collectCount: number;
  commentCount: number;
  shareCount: number;
  hotScore: number;
  isRecommend: number;
  sortWeight: number;
  auditStatus: number;
  status: number;
  /** 模块 18：1=AI 生成，前端展示不可移除的角标 */
  aiGenerated?: number;
}

export interface PlaylistDetail {
  playlist: Playlist;
  songs: PageResult<Song>;
}

export interface Lyric extends BaseEntity {
  songId: Id;
  /** LRC 原文，形如 `[00:12.34]歌词` */
  content: string | null;
  /** 1标准LRC 2逐字LRC 3纯文本 */
  contentType: number;
  translation: string | null;
  romaji: string | null;
  /** 整体偏移（毫秒） */
  offsetMs: number;
  summary: string | null;
  emotion: string | null;
  auditStatus: number;
}

export interface Tag extends BaseEntity {
  name: string;
  /** 1风格 2场景 3情绪 4年代 5语种 6自定义 */
  tagType: number;
  useCount: number;
  isHot: number;
  status: number;
}

export interface Category extends BaseEntity {
  name: string;
  code: string;
  parentId: Id;
  icon: string | null;
  sortOrder: number;
  status: number;
}

// ---------------------------------------------------------------- 查询参数

export interface SongQuery extends PageQuery {
  artistId?: Id;
  albumId?: Id;
  genre?: string;
  language?: string;
  status?: number;
  auditStatus?: number;
  tagId?: Id;
  playlistId?: Id;
  bpmMin?: number;
  bpmMax?: number;
}

export interface ArtistQuery extends PageQuery {
  country?: string;
  firstLetter?: string;
  status?: number;
}

export interface AlbumQuery extends PageQuery {
  artistId?: Id;
  language?: string;
  status?: number;
}

export interface TagQuery extends PageQuery {
  /** 1 风格 / 2 场景 / 3 情绪 / 4 年代 / 5 语种 / 6 自定义 */
  tagType?: number;
  isHot?: number;
  status?: number;
}

export interface PlaylistQuery extends PageQuery {
  userId?: Id;
  playlistType?: number;
  isPublic?: number;
  isRecommend?: number;
  /** 仅看我的歌单（需登录） */
  mine?: boolean;
  tag?: string;
}

// ---------------------------------------------------------------- 写请求

export interface SongSaveReq {
  name: string;
  subtitle?: string | null;
  artistId: Id;
  albumId?: Id | null;
  cover?: string | null;
  language?: string | null;
  genre?: string | null;
  duration?: number | null;
  bpm?: number | null;
  releaseDate?: string | null;
  era?: string | null;
  isrc?: string | null;
  audioStandard?: string | null;
  audioHigh?: string | null;
  audioLossless?: string | null;
  copyrightStatus?: number;
  copyrightOwner?: string | null;
  copyrightExpire?: string | null;
  auditStatus?: number;
  status?: number;
  sortWeight?: number;
  /** 全量覆盖，最多 10 个 */
  tagIds?: Id[];
  version?: number;
}

export interface ArtistSaveReq {
  name: string;
  alias?: string | null;
  avatar?: string | null;
  gender?: number | null;
  country?: string | null;
  description?: string | null;
  styles?: string | null;
  firstLetter?: string | null;
  status?: number;
  version?: number;
}

export interface PlaylistSaveReq {
  name: string;
  cover?: string | null;
  description?: string | null;
  isPublic?: number;
  tags?: string | null;
  version?: number;
}

// ---------------------------------------------------------------- 模块 04：实时通信

/** `t_notification`（WS 推送与 HTTP 列表共用同一份结构） */
export interface NotificationItem {
  id: Id;
  userId?: Id;
  /** 1系统 2评论回复 3点赞 4关注 5私信 6@提醒 7房间邀请 8审核结果 9举报结果 */
  notifyType: number;
  title: string | null;
  content: string | null;
  fromUserId?: Id | null;
  fromNickname?: string | null;
  fromAvatar?: string | null;
  targetType?: number | null;
  targetId?: Id | null;
  isRead: number;
  readTime?: string | null;
  createTime: string;
}

/** `t_room_message`：断线补偿接口返回的历史消息 */
export interface RoomMessageItem {
  id: Id;
  roomId: Id;
  userId: Id;
  /** 1聊天 2弹幕 3系统 4礼物 5进房 6控播 */
  messageType: number;
  content: string | null;
  extra?: string | null;
  auditStatus?: number;
  status?: number;
  createTime: string;
}

export interface NotificationPushReq {
  userIds: Id[];
  notifyType?: number;
  title: string;
  content?: string;
  targetType?: number;
  targetId?: Id;
}

// ---------------------------------------------------------------- 模块 05：音乐房

/** 房间列表项（`GET /room/rooms`） */
export interface RoomVO {
  id: Id;
  roomNo: string;
  name: string;
  cover: string | null;
  ownerId: Id;
  ownerNickname: string | null;
  ownerAvatar: string | null;
  /** 1公开 2私密 3密码 */
  roomType: number;
  passwordRequired: boolean;
  notice: string | null;
  themeTags: string | null;
  micCount: number;
  maxMembers: number;
  /** 1房主控播 2自由播放 3投票切歌 */
  playMode: number;
  needApprove: number;
  allowFreeMic: number;
  /** 1活跃 2已关闭 3已解散 */
  status: number;
  onlineCount: number;
  hotScore: number;
  currentSongId: string | null;
  currentSongName: string | null;
  currentArtistName: string | null;
  currentCover: string | null;
  currentDuration: number | null;
  playedPosition: number | null;
  playing: boolean | null;
  createTime: string;
  lastActiveTime: string | null;
}

export interface RoomMemberVO {
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  /** 0成员 1房管 2房主 9黑名单 */
  role: number;
  online: boolean;
  muted: boolean;
  muteUntil: string | null;
  joinTime: string | null;
  totalDuration: number;
  contribute: number;
}

export interface MicVO {
  micPos: number;
  userId: Id | null;
  nickname: string | null;
  avatar: string | null;
  /** 0空闲 1占用 2锁定 */
  status: number;
  online: boolean | null;
  locked: boolean;
  ownerSeat: boolean;
}

export interface QueueItemVO {
  id: Id;
  roomId: Id;
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  songId: Id | null;
  songName: string | null;
  artistName: string | null;
  cover: string | null;
  duration: number | null;
  queueType: number;
  status: number;
  seq: number;
  source: number;
  createTime: string;
  /** 模块 13：房主置顶为 100，普通为 0 */
  priority?: number;
  /** 模块 13：合并点歌的票数 */
  voteCount?: number;
  /** 模块 13：priority × 10000 + voteCount × 100 + (1000 - seq) */
  orderIndex?: number;
}

/** 房间详情（HTTP 与 `room.join.ack` 同一份结构） */
export interface RoomDetailVO extends RoomVO {
  members: RoomMemberVO[];
  mics: MicVO[];
  queue: QueueItemVO[];
  myRole: number;
  myPermissions: string[];
  myMuteRemainingSeconds: number;
  kickedRemainingSeconds: number;
  serverTime: string;
}

/** 房间权威播放态（模块 13 FR-22-001） */
export interface RoomPlayStateVO {
  roomId: Id;
  songId: Id | null;
  songName: string | null;
  artistName: string | null;
  cover: string | null;
  durationMs: number | null;
  /** 1播放中 2暂停 3缓冲中 4结束 */
  playStatus: number;
  startPositionMs: number;
  stateUpdateTimeMs: number;
  playStateVersion: number;
  playbackRate: number;
  volume: number;
  updatedBy: number | null;
  serverTime: number;
  /** 模块 04 兼容字段（秒） */
  position: number;
  isPlaying: boolean | null;
}

/** 跳过投票（模块 13 FR-22-004） */
export interface RoomVoteVO {
  voteId: Id;
  roomId: Id;
  songId: Id | null;
  songName: string | null;
  initiatorId: Id | null;
  initiatorNickname: string | null;
  onlineCount: number;
  agreeCount: number;
  /** 1进行中 2通过 3未通过 4取消 */
  status: number;
  startTime: number | null;
  endTime: number | null;
  remainingSeconds: number | null;
  voted: boolean | null;
  passed: boolean | null;
}

/** 房间榜单类型（模块 13 FR-22-005） */
export type RankType = 'gift' | 'active' | 'request' | 'listen';

/** 房间榜单条目（模块 13 FR-22-005） */
export interface RoomRankEntryVO {
  rank: number;
  userId: Id;
  nickname: string | null;
  avatar: string | null;
  score: number;
}

/** HTTP 控播请求（模块 13）；位置统一毫秒，与 WS 的秒制控播不同 */
export interface PlayControlReq {
  action: 'play' | 'pause' | 'seek' | 'switch' | 'buffering' | 'end';
  songId?: Id;
  positionMs?: number;
  /** 0-100，缺省不改 */
  volume?: number;
}

export interface RoomQuery extends PageQuery {
  tab?: 'recommend' | 'hot' | 'new' | 'follow' | 'mine';
  roomType?: number;
  keyword?: string;
}

export interface RoomCreateReq {
  name: string;
  cover?: string;
  roomType?: 1 | 2 | 3;
  password?: string;
  notice?: string;
  themeTags?: string;
  micCount?: 4 | 6 | 8 | 12;
  maxMembers?: 50 | 100 | 200 | 500;
  playMode?: 1 | 2 | 3;
  needApprove?: 0 | 1;
  allowFreeMic?: 0 | 1;
}

export interface RoomUpdateReq extends Partial<RoomCreateReq> {
  version: number;
}

export interface MuteReq {
  userId: Id;
  /** 分钟；-1 表示永久（仅房主可用），与后端 MuteReq 一致 */
  durationMinutes: number;
  reason?: string;
}

export interface KickReq {
  userId: Id;
  reason?: string;
}

// ---------------------------------------------------------------- 错误码

/** 与后端 ResultCode 对齐的常用错误码（仅前端需要分支处理的那些） */
export const ErrCode = {
  SUCCESS: 0,
  PARAM_INVALID: 10001,
  RATE_LIMITED: 10004,
  CONCURRENT_MODIFY_CONFLICT: 10008,
  BATCH_SIZE_EXCEEDED: 10009,
  DUPLICATE_DATA: 10010,
  LOGIN_FAILED: 20001,
  ACCOUNT_BANNED: 20005,
  SONG_NOT_FOUND: 30001,
  SONG_NO_COPYRIGHT: 30002,
  /** 模块 07：请求音质无对应音源（后端会自动降级，仅在全部档位为空时抛出） */
  AUDIO_QUALITY_UNAVAILABLE: 30003,
  PLAYLIST_NOT_FOUND: 30004,
  /** HTTP 403：无权修改该歌单 */
  NO_PERMISSION_EDIT_PLAYLIST: 30005,
  PLAYLIST_SONG_LIMIT: 30006,
  SONG_AUDITING: 30008,
  // ---- 模块 14：AI
  /** 额度用完（用户小时/日额度或全局 QPS） */
  AI_QUOTA_EXCEEDED: 60002,
  AI_INPUT_ILLEGAL: 60003,
  /** 上游模型失败且降级后仍不可用 */
  AI_UNAVAILABLE: 60004,
  AI_OUTPUT_ILLEGAL: 60005,
  /** 中文歌无需翻译、无歌词等「不适用」场景，不是错误 */
  AI_NOT_APPLICABLE: 60006,
  AI_JOB_RUNNING: 60007,
  UNAUTHORIZED: 40101,
  TOKEN_INVALID: 40102,
  TOKEN_EXPIRED: 40103,
  NO_PERMISSION: 40301,
  SYSTEM_BUSY: 90001,
} as const;
