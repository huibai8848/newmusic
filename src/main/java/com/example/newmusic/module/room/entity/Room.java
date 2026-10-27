package com.example.newmusic.module.room.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 音乐房 t_room（PRD 14 / 数据模型 §6）。
 *
 * <p>房主、状态、类型这些是**权威数据**，Redis 只做实时在线/播放态的加速层。
 * 模块 04 演示房间的 {@code ml:room:owner:{roomId}} 从这里开始不再是真实房间的真相源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_room")
@ApiModel("音乐房")
public class Room extends BaseEntity {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_CLOSED = 2;
    public static final int STATUS_DISSOLVED = 3;

    public static final int TYPE_PUBLIC = 1;
    public static final int TYPE_PRIVATE = 2;
    public static final int TYPE_PASSWORD = 3;

    @ApiModelProperty("6位数字房间号")
    private String roomNo;

    @ApiModelProperty("房间名")
    private String name;

    @ApiModelProperty("封面")
    private String cover;

    @ApiModelProperty("房主ID")
    private Long ownerId;

    @ApiModelProperty("1公开 2私密 3密码")
    private Integer roomType;

    /** 只用于校验，绝不出现在任何 VO / JSON 里。 */
    private String passwordHash;

    @ApiModelProperty("公告")
    private String notice;

    @ApiModelProperty("主题标签，逗号分隔")
    private String themeTags;

    @ApiModelProperty("麦位数 4/6/8/12")
    private Integer micCount;

    @ApiModelProperty("人数上限 50/100/200/500")
    private Integer maxMembers;

    @ApiModelProperty("1房主控播 2自由播放 3投票切歌")
    private Integer playMode;

    @ApiModelProperty("点歌/上麦是否需要审核")
    private Integer needApprove;

    @ApiModelProperty("是否允许自由上麦")
    private Integer allowFreeMic;

    @ApiModelProperty("1活跃 2已关闭 3已解散")
    private Integer status;

    @ApiModelProperty("在线人数（Redis 在线集合的落库镜像）")
    private Integer onlineCount;

    @ApiModelProperty("热度分")
    private Integer hotScore;

    private Long currentSongId;
    private LocalDateTime lastActiveTime;

    @ApiModelProperty("累计停留秒数（热度公式的停留项）")
    private Long totalDuration;
}
