package com.example.library.security;

import java.io.IOException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 无权限 → 403 FORBIDDEN；CSRF 缺失/错误 → 403 CSRF_INVALID（design-v0.1 第 7 节）。
 * CsrfFilter 对匿名请求也直接调用本处理器，所以未登录缺 Token 同样是 CSRF_INVALID。
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ErrorCode code = accessDeniedException instanceof CsrfException
                ? ErrorCode.CSRF_INVALID
                : ErrorCode.FORBIDDEN;
        ErrorResponse body = new ErrorResponse(code.name(), code.getDefaultMessage(), code.getStatus().value(),
                request.getRequestURI(),
                DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS)),
                List.of(), Map.of());
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}