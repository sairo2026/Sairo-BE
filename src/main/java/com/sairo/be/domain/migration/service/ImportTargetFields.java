package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.List;
import java.util.Optional;

public final class ImportTargetFields {

  public static final String CUSTOMER_SOURCE_ID = "customerSourceId";
  public static final String CUSTOMER_NAME = "customerName";
  public static final String CUSTOMER_IS_CORPORATION = "customerIsCorporation";
  public static final String CUSTOMER_PHONE = "customerPhone";
  public static final String CUSTOMER_SECONDARY_PHONE = "customerSecondaryPhone";
  public static final String CUSTOMER_EMAIL = "customerEmail";
  public static final String CUSTOMER_MEMO = "customerMemo";
  public static final String PROPERTY_SOURCE_ID = "propertySourceId";
  public static final String PROPERTY_NAME = "propertyName";
  public static final String PROPERTY_ADDRESS = "propertyAddress";
  public static final String PROPERTY_ADDRESS_DETAIL = "propertyAddressDetail";
  public static final String EXCLUSIVE_AREA = "exclusiveAreaSqm";
  public static final String SUPPLY_AREA = "supplyAreaSqm";
  public static final String LAND_AREA = "landAreaSqm";
  public static final String GROSS_FLOOR_AREA = "grossFloorAreaSqm";
  public static final String MOVE_IN_DATE = "moveInDate";
  public static final String MOVE_IN_NEGOTIABLE = "moveInNegotiable";
  public static final String MAINTENANCE_FEE_AMOUNT = "maintenanceFeeAmount";
  public static final String MAINTENANCE_FEE_ITEMS = "maintenanceFeeItems";
  public static final String OCCUPANCY_STATUS = "occupancyStatus";
  public static final String PROPERTY_DESCRIPTION = "propertyDescription";
  public static final String PROPERTY_MEMO = "propertyMemo";

  private static final List<ImportTargetField> CUSTOMER =
      List.of(
          new ImportTargetField(CUSTOMER_SOURCE_ID, "원본 고객 ID", "string", true),
          new ImportTargetField(CUSTOMER_NAME, "고객명", "string", true),
          new ImportTargetField(CUSTOMER_IS_CORPORATION, "법인 여부", "boolean", false),
          new ImportTargetField(CUSTOMER_PHONE, "연락처", "string", false),
          new ImportTargetField(CUSTOMER_SECONDARY_PHONE, "보조 연락처", "string", false),
          new ImportTargetField(CUSTOMER_EMAIL, "이메일", "string", false),
          new ImportTargetField(CUSTOMER_MEMO, "고객 메모", "string", false));

  private static final List<ImportTargetField> PROPERTY =
      List.of(
          new ImportTargetField(PROPERTY_SOURCE_ID, "원본 매물 ID", "string", true),
          new ImportTargetField(PROPERTY_NAME, "매물명", "string", true),
          new ImportTargetField(PROPERTY_ADDRESS, "기본주소", "string", false),
          new ImportTargetField(PROPERTY_ADDRESS_DETAIL, "상세주소", "string", false),
          new ImportTargetField(EXCLUSIVE_AREA, "전용면적(㎡)", "number", false),
          new ImportTargetField(SUPPLY_AREA, "공급면적(㎡)", "number", false),
          new ImportTargetField(LAND_AREA, "대지면적(㎡)", "number", false),
          new ImportTargetField(GROSS_FLOOR_AREA, "연면적(㎡)", "number", false),
          new ImportTargetField(MOVE_IN_DATE, "입주 가능일", "date", false),
          new ImportTargetField(MOVE_IN_NEGOTIABLE, "입주일 협의 가능 여부", "boolean", false),
          new ImportTargetField(MAINTENANCE_FEE_AMOUNT, "관리비 금액", "number", false),
          new ImportTargetField(MAINTENANCE_FEE_ITEMS, "관리비 포함 항목", "string", false),
          new ImportTargetField(OCCUPANCY_STATUS, "점유상태", "string", false),
          new ImportTargetField(PROPERTY_DESCRIPTION, "매물 설명", "string", false),
          new ImportTargetField(PROPERTY_MEMO, "매물 메모", "string", false));

  private ImportTargetFields() {}

  public static List<ImportTargetField> forType(ImportTargetType type) {
    return switch (type) {
      case CUSTOMER -> CUSTOMER;
      case PROPERTY -> PROPERTY;
      case CONTRACT -> List.of();
    };
  }

  public static Optional<ImportTargetField> find(ImportTargetType type, String field) {
    return forType(type).stream().filter(item -> item.field().equals(field)).findFirst();
  }

  public static String sourceIdField(ImportTargetType type) {
    return switch (type) {
      case CUSTOMER -> CUSTOMER_SOURCE_ID;
      case PROPERTY -> PROPERTY_SOURCE_ID;
      case CONTRACT -> null;
    };
  }

  public static String nameField(ImportTargetType type) {
    return switch (type) {
      case CUSTOMER -> CUSTOMER_NAME;
      case PROPERTY -> PROPERTY_NAME;
      case CONTRACT -> null;
    };
  }
}
