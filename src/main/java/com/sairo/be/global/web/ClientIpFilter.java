package com.sairo.be.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stores the resolved client address on the request so every later reader sees the same value. */
@Profile("!migrate")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ClientIpFilter extends OncePerRequestFilter {

  private final ClientIpResolver resolver;

  public ClientIpFilter(
      @Value("${sairo.client-ip.trusted-proxies:127.0.0.0/8,::1/128,172.16.0.0/12}")
          List<String> trustedProxies,
      @Value("${sairo.client-ip.edge-token:}") String edgeToken) {
    this.resolver = new ClientIpResolver(trustedProxies, edgeToken);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    request.setAttribute(ClientRequestInfo.CLIENT_IP_ATTRIBUTE, resolver.resolve(request));
    filterChain.doFilter(request, response);
  }
}
