package com.example.newmusic.common.query;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用分页查询参数（《05-通用设计规范》第 3 节 / FR-10-001）。
 *
 * <p>子类按业务追加筛选字段；Controller 入口必须调用 {@link #normalize()}。
 */
@Data
@ApiModel("分页查询参数")
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** BR-10-006：keyword 长度上限 */
    public static final int MAX_KEYWORD_LEN = 50;

    @ApiModelProperty(value = "页码，从 1 开始", example = "1")
    private Integer pageNum = 1;

    @ApiModelProperty(value = "每页条数 1~100", example = "20")
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    @ApiModelProperty("排序字段（接口级白名单，非法值回退默认排序）")
    private String sortBy;

    @ApiModelProperty(value = "排序方向 asc/desc", example = "desc")
    private String sortOrder = "desc";

    @ApiModelProperty("通用模糊搜索关键词，≤50 字符")
    private String keyword;

    /** 本次请求的 pageSize 是否被强制截断（BR-10-001 需在响应中标记）。 */
    private transient boolean truncated = false;

    /**
     * 归一化参数（幂等，可重复调用）。
     */
    public void normalize() {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
            truncated = true;
        }
        if (!"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
            sortOrder = "desc";
        }
        if (keyword != null) {
            keyword = keyword.trim();
            if (keyword.length() > MAX_KEYWORD_LEN) {
                keyword = keyword.substring(0, MAX_KEYWORD_LEN);
            }
        }
    }

    public boolean isAsc() {
        return "asc".equalsIgnoreCase(sortOrder);
    }

    public <T> Page<T> toPage() {
        normalize();
        return new Page<>(pageNum, pageSize);
    }

    /** 关键词是否有效。 */
    public boolean hasKeyword() {
        return StrUtil.isNotBlank(keyword);
    }
}
