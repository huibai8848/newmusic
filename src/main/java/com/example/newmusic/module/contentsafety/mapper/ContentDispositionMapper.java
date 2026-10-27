package com.example.newmusic.module.contentsafety.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 内容处置的统一写入口。用显式 SQL 而不是注入各业务 Service，
 * 避免内容安全模块反向依赖评论/动态/歌单等六个模块（也避免循环依赖）。
 */
@Mapper
public interface ContentDispositionMapper {

    @Update("UPDATE t_comment SET status = 3, update_by = #{operatorId} WHERE id = #{id}")
    int hideComment(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /** 待审（0）与已屏蔽（3）都能恢复；已删除（2）不能。 */
    @Update("UPDATE t_comment SET status = 1, update_by = #{operatorId} WHERE id = #{id} AND status IN (0, 3)")
    int restoreComment(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_user_post SET status = 4, audit_status = 2, update_by = #{operatorId} WHERE id = #{id}")
    int hidePost(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_user_post SET status = 1, audit_status = 1, update_by = #{operatorId} "
            + "WHERE id = #{id} AND status = 4")
    int restorePost(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /** 动态的「仅自己可见」：列表查询会排除 status=3（作者本人仍可见）。 */
    @Update("UPDATE t_user_post SET status = 3, audit_status = 0, update_by = #{operatorId} WHERE id = #{id}")
    int selfOnlyPost(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_user_post SET status = 1, audit_status = 1, update_by = #{operatorId} "
            + "WHERE id = #{id} AND status = 3")
    int publishPost(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /** 人工审核通过「仅自己可见/待审」的动态。 */
    @Update("UPDATE t_user_post SET status = 1, audit_status = 1, update_by = #{operatorId} "
            + "WHERE id = #{id} AND status IN (0, 3)")
    int publishReviewedPost(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_comment SET content = #{content}, update_by = #{operatorId} WHERE id = #{id}")
    int rewriteComment(@Param("id") Long id, @Param("content") String content,
                       @Param("operatorId") Long operatorId);

    @Update("UPDATE t_room_message SET content = #{content} WHERE id = #{id}")
    int rewriteRoomMessage(@Param("id") Long id, @Param("content") String content);

    @Update("UPDATE t_playlist SET status = 0, audit_status = 2, update_by = #{operatorId} WHERE id = #{id}")
    int hidePlaylist(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_playlist SET status = 1, audit_status = 1, update_by = #{operatorId} "
            + "WHERE id = #{id} AND status = 0")
    int restorePlaylist(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_room SET status = 2, update_by = #{operatorId} WHERE id = #{id}")
    int closeRoom(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_room SET status = 1, update_by = #{operatorId} WHERE id = #{id} AND status = 2")
    int reopenRoom(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_private_message SET is_revoked = 1, revoke_time = NOW(3), deleted = 1, "
            + "update_by = #{operatorId} WHERE id = #{id}")
    int revokeMessage(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_private_message SET is_revoked = 0, revoke_time = NULL, deleted = 0, "
            + "update_by = #{operatorId} WHERE id = #{id} AND is_revoked = 1")
    int restoreMessage(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_room_message SET status = 0, audit_status = 2 WHERE id = #{id}")
    int hideRoomMessage(@Param("id") Long id);

    @Update("UPDATE t_room_message SET status = 1, audit_status = 1 WHERE id = #{id} AND status = 0")
    int restoreRoomMessage(@Param("id") Long id);

    @Update("UPDATE t_song SET status = 0, update_by = #{operatorId} WHERE id = #{id}")
    int offlineSong(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("UPDATE t_song SET status = 1, update_by = #{operatorId} WHERE id = #{id}")
    int onlineSong(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /** 仅用于审计：确认目标是否存在，处置前不会对不存在的行静默成功。 */
    @Select("SELECT COUNT(*) FROM t_comment WHERE id = #{id}")
    long countComment(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_user_post WHERE id = #{id}")
    long countPost(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_playlist WHERE id = #{id}")
    long countPlaylist(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_room WHERE id = #{id}")
    long countRoom(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_private_message WHERE id = #{id}")
    long countMessage(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_room_message WHERE id = #{id}")
    long countRoomMessage(@Param("id") Long id);

    @Select("SELECT content FROM t_comment WHERE id = #{id}")
    String selectCommentContent(@Param("id") Long id);

    @Select("SELECT content FROM t_room_message WHERE id = #{id}")
    String selectRoomMessageContent(@Param("id") Long id);
}
