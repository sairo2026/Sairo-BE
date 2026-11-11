package com.sairo.be.domain.support.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.InputRules;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class EnterpriseLeadParser {

  public static final int COUNT_MAX = 100_000;

  private static final Set<String> KEYS =
      Set.of(
          "companyName",
          "contactName",
          "contactEmail",
          "contactPhone",
          "branchCount",
          "expectedSeats",
          "migrationNeeded",
          "content",
          "privacyNoticeAcknowledged");
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");
  private static final Pattern PHONE = Pattern.compile("^[0-9+()\\- ]{7,30}$");

  public record LeadInput(
      String companyName,
      String contactName,
      String contactEmail,
      String contactPhone,
      int branchCount,
      int expectedSeats,
      boolean migrationNeeded,
      String content) {}

  private EnterpriseLeadParser() {}

  public static LeadInput parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    String companyName = CustomerInput.requiredText(body, "companyName", 200, false);
    String contactName = CustomerInput.requiredText(body, "contactName", 50, false);
    String contactEmail = CustomerInput.requiredText(body, "contactEmail", 254, false);
    if (!EMAIL.matcher(contactEmail).matches()) {
      throw CustomerInput.invalid("contactEmail", FieldErrorCode.INVALID_FORMAT, "이메일 형식이 아닙니다.");
    }
    String contactPhone = CustomerInput.requiredText(body, "contactPhone", 30, false);
    if (!PHONE.matcher(contactPhone).matches()) {
      throw CustomerInput.invalid("contactPhone", FieldErrorCode.INVALID_FORMAT, "연락처 형식이 아닙니다.");
    }
    int branchCount = count(body, "branchCount");
    int expectedSeats = count(body, "expectedSeats");
    boolean migrationNeeded = CustomerInput.requiredBoolean(body, "migrationNeeded");
    String content = CustomerInput.requiredText(body, "content", InputRules.BODY_MEMO_MAX, false);
    Boolean acknowledged = CustomerInput.requiredBoolean(body, "privacyNoticeAcknowledged");
    if (!acknowledged) {
      throw CustomerInput.invalid(
          "privacyNoticeAcknowledged", FieldErrorCode.INVALID_VALUE, "개인정보 고지를 확인해야 접수할 수 있습니다.");
    }
    return new LeadInput(
        companyName,
        contactName,
        contactEmail,
        contactPhone,
        branchCount,
        expectedSeats,
        migrationNeeded,
        content);
  }

  public static void requireEmptyBody(JsonNode body) {
    if (body == null || body.isNull()) {
      return;
    }
    CustomerInput.requireObject(body, Set.of());
  }

  private static int count(JsonNode body, String key) {
    long value = CustomerInput.requiredLong(body, key);
    if (value < 1 || value > COUNT_MAX) {
      throw CustomerInput.invalid(
          key, FieldErrorCode.OUT_OF_RANGE, "1 이상 " + COUNT_MAX + " 이하여야 합니다.");
    }
    return (int) value;
  }
}
