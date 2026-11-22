package com.example.library.common.error;

import org.springframework.http.HttpStatus;

/**
 * 全部错误码（design-v0.1-R1 第 8.2 节，逐项对应；前端 errors.ts 与此清单一致）。
 * 本枚举为共享契约：T01 之后只读（新增需经主会话/用户批准）。
 * METHOD_NOT_ALLOWED：修订 R1（2026-11-18 用户批准，approval-record §1.1，I-02），由 T13 加入。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "请先登录"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或口令错误"),
    AUTH_ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "账号已停用"),
    AUTH_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "旧口令错误"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权限执行该操作"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF Token 缺失或错误"),

    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "分类不存在"),
    BOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "图书不存在"),
    COVER_NOT_FOUND(HttpStatus.NOT_FOUND, "封面不存在"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "请求或图片超过大小限制"),
    COVER_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "封面仅支持 JPEG 或 PNG"),
    OPEN_LIBRARY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Open Library 暂不可用，请继续手工录入"),
    OPEN_LIBRARY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Open Library 查询过于频繁，请稍后重试"),
    COPY_NOT_FOUND(HttpStatus.NOT_FOUND, "副本不存在"),
    READER_NOT_FOUND(HttpStatus.NOT_FOUND, "读者不存在"),
    LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "借阅记录不存在"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "通知不存在"),
    LOAN_NOT_OVERDUE(HttpStatus.CONFLICT, "借阅尚未逾期"),
    ACTIVE_LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "该副本没有正在借出的记录"),
    READER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "读者档案不存在"),

    CATEGORY_NAME_DUPLICATE(HttpStatus.CONFLICT, "分类名称已存在"),
    ISBN_DUPLICATE(HttpStatus.CONFLICT, "ISBN 已存在"),
    USERNAME_DUPLICATE(HttpStatus.CONFLICT, "用户名已存在"),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "分类仍被图书引用，不能删除"),
    BOOK_HAS_ACTIVE_LOANS(HttpStatus.CONFLICT, "图书存在未归还的借阅，不能删除"),
    BOOK_HAS_LOAN_HISTORY(HttpStatus.CONFLICT, "图书存在借阅历史，不能删除"),
    COPY_ON_LOAN(HttpStatus.CONFLICT, "副本正在借出"),
    COPY_HAS_LOAN_HISTORY(HttpStatus.CONFLICT, "副本存在借阅历史，不能删除"),
    READER_HAS_ACTIVE_LOANS(HttpStatus.CONFLICT, "读者存在未归还的借阅，不能删除"),
    READER_HAS_LOAN_HISTORY(HttpStatus.CONFLICT, "读者存在借阅历史，不能删除"),
    READER_DISABLED(HttpStatus.CONFLICT, "读者已停用"),
    READER_HAS_OVERDUE(HttpStatus.CONFLICT, "读者存在逾期未还的借阅"),
    LOAN_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "读者在借数量已达上限"),
    DUPLICATE_BOOK_LOAN(HttpStatus.CONFLICT, "读者已在借同一书目的其他副本"),
    COPY_NOT_AVAILABLE(HttpStatus.CONFLICT, "副本不可借"),
    LOAN_ALREADY_RETURNED(HttpStatus.CONFLICT, "借阅已归还"),
    LOAN_OVERDUE(HttpStatus.CONFLICT, "逾期的借阅不能续借"),
    RENEW_LIMIT_REACHED(HttpStatus.CONFLICT, "续借次数已达上限"),

    DB_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "系统繁忙，请稍后再试"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被该端点支持"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}