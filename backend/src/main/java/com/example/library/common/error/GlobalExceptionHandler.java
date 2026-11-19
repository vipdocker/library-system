package com.example.library.common.error;

import java.sql.SQLException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常 → JSON 错误响应（design-v0.1 第 8.1/8.2 节）。
 * - 校验错误转 fieldErrors；
 * - SQLITE_BUSY / 获取连接超时 → 503 DB_BUSY；
 * - DataIntegrityViolation 兜底：预期内的唯一/外键冲突由各服务在事务内转成对应 409，
 *   走到这里的是未预期冲突，按 500 INTERNAL_ERROR 输出（不暴露内部信息），日志 WARN 留全因；
 * - 500 响应不含堆栈与内部信息，完整异常只写日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getCode();
        return build(code, ex.getMessage(), request, ex.getFieldErrors(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> items = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            items.add(new ErrorResponse.FieldErrorItem(fe.getField(), fe.getDefaultMessage()));
        }
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                request, items, Map.of());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex,
                                                               HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> items = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    items.add(new ErrorResponse.FieldErrorItem(name, error.getDefaultMessage())));
        });
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                request, items, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> items = new ArrayList<>();
        ex.getConstraintViolations().forEach(v ->
                items.add(new ErrorResponse.FieldErrorItem(String.valueOf(v.getPropertyPath()), v.getMessage())));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                request, items, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_FAILED, "请求体缺失或不是合法的 JSON", request, List.of(), Map.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleParamType(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_FAILED, "请求参数类型不正确或缺失", request, List.of(), Map.of());
    }

    /**
     * 请求方法不被端点支持 → 405 METHOD_NOT_ALLOWED（design-v0.1-R1 §8.2，I-02）。
     * 安全过滤器链在 DispatcherServlet 之前执行：未登录请求仍先得到 401，不会经由本映射绕过认证。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("http.method-not-allowed method={} path={}", request.getMethod(), request.getRequestURI());
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(),
                request, List.of(), Map.of());
    }

    /**
     * 未映射路由（含 T01 阶段尚未实现的业务端点）保持 404：
     * 重新抛出交给容器 ERROR dispatch（/error 已放行），不进 500 兜底。
     * ErrorCode 表没有通用 NOT_FOUND 码，故不包装成 ErrorResponse。
     */
    @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.servlet.resource.NoResourceFoundException.class})
    public void handleNotFound(Exception ex) throws Exception {
        throw ex;
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        if (isBusy(ex)) {
            log.warn("db.busy path={} cause={}", request.getRequestURI(), rootMessage(ex));
            return build(ErrorCode.DB_BUSY, ErrorCode.DB_BUSY.getDefaultMessage(), request, List.of(), Map.of());
        }
        if (ex instanceof DataIntegrityViolationException) {
            // 兜底：服务层应已把预期冲突转成具体 409；此处不向客户端暴露约束名或 SQL。
            log.warn("db.integrity-violation path={} cause={}", request.getRequestURI(), rootMessage(ex));
            return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                    request, List.of(), Map.of());
        }
        log.error("db.error path={}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request, List.of(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (isBusy(ex)) {
            log.warn("db.busy path={} cause={}", request.getRequestURI(), rootMessage(ex));
            return build(ErrorCode.DB_BUSY, ErrorCode.DB_BUSY.getDefaultMessage(), request, List.of(), Map.of());
        }
        log.error("internal.error path={}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request, List.of(), Map.of());
    }

    /** SQLITE_BUSY / database is locked / Hikari 获取连接超时。 */
    private boolean isBusy(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String msg = sql.getMessage();
                if (msg != null && (msg.contains("SQLITE_BUSY") || msg.contains("database is locked"))) {
                    return true;
                }
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("SQLITE_BUSY") || msg.contains("database is locked"))) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, HttpServletRequest request,
                                                List<ErrorResponse.FieldErrorItem> fieldErrors,
                                                Map<String, Object> details) {
        String timestamp = TIMESTAMP.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
        ErrorResponse body = new ErrorResponse(code.name(), message, code.getStatus().value(),
                request.getRequestURI(), timestamp, fieldErrors, details);
        return ResponseEntity.status(code.getStatus()).body(body);
    }
}