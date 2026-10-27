package com.example.newmusic.module.room.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.room.dto.DissolveReq;
import com.example.newmusic.module.room.dto.KickReq;
import com.example.newmusic.module.room.dto.MicApproveReq;
import com.example.newmusic.module.room.dto.MicLockReq;
import com.example.newmusic.module.room.dto.ListenReportReq;
import com.example.newmusic.module.room.dto.MuteReq;
import com.example.newmusic.module.room.dto.NoticeReq;
import com.example.newmusic.module.room.dto.PlayControlReq;
import com.example.newmusic.module.room.dto.QueueAddReq;
import com.example.newmusic.module.room.dto.QueuePriorityReq;
import com.example.newmusic.module.room.dto.RankHiddenReq;
import com.example.newmusic.module.room.dto.RoomCreateReq;
import com.example.newmusic.module.room.dto.RoomJoinReq;
import com.example.newmusic.module.room.dto.RoomQueryReq;
import com.example.newmusic.module.room.dto.RoomUpdateReq;
import com.example.newmusic.module.room.dto.UserTargetReq;
import com.example.newmusic.module.room.service.RoomMicService;
import com.example.newmusic.module.room.service.RoomModerationService;
import com.example.newmusic.module.room.service.RoomPlayService;
import com.example.newmusic.module.room.service.RoomQueueService;
import com.example.newmusic.module.room.service.RoomRankService;
import com.example.newmusic.module.room.service.RoomService;
import com.example.newmusic.module.room.service.RoomVoteService;
import com.example.newmusic.module.room.vo.MicVO;
import com.example.newmusic.module.room.vo.QueueItemVO;
import com.example.newmusic.module.room.vo.RoomDetailVO;
import com.example.newmusic.module.room.vo.RoomMemberVO;
import com.example.newmusic.module.room.vo.RoomPlayStateVO;
import com.example.newmusic.module.room.vo.RoomRankEntryVO;
import com.example.newmusic.module.room.vo.RoomVO;
import com.example.newmusic.module.room.vo.RoomVoteVO;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.service.RoomMessageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 音乐房 HTTP 接口（PRD 14 §7）。
 *
 * <p>路径里的 {@code roomNo} 只用于“详情”：分享链接给用户的是 6 位房间号，
 * 不是内部雪花 ID。其余写操作使用 {@code roomId}，它来自 join 快照，
 * 避免每次写操作都再做一次 room_no → id 的转换。
 */
@Api(tags = "音乐房")
@RestController
@RequestMapping("/api/v1/room/rooms")
public class RoomController {

    private final RoomService roomService;
    private final RoomMicService micService;
    private final RoomQueueService queueService;
    private final RoomModerationService moderationService;
    private final RoomMessageService roomMessageService;
    private final RoomPlayService playService;
    private final RoomVoteService voteService;
    private final RoomRankService rankService;

    public RoomController(RoomService roomService, RoomMicService micService,
                          RoomQueueService queueService,
                          RoomModerationService moderationService,
                          RoomMessageService roomMessageService,
                          RoomPlayService playService, RoomVoteService voteService,
                          RoomRankService rankService) {
        this.roomService = roomService;
        this.micService = micService;
        this.queueService = queueService;
        this.moderationService = moderationService;
        this.roomMessageService = roomMessageService;
        this.playService = playService;
        this.voteService = voteService;
        this.rankService = rankService;
    }

    // ------------------------------------------------------------------ 列表 / 详情 / 创建

    @ApiOperation(value = "房间列表", notes = "tab=recommend/hot/new/follow/mine；私密房不出现在任何列表")
    @GetMapping
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<PageResult<RoomVO>> list(RoomQueryReq query) {
        return R.ok(roomService.page(query, UserContext.getUserId()));
    }

    @ApiOperation(value = "房间详情", notes = "按 6 位房间号查询；游客可访问公开房")
    @GetMapping("/{roomNo}")
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<RoomDetailVO> detail(@PathVariable String roomNo) {
        return R.ok(roomService.detailByNo(roomNo, UserContext.getUserId()));
    }

    @ApiOperation(value = "创建房间", notes = "自动成为房主并占用 1 号麦；每日 3 个、同时活跃 10 个")
    @PostMapping
    @RateLimit(dimension = RateLimitDimension.USER, limit = 3, windowSeconds = 3600, type = "room")
    public R<RoomDetailVO> create(@Valid @RequestBody RoomCreateReq req) {
        return R.ok(roomService.create(UserContext.requireUserId(), req));
    }

    @ApiOperation(value = "修改房间", notes = "房主/房管；须回传 version（乐观锁）")
    @PutMapping("/{id}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<RoomDetailVO> update(@PathVariable Long id, @Valid @RequestBody RoomUpdateReq req) {
        return R.ok(roomService.update(id, req, UserContext.requireUserId()));
    }

    @ApiOperation("修改公告")
    @PutMapping("/{id}/notice")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<RoomDetailVO> notice(@PathVariable Long id, @Valid @RequestBody NoticeReq req) {
        return R.ok(roomService.updateNotice(id, UserContext.requireUserId(), req.getNotice()));
    }

    // ------------------------------------------------------------------ 加入 / 退出

    @ApiOperation(value = "加入房间", notes = "校验顺序见 PRD FR-14-002；HTTP 加入只记录成员关系，"
            + "在线状态由 WS room.join 建立")
    @PostMapping("/{id}/join")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<RoomDetailVO> join(@PathVariable Long id,
                                @RequestBody(required = false) RoomJoinReq req) {
        return R.ok(roomService.join(id, UserContext.requireUserId(),
                req == null ? null : req.getPassword()));
    }

    @ApiOperation("退出房间")
    @PostMapping("/{id}/leave")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<Void> leave(@PathVariable Long id) {
        roomService.onWsLeave(id, UserContext.requireUserId());
        return R.ok();
    }

    // ------------------------------------------------------------------ 成员与治理

    @ApiOperation("成员列表")
    @GetMapping("/{id}/members")
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<List<RoomMemberVO>> members(@PathVariable Long id) {
        return R.ok(roomService.members(id));
    }

    @ApiOperation("设置房管")
    @PostMapping("/{id}/admins")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> addAdmin(@PathVariable Long id, @Valid @RequestBody UserTargetReq req) {
        moderationService.setAdmin(id, UserContext.requireUserId(), req.getUserId(), true);
        return R.ok();
    }

    @ApiOperation("撤销房管")
    @DeleteMapping("/{id}/admins/{userId}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> removeAdmin(@PathVariable Long id, @PathVariable Long userId) {
        moderationService.setAdmin(id, UserContext.requireUserId(), userId, false);
        return R.ok();
    }

    @ApiOperation("禁言")
    @PostMapping("/{id}/mute")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> mute(@PathVariable Long id, @Valid @RequestBody MuteReq req) {
        moderationService.mute(id, UserContext.requireUserId(), req);
        return R.ok();
    }

    @ApiOperation("解除禁言")
    @DeleteMapping("/{id}/mute/{userId}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> unmute(@PathVariable Long id, @PathVariable Long userId) {
        moderationService.unmute(id, UserContext.requireUserId(), userId);
        return R.ok();
    }

    @ApiOperation("踢出房间")
    @PostMapping("/{id}/kick")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> kick(@PathVariable Long id, @Valid @RequestBody KickReq req) {
        moderationService.kick(id, UserContext.requireUserId(), req);
        return R.ok();
    }

    @ApiOperation("拉黑")
    @PostMapping("/{id}/blacklist")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> blacklist(@PathVariable Long id, @Valid @RequestBody UserTargetReq req) {
        moderationService.blacklist(id, UserContext.requireUserId(), req.getUserId());
        return R.ok();
    }

    @ApiOperation("移除黑名单")
    @DeleteMapping("/{id}/blacklist/{userId}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> unblacklist(@PathVariable Long id, @PathVariable Long userId) {
        moderationService.unblacklist(id, UserContext.requireUserId(), userId);
        return R.ok();
    }

    // ------------------------------------------------------------------ 麦位

    @ApiOperation("申请上麦")
    @PostMapping("/{id}/mic/apply")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Map<String, Object>> applyMic(@PathVariable Long id) {
        return R.ok(micService.apply(id, UserContext.requireUserId()));
    }

    @ApiOperation("下麦")
    @PostMapping("/{id}/mic/leave")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> leaveMic(@PathVariable Long id) {
        micService.leave(id, UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation("审批上麦")
    @PostMapping("/{id}/mic/approve")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<Void> approveMic(@PathVariable Long id, @Valid @RequestBody MicApproveReq req) {
        micService.approve(id, UserContext.requireUserId(), req.getUserId(),
                Boolean.TRUE.equals(req.getApprove()));
        return R.ok();
    }

    @ApiOperation("麦位列表")
    @GetMapping("/{id}/mics")
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<List<MicVO>> mics(@PathVariable Long id) {
        return R.ok(roomService.mics(id));
    }

    @ApiOperation("锁定/解锁麦位")
    @PostMapping("/{id}/mic/lock")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> lockMic(@PathVariable Long id, @Valid @RequestBody MicLockReq req) {
        micService.lock(id, UserContext.requireUserId(), req);
        return R.ok();
    }

    // ------------------------------------------------------------------ 点歌队列

    @ApiOperation(value = "点歌", notes = "need_approve=1 时进入待审区；房主/房管点歌直接入队；"
            + "同一首歌重复点会合并（BR-22-025），每人 5 次/分钟")
    @PostMapping("/{id}/queue")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 5, windowSeconds = 60, type = "room")
    public R<QueueItemVO> addSong(@PathVariable Long id, @Valid @RequestBody QueueAddReq req) {
        return R.ok(queueService.add(id, UserContext.requireUserId(), req.getSongId()));
    }

    @ApiOperation("队列列表")
    @GetMapping("/{id}/queue")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "room")
    public R<List<QueueItemVO>> queue(@PathVariable Long id) {
        return R.ok(queueService.list(id));
    }

    @ApiOperation("审批点歌")
    @PostMapping("/{id}/queue/{queueId}/approve")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<Void> approveSong(@PathVariable Long id, @PathVariable Long queueId,
                               @RequestParam(defaultValue = "true") boolean approve) {
        queueService.approve(id, UserContext.requireUserId(), queueId, approve);
        return R.ok();
    }

    @ApiOperation("删除队列歌曲")
    @DeleteMapping("/{id}/queue/{queueId}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<Void> removeSong(@PathVariable Long id, @PathVariable Long queueId) {
        queueService.remove(id, UserContext.requireUserId(), queueId);
        return R.ok();
    }

    @ApiOperation("清空队列")
    @DeleteMapping("/{id}/queue")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 10, windowSeconds = 60, type = "room")
    public R<Map<String, Object>> clearQueue(@PathVariable Long id) {
        int removed = queueService.clear(id, UserContext.requireUserId());
        return R.ok(java.util.Collections.<String, Object>singletonMap("removed", removed));
    }

    @ApiOperation("房主置顶/取消置顶队列项（BR-22-024）")
    @PostMapping("/{id}/queue/{queueId}/priority")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<QueueItemVO> priority(@PathVariable Long id, @PathVariable Long queueId,
                                   @Valid @RequestBody QueuePriorityReq req) {
        return R.ok(queueService.pin(id, UserContext.requireUserId(), queueId,
                Boolean.TRUE.equals(req.getPinned())));
    }

    // ------------------------------------------------------------------ 权威播放态 / 投票（模块 13）

    @ApiOperation(value = "当前权威播放态", notes = "客户端用 stateUpdateTimeMs 计算应到进度，"
            + "只应用 playStateVersion 更大的状态（BR-22-009）")
    @GetMapping("/{id}/play-state")
    @RateLimit(limit = 600, windowSeconds = 60, type = "room")
    public R<RoomPlayStateVO> playState(@PathVariable Long id) {
        return R.ok(playService.current(id));
    }

    @ApiOperation(value = "HTTP 控播", notes = "action=play/pause/seek/switch/buffering/end；"
            + "与 WS room.play 等消息共用同一状态机")
    @PostMapping("/{id}/play")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<RoomPlayStateVO> play(@PathVariable Long id,
                                   @Valid @RequestBody PlayControlReq req) {
        return R.ok(playService.control(id, UserContext.requireUserId(), req));
    }

    @ApiOperation("发起跳过当前歌曲投票（60 秒，BR-22-033）")
    @PostMapping("/{id}/votes")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 5, windowSeconds = 300, type = "room")
    public R<RoomVoteVO> startVote(@PathVariable Long id) {
        return R.ok(voteService.start(id, UserContext.requireUserId()));
    }

    @ApiOperation("同意跳过当前歌曲")
    @PostMapping("/{id}/votes/{voteId}/agree")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<RoomVoteVO> agreeVote(@PathVariable Long id, @PathVariable Long voteId) {
        return R.ok(voteService.agree(id, UserContext.requireUserId(), voteId));
    }

    @ApiOperation("当前进行中(或最近一次)的跳过投票")
    @GetMapping("/{id}/votes/current")
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<RoomVoteVO> currentVote(@PathVariable Long id) {
        return R.ok(voteService.current(id, UserContext.getUserId()));
    }

    @ApiOperation("房间榜单 Top N（type=gift/active/request/listen）")
    @GetMapping("/{id}/rank")
    @RateLimit(limit = 300, windowSeconds = 60, type = "room")
    public R<List<RoomRankEntryVO>> rank(@PathVariable Long id,
                                         @RequestParam(defaultValue = "active") String type,
                                         @RequestParam(defaultValue = "10") int limit) {
        return R.ok(rankService.top(id, type, limit));
    }

    @ApiOperation("选择是否上榜（隐私开关）")
    @PostMapping("/{id}/rank/hidden")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Void> rankHidden(@PathVariable Long id, @Valid @RequestBody RankHiddenReq req) {
        rankService.setHidden(id, UserContext.requireUserId(), Boolean.TRUE.equals(req.getHidden()));
        return R.ok();
    }

    @ApiOperation("给当前歌曲点赞（每人每歌一次）")
    @PostMapping("/{id}/like")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 60, windowSeconds = 60, type = "room")
    public R<Map<String, Object>> like(@PathVariable Long id) {
        return R.ok(rankService.like(id, UserContext.requireUserId()));
    }

    @ApiOperation("上报收听时长（服务端按真实间隔截断）")
    @PostMapping("/{id}/listen")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 30, windowSeconds = 60, type = "room")
    public R<Map<String, Object>> listen(@PathVariable Long id,
                                         @Valid @RequestBody ListenReportReq req) {
        return R.ok(rankService.reportListen(id, UserContext.requireUserId(), req.getSeconds()));
    }

    // ------------------------------------------------------------------ 生命周期

    @ApiOperation("关闭房间")
    @PostMapping("/{id}/close")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 10, windowSeconds = 60, type = "room")
    public R<Void> close(@PathVariable Long id) {
        roomService.close(id, UserContext.requireUserId());
        return R.ok();
    }

    @ApiOperation(value = "重开房间", notes = "仅关闭状态且关闭时间在 24 小时内")
    @PostMapping("/{id}/reopen")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 10, windowSeconds = 60, type = "room")
    public R<RoomDetailVO> reopen(@PathVariable Long id) {
        return R.ok(roomService.reopen(id, UserContext.requireUserId()));
    }

    @ApiOperation(value = "解散房间", notes = "不可恢复；body 需回传房间名确认")
    @DeleteMapping("/{id}")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 10, windowSeconds = 60, type = "room")
    public R<Void> dissolve(@PathVariable Long id,
                            @Valid @RequestBody(required = false) DissolveReq req) {
        roomService.dissolve(id, UserContext.requireUserId(),
                req == null ? null : req.getConfirmName());
        return R.ok();
    }

    @ApiOperation("转让房主")
    @PostMapping("/{id}/transfer")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 10, windowSeconds = 60, type = "room")
    public R<Void> transfer(@PathVariable Long id, @Valid @RequestBody UserTargetReq req) {
        roomService.transfer(id, UserContext.requireUserId(), req.getUserId());
        return R.ok();
    }

    @ApiOperation("房间历史消息（断线补偿）")
    @GetMapping("/{id}/messages")
    @RateLimit(dimension = RateLimitDimension.USER, limit = 120, windowSeconds = 60, type = "room")
    public R<List<RoomMessage>> messages(@PathVariable Long id,
                                         @RequestParam(defaultValue = "0") long sinceId,
                                         @RequestParam(defaultValue = "50") int limit) {
        UserContext.requireUserId();
        return R.ok(roomMessageService.listSince(id, sinceId, limit));
    }
}
