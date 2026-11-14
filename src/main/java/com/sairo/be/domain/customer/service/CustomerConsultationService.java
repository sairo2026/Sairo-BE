package com.sairo.be.domain.customer.service;

import static com.sairo.be.domain.customer.service.CustomerInput.has;

import com.sairo.be.domain.customer.dto.response.CustomerConsultationListResponse;
import com.sairo.be.domain.customer.dto.response.CustomerConsultationResponse;
import com.sairo.be.domain.customer.dto.response.CustomerConsultationRevisionListResponse;
import com.sairo.be.domain.customer.entity.ConsultationType;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.entity.CustomerConsultation;
import com.sairo.be.domain.customer.entity.CustomerConsultationRevision;
import com.sairo.be.domain.customer.repository.CustomerConsultationRepository;
import com.sairo.be.domain.customer.repository.CustomerConsultationRevisionRepository;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CustomerConsultationService {

  private static final Set<String> CREATE_KEYS =
      Set.of("occurredAt", "content", "consultationType");
  private static final Set<String> UPDATE_KEYS =
      Set.of("occurredAt", "content", "consultationType", "correctionReason");
  private static final int CONTENT_MAX = 10_000;
  private static final int REASON_MAX = 500;

  private final CustomerConsultationRepository consultationRepository;
  private final CustomerConsultationRevisionRepository revisionRepository;
  private final CustomerReadRepository readRepository;
  private final CustomerGuard guard;

  public CustomerConsultationService(
      CustomerConsultationRepository consultationRepository,
      CustomerConsultationRevisionRepository revisionRepository,
      CustomerReadRepository readRepository,
      CustomerGuard guard) {
    this.consultationRepository = consultationRepository;
    this.revisionRepository = revisionRepository;
    this.readRepository = readRepository;
    this.guard = guard;
  }

  @Transactional(readOnly = true)
  public CustomerConsultationListResponse list(StaffPrincipal principal, Long customerId) {
    guard.find(principal.officeId(), customerId);
    if (readRepository.isMerged(customerId)) {
      return new CustomerConsultationListResponse(List.of());
    }
    return new CustomerConsultationListResponse(
        readRepository.findConsultations(readRepository.mergedSet(customerId)).stream()
            .map(
                row ->
                    new CustomerConsultationListResponse.Item(
                        row.consultationId(),
                        row.consultationType(),
                        TimeRules.toDisplayOffset(row.occurredAt()),
                        row.content(),
                        row.authorMembershipId(),
                        WithdrawnMemberDisplay.name(
                            row.authorWithdrawn(), row.authorName(), row.authorAliasNo()),
                        row.revisionCount(),
                        principal.officeId(),
                        row.version()))
            .toList());
  }

  @Transactional
  public CustomerConsultationResponse create(
      StaffPrincipal principal, Long customerId, JsonNode body) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    Instant occurredAt = CustomerInput.requiredDateTime(body, "occurredAt").toInstant();
    String content = CustomerInput.requiredText(body, "content", CONTENT_MAX, false);
    ConsultationType type =
        CustomerInput.requiredEnum(body, "consultationType", ConsultationType.class);
    Customer customer = guard.lockFinal(principal.officeId(), customerId);
    guard.requireNotArchived(customer);
    if (type == ConsultationType.FIRST_INQUIRY
        && !readRepository.firstInquiryIds(readRepository.mergedSet(customer.getId())).isEmpty()) {
      throw new BusinessException(ErrorCode.FIRST_INQUIRY_ALREADY_EXISTS);
    }
    CustomerConsultation saved =
        consultationRepository.saveAndFlush(
            CustomerConsultation.record(
                customer.getId(), occurredAt, principal.membershipId(), content, type));
    return response(saved, principal.officeId());
  }

  @Transactional
  public CustomerConsultationResponse update(
      StaffPrincipal principal, Long consultationId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, UPDATE_KEYS);
    String reason = CustomerInput.requiredText(body, "correctionReason", REASON_MAX, false);
    if (!has(body, "occurredAt") && !has(body, "content") && !has(body, "consultationType")) {
      throw CustomerInput.invalid("body", FieldErrorCode.REQUIRED, "정정할 항목을 하나 이상 보내야 합니다.");
    }
    Instant occurredAt =
        has(body, "occurredAt")
            ? CustomerInput.requiredDateTime(body, "occurredAt").toInstant()
            : null;
    String content =
        has(body, "content")
            ? CustomerInput.requiredText(body, "content", CONTENT_MAX, false)
            : null;
    ConsultationType type =
        has(body, "consultationType")
            ? CustomerInput.requiredEnum(body, "consultationType", ConsultationType.class)
            : null;
    Long ownerCustomerId =
        readRepository
            .findConsultationCustomerId(consultationId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Customer customer = guard.lockFinal(principal.officeId(), ownerCustomerId);
    guard.requireNotArchived(customer);
    CustomerConsultation consultation =
        consultationRepository
            .findById(consultationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (consultation.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(consultation.getVersion());
    }
    ConsultationType nextType = type != null ? type : consultation.getConsultationType();
    if (nextType == ConsultationType.FIRST_INQUIRY
        && consultation.getConsultationType() != ConsultationType.FIRST_INQUIRY
        && !readRepository.firstInquiryIds(readRepository.mergedSet(customer.getId())).isEmpty()) {
      throw new BusinessException(ErrorCode.FIRST_INQUIRY_ALREADY_EXISTS);
    }
    boolean unchanged =
        (occurredAt == null || occurredAt.equals(consultation.getOccurredAt()))
            && (content == null || content.equals(consultation.getContent()))
            && nextType == consultation.getConsultationType();
    if (unchanged) {
      return response(consultation, principal.officeId());
    }
    revisionRepository.save(
        CustomerConsultationRevision.preserve(consultation, reason, principal.membershipId()));
    consultation.correct(
        occurredAt != null ? occurredAt : consultation.getOccurredAt(),
        content != null ? content : consultation.getContent(),
        nextType);
    try {
      return response(consultationRepository.saveAndFlush(consultation), principal.officeId());
    } catch (OptimisticLockingFailureException e) {
      throw BusinessException.versionConflict(consultation.getVersion() + 1);
    }
  }

  @Transactional(readOnly = true)
  public CustomerConsultationRevisionListResponse revisions(
      StaffPrincipal principal, Long consultationId) {
    readRepository
        .findConsultationCustomerId(consultationId, principal.officeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new CustomerConsultationRevisionListResponse(
        readRepository.findRevisions(consultationId).stream()
            .map(
                row ->
                    new CustomerConsultationRevisionListResponse.Item(
                        TimeRules.toDisplayOffset(row.previousOccurredAt()),
                        row.previousContent(),
                        row.previousConsultationType(),
                        WithdrawnMemberDisplay.name(
                            row.correctorWithdrawn(), row.correctedBy(), row.correctorAliasNo()),
                        TimeRules.toDisplayOffset(row.correctedAt()),
                        row.correctionReason()))
            .toList());
  }

  private static CustomerConsultationResponse response(CustomerConsultation saved, Long officeId) {
    return new CustomerConsultationResponse(
        saved.getId(),
        saved.getCustomerId(),
        TimeRules.toDisplayOffset(saved.getOccurredAt()),
        saved.getAuthorMembershipId(),
        saved.getContent(),
        saved.getConsultationType().name(),
        TimeRules.toDisplayOffset(saved.getCreatedAt()),
        TimeRules.toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion(),
        officeId);
  }
}
