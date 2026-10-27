package com.example.newmusic.module.social.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.infra.ratelimit.RateLimitDimension;
import com.example.newmusic.module.social.dto.MessageReadReq;
import com.example.newmusic.module.social.dto.MessageSendReq;
import com.example.newmusic.module.social.service.MessageService;
import com.example.newmusic.module.social.vo.ConversationVO;
import com.example.newmusic.module.social.vo.PrivateMessageVO;
import com.example.newmusic.module.socialupgrade.service.ConversationUpgradeService;
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

@Api(tags = "社交-私信")
@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final MessageService messageService;
    private final ConversationUpgradeService conversationUpgradeService;

    public MessageController(MessageService messageService,
                             ConversationUpgradeService conversationUpgradeService) {
        this.messageService = messageService;
        this.conversationUpgradeService = conversationUpgradeService;
    }

    @ApiOperation("会话列表")
    @GetMapping("/conversations")
    public R<PageResult<ConversationVO>> conversations(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(messageService.conversations(UserContext.requireUserId(), page, size));
    }

    @ApiOperation("会话消息（按 ID 倒序游标拉取）")
    @GetMapping
    public R<List<PrivateMessageVO>> messages(@RequestParam Long conversationId,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return R.ok(messageService.messages(UserContext.requireUserId(), conversationId,
                beforeId, limit));
    }

    @ApiOperation("发送私信")
    @PostMapping
    @RateLimit(limit = 30, windowSeconds = 60, dimension = RateLimitDimension.USER, type = "social_msg")
    public R<PrivateMessageVO> send(@Valid @RequestBody MessageSendReq req) {
        return R.ok(messageService.send(UserContext.requireUserId(), req));
    }

    @ApiOperation("标记会话已读")
    @PutMapping("/read")
    public R<Integer> read(@Valid @RequestBody MessageReadReq req) {
        return R.ok(messageService.read(UserContext.requireUserId(), req.getConversationId()));
    }

    @ApiOperation(value = "撤回消息", notes = "仅发送者本人，2 分钟内可撤回。")
    @PostMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable Long id) {
        messageService.revoke(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("删除消息（仅对自己隐藏）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        messageService.delete(UserContext.requireUserId(), id);
        return R.ok();
    }

    @ApiOperation("全部会话未读数（含群聊）")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(conversationUpgradeService.unreadTotal(UserContext.requireUserId()));
    }
}
