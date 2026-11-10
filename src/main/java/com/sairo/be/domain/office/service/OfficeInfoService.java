package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.OfficeChangeRequestCreateRequest;
import com.sairo.be.domain.office.dto.response.OfficeChangeRequestItemResponse;
import com.sairo.be.domain.office.dto.response.OfficeChangeRequestResponse;
import com.sairo.be.domain.office.dto.response.OfficeDetailResponse;
import com.sairo.be.domain.office.entity.ChangeRequestStatus;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeChangeRequest;
import com.sairo.be.domain.office.entity.OfficeChangeType;
import com.sairo.be.domain.office.entity.OfficeEntityType;
import com.sairo.be.domain.office.mapper.OfficeMapper;
import com.sairo.be.domain.office.repository.OfficeChangeRequestRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import jakarta.servlet.http.HttpSession;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeInfoService {

  private final OfficeRepository officeRepository;
  private final OfficeChangeRequestRepository changeRequestRepository;
  private final ChangeValuesCodec codec;
  private final StepUpState stepUpState;

  public OfficeInfoService(
      OfficeRepository officeRepository,
      OfficeChangeRequestRepository changeRequestRepository,
      ChangeValuesCodec codec,
      StepUpState stepUpState) {
    this.officeRepository = officeRepository;
    this.changeRequestRepository = changeRequestRepository;
    this.codec = codec;
    this.stepUpState = stepUpState;
  }

  @Transactional(readOnly = true)
  public OfficeDetailResponse current(StaffPrincipal principal) {
    Office office = requireOffice(principal.officeId());
    return OfficeMapper.toDetail(office, principal.role() != StaffRole.DEPUTY);
  }

  @Transactional
  public OfficeDetailResponse updatePhone(
      StaffPrincipal principal, long expectedVersion, Map<String, Object> body) {
    if (!body.keySet().equals(Set.of("representativePhone"))
        || !(body.get("representativePhone") instanceof String rawPhone)) {
      throw OfficeInputRules.invalid(
          "representativePhone", FieldErrorCode.NOT_ALLOWED, "representativePhone만 보낼 수 있습니다.");
    }
    String phone = OfficeInputRules.phone("representativePhone", rawPhone);
    Office office = requireOffice(principal.officeId());
    if (office.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(office.getVersion());
    }
    office.changeRepresentativePhone(phone);
    try {
      officeRepository.saveAndFlush(office);
    } catch (OptimisticLockingFailureException e) {
      throw BusinessException.versionConflict(office.getVersion() + 1);
    }
    return OfficeMapper.toDetail(office, true);
  }

  @Transactional
  public OfficeChangeRequestResponse createChangeRequest(
      StaffPrincipal principal, HttpSession session, OfficeChangeRequestCreateRequest request) {
    Map<String, String> values = validateValues(request.changeType(), request.requestedValues());
    stepUpState.require(session);
    Office office = requireOffice(principal.officeId());
    OfficeChangeRequest saved =
        changeRequestRepository.saveAndFlush(
            OfficeChangeRequest.submit(
                office.getId(),
                principal.membershipId(),
                request.changeType(),
                codec.write(values)));
    stepUpState.consume(session);
    return OfficeMapper.toChangeResponse(saved, values, office);
  }

  @Transactional(readOnly = true)
  public PageResponse<OfficeChangeRequestItemResponse> listChangeRequests(
      StaffPrincipal principal, String statusParam, PageQuery pageQuery) {
    Set<ChangeRequestStatus> statuses =
        StatusFilter.parse(
            statusParam, ChangeRequestStatus.class, EnumSet.allOf(ChangeRequestStatus.class));
    return PageResponse.from(
        changeRequestRepository.findByOfficeIdAndStatusIn(
            principal.officeId(),
            statuses,
            pageQuery.toPageable(Sort.by(Sort.Direction.DESC, "createdAt", "id"))),
        item -> OfficeMapper.toChangeItem(item, codec.read(item.getRequestedValues())));
  }

  private Office requireOffice(Long officeId) {
    return officeRepository
        .findById(officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private Map<String, String> validateValues(OfficeChangeType type, Map<String, String> raw) {
    Map<String, String> values = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      if (!type.allowedKeys().contains(entry.getKey())) {
        throw OfficeInputRules.invalid(
            "requestedValues." + entry.getKey(), FieldErrorCode.NOT_ALLOWED, "변경 종류에 맞지 않는 항목입니다.");
      }
      values.put(entry.getKey(), validateValue(entry.getKey(), entry.getValue()));
    }
    if (type == OfficeChangeType.ADDRESS && !values.containsKey("address")) {
      throw OfficeInputRules.invalid(
          "requestedValues.address", FieldErrorCode.REQUIRED, "주소를 입력해야 합니다.");
    }
    return values;
  }

  private String validateValue(String key, String raw) {
    String field = "requestedValues." + key;
    return switch (key) {
      case "businessRegistrationNumber" -> OfficeInputRules.businessNumber(field, raw);
      case "representativePhone" -> OfficeInputRules.phone(field, raw);
      case "entityType" -> {
        try {
          yield OfficeEntityType.valueOf(raw == null ? "" : raw.strip()).name();
        } catch (IllegalArgumentException e) {
          throw OfficeInputRules.invalid(field, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 사업자 유형입니다.");
        }
      }
      case "address" -> InputRules.requireText(field, raw, 1, 300);
      case "registrationAuthority" -> InputRules.requireText(field, raw, 1, 100);
      case "realEstateLicenseNumber" -> InputRules.requireText(field, raw, 1, 30);
      default -> InputRules.requireText(field, raw, 1, 50);
    };
  }
}
