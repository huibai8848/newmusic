package com.example.newmusic.module.ws.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ws.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /** 未读数走覆盖索引 {@code (user_id, is_read)}，不扫正文。 */
    @Select("SELECT COUNT(*) FROM t_notification WHERE user_id = #{userId} AND is_read = 0")
    long countUnread(@Param("userId") Long userId);

    @Update("UPDATE t_notification SET is_read = 1, read_time = NOW(3) "
            + "WHERE id = #{id} AND user_id = #{userId} AND is_read = 0")
    int markRead(@Param("userId") Long userId, @Param("id") Long id);

    @Update("UPDATE t_notification SET is_read = 1, read_time = NOW(3) "
            + "WHERE user_id = #{userId} AND is_read = 0")
    int markAllRead(@Param("userId") Long userId);

    @Select("<script>SELECT COUNT(*) FROM t_notification WHERE user_id = #{userId} AND is_read = 0 "
            + "AND notify_type IN <foreach collection='types' item='t' open='(' separator=',' close=')'>#{t}</foreach>"
            + "</script>")
    long countUnreadByTypes(@Param("userId") Long userId, @Param("types") java.util.List<Integer> types);

    @Update("<script>UPDATE t_notification SET is_read = 1, read_time = NOW(3) "
            + "WHERE user_id = #{userId} AND is_read = 0 AND notify_type IN "
            + "<foreach collection='types' item='t' open='(' separator=',' close=')'>#{t}</foreach></script>")
    int markReadByTypes(@Param("userId") Long userId, @Param("types") java.util.List<Integer> types);

    @Update("UPDATE t_notification SET title = #{title}, content = #{content}, "
            + "from_user_id = #{fromUserId}, from_nickname = #{fromNickname}, from_avatar = #{fromAvatar}, "
            + "is_read = 0, read_time = NULL WHERE id = #{id}")
    int updateContent(@Param("id") Long id, @Param("title") String title, @Param("content") String content,
                      @Param("fromUserId") Long fromUserId, @Param("fromNickname") String fromNickname,
                      @Param("fromAvatar") String fromAvatar);

    @Update("<script>UPDATE t_notification SET is_read = 1, read_time = NOW(3) "
            + "WHERE user_id = #{userId} AND is_read = 0 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markReadBatch(@Param("userId") Long userId, @Param("ids") java.util.List<Long> ids);
}
