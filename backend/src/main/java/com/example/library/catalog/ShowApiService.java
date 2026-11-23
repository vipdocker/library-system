package com.example.library.catalog;

import java.util.List;
import java.util.Map;

import com.example.library.catalog.dto.ShowApiLookupResponse;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.util.IsbnUtils;
import org.springframework.stereotype.Service;

/** Lookup has no repository, persistence operation or write transaction; it reads only the config snapshot. */
@Service
public class ShowApiService {
    private final ShowApiClient client;

    public ShowApiService(ShowApiClient client) {
        this.client = client;
    }

    public ShowApiLookupResponse lookup(String raw) {
        if (raw == null || !IsbnUtils.isValid(raw)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "ISBN 格式或校验位错误", Map.of(),
                    List.of(new ErrorResponse.FieldErrorItem("isbn", "ISBN 格式或校验位错误")));
        }
        return client.lookup(IsbnUtils.normalize(raw));
    }
}