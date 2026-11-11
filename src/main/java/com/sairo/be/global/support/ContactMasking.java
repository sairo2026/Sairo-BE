package com.sairo.be.global.support;

/** 비회원 연락처를 화면에 보일 때 가리는 규칙이다. 접수 응답과 운영 콘솔 큐가 같은 규칙을 쓴다. */
public final class ContactMasking {

  private ContactMasking() {}

  /** 하이픈으로 나뉜 휴대폰 번호의 가운데 자리를 가린다. */
  public static String phone(String contact) {
    String[] parts = contact.split("-");
    if (parts.length != 3) {
      return "***";
    }
    return parts[0] + "-****-" + parts[2];
  }

  /** 이메일 앞부분의 처음 한두 글자만 남긴다. */
  public static String email(String contact) {
    int at = contact.indexOf('@');
    if (at < 1) {
      return "***";
    }
    String local = contact.substring(0, at);
    String visible = local.substring(0, Math.min(2, Math.max(1, local.length() - 1)));
    return visible + "***" + contact.substring(at);
  }
}
