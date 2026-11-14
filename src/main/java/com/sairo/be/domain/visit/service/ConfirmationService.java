package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.ConfirmationResponses;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.ConfirmationDeliveryRepository;
import com.sairo.be.domain.visit.repository.ConfirmationDeliveryRepository.DeliveryRow;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ConfirmationService {

  private static final Set<String> KEYS =
      Set.of(
          "scheduleVersion",
          "recipientType",
          "candidatePropertyId",
          "delivered",
          "channel",
          "deliveredAt");
  private static final Set<String> CHANNELS = Set.of("KAKAO", "SMS", "PHONE", "IN_PERSON", "EMAIL");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final CoordinationAccess access;
  private final ScheduleItemRepository itemRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitRequestRepository requestRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final PropertyReferenceService propertyReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final ConfirmationDeliveryRepository deliveryRepository;
  private final Clock clock;

  public ConfirmationService(
      CoordinationAccess access,
      ScheduleItemRepository itemRepository,
      VisitCandidatePropertyRepository candidateRepository,
      VisitRequestRepository requestRepository,
      PropertyAccessApprovalRepository approvalRepository,
      PropertyReferenceService propertyReferences,
      ManagingMemberDirectory memberDirectory,
      ConfirmationDeliveryRepository deliveryRepository,
      Clock clock) {
    this.access = access;
    this.itemRepository = itemRepository;
    this.candidateRepository = candidateRepository;
    this.requestRepository = requestRepository;
    this.approvalRepository = approvalRepository;
    this.propertyReferences = propertyReferences;
    this.memberDirectory = memberDirectory;
    this.deliveryRepository = deliveryRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ConfirmationResponses.Messages messages(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination = access.view(principal, coordinationId);
    requireConfirmed(coordination);
    List<ItemRow> items = keptItems(coordinationId);
    Map<Long, VisitCandidateProperty> candidates = candidatesById(coordinationId);
    Map<Long, PropertySummary> properties =
        propertyReferences.findAll(
            candidates.values().stream().map(VisitCandidateProperty::getPropertyId).toList());
    StringBuilder customer = new StringBuilder("방문 일정 안내");
    String managerName =
        memberDirectory
            .find(coordination.getManagerMembershipId())
            .map(
                member -> {
                  if ("DEPUTY".equals(member.role())) {
                    customer
                        .append("\n방문 안내 담당 ")
                        .append(member.displayName())
                        .append("는 중개보조원입니다");
                  }
                  return member.displayName();
                })
            .orElse("");
    int order = 1;
    List<ConfirmationResponses.ApproverMessage> approverMessages = new ArrayList<>();
    for (ItemRow item : items) {
      VisitCandidateProperty candidate = candidates.get(item.candidatePropertyId());
      PropertySummary property = properties.get(candidate.getPropertyId());
      String name = property == null ? "" : property.name();
      String address = property == null || property.address() == null ? "" : property.address();
      customer
          .append('\n')
          .append(order++)
          .append(". ")
          .append(slotText(item.startAt(), item.endAt()))
          .append(' ')
          .append(name)
          .append(address.isBlank() ? "" : " (" + address + ")");
      if (!candidate.isCrossOffice() && item.confirmedRequestId() != null) {
        approverMessages.add(approverMessage(candidate, item, name));
      }
    }
    if (!managerName.isBlank()) {
      customer.append("\n담당 ").append(managerName);
    }
    return new ConfirmationResponses.Messages(
        itemRepository.scheduleVersion(coordinationId), customer.toString(), approverMessages);
  }

  @Transactional
  public ConfirmationResponses.DeliveryCreated record(
      StaffPrincipal principal, Long coordinationId, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Long scheduleVersion = CustomerInput.requiredLong(body, "scheduleVersion");
    String recipientType = recipientType(body);
    Boolean delivered = CustomerInput.requiredBoolean(body, "delivered");
    Long candidatePropertyId = CustomerInput.optionalLong(body, "candidatePropertyId");
    String channel = CustomerInput.optionalText(body, "channel", 20, false);
    OffsetDateTime deliveredAt = CustomerInput.optionalDateTime(body, "deliveredAt");
    Instant now = clock.instant();
    validateDelivery(recipientType, delivered, candidatePropertyId, channel, deliveredAt, now);

    VisitCoordination coordination = access.view(principal, coordinationId);
    requireConfirmed(coordination);
    if (scheduleVersion != itemRepository.scheduleVersion(coordinationId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if ("APPROVER".equals(recipientType)) {
      VisitCandidateProperty candidate =
          candidateRepository
              .findByIdAndCoordinationId(candidatePropertyId, coordinationId)
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
      boolean confirmed =
          keptItems(coordinationId).stream()
              .anyMatch(item -> item.candidatePropertyId().equals(candidate.getId()));
      if (candidate.isCrossOffice() || !confirmed) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
    }
    DeliveryRow saved =
        deliveryRepository.insert(
            coordinationId,
            candidatePropertyId,
            scheduleVersion,
            recipientType,
            delivered,
            delivered ? channel : null,
            delivered ? deliveredAt.toInstant() : null,
            principal.membershipId(),
            now);
    return new ConfirmationResponses.DeliveryCreated(
        saved.id(),
        coordinationId,
        saved.scheduleVersion(),
        saved.recipientType(),
        saved.candidatePropertyId(),
        saved.delivered(),
        saved.channel(),
        TimeRules.toDisplayOffset(saved.deliveredAt()),
        TimeRules.toDisplayOffset(saved.recordedAt()),
        coordination.getOfficeId(),
        coordination.getVersion());
  }

  @Transactional(readOnly = true)
  public ConfirmationResponses.DeliveryList list(StaffPrincipal principal, Long coordinationId) {
    access.view(principal, coordinationId);
    return new ConfirmationResponses.DeliveryList(
        deliveryRepository.listByCoordination(coordinationId).stream()
            .map(
                row ->
                    new ConfirmationResponses.DeliveryItem(
                        row.id(),
                        row.scheduleVersion(),
                        row.recipientType(),
                        row.candidatePropertyId(),
                        row.delivered(),
                        row.channel(),
                        TimeRules.toDisplayOffset(row.deliveredAt()),
                        TimeRules.toDisplayOffset(row.recordedAt())))
            .toList());
  }

  private ConfirmationResponses.ApproverMessage approverMessage(
      VisitCandidateProperty candidate, ItemRow item, String propertyName) {
    Long approvalId =
        requestRepository
            .findById(item.confirmedRequestId())
            .map(request -> request.getApprovalId())
            .orElse(null);
    String approverName =
        approvalId == null
            ? ""
            : approvalRepository
                .findById(approvalId)
                .map(PropertyAccessApproval::getApproverName)
                .orElse("");
    return new ConfirmationResponses.ApproverMessage(
        candidate.getId(),
        approvalId,
        approverName == null ? "" : approverName,
        propertyName + " 방문 시각 안내: " + slotText(item.startAt(), item.endAt()));
  }

  private static String slotText(Instant start, Instant end) {
    return TimeRules.toDisplay(start).format(DATE)
        + " "
        + TimeRules.toDisplay(start).format(TIME)
        + "~"
        + TimeRules.toDisplay(end).format(TIME);
  }

  private List<ItemRow> keptItems(Long coordinationId) {
    return itemRepository.listByCoordination(coordinationId).stream()
        .filter(ItemRow::kept)
        .toList();
  }

  private Map<Long, VisitCandidateProperty> candidatesById(Long coordinationId) {
    Map<Long, VisitCandidateProperty> map = new java.util.HashMap<>();
    candidateRepository
        .findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId)
        .forEach(candidate -> map.put(candidate.getId(), candidate));
    return map;
  }

  private static void requireConfirmed(VisitCoordination coordination) {
    CoordinationStatus status = coordination.getStatus();
    if (status != CoordinationStatus.CONFIRMED
        && status != CoordinationStatus.VISIT_COMPLETED
        && status != CoordinationStatus.VISIT_INCOMPLETE) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private static String recipientType(JsonNode body) {
    JsonNode node = body.get("recipientType");
    String value = node == null || node.isNull() ? null : node.asString();
    if (!"CUSTOMER".equals(value) && !"APPROVER".equals(value)) {
      throw CustomerInput.invalid(
          "recipientType", FieldErrorCode.INVALID_VALUE, "CUSTOMER 또는 APPROVER여야 합니다.");
    }
    return value;
  }

  private static void validateDelivery(
      String recipientType,
      boolean delivered,
      Long candidatePropertyId,
      String channel,
      OffsetDateTime deliveredAt,
      Instant now) {
    if ("CUSTOMER".equals(recipientType) && candidatePropertyId != null) {
      throw CustomerInput.invalid(
          "candidatePropertyId", FieldErrorCode.NOT_ALLOWED, "고객 안내에는 후보 매물을 보낼 수 없습니다.");
    }
    if ("APPROVER".equals(recipientType) && candidatePropertyId == null) {
      throw CustomerInput.invalid(
          "candidatePropertyId", FieldErrorCode.REQUIRED, "승인자 안내에는 후보 매물이 필요합니다.");
    }
    if (delivered) {
      if (channel == null || !CHANNELS.contains(channel)) {
        throw CustomerInput.invalid("channel", FieldErrorCode.INVALID_VALUE, "전달 채널이 필요합니다.");
      }
      if (deliveredAt == null) {
        throw CustomerInput.invalid("deliveredAt", FieldErrorCode.REQUIRED, "전달 시각이 필요합니다.");
      }
      if (deliveredAt.toInstant().isAfter(now.plusSeconds(60))) {
        throw CustomerInput.invalid(
            "deliveredAt", FieldErrorCode.OUT_OF_RANGE, "전달 시각은 미래일 수 없습니다.");
      }
    } else if (channel != null || deliveredAt != null) {
      throw CustomerInput.invalid(
          "channel", FieldErrorCode.NOT_ALLOWED, "미전달이면 채널과 전달 시각을 비워야 합니다.");
    }
  }
}
