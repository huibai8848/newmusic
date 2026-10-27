package com.example.newmusic.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务主表公共实体（《10-模块01-数据库CRUD》4.2）。
 *
 * <p>内部字段（deleted/createBy/updateBy）用 {@code @JsonIgnore} 隐藏，
 * 满足 BR-10-010「详情响应不包含内部字段」，同时避免为每个实体写 VO；
 * {@code version} 例外——它必须回传给客户端做乐观锁回传（见字段注释）。
 *
 * <p>关联表（点赞/收藏/歌单歌曲）请用 {@link BaseLinkEntity}——它们必须物理删除。
 */
@Data
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 雪花算法主键；序列化为 String 由 JacksonConfig 的阈值序列化器统一处理。 */
    @TableId(type = IdType.ASSIGN_ID)
    @ApiModelProperty("主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @ApiModelProperty("更新时间")
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    @JsonIgnore
    private Long createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private Long updateBy;

    @TableLogic
    @TableField(select = false)
    @JsonIgnore
    private Integer deleted;

    /**
     * 乐观锁版本号（BR-10-018）。
     *
     * <p>**必须随响应返回**：客户端在更新请求中原样回传，服务端据此判断并发冲突（10008）。
     */
    @Version
    @ApiModelProperty("乐观锁版本号，更新时需原样回传")
    private Integer version;
}
