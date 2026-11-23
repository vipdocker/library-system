-- ShowAPI ISBN data source (showapi-design-v1): admin-configured AppKey stored in plaintext in the
-- local database by explicit user decision; generic book/cover source columns backfilled from the
-- legacy Open Library fields. Existing V1-V4 migrations are never modified.
CREATE TABLE showapi_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    app_key TEXT CHECK (app_key IS NULL OR (length(trim(app_key)) BETWEEN 1 AND 512)),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    updated_at INTEGER,
    updated_by TEXT,
    CHECK (enabled = 0 OR app_key IS NOT NULL)
);
INSERT INTO showapi_settings (id, enabled, app_key, revision, updated_at, updated_by)
VALUES (1, 0, NULL, 0, NULL, NULL);

ALTER TABLE book ADD COLUMN source_provider TEXT
    CHECK (source_provider IS NULL OR source_provider IN ('OPEN_LIBRARY', 'SHOWAPI'));
ALTER TABLE book ADD COLUMN source_isbn TEXT
    CHECK ((source_provider IS NULL) = (source_isbn IS NULL)
        AND (source_provider <> 'SHOWAPI' OR open_library_isbn IS NULL));
UPDATE book SET source_provider = 'OPEN_LIBRARY', source_isbn = open_library_isbn
WHERE open_library_isbn IS NOT NULL;

-- SQLite validates an ADD COLUMN CHECK against pre-existing rows, so the cover pair invariant
-- (provider IS NULL iff source_isbn IS NULL) requires a table rebuild instead of ALTER.
CREATE TABLE book_cover_v5 (
    book_id INTEGER PRIMARY KEY REFERENCES book(id) ON DELETE CASCADE,
    image_data BLOB NOT NULL CHECK (typeof(image_data) = 'blob' AND length(image_data) BETWEEN 1 AND 2097152),
    media_type TEXT NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 4096),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 4096),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    source_isbn TEXT,
    updated_at INTEGER NOT NULL,
    provider TEXT CHECK ((provider IS NULL OR provider IN ('OPEN_LIBRARY', 'SHOWAPI'))
        AND (provider IS NULL) = (source_isbn IS NULL)),
    CHECK (width * height <= 10000000)
);
INSERT INTO book_cover_v5 (book_id, image_data, media_type, width, height, sha256, source_isbn, updated_at, provider)
SELECT book_id, image_data, media_type, width, height, sha256, source_isbn, updated_at,
       CASE WHEN source_isbn IS NOT NULL THEN 'OPEN_LIBRARY' ELSE NULL END
FROM book_cover;
DROP TABLE book_cover;
ALTER TABLE book_cover_v5 RENAME TO book_cover;