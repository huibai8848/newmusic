package com.example.newmusic.module.dict.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@ApiModel("同义词组")
public class SynonymGroupVO {

    @ApiModelProperty("组号")
    private Integer groupNo;

    private List<Word> words = new ArrayList<>();

    @ApiModelProperty("主词")
    private String primaryWord;

    @ApiModelProperty("EQUIVALENT / ONE_WAY")
    private String synonymType;

    private String category;

    private Integer enabled;

    private LocalDateTime updateTime;

    @Data
    @ApiModel("同义词组成员")
    public static class Word {
        private Long id;
        private String word;
        private Integer isPrimary;
        private Integer enabled;
        private Integer sortOrder;
    }
}
