package com.sairo.be.domain.support.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.util.Locale;
import java.util.Map;

public final class AttachmentRules {

  public static final long MAX_BYTES = 20L * 1024 * 1024;
  public static final int MAX_PER_INQUIRY = 10;
  public static final int FILE_NAME_MAX = 255;

  private static final Map<String, String> MEDIA_TYPE_BY_EXTENSION =
      Map.of(
          "pdf", "application/pdf",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png");

  private AttachmentRules() {}

  public static String fileName(String original) {
    if (original == null || original.isBlank()) {
      return "attachment";
    }
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).strip();
    if (name.length() > FILE_NAME_MAX) {
      String extension = extension(name);
      name = name.substring(0, FILE_NAME_MAX - extension.length()) + extension;
    }
    return name.isEmpty() ? "attachment" : name;
  }

  public static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
  }

  public static String mediaTypeFor(String fileName, String declaredContentType) {
    String extension = extension(fileName);
    String mediaType =
        extension.isEmpty() ? null : MEDIA_TYPE_BY_EXTENSION.get(extension.substring(1));
    if (mediaType == null) {
      throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    }
    if (declaredContentType != null && !declaredContentType.isBlank()) {
      String declared = declaredContentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
      String normalized = "image/jpg".equals(declared) ? "image/jpeg" : declared;
      if (!mediaType.equals(normalized)) {
        throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
      }
    }
    return mediaType;
  }

  public static void requireSignatureMatches(String mediaType, byte[] content) {
    boolean matches =
        switch (mediaType) {
          case "application/pdf" -> startsWith(content, '%', 'P', 'D', 'F');
          case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
          case "image/png" -> startsWith(content, 0x89, 'P', 'N', 'G');
          default -> false;
        };
    if (!matches) {
      throw new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    }
  }

  private static boolean startsWith(byte[] content, int... signature) {
    if (content.length < signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if ((content[i] & 0xFF) != signature[i]) {
        return false;
      }
    }
    return true;
  }
}
