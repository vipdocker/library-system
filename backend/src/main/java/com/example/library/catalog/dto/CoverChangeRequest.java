package com.example.library.catalog.dto;

/** OL-v1: KEEP/REMOVE have no image payload; REPLACE contains standard base64, never a URL. */
public record CoverChangeRequest(String action, String mediaType, String base64, String sourceIsbn) {
}
