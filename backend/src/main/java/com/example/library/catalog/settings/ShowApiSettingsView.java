package com.example.library.catalog.settings;

import java.time.Instant;

/** Public GET/PUT response: never contains the AppKey or any fragment of it. */
public record ShowApiSettingsView(boolean enabled, boolean appKeyConfigured, long revision, Instant updatedAt) {
    public static ShowApiSettingsView from(ShowApiSettings settings) {
        return new ShowApiSettingsView(settings.enabled(), settings.appKeyConfigured(),
                settings.revision(), settings.updatedAt());
    }
}