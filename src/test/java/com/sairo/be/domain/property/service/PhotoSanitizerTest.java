package com.sairo.be.domain.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class PhotoSanitizerTest {

  private static final byte[] EXIF_MARK = "GPSLatitude".getBytes(StandardCharsets.US_ASCII);

  public static byte[] minimalPng() {
    return pngWith(false);
  }

  public static byte[] pngWithMetadata() {
    return pngWith(true);
  }

  private static byte[] pngWith(boolean metadata) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    out.writeBytes(pngChunk("IHDR", new byte[13]));
    if (metadata) {
      out.writeBytes(pngChunk("eXIf", EXIF_MARK));
      out.writeBytes(pngChunk("tEXt", "Comment\0GPS".getBytes(StandardCharsets.US_ASCII)));
    }
    out.writeBytes(pngChunk("IDAT", new byte[] {1, 2, 3}));
    out.writeBytes(pngChunk("IEND", new byte[0]));
    return out.toByteArray();
  }

  private static byte[] pngChunk(String type, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(intBig(data.length));
    out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(data);
    out.writeBytes(new byte[4]);
    return out.toByteArray();
  }

  public static byte[] jpegWithExif() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
    out.writeBytes(jpegSegment(0xE0, "JFIF\0".getBytes(StandardCharsets.US_ASCII)));
    byte[] exif = new byte[EXIF_MARK.length + 6];
    System.arraycopy("Exif\0\0".getBytes(StandardCharsets.US_ASCII), 0, exif, 0, 6);
    System.arraycopy(EXIF_MARK, 0, exif, 6, EXIF_MARK.length);
    out.writeBytes(jpegSegment(0xE1, exif));
    out.writeBytes(jpegSegment(0xDB, new byte[65]));
    out.writeBytes(jpegSegment(0xDA, new byte[] {1, 1, 0, 0, 63, 0}));
    out.writeBytes(new byte[] {5, 6, 7, (byte) 0xFF, (byte) 0xD9});
    return out.toByteArray();
  }

  private static byte[] jpegSegment(int marker, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF);
    out.write(marker);
    int length = payload.length + 2;
    out.write((length >> 8) & 0xFF);
    out.write(length & 0xFF);
    out.writeBytes(payload);
    return out.toByteArray();
  }

  public static byte[] webpWithExif() {
    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    byte[] vp8x = new byte[10];
    vp8x[0] = 0x0C;
    chunks.writeBytes(webpChunk("VP8X", vp8x));
    chunks.writeBytes(webpChunk("VP8 ", new byte[] {1, 2, 3}));
    chunks.writeBytes(webpChunk("EXIF", EXIF_MARK));
    chunks.writeBytes(webpChunk("XMP ", EXIF_MARK));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(intLittle(chunks.size() + 4));
    out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(chunks.toByteArray());
    return out.toByteArray();
  }

  private static byte[] webpChunk(String type, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(intLittle(data.length));
    out.writeBytes(data);
    if ((data.length & 1) == 1) {
      out.write(0);
    }
    return out.toByteArray();
  }

  private static byte[] intBig(int value) {
    return new byte[] {
      (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
    };
  }

  private static byte[] intLittle(int value) {
    return new byte[] {
      (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
    };
  }

  private static boolean contains(byte[] data, byte[] needle) {
    for (int i = 0; i + needle.length <= data.length; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return true;
      }
    }
    return false;
  }

  @Test
  void JPEG의_EXIF_세그먼트를_제거하고_나머지는_보존한다() {
    byte[] original = jpegWithExif();
    assertThat(contains(original, EXIF_MARK)).isTrue();
    PhotoSanitizer.Sanitized result = PhotoSanitizer.sanitize(original);
    assertThat(result.extension()).isEqualTo("jpg");
    assertThat(result.contentType()).isEqualTo("image/jpeg");
    assertThat(contains(result.content(), EXIF_MARK)).isFalse();
    assertThat(contains(result.content(), "JFIF".getBytes(StandardCharsets.US_ASCII))).isTrue();
    assertThat(result.content()).endsWith(new byte[] {5, 6, 7, (byte) 0xFF, (byte) 0xD9});
  }

  @Test
  void PNG의_메타데이터_청크를_제거하고_IEND로_끝난다() {
    PhotoSanitizer.Sanitized result = PhotoSanitizer.sanitize(pngWithMetadata());
    assertThat(result.extension()).isEqualTo("png");
    assertThat(contains(result.content(), EXIF_MARK)).isFalse();
    assertThat(contains(result.content(), "tEXt".getBytes(StandardCharsets.US_ASCII))).isFalse();
    assertThat(contains(result.content(), "IDAT".getBytes(StandardCharsets.US_ASCII))).isTrue();
    assertThat(result.content().length).isLessThan(pngWithMetadata().length);
  }

  @Test
  void WebP의_EXIF와_XMP_청크를_제거하고_크기와_플래그를_고친다() {
    byte[] original = webpWithExif();
    PhotoSanitizer.Sanitized result = PhotoSanitizer.sanitize(original);
    byte[] content = result.content();
    assertThat(result.extension()).isEqualTo("webp");
    assertThat(contains(content, EXIF_MARK)).isFalse();
    long declared =
        (content[4] & 0xFFL)
            | ((content[5] & 0xFFL) << 8)
            | ((content[6] & 0xFFL) << 16)
            | ((content[7] & 0xFFL) << 24);
    assertThat(declared).isEqualTo(content.length - 8);
    assertThat(content[20] & 0x0C).isZero();
  }

  @Test
  void 지원하지_않는_형식이나_손상된_파일은_INVALID_REQUEST이다() {
    byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(() -> PhotoSanitizer.sanitize(gif))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    byte[] truncatedPng = java.util.Arrays.copyOf(minimalPng(), 30);
    assertThatThrownBy(() -> PhotoSanitizer.sanitize(truncatedPng))
        .isInstanceOf(BusinessException.class);
    byte[] truncatedJpeg = java.util.Arrays.copyOf(jpegWithExif(), 12);
    assertThatThrownBy(() -> PhotoSanitizer.sanitize(truncatedJpeg))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> PhotoSanitizer.sanitize(new byte[0]))
        .isInstanceOf(BusinessException.class);
  }
}
