package com.example.newmusic.module.social.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.room.entity.Room;
import com.example.newmusic.module.room.mapper.RoomMapper;
import com.example.newmusic.module.social.entity.Comment;
import com.example.newmusic.module.social.entity.PrivateMessage;
import com.example.newmusic.module.social.mapper.CommentMapper;
import com.example.newmusic.module.social.mapper.PrivateMessageMapper;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.mapper.UserMapper;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.ws.entity.RoomMessage;
import com.example.newmusic.module.ws.mapper.RoomMessageMapper;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 互动目标校验与展示信息解析（模块 10）。
 *
 * <p>所有「这个 ID 是什么、还在不在、叫什么」的判断集中在这里，避免
 * 点赞/收藏/评论/举报/分享各自写一份 switch 后逐渐不一致。
 */
@Component
public class SocialTargets {

    public static final String SONG = "SONG";
    public static final String PLAYLIST = "PLAYLIST";
    public static final String ALBUM = "ALBUM";
    public static final String COMMENT = "COMMENT";
    public static final String ROOM = "ROOM";
    public static final String ROOM_MESSAGE = "ROOM_MESSAGE";
    public static final String USER = "USER";
    public static final String MESSAGE = "MESSAGE";
    public static final String LYRIC = "LYRIC";
    /** 模块 16：用户动态，可评论/举报，点赞走动态专用接口。 */
    public static final String POST = "POST";

    private static final Set<String> LIKE_TYPES = new HashSet<>(Arrays.asList(
            SONG, PLAYLIST, ALBUM, COMMENT, ROOM_MESSAGE));
    private static final Set<String> COLLECT_TYPES = new HashSet<>(Arrays.asList(
            SONG, PLAYLIST, ALBUM, ROOM));
    private static final Set<String> COMMENT_TYPES = new HashSet<>(Arrays.asList(
            SONG, PLAYLIST, ALBUM, ROOM, COMMENT, POST));
    private static final Set<String> REPORT_TYPES = new HashSet<>(Arrays.asList(
            SONG, COMMENT, USER, ROOM, MESSAGE, PLAYLIST, LYRIC, POST));
    private static final Set<String> SHARE_TYPES = new HashSet<>(Arrays.asList(
            SONG, PLAYLIST, ALBUM));

    private final SongMapper songMapper;
    private final PlaylistMapper playlistMapper;
    private final AlbumMapper albumMapper;
    private final RoomMapper roomMapper;
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;
    private final PrivateMessageMapper privateMessageMapper;
    private final RoomMessageMapper roomMessageMapper;
    private final LyricMapper lyricMapper;
    private final UserPostMapper userPostMapper;
    private final com.example.newmusic.module.social.mapper.SocialCounterMapper counters;

    public SocialTargets(com.example.newmusic.module.social.mapper.SocialCounterMapper counters,
                         SongMapper songMapper, PlaylistMapper playlistMapper, AlbumMapper albumMapper,
                         RoomMapper roomMapper, CommentMapper commentMapper, UserMapper userMapper,
                         PrivateMessageMapper privateMessageMapper, RoomMessageMapper roomMessageMapper,
                         LyricMapper lyricMapper, UserPostMapper userPostMapper) {
        this.counters = counters;
        this.songMapper = songMapper;
        this.playlistMapper = playlistMapper;
        this.albumMapper = albumMapper;
        this.roomMapper = roomMapper;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.privateMessageMapper = privateMessageMapper;
        this.roomMessageMapper = roomMessageMapper;
        this.lyricMapper = lyricMapper;
        this.userPostMapper = userPostMapper;
    }

    @Data
    public static class TargetInfo {
        private String targetType;
        private Long targetId;
        private String name;
        private String cover;
        private String subtitle;
        /** 内容归属人；歌曲/专辑返回歌手ID，用于权限判断时注意区分 */
        private Long ownerId;
        private boolean privatePlaylist;
        private boolean available;
        /** POST 专用：0公开 1仅关注者 2仅自己 */
        private Integer visibility;
    }

    public String normalize(String type) {
        if (type == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "缺少目标类型");
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    public String requireLikeType(String type) {
        String t = normalize(type);
        if (!LIKE_TYPES.contains(t)) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "不支持点赞该类型");
        }
        return t;
    }

    public String requireCollectType(String type) {
        String t = normalize(type);
        if (!COLLECT_TYPES.contains(t)) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "不支持收藏该类型");
        }
        return t;
    }

    public String requireCommentType(String type) {
        String t = normalize(type);
        if (!COMMENT_TYPES.contains(t)) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "不支持评论该类型");
        }
        return t;
    }

    public String requireReportType(String type) {
        String t = normalize(type);
        if (!REPORT_TYPES.contains(t)) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "不支持举报该类型");
        }
        return t;
    }

    public String requireShareType(String type) {
        String t = normalize(type);
        if (!SHARE_TYPES.contains(t)) {
            throw new BizException(ResultCode.SHARE_TARGET_INVALID);
        }
        return t;
    }

    /**
     * @param requireOnline true 时下架内容抛 {@code CONTENT_OFFLINE}
     * @throws BizException 目标不存在/已下架
     */
    public TargetInfo require(String type, Long id, boolean requireOnline) {
        if (id == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND);
        }
        TargetInfo info = load(type, id);
        if (info == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND);
        }
        if (requireOnline && !info.isAvailable()) {
            throw new BizException(ResultCode.CONTENT_OFFLINE);
        }
        return info;
    }

    private TargetInfo load(String type, Long id) {
        switch (type) {
            case SONG: {
                Song song = songMapper.selectById(id);
                if (song == null) {
                    return null;
                }
                TargetInfo info = base(SONG, id, song.getName(), song.getCover());
                info.setSubtitle(song.getArtistName());
                info.setOwnerId(song.getArtistId());
                info.setAvailable(Integer.valueOf(1).equals(song.getStatus())
                        && (song.getAuditStatus() == null || Integer.valueOf(1).equals(song.getAuditStatus())));
                return info;
            }
            case PLAYLIST: {
                Playlist playlist = playlistMapper.selectById(id);
                if (playlist == null) {
                    return null;
                }
                TargetInfo info = base(PLAYLIST, id, playlist.getName(), playlist.getCover());
                info.setSubtitle(playlist.getDescription());
                info.setOwnerId(playlist.getUserId());
                info.setPrivatePlaylist(Integer.valueOf(0).equals(playlist.getIsPublic())
                        || Integer.valueOf(0).equals(playlist.getStatus()));
                info.setAvailable(Integer.valueOf(1).equals(playlist.getStatus())
                        && Integer.valueOf(1).equals(playlist.getAuditStatus()));
                return info;
            }
            case ALBUM: {
                Album album = albumMapper.selectById(id);
                if (album == null) {
                    return null;
                }
                TargetInfo info = base(ALBUM, id, album.getName(), album.getCover());
                info.setSubtitle(album.getArtistName());
                info.setOwnerId(album.getArtistId());
                info.setAvailable(Integer.valueOf(1).equals(album.getStatus()));
                return info;
            }
            case ROOM: {
                Room room = roomMapper.selectById(id);
                if (room == null) {
                    return null;
                }
                TargetInfo info = base(ROOM, id, room.getName(), room.getCover());
                info.setSubtitle(room.getNotice());
                info.setOwnerId(room.getOwnerId());
                info.setAvailable(!Integer.valueOf(Room.STATUS_CLOSED).equals(room.getStatus()));
                return info;
            }
            case COMMENT: {
                Comment comment = commentMapper.selectById(id);
                if (comment == null || Integer.valueOf(Comment.STATUS_DELETED).equals(comment.getStatus())) {
                    return null;
                }
                TargetInfo info = base(COMMENT, id, comment.getContent(), null);
                info.setOwnerId(comment.getUserId());
                info.setAvailable(Integer.valueOf(Comment.STATUS_PUBLISHED).equals(comment.getStatus()));
                return info;
            }
            case ROOM_MESSAGE: {
                RoomMessage message = roomMessageMapper.selectById(id);
                if (message == null) {
                    return null;
                }
                TargetInfo info = base(ROOM_MESSAGE, id, message.getContent(), null);
                info.setOwnerId(message.getUserId());
                info.setAvailable(Integer.valueOf(1).equals(message.getStatus()));
                return info;
            }
            case USER: {
                User user = userMapper.selectById(id);
                if (user == null) {
                    return null;
                }
                TargetInfo info = base(USER, id, user.getNickname(), user.getAvatar());
                info.setSubtitle(user.getSignature());
                info.setOwnerId(user.getId());
                info.setAvailable(!Integer.valueOf(4).equals(user.getStatus()));
                return info;
            }
            case MESSAGE: {
                PrivateMessage message = privateMessageMapper.selectById(id);
                if (message == null) {
                    return null;
                }
                TargetInfo info = base(MESSAGE, id, "私信消息", null);
                info.setOwnerId(message.getSenderId());
                info.setAvailable(true);
                return info;
            }
            case POST: {
                UserPost post = userPostMapper.selectById(id);
                if (post == null || Integer.valueOf(UserPost.STATUS_DELETED).equals(post.getStatus())
                        || Integer.valueOf(UserPost.STATUS_ADMIN_DELETED).equals(post.getStatus())) {
                    return null;
                }
                String text = post.getContent() == null ? "" : post.getContent();
                TargetInfo info = base(POST, id, text.length() > 60 ? text.substring(0, 60) : text, null);
                info.setSubtitle("用户动态");
                info.setOwnerId(post.getUserId());
                info.setVisibility(Integer.valueOf(UserPost.STATUS_SELF_ONLY).equals(post.getStatus())
                        ? 2 : post.getVisibility());
                info.setAvailable(Integer.valueOf(UserPost.STATUS_PUBLISHED).equals(post.getStatus())
                        || Integer.valueOf(UserPost.STATUS_SELF_ONLY).equals(post.getStatus()));
                return info;
            }
            case LYRIC: {
                Lyric lyric = lyricMapper.selectById(id);
                if (lyric == null) {
                    return null;
                }
                TargetInfo info = base(LYRIC, id, "歌词", null);
                info.setOwnerId(lyric.getContributorId());
                info.setAvailable(true);
                return info;
            }
            default:
                return null;
        }
    }

    private static TargetInfo base(String type, Long id, String name, String cover) {
        TargetInfo info = new TargetInfo();
        info.setTargetType(type);
        info.setTargetId(id);
        info.setName(name);
        info.setCover(cover);
        return info;
    }

    /** 内容计数列维护（评论/点赞/收藏/分享）。 */
    public void addCommentCount(String type, Long id, int delta) {
        switch (type) {
            case SONG: songCounter(id, delta, 1); break;
            case PLAYLIST: playlistCounter(id, delta, 1); break;
            case ALBUM: albumCounter(id, delta, 1); break;
            case POST: userPostMapper.incrComment(id, delta); break;
            default: break;
        }
    }

    public void addLikeCount(String type, Long id, int delta) {
        switch (type) {
            case SONG: songCounter(id, delta, 0); break;
            case PLAYLIST: playlistCounter(id, delta, 0); break;
            case ALBUM: albumCounter(id, delta, 0); break;
            default: break;
        }
    }

    public void addCollectCount(String type, Long id, int delta) {
        switch (type) {
            case SONG: songCounter(id, delta, 2); break;
            case PLAYLIST: playlistCounter(id, delta, 2); break;
            case ALBUM: albumCounter(id, delta, 2); break;
            default: break;
        }
    }

    public void addShareCount(String type, Long id, int delta) {
        switch (type) {
            case SONG: songCounter(id, delta, 3); break;
            case PLAYLIST: playlistCounter(id, delta, 3); break;
            case ALBUM: albumCounter(id, delta, 3); break;
            default: break;
        }
    }

    private void songCounter(Long id, int delta, int what) {
        switch (what) {
            case 0: counters.songLike(id, delta); break;
            case 1: counters.songComment(id, delta); break;
            case 2: counters.songCollect(id, delta); break;
            default: counters.songShare(id, delta); break;
        }
    }

    private void playlistCounter(Long id, int delta, int what) {
        switch (what) {
            case 0: counters.playlistLike(id, delta); break;
            case 1: counters.playlistComment(id, delta); break;
            case 2: counters.playlistCollect(id, delta); break;
            default: counters.playlistShare(id, delta); break;
        }
    }

    private void albumCounter(Long id, int delta, int what) {
        switch (what) {
            case 0: counters.albumLike(id, delta); break;
            case 1: counters.albumComment(id, delta); break;
            case 2: counters.albumCollect(id, delta); break;
            default: counters.albumShare(id, delta); break;
        }
    }

}
