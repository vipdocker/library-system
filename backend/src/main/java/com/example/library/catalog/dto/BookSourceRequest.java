package com.example.library.catalog.dto;

/**
 * Generic source reference (showapi-design-v1 §8): {provider, isbn}, provider only OPEN_LIBRARY or
 * SHOWAPI. Used by book metadataSource and cover source; validated in BookWriteService. The legacy
 * openLibraryIsbn / cover.sourceIsbn fields keep meaning Open Library only.
 */
public record BookSourceRequest(String provider, String isbn) {
}