package com.example.newmusic.module.dict.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分词测试结果（PRD 20 FR-20-001 / BR-20-060）。
 *
 * <p>{@code source} 说明这份结果来自 ES 还是本地最大匹配兜底：
 * ES 没装时后台仍然能对比「加词前 / 加词后」，而不是给运营一个 71008 的死页面。
 */
@Data
@ApiModel("分词测试结果")
public class DictAnalyzeVO {

    private String text;

    private String analyzer;

    @ApiModelProperty("ES / LOCAL")
    private String source;

    private String notice;

    private List<Token> tokens = new ArrayList<>();

    @Data
    @ApiModel("分词 Token")
    public static class Token {
        private String token;
        private Integer startOffset;
        private Integer endOffset;
        private String type;

        public Token() {
        }

        public Token(String token, int startOffset, int endOffset, String type) {
            this.token = token;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
        }
    }
}
