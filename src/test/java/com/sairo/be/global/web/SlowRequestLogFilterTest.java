package com.sairo.be.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sairo.be.global.error.TraceIds;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class SlowRequestLogFilterTest {
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(SlowRequestLogFilter.class);

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
    MDC.put(TraceIds.MDC_KEY, "trace-1");
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    MDC.remove(TraceIds.MDC_KEY);
  }

  private static void run(
      SlowRequestLogFilter filter, MockHttpServletRequest request, FilterChain chain)
      throws Exception {
    filter.doFilter(request, new MockHttpServletResponse(), chain);
  }

  private static void pause(long millis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (System.nanoTime() < deadline) {
      LockSupport.parkNanos(1_000_000L);
    }
  }

  private static FilterChain handler(String pattern, int status, long sleepMillis) {
    return (req, res) -> {
      req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
      pause(sleepMillis);
      ((MockHttpServletResponse) res).setStatus(status);
    };
  }

  @Test
  void 임계값을_넘은_요청은_한_줄_INFO로_남긴다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/properties/123/memo");

    run(
        new SlowRequestLogFilter(10),
        request,
        handler("/api/properties/{propertyId}/memo", 201, 30));

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage())
        .matches(
            "slow request method=POST path=/api/properties/\\{propertyId\\}/memo status=201"
                + " elapsedMs=\\d+ traceId=trace-1");
  }

  @Test
  void 임계값을_넘지_않은_빠른_요청은_남기지_않는다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/home");

    run(new SlowRequestLogFilter(60_000), request, handler("/api/home", 200, 0));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void 경로_변수_값과_쿼리_문자열과_헤더는_남기지_않는다() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/public/links/secret-token-value");
    request.setQueryString("phone=01012345678");
    request.addHeader("Authorization", "Bearer secret-bearer");
    request.addHeader("Cookie", "SESSION=secret-session");

    run(new SlowRequestLogFilter(10), request, handler("/api/public/links/{token}", 200, 30));

    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("/api/public/links/{token}")
        .doesNotContain("secret-token-value")
        .doesNotContain("01012345678")
        .doesNotContain("secret-bearer")
        .doesNotContain("secret-session");
  }

  @Test
  void 처리기를_찾지_못한_요청은_원본_경로_대신_UNMATCHED로_남긴다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/unknown/abc-secret");

    run(new SlowRequestLogFilter(10), request, (req, res) -> pause(30));

    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("path=UNMATCHED")
        .doesNotContain("abc-secret");
  }

  @Test
  void 요청_처리_중_예외가_나도_시간을_남기고_예외는_그대로_전달한다() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/properties");
    FilterChain failing =
        (req, res) -> {
          pause(30);
          throw new IOException("연결 끊김");
        };

    assertThatThrownBy(() -> run(new SlowRequestLogFilter(10), request, failing))
        .isInstanceOf(IOException.class);

    assertThat(appender.list).hasSize(1);
  }

  @Test
  void API_경로가_아닌_요청은_측정하지_않는다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

    run(new SlowRequestLogFilter(0), request, handler("/actuator/health", 200, 30));

    assertThat(appender.list).isEmpty();
  }
}
