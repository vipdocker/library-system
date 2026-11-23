package com.example.library.catalog.dto;

import java.util.List;
import java.util.Map;

/** OL-v1 preview only: all nullable fields are explicit; nothing is persisted by lookup. */
public record OpenLibraryLookupResponse(String isbn, boolean found, String sourceUrl, Fields fields,
                                        Cover cover, List<Warning> warnings, Integer retryAfterSeconds) {
    public record Fields(String title, String author, String publisher, String description, Integer publishYear) {
    }
    public record Cover(String mediaType, String base64, int byteLength, int width, int height) {
    }
    /** details: optional sanitized diagnostics (enums/integers only); null for plain field warnings. */
    public record Warning(String code, String field, String message, Map<String, Object> details) {
        public Warning(String code, String field, String message) {
            this(code, field, message, null);
        }
        public Warning {
            details = details == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
        }
    }
}
