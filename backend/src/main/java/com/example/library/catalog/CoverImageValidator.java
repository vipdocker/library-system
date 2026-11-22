package com.example.library.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import org.springframework.stereotype.Component;

/** Bounded JPEG/PNG validation, outside database transactions; stores original bytes (including EXIF). */
@Component
public class CoverImageValidator {
    public static final int MAX_BYTES = 2097152;
    public static final int MAX_BASE64 = 2796204;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public record Image(byte[] bytes, String mediaType, int width, int height, String sha256) {
    }

    public Image decode(String mediaType, String base64) {
        requireType(mediaType);
        if (base64 == null || base64.isEmpty()) {
            throw invalid("缺少封面图片");
        }
        if (base64.length() > MAX_BASE64) {
            throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        // The basic decoder rejects whitespace and URL-safe variants. Canonical re-encoding also rejects
        // missing padding and non-zero unused bits without a potentially expensive regex on a large body.
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw invalid("封面 Base64 不合法");
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(base64)) {
            throw invalid("封面须使用标准 Base64");
        }
        return validate(mediaType, bytes);
    }

    public Image validate(String mediaType, byte[] bytes) {
        requireType(mediaType);
        if (bytes == null || bytes.length == 0) {
            throw invalid("封面为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        boolean png = bytes.length >= 8 && java.util.Arrays.equals(PNG, java.util.Arrays.copyOf(bytes, 8));
        boolean jpeg = bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255 && (bytes[bytes.length - 2] & 255) == 255
                && (bytes[bytes.length - 1] & 255) == 217;
        if (!("image/png".equals(mediaType) && png || "image/jpeg".equals(mediaType) && jpeg)) {
            throw invalid("封面内容与 JPEG/PNG 类型不一致或图片不完整");
        }
        if (png) {
            validatePngChunks(bytes);
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("封面图片无法读取");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 10000000) {
                    throw invalid("封面尺寸超过限制");
                }
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                var decoded = reader.read(0);
                if (decoded == null || warning[0] || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("封面图片不完整");
                }
                decoded.flush();
                return new Image(bytes, mediaType, width, height,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("封面图片无法完整解码");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ImageIO can decode all pixels even when the IEND chunk is missing. Check bounded chunk
    // framing and CRCs as well, so a truncated/corrupt PNG cannot become a valid saved candidate.
    private static void validatePngChunks(byte[] bytes) {
        int offset = 8;
        while (offset <= bytes.length - 12) {
            long length = unsignedInt(bytes, offset);
            if (length > bytes.length - offset - 12) {
                throw invalid("PNG 数据块不完整");
            }
            int size = (int) length;
            long type = unsignedInt(bytes, offset + 4);
            if (offset == 8 && (type != 0x49484452L || size != 13)
                    || offset != 8 && type == 0x49484452L) {
                throw invalid("PNG 文件头不合法");
            }
            var crc = new java.util.zip.CRC32();
            crc.update(bytes, offset + 4, size + 4);
            if (crc.getValue() != unsignedInt(bytes, offset + 8 + size)) {
                throw invalid("PNG 数据块校验失败");
            }
            offset += size + 12;
            if (type == 0x49454e44L) {
                if (size == 0 && offset == bytes.length) {
                    return;
                }
                throw invalid("PNG 结束块不合法");
            }
        }
        throw invalid("PNG 缺少完整结束块");
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return (long) (bytes[offset] & 255) << 24 | (long) (bytes[offset + 1] & 255) << 16
                | (long) (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }

    private static void requireType(String mediaType) {
        if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
            throw ApiException.of(ErrorCode.COVER_UNSUPPORTED_TYPE);
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, Map.of(),
                List.of(new ErrorResponse.FieldErrorItem("cover", message)));
    }
}
