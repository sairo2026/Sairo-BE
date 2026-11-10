package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.VisitRequestDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestListResponse;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.mapper.VisitRequestMapper;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.repository.VisitRequestStatusHistoryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class VisitRequestQueryService {

  private final CoordinationAccess access;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusHistoryRepository historyRepository;
  private final VisitRequestReadRepository readRepository;

  public VisitRequestQueryService(
      CoordinationAccess access,
      VisitRequestRepository requestRepository,
      VisitRequestStatusHistoryRepository historyRepository,
      VisitRequestReadRepository readRepository) {
    this.access = access;
    this.requestRepository = requestRepository;
    this.historyRepository = historyRepository;
    this.readRepository = readRepository;
  }

  public VisitRequestListResponse list(
      StaffPrincipal principal, Long coordinationId, String statusFilter) {
    access.view(principal, coordinationId);
    List<VisitRequest> requests;
    if (statusFilter == null) {
      requests = requestRepository.findByCoordinationIdOrderBySubmittedAtDescIdDesc(coordinationId);
    } else {
      requests =
          requestRepository.findByCoordinationIdAndStatusOrderBySubmittedAtDescIdDesc(
              coordinationId, parseStatus(statusFilter));
    }
    return VisitRequestMapper.toList(requests);
  }

  public VisitRequestDetailResponse detail(StaffPrincipal principal, Long requestId) {
    VisitRequest request =
        requestRepository
            .findByIdAndApplicantOfficeId(requestId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    access.view(principal, request.getCoordinationId());
    return VisitRequestMapper.toDetail(
        request,
        historyRepository.findByRequestIdOrderByEffectiveAtAscIdAsc(requestId),
        readRepository.selectionsOf(requestId),
        readRepository.revalidationsOf(requestId));
  }

  private static RequestStatus parseStatus(String value) {
    try {
      return RequestStatus.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw CustomerInput.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }
}
