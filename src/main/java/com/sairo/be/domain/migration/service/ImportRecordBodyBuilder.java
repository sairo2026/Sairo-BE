package com.sairo.be.domain.migration.service;

import com.sairo.be.global.security.StaffPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Profile("!migrate")
@Component
public class ImportRecordBodyBuilder {

  public static final String DATA_SOURCE = "IMPORT";
  public static final String PROCESSING_BASIS = "LEGITIMATE_INTEREST";
  public static final String DATA_PURPOSE = "기존 업무 자료를 사이로로 이전";
  public static final int RETENTION_YEARS = 3;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  public ObjectNode customer(
      StaffPrincipal principal, Map<String, Object> values, LocalDate retentionUntil) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put(
        "isCorporation",
        Boolean.TRUE.equals(values.get(ImportTargetFields.CUSTOMER_IS_CORPORATION)));
    body.put("name", (String) values.get(ImportTargetFields.CUSTOMER_NAME));
    body.put("managingMembershipId", principal.membershipId());
    body.put("dataSource", DATA_SOURCE);
    body.put("processingBasis", PROCESSING_BASIS);
    body.put("dataPurpose", DATA_PURPOSE);
    body.put("retentionUntil", retentionUntil.toString());
    putText(body, "phone", values.get(ImportTargetFields.CUSTOMER_PHONE));
    putText(body, "secondaryPhone", values.get(ImportTargetFields.CUSTOMER_SECONDARY_PHONE));
    putText(body, "email", values.get(ImportTargetFields.CUSTOMER_EMAIL));
    putText(body, "memo", values.get(ImportTargetFields.CUSTOMER_MEMO));
    return body;
  }

  public ObjectNode property(StaffPrincipal principal, Map<String, Object> values) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("name", (String) values.get(ImportTargetFields.PROPERTY_NAME));
    body.put("managingMembershipId", principal.membershipId());
    putText(body, "address", values.get(ImportTargetFields.PROPERTY_ADDRESS));
    putText(body, "addressDetail", values.get(ImportTargetFields.PROPERTY_ADDRESS_DETAIL));
    putArea(body, "exclusiveAreaSqm", values.get(ImportTargetFields.EXCLUSIVE_AREA));
    putArea(body, "supplyAreaSqm", values.get(ImportTargetFields.SUPPLY_AREA));
    putArea(body, "landAreaSqm", values.get(ImportTargetFields.LAND_AREA));
    putArea(body, "grossFloorAreaSqm", values.get(ImportTargetFields.GROSS_FLOOR_AREA));
    Object moveInDate = values.get(ImportTargetFields.MOVE_IN_DATE);
    if (moveInDate != null) {
      body.put("moveInDate", moveInDate.toString());
    }
    Object negotiable = values.get(ImportTargetFields.MOVE_IN_NEGOTIABLE);
    if (negotiable instanceof Boolean flag) {
      body.put("moveInNegotiable", flag);
    }
    Object fee = values.get(ImportTargetFields.MAINTENANCE_FEE_AMOUNT);
    if (fee instanceof Number amount) {
      body.put("maintenanceFeeAmount", amount.longValue());
    }
    putText(body, "maintenanceFeeItems", values.get(ImportTargetFields.MAINTENANCE_FEE_ITEMS));
    putText(body, "occupancyStatus", values.get(ImportTargetFields.OCCUPANCY_STATUS));
    putText(body, "description", values.get(ImportTargetFields.PROPERTY_DESCRIPTION));
    putText(body, "memo", values.get(ImportTargetFields.PROPERTY_MEMO));
    return body;
  }

  private static void putText(ObjectNode body, String key, Object value) {
    if (value instanceof String text && !text.isBlank()) {
      body.put(key, text);
    }
  }

  private static void putArea(ObjectNode body, String key, Object value) {
    if (value instanceof BigDecimal area) {
      body.put(key, area);
    }
  }
}
