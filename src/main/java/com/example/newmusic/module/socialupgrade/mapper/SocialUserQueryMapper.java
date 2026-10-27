package com.example.newmusic.module.socialupgrade.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 模块 16 的用户检索（只读，均在 t_user / t_user_profile 上走索引）。 */
@Mapper
public interface SocialUserQueryMapper {

    /** region 在资料表，不能从 t_user 取；左连接保证没有资料行的用户也能被搜到。 */
    @Select("SELECT u.id, u.nickname, u.avatar, u.signature, p.region FROM t_user u "
            + "LEFT JOIN t_user_profile p ON p.user_id = u.id "
            + "WHERE u.deleted = 0 AND u.status NOT IN (2, 4) "
            + "AND u.nickname LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY (u.nickname = #{keyword}) DESC, u.last_login_time DESC LIMIT #{limit}")
    List<UserSearchRow> search(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("SELECT u.id, u.nickname, u.avatar, u.signature, p.region, p.fans_count AS fansCount, "
            + "p.cert_type AS certType FROM t_user u LEFT JOIN t_user_profile p ON p.user_id = u.id "
            + "WHERE u.deleted = 0 AND u.status = 1 "
            + "AND u.id NOT IN (SELECT following_id FROM t_follow WHERE follower_id = #{userId}) "
            + "AND u.id <> #{userId} ORDER BY p.fans_count DESC, u.id DESC LIMIT #{limit}")
    List<UserSearchRow> hotUsers(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("<script>SELECT u.id, u.nickname, u.avatar, u.signature, p.region, p.fans_count AS fansCount, "
            + "p.cert_type AS certType FROM t_user u LEFT JOIN t_user_profile p ON p.user_id = u.id "
            + "WHERE u.deleted = 0 AND u.status = 1 AND u.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<UserSearchRow> byIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT u.id, u.nickname, u.avatar, u.signature, p.region, p.fans_count AS fansCount, "
            + "p.cert_type AS certType FROM t_user u JOIN t_user_profile p ON p.user_id = u.id "
            + "WHERE u.deleted = 0 AND u.status = 1 AND p.region = #{region} AND u.id <> #{userId} "
            + "ORDER BY p.fans_count DESC LIMIT #{limit}")
    List<UserSearchRow> sameRegion(@Param("userId") Long userId, @Param("region") String region,
                                   @Param("limit") int limit);

    @Data
    class UserSearchRow {
        private Long id;
        private String nickname;
        private String avatar;
        private String signature;
        private String region;
        private Integer fansCount;
        private Integer certType;
    }
}
