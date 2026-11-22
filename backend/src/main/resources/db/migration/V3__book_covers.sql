ALTER TABLE book ADD COLUMN open_library_isbn TEXT;

CREATE TABLE book_cover (
    book_id INTEGER PRIMARY KEY REFERENCES book(id) ON DELETE CASCADE,
    image_data BLOB NOT NULL CHECK (typeof(image_data) = 'blob' AND length(image_data) BETWEEN 1 AND 2097152),
    media_type TEXT NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 4096),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 4096),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    source_isbn TEXT,
    updated_at INTEGER NOT NULL,
    CHECK (width * height <= 10000000)
);
