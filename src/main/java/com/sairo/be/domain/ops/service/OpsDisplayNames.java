package com.sairo.be.domain.ops.service;

/** 운영 콘솔에서 회원 이름을 가려 보이는 규칙이다. */
final class OpsDisplayNames {

  static final String UNKNOWN = "알 수 없음";
  static final String WITHDRAWN = "탈퇴한 회원";

  private OpsDisplayNames() {}

  /** 첫 글자와 마지막 글자만 남긴다. 두 글자면 첫 글자만, 한 글자면 모두 가린다. */
  static String member(String name, boolean withdrawn) {
    if (withdrawn) {
      return WITHDRAWN;
    }
    if (name == null || name.isBlank()) {
      return UNKNOWN;
    }
    String value = name.strip();
    int[] points = value.codePoints().toArray();
    if (points.length == 1) {
      return "*";
    }
    StringBuilder masked = new StringBuilder().appendCodePoint(points[0]);
    if (points.length == 2) {
      return masked.append('*').toString();
    }
    masked.append("*".repeat(points.length - 2));
    return masked.appendCodePoint(points[points.length - 1]).toString();
  }
}
