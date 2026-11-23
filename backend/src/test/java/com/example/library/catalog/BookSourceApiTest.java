package com.example.library.catalog;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AC-S1/S3/T3: generic metadata/cover sources coexist with legacy OL fields; saves stay atomic. */
class BookSourceApiTest extends CatalogTestBase {
    private static final String SHOWAPI_URL = "https://www.showapi.com/apis/isbn-book-1626";

    private String png() throws Exception {
        return Base64.getEncoder().encodeToString(BookCoverApiTest.image("png", 4, 6));
    }

    private String createJson(String isbn, String extra) {
        return "{\"isbn\":\"" + isbn + "\",\"title\":\"书名\",\"author\":\"作者\",\"initialCopies\":1" + extra + "}";
    }

    @Test
    void t3ShowApiSourceIsStoredAndExposedWithoutLegacyOlIsbn() throws Exception {
        var result = asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(createJson("0-8044-2957-x",
                        ",\"metadataSource\":{\"provider\":\"SHOWAPI\",\"isbn\":\"9780804429573\"},"
                                + "\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                                + "\"source\":{\"provider\":\"SHOWAPI\",\"isbn\":\"0-8044-2957-x\"}}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openLibraryIsbn").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sourceProvider").value("SHOWAPI"))
                .andExpect(jsonPath("$.sourceIsbn").value("9780804429573"))
                .andExpect(jsonPath("$.metadataSourceUrl").value(SHOWAPI_URL))
                .andExpect(jsonPath("$.cover.sourceProvider").value("SHOWAPI"))
                .andExpect(jsonPath("$.cover.sourceIsbn").value("080442957X"))
                .andExpect(jsonPath("$.cover.sourceUrl").value(SHOWAPI_URL))
                .andReturn();
        long id = idOf(result);
        assertThat(jdbcTemplate.queryForObject("SELECT source_provider FROM book WHERE id=?", String.class, id)).isEqualTo("SHOWAPI");
        assertThat(jdbcTemplate.queryForObject("SELECT open_library_isbn FROM book WHERE id=?", String.class, id)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT provider FROM book_cover WHERE book_id=?", String.class, id)).isEqualTo("SHOWAPI");
        // AC-S3: a signed-in reader can view the saved cover bytes.
        asReader(get("/api/books/" + id + "/cover")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        asReader(get("/api/books/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceProvider").value("SHOWAPI"))
                .andExpect(jsonPath("$.cover.sourceProvider").value("SHOWAPI"));
        asAdmin(get("/api/books").param("q", "书名")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cover.sourceProvider").value("SHOWAPI"));
    }

    @Test
    void t3LegacyOpenLibraryFieldsStillWorkAndBackfillGenericSource() throws Exception {
        var result = asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(createJson("080442957X",
                        ",\"openLibraryIsbn\":\"0-8044-2957-x\","
                                + "\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                                + "\"sourceIsbn\":\"080442957X\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openLibraryIsbn").value("080442957X"))
                .andExpect(jsonPath("$.sourceProvider").value("OPEN_LIBRARY"))
                .andExpect(jsonPath("$.sourceIsbn").value("080442957X"))
                .andExpect(jsonPath("$.metadataSourceUrl").value("https://openlibrary.org/isbn/080442957X"))
                .andExpect(jsonPath("$.cover.sourceProvider").value("OPEN_LIBRARY"))
                .andExpect(jsonPath("$.cover.sourceUrl").value("https://openlibrary.org/isbn/080442957X"))
                .andReturn();
        long id = idOf(result);
        assertThat(jdbcTemplate.queryForObject("SELECT source_provider FROM book WHERE id=?", String.class, id)).isEqualTo("OPEN_LIBRARY");
    }

    @Test
    void t3ConflictingOrInvalidSourcesAreRejectedWithoutWrites() throws Exception {
        String[] invalid = {
                ",\"openLibraryIsbn\":\"080442957X\",\"metadataSource\":{\"provider\":\"SHOWAPI\",\"isbn\":\"080442957X\"}",
                ",\"metadataSource\":{\"provider\":\"OTHER\",\"isbn\":\"080442957X\"}",
                ",\"metadataSource\":{\"provider\":\"SHOWAPI\"}",
                ",\"metadataSource\":{\"provider\":\"SHOWAPI\",\"isbn\":\"bad\"}",
                ",\"metadataSource\":{\"isbn\":\"080442957X\"}",
                ",\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                        + "\"sourceIsbn\":\"080442957X\",\"source\":{\"provider\":\"SHOWAPI\",\"isbn\":\"080442957X\"}}",
                ",\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                        + "\"source\":{\"provider\":\"SHOWAPI\",\"isbn\":\"bad\"}}",
        };
        for (String extra : invalid) {
            asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(createJson("080442957X", extra)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
    }

    @Test
    void t3UpdateAppliesClearsAndKeepsSourcesIndependently() throws Exception {
        var created = asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(createJson("080442957X", ",\"openLibraryIsbn\":\"080442957X\","
                        + "\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                        + "\"sourceIsbn\":\"080442957X\"}")))
                .andExpect(status().isCreated()).andReturn();
        long id = idOf(created);
        String update = "{\"isbn\":\"080442957X\",\"title\":\"改后书名\",\"author\":\"作者\"";
        // Applying SHOWAPI clears the legacy OL isbn; cover KEEP preserves the old cover source.
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(update + ",\"metadataSource\":{\"provider\":\"SHOWAPI\",\"isbn\":\"080442957X\"},\"cover\":{\"action\":\"KEEP\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openLibraryIsbn").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.sourceProvider").value("SHOWAPI"))
                .andExpect(jsonPath("$.metadataSourceUrl").value(SHOWAPI_URL))
                .andExpect(jsonPath("$.cover.sourceProvider").value("OPEN_LIBRARY"));
        // metadataSource omitted = KEEP: the SHOWAPI source survives an unrelated edit.
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON).content(update + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceProvider").value("SHOWAPI"));
        // Applying OPEN_LIBRARY restores both the legacy and generic fields.
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(update + ",\"metadataSource\":{\"provider\":\"OPEN_LIBRARY\",\"isbn\":\"080442957X\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openLibraryIsbn").value("080442957X"))
                .andExpect(jsonPath("$.sourceProvider").value("OPEN_LIBRARY"));
        // Manual cover replacement carries no source; REMOVE clears the cover entirely.
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(update + ",\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover.sourceProvider").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cover.sourceUrl").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbcTemplate.queryForObject("SELECT provider FROM book_cover WHERE book_id=?", String.class, id)).isNull();
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(update + ",\"cover\":{\"action\":\"REMOVE\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover WHERE book_id=?", Long.class, id)).isZero();
        // An invalid source on update changes nothing.
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(update + ",\"metadataSource\":{\"provider\":\"BAD\",\"isbn\":\"080442957X\"}}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT source_provider FROM book WHERE id=?", String.class, id)).isEqualTo("OPEN_LIBRARY");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM book WHERE id=?", String.class, id)).isEqualTo("改后书名");
    }

    @Test
    void t3FailedSaveRollsBackBookCopiesAndCoverTogether() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(createJson("080442957X", "")))
                .andExpect(status().isCreated());
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(createJson("080442957X", ",\"metadataSource\":{\"provider\":\"SHOWAPI\",\"isbn\":\"080442957X\"},"
                        + "\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"" + png() + "\","
                        + "\"source\":{\"provider\":\"SHOWAPI\",\"isbn\":\"080442957X\"}}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISBN_DUPLICATE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_copy", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT source_provider FROM book", String.class)).isNull();
    }
}