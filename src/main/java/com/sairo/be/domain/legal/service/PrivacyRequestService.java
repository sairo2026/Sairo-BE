package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestCreateResponse;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestListItem;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestResponse;
import com.sairo.be.domain.legal.entity.PrivacyRequest;
import com.sairo.be.domain.legal.entity.PrivacyRequestEvent;
import com.sairo.be.domain.legal.entity.PrivacyRequestStatus;
import com.sairo.be.domain.legal.entity.PrivacyRequestType;
import com.sairo.be.domain.legal.mapper.PrivacyRequestMapper;
import com.sairo.be.domain.legal.repository.PrivacyRequestEventRepository;
import com.sairo.be.domain.legal.repository.PrivacyRequestReadRepository;
import com.sairo.be.domain.legal.repository.PrivacyRequestReadRepository.Row;
import com.sairo.be.domain.legal.repository.PrivacyRequestRepository;
import com.sairo.be.domain.support.service.SupportInquiryService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PrivacyRequestService {

  private static final Set<String> CREATE_KEYS = Set.of("requestType", "content", "requestScope");
  private static final int SCOPE_MAX = 2_000;

  private final PrivacyRequestRepository requestRepository;
  private final PrivacyRequestEventRepository eventRepository;
  private final PrivacyRequestReadRepository readRepository;
  private final SupportInquiryService inquiryService;
  private final Clock clock;

  public PrivacyRequestService(
      PrivacyRequestRepository requestRepository,
      PrivacyRequestEventRepository eventRepository,
      PrivacyRequestReadRepository readRepository,
      SupportInquiryService inquiryService,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.eventRepository = eventRepository;
    this.readRepository = readRepository;
    this.inquiryService = inquiryService;
    this.clock = clock;
  }

  @Transactional
  public PrivacyRequestCreateResponse create(StaffPrincipal principal, JsonNode body) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    PrivacyRequestType type =
        CustomerInput.requiredEnum(body, "requestType", PrivacyRequestType.class);
    String content =
        CustomerInput.rejectIdentityNumber(
            "content",
            CustomerInput.requiredText(body, "content", InputRules.BODY_MEMO_MAX, false));
    String scope =
        CustomerInput.rejectIdentityNumber(
            "requestScope", CustomerInput.optionalText(body, "requestScope", SCOPE_MAX, false));
    Instant now = clock.instant();
    Instant legalDueAt = now.plus(java.time.Duration.ofDays(PrivacyRequest.LEGAL_DUE_DAYS));
    Long inquiryId =
        inquiryService.openForPrivacyRequest(
            principal.userId(), principal.officeId(), content, scope, legalDueAt);
    PrivacyRequest request =
        requestRepository.saveAndFlush(
            PrivacyRequest.receiveFromMember(principal.userId(), inquiryId, type, scope, now));
    eventRepository.save(PrivacyRequestEvent.received(request.getId(), now));
    return PrivacyRequestMapper.toCreate(request);
  }

  /** 운영자가 확인한 비회원 접수를 권리 요청으로 만든다(OPS-15). 만든 요청 ID를 돌려준다. */
  @Transactional
  public Long receiveFromGuestIntake(GuestConversion conversion, Long operatorId, Instant now) {
    PrivacyRequest request =
        requestRepository.saveAndFlush(
            PrivacyRequest.receiveFromGuest(
                conversion.officeId(),
                conversion.requestType(),
                conversion.requestScope(),
                conversion.legalBasis(),
                conversion.receivedAt(),
                conversion.legalDueAt(),
                conversion.internalDueAt()));
    eventRepository.save(
        PrivacyRequestEvent.receivedFromGuestIntake(
            request.getId(), conversion.intakeId(), operatorId, now));
    return request.getId();
  }

  public record GuestConversion(
      Long intakeId,
      Long officeId,
      PrivacyRequestType requestType,
      String requestScope,
      String legalBasis,
      Instant receivedAt,
      Instant legalDueAt,
      Instant internalDueAt) {}

  @Transactional(readOnly = true)
  public PageResponse<PrivacyRequestListItem> list(
      StaffPrincipal principal, String statusInput, Integer page, Integer size) {
    PageQuery query = PageQuery.of(page, size);
    Set<PrivacyRequestStatus> statuses =
        StatusFilter.parse(
            statusInput, PrivacyRequestStatus.class, EnumSet.allOf(PrivacyRequestStatus.class));
    List<String> names = statuses.stream().map(Enum::name).toList();
    List<Row> rows =
        readRepository.page(
            principal.userId(), names, query.size(), (long) query.page() * query.size());
    long total = readRepository.count(principal.userId(), names);
    return PageResponse.of(
        rows.stream().map(PrivacyRequestMapper::toListItem).toList(),
        query.page(),
        query.size(),
        total);
  }

  @Transactional(readOnly = true)
  public PrivacyRequestResponse detail(StaffPrincipal principal, Long requestId) {
    Row row =
        readRepository
            .find(principal.userId(), requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    String supplement =
        PrivacyRequestStatus.WAITING_FOR_INFO.name().equals(row.status())
            ? readRepository.latestSupplementRequest(requestId).orElse(null)
            : null;
    return PrivacyRequestMapper.toDetail(row, supplement);
  }
}
