package com.example.newmusic.module.socialupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.vo.PrivateMessageVO;
import com.example.newmusic.module.socialupgrade.dto.AddMembersReq;
import com.example.newmusic.module.socialupgrade.dto.ConversationSettingReq;
import com.example.newmusic.module.socialupgrade.dto.CreateConversationReq;
import com.example.newmusic.module.socialupgrade.dto.GroupCreateReq;
import com.example.newmusic.module.socialupgrade.dto.GroupMessageReq;
import com.example.newmusic.module.socialupgrade.service.ConversationUpgradeService;
import com.example.newmusic.module.socialupgrade.vo.ConversationItemVO;
import com.example.newmusic.module.socialupgrade.vo.ConversationSearchVO;
import com.example.newmusic.module.socialupgrade.vo.GroupMemberVO;
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

/**
 * 会话（单聊/群聊）统一入口（FR-25-010）。
 *
 * <p>旧的 {@code /api/v1/messages} 接口保持原样，只服务单聊；这里额外承担群聊、
 * 归档/置顶/免打扰、搜索、引用和转发。
 */
@Api(tags = "社交升级-会话")
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationUpgradeService service;

    public ConversationController(ConversationUpgradeService service) {
        this.service = service;
    }

    @ApiOperation("会话列表（单聊+群聊）")
    @GetMapping
    public R<PageResult<ConversationItemVO>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(defaultValue = "false") boolean archived) {
        return R.ok(service.conversations(UserContext.requireUserId(), page, size, archived));
    }

    @ApiOperation("全部会话未读数（含群聊）")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(service.unreadTotal(UserContext.requireUserId()));
    }

    @ApiOperation("搜索会话名称与消息内容")
    @GetMapping("/search")
    public R<ConversationSearchVO> search(@RequestParam String keyword,
                                          @RequestParam(defaultValue = "10") int limit) {
        return R.ok(service.search(UserContext.requireUserId(), keyword, limit));
    }

    @ApiOperation(value = "会话消息", notes = "cursor 为上一页最后一条消息 ID（beforeId），留空取最新一页")
    @GetMapping("/{id}/messages")
    public R<List<PrivateMessageVO>> messages(@PathVariable Long id,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "50") int limit) {
        return R.ok(service.messages(UserContext.requireUserId(), id, parseCursor(cursor), limit));
    }

    @ApiOperation("发送消息（群聊可在 body 带 quoteMsgId；单聊继续走 /api/v1/messages 也可）")
    @PostMapping("/{id}/messages")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_msg")
    public R<PrivateMessageVO> send(@PathVariable Long id,
                                    @Valid @RequestBody GroupMessageReq req) {
        return R.ok(service.send(UserContext.requireUserId(), id, req));
    }

    @ApiOperation("创建单聊会话（不发送消息）")
    @PostMapping
    public R<ConversationItemVO> create(@Valid @RequestBody CreateConversationReq req) {
        return R.ok(service.createSingle(UserContext.requireUserId(), req.getTargetUserId()));
    }

    @ApiOperation("创建群聊（3~20 人，创建者为群主）")
    @PostMapping("/group")
    public R<ConversationItemVO> createGroup(@Valid @RequestBody GroupCreateReq req) {
        return R.ok(service.createGroup(UserContext.requireUserId(), req));
    }

    @ApiOperation("置顶/取消置顶（最多 3 个）")
    @PutMapping("/{id}/pin")
    public R<ConversationItemVO> pin(@PathVariable Long id,
                                     @Valid @RequestBody ConversationSettingReq req) {
        return R.ok(service.pin(UserContext.requireUserId(), id, req.getEnabled()));
    }

    @ApiOperation("归档/取消归档（最多 100 个）")
    @PutMapping("/{id}/archive")
    public R<ConversationItemVO> archive(@PathVariable Long id,
                                         @Valid @RequestBody ConversationSettingReq req) {
        return R.ok(service.archive(UserContext.requireUserId(), id, req.getEnabled()));
    }

    @ApiOperation("免打扰开关")
    @PutMapping("/{id}/mute")
    public R<ConversationItemVO> mute(@PathVariable Long id,
                                      @Valid @RequestBody ConversationSettingReq req) {
        return R.ok(service.mute(UserContext.requireUserId(), id, req.getEnabled()));
    }

    @ApiOperation("标记会话已读（群聊按最后消息更新已读游标）")
    @PutMapping("/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        service.read(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation(value = "转发消息到目标会话", notes = "BR-25-111：转发不展示原发送者")
    @PostMapping("/{id}/forward/{messageId}")
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_msg")
    public R<PrivateMessageVO> forward(@PathVariable Long id, @PathVariable Long messageId) {
        return R.ok(service.forward(UserContext.requireUserId(), messageId, id));
    }

    @ApiOperation("群成员列表")
    @GetMapping("/{id}/members")
    public R<List<GroupMemberVO>> members(@PathVariable Long id) {
        return R.ok(service.members(UserContext.requireUserId(), id));
    }

    @ApiOperation("邀请入群")
    @PostMapping("/{id}/members")
    public R<Void> addMembers(@PathVariable Long id,
                              @Valid @RequestBody AddMembersReq req) {
        service.addMembers(UserContext.requireUserId(), id, req);
        return R.ok();
    }

    @ApiOperation("移除群成员（群主）")
    @DeleteMapping("/{id}/members/{userId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        service.removeMember(UserContext.requireUserId(), id, userId);
        return R.ok();
    }

    @ApiOperation("退出群聊（群主需先解散）")
    @DeleteMapping("/{id}/members/me")
    public R<Void> quit(@PathVariable Long id) {
        service.quit(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("解散群聊（群主）")
    @DeleteMapping("/{id}")
    public R<Void> dissolve(@PathVariable Long id) {
        service.dissolve(UserContext.requireUserId(), id);
        return R.ok();
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "cursor 必须是消息 ID");
        }
    }
}
