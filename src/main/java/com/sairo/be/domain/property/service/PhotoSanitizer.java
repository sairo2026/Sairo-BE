package com.sairo.be.domain.property.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

public final class PhotoSanitizer {

  public static final long MAX_BYTES = 10L * 1024 * 1024;

  private static final Set<String> PNG_DROPPED = Set.of("eXIf", "tEXt", "zTXt", "iTXt");
  private static final Set<String> WEBP_DROPPED = Set.of("EXIF", "XMP ");
  private static final int JPEG_APP1 = 0xE1;
  private static final int JPEG_APP13 = 0xED;
  private static final int JPEG_COMMENT = 0xFE;

  public record Sanitized(String extension, String contentType, byte[] content) {}

  private PhotoSanitizer() {}

  public static Sanitized sanitize(byte[] data) {
    if (data.length >= 3
        && (data[0] & 0xFF) == 0xFF
        && (data[1] & 0xFF) == 0xD8
        && (data[2] & 0xFF) == 0xFF) {
      return new Sanitized("jpg", "image/jpeg", stripJpeg(data));
    }
    if (data.length >= 8 && isPngSignature(data)) {
      return new Sanitized("png", "image/png", stripPng(data));
    }
    if (data.length >= 12 && ascii(data, 0, 4).equals("RIFF") && ascii(data, 8, 4).equals("WEBP")) {
      return new Sanitized("webp", "image/webp", stripWebp(data));
    }
    throw invalid("JPG, PNG, WebP 형식만 올릴 수 있습니다.");
  }

  private static byte[] stripJpeg(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    out.write(data, 0, 2);
    int pos = 2;
    while (pos < data.length) {
      if ((data[pos] & 0xFF) != 0xFF) {
        throw invalid("손상된 이미지입니다.");
      }
      while (pos < data.length && (data[pos] & 0xFF) == 0xFF) {
        pos++;
      }
      if (pos >= data.length) {
        throw invalid("손상된 이미지입니다.");
      }
      int marker = data[pos] & 0xFF;
      int markerStart = pos - 1;
      pos++;
      if (marker == 0xD9) {
        out.write(data, markerStart, data.length - markerStart);
        return out.toByteArray();
      }
      if (marker == 0x01 || marker == 0x00 || (marker >= 0xD0 && marker <= 0xD7)) {
        out.write(data, markerStart, pos - markerStart);
        continue;
      }
      if (pos + 2 > data.length) {
        throw invalid("손상된 이미지입니다.");
      }
      int length = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
      int segmentEnd = pos + length;
      if (length < 2 || segmentEnd > data.length) {
        throw invalid("손상된 이미지입니다.");
      }
      if (marker == 0xDA) {
        out.write(data, markerStart, data.length - markerStart);
        return out.toByteArray();
      }
      boolean dropped = marker == JPEG_APP1 || marker == JPEG_APP13 || marker == JPEG_COMMENT;
      if (!dropped) {
        out.write(data, markerStart, segmentEnd - markerStart);
      }
      pos = segmentEnd;
    }
    throw invalid("손상된 이미지입니다.");
  }

  private static boolean isPngSignature(byte[] data) {
    int[] signature = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    for (int i = 0; i < signature.length; i++) {
      if ((data[i] & 0xFF) != signature[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] stripPng(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    out.write(data, 0, 8);
    int pos = 8;
    boolean ended = false;
    while (pos < data.length) {
      if (pos + 12 > data.length) {
        throw invalid("손상된 이미지입니다.");
      }
      long length = readInt(data, pos, false);
      String type = ascii(data, pos + 4, 4);
      long chunkEnd = pos + 12L + length;
      if (length < 0 || chunkEnd > data.length) {
        throw invalid("손상된 이미지입니다.");
      }
      if (!PNG_DROPPED.contains(type)) {
        out.write(data, pos, (int) (chunkEnd - pos));
      }
      pos = (int) chunkEnd;
      if (type.equals("IEND")) {
        ended = true;
        break;
      }
    }
    if (!ended) {
      throw invalid("손상된 이미지입니다.");
    }
    return out.toByteArray();
  }

  private static byte[] stripWebp(byte[] data) {
    long declared = readInt(data, 4, true);
    if (declared + 8 > data.length || declared < 4) {
      throw invalid("손상된 이미지입니다.");
    }
    int end = (int) (declared + 8);
    ByteArrayOutputStream chunks = new ByteArrayOutputStream(data.length);
    int pos = 12;
    while (pos < end) {
      if (pos + 8 > end) {
        throw invalid("손상된 이미지입니다.");
      }
      String type = ascii(data, pos, 4);
      long size = readInt(data, pos + 4, true);
      long padded = size + (size & 1);
      long chunkEnd = pos + 8L + padded;
      if (chunkEnd > end) {
        throw invalid("손상된 이미지입니다.");
      }
      if (!WEBP_DROPPED.contains(type)) {
        byte[] chunk = new byte[(int) (chunkEnd - pos)];
        System.arraycopy(data, pos, chunk, 0, chunk.length);
        if (type.equals("VP8X") && size >= 1) {
          chunk[8] = (byte) (chunk[8] & ~0x0C);
        }
        chunks.write(chunk, 0, chunk.length);
      }
      pos = (int) chunkEnd;
    }
    byte[] body = chunks.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
    out.write(data, 0, 4);
    writeIntLittleEndian(out, body.length + 4);
    out.write(data, 8, 4);
    out.write(body, 0, body.length);
    return out.toByteArray();
  }

  private static long readInt(byte[] data, int offset, boolean littleEndian) {
    long value = 0;
    for (int i = 0; i < 4; i++) {
      int b = data[offset + (littleEndian ? 3 - i : i)] & 0xFF;
      value = (value << 8) | b;
    }
    return value;
  }

  private static void writeIntLittleEndian(ByteArrayOutputStream out, int value) {
    out.write(value & 0xFF);
    out.write((value >> 8) & 0xFF);
    out.write((value >> 16) & 0xFF);
    out.write((value >> 24) & 0xFF);
  }

  private static String ascii(byte[] data, int offset, int length) {
    return new String(data, offset, length, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  private static BusinessException invalid(String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST,
        List.of(new FieldErrorItem("file", FieldErrorCode.INVALID_FORMAT, message)));
  }
}
