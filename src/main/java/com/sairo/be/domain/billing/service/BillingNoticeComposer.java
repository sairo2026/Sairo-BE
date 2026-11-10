package com.sairo.be.domain.billing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BillingNoticeComposer {

  public record BillingNotice(
      String title, String body, String emailFooter, String actionLabel, String inAppText) {

    public String emailBody() {
      return emailFooter.isEmpty() ? body : body + "\n\n" + emailFooter;
    }
  }

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
  private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=\\.)\\s+");

  private BillingNoticeComposer() {}

  public static BillingNotice compose(BillingNoticeTemplate template, Map<String, String> values) {
    String title = fill(template.title(), values);
    if (title == null) {
      throw new IllegalArgumentException("제목에 쓰는 값이 비어 있습니다: " + template.name());
    }
    String body = fillSentences(template.body(), values);
    String footer = fillSentences(BillingNoticeTemplate.COMMON_FOOTER, values);
    String inApp = template.inAppText() == null ? null : fill(template.inAppText(), values);
    return new BillingNotice(title, body, footer, template.actionLabel(), inApp);
  }

  private static String fillSentences(String text, Map<String, String> values) {
    List<String> lines = new ArrayList<>();
    for (String line : text.strip().split("\n")) {
      List<String> kept = new ArrayList<>();
      for (String sentence : SENTENCE_BREAK.split(line)) {
        String filled = fill(sentence, values);
        if (filled != null) {
          kept.add(filled);
        }
      }
      if (!kept.isEmpty()) {
        lines.add(String.join(" ", kept));
      }
    }
    return String.join("\n", lines);
  }

  private static String fill(String text, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null || value.isBlank()) {
        return null;
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
