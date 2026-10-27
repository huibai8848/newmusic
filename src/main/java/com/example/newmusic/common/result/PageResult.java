package com.example.newmusic.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一分页响应（《05-通用设计规范》第 3 节）。
 */
@Data
@ApiModel("分页结果")
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("当前页数据")
    private List<T> list;

    @ApiModelProperty("总条数")
    private long total;

    @ApiModelProperty("页码，从 1 开始")
    private long pageNum;

    @ApiModelProperty("每页条数")
    private long pageSize;

    @ApiModelProperty("总页数")
    private long pages;

    @ApiModelProperty("是否有下一页")
    private boolean hasNext;

    @ApiModelProperty("pageSize 是否被强制截断为 100")
    private boolean truncated;

    public static <T> PageResult<T> of(IPage<T> page) {
        return of(page, false);
    }

    public static <T> PageResult<T> of(IPage<T> page, boolean truncated) {
        PageResult<T> r = new PageResult<>();
        r.setList(page.getRecords() == null ? Collections.emptyList() : page.getRecords());
        r.setTotal(page.getTotal());
        r.setPageNum(page.getCurrent());
        r.setPageSize(page.getSize());
        r.setPages(page.getPages());
        r.setHasNext(page.getCurrent() < page.getPages());
        r.setTruncated(truncated);
        return r;
    }

    /** 分页结果实体 → VO 转换。 */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> mapper, boolean truncated) {
        PageResult<V> r = new PageResult<>();
        List<E> records = page.getRecords() == null ? Collections.emptyList() : page.getRecords();
        r.setList(records.stream().map(mapper).collect(Collectors.toList()));
        r.setTotal(page.getTotal());
        r.setPageNum(page.getCurrent());
        r.setPageSize(page.getSize());
        r.setPages(page.getPages());
        r.setHasNext(page.getCurrent() < page.getPages());
        r.setTruncated(truncated);
        return r;
    }

    /** 已经批量转换好的结果直接套分页元信息（避免再逐条 map 一遍）。 */
    public static <V> PageResult<V> ofList(IPage<?> page, List<V> list) {
        PageResult<V> r = new PageResult<>();
        r.setList(list == null ? Collections.emptyList() : list);
        r.setTotal(page.getTotal());
        r.setPageNum(page.getCurrent());
        r.setPageSize(page.getSize());
        r.setPages(page.getPages());
        r.setHasNext(page.getCurrent() < page.getPages());
        r.setTruncated(false);
        return r;
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        PageResult<T> r = new PageResult<>();
        r.setList(Collections.emptyList());
        r.setTotal(0);
        r.setPageNum(pageNum);
        r.setPageSize(pageSize);
        r.setPages(0);
        r.setHasNext(false);
        r.setTruncated(false);
        return r;
    }
}
