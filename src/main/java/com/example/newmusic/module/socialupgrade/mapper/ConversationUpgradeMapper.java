package com.example.newmusic.module.socialupgrade.mapper;

import com.example.newmusic.module.social.entity.Conversation;
import com.example.newmusic.module.socialupgrade.entity.ConversationMember;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 模块 16 会话检索（只读，写操作复用模块 10 的 Mapper）。 */
@Mapper
public interface ConversationUpgradeMapper {

    @Select("SELECT * FROM t_conversation WHERE type = 1 AND deleted = 0 "
            + "AND (user_a_id = #{userId} OR user_b_id = #{userId}) "
            + "ORDER BY last_message_time DESC, id DESC")
    List<Conversation> selectSingleConversations(@Param("userId") Long userId);

    @Select("SELECT c.* FROM t_conversation c "
            + "JOIN t_conversation_member m ON m.conversation_id = c.id "
            + "WHERE m.user_id = #{userId} AND m.quit_time IS NULL AND c.type = 2 AND c.deleted = 0 "
            + "ORDER BY c.last_message_time DESC, c.id DESC")
    List<Conversation> selectGroupConversations(@Param("userId") Long userId);

    @Select("<script>SELECT * FROM t_conversation_member WHERE user_id = #{userId} "
            + "AND conversation_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<ConversationMember> selectMemberships(@Param("userId") Long userId,
                                               @Param("ids") Collection<Long> ids);

    /** 群聊未读数：按成员最后已读消息现算，不维护冗余计数。 */
    @Select("SELECT COUNT(*) FROM t_private_message m WHERE m.conversation_id = #{conversationId} "
            + "AND m.sender_id <> #{userId} AND m.is_revoked = 0 AND m.deleted = 0 "
            + "AND m.id > COALESCE(#{lastReadMsgId}, 0)")
    int countGroupUnread(@Param("conversationId") Long conversationId,
                         @Param("userId") Long userId,
                         @Param("lastReadMsgId") Long lastReadMsgId);

    @Select("<script>SELECT m.conversation_id AS conversationId, COUNT(*) AS cnt "
            + "FROM t_private_message m JOIN t_conversation_member cm "
            + "ON cm.conversation_id = m.conversation_id AND cm.user_id = #{userId} AND cm.quit_time IS NULL "
            + "WHERE m.conversation_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND m.sender_id &lt;&gt; #{userId} AND m.is_revoked = 0 AND m.deleted = 0 "
            + "AND (cm.last_read_msg_id IS NULL OR m.id &gt; cm.last_read_msg_id) "
            + "GROUP BY m.conversation_id</script>")
    List<GroupUnreadRow> countGroupUnreadBatch(@Param("userId") Long userId,
                                               @Param("ids") Collection<Long> ids);

    @Data
    class GroupUnreadRow {
        private Long conversationId;
        private Long cnt;
    }
}
