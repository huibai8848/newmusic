package com.example.newmusic.module.admin.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@ApiModel("歌曲导入结果")
public class SongImportResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("成功行数")
    private int success;

    @ApiModelProperty("失败行数")
    private int failed;

    @ApiModelProperty("失败明细（行号从 1 开始）")
    private List<RowError> errors = new ArrayList<>();

    public void addError(int row, String message) {
        RowError error = new RowError();
        error.setRow(row);
        error.setMessage(message);
        errors.add(error);
        failed++;
    }

    @Data
    @ApiModel("导入失败行")
    public static class RowError implements Serializable {
        private static final long serialVersionUID = 1L;
        private int row;
        private String message;
    }
}
