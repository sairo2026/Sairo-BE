package com.sairo.be.domain.support.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.support.entity.InquiryCategory;
import com.sairo.be.global.support.InputRules;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class SupportRequestParser {

  public static final int CONTENT_MAX = InputRules.BODY_MEMO_MAX;
  public static final int SCOPE_MAX = 2_000;

  private static final Set<String> CREATE_KEYS = Set.of("category", "content", "requestScope");
  private static final Set<String> REPLY_KEYS = Set.of("content");

  public record InquiryInput(InquiryCategory category, String content, String requestScope) {}

  private SupportRequestParser() {}

  public static InquiryInput inquiry(JsonNode body) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    InquiryCategory category = CustomerInput.requiredEnum(body, "category", InquiryCategory.class);
    String content = CustomerInput.requiredText(body, "content", CONTENT_MAX, false);
    String scope = CustomerInput.optionalText(body, "requestScope", SCOPE_MAX, false);
    return new InquiryInput(category, content, scope);
  }

  public static String reply(JsonNode body) {
    CustomerInput.requireObject(body, REPLY_KEYS);
    return CustomerInput.requiredText(body, "content", CONTENT_MAX, false);
  }
}
