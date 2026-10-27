package com.example.newmusic.module.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * IK 主词典词条（模块 11，表 {@code t_ik_dict_word}）。
 *
 * <p>没有继承 {@link com.example.newmusic.common.entity.BaseEntity}：本表是词典配置表，
 * 物理删除（删词就是删词，不需要逻辑删除的墓碑），也没有乐观锁与操作人列。用逻辑删除会让
 * {@code word} 唯一索引在「删除后再添加同一个词」时直接撞键。
 *
 * <p>所有字段由数据库默认值兜底；{@code updateTime} 由 MySQL
 * {@code ON UPDATE CURRENT_TIMESTAMP(3)} 维护，远程词典的 Last-Modified 依赖它。
 */
@Data
@TableName("t_ik_dict_word")
@ApiModel("IK 主词典词条")
public class IkDictWord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty("词条")
    private String word;

    @ApiModelProperty("词频，默认 100")
    private Integer frequency;

    @ApiModelProperty("词性，默认 n")
    private String pos;

    @ApiModelProperty("来源 MANUAL / AUTO_ARTIST / AUTO_SONG")
    private String source;

    @ApiModelProperty("自动同步关联的对象 ID")
    private Long refId;

    @ApiModelProperty("1 启用 0 停用")
    private Integer enabled;

    @ApiModelProperty("词典类型 MAIN / NET_SLANG / REGION")
    private String dictType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
