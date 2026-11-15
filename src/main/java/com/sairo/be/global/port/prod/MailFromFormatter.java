package com.sairo.be.global.port.prod;

final class MailFromFormatter {
  private MailFromFormatter() {}

  static String display(String from, String fromName) {
    if (from.contains("<")) {
      return from;
    }
    String name = clean(fromName);
    if (name.isEmpty()) {
      return from;
    }
    return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + from + ">";
  }

  static String asciiDisplay(String from, String fromName) {
    String name = clean(fromName);
    if (!name.chars().allMatch(c -> c < 0x80)) {
      return from;
    }
    return display(from, name);
  }

  private static String clean(String name) {
    return name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").strip();
  }
}
