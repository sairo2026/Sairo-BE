package com.sairo.be.global.web;

import com.sairo.be.global.error.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Profile("!migrate")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String RESPONSE_HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = UUID.randomUUID().toString();
    MDC.put(TraceIds.MDC_KEY, traceId);
    response.setHeader(RESPONSE_HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TraceIds.MDC_KEY);
    }
  }
}
