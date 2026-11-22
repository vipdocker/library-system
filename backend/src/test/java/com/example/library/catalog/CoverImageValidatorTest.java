package com.example.library.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import com.example.library.catalog.dto.BookCreateRequest;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

import static org.assertj.core.api.Assertions.*;

class CoverImageValidatorTest {
    private final CoverImageValidator validator = new CoverImageValidator();

    @Test
    void acOl01AcceptsActualPngAndJpegAndHashesOriginalBytes() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            byte[] bytes = BookCoverApiTest.image(format, 20, 30);
            var image = validator.decode("image/" + format, Base64.getEncoder().encodeToString(bytes));
            assertThat(image.bytes()).isEqualTo(bytes);
            assertThat(image.width()).isEqualTo(20);
            assertThat(image.height()).isEqualTo(30);
            assertThat(image.sha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    @Test
    void acOl01RejectsFakeWrongMimeTruncatedImagesAndNonCanonicalBase64() throws Exception {
        byte[] png = BookCoverApiTest.image("png", 1, 1);
        for (String bad : new String[]{"", "AA", "AB==", "AA==\n", "data:image/png;base64,AA==", "____", "aGVsbG8="}) {
            assertThatThrownBy(() -> validator.decode("image/png", bad))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(ex.getFieldErrors().getFirst().field()).isEqualTo("cover");
                    });
        }
        assertThatThrownBy(() -> validator.validate("image/jpeg", png)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("image/png", java.util.Arrays.copyOf(png, 24))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("image/png", java.util.Arrays.copyOf(png, png.length - 12)))
                .isInstanceOf(ApiException.class);
        for (String type : new String[]{"image/svg+xml", "image/gif", "image/webp", "text/html"}) {
            assertThatThrownBy(() -> validator.validate(type, png)).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.COVER_UNSUPPORTED_TYPE));
        }
    }

    @Test
    void dod02RejectsByteAndPixelLimitsBeforeDecodingRaster() throws Exception {
        assertThatThrownBy(() -> validator.decode("image/png", "A".repeat(2796205)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> validator.validate("image/png", new byte[2097153]))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        byte[] wide = BookCoverApiTest.image("png", 4097, 1);
        byte[] pixels = BookCoverApiTest.image("png", 3200, 3200);
        assertThatThrownBy(() -> validator.validate("image/png", wide)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("image/png", pixels)).isInstanceOf(ApiException.class);
        assertThat(validator.validate("image/png", BookCoverApiTest.image("png", 4096, 1)).width()).isEqualTo(4096);
    }

    @Test
    void dod02CountsChunkedStreamAndClosesItOnOverflow() {
        boolean[] closed = {false};
        int[] read = {0};
        InputStream stream = new InputStream() {
            @Override public int read() { read[0]++; return 32; }
            @Override public void close() { closed[0] = true; }
        };
        var message = message(stream, new HttpHeaders());
        assertThatThrownBy(() -> new CatalogRequestBodyAdvice().beforeBodyRead(message, null, BookCreateRequest.class, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(read[0]).isEqualTo(3 * 1024 * 1024 + 1);
        assertThat(closed[0]).isTrue();
    }

    @Test
    void dod02DoesNotTrustSmallContentLength() {
        var headers = new HttpHeaders();
        headers.setContentLength(1);
        var message = message(new ByteArrayInputStream(new byte[3 * 1024 * 1024 + 1]), headers);
        assertThatThrownBy(() -> new CatalogRequestBodyAdvice().beforeBodyRead(message, null, BookCreateRequest.class, null))
                .isInstanceOf(ApiException.class);
    }

    private static HttpInputMessage message(InputStream stream, HttpHeaders headers) {
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return stream; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }
}
