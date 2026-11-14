package com.sairo.be.global.web;

import jakarta.servlet.http.HttpServletRequest;

public record ClientRequestInfo(String ipAddress, String userAgent) {

  private static final int USER_AGENT_MAX = 500;

  public static ClientRequestInfo from(HttpServletRequest request) {
    String agent = request.getHeader("User-Agent");
    if (agent != null && agent.length() > USER_AGENT_MAX) {
      agent = agent.substring(0, USER_AGENT_MAX);
    }
    return new ClientRequestInfo(request.getRemoteAddr(), agent);
  }
}
