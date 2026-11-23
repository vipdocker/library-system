package com.example.library.catalog.dto;

/** OL-v1: KEEP/REMOVE have no image payload; REPLACE contains standard base64, never a URL.
 *  showapi-v1: optional generic source {provider,isbn}; legacy sourceIsbn keeps meaning OL only. */
public record CoverChangeRequest(String action, String mediaType, String base64, String sourceIsbn,
                                 BookSourceRequest source) {
}
