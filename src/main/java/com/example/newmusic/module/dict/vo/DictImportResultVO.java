package com.example.newmusic.module.dict.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入结果。BR-20-062 要求逐行校验并返回错误报告，因此失败行不能只给一个总数。
 */
@Data
@ApiModel("批量导入结果")
public class DictImportResultVO {

    private int total;

    private int inserted;

    private int updated;

    private int skipped;

    private int failed;

    private List<LineError> errors = new ArrayList<>();

    @Data
    @ApiModel("导入错误行")
    public static class LineError {
        private int line;
        private String value;
        private String reason;

        public LineError() {
        }

        public LineError(int line, String value, String reason) {
            this.line = line;
            this.value = value;
            this.reason = reason;
        }
    }
}
