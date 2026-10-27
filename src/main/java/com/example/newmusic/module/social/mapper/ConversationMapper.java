package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.Conversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Insert("INSERT IGNORE INTO t_conversation "
            + "(id, user_a_id, user_b_id, unread_a, unread_b, create_time, update_time, deleted, version) "
            + "VALUES (#{id}, #{userAId}, #{userBId}, 0, 0, #{createTime}, #{createTime}, 0, 0)")
    int insertIgnore(@Param("id") Long id,
                     @Param("userAId") Long userAId,
                     @Param("userBId") Long userBId,
                     @Param("createTime") LocalDateTime createTime);

    @Select("SELECT * FROM t_conversation WHERE user_a_id = #{userAId} AND user_b_id = #{userBId} "
            + "AND type = 1 AND deleted = 0")
    Conversation selectByPair(@Param("userAId") Long userAId, @Param("userBId") Long userBId);

    @Update("UPDATE t_conversation SET last_message_id = #{messageId}, last_message_time = #{time}, "
            + "last_message_preview = #{preview}, update_time = NOW(3) WHERE id = #{id}")
    int updateLastMessage(@Param("id") Long id,
                          @Param("messageId") Long messageId,
                          @Param("time") LocalDateTime time,
                          @Param("preview") String preview);

    @Update("UPDATE t_conversation SET unread_a = GREATEST(unread_a + #{delta}, 0) WHERE id = #{id}")
    int incrUnreadA(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_conversation SET unread_b = GREATEST(unread_b + #{delta}, 0) WHERE id = #{id}")
    int incrUnreadB(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE t_conversation SET unread_a = 0 WHERE id = #{id}")
    int resetUnreadA(@Param("id") Long id);

    @Update("UPDATE t_conversation SET unread_b = 0 WHERE id = #{id}")
    int resetUnreadB(@Param("id") Long id);

    @Select("SELECT COALESCE(SUM(CASE WHEN user_a_id = #{userId} THEN unread_a ELSE unread_b END), 0) "
            + "FROM t_conversation WHERE type = 1 AND (user_a_id = #{userId} OR user_b_id = #{userId}) "
            + "AND deleted = 0")
    long sumUnread(@Param("userId") Long userId);

    /** 创建群聊；user_b_id 写会话 ID，见 {@code Conversation#userBId} 的说明。 */
    @Insert("INSERT INTO t_conversation (id, user_a_id, user_b_id, type, group_name, group_avatar, "
            + "owner_id, member_count, unread_a, unread_b, create_time, update_time, deleted, version) "
            + "VALUES (#{id}, #{ownerId}, #{id}, 2, #{name}, #{avatar}, #{ownerId}, #{memberCount}, "
            + "0, 0, #{now}, #{now}, 0, 0)")
    int insertGroup(@Param("id") Long id, @Param("ownerId") Long ownerId,
                    @Param("name") String name, @Param("avatar") String avatar,
                    @Param("memberCount") int memberCount,
                    @Param("now") LocalDateTime now);

    @Update("UPDATE t_conversation SET member_count = #{count}, update_time = NOW(3) WHERE id = #{id}")
    int updateMemberCount(@Param("id") Long id, @Param("count") int count);
}
