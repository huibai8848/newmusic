package com.example.newmusic.module.social.service;

import com.example.newmusic.module.socialupgrade.service.NotificationAggregator;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import org.springframework.stereotype.Component;

/**
 * 社交通知的统一出口（模块 10）。
 *
 * <p>三条硬规则集中在这里，避免每个业务服务各写一遍：
 * <ol>
 *   <li>自己触发的动作不通知自己；</li>
 *   <li>接收者关闭了对应分类就不落库；</li>
 *   <li>先落库再推送由 {@link NotificationService} 保证。</li>
 * </ol>
 */
@Component
public class SocialNotifier {

    private final NotificationService notificationService;
    private final NotificationSettingService settingService;
    private final NotificationAggregator aggregator;

    public SocialNotifier(NotificationService notificationService,
                          NotificationSettingService settingService,
                          NotificationAggregator aggregator) {
        this.notificationService = notificationService;
        this.settingService = settingService;
        this.aggregator = aggregator;
    }

    public void notify(Long userId, int notifyType, String title, String content,
                       Long fromUserId, User fromUser, int targetType, Long targetId) {
        if (userId == null || userId.equals(fromUserId)) {
            return;
        }
        if (!settingService.enabled(userId, notifyType)) {
            return;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setNotifyType(notifyType);
        n.setTitle(title);
        n.setContent(content);
        n.setFromUserId(fromUserId);
        if (fromUser != null) {
            n.setFromNickname(fromUser.getNickname());
            n.setFromAvatar(fromUser.getAvatar());
        }
        n.setTargetType(targetType);
        n.setTargetId(targetId);
        n.setIsRead(0);
        n.setCreateTime(java.time.LocalDateTime.now());
        // 模块 16：互动/粉丝/音乐提醒先尝试按时间桶聚合；系统与私信逐条落库。
        aggregator.notify(n);
    }
}
