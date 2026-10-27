package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.entity.Blacklist;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.BlacklistVO;
import com.example.newmusic.module.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 黑名单（模块 10 FR-19-010）。
 *
 * <p>拉黑是单向关系，但**互动过滤是双向的**：我拉黑你、或你拉黑我，
 * 双方都不能再评论/私信/关注对方。拉黑时自动解除双向关注，但不发通知。
 */
@Service
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final FollowMapper followMapper;
    private final SocialUserSupport users;
    private final RedisOps redisOps;
    private final SocialProperties properties;

    public BlacklistService(BlacklistMapper blacklistMapper, FollowMapper followMapper,
                            SocialUserSupport users, RedisOps redisOps, SocialProperties properties) {
        this.blacklistMapper = blacklistMapper;
        this.followMapper = followMapper;
        this.users = users;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    @Transactional
    public void add(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BizException(ResultCode.SELF_INTERACTION_FORBIDDEN, "不能拉黑自己");
        }
        users.requireUser(targetUserId);
        if (blacklistMapper.exists(userId, targetUserId) > 0) {
            return;
        }
        if (blacklistMapper.countByUser(userId) >= properties.getBlacklist().getLimit()) {
            throw new BizException(ResultCode.BLACKLIST_LIMIT);
        }
        blacklistMapper.insertIgnore(IdWorker.getId(), userId, targetUserId, LocalDateTime.now());

        // 自动解除双向关注（PRD FR-19-010），不产生通知
        followMapper.deleteByPair(userId, targetUserId);
        followMapper.deleteByPair(targetUserId, userId);
        redisOps.removeFromSet(RedisKeys.socialFollow(userId), String.valueOf(targetUserId));
        redisOps.removeFromSet(RedisKeys.socialFans(targetUserId), String.valueOf(userId));
        redisOps.removeFromSet(RedisKeys.socialFollow(targetUserId), String.valueOf(userId));
        redisOps.removeFromSet(RedisKeys.socialFans(userId), String.valueOf(targetUserId));

        redisOps.addToSet(RedisKeys.socialBlacklist(userId), String.valueOf(targetUserId), null);
        redisOps.addToSet(RedisKeys.socialBlockedBy(targetUserId), String.valueOf(userId), null);
    }

    @Transactional
    public void remove(Long userId, Long targetUserId) {
        int deleted = blacklistMapper.deleteByPair(userId, targetUserId);
        if (deleted > 0) {
            redisOps.removeFromSet(RedisKeys.socialBlacklist(userId), String.valueOf(targetUserId));
            redisOps.removeFromSet(RedisKeys.socialBlockedBy(targetUserId), String.valueOf(userId));
        }
    }

    public PageResult<BlacklistVO> page(Long userId, int pageNum, int pageSize) {
        Page<Blacklist> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<Blacklist> wrapper = new QueryWrapper<Blacklist>()
                .eq("user_id", userId).orderByDesc("create_time").orderByDesc("id");
        Page<Blacklist> result = blacklistMapper.selectPage(page, wrapper);
        List<Long> ids = result.getRecords().stream()
                .map(Blacklist::getBlockedUserId).collect(Collectors.toList());
        Map<Long, User> userMap = users.loadUsers(ids);
        return PageResult.of(result, item -> {
            BlacklistVO vo = new BlacklistVO();
            vo.setUserId(item.getBlockedUserId());
            User user = userMap.get(item.getBlockedUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
            }
            vo.setCreateTime(item.getCreateTime());
            return vo;
        }, false);
    }
}
