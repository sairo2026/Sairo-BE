package com.sairo.be.domain.home.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.home.dto.response.TaskListItem;
import com.sairo.be.domain.home.dto.response.TaskResponse;
import com.sairo.be.domain.home.entity.TaskSourceType;
import com.sairo.be.domain.home.entity.TaskStatus;
import com.sairo.be.domain.home.repository.TaskRepository;
import com.sairo.be.domain.home.repository.TaskRepository.ListFilter;
import com.sairo.be.domain.home.repository.TaskRepository.TaskListRow;
import com.sairo.be.domain.home.repository.TaskRepository.TaskRow;
import com.sairo.be.domain.home.repository.TaskRepository.TaskValues;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 홈 업무 목록·생성·수정·삭제(HOME-02)다. 자동 생성 업무는 완료 처리, 완료 취소, 메모만 바꿀 수 있다. */
@Profile("!migrate")
@Service
public class TaskService {

  static final int TITLE_MAX = 200;
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
  private static final Set<String> CREATE_KEYS =
      Set.of(
          "title",
          "assigneeMembershipId",
          "dueDate",
          "dueTime",
          "relatedCustomerId",
          "relatedPropertyId",
          "memo");
  private static final Set<String> UPDATE_KEYS =
      Set.of(
          "title",
          "assigneeMembershipId",
          "dueDate",
          "dueTime",
          "relatedCustomerId",
          "relatedPropertyId",
          "memo",
          "status");
  private static final Set<String> AUTO_EDITABLE_KEYS = Set.of("status", "memo");

  private final TaskRepository repository;
  private final Clock clock;

  public TaskService(TaskRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<TaskListItem> list(
      StaffPrincipal principal, String status, Long assigneeMembershipId, PageQuery page) {
    Set<TaskStatus> statuses =
        StatusFilter.parse(status, TaskStatus.class, EnumSet.allOf(TaskStatus.class));
    boolean deputy = principal.role() == StaffRole.DEPUTY;
    Long assignee = assigneeMembershipId;
    if (deputy) {
      if (assignee != null && !assignee.equals(principal.membershipId())) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
      }
      assignee = principal.membershipId();
    }
    ListFilter filter =
        new ListFilter(
            principal.officeId(),
            assignee,
            statuses.stream().map(Enum::name).collect(Collectors.toSet()),
            deputy);
    long total = repository.count(filter);
    List<TaskListItem> items =
        repository.list(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(TaskService::listItem)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  @Transactional
  public TaskResponse create(StaffPrincipal principal, JsonNode body) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    String title = title(body);
    Long assignee = CustomerInput.requiredLong(body, "assigneeMembershipId");
    LocalDate dueDate = CustomerInput.requiredDate(body, "dueDate");
    LocalTime dueTime = dueTime(body);
    Long customer = CustomerInput.optionalLong(body, "relatedCustomerId");
    Long property = CustomerInput.optionalLong(body, "relatedPropertyId");
    String memo = memo(body);
    if (principal.role() == StaffRole.DEPUTY && !assignee.equals(principal.membershipId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    validateReferences(principal.officeId(), assignee, customer, property);
    Instant now = clock.instant();
    Long id =
        repository.insert(
            principal.officeId(),
            new TaskValues(
                title,
                assignee,
                dueDate,
                dueTime,
                customer,
                property,
                memo,
                TaskStatus.TODO.name(),
                null),
            TaskSourceType.MANUAL.name(),
            null,
            now);
    return response(repository.find(principal.officeId(), id).orElseThrow());
  }

  @Transactional
  public TaskResponse update(
      StaffPrincipal principal, Long taskId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, UPDATE_KEYS);
    TaskRow task =
        repository
            .lock(principal.officeId(), taskId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireOwnTaskForDeputy(principal, task);
    TaskSourceType source = TaskSourceType.valueOf(task.sourceType());
    TaskStatus current = TaskStatus.valueOf(task.status());
    TaskStatus next =
        body.has("status") ? CustomerInput.requiredEnum(body, "status", TaskStatus.class) : current;
    if (source.isAutomatic()) {
      for (String key : body.propertyNames()) {
        if (!AUTO_EDITABLE_KEYS.contains(key)) {
          throw new BusinessException(ErrorCode.AUTO_TASK_IMMUTABLE);
        }
      }
      if (next != current && !autoTransitionAllowed(current, next)) {
        throw new BusinessException(ErrorCode.AUTO_TASK_IMMUTABLE);
      }
    }
    String title = body.has("title") ? title(body) : task.title();
    Long assignee =
        body.has("assigneeMembershipId")
            ? CustomerInput.requiredLong(body, "assigneeMembershipId")
            : task.assigneeMembershipId();
    LocalDate dueDate =
        body.has("dueDate") ? CustomerInput.requiredDate(body, "dueDate") : task.dueDate();
    LocalTime dueTime = body.has("dueTime") ? dueTime(body) : task.dueTime();
    Long customer =
        body.has("relatedCustomerId")
            ? CustomerInput.optionalLong(body, "relatedCustomerId")
            : task.relatedCustomerId();
    Long property =
        body.has("relatedPropertyId")
            ? CustomerInput.optionalLong(body, "relatedPropertyId")
            : task.relatedPropertyId();
    String memo = body.has("memo") ? memo(body) : task.memo();
    if (principal.role() == StaffRole.DEPUTY && !assignee.equals(principal.membershipId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (task.version() != expectedVersion) {
      throw BusinessException.versionConflict(task.version());
    }
    validateReferences(
        principal.officeId(),
        assignee.equals(task.assigneeMembershipId()) ? null : assignee,
        changed(customer, task.relatedCustomerId()),
        changed(property, task.relatedPropertyId()));
    Instant now = clock.instant();
    Instant completedAt =
        next == TaskStatus.DONE ? (current == TaskStatus.DONE ? task.completedAt() : now) : null;
    repository.update(
        task.id(),
        new TaskValues(
            title, assignee, dueDate, dueTime, customer, property, memo, next.name(), completedAt),
        now);
    return response(repository.find(principal.officeId(), taskId).orElseThrow());
  }

  @Transactional
  public void delete(StaffPrincipal principal, Long taskId) {
    TaskRow task =
        repository
            .lock(principal.officeId(), taskId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireOwnTaskForDeputy(principal, task);
    if (TaskSourceType.valueOf(task.sourceType()).isAutomatic()) {
      throw new BusinessException(ErrorCode.AUTO_TASK_IMMUTABLE);
    }
    repository.delete(task.id());
  }

  private static boolean autoTransitionAllowed(TaskStatus current, TaskStatus next) {
    return (current == TaskStatus.TODO && next == TaskStatus.DONE)
        || (current == TaskStatus.DONE && next == TaskStatus.TODO);
  }

  private static void requireOwnTaskForDeputy(StaffPrincipal principal, TaskRow task) {
    if (principal.role() != StaffRole.DEPUTY) {
      return;
    }
    if (!task.assigneeMembershipId().equals(principal.membershipId())
        || TaskSourceType.valueOf(task.sourceType()).isContract()) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private void validateReferences(Long officeId, Long assignee, Long customer, Long property) {
    if (assignee != null && !repository.isApprovedMember(officeId, assignee)) {
      throw CustomerInput.invalid(
          "assigneeMembershipId", FieldErrorCode.INVALID_VALUE, "같은 사무소의 승인된 구성원이어야 합니다.");
    }
    if (customer != null && !repository.isActiveCustomer(officeId, customer)) {
      throw CustomerInput.invalid(
          "relatedCustomerId", FieldErrorCode.INVALID_VALUE, "같은 사무소의 고객이어야 합니다.");
    }
    if (property != null && !repository.isActiveProperty(officeId, property)) {
      throw CustomerInput.invalid(
          "relatedPropertyId", FieldErrorCode.INVALID_VALUE, "같은 사무소의 매물이어야 합니다.");
    }
  }

  private static Long changed(Long next, Long current) {
    return next == null || next.equals(current) ? null : next;
  }

  private static String title(JsonNode body) {
    return CustomerInput.requiredText(body, "title", TITLE_MAX, false);
  }

  private static String memo(JsonNode body) {
    return CustomerInput.optionalText(body, "memo", InputRules.WORK_MEMO_MAX, false);
  }

  private static LocalTime dueTime(JsonNode body) {
    if (CustomerInput.isNull(body, "dueTime")) {
      return null;
    }
    JsonNode node = body.get("dueTime");
    if (!node.isString()) {
      throw CustomerInput.invalid("dueTime", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    try {
      return LocalTime.parse(node.asString(), HH_MM);
    } catch (DateTimeParseException e) {
      throw CustomerInput.invalid("dueTime", FieldErrorCode.INVALID_FORMAT, "HH:mm 형식이어야 합니다.");
    }
  }

  static String formatTime(LocalTime time) {
    return time == null ? null : time.format(HH_MM);
  }

  private static TaskResponse response(TaskRow row) {
    return new TaskResponse(
        row.id(),
        row.officeId(),
        row.title(),
        row.assigneeMembershipId(),
        row.dueDate(),
        formatTime(row.dueTime()),
        row.relatedCustomerId(),
        row.relatedPropertyId(),
        row.memo(),
        row.status(),
        row.sourceType(),
        row.sourceId(),
        row.completedAt() == null ? null : TimeRules.toDisplayOffset(row.completedAt()),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  private static TaskListItem listItem(TaskListRow listRow) {
    TaskRow row = listRow.task();
    return new TaskListItem(
        row.id(),
        row.title(),
        row.assigneeMembershipId(),
        listRow.assigneeName(),
        row.dueDate(),
        formatTime(row.dueTime()),
        row.relatedCustomerId(),
        listRow.relatedCustomerName(),
        row.relatedPropertyId(),
        listRow.relatedPropertyName(),
        row.memo(),
        row.completedAt() == null ? null : TimeRules.toDisplayOffset(row.completedAt()),
        row.status(),
        row.sourceType(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }
}
