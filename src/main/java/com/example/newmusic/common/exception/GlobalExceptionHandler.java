package com.example.newmusic.common.exception;

import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理（《05-通用设计规范》6.3）。
 *
 * <p>规则：兜底异常必须打 ERROR 日志并返回 99001，禁止把堆栈返回前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：原样返回 code 与 message。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(R.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 请求体校验失败 → 10001，message 指明具体字段。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", msg);
        return body(ResultCode.PARAM_INVALID, msg.isEmpty() ? ResultCode.PARAM_INVALID.getMessage() : msg);
    }

    /** 表单/查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return body(ResultCode.PARAM_INVALID, msg.isEmpty() ? ResultCode.PARAM_INVALID.getMessage() : msg);
    }

    /** 方法参数级校验（@Validated + @RequestParam）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return body(ResultCode.PARAM_INVALID, msg.isEmpty() ? ResultCode.PARAM_INVALID.getMessage() : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return body(ResultCode.PARAM_INVALID, "缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(ResultCode.PARAM_INVALID, "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return body(ResultCode.BODY_PARSE_ERROR, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return body(ResultCode.METHOD_NOT_SUPPORTED, null);
    }

    /**
     * multipart 在进入 Controller 之前就被容器拒绝，默认会落到 99001「未知异常」。
     * 直接给出上限，避免用户只看到「未知异常」而不知道是文件太大。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return body(ResultCode.PARAM_INVALID, "文件超过大小限制（音频最大 50MB，图片最大 5MB）");
    }

    /** 唯一键冲突：兜底为 10010，具体业务应在 Service 层预校验给出更精确的码。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return body(ResultCode.DUPLICATE_DATA, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return body(ResultCode.UNAUTHORIZED, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return body(ResultCode.NO_PERMISSION, null);
    }

    /** 兜底：不泄露堆栈，返回 99001。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return body(ResultCode.UNKNOWN, null);
    }

    private ResponseEntity<R<Void>> body(ResultCode rc, String message) {
        R<Void> r = message == null ? R.fail(rc) : R.fail(rc, message);
        return ResponseEntity.status(rc.getHttpStatus()).body(r);
    }
}
