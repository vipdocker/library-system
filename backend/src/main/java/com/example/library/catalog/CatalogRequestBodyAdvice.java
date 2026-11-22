package com.example.library.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.catalog.dto.BookUpdateRequest;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Runs after authentication/CSRF, counting the real stream even with absent/false Content-Length. */
@ControllerAdvice(basePackages = "com.example.library.catalog")
public class CatalogRequestBodyAdvice extends RequestBodyAdviceAdapter {
    static final int MAX_JSON_BYTES = 3 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converter) {
        return targetType == BookCreateRequest.class || targetType == BookUpdateRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes;
        try (InputStream stream = input.getBody()) {
            bytes = stream.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE);
            }
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
