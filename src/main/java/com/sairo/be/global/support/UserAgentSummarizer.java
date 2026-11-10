package com.sairo.be.global.support;

public final class UserAgentSummarizer {

  private static final String UNKNOWN = "알 수 없음";

  private UserAgentSummarizer() {}

  public static String summarize(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return UNKNOWN;
    }
    return browser(userAgent) + " on " + os(userAgent);
  }

  private static String browser(String ua) {
    if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
      return "Edge";
    }
    if (ua.contains("OPR/") || ua.contains("Opera")) {
      return "Opera";
    }
    if (ua.contains("SamsungBrowser/")) {
      return "Samsung Internet";
    }
    if (ua.contains("Firefox/") || ua.contains("FxiOS/")) {
      return "Firefox";
    }
    if (ua.contains("Chrome/") || ua.contains("CriOS/")) {
      return "Chrome";
    }
    if (ua.contains("Safari/")) {
      return "Safari";
    }
    return "기타 브라우저";
  }

  private static String os(String ua) {
    if (ua.contains("Windows")) {
      return "Windows";
    }
    if (ua.contains("Android")) {
      return "Android";
    }
    if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
      return "iOS";
    }
    if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
      return "macOS";
    }
    if (ua.contains("CrOS")) {
      return "ChromeOS";
    }
    if (ua.contains("Linux")) {
      return "Linux";
    }
    return "기타 OS";
  }
}
