package com.example.library.catalog.dto;

import java.util.List;

/** OL-v1 preview only: all nullable fields are explicit; nothing is persisted by lookup. */
public record OpenLibraryLookupResponse(String isbn, boolean found, String sourceUrl, Fields fields,
                                        Cover cover, List<Warning> warnings, Integer retryAfterSeconds) {
    public record Fields(String title, String author, String publisher, String description, Integer publishYear) {
    }
    public record Cover(String mediaType, String base64, int byteLength, int width, int height) {
    }
    public record Warning(String code, String field, String message) {
    }
}
