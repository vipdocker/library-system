package com.example.library.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.library.catalog.dto.BookCoverResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC deliberately keeps BLOBs out of the JPA Book and list/detail queries. */
@Repository
public class BookCoverRepository {
    private static final String METADATA = "book_id,media_type,length(image_data) AS byte_length,width,height,sha256,source_isbn";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public BookCoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public Map<Long, BookCoverResponse> metadata(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return named.query("SELECT " + METADATA + " FROM book_cover WHERE book_id IN (:ids)",
                new MapSqlParameterSource("ids", ids), (rs, row) -> new Metadata(rs.getLong("book_id"), response(rs)))
                .stream().collect(Collectors.toMap(Metadata::id, Metadata::cover));
    }

    public record StoredCover(byte[] bytes, String mediaType) {
    }

    public StoredCover read(Long id) {
        return jdbc.query("SELECT image_data,media_type FROM book_cover WHERE book_id=?",
                (rs, row) -> new StoredCover(rs.getBytes(1), rs.getString(2)), id).stream().findFirst().orElse(null);
    }

    public void replace(Long id, CoverImageValidator.Image image, String sourceIsbn, long now) {
        jdbc.update("INSERT INTO book_cover(book_id,image_data,media_type,width,height,sha256,source_isbn,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(book_id) DO UPDATE SET image_data=excluded.image_data,"
                + "media_type=excluded.media_type,width=excluded.width,height=excluded.height,"
                + "sha256=excluded.sha256,source_isbn=excluded.source_isbn,updated_at=excluded.updated_at",
                id, image.bytes(), image.mediaType(), image.width(), image.height(), image.sha256(), sourceIsbn, now);
    }

    public void remove(Long id) {
        jdbc.update("DELETE FROM book_cover WHERE book_id=?", id);
    }

    private record Metadata(Long id, BookCoverResponse cover) {
    }

    private static BookCoverResponse response(ResultSet rs) throws SQLException {
        String sha = rs.getString("sha256");
        String source = rs.getString("source_isbn");
        return new BookCoverResponse("/api/books/" + rs.getLong("book_id") + "/cover?v=" + sha,
                rs.getString("media_type"), rs.getInt("byte_length"), rs.getInt("width"), rs.getInt("height"), sha,
                source == null ? null : "https://openlibrary.org/isbn/" + source);
    }
}
