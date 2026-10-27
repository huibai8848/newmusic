package com.example.newmusic.module.dict.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 词典生效状态（BR-20-064）。运营需要一眼看到「ES 上次来拉是什么时候」。
 */
@Data
@ApiModel("词典生效状态")
public class DictStatusVO {

    @ApiModelProperty("配置里是否启用 ES")
    private boolean esEnabled;

    @ApiModelProperty("这次探测 ES 是否可达")
    private boolean esAvailable;

    private String esUrl;

    private String esVersion;

    @ApiModelProperty("当前搜索引擎（mysql / es）")
    private String searchEngine;

    @ApiModelProperty("ES analysis-pinyin 插件是否可用")
    private boolean pinyinPlugin;

    @ApiModelProperty("ES analysis-stconvert 插件是否可用")
    private boolean stconvertPlugin;

    private List<String> warnings = new ArrayList<>();

    private LocalDateTime lastPullTime;

    @ApiModelProperty("上次同步/重载日志")
    private DictSyncLogVO lastSync;

    private List<DictStatVO> dicts = new ArrayList<>();

    @ApiModelProperty("给运营看的结论：已生效 / 生效中 / 本地兜底")
    private String effectiveStatus;

    private String notice;

    @Data
    @ApiModel("单类词典状态")
    public static class DictStatVO {
        private String type;
        private int count;
        private String version;
        private String lastModified;
        private LocalDateTime lastPullTime;
    }

    @Data
    @ApiModel("同步日志")
    public static class DictSyncLogVO {
        private String syncType;
        private String status;
        private Integer itemCount;
        private Long costMs;
        private String message;
        private LocalDateTime createTime;
    }
}
