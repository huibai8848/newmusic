package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.PrivateMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PrivateMessageMapper extends BaseMapper<PrivateMessage> {

    @Update("UPDATE t_private_message SET is_read = 1, read_time = #{time} "
            + "WHERE conversation_id = #{conversationId} AND receiver_id = #{receiverId} "
            + "AND is_read = 0 AND deleted = 0")
    int markRead(@Param("conversationId") Long conversationId,
                 @Param("receiverId") Long receiverId,
                 @Param("time") LocalDateTime time);

    @Select("SELECT COUNT(*) FROM t_private_message WHERE receiver_id = #{receiverId} AND is_read = 0 AND deleted = 0")
    int countUnread(@Param("receiverId") Long receiverId);

    @Update("UPDATE t_private_message SET deleted_by_sender = 1 WHERE id = #{id} AND sender_id = #{userId}")
    int deleteBySender(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE t_private_message SET deleted_by_receiver = 1 WHERE id = #{id} AND receiver_id = #{userId}")
    int deleteByReceiver(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE t_private_message SET is_revoked = 1, revoke_time = #{time}, update_time = NOW(3) "
            + "WHERE id = #{id} AND sender_id = #{userId} AND is_revoked = 0 AND deleted = 0")
    int revoke(@Param("id") Long id, @Param("userId") Long userId, @Param("time") LocalDateTime time);

    @Select("SELECT COUNT(*) FROM t_private_message WHERE conversation_id = #{conversationId} "
            + "AND sender_id = #{senderId} AND deleted = 0")
    int countSentInConversation(@Param("conversationId") Long conversationId,
                                @Param("senderId") Long senderId);

    // ---------------------------------------------------------- 模块 16 群聊

    /** 群聊消息：发送者仍可看到自己撤回/删除后的占位，其他成员始终可见。 */
    @Select("<script>SELECT * FROM t_private_message WHERE conversation_id = #{conversationId} "
            + "AND deleted = 0 AND (sender_id &lt;&gt; #{viewerId} OR deleted_by_sender = 0) "
            + "<if test='beforeId != null'>AND id &lt; #{beforeId} </if>"
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    java.util.List<PrivateMessage> selectGroupMessages(@Param("conversationId") Long conversationId,
                                                       @Param("viewerId") Long viewerId,
                                                       @Param("beforeId") Long beforeId,
                                                       @Param("limit") int limit);

    @Select("SELECT MAX(id) FROM t_private_message WHERE conversation_id = #{conversationId} AND deleted = 0")
    Long selectMaxId(@Param("conversationId") Long conversationId);

    @Update("UPDATE t_private_message SET forwarded = 1 WHERE id = #{id}")
    int markForwarded(@Param("id") Long id);

    /** 单聊引用：落库后补写；读取时由 VO 组装带回引用摘要。 */
    @Update("UPDATE t_private_message SET quote_msg_id = #{quoteId} WHERE id = #{id}")
    int updateQuote(@Param("id") Long id, @Param("quoteId") Long quoteId);

    @Select("SELECT * FROM t_private_message WHERE conversation_id = #{conversationId} "
            + "AND content LIKE CONCAT('%', #{keyword}, '%') AND deleted = 0 "
            + "ORDER BY id DESC LIMIT #{limit}")
    java.util.List<PrivateMessage> searchInConversation(@Param("conversationId") Long conversationId,
                                                        @Param("keyword") String keyword,
                                                        @Param("limit") int limit);
}
