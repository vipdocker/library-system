package com.example.library.catalog.settings;

/** Read-only access to the committed configuration snapshot; queries never read the database. */
public interface ShowApiSettingsProvider {
    ShowApiSettings snapshot();
}