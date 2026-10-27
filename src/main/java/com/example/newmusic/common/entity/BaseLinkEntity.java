package com.example.newmusic.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 关联表公共实体（系分决策 D-03）。
 *
 * <p>关联表（歌单歌曲、歌曲标签、点赞、收藏、关注）**必须物理删除**：
 * 这些表靠唯一索引保证幂等，若逻辑删除则「移除后再添加」会撞唯一索引。
 */
@Data
public abstract class BaseLinkEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @ApiModelProperty("主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
