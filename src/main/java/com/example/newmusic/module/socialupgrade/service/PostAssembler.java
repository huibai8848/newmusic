package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.UserBriefVO;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.mapper.PostCollectMapper;
import com.example.newmusic.module.socialupgrade.mapper.PostLikeMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 动态实体 → 展示对象；批量装载作者/歌曲/歌单/互动状态，避免列表 N+1。 */
@Component
public class PostAssembler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SocialUserSupport users;
    private final SongMapper songMapper;
    private final PlaylistMapper playlistMapper;
    private final UserPostMapper postMapper;
    private final PostLikeMapper likeMapper;
    private final PostCollectMapper collectMapper;
    private final MentionService mentionService;

    public PostAssembler(SocialUserSupport users, SongMapper songMapper, PlaylistMapper playlistMapper,
                         UserPostMapper postMapper, PostLikeMapper likeMapper,
                         PostCollectMapper collectMapper, MentionService mentionService) {
        this.users = users;
        this.songMapper = songMapper;
        this.playlistMapper = playlistMapper;
        this.postMapper = postMapper;
        this.likeMapper = likeMapper;
        this.collectMapper = collectMapper;
        this.mentionService = mentionService;
    }

    public List<PostVO> assemble(List<UserPost> posts, Long viewerId) {
        return assemble(posts, viewerId, true);
    }

    public List<PostVO> assemble(List<UserPost> posts, Long viewerId, boolean withOriginal) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> authorIds = posts.stream().map(UserPost::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, UserBriefVO> authors = new HashMap<>();
        for (UserBriefVO brief : users.briefs(authorIds, viewerId)) {
            authors.put(brief.getId(), brief);
        }

        List<Long> postIds = posts.stream().map(UserPost::getId).collect(Collectors.toList());
        Set<Long> liked = viewerId == null ? Collections.emptySet()
                : new HashSet<>(likeMapper.selectLikedIds(viewerId, postIds));
        Set<Long> collected = viewerId == null ? Collections.emptySet()
                : new HashSet<>(collectMapper.selectCollectedIds(viewerId, postIds));

        Set<Long> songIds = posts.stream().map(UserPost::getSongId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> playlistIds = posts.stream().map(UserPost::getPlaylistId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Song> songs = new HashMap<>();
        if (!songIds.isEmpty()) {
            for (Song song : songMapper.selectBatchIds(songIds)) {
                songs.put(song.getId(), song);
            }
        }
        Map<Long, Playlist> playlists = new HashMap<>();
        if (!playlistIds.isEmpty()) {
            for (Playlist playlist : playlistMapper.selectBatchIds(playlistIds)) {
                playlists.put(playlist.getId(), playlist);
            }
        }

        Map<Long, UserPost> originals = new HashMap<>();
        if (withOriginal) {
            Set<Long> originalIds = posts.stream().map(UserPost::getRepostPostId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (!originalIds.isEmpty()) {
                for (UserPost p : postMapper.selectBatchIds(originalIds)) {
                    originals.put(p.getId(), p);
                }
            }
        }
        // 原动态的作者也要装载，避免内嵌卡片显示空昵称
        Set<Long> missingAuthors = originals.values().stream().map(UserPost::getUserId)
                .filter(id -> !authors.containsKey(id)).collect(Collectors.toSet());
        if (!missingAuthors.isEmpty()) {
            for (UserBriefVO brief : users.briefs(missingAuthors, viewerId)) {
                authors.put(brief.getId(), brief);
            }
        }

        List<PostVO> out = new ArrayList<>(posts.size());
        for (UserPost post : posts) {
            PostVO vo = toVO(post, viewerId, authors, liked, collected, songs, playlists);
            if (withOriginal && post.getRepostPostId() != null) {
                UserPost original = originals.get(post.getRepostPostId());
                if (original == null || Integer.valueOf(UserPost.STATUS_DELETED).equals(original.getStatus())
                        || Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(original.getStatus())) {
                    PostVO deleted = new PostVO();
                    deleted.setId(post.getRepostPostId());
                    deleted.setDeleted(true);
                    deleted.setContent("原动态已删除");
                    vo.setOriginal(deleted);
                } else {
                    vo.setOriginal(toVO(original, viewerId, authors, liked, collected, songs, playlists));
                }
            }
            out.add(vo);
        }
        return out;
    }

    public PostVO one(UserPost post, Long viewerId) {
        return assemble(Collections.singletonList(post), viewerId).get(0);
    }

    private PostVO toVO(UserPost post, Long viewerId, Map<Long, UserBriefVO> authors,
                        Set<Long> liked, Set<Long> collected, Map<Long, Song> songs,
                        Map<Long, Playlist> playlists) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setAuthor(authors.get(post.getUserId()));
        vo.setType(post.getType());
        vo.setContent(post.getContent());
        vo.setImages(parseList(post.getImages()));
        vo.setTopics(parseList(post.getTopics()));
        vo.setLyricSnippet(post.getLyricSnippet());
        vo.setRepostPostId(post.getRepostPostId());
        vo.setRootPostId(post.getRootPostId());
        vo.setLikeCount(post.getLikeCount() == null ? 0 : post.getLikeCount());
        vo.setCommentCount(post.getCommentCount() == null ? 0 : post.getCommentCount());
        vo.setRepostCount(post.getRepostCount() == null ? 0 : post.getRepostCount());
        vo.setCollectCount(post.getCollectCount() == null ? 0 : post.getCollectCount());
        vo.setViewCount(post.getViewCount() == null ? 0 : post.getViewCount());
        vo.setVisibility(post.getVisibility());
        vo.setStatus(post.getStatus());
        vo.setEdited(Integer.valueOf(1).equals(post.getEdited()));
        vo.setIpRegion(post.getIpRegion());
        vo.setCreateTime(post.getCreateTime());
        vo.setLiked(liked.contains(post.getId()));
        vo.setCollected(collected.contains(post.getId()));
        vo.setAuthorSelf(Objects.equals(post.getUserId(), viewerId));
        vo.setDeleted(false);
        vo.setMentionedUsers(mentionService.render(1, post.getId(), viewerId));

        if (post.getSongId() != null) {
            PostVO.SongCard card = new PostVO.SongCard();
            Song song = songs.get(post.getSongId());
            card.setId(post.getSongId());
            if (song == null) {
                card.setName("歌曲已下架");
                card.setAvailable(false);
            } else {
                card.setName(song.getName());
                card.setCover(song.getCover());
                card.setArtistName(song.getArtistName());
                card.setAvailable(Integer.valueOf(1).equals(song.getStatus())
                        && (song.getAuditStatus() == null || Integer.valueOf(1).equals(song.getAuditStatus())));
                if (!card.getAvailable()) {
                    card.setName("歌曲已下架");
                }
            }
            vo.setSong(card);
        }
        if (post.getPlaylistId() != null) {
            PostVO.PlaylistCard card = new PostVO.PlaylistCard();
            Playlist playlist = playlists.get(post.getPlaylistId());
            card.setId(post.getPlaylistId());
            if (playlist == null) {
                card.setName("歌单已删除");
                card.setAvailable(false);
            } else {
                card.setName(playlist.getName());
                card.setCover(playlist.getCover());
                card.setAvailable(Integer.valueOf(1).equals(playlist.getStatus())
                        && Integer.valueOf(1).equals(playlist.getAuditStatus()));
                if (!card.getAvailable()) {
                    card.setName("歌单已下架");
                }
            }
            vo.setPlaylist(card);
        }
        return vo;
    }

    private static List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
