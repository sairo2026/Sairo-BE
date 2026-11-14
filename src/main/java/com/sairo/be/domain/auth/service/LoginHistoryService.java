package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.LoginHistoryItemResponse;
import com.sairo.be.domain.auth.entity.AuthMethod;
import com.sairo.be.domain.auth.entity.LoginHistory;
import com.sairo.be.domain.auth.repository.LoginHistoryRepository;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.UserAgentSummarizer;
import com.sairo.be.global.web.ClientRequestInfo;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class LoginHistoryService {

  private final LoginHistoryRepository loginHistoryRepository;
  private final Clock clock;

  public LoginHistoryService(LoginHistoryRepository loginHistoryRepository, Clock clock) {
    this.loginHistoryRepository = loginHistoryRepository;
    this.clock = clock;
  }

  @Transactional
  public void record(Long userId, AuthMethod method, ClientRequestInfo client) {
    loginHistoryRepository.save(
        LoginHistory.record(
            userId, method, parseAddress(client.ipAddress()), client.userAgent(), clock.instant()));
  }

  @Transactional(readOnly = true)
  public PageResponse<LoginHistoryItemResponse> list(Long userId, PageQuery pageQuery) {
    Page<LoginHistory> page =
        loginHistoryRepository.findByUserId(
            userId,
            pageQuery.toPageable(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    return PageResponse.from(
        page,
        history ->
            new LoginHistoryItemResponse(
                history.getId(),
                history.getAuthMethod().name(),
                history.getIpAddress() == null ? "" : history.getIpAddress().getHostAddress(),
                UserAgentSummarizer.summarize(history.getUserAgent()),
                TimeRules.toDisplay(history.getCreatedAt()).toOffsetDateTime()));
  }

  private InetAddress parseAddress(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return InetAddress.getByName(raw.strip());
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
