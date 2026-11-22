CREATE TABLE system_proxy_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    host TEXT,
    port INTEGER CHECK (port IS NULL OR (typeof(port) = 'integer' AND port BETWEEN 1 AND 65535)),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    updated_at INTEGER,
    updated_by TEXT,
    CHECK ((host IS NULL AND port IS NULL) OR (host IS NOT NULL AND length(trim(host)) > 0 AND port IS NOT NULL)),
    CHECK (enabled = 0 OR (host IS NOT NULL AND port IS NOT NULL))
);
INSERT INTO system_proxy_settings (id, enabled, host, port, revision, updated_at, updated_by)
VALUES (1, 0, NULL, NULL, 0, NULL, NULL);
