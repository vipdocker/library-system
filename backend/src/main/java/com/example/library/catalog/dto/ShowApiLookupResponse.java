package com.example.library.catalog.dto;

import java.util.List;

/**
 * ShowAPI-v1 preview only (showapi-design-v1 §3): explicit nulls, no raw upstream JSON, no table of
 * contents (the interface does not provide one). Nothing is persisted by a lookup. sourceUrl is a
 * fixed public description page, never a per-book link and never contains the AppKey.
 */
public record ShowApiLookupResponse(String provider, String isbn, boolean found, String sourceUrl,
                                    Fields fields, Additional additional, Capabilities capabilities,
                                    Cover cover, List<Warning> warnings, Integer retryAfterSeconds) {
    public record Fields(String title, String author, String publisher, String description, Integer publishYear) {
    }
    public record Additional(String publicationDate, String edition, String pageCountText, String format,
                             String paper, String binding, String priceText) {
    }
    public record Capabilities(boolean tableOfContents) {
    }
    public record Cover(String mediaType, String base64, int byteLength, int width, int height) {
    }
    public record Warning(String code, String field, String message) {
    }
}