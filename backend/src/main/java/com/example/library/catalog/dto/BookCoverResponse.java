package com.example.library.catalog.dto;

/** Public metadata only: no BLOB or base64 is included in book responses. */
public record BookCoverResponse(String url, String mediaType, int byteLength, int width, int height,
                                String sha256, String sourceUrl) {
}
