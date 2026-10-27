package com.example.newmusic.common.result;

import com.example.newmusic.common.util.TraceIdHolder;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;

import java.io.Serializable;

/**
 * 统一响应体（《05-通用设计规范》第 1 节）。
 *
 * <p>所有 Controller 必须返回 {@code R<T>}，禁止直接返回实体或 {@code ResponseEntity}。
 */
@Getter
@ApiModel("统一响应体")
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "业务码，0 表示成功", example = "0")
    private final int code;

    @ApiModelProperty(value = "提示信息，可直接展示给用户")
    private final String message;

    @ApiModelProperty("业务数据，失败时为 null")
    private final T data;

    @ApiModelProperty("链路追踪 ID")
    private final String traceId;

    @ApiModelProperty("服务端毫秒时间戳")
    private final long timestamp;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceIdHolder.current();
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> fail(ResultCode rc) {
        return new R<>(rc.getCode(), rc.getMessage(), null);
    }

    public static <T> R<T> fail(ResultCode rc, String message) {
        return new R<>(rc.getCode(), message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
