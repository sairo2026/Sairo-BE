package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.dto.response.NotificationItem;
import com.sairo.be.domain.home.repository.NotificationRepository;
import com.sairo.be.domain.home.repository.NotificationRepository.NotificationRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 알림함 조회와 읽음 처리(HOME-03)다. 본인에게 온 알림만 다룬다. */
@Profile("!migrate")
@Service
public class NotificationService {

  private final NotificationRepository repository;
  private final Clock clock;

  public NotificationService(NotificationRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<NotificationItem> list(
      StaffPrincipal principal, boolean unreadOnly, PageQuery page) {
    long total = repository.count(principal.userId(), principal.officeId(), unreadOnly);
    List<NotificationItem> items =
        repository
            .page(
                principal.userId(),
                principal.officeId(),
                unreadOnly,
                page.size(),
                (long) page.page() * page.size())
            .stream()
            .map(NotificationService::item)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  @Transactional
  public void markRead(StaffPrincipal principal, Long notificationId) {
    if (!repository.markRead(
        notificationId, principal.userId(), principal.officeId(), clock.instant())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  @Transactional(readOnly = true)
  public long unreadCount(StaffPrincipal principal) {
    return repository.count(principal.userId(), principal.officeId(), true);
  }

  private static NotificationItem item(NotificationRow row) {
    return new NotificationItem(
        row.id(),
        row.type(),
        row.message(),
        row.readAt() != null,
        row.targetType(),
        row.targetId(),
        TimeRules.toDisplayOffset(row.createdAt()));
  }
}
