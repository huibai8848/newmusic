package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.contentsafety.dto.AuditRequest;
import com.example.newmusic.module.contentsafety.dto.AuditResult;
import com.example.newmusic.module.contentsafety.service.ContentAuditService;
import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.social.entity.PrivateMessage;
import com.example.newmusic.module.social.mapper.FollowMapper;
import com.example.newmusic.module.social.service.SocialNotifier;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.config.SocialUpgradeProperties;
import com.example.newmusic.module.socialupgrade.dto.MentionRef;
import com.example.newmusic.module.socialupgrade.dto.PostCreateReq;
import com.example.newmusic.module.socialupgrade.dto.PostUpdateReq;
import com.example.newmusic.module.socialupgrade.dto.RepostReq;
import com.example.newmusic.module.socialupgrade.entity.PostCollect;
import com.example.newmusic.module.socialupgrade.entity.PostLike;
import com.example.newmusic.module.socialupgrade.entity.PostMention;
import com.example.newmusic.module.socialupgrade.entity.Topic;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.mapper.PostCollectMapper;
import com.example.newmusic.module.socialupgrade.mapper.PostLikeMapper;
import com.example.newmusic.module.socialupgrade.mapper.TopicMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态发布、详情与互动（FR-25-003 / FR-25-004）。
 */
@Slf4j
@Service
public class PostService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> MILESTONES = new HashSet<>(Arrays.asList(
            1, 10, 50, 100, 500, 1000));

    private final UserPostMapper postMapper;
    private final PostLikeMapper likeMapper;
    private final PostCollectMapper collectMapper;
    private final TopicMapper topicMapper;
    private final UserProfileMapper profileMapper;
    private final SongMapper songMapper;
    private final PlaylistMapper playlistMapper;
    private final FollowMapper followMapper;
    private final SocialUserSupport users;
    private final SocialNotifier notifier;
    private final ProfileService profileService;
    private final PostContentGuard guard;
    private final MentionService mentionService;
    private final PostAssembler assembler;
    private final FeedService feedService;
    private final RedisOps redisOps;
    private final SocialUpgradeProperties properties;
    private final IntimacyService intimacyService;
    private final ContentAuditService contentAuditService;
    private final UserRestrictionService restrictionService;
    private final MinorProtectionService minorService;

    public PostService(UserPostMapper postMapper, PostLikeMapper likeMapper,
                       PostCollectMapper collectMapper, TopicMapper topicMapper,
                       UserProfileMapper profileMapper, SongMapper songMapper,
                       PlaylistMapper playlistMapper, FollowMapper followMapper,
                       SocialUserSupport users, SocialNotifier notifier,
                       ProfileService profileService, PostContentGuard guard,
                       MentionService mentionService, PostAssembler assembler,
                       FeedService feedService, RedisOps redisOps,
                       SocialUpgradeProperties properties, IntimacyService intimacyService,
                       ContentAuditService contentAuditService,
                       UserRestrictionService restrictionService,
                       MinorProtectionService minorService) {
        this.postMapper = postMapper;
        this.likeMapper = likeMapper;
        this.collectMapper = collectMapper;
        this.topicMapper = topicMapper;
        this.profileMapper = profileMapper;
        this.songMapper = songMapper;
        this.playlistMapper = playlistMapper;
        this.followMapper = followMapper;
        this.users = users;
        this.notifier = notifier;
        this.profileService = profileService;
        this.guard = guard;
        this.mentionService = mentionService;
        this.assembler = assembler;
        this.feedService = feedService;
        this.redisOps = redisOps;
        this.properties = properties;
        this.intimacyService = intimacyService;
        this.contentAuditService = contentAuditService;
        this.restrictionService = restrictionService;
        this.minorService = minorService;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public PostVO create(Long userId, PostCreateReq req, String ip) {
        User user = users.requireUser(userId);
        if (users.isBanned(user)) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }
        int type = req.getType() == null ? UserPost.TYPE_TEXT : req.getType();
        if (type == UserPost.TYPE_REPOST) {
            throw new BizException(ResultCode.PARAM_INVALID, "转发请使用转发接口");
        }
        List<String> images = req.getImages() == null ? Collections.<String>emptyList()
                : req.getImages();
        List<String> topics = normalizeTopics(req.getTopics(), req.getContent());
        int visibility = req.getVisibility() == null ? UserPost.VISIBILITY_PUBLIC
                : req.getVisibility();
        if (visibility < 0 || visibility > 2) {
            throw new BizException(ResultCode.PARAM_INVALID, "可见范围不合法");
        }
        validatePayload(type, req.getSongId(), req.getPlaylistId(), req.getLyricSnippet(), userId);

        UserProfile profile = profileService.requireProfile(userId);
        restrictionService.requireCanPublish(userId);
        minorService.requireSocialAllowed(userId);
        int status = guard.check(user, profile, req.getContent(), images,
                req.getSongId(), req.getPlaylistId(), req.getLyricSnippet(), topics);
        AuditRequest auditRequest = auditRequest("POST", userId,
                req.getContent(), req.getLyricSnippet(), images, "POST");
        AuditResult audit = contentAuditService.audit(auditRequest);
        if (audit.isReject()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容未通过安全审核");
        }
        if (audit.isReview()) {
            status = UserPost.STATUS_SELF_ONLY;
        }

        UserPost post = new UserPost();
        post.setUserId(userId);
        post.setType(type);
        post.setContent(req.getContent());
        post.setImages(toJson(images));
        post.setSongId(req.getSongId());
        post.setPlaylistId(req.getPlaylistId());
        post.setLyricSnippet(req.getLyricSnippet());
        post.setTopics(toJson(topics));
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setRepostCount(0);
        post.setCollectCount(0);
        post.setViewCount(0);
        post.setVisibility(visibility);
        post.setStatus(status);
        post.setAuditStatus(status == UserPost.STATUS_PUBLISHED ? 1 : 0);
        post.setImageAuditStatus(images.isEmpty() ? 1 : 0);
        post.setEdited(0);
        post.setIpRegion(regionOf(ip));
        postMapper.insert(post);
        contentAuditService.attachContent(audit, auditRequest, post.getId());

        profileMapper.incrPostCount(userId, 1);
        bumpTopics(topics, 1);

        if (status == UserPost.STATUS_PUBLISHED) {
            saveMentions(user, post, req.getMentions(), req.getContent());
            if (visibility != UserPost.VISIBILITY_SELF) {
                feedService.distribute(post);
            }
        }
        return assembler.one(post, userId);
    }

    private static AuditRequest auditRequest(String contentType, Long userId, String content,
                                             String lyricSnippet, List<String> images,
                                             String scene) {
        String text = (content == null ? "" : content)
                + (StringUtils.hasText(lyricSnippet) ? "\n" + lyricSnippet : "");
        AuditRequest request = AuditRequest.of(contentType, null, text, userId, scene);
        if (images != null && !images.isEmpty()) {
            request.setImages(images);
        }
        return request;
    }

    private void validatePayload(int type, Long songId, Long playlistId,
                                 String lyricSnippet, Long userId) {
        switch (type) {
            case UserPost.TYPE_SONG: {
                requireSong(songId);
                break;
            }
            case UserPost.TYPE_PLAYLIST:
            case UserPost.TYPE_PLAYLIST_CREATED: {
                requirePlaylist(playlistId, userId, type == UserPost.TYPE_PLAYLIST_CREATED);
                break;
            }
            case UserPost.TYPE_LYRIC: {
                requireSong(songId);
                if (!StringUtils.hasText(lyricSnippet)) {
                    throw new BizException(ResultCode.PARAM_INVALID, "请选择要分享的歌词");
                }
                break;
            }
            default:
                break;
        }
        if (type == UserPost.TYPE_TEXT && songId != null) {
            throw new BizException(ResultCode.PARAM_INVALID, "纯文本动态不能关联歌曲");
        }
    }

    private void requireSong(Long songId) {
        Song song = songId == null ? null : songMapper.selectById(songId);
        if (song == null || !Integer.valueOf(1).equals(song.getStatus())
                || (song.getAuditStatus() != null && !Integer.valueOf(1).equals(song.getAuditStatus()))) {
            throw new BizException(ResultCode.SONG_NOT_FOUND);
        }
    }

    private void requirePlaylist(Long playlistId, Long userId, boolean mustOwn) {
        Playlist playlist = playlistId == null ? null : playlistMapper.selectById(playlistId);
        if (playlist == null || !Integer.valueOf(1).equals(playlist.getStatus())
                || !Integer.valueOf(1).equals(playlist.getAuditStatus())) {
            throw new BizException(ResultCode.PLAYLIST_NOT_FOUND);
        }
        if (mustOwn && !Objects.equals(playlist.getUserId(), userId)) {
            throw new BizException(ResultCode.NO_PERMISSION_EDIT_PLAYLIST);
        }
        if (!mustOwn && !Objects.equals(playlist.getUserId(), userId)
                && !Integer.valueOf(1).equals(playlist.getIsPublic())) {
            throw new BizException(ResultCode.PLAYLIST_NOT_FOUND);
        }
    }

    private void saveMentions(User author, UserPost post, List<MentionRef> mentions, String content) {
        List<MentionRef> refs = mentions == null ? Collections.<MentionRef>emptyList() : mentions;
        mentionService.save(author, PostMention.SOURCE_POST, post.getId(), content, refs);
    }

    // ------------------------------------------------------------------ read

    public PostVO detail(Long viewerId, Long postId, String ip) {
        UserPost post = requireVisible(viewerId, postId);
        if (viewerId != null && !Objects.equals(viewerId, post.getUserId())) {
            postMapper.incrView(postId);
            post.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        }
        if (viewerId != null) {
            // 曝光计数用 Hash：发现页要按「看过 3 次不再推荐」精确判断次数（BR-25-062）
            redisOps.hashIncrement(RedisKeys.postSeen(viewerId), String.valueOf(postId), 1,
                    Duration.ofDays(7));
        }
        return assembler.one(post, viewerId);
    }

    /** 某用户主页的动态列表；自见时包含仅自己可见。 */
    public PageResult<PostVO> userPosts(Long targetUserId, Long viewerId, int pageNum, int pageSize) {
        users.requireUser(targetUserId);
        boolean self = Objects.equals(targetUserId, viewerId);
        boolean blocked = viewerId != null && users.blockedEither(viewerId, targetUserId);
        User target = users.requireUser(targetUserId);
        if (blocked || users.isBanned(target)
                || !profileService.canViewContent(targetUserId, viewerId)) {
            return PageResult.empty(pageNum, pageSize);
        }
        boolean followed = viewerId != null && !self && users.isFollowing(viewerId, targetUserId);

        Page<UserPost> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 50));
        QueryWrapper<UserPost> wrapper = new QueryWrapper<UserPost>()
                .eq("user_id", targetUserId).eq("deleted", 0)
                .orderByDesc("create_time").orderByDesc("id");
        if (self) {
            wrapper.in("status", UserPost.STATUS_PUBLISHED, UserPost.STATUS_SELF_ONLY,
                    UserPost.STATUS_AUDITING);
        } else {
            wrapper.eq("status", UserPost.STATUS_PUBLISHED);
            wrapper.in("visibility", followed
                    ? Arrays.asList(UserPost.VISIBILITY_PUBLIC, UserPost.VISIBILITY_FOLLOWERS)
                    : Collections.singletonList(UserPost.VISIBILITY_PUBLIC));
        }
        Page<UserPost> result = postMapper.selectPage(page, wrapper);
        List<PostVO> vos = assembler.assemble(result.getRecords(), viewerId);
        return PageResult.ofList(result, vos);
    }

    public UserPost requireVisible(Long viewerId, Long postId) {
        UserPost post = postId == null ? null : postMapper.selectById(postId);
        if (post == null || Integer.valueOf(UserPost.STATUS_DELETED).equals(post.getStatus())
                || Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(post.getStatus())) {
            throw new BizException(ResultCode.POST_NOT_FOUND);
        }
        User author = users.loadUsers(Collections.singletonList(post.getUserId()))
                .get(post.getUserId());
        if (author == null || users.isBanned(author)) {
            throw new BizException(ResultCode.POST_NOT_VISIBLE);
        }
        if (viewerId != null && users.blockedEither(viewerId, post.getUserId())) {
            throw new BizException(ResultCode.USER_BLACKLISTED);
        }
        if (Objects.equals(viewerId, post.getUserId())) {
            return post;
        }
        if (Integer.valueOf(UserPost.STATUS_SELF_ONLY).equals(post.getStatus())
                || Integer.valueOf(UserPost.STATUS_AUDITING).equals(post.getStatus())) {
            throw new BizException(ResultCode.POST_NOT_VISIBLE);
        }
        int visibility = post.getVisibility() == null ? 0 : post.getVisibility();
        if (visibility == UserPost.VISIBILITY_SELF) {
            throw new BizException(ResultCode.POST_NOT_VISIBLE);
        }
        if (visibility == UserPost.VISIBILITY_FOLLOWERS
                && (viewerId == null || !users.isFollowing(viewerId, post.getUserId()))) {
            throw new BizException(ResultCode.POST_NOT_VISIBLE);
        }
        return post;
    }

    // ------------------------------------------------------------------ edit / delete

    @Transactional
    public PostVO update(Long userId, Long postId, PostUpdateReq req, String ip) {
        UserPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(ResultCode.POST_NOT_FOUND);
        }
        if (!Objects.equals(post.getUserId(), userId)) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
        if (post.getCreateTime() != null && post.getCreateTime()
                .plusMinutes(properties.getPost().getEditMinutes()).isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.POST_EDIT_EXPIRED);
        }
        User user = users.requireUser(userId);
        String content = req.getContent() == null ? post.getContent() : req.getContent();
        List<String> images = req.getImages() == null ? parseList(post.getImages()) : req.getImages();
        List<String> topics = req.getTopics() == null ? parseList(post.getTopics())
                : normalizeTopics(req.getTopics(), content);
        if (req.getVisibility() != null) {
            if (req.getVisibility() < 0 || req.getVisibility() > 2) {
                throw new BizException(ResultCode.PARAM_INVALID, "可见范围不合法");
            }
            post.setVisibility(req.getVisibility());
        }
        restrictionService.requireCanPublish(userId);
        minorService.requireSocialAllowed(userId);
        int status = guard.recheck(userId, content, images, post.getSongId(),
                post.getPlaylistId(), post.getLyricSnippet(), topics);
        AuditRequest auditRequest = auditRequest("POST", userId, content,
                post.getLyricSnippet(), images, "POST_EDIT");
        AuditResult audit = contentAuditService.audit(auditRequest);
        if (audit.isReject()) {
            throw new BizException(ResultCode.POST_ILLEGAL, "内容未通过安全审核");
        }
        if (audit.isReview()) {
            status = UserPost.STATUS_SELF_ONLY;
        }

        mentionService.deleteBySource(PostMention.SOURCE_POST, postId);
        bumpTopics(parseList(post.getTopics()), -1);
        bumpTopics(topics, 1);

        post.setContent(content);
        post.setImages(toJson(images));
        post.setTopics(toJson(topics));
        post.setStatus(status);
        post.setAuditStatus(status == UserPost.STATUS_PUBLISHED ? 1 : 0);
        post.setEdited(1);
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
        contentAuditService.attachContent(audit, auditRequest, postId);
        if (status == UserPost.STATUS_PUBLISHED) {
            saveMentions(user, post, req.getMentions(), content);
            if (!Integer.valueOf(UserPost.VISIBILITY_SELF).equals(post.getVisibility())) {
                feedService.distribute(post);
            }
        }
        return assembler.one(post, userId);
    }

    @Transactional
    public void delete(Long userId, Long postId) {
        UserPost post = postMapper.selectById(postId);
        if (post == null) {
            return;
        }
        User operator = users.requireUser(userId);
        boolean mine = Objects.equals(post.getUserId(), userId);
        if (!mine && !users.isStaff(operator)) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
        if (Integer.valueOf(UserPost.STATUS_DELETED).equals(post.getStatus())
                || Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(post.getStatus())) {
            return;
        }
        bumpTopics(parseList(post.getTopics()), -1);
        postMapper.softDelete(postId);
        mentionService.deleteBySource(PostMention.SOURCE_POST, postId);
        profileMapper.incrPostCount(post.getUserId(), -1);
        if (post.getRepostPostId() != null) {
            postMapper.incrRepost(post.getRepostPostId(), -1);
        }
    }

    // ------------------------------------------------------------------ interactions

    @Transactional
    public PostVO like(Long userId, Long postId) {
        UserPost post = requireVisible(userId, postId);
        if (Objects.equals(post.getUserId(), userId)) {
            throw new BizException(ResultCode.SELF_INTERACTION_FORBIDDEN);
        }
        User actor = users.requireUser(userId);
        PostLike row = new PostLike();
        row.setId(IdWorker.getId());
        row.setUserId(userId);
        row.setPostId(postId);
        row.setCreateTime(LocalDateTime.now());
        if (likeMapper.insertIgnore(row.getId(), row.getUserId(), row.getPostId(), row.getCreateTime()) > 0) {
            postMapper.incrLike(postId, 1);
            profileMapper.incrLikeReceived(post.getUserId(), 1);
            int count = likeMapper.countByPost(postId);
            User owner = users.loadUsers(Collections.singletonList(post.getUserId()))
                    .get(post.getUserId());
            if (MILESTONES.contains(count)) {
                notifier.notify(post.getUserId(), Notification.TYPE_LIKE, "动态点赞里程碑",
                        "你的动态获得了 " + count + " 个赞", userId, actor,
                        Notification.TARGET_POST, postId);
            } else {
                notifier.notify(post.getUserId(), Notification.TYPE_LIKE, "收到新的点赞",
                        actor.getNickname() + " 赞了你的动态", userId, actor,
                        Notification.TARGET_POST, postId);
            }
            if (owner != null) {
                post.setLikeCount((post.getLikeCount() == null ? 0 : post.getLikeCount()) + 1);
            }
            intimacyService.record(userId, post.getUserId(), IntimacyService.WEIGHT_LIKE);
        }
        return assembler.one(post, userId);
    }

    @Transactional
    public PostVO unlike(Long userId, Long postId) {
        UserPost post = requireVisible(userId, postId);
        if (likeMapper.deletePair(userId, postId) > 0) {
            postMapper.incrLike(postId, -1);
            profileMapper.incrLikeReceived(post.getUserId(), -1);
            post.setLikeCount(Math.max(0, (post.getLikeCount() == null ? 0 : post.getLikeCount()) - 1));
        }
        return assembler.one(post, userId);
    }

    @Transactional
    public PostVO collect(Long userId, Long postId) {
        UserPost post = requireVisible(userId, postId);
        PostCollect row = new PostCollect();
        row.setId(IdWorker.getId());
        row.setUserId(userId);
        row.setPostId(postId);
        row.setCreateTime(LocalDateTime.now());
        if (collectMapper.insertIgnore(row.getId(), row.getUserId(), row.getPostId(), row.getCreateTime()) > 0) {
            postMapper.incrCollect(postId, 1);
            User actor = users.requireUser(userId);
            notifier.notify(post.getUserId(), Notification.TYPE_POST_COLLECT, "动态被收藏",
                    actor.getNickname() + " 收藏了你的动态", userId, actor,
                    Notification.TARGET_POST, postId);
            post.setCollectCount((post.getCollectCount() == null ? 0 : post.getCollectCount()) + 1);
        }
        return assembler.one(post, userId);
    }

    @Transactional
    public PostVO uncollect(Long userId, Long postId) {
        UserPost post = requireVisible(userId, postId);
        if (collectMapper.deletePair(userId, postId) > 0) {
            postMapper.incrCollect(postId, -1);
            post.setCollectCount(Math.max(0, (post.getCollectCount() == null ? 0 : post.getCollectCount()) - 1));
        }
        return assembler.one(post, userId);
    }

    @Transactional
    public PostVO repost(Long userId, Long postId, RepostReq req) {
        UserPost original = requireVisible(userId, postId);
        User user = users.requireUser(userId);
        if (users.isBanned(user)) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }
        String comment = req == null ? null : req.getContent();
        guard.checkRepost(comment);
        if (original.getRepostPostId() != null && comment == null) {
            // 转发别人的转发时只保留一层引用：内嵌指向最初的动态
            Long root = original.getRootPostId() != null ? original.getRootPostId()
                    : original.getRepostPostId();
            UserPost rootPost = postMapper.selectById(root);
            if (rootPost != null) {
                original = rootPost;
            }
        }

        UserPost post = new UserPost();
        post.setUserId(userId);
        post.setType(UserPost.TYPE_REPOST);
        post.setContent(comment);
        post.setImages("[]");
        post.setTopics("[]");
        post.setRepostPostId(original.getId());
        post.setRootPostId(original.getRootPostId() != null
                ? original.getRootPostId() : original.getId());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setRepostCount(0);
        post.setCollectCount(0);
        post.setViewCount(0);
        post.setVisibility(original.getVisibility() == null ? 0 : original.getVisibility());
        post.setStatus(UserPost.STATUS_PUBLISHED);
        post.setAuditStatus(1);
        post.setImageAuditStatus(1);
        post.setEdited(0);
        postMapper.insert(post);

        postMapper.incrRepost(original.getId(), 1);
        profileMapper.incrPostCount(userId, 1);
        if (!Integer.valueOf(UserPost.VISIBILITY_SELF).equals(post.getVisibility())) {
            feedService.distribute(post);
        }
        notifier.notify(original.getUserId(), Notification.TYPE_REPOST, "动态被转发",
                user.getNickname() + " 转发了你的动态", userId, user,
                Notification.TARGET_POST, original.getId());
        intimacyService.record(userId, original.getUserId(), IntimacyService.WEIGHT_REPOST);
        return assembler.one(post, userId);
    }

    /** 分享卡片信息；分享本身不产生额外计数。 */
    public Map<String, Object> share(Long viewerId, Long postId) {
        UserPost post = requireVisible(viewerId, postId);
        User author = users.loadUsers(Collections.singletonList(post.getUserId()))
                .get(post.getUserId());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("postId", post.getId());
        map.put("title", excerpt(post.getContent()));
        map.put("author", author == null ? null : author.getNickname());
        map.put("authorAvatar", author == null ? null : author.getAvatar());
        map.put("url", "/posts/" + post.getId());
        map.put("sharePath", "/pages/post/detail?id=" + post.getId());
        return map;
    }

    public void dislike(Long userId, Long postId) {
        requireVisible(userId, postId);
        redisOps.addToSet(RedisKeys.postDislike(userId), String.valueOf(postId),
                Duration.ofDays(30));
    }

    // ------------------------------------------------------------------ tabs

    public PageResult<PostVO> likedPosts(Long userId, int pageNum, int pageSize) {
        int size = Math.min(Math.max(1, pageSize), 50);
        int index = Math.max(1, pageNum);
        long offset = (long) (index - 1) * size;
        List<Long> ids = likeMapper.selectLikedPostIds(userId, offset, size);
        long total = likeMapper.countByUser(userId);
        return pages(ids, total, index, size, userId);
    }

    public PageResult<PostVO> collectedPosts(Long userId, int pageNum, int pageSize) {
        int size = Math.min(Math.max(1, pageSize), 50);
        int index = Math.max(1, pageNum);
        long offset = (long) (index - 1) * size;
        List<Long> ids = collectMapper.selectCollectedPostIds(userId, offset, size);
        long total = collectMapper.countByUser(userId);
        return pages(ids, total, index, size, userId);
    }

    private PageResult<PostVO> pages(List<Long> ids, long total, int pageNum, int size, Long viewerId) {
        PageResult<PostVO> result = new PageResult<>();
        result.setPageNum(pageNum);
        result.setPageSize(size);
        result.setTotal(total);
        result.setPages((int) ((total + size - 1) / size));
        result.setHasNext((long) pageNum * size < total);
        result.setTruncated(false);
        if (ids.isEmpty()) {
            result.setList(Collections.emptyList());
            return result;
        }
        Map<Long, UserPost> map = postMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(UserPost::getId, p -> p));
        List<UserPost> ordered = ids.stream().map(map::get).filter(Objects::nonNull)
                .collect(Collectors.toList());
        result.setList(assembler.assemble(ordered, viewerId));
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private void bumpTopics(List<String> topics, int delta) {
        if (topics == null || topics.isEmpty()) {
            return;
        }
        for (String topic : topics.stream().filter(StringUtils::hasText).distinct()
                .collect(Collectors.toList())) {
            if (delta > 0) {
                topicMapper.insertIgnore(IdWorker.getId(), topic);
            }
            Topic existing = topicMapper.selectByName(topic);
            if (existing != null) {
                topicMapper.incrPostCount(topic, delta);
            }
        }
    }

    private List<String> normalizeTopics(List<String> input, String content) {
        Collection<String> raw = input == null || input.isEmpty()
                ? guard.extractTopics(content) : input;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : raw) {
            if (!StringUtils.hasText(t)) {
                continue;
            }
            String clean = t.trim().replace("#", "");
            if (StringUtils.hasText(clean)) {
                set.add(clean);
            }
        }
        List<String> topics = new ArrayList<>(set);
        if (topics.size() > properties.getPost().getTopicsMax()) {
            throw new BizException(ResultCode.POST_TOPIC_LIMIT);
        }
        return topics;
    }

    private static String regionOf(String ip) {
        if (!StringUtils.hasText(ip)) {
            return null;
        }
        if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip.startsWith("172.18.")
                || ip.startsWith("172.19.") || ip.startsWith("172.2") || ip.startsWith("172.30.")
                || ip.startsWith("172.31.") || "0:0:0:0:0:0:0:1".equals(ip)
                || "::1".equals(ip)) {
            return "本地";
        }
        return "未知";
    }

    private static String excerpt(String content) {
        if (!StringUtils.hasText(content)) {
            return "分享一条动态";
        }
        return content.length() > 40 ? content.substring(0, 40) + "…" : content;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
