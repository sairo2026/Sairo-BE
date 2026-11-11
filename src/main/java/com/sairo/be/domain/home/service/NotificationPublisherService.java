package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.entity.NotificationTargetType;
import com.sairo.be.domain.home.entity.NotificationType;
import com.sairo.be.domain.home.repository.NotificationRepository;
import com.sairo.be.global.port.NotificationChannelSender;
import com.sairo.be.global.port.NotificationChannelSender.Delivery;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.support.AfterCommit;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 아웃박스다. 호출한 업무 트랜잭션 안에서 notification 행을 남기고, 커밋된 뒤에만 외부 전달 포트를 부른다. 외부 전달이 실패해도 알림함 행은 남는다.
 */
@Profile("!migrate")
@Service
public class NotificationPublisherService implements NotificationPublisher {

  static final int MESSAGE_MAX = 500;

  private static final Logger log = LoggerFactory.getLogger(NotificationPublisherService.class);

  private final NotificationRepository repository;
  private final ObjectProvider<NotificationChannelSender> channelSender;
  private final Clock clock;

  public NotificationPublisherService(
      NotificationRepository repository,
      ObjectProvider<NotificationChannelSender> channelSender,
      Clock clock) {
    this.repository = repository;
    this.channelSender = channelSender;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<Long> publish(Notice notice) {
    NotificationType type = NotificationType.valueOf(notice.type());
    NotificationTargetType target = NotificationTargetType.valueOf(notice.targetType());
    if (!type.allows(target)) {
      throw new IllegalArgumentException("알림 종류와 이동 대상 종류가 맞지 않습니다: " + type + "/" + target);
    }
    if (target.requiresTargetId() != (notice.targetId() != null)) {
      throw new IllegalArgumentException("이동 대상 식별값 유무가 대상 종류와 맞지 않습니다: " + target);
    }
    if (notice.recipientUserId() == null
        || (type.preferenceKey() != null
            && !repository.channelEnabled(notice.recipientUserId(), type.preferenceKey()))) {
      return Optional.empty();
    }
    String message = trim(notice.message());
    Long id =
        repository.insert(
            notice.officeId(),
            notice.recipientUserId(),
            type.name(),
            message,
            target.name(),
            notice.targetId(),
            clock.instant());
    Delivery delivery = new Delivery(id, notice.recipientUserId(), type.name(), message);
    AfterCommit.run(() -> deliver(delivery));
    return Optional.of(id);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<Long> publishToMembership(Long membershipId, Notice notice) {
    return repository
        .userOfMembership(membershipId)
        .flatMap(
            userId ->
                publish(
                    new Notice(
                        notice.officeId(),
                        userId,
                        notice.type(),
                        notice.message(),
                        notice.targetType(),
                        notice.targetId())));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Long> publishToOwners(Long officeId, Notice notice) {
    List<Long> ids = new ArrayList<>();
    for (Long userId : repository.ownerUserIds(officeId)) {
      publish(
              new Notice(
                  officeId,
                  userId,
                  notice.type(),
                  notice.message(),
                  notice.targetType(),
                  notice.targetId()))
          .ifPresent(ids::add);
    }
    return ids;
  }

  private void deliver(Delivery delivery) {
    NotificationChannelSender sender = channelSender.getIfAvailable();
    if (sender == null) {
      return;
    }
    try {
      sender.deliver(delivery);
    } catch (RuntimeException e) {
      log.warn("알림 외부 전달 실패 notificationId={}", delivery.notificationId(), e);
    }
  }

  private static String trim(String message) {
    String value = message == null ? "" : message.strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("알림 문구가 비어 있습니다.");
    }
    return value.length() <= MESSAGE_MAX ? value : value.substring(0, MESSAGE_MAX);
  }
}
