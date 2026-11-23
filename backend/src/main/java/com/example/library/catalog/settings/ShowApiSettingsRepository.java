package com.example.library.catalog.settings;

import java.time.Instant;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse.FieldErrorItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Parameterized SQL only; the AppKey is stored as plaintext by explicit user decision. */
@Repository
class ShowApiSettingsRepository {
    private final JdbcClient jdbc;

    ShowApiSettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    ShowApiSettings load() {
        return jdbc.sql("SELECT enabled,app_key,revision,updated_at FROM showapi_settings WHERE id=1")
                .query((rs, row) -> new ShowApiSettings(rs.getInt("enabled") == 1, rs.getString("app_key"),
                        rs.getLong("revision"),
                        rs.getObject("updated_at") == null ? null : Instant.ofEpochMilli(rs.getLong("updated_at"))))
                .single();
    }

    /** Runs inside the service-owned transaction; any failure rolls back without touching the row. */
    ShowApiSettings replace(ShowApiSettingsRequest request, String actor, Instant now) {
        ShowApiSettings current = load();
        if (current.revision() != request.expectedRevision()) { throw conflict(current.revision()); }
        String newKey = switch (request.action()) {
            case "KEEP" -> current.appKey();
            case "REPLACE" -> request.appKey();
            case "CLEAR" -> null;
            default -> throw new IllegalStateException("unsupported action");
        };
        if (request.enabled() && newKey == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                    Map.of(), List.of(new FieldErrorItem("appKey", "启用 ShowAPI 前必须配置 AppKey")));
        }
        int changed = jdbc.sql("UPDATE showapi_settings SET enabled=:enabled,app_key=:key,"
                        + "revision=revision+1,updated_at=:now,updated_by=:actor WHERE id=1 AND revision=:expected")
                .param("enabled", request.enabled() ? 1 : 0).param("key", newKey, Types.VARCHAR)
                .param("now", now.toEpochMilli()).param("actor", actor)
                .param("expected", request.expectedRevision()).update();
        if (changed != 1) { throw conflict(load().revision()); }
        return load();
    }

    private static ApiException conflict(long currentRevision) {
        return ApiException.withDetails(ErrorCode.SETTINGS_CONFLICT, Map.of("currentRevision", currentRevision));
    }
}