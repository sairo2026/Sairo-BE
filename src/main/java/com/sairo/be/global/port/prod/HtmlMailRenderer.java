package com.sairo.be.global.port.prod;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class HtmlMailRenderer {
  private static final String URL_CHARS = "[A-Za-z0-9\\-._~:/?#@!$&*+,;=%()\\[\\]]+";
  private static final Pattern URL = Pattern.compile("https?://" + URL_CHARS);
  private static final Pattern STANDALONE_URL = Pattern.compile("https://" + URL_CHARS);

  private static final String BRAND_COLOR = "#3335a6";
  private static final String FONT =
      "'Malgun Gothic','맑은 고딕','Apple SD Gothic Neo',-apple-system,'Segoe UI',Roboto,Arial,sans-serif";
  private static final String TEXT_STYLE =
      "margin:0 0 16px 0;font-family:"
          + FONT
          + ";font-size:15px;line-height:1.7;color:#1f2937;word-break:break-word;";

  private final String logoUrl;
  private final String serviceUrl;

  HtmlMailRenderer(
      @Value("${sairo.delivery.mail.logo-url:https://app.sairo.agency/email/sairo-logo.png}")
          String logoUrl,
      @Value("${app.frontend-base-url:https://app.sairo.agency}") String serviceUrl) {
    this.logoUrl = logoUrl == null ? "" : logoUrl.strip();
    this.serviceUrl = serviceUrl == null ? "" : serviceUrl.strip().replaceAll("/+$", "");
  }

  String render(String body) {
    StringBuilder content = new StringBuilder();
    List<String> paragraph = new ArrayList<>();
    String normalized = (body == null ? "" : body).replace("\r\n", "\n").replace('\r', '\n');
    for (String line : normalized.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        flushParagraph(content, paragraph);
      } else if (STANDALONE_URL.matcher(trimmed).matches()) {
        flushParagraph(content, paragraph);
        appendButton(content, trimmed);
      } else {
        paragraph.add(line.stripTrailing());
      }
    }
    flushParagraph(content, paragraph);

    StringBuilder html = new StringBuilder(content.length() + 2048);
    html.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta name=\"color-scheme\" content=\"light\">")
        .append("<meta name=\"supported-color-schemes\" content=\"light\">")
        .append("<title>사이로(SAIRO)</title></head>")
        .append("<body style=\"margin:0;padding:0;background-color:#f3f4f6;\">")
        .append(
            "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " border=\"0\" bgcolor=\"#f3f4f6\" style=\"background-color:#f3f4f6;\">")
        .append("<tr><td align=\"center\" style=\"padding:24px 12px;\">")
        .append(
            "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " border=\"0\" bgcolor=\"#ffffff\" style=\"max-width:560px;width:100%;"
                + "background-color:#ffffff;border-radius:12px;\">");
    appendLogoRow(html);
    html.append("<tr><td style=\"padding:8px 28px 8px 28px;background-color:#ffffff;\">")
        .append(content)
        .append("</td></tr>");
    appendFooterRow(html);
    html.append("</table></td></tr></table></body></html>");
    return html.toString();
  }

  private void appendLogoRow(StringBuilder html) {
    if (!isHttpUrl(logoUrl)) {
      return;
    }
    html.append(
            "<tr><td align=\"left\" style=\"padding:28px 28px 8px 28px;background-color:#ffffff;\">")
        .append("<img src=\"")
        .append(escape(logoUrl))
        .append("\" alt=\"사이로(SAIRO)\" width=\"120\" ")
        .append(
            "style=\"display:block;width:120px;height:auto;border:0;background-color:#ffffff;\">")
        .append("</td></tr>");
  }

  private void appendFooterRow(StringBuilder html) {
    html.append(
            "<tr><td style=\"padding:16px 28px 28px 28px;background-color:#ffffff;"
                + "border-radius:0 0 12px 12px;\">")
        .append("<div style=\"border-top:1px solid #e5e7eb;padding-top:16px;font-family:")
        .append(FONT)
        .append(";font-size:12px;line-height:1.6;color:#6b7280;\">")
        .append("<strong style=\"color:#374151;\">사이로(SAIRO)</strong>");
    if (isHttpUrl(serviceUrl)) {
      html.append("<br><a href=\"")
          .append(escape(serviceUrl))
          .append("\" style=\"color:")
          .append(BRAND_COLOR)
          .append(";text-decoration:underline;\">")
          .append(escape(serviceUrl))
          .append("</a>");
    }
    html.append("<br>이 메일은 발신 전용입니다. 문의는 서비스 안의 고객센터를 이용해 주세요.").append("</div></td></tr>");
  }

  private void flushParagraph(StringBuilder content, List<String> paragraph) {
    if (paragraph.isEmpty()) {
      return;
    }
    content.append("<p style=\"").append(TEXT_STYLE).append("\">");
    for (int i = 0; i < paragraph.size(); i++) {
      if (i > 0) {
        content.append("<br>");
      }
      content.append(linkify(paragraph.get(i)));
    }
    content.append("</p>");
    paragraph.clear();
  }

  private void appendButton(StringBuilder content, String url) {
    String href = escape(url);
    content
        .append(
            "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"margin:8px 0 12px 0;\"><tr>")
        .append("<td align=\"center\" bgcolor=\"")
        .append(BRAND_COLOR)
        .append("\" style=\"background-color:")
        .append(BRAND_COLOR)
        .append(";border-radius:8px;\"><a href=\"")
        .append(href)
        .append("\" style=\"display:inline-block;padding:13px 28px;font-family:")
        .append(FONT)
        .append(";font-size:15px;font-weight:bold;color:#ffffff;text-decoration:none;")
        .append("border-radius:8px;\">링크 열기</a></td></tr></table>")
        .append("<p style=\"margin:0 0 16px 0;font-family:")
        .append(FONT)
        .append(";font-size:12px;line-height:1.6;color:#6b7280;word-break:break-all;\">")
        .append("버튼이 열리지 않으면 아래 주소를 브라우저에 붙여 넣어 주세요.<br>")
        .append("<a href=\"")
        .append(href)
        .append("\" style=\"color:")
        .append(BRAND_COLOR)
        .append(";text-decoration:underline;\">")
        .append(href)
        .append("</a></p>");
  }

  private String linkify(String line) {
    StringBuilder out = new StringBuilder();
    Matcher matcher = URL.matcher(line);
    int last = 0;
    while (matcher.find()) {
      String url = trimTrailingPunctuation(matcher.group());
      int end = matcher.start() + url.length();
      out.append(escape(line.substring(last, matcher.start())));
      out.append("<a href=\"")
          .append(escape(url))
          .append("\" style=\"color:")
          .append(BRAND_COLOR)
          .append(";text-decoration:underline;\">")
          .append(escape(url))
          .append("</a>");
      last = end;
    }
    out.append(escape(line.substring(last)));
    return out.toString();
  }

  private static String trimTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      boolean closingWithoutOpening = c == ')' && url.indexOf('(') < 0;
      if (".,;:!?".indexOf(c) >= 0 || closingWithoutOpening) {
        end--;
      } else {
        break;
      }
    }
    return url.substring(0, end);
  }

  private static boolean isHttpUrl(String value) {
    return value.startsWith("https://") || value.startsWith("http://");
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
