package com.example.newmusic.module.socialupgrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.socialupgrade.entity.ConversationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    @Select("SELECT * FROM t_conversation_member WHERE conversation_id = #{conversationId} "
            + "AND user_id = #{userId} AND quit_time IS NULL")
    ConversationMember selectActive(@Param("conversationId") Long conversationId,
                                    @Param("userId") Long userId);

    @Select("SELECT * FROM t_conversation_member WHERE conversation_id = #{conversationId} "
            + "AND user_id = #{userId}")
    ConversationMember selectAny(@Param("conversationId") Long conversationId,
                                 @Param("userId") Long userId);

    @Select("SELECT * FROM t_conversation_member WHERE conversation_id = #{conversationId} "
            + "AND quit_time IS NULL ORDER BY role ASC, joined_time ASC")
    List<ConversationMember> selectMembers(@Param("conversationId") Long conversationId);

    @Update("UPDATE t_conversation_member SET quit_time = NOW(3), update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NULL")
    int quit(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Update("UPDATE t_conversation_member SET pinned = #{pinned}, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NULL")
    int updatePinned(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                     @Param("pinned") int pinned);

    @Update("UPDATE t_conversation_member SET archived = #{archived}, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NULL")
    int updateArchived(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                       @Param("archived") int archived);

    @Update("UPDATE t_conversation_member SET muted = #{muted}, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NULL")
    int updateMuted(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                    @Param("muted") int muted);

    @Select("SELECT COUNT(*) FROM t_conversation_member WHERE user_id = #{userId} AND archived = 1 "
            + "AND quit_time IS NULL")
    int countArchived(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM t_conversation_member WHERE user_id = #{userId} AND pinned = 1 "
            + "AND quit_time IS NULL")
    int countPinned(@Param("userId") Long userId);

    @Select("SELECT * FROM t_conversation_member WHERE user_id = #{userId} AND quit_time IS NULL")
    List<ConversationMember> selectActiveByUser(@Param("userId") Long userId);

    @Update("UPDATE t_conversation_member SET archived = 0, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND archived = 1")
    int unarchive(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /** 退群后再次被拉回：恢复成员身份并重置加入时间。 */
    @Update("UPDATE t_conversation_member SET quit_time = NULL, joined_time = NOW(3), "
            + "archived = 0, muted = 0, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NOT NULL")
    int reactivate(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Update("UPDATE t_conversation_member SET last_read_msg_id = GREATEST(COALESCE(last_read_msg_id, 0), "
            + "#{messageId}), update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND quit_time IS NULL")
    int updateLastRead(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
                       @Param("messageId") Long messageId);
}
