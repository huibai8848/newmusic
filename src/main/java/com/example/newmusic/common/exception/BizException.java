package com.example.newmusic.common.exception;

import com.example.newmusic.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。由 {@link GlobalExceptionHandler} 原样返回 code 与 message。
 *
 * <p>使用约定：可预期的业务失败抛 BizException（WARN 日志）；
 * 不可预期的系统异常由兜底分支处理（ERROR 日志 + 99001）。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final int httpStatus;

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
        this.httpStatus = rc.getHttpStatus();
    }

    public BizException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
        this.httpStatus = rc.getHttpStatus();
    }

    public BizException(ResultCode rc, String message, Throwable cause) {
        super(message, cause);
        this.code = rc.getCode();
        this.httpStatus = rc.getHttpStatus();
    }

    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }
}
