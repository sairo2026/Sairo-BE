package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.entity.TaskSourceType;
import com.sairo.be.domain.home.entity.TaskStatus;
import com.sairo.be.domain.home.repository.ContractTaskSourceRepository;
import com.sairo.be.domain.home.repository.ContractTaskSourceRepository.ContractSource;
import com.sairo.be.domain.home.repository.TaskRepository;
import com.sairo.be.domain.home.repository.TaskRepository.TaskRow;
import com.sairo.be.domain.home.repository.TaskRepository.TaskValues;
import com.sairo.be.global.port.ContractWorkHooks;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 원천 자동 업무다. 잔금 예정 업무(CONTRACT_BALANCE)는 체결 계약에 잔금 예정일이 있으면, 임대 만기 업무(CONTRACT_MATURITY)는 체결된
 * 전세·월세의 만기가 오늘부터 90일 안이면 계약마다 하나씩 둔다. 원천이 바뀌면 같은 업무를 고치고 조건에서 벗어나면 미완료 업무를 취소한다. 업무 체크는 계약을 바꾸지
 * 않는다.
 */
@Profile("!migrate")
@Service
public class ContractWorkHookService implements ContractWorkHooks {

  static final String BALANCE_PREFIX = "잔금 예정 · ";
  static final String MATURITY_PREFIX = "임대 만기 · ";
  public static final long MATURITY_WINDOW_DAYS = 90;

  private final ContractTaskSourceRepository sourceRepository;
  private final TaskRepository taskRepository;
  private final Clock clock;

  public ContractWorkHookService(
      ContractTaskSourceRepository sourceRepository, TaskRepository taskRepository, Clock clock) {
    this.sourceRepository = sourceRepository;
    this.taskRepository = taskRepository;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void contractChanged(Long contractId) {
    Optional<ContractSource> found = sourceRepository.find(contractId);
    if (found.isEmpty()) {
      return;
    }
    ContractSource source = found.get();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    LocalDate today = TimeRules.displayDate(now);
    boolean active = "SIGNED".equals(source.contractStatus()) && !source.excluded();
    LocalDate balance = active ? source.balanceDueDate() : null;
    LocalDate maturity =
        active
                && !"SALE".equals(source.dealType())
                && source.leaseEndDate() != null
                && !source.leaseEndDate().isAfter(today.plusDays(MATURITY_WINDOW_DAYS))
            ? source.leaseEndDate()
            : null;
    sync(source, TaskSourceType.CONTRACT_BALANCE, BALANCE_PREFIX, balance, now);
    sync(source, TaskSourceType.CONTRACT_MATURITY, MATURITY_PREFIX, maturity, now);
  }

  private void sync(
      ContractSource source, TaskSourceType type, String prefix, LocalDate due, Instant now) {
    Optional<TaskRow> existing = taskRepository.lockBySource(type.name(), source.contractId());
    if (due == null) {
      existing
          .filter(row -> TaskStatus.TODO.name().equals(row.status()))
          .ifPresent(row -> taskRepository.update(row.id(), cancelled(row), now));
      return;
    }
    String title = title(prefix, source.propertyName());
    if (existing.isEmpty()) {
      taskRepository.insert(
          source.officeId(),
          new TaskValues(
              title,
              source.managerMembershipId(),
              due,
              null,
              null,
              source.propertyId(),
              null,
              TaskStatus.TODO.name(),
              null),
          type.name(),
          source.contractId(),
          now);
      return;
    }
    TaskRow row = existing.get();
    String status =
        TaskStatus.CANCELLED.name().equals(row.status()) ? TaskStatus.TODO.name() : row.status();
    TaskValues next =
        new TaskValues(
            title,
            source.managerMembershipId(),
            due,
            null,
            null,
            source.propertyId(),
            row.memo(),
            status,
            TaskStatus.DONE.name().equals(status) ? row.completedAt() : null);
    if (!same(row, next)) {
      taskRepository.update(row.id(), next, now);
    }
  }

  private static TaskValues cancelled(TaskRow row) {
    return new TaskValues(
        row.title(),
        row.assigneeMembershipId(),
        row.dueDate(),
        row.dueTime(),
        row.relatedCustomerId(),
        row.relatedPropertyId(),
        row.memo(),
        TaskStatus.CANCELLED.name(),
        null);
  }

  private static boolean same(TaskRow row, TaskValues next) {
    return row.title().equals(next.title())
        && row.assigneeMembershipId().equals(next.assigneeMembershipId())
        && row.dueDate().equals(next.dueDate())
        && Objects.equals(row.relatedPropertyId(), next.relatedPropertyId())
        && row.status().equals(next.status());
  }

  static String title(String prefix, String propertyName) {
    String name = propertyName == null ? "" : propertyName.strip();
    String title = prefix + name;
    return title.length() <= TaskService.TITLE_MAX
        ? title
        : title.substring(0, TaskService.TITLE_MAX);
  }
}
