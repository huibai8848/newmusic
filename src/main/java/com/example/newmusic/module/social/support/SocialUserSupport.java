package com.example.newmusic.module.social.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 社交用户读取与关系判定（模块 10）。
 */
@Component
public class SocialUserSupport {

    private final UserMapper userMapper;
    private final FollowMapper followMapper;
    private final BlacklistMapper blacklistMapper;
    private final RedisOps redisOps;

    public SocialUserSupport(UserMapper userMapper, FollowMapper followMapper,
                             BlacklistMapper blacklistMapper, RedisOps redisOps) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
        this.blacklistMapper = blacklistMapper;
        this.redisOps = redisOps;
    }

    public User requireUser(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || Integer.valueOf(4).equals(user.getStatus())) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public Map<Long, User> loadUsers(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, User> map = new HashMap<>();
        for (User user : userMapper.selectBatchIds(distinct)) {
            map.put(user.getId(), user);
        }
        return map;
    }

    /**
     * 双方任一方向拉黑都视为不可互动（PRD FR-19-010：互拉黑双向隐藏）。
     *
     * <p>先查 Redis，未命中再落库：Redis 只是加速镜像，丢了不能改变结论。
     */
    public boolean blockedEither(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        return blockedBy(a, b) || blockedBy(b, a);
    }

    /** owner 是否拉黑了 actor。 */
    public boolean blockedBy(Long owner, Long actor) {
        if (owner == null || actor == null) {
            return false;
        }
        String key = RedisKeys.socialBlacklist(owner);
        if (redisOps.isMember(key, String.valueOf(actor))) {
            return true;
        }
        return blacklistMapper.exists(owner, actor) > 0;
    }

    public boolean isFollowing(Long followerId, Long followingId) {
        if (followerId == null || followingId == null) {
            return false;
        }
        if (redisOps.isMember(RedisKeys.socialFollow(followerId), String.valueOf(followingId))) {
            return true;
        }
        return followMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.example.newmusic.module.social.entity.Follow>()
                .eq("follower_id", followerId).eq("following_id", followingId)) > 0;
    }

    public boolean isMutual(Long a, Long b) {
        return a != null && b != null && !a.equals(b)
                && isFollowing(a, b) && isFollowing(b, a);
    }

    public UserBriefVO brief(Long userId, Long viewerId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        UserBriefVO vo = new UserBriefVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setSignature(user.getSignature());
        if (viewerId != null && !viewerId.equals(userId)) {
            boolean followed = isFollowing(viewerId, userId);
            vo.setFollowed(followed);
            vo.setMutual(followed && isFollowing(userId, viewerId));
        } else {
            vo.setFollowed(false);
            vo.setMutual(false);
        }
        return vo;
    }

    public List<UserBriefVO> briefs(Collection<Long> ids, Long viewerId) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> users = loadUsers(ids);
        Set<Long> followed = followedIds(viewerId, ids);
        return ids.stream().filter(Objects::nonNull).distinct()
                .map(users::get).filter(Objects::nonNull)
                .map(user -> {
                    UserBriefVO vo = new UserBriefVO();
                    vo.setId(user.getId());
                    vo.setNickname(user.getNickname());
                    vo.setAvatar(user.getAvatar());
                    vo.setSignature(user.getSignature());
                    boolean isFollowed = followed.contains(user.getId());
                    vo.setFollowed(isFollowed);
                    vo.setMutual(isFollowed && isFollowing(user.getId(), viewerId));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private Set<Long> followedIds(Long viewerId, Collection<Long> ids) {
        if (viewerId == null || ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return Collections.emptySet();
        }
        return new java.util.HashSet<>(followMapper.selectFollowingIds(viewerId, distinct));
    }

    public boolean isStaff(User user) {
        return user != null && user.getUserType() != null && user.getUserType() >= 2;
    }

    public boolean isBanned(User user) {
        return user != null && Integer.valueOf(2).equals(user.getStatus());
    }
}
