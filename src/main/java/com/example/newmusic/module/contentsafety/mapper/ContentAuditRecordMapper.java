package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.ContentAuditRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ContentAuditRecordMapper extends BaseMapper<ContentAuditRecord> {

    @Select("SELECT decision, COUNT(*) AS cnt FROM t_content_audit_record "
            + "WHERE create_time >= #{since} GROUP BY decision")
    List<Map<String, Object>> countByDecision(@Param("since") LocalDateTime since);

    @Select("SELECT audit_source, COUNT(*) AS cnt FROM t_content_audit_record "
            + "WHERE create_time >= #{since} GROUP BY audit_source")
    List<Map<String, Object>> countBySource(@Param("since") LocalDateTime since);

    @Select("SELECT content_type, COUNT(*) AS cnt FROM t_content_audit_record "
            + "WHERE create_time >= #{since} GROUP BY content_type ORDER BY cnt DESC")
    List<Map<String, Object>> countByContentType(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM t_content_audit_record WHERE content_type = #{type} "
            + "AND content_id = #{contentId} AND decision = #{decision}")
    long countContentDecision(@Param("type") String type, @Param("contentId") Long contentId,
                              @Param("decision") int decision);
}
