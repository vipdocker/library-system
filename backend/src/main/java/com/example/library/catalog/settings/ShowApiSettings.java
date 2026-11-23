package com.example.library.catalog.settings;

import java.time.Instant;

/**
 * Immutable in-memory configuration snapshot (showapi-design-v1 §5). The AppKey lives in plaintext
 * in the local database by explicit user decision; it must never appear in toString, responses,
 * errors or logs. Clearing the row does not scrub WAL/backups: revoke the old key at the vendor.
 */
public record ShowApiSettings(boolean enabled, String appKey, long revision, Instant updatedAt) {
    public ShowApiSettings {
        if (revision < 0 || enabled && (appKey == null || appKey.isBlank())
                || appKey != null && (appKey.isBlank() || appKey.length() > 512)) {
            throw new IllegalArgumentException("invalid showapi settings");
        }
    }

    public boolean appKeyConfigured() { return appKey != null; }

    @Override
    public String toString() {
        return "ShowApiSettings[enabled=" + enabled + ", appKey=" + (appKey == null ? "absent" : "masked")
                + ", revision=" + revision + ", updatedAt=" + updatedAt + "]";
    }
}