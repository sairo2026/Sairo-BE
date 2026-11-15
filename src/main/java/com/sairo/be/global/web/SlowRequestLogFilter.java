package com.sairo.be.global.web;

import com.sairo.be.global.error.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Profile("!migrate")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SlowRequestLogFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(SlowRequestLogFilter.class);
  private static final String UNMATCHED_PATH = "UNMATCHED";

  private final long thresholdMillis;

  public SlowRequestLogFilter(
      @Value("${sairo.logging.slow-request-threshold-millis:1000}") long thresholdMillis) {
    this.thresholdMillis = thresholdMillis;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/api") || path.startsWith("/api/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long startedAt = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
      if (elapsedMillis > thresholdMillis) {
        log.info(
            "slow request method={} path={} status={} elapsedMs={} traceId={}",
            request.getMethod(),
            pathPattern(request),
            response.getStatus(),
            elapsedMillis,
            TraceIds.currentOrNew());
      }
    }
  }

  private static String pathPattern(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? UNMATCHED_PATH : pattern.toString();
  }
}
