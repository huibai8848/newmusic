package com.example.newmusic.module.play.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 用户播放设置 t_user_play_setting（模块 12 FR-21-008）。
 *
 * <p>PRD 把 {@code user_id} 定义为主键；本实现沿用 {@link BaseEntity} 的雪花主键，
 * 用唯一索引 {@code uk_play_setting_user(user_id)} 保证一人一行，与模块 10 的
 * {@code t_notification_setting} 保持一致。
 *
 * <p>设置行不存在时按 {@link #defaults(Long)} 返回，首次 PUT 才落库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_play_setting")
@ApiModel("播放设置")
public class UserPlaySetting extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;

    @ApiModelProperty("默认音质 standard/high/lossless")
    private String defaultQuality;

    @ApiModelProperty("播放模式 order/loop/single/shuffle")
    private String playMode;

    @ApiModelProperty("自动降质 0关 1开")
    private Integer autoQuality;

    @ApiModelProperty("省流量模式 0关 1开")
    private Integer dataSaver;

    /** P2 预留：淡入淡出。字段先落库，播放器本期不消费。 */
    private Integer fadeEnabled;

    /** P2 预留：均衡器预设。 */
    private String equalizerPreset;

    /** P2 预留：自定义 EQ 频段 JSON。 */
    private String equalizerBands;

    @ApiModelProperty("默认倍速 0.50~2.00")
    private BigDecimal playbackRate;

    @ApiModelProperty("歌词显示模式")
    private String lyricMode;

    @ApiModelProperty("全局歌词偏移（毫秒）")
    private Integer lyricOffset;

    @ApiModelProperty("主题 system/light/dark")
    private String theme;

    /** P2 预留：自动下载。 */
    private Integer autoDownload;

    public static UserPlaySetting defaults(Long userId) {
        UserPlaySetting setting = new UserPlaySetting();
        setting.setUserId(userId);
        setting.setDefaultQuality("high");
        setting.setPlayMode("loop");
        setting.setAutoQuality(1);
        setting.setDataSaver(0);
        setting.setFadeEnabled(1);
        setting.setEqualizerPreset("default");
        setting.setPlaybackRate(BigDecimal.ONE.setScale(2));
        setting.setLyricMode("original_translation");
        setting.setLyricOffset(0);
        setting.setTheme("system");
        setting.setAutoDownload(0);
        return setting;
    }
}
