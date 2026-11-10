package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.office.entity.ChangeRequestStatus;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeChangeRequest;
import com.sairo.be.domain.office.entity.OfficeEntityType;
import com.sairo.be.domain.office.repository.OfficeChangeRequestRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.domain.office.service.ChangeValuesCodec;
import com.sairo.be.domain.ops.dto.request.OpsOfficeChangeRejectRequest;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeResultResponse;
import com.sairo.be.domain.ops.mapper.OpsReviewMapper;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorOfficeChangeService {

  private final OfficeChangeRequestRepository changeRequestRepository;
  private final OfficeRepository officeRepository;
  private final ChangeValuesCodec codec;
  private final OpsAuditLogRepository auditLogRepository;
  private final Clock clock;

  public OperatorOfficeChangeService(
      OfficeChangeRequestRepository changeRequestRepository,
      OfficeRepository officeRepository,
      ChangeValuesCodec codec,
      OpsAuditLogRepository auditLogRepository,
      Clock clock) {
    this.changeRequestRepository = changeRequestRepository;
    this.officeRepository = officeRepository;
    this.codec = codec;
    this.auditLogRepository = auditLogRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OpsOfficeChangeItemResponse> list(String statusParam, PageQuery pageQuery) {
    Set<ChangeRequestStatus> statuses =
        StatusFilter.parse(
            statusParam, ChangeRequestStatus.class, EnumSet.of(ChangeRequestStatus.PENDING_REVIEW));
    return PageResponse.from(
        changeRequestRepository.findByStatusIn(
            statuses, pageQuery.toPageable(Sort.by(Sort.Direction.ASC, "createdAt", "id"))),
        item -> OpsReviewMapper.toChangeItem(item, codec.read(item.getRequestedValues())));
  }

  @Transactional
  public OpsOfficeChangeResultResponse approve(long operatorId, Long requestId) {
    OfficeChangeRequest request = lock(requestId);
    Instant now = clock.instant();
    Office office =
        officeRepository
            .findById(request.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    request.approve(now);
    apply(office, request, codec.read(request.getRequestedValues()));
    try {
      officeRepository.saveAndFlush(office);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.OFFICE_ALREADY_EXISTS);
    }
    changeRequestRepository.saveAndFlush(request);
    auditLogRepository.insert(
        operatorId, "OFFICE_CHANGE_APPROVED", "OFFICE_CHANGE_REQUEST", requestId, null, now);
    return OpsReviewMapper.toChangeResult(request, office);
  }

  @Transactional
  public OpsOfficeChangeResultResponse reject(
      long operatorId, Long requestId, OpsOfficeChangeRejectRequest body) {
    OfficeChangeRequest request = lock(requestId);
    Instant now = clock.instant();
    request.reject(body.reason().strip(), now);
    changeRequestRepository.saveAndFlush(request);
    Office office =
        officeRepository
            .findById(request.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    auditLogRepository.insert(
        operatorId, "OFFICE_CHANGE_REJECTED", "OFFICE_CHANGE_REQUEST", requestId, null, now);
    return OpsReviewMapper.toChangeResult(request, office);
  }

  private void apply(Office office, OfficeChangeRequest request, Map<String, String> values) {
    switch (request.getChangeType()) {
      case ADDRESS -> office.changeAddress(values.get("address"));
      case REGISTRATION_INFO ->
          office.changeRegistrationInfo(
              values.containsKey("entityType")
                  ? OfficeEntityType.valueOf(values.get("entityType"))
                  : office.getEntityType(),
              values.getOrDefault("registrationAuthority", office.getRegistrationAuthority()),
              values.getOrDefault("realEstateLicenseNumber", office.getRealEstateLicenseNumber()),
              values.getOrDefault(
                  "businessRegistrationNumber", office.getBusinessRegistrationNumber()));
      case REPRESENTATIVE ->
          office.changeRepresentative(
              values.getOrDefault("representativeName", office.getRepresentativeName()),
              values.getOrDefault("representativePhone", office.getRepresentativePhone()));
    }
  }

  private OfficeChangeRequest lock(Long requestId) {
    return changeRequestRepository
        .findByIdForUpdate(requestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
