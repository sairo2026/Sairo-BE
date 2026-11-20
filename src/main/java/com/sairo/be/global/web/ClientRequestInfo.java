package com.sairo.be.global.web;

import jakarta.servlet.http.HttpServletRequest;

public record ClientRequestInfo(String ipAddress, String userAgent) {

  public static final String CLIENT_IP_ATTRIBUTE = ClientRequestInfo.class.getName() + ".clientIp";

  private static final int USER_AGENT_MAX = 500;

  public static ClientRequestInfo from(HttpServletRequest request) {
    String agent = request.getHeader("User-Agent");
    if (agent != null && agent.length() > USER_AGENT_MAX) {
      agent = agent.substring(0, USER_AGENT_MAX);
    }
    Object resolved = request.getAttribute(CLIENT_IP_ATTRIBUTE);
    String ip = resolved instanceof String value ? value : request.getRemoteAddr();
    return new ClientRequestInfo(ip, agent);
  }
}
