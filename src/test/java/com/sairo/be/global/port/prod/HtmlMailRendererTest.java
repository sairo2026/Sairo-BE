package com.sairo.be.global.port.prod;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlMailRendererTest {
  private static final String LOGO = "https://app.sairo.agency/email/sairo-logo.png";
  private static final String SERVICE = "https://app.sairo.agency";

  private final HtmlMailRenderer renderer = new HtmlMailRenderer(LOGO, SERVICE);

  @Test
  void 본문의_HTML_특수문자를_이스케이프한다() {
    String html = renderer.render("A & B <b>굵게</b> \"따옴표\" '작은따옴표'");

    assertThat(html)
        .contains("A &amp; B &lt;b&gt;굵게&lt;/b&gt; &quot;따옴표&quot; &#39;작은따옴표&#39;")
        .doesNotContain("<b>굵게</b>");
  }

  @Test
  void 스크립트와_속성_탈출_문자열을_무해화한다() {
    String html =
        renderer.render(
            "<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n"
                + "https://evil.test/\"><script>alert(2)</script>\njavascript:alert(3)");

    assertThat(html)
        .doesNotContain("<script")
        .doesNotContain("<img src=x")
        .doesNotContain("href=\"javascript:")
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        .contains("&lt;img src=x onerror=alert(1)&gt;")
        .contains("<a href=\"https://evil.test/\"")
        .contains("&quot;&gt;&lt;script&gt;alert(2)&lt;/script&gt;");
  }

  @Test
  void 줄바꿈은_br로_문단은_p로_보존한다() {
    String html = renderer.render("첫 줄\r\n둘째 줄\n\n\n새 문단");

    assertThat(html).contains("첫 줄<br>둘째 줄</p>").contains(">새 문단</p>");
    assertThat(html.split("<p style=", -1)).hasSize(3);
  }

  @Test
  void 문장_속_URL은_누를_수_있는_링크로_바꾸고_문장부호는_뺀다() {
    String html = renderer.render("자세한 내용은 https://app.sairo.agency/help?a=1&b=2. 에서 확인하세요.");

    assertThat(html)
        .contains("<a href=\"https://app.sairo.agency/help?a=1&amp;b=2\"")
        .contains(">https://app.sairo.agency/help?a=1&amp;b=2</a>. 에서 확인하세요.")
        .doesNotContain("링크 열기");
  }

  @Test
  void 단독_줄의_https_URL은_버튼과_원문_주소로_보여준다() {
    String html =
        renderer.render(
            "아래 링크로 이메일을 인증해 주세요.\n\nhttps://app.sairo.agency/verify?token=abc\n\n감사합니다.");

    assertThat(html)
        .contains("링크 열기</a>")
        .contains("background-color:#3335a6")
        .contains(">https://app.sairo.agency/verify?token=abc</a></p>")
        .contains("감사합니다.");
    assertThat(html.split("href=\"https://app.sairo.agency/verify\\?token=abc\"", -1)).hasSize(3);
  }

  @Test
  void 단독_줄의_http_URL은_버튼이_아닌_링크로만_보여준다() {
    String html = renderer.render("http://example.test/path");

    assertThat(html).doesNotContain("링크 열기").contains("<a href=\"http://example.test/path\"");
  }

  @Test
  void 맨_위에_설정한_로고를_넣고_알트와_폭을_지정한다() {
    String html = new HtmlMailRenderer("https://cdn.example.test/logo.png", SERVICE).render("본문");

    assertThat(html)
        .contains(
            "<img src=\"https://cdn.example.test/logo.png\" alt=\"사이로(SAIRO)\" width=\"120\"");
    assertThat(html.indexOf("<img")).isLessThan(html.indexOf("본문"));
  }

  @Test
  void 로고_주소가_비었거나_http가_아니면_로고를_넣지_않는다() {
    assertThat(new HtmlMailRenderer("", SERVICE).render("본문")).doesNotContain("<img");
    assertThat(new HtmlMailRenderer("javascript:alert(1)", SERVICE).render("본문"))
        .doesNotContain("<img");
  }

  @Test
  void 로고_주소의_따옴표는_이스케이프한다() {
    String html =
        new HtmlMailRenderer("https://a.test/x\" onerror=\"alert(1)", SERVICE).render("본문");

    assertThat(html).doesNotContain("\" onerror=\"").contains("&quot; onerror=&quot;");
  }

  @Test
  void 푸터에_서비스_이름과_주소와_발신_전용_안내를_넣는다() {
    String html = renderer.render("본문");

    assertThat(html)
        .contains("사이로(SAIRO)</strong>")
        .contains(">https://app.sairo.agency</a>")
        .contains("이 메일은 발신 전용입니다. 문의는 서비스 안의 고객센터를 이용해 주세요.");
  }

  @Test
  void 서비스_주소_끝의_슬래시는_제거한다() {
    String html = new HtmlMailRenderer(LOGO, "https://app.sairo.agency/").render("본문");

    assertThat(html).contains(">https://app.sairo.agency</a>");
  }

  @Test
  void 흰색_카드와_연한_회색_배경과_최대_폭_그리고_표_레이아웃을_쓴다() {
    String html = renderer.render("본문");

    assertThat(html)
        .contains("max-width:560px")
        .contains("bgcolor=\"#ffffff\"")
        .contains("bgcolor=\"#f3f4f6\"")
        .contains("'Malgun Gothic'")
        .contains("'Apple SD Gothic Neo'")
        .contains("<meta name=\"viewport\"")
        .contains("<table role=\"presentation\"");
  }

  @Test
  void 빈_본문과_null도_문서를_만든다() {
    assertThat(renderer.render("")).startsWith("<!DOCTYPE html>").endsWith("</html>");
    assertThat(renderer.render(null)).startsWith("<!DOCTYPE html>").endsWith("</html>");
  }
}
