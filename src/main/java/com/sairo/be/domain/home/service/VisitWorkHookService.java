package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.entity.NotificationTargetType;
import com.sairo.be.domain.home.entity.NotificationType;
import com.sairo.be.domain.home.entity.TaskSourceType;
import com.sairo.be.domain.home.entity.TaskStatus;
import com.sairo.be.domain.home.repository.TaskRepository;
import com.sairo.be.domain.home.repository.TaskRepository.TaskRow;
import com.sairo.be.domain.home.repository.TaskRepository.TaskValues;
import com.sairo.be.domain.home.repository.VisitTaskSourceRepository;
import com.sairo.be.domain.home.repository.VisitTaskSourceRepository.CoordinationHead;
import com.sairo.be.domain.home.repository.VisitTaskSourceRepository.ItemSource;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.port.VisitWorkHooks;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 확정·취소·담당 변경을 홈 업무(source_type VISIT)와 알림함에 반영한다. 방문 조율 트랜잭션 안에서 불리며 같은 방문 항목의 업무는 하나만 둔다. 업무
 * 체크는 방문 결과를 바꾸지 않는다.
 */
@Profile("!migrate")
@Service
public class VisitWorkHookService implements VisitWorkHooks {

  static final String TITLE_PREFIX = "방문 안내 · ";
  static final String MANAGER_MESSAGE = "방문 조율 담당자로 지정됐습니다.";

  private final VisitTaskSourceRepository sourceRepository;
  private final TaskRepository taskRepository;
  private final NotificationPublisher publisher;
  private final Clock clock;

  public VisitWorkHookService(
      VisitTaskSourceRepository sourceRepository,
      TaskRepository taskRepository,
      NotificationPublisher publisher,
      Clock clock) {
    this.sourceRepository = sourceRepository;
    this.taskRepository = taskRepository;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void scheduleItemConfirmed(Long scheduleItemId) {
    sourceRepository
        .find(scheduleItemId)
        .filter(source -> "SCHEDULED".equals(source.result()) && source.startAt() != null)
        .ifPresent(this::upsert);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void scheduleItemCancelled(Long scheduleItemId) {
    Optional<TaskRow> task =
        taskRepository.lockBySource(TaskSourceType.VISIT.name(), scheduleItemId);
    if (task.isEmpty() || TaskStatus.CANCELLED.name().equals(task.get().status())) {
      return;
    }
    TaskRow row = task.get();
    taskRepository.update(
        row.id(),
        new TaskValues(
            row.title(),
            row.assigneeMembershipId(),
            row.dueDate(),
            row.dueTime(),
            row.relatedCustomerId(),
            row.relatedPropertyId(),
            row.memo(),
            TaskStatus.CANCELLED.name(),
            null),
        clock.instant());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void coordinationManagerChanged(Long coordinationId, Long actorMembershipId) {
    for (Long itemId : sourceRepository.scheduledItems(coordinationId)) {
      Optional<ItemSource> source = sourceRepository.find(itemId);
      if (source.isPresent() && source.get().startAt() != null) {
        upsert(source.get());
      }
    }
    Optional<CoordinationHead> head = sourceRepository.coordinationHead(coordinationId);
    if (head.isEmpty() || head.get().managerMembershipId().equals(actorMembershipId)) {
      return;
    }
    publisher.publishToMembership(
        head.get().managerMembershipId(),
        new Notice(
            head.get().officeId(),
            null,
            NotificationType.ASSIGNEE_CHANGED.name(),
            MANAGER_MESSAGE,
            NotificationTargetType.VISIT_COORDINATION.name(),
            coordinationId));
  }

  private void upsert(ItemSource source) {
    ZonedDateTime start = TimeRules.toDisplay(source.startAt());
    LocalTime time = start.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    String title = title(source.propertyName());
    Long property =
        source.officeId().equals(source.propertyOfficeId()) ? source.propertyId() : null;
    Instant now = clock.instant();
    Optional<TaskRow> existing =
        taskRepository.lockBySource(TaskSourceType.VISIT.name(), source.itemId());
    if (existing.isEmpty()) {
      taskRepository.insert(
          source.officeId(),
          new TaskValues(
              title,
              source.managerMembershipId(),
              start.toLocalDate(),
              time,
              source.customerId(),
              property,
              null,
              TaskStatus.TODO.name(),
              null),
          TaskSourceType.VISIT.name(),
          source.itemId(),
          now);
      return;
    }
    TaskRow row = existing.get();
    String status =
        TaskStatus.CANCELLED.name().equals(row.status()) ? TaskStatus.TODO.name() : row.status();
    taskRepository.update(
        row.id(),
        new TaskValues(
            title,
            source.managerMembershipId(),
            start.toLocalDate(),
            time,
            source.customerId(),
            property,
            row.memo(),
            status,
            TaskStatus.DONE.name().equals(status) ? row.completedAt() : null),
        now);
  }

  static String title(String propertyName) {
    String name = propertyName == null ? "" : propertyName.strip();
    String title = TITLE_PREFIX + name;
    return title.length() <= TaskService.TITLE_MAX
        ? title
        : title.substring(0, TaskService.TITLE_MAX);
  }
}
