package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.DealType;
import com.sairo.be.domain.contract.entity.PartyInput;
import com.sairo.be.domain.contract.entity.PartySide;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.SensitiveDataDetector;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PhoneNumbers;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 계약 요청 본문을 읽고 체결 조건을 검사한다. 형식 오류는 INVALID_REQUEST, 체결에 필요한 값이 빠지면 INCOMPLETE_FOR_SIGNING이다. */
final class ContractInput {

  static final int TEXT_MAX = 2_000;
  static final int NAME_MAX = 50;
  static final int PHONE_MAX = 30;
  static final int PARTY_MAX = 20;

  static final Set<String> VALUE_KEYS =
      Set.of(
          "dealType",
          "managingMembershipId",
          "signedDate",
          "salePrice",
          "depositAmount",
          "monthlyRentAmount",
          "balanceDueDate",
          "balanceDueDatePendingReason",
          "leaseStartDate",
          "leaseEndDate",
          "memo",
          "sellerLandlordParties",
          "buyerTenantParties");

  static final Set<String> CORRECTION_KEYS =
      Set.of(
          "baseRevisionId",
          "reason",
          "effectiveAt",
          "signedDate",
          "dealType",
          "salePrice",
          "depositAmount",
          "monthlyRentAmount",
          "balanceDueDate",
          "balanceDueDatePendingReason",
          "leaseStartDate",
          "leaseEndDate",
          "sellerLandlordParties",
          "buyerTenantParties");

  private static final Set<String> PARTY_KEYS = Set.of("name", "phone", "customerId");
  private static final Set<String> CORRECTION_PARTY_KEYS =
      Set.of("partyId", "sequenceNo", "name", "phone", "customerId");

  /** 당사자 배열이 본문에 없으면 null이다. */
  record Parsed(ContractValues values, List<PartyInput> sellers, List<PartyInput> buyers) {}

  private ContractInput() {}

  /** 초안 생성·즉시 체결·재계약 본문이다. 거래유형과 담당자는 필수다. */
  static Parsed full(JsonNode body) {
    CustomerInput.requireObject(body, VALUE_KEYS);
    ContractValues values =
        new ContractValues(
            CustomerInput.requiredEnum(body, "dealType", DealType.class),
            CustomerInput.requiredLong(body, "managingMembershipId"),
            CustomerInput.optionalDate(body, "signedDate"),
            CustomerInput.optionalAmount(body, "salePrice"),
            CustomerInput.optionalAmount(body, "depositAmount"),
            CustomerInput.optionalAmount(body, "monthlyRentAmount"),
            CustomerInput.optionalDate(body, "balanceDueDate"),
            CustomerInput.optionalText(body, "balanceDueDatePendingReason", TEXT_MAX, true),
            CustomerInput.optionalDate(body, "leaseStartDate"),
            CustomerInput.optionalDate(body, "leaseEndDate"),
            CustomerInput.optionalText(body, "memo", TEXT_MAX, true));
    return new Parsed(
        values,
        parties(body, PartySide.SELLER_LANDLORD, false),
        parties(body, PartySide.BUYER_TENANT, false));
  }

  /** 초안 부분 수정이다. 보낸 키만 바꾼다. */
  static Parsed patch(JsonNode body, ContractValues base) {
    CustomerInput.requireObject(body, VALUE_KEYS);
    DealType dealType =
        body.has("dealType")
            ? CustomerInput.requiredEnum(body, "dealType", DealType.class)
            : base.dealType();
    Long manager =
        body.has("managingMembershipId")
            ? CustomerInput.requiredLong(body, "managingMembershipId")
            : base.managingMembershipId();
    ContractValues values =
        new ContractValues(
            dealType,
            manager,
            body.has("signedDate")
                ? CustomerInput.optionalDate(body, "signedDate")
                : base.signedDate(),
            body.has("salePrice")
                ? CustomerInput.optionalAmount(body, "salePrice")
                : base.salePrice(),
            body.has("depositAmount")
                ? CustomerInput.optionalAmount(body, "depositAmount")
                : base.depositAmount(),
            body.has("monthlyRentAmount")
                ? CustomerInput.optionalAmount(body, "monthlyRentAmount")
                : base.monthlyRentAmount(),
            body.has("balanceDueDate")
                ? CustomerInput.optionalDate(body, "balanceDueDate")
                : base.balanceDueDate(),
            body.has("balanceDueDatePendingReason")
                ? CustomerInput.optionalText(body, "balanceDueDatePendingReason", TEXT_MAX, true)
                : base.balanceDueDatePendingReason(),
            body.has("leaseStartDate")
                ? CustomerInput.optionalDate(body, "leaseStartDate")
                : base.leaseStartDate(),
            body.has("leaseEndDate")
                ? CustomerInput.optionalDate(body, "leaseEndDate")
                : base.leaseEndDate(),
            body.has("memo")
                ? CustomerInput.optionalText(body, "memo", TEXT_MAX, true)
                : base.memo());
    return new Parsed(
        values,
        parties(body, PartySide.SELLER_LANDLORD, false),
        parties(body, PartySide.BUYER_TENANT, false));
  }

  /** 정정 본문의 계약 값이다. 모든 키를 보내야 하며 담당자와 메모는 현재 값을 유지한다. */
  static Parsed correction(JsonNode body, ContractValues base) {
    CustomerInput.requireObject(body, CORRECTION_KEYS);
    for (String key : CORRECTION_KEYS) {
      if (!body.has(key)) {
        throw CustomerInput.invalid(key, FieldErrorCode.REQUIRED, "현재 전체값을 모두 보내야 합니다.");
      }
    }
    ContractValues values =
        new ContractValues(
            CustomerInput.requiredEnum(body, "dealType", DealType.class),
            base.managingMembershipId(),
            CustomerInput.requiredDate(body, "signedDate"),
            CustomerInput.optionalAmount(body, "salePrice"),
            CustomerInput.optionalAmount(body, "depositAmount"),
            CustomerInput.optionalAmount(body, "monthlyRentAmount"),
            CustomerInput.optionalDate(body, "balanceDueDate"),
            CustomerInput.optionalText(body, "balanceDueDatePendingReason", TEXT_MAX, true),
            CustomerInput.optionalDate(body, "leaseStartDate"),
            CustomerInput.optionalDate(body, "leaseEndDate"),
            base.memo());
    return new Parsed(
        values,
        parties(body, PartySide.SELLER_LANDLORD, true),
        parties(body, PartySide.BUYER_TENANT, true));
  }

  private static List<PartyInput> parties(JsonNode body, PartySide side, boolean correction) {
    String field = side.field();
    if (!body.has(field)) {
      return null;
    }
    JsonNode array = body.get(field);
    if (!array.isArray()) {
      throw CustomerInput.invalid(field, FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (array.size() > PARTY_MAX) {
      throw CustomerInput.invalid(field, FieldErrorCode.TOO_LONG, "한쪽 당사자는 20명까지입니다.");
    }
    Set<String> allowed = correction ? CORRECTION_PARTY_KEYS : PARTY_KEYS;
    List<PartyInput> result = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonNode item = array.get(i);
      String prefix = field + "[" + i + "].";
      if (item == null || !item.isObject()) {
        throw CustomerInput.invalid(
            field + "[" + i + "]", FieldErrorCode.TYPE_MISMATCH, "객체여야 합니다.");
      }
      for (String name : item.propertyNames()) {
        if (!allowed.contains(name)) {
          throw CustomerInput.invalid(prefix + name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
        }
      }
      Integer sequenceNo = null;
      if (correction) {
        if (CustomerInput.isNull(item, "sequenceNo")) {
          throw CustomerInput.invalid(prefix + "sequenceNo", FieldErrorCode.REQUIRED, "필수 입력입니다.");
        }
        JsonNode seq = item.get("sequenceNo");
        if (!seq.isIntegralNumber() || !seq.canConvertToInt() || seq.intValue() < 1) {
          throw CustomerInput.invalid(
              prefix + "sequenceNo", FieldErrorCode.OUT_OF_RANGE, "1 이상의 정수여야 합니다.");
        }
        sequenceNo = seq.intValue();
      }
      result.add(
          new PartyInput(
              correction ? longValue(item, prefix, "partyId") : null,
              sequenceNo,
              name(item, prefix),
              phone(item, prefix),
              longValue(item, prefix, "customerId")));
    }
    return result;
  }

  private static String name(JsonNode item, String prefix) {
    if (CustomerInput.isNull(item, "name")) {
      throw CustomerInput.invalid(prefix + "name", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return text(item, prefix, "name", NAME_MAX);
  }

  private static String phone(JsonNode item, String prefix) {
    if (CustomerInput.isNull(item, "phone")) {
      return null;
    }
    String value = text(item, prefix, "phone", PHONE_MAX);
    return PhoneNumbers.normalize(value).orElse(value);
  }

  private static String text(JsonNode item, String prefix, String key, int max) {
    JsonNode node = item.get(key);
    if (!node.isString()) {
      throw CustomerInput.invalid(prefix + key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String value = InputRules.requireText(prefix + key, node.asString(), 1, max);
    if (SensitiveDataDetector.detect(value)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.SENSITIVE_DATA_DETECTED,
          List.of(
              new FieldErrorItem(prefix + key, FieldErrorCode.INVALID_VALUE, "민감 번호가 감지됐습니다.")));
    }
    return value;
  }

  private static Long longValue(JsonNode item, String prefix, String key) {
    if (CustomerInput.isNull(item, key)) {
      return null;
    }
    JsonNode node = item.get(key);
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw CustomerInput.invalid(prefix + key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return node.longValue();
  }

  /** 임대 만기일이 시작일보다 이르면 400 INVALID_REQUEST다. */
  static void requireLeaseOrder(ContractValues values) {
    if (values.leaseStartDate() != null
        && values.leaseEndDate() != null
        && values.leaseEndDate().isBefore(values.leaseStartDate())) {
      throw CustomerInput.invalid(
          "leaseEndDate", FieldErrorCode.INVALID_VALUE, "임대 만기일은 시작일보다 이를 수 없습니다.");
    }
  }

  /**
   * 체결 조건이다. 거래유형에 맞지 않는 금액·기간과 미래 체결일은 INVALID_REQUEST, 거래유형별 금액·기간·체결일·잔금 예정일 또는 미정 사유·매물 주소·양측
   * 당사자가 빠지면 INCOMPLETE_FOR_SIGNING이다.
   */
  static void requireSignable(
      ContractValues v, String propertyAddress, int sellerCount, int buyerCount, LocalDate today) {
    List<FieldErrorItem> invalid = new ArrayList<>();
    DealType type = v.dealType();
    if (type == DealType.SALE) {
      notAllowed(invalid, "depositAmount", v.depositAmount());
      notAllowed(invalid, "monthlyRentAmount", v.monthlyRentAmount());
      notAllowed(invalid, "leaseStartDate", v.leaseStartDate());
      notAllowed(invalid, "leaseEndDate", v.leaseEndDate());
    } else {
      notAllowed(invalid, "salePrice", v.salePrice());
      if (type == DealType.JEONSE) {
        notAllowed(invalid, "monthlyRentAmount", v.monthlyRentAmount());
      }
    }
    if (v.signedDate() != null && v.signedDate().isAfter(today)) {
      invalid.add(
          new FieldErrorItem("signedDate", FieldErrorCode.OUT_OF_RANGE, "체결일은 오늘보다 늦을 수 없습니다."));
    }
    if (!invalid.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, invalid);
    }
    requireLeaseOrder(v);

    List<FieldErrorItem> missing = new ArrayList<>();
    if (propertyAddress == null || propertyAddress.isBlank()) {
      missing.add(required("propertyAddress", "매물 주소가 있어야 체결할 수 있습니다."));
    }
    if (sellerCount < 1) {
      missing.add(required("sellerLandlordParties", "매도인·임대인이 한 명 이상 있어야 합니다."));
    }
    if (buyerCount < 1) {
      missing.add(required("buyerTenantParties", "매수인·임차인이 한 명 이상 있어야 합니다."));
    }
    if (v.signedDate() == null) {
      missing.add(required("signedDate", "체결일이 필요합니다."));
    }
    if (type == DealType.SALE) {
      if (v.salePrice() == null) {
        missing.add(required("salePrice", "매매가가 필요합니다."));
      }
    } else {
      if (v.depositAmount() == null) {
        missing.add(required("depositAmount", "보증금이 필요합니다."));
      }
      if (type == DealType.MONTHLY && v.monthlyRentAmount() == null) {
        missing.add(required("monthlyRentAmount", "월세가 필요합니다."));
      }
      if (v.leaseStartDate() == null) {
        missing.add(required("leaseStartDate", "임대 시작일이 필요합니다."));
      }
      if (v.leaseEndDate() == null) {
        missing.add(required("leaseEndDate", "임대 만기일이 필요합니다."));
      }
    }
    if (v.balanceDueDate() == null
        && (v.balanceDueDatePendingReason() == null || v.balanceDueDatePendingReason().isBlank())) {
      missing.add(required("balanceDueDatePendingReason", "잔금 예정일이 없으면 미정 사유가 필요합니다."));
    }
    if (!missing.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INCOMPLETE_FOR_SIGNING, missing);
    }
  }

  private static void notAllowed(List<FieldErrorItem> errors, String field, Object value) {
    if (value != null) {
      errors.add(new FieldErrorItem(field, FieldErrorCode.NOT_ALLOWED, "이 거래유형에는 쓰지 않는 값입니다."));
    }
  }

  private static FieldErrorItem required(String field, String message) {
    return new FieldErrorItem(field, FieldErrorCode.REQUIRED, message);
  }
}
