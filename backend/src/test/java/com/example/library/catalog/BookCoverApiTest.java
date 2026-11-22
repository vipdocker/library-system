package com.example.library.catalog;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookCoverApiTest extends CatalogTestBase {
    private static final String ISBN = "9787115428028";
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.library.domain.repository.BookCopyRepository copies;

    static byte[] image(String format, int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes)).isTrue();
        return bytes.toByteArray();
    }

    private String json(String extra) {
        return "{\"isbn\":\"" + ISBN + "\",\"title\":\"封面书\",\"author\":\"作者\"" + extra + "}";
    }

    private String replacement(byte[] bytes, String type) {
        return ",\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"" + type
                + "\",\"base64\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"sourceIsbn\":\"0-8044-2957-X\"}";
    }

    @Test
    void acOl03StoresOriginalBytesAndReturnsLocalMetadata() throws Exception {
        byte[] bytes = image("png", 12, 16);
        var created = asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(bytes, "image/png") + ",\"openLibraryIsbn\":\"0-8044-2957-x\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cover.width").value(12))
                .andExpect(jsonPath("$.cover.height").value(16))
                .andExpect(jsonPath("$.cover.byteLength").value(bytes.length))
                .andExpect(jsonPath("$.cover.sourceUrl").value("https://openlibrary.org/isbn/080442957X"))
                .andExpect(jsonPath("$.openLibraryIsbn").value("080442957X"))
                .andExpect(jsonPath("$.metadataSourceUrl").value("https://openlibrary.org/isbn/080442957X"))
                .andReturn();
        long id = idOf(created);
        var loaded = asReader(get("/api/books/" + id + "/cover"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(header().string("Cache-Control", "private,no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
        assertThat(loaded.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(jdbcTemplate.queryForObject("SELECT image_data FROM book_cover WHERE book_id=?", byte[].class, id)).isEqualTo(bytes);
        asReader(get("/api/books")).andExpect(jsonPath("$.items[0].cover.width").value(12));
        asReader(get("/api/books/" + id)).andExpect(jsonPath("$.cover.width").value(12));
        assertThat(body(created)).doesNotContain("base64", "image_data");
    }

    @Test
    void acOl01KeepReplaceRemoveAndInvalidReplacementPreservesOriginal() throws Exception {
        byte[] original = image("png", 8, 10);
        long id = idOf(asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(original, "image/png")))).andExpect(status().isCreated()).andReturn());
        for (String keep : new String[]{"", ",\"cover\":null", ",\"cover\":{\"action\":\"KEEP\"}"}) {
            asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON).content(json(keep)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cover.width").value(8));
        }
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json(",\"cover\":{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"aGVsbG8=\"}").replace("封面书", "不应保存")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("cover"));
        asReader(get("/api/books/" + id)).andExpect(jsonPath("$.title").value("封面书"));
        asReader(get("/api/books/" + id + "/cover")).andExpect(content().bytes(original));
        byte[] jpeg = image("jpeg", 6, 9);
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON).content(json(replacement(jpeg, "image/jpeg"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cover.mediaType").value("image/jpeg"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover WHERE book_id=?", Long.class, id)).isEqualTo(1);
        asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON).content(json(",\"cover\":{\"action\":\"REMOVE\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cover").value(org.hamcrest.Matchers.nullValue()));
        asReader(get("/api/books/" + id + "/cover")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COVER_NOT_FOUND"));
    }

    @Test
    void acOl05AuthorizationCsrfAndMissingBook() throws Exception {
        anon(get("/api/books/999/cover")).andExpect(status().isUnauthorized());
        asReader(get("/api/books/999/cover")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
        asReader(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(json(""))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/books").session(admin.session()).contentType(MediaType.APPLICATION_JSON).content(json("")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void acOl01RejectsUnsupportedTypeAndBadActionsBeforeAnyWrite() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(json(replacement(image("png", 1, 1), "image/gif"))))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("COVER_UNSUPPORTED_TYPE"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        for (String cover : new String[]{"{\"action\":\"KEEP\",\"base64\":\"AA==\"}", "{\"action\":\"BOGUS\"}",
                "{\"action\":\"REMOVE\",\"mediaType\":\"image/png\"}",
                "{\"action\":\"REPLACE\",\"mediaType\":\"image/png\",\"base64\":\"AA==\",\"sourceIsbn\":\"bad\"}"}) {
            asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(json(",\"cover\":" + cover)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("cover"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
    }

    @Test
    void acOl01BoundedJsonEvenWithoutContentLength() throws Exception {
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json("") + " ".repeat(3 * 1024 * 1024)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
    }

    @Test
    void dod01DatabaseFailureRollsBackBookCoverAndInitialCopies() throws Exception {
        jdbcTemplate.execute("CREATE TRIGGER ol_fail_cover BEFORE INSERT ON book_cover BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        try {
            asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                    .content(json(replacement(image("png", 1, 1), "image/png") + ",\"initialCopies\":3")))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book", Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_copy", Long.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER ol_fail_cover");
        }
        byte[] original = image("png", 2, 3);
        long id = idOf(asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(original, "image/png")))).andReturn());
        jdbcTemplate.execute("CREATE TRIGGER ol_fail_cover BEFORE INSERT ON book_cover BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        try {
            asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content(json(replacement(image("png", 4, 5), "image/png")).replace("封面书", "不应保存")))
                    .andExpect(status().isInternalServerError());
            asReader(get("/api/books/" + id)).andExpect(jsonPath("$.title").value("封面书"));
            asReader(get("/api/books/" + id + "/cover")).andExpect(content().bytes(original));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER ol_fail_cover");
        }
    }

    @Test
    void acOl05RefusedDeletionAndDuplicateIsbnPreserveCover() throws Exception {
        byte[] bytes = image("png", 1, 1);
        long id = idOf(asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(bytes, "image/png")))).andReturn());
        var copy = copies.findByBookIdOrderByIdAsc(id).getFirst();
        var today = java.time.LocalDate.now(mutableClock);
        testDataFactory.returnedLoan(copy, readerFixture.reader(), today.minusDays(40), today.minusDays(10),
                today.minusDays(5), adminUser.getId());
        asAdmin(delete("/api/books/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_HAS_LOAN_HISTORY"));
        asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(image("png", 6, 7), "image/png"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ISBN_DUPLICATE"));
        asReader(get("/api/books/" + id + "/cover")).andExpect(content().bytes(bytes));
    }

    @Test
    void dod01ConcurrentUpdatesNeverMixBookAndCover() throws Exception {
        long id = idOf(asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(json(""))).andReturn());
        byte[] a = image("png", 2, 3);
        byte[] b = image("png", 4, 5);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(replacement(a, "image/png")).replace("封面书", "A"))).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return asAdmin(put("/api/books/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(replacement(b, "image/png")).replace("封面书", "B"))).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        String title = jdbcTemplate.queryForObject("SELECT title FROM book WHERE id=?", String.class, id);
        byte[] stored = asReader(get("/api/books/" + id + "/cover")).andReturn().getResponse().getContentAsByteArray();
        assertThat(stored).isEqualTo("A".equals(title) ? a : b);
        assertThat(title).isIn("A", "B");
    }

    @Test
    void acOl03LegalDeletionCascadesCover() throws Exception {
        long id = idOf(asAdmin(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(json(replacement(image("png", 1, 1), "image/png")))).andExpect(status().isCreated()).andReturn());
        asAdmin(delete("/api/books/" + id)).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
    }
}
