package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Profile("!migrate")
@Component
public class EvidenceFileValidator {

  static final long MAX_BYTES = 10L * 1024 * 1024;
  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "pdf", "application/pdf",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "gif", "image/gif",
          "webp", "image/webp");
  private static final Pattern RESIDENT_NUMBER = Pattern.compile("\\d{6}-?[1-8]\\d{6}");

  public record ValidatedFile(byte[] content, String extension, String contentType) {}

  public ValidatedFile validate(EvidenceType type, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw invalid(type, FieldErrorCode.REQUIRED, "파일이 비어 있습니다.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw invalid(type, FieldErrorCode.OUT_OF_RANGE, "파일은 10MB 이하여야 합니다.");
    }
    String extension = extensionOf(file.getOriginalFilename());
    String expectedType = CONTENT_TYPES.get(extension);
    if (expectedType == null) {
      throw invalid(type, FieldErrorCode.NOT_ALLOWED, "이미지 또는 PDF 파일만 올릴 수 있습니다.");
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw invalid(type, FieldErrorCode.INVALID_VALUE, "파일을 읽을 수 없습니다.");
    }
    if (!matchesSignature(content, expectedType)) {
      throw invalid(type, FieldErrorCode.NOT_ALLOWED, "파일 내용이 확장자와 맞지 않습니다.");
    }
    if (RESIDENT_NUMBER.matcher(new String(content, StandardCharsets.ISO_8859_1)).find()) {
      throw invalid(type, FieldErrorCode.INVALID_VALUE, "주민등록번호 등 불필요한 정보가 포함돼 있습니다.");
    }
    return new ValidatedFile(content, extension.equals("jpeg") ? "jpg" : extension, expectedType);
  }

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static boolean matchesSignature(byte[] content, String contentType) {
    return switch (contentType) {
      case "application/pdf" -> startsWith(content, 0, new byte[] {'%', 'P', 'D', 'F'});
      case "image/jpeg" ->
          startsWith(content, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
      case "image/png" ->
          startsWith(content, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
      case "image/gif" -> startsWith(content, 0, new byte[] {'G', 'I', 'F', '8'});
      case "image/webp" ->
          startsWith(content, 0, new byte[] {'R', 'I', 'F', 'F'})
              && startsWith(content, 8, new byte[] {'W', 'E', 'B', 'P'});
      default -> false;
    };
  }

  private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
    if (content.length < offset + prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (content[offset + i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static BusinessException invalid(EvidenceType type, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST,
        List.of(new FieldErrorItem("evidenceType." + type.name(), code, message)));
  }
}
