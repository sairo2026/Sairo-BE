package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.request.PropertyListRequest;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse;
import com.sairo.be.domain.property.dto.response.PropertyListItemResponse;
import com.sairo.be.domain.property.entity.AreaType;
import com.sairo.be.domain.property.entity.DealType;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.RegistrationStage;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.PropertyReadRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class PropertyQueryService {

  private final PropertyRepository propertyRepository;
  private final PropertyReadRepository readRepository;
  private final PropertyViewAssembler assembler;
  private final Clock clock;

  public PropertyQueryService(
      PropertyRepository propertyRepository,
      PropertyReadRepository readRepository,
      PropertyViewAssembler assembler,
      Clock clock) {
    this.propertyRepository = propertyRepository;
    this.readRepository = readRepository;
    this.assembler = assembler;
    this.clock = clock;
  }

  public PageResponse<PropertyListItemResponse> list(
      StaffPrincipal principal, PropertyListRequest request, PageQuery pageQuery) {
    PropertyReadRepository.ListFilter filter = toFilter(request);
    long total = readRepository.countList(principal.officeId(), filter);
    List<PropertyReadRepository.ListRow> rows =
        readRepository.findListRows(
            principal.officeId(),
            filter,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size());
    List<Long> ids = rows.stream().map(PropertyReadRepository.ListRow::propertyId).toList();
    LocalDate today = TimeRules.displayDate(clock.instant());
    Map<Long, List<String>> owners = readRepository.findCurrentPartyNames(ids, "OWNER", today);
    Map<Long, List<String>> occupants =
        readRepository.findCurrentPartyNames(ids, "OCCUPANT", today);
    Map<Long, List<PropertyReadRepository.OfferRow>> offers = readRepository.findActiveOffers(ids);
    Map<Long, LocalDate> leaseEnds = readRepository.findContractLeaseEnds(ids);
    List<PropertyListItemResponse> items =
        rows.stream()
            .map(
                row ->
                    PropertyMapper.toListItem(
                        row,
                        owners.getOrDefault(row.propertyId(), List.of()),
                        occupants.getOrDefault(row.propertyId(), List.of()),
                        offers.getOrDefault(row.propertyId(), List.of()),
                        leaseEnds.get(row.propertyId())))
            .toList();
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  public PropertyDetailResponse detail(StaffPrincipal principal, Long propertyId) {
    Property property =
        propertyRepository
            .findByIdAndOfficeId(propertyId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return PropertyMapper.toDetail(assembler.assemble(property, true));
  }

  public PropertyDuplicateCheckResponse duplicateCheck(
      StaffPrincipal principal, String address, String addressDetail) {
    String normalizedAddress = InputRules.requireText("address", address, 1, 300);
    String normalizedDetail = InputRules.optionalText("addressDetail", addressDetail, 100);
    return new PropertyDuplicateCheckResponse(
        readRepository
            .findDuplicates(principal.officeId(), normalizedAddress, normalizedDetail, null, false)
            .stream()
            .map(
                row ->
                    new PropertyDuplicateCheckResponse.Match(
                        row.propertyId(),
                        row.address(),
                        row.addressDetail(),
                        row.categoryName(),
                        row.registrationStage()))
            .toList());
  }

  private PropertyReadRepository.ListFilter toFilter(PropertyListRequest request) {
    String keyword = request.keyword() == null ? null : request.keyword().strip();
    if (keyword != null && keyword.isEmpty()) {
      keyword = null;
    }
    if (keyword != null && InputRules.length(keyword) > InputRules.NAME_MAX) {
      throw invalid("keyword", FieldErrorCode.TOO_LONG, "검색어가 너무 깁니다.");
    }
    DealType dealType = enumValue("dealType", request.dealType(), DealType.class);
    AreaType areaType = enumValue("areaType", request.areaType(), AreaType.class);
    RegistrationStage stage =
        enumValue("registrationStage", request.registrationStage(), RegistrationStage.class);
    if ((request.priceMin() != null || request.priceMax() != null) && dealType == null) {
      throw invalid("dealType", FieldErrorCode.REQUIRED, "가격 범위에는 거래유형이 필요합니다.");
    }
    if ((request.areaMin() != null || request.areaMax() != null) && areaType == null) {
      throw invalid("areaType", FieldErrorCode.REQUIRED, "면적 범위에는 면적 종류가 필요합니다.");
    }
    if (request.priceMin() != null && request.priceMin() < 0
        || request.priceMax() != null && request.priceMax() < 0) {
      throw invalid("priceMin", FieldErrorCode.OUT_OF_RANGE, "가격은 0 이상이어야 합니다.");
    }
    if (request.areaMin() != null && request.areaMin().signum() < 0
        || request.areaMax() != null && request.areaMax().signum() < 0) {
      throw invalid("areaMin", FieldErrorCode.OUT_OF_RANGE, "면적은 0 이상이어야 합니다.");
    }
    if (request.priceMin() != null
        && request.priceMax() != null
        && request.priceMin() > request.priceMax()) {
      throw new BusinessException(ErrorCode.INVALID_RANGE);
    }
    if (request.areaMin() != null
        && request.areaMax() != null
        && request.areaMin().compareTo(request.areaMax()) > 0) {
      throw new BusinessException(ErrorCode.INVALID_RANGE);
    }
    return new PropertyReadRepository.ListFilter(
        keyword,
        request.categoryId(),
        dealType,
        request.priceMin(),
        request.priceMax(),
        areaType,
        request.areaMin(),
        request.areaMax(),
        request.managingMembershipId(),
        stage,
        Boolean.TRUE.equals(request.archived()));
  }

  private static <E extends Enum<E>> E enumValue(String field, String raw, Class<E> type) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.strip());
    } catch (IllegalArgumentException e) {
      throw invalid(field, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
