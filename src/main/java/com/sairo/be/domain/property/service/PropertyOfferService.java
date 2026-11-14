package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.response.PropertyOfferListResponse;
import com.sairo.be.domain.property.dto.response.PropertyOfferResponse;
import com.sairo.be.domain.property.entity.DealType;
import com.sairo.be.domain.property.entity.OfferStatus;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.entity.PropertyOffer;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.domain.property.repository.PropertyOfferRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyOfferService {

  private static final String PRESALE_CODE = "PRESALE_RIGHT";
  private static final Set<String> PRICE_KEYS =
      Set.of("salePrice", "depositAmount", "monthlyRentAmount", "premiumAmount");
  private static final Set<OfferStatus> ACTIVE =
      EnumSet.of(OfferStatus.AVAILABLE, OfferStatus.NEGOTIATING);
  private static final long NO_EXCLUDE = -1L;

  private final PropertyRepository propertyRepository;
  private final PropertyOfferRepository offerRepository;
  private final PropertyCategoryRepository categoryRepository;
  private final PropertyGuard guard;

  public PropertyOfferService(
      PropertyRepository propertyRepository,
      PropertyOfferRepository offerRepository,
      PropertyCategoryRepository categoryRepository,
      PropertyGuard guard) {
    this.propertyRepository = propertyRepository;
    this.offerRepository = offerRepository;
    this.categoryRepository = categoryRepository;
    this.guard = guard;
  }

  @Transactional(readOnly = true)
  public PropertyOfferListResponse list(StaffPrincipal principal, Long propertyId) {
    Property property =
        propertyRepository
            .findByIdAndOfficeId(propertyId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new PropertyOfferListResponse(
        offerRepository.findByPropertyIdOrderById(propertyId).stream()
            .map(
                offer ->
                    new PropertyOfferListResponse.Item(
                        offer.getId(),
                        offer.getDealType().name(),
                        offer.getStatus().name(),
                        offer.getSalePrice(),
                        offer.getDepositAmount(),
                        offer.getMonthlyRentAmount(),
                        offer.getPremiumAmount(),
                        property.getOfficeId(),
                        property.getVersion()))
            .toList());
  }

  @Transactional
  public PropertyOfferResponse create(StaffPrincipal principal, Long propertyId, JsonNode body) {
    requireObject(body, Set.of("dealType"));
    DealType dealType = dealType(body);
    Long sale = price(body, "salePrice", true);
    Long deposit = price(body, "depositAmount", true);
    Long rent = price(body, "monthlyRentAmount", true);
    Long premium = price(body, "premiumAmount", false);
    validateShape(dealType, sale, deposit, rent);
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    requirePremiumAllowed(property, premium);
    if (offerRepository.existsByPropertyIdAndDealTypeAndStatusInAndIdNot(
        propertyId, dealType, ACTIVE, NO_EXCLUDE)) {
      throw new BusinessException(ErrorCode.OFFER_ALREADY_ACTIVE);
    }
    PropertyOffer offer =
        offerRepository.saveAndFlush(
            PropertyOffer.open(propertyId, dealType, sale, deposit, rent, premium));
    property.touch();
    return toResponse(offer, propertyRepository.saveAndFlush(property));
  }

  @Transactional
  public PropertyOfferResponse update(
      StaffPrincipal principal,
      Long propertyId,
      Long offerId,
      long expectedVersion,
      JsonNode body) {
    requireObject(body, Set.of("status"));
    OfferStatus status = body.has("status") ? status(body.get("status")) : null;
    boolean touchesPrice = PRICE_KEYS.stream().anyMatch(body::has);
    if (status == null && !touchesPrice) {
      throw invalid("body", FieldErrorCode.REQUIRED, "변경할 필드를 하나 이상 보내야 합니다.");
    }
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    guard.requireVersion(property, expectedVersion);
    PropertyOffer offer =
        offerRepository
            .findByIdAndPropertyId(offerId, propertyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    boolean terminal = !offer.getStatus().isActive();
    if (terminal && ((status != null && status != offer.getStatus()) || touchesPrice)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (touchesPrice) {
      Long sale = body.has("salePrice") ? price(body, "salePrice", true) : offer.getSalePrice();
      Long deposit =
          body.has("depositAmount") ? price(body, "depositAmount", true) : offer.getDepositAmount();
      Long rent =
          body.has("monthlyRentAmount")
              ? price(body, "monthlyRentAmount", true)
              : offer.getMonthlyRentAmount();
      Long premium =
          body.has("premiumAmount")
              ? price(body, "premiumAmount", false)
              : offer.getPremiumAmount();
      validateShape(offer.getDealType(), sale, deposit, rent);
      requirePremiumAllowed(property, premium);
      offer.changePrices(sale, deposit, rent, premium);
    }
    if (status != null && status != offer.getStatus()) {
      offer.changeStatus(status);
    }
    offerRepository.saveAndFlush(offer);
    property.touch();
    return toResponse(offer, propertyRepository.saveAndFlush(property));
  }

  private void requirePremiumAllowed(Property property, Long premium) {
    if (premium == null) {
      return;
    }
    boolean presale =
        categoryRepository
            .findById(property.getCategoryId())
            .map(PropertyCategory::getCode)
            .filter(PRESALE_CODE::equals)
            .isPresent();
    if (!presale) {
      throw invalid("premiumAmount", FieldErrorCode.NOT_ALLOWED, "분양권 카테고리 매물만 프리미엄을 받을 수 있습니다.");
    }
  }

  private static void validateShape(DealType dealType, Long sale, Long deposit, Long rent) {
    switch (dealType) {
      case SALE -> {
        required("salePrice", sale);
        forbidden("depositAmount", deposit);
        forbidden("monthlyRentAmount", rent);
      }
      case JEONSE -> {
        required("depositAmount", deposit);
        forbidden("salePrice", sale);
        forbidden("monthlyRentAmount", rent);
      }
      case MONTHLY -> {
        required("depositAmount", deposit);
        required("monthlyRentAmount", rent);
        forbidden("salePrice", sale);
      }
    }
  }

  private static void required(String field, Long value) {
    if (value == null) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
  }

  private static void forbidden(String field, Long value) {
    if (value != null) {
      throw invalid(field, FieldErrorCode.NOT_ALLOWED, "거래유형에 맞지 않는 가격입니다.");
    }
  }

  private static Long price(JsonNode body, String key, boolean nonNegative) {
    if (!body.has(key) || body.get(key).isNull()) {
      return null;
    }
    JsonNode node = body.get(key);
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    long value = node.longValue();
    if (nonNegative && value < 0) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return value;
  }

  private static DealType dealType(JsonNode body) {
    JsonNode node = body.get("dealType");
    if (node == null || node.isNull()) {
      throw invalid("dealType", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!node.isString()) {
      throw invalid("dealType", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    try {
      return DealType.valueOf(node.asString());
    } catch (IllegalArgumentException e) {
      throw invalid("dealType", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  private static OfferStatus status(JsonNode node) {
    if (node.isNull() || !node.isString()) {
      throw invalid("status", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    try {
      return OfferStatus.valueOf(node.asString());
    } catch (IllegalArgumentException e) {
      throw invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  private static void requireObject(JsonNode body, Set<String> extraKeys) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : body.propertyNames()) {
      if (!PRICE_KEYS.contains(name) && !extraKeys.contains(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
  }

  private static PropertyOfferResponse toResponse(PropertyOffer offer, Property property) {
    return new PropertyOfferResponse(
        offer.getId(),
        offer.getDealType().name(),
        offer.getStatus().name(),
        offer.getSalePrice(),
        offer.getDepositAmount(),
        offer.getMonthlyRentAmount(),
        offer.getPremiumAmount(),
        property.getOfficeId(),
        TimeRules.toDisplayOffset(offer.getUpdatedAt()),
        property.getVersion());
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
