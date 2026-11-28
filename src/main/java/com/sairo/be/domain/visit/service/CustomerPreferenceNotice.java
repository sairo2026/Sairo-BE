package com.sairo.be.domain.visit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CustomerPreferenceNotice {
  public static final String VERSION = "1.0";
  public static final String TEXT =
      "수집 항목: 선호 요일과 선호 시간대.\n"
          + "이용 목적: 같은 사무소의 다음 방문 요청에 기본 선호를 표시합니다. 실제 가능한 날짜와 시간은 요청마다 고객이 확인합니다.\n"
          + "관리 주체: 현재 방문 요청을 보낸 사무소이며, 다른 사무소와 공유하지 않습니다.\n"
          + "보관 기간: 화면에 표시된 보관 기한까지입니다. 고객이 삭제하면 선호 값은 즉시 삭제하고, 확인 기록은 기존 방문 링크 기록 보관 기준에 따라 처리합니다.\n"
          + "기본 선호를 변경해도 이미 제출한 가능 시간과 확정 방문은 변경되지 않습니다.";
  public static final String SHA256 = sha256(TEXT);

  private CustomerPreferenceNotice() {}

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
