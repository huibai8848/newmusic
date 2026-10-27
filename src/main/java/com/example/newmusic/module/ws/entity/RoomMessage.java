package com.example.newmusic.module.ws.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 房间消息（PRD 04 §5.3 {@code t_room_message}）。
 *
 * <p>同 {@link Notification}，不继承 {@code BaseEntity}：追加型表，没有逻辑删除/乐观锁列。
 *
 * <p><b>ID 由调用方传入</b>（{@link IdType#INPUT}）：WS 广播里的 {@code messageId}
 * 必须与库里的主键一致，客户端的 {@code room.messages.since} 才能用同一个游标
 * 补齐断线期间的消息。如果让 MP 自己生成，广播与落库会拿到两个不同的雪花 ID。
 */
@Data
@TableName("t_room_message")
@ApiModel("房间消息")
public class RoomMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_CHAT = 1;
    public static final int TYPE_DANMAKU = 2;
    public static final int TYPE_SYSTEM = 3;
    public static final int TYPE_GIFT = 4;
    public static final int TYPE_JOIN = 5;
    public static final int TYPE_PLAY = 6;

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long roomId;
    private Long userId;

    @ApiModelProperty("1聊天 2弹幕 3系统 4礼物 5进房 6控播")
    private Integer messageType;

    @ApiModelProperty("内容（敏感词过滤后）")
    private String content;

    @ApiModelProperty("扩展（礼物/歌曲信息），JSON 字符串")
    private String extra;

    @ApiModelProperty("0待审 1通过 2拦截")
    private Integer auditStatus;

    @ApiModelProperty("1正常 0撤回")
    private Integer status;

    private LocalDateTime createTime;
}
