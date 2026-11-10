package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

  private static final Map<String, Set<String>> EXPECTED_RESPONSES =
      Map.ofEntries(
          Map.entry("GET /api/auth/kakao/start", Set.of("200")),
          Map.entry("GET /api/auth/kakao/callback", Set.of("200")),
          Map.entry("GET /api/auth/context", Set.of("200")),
          Map.entry("POST /api/auth/login", Set.of("200")),
          Map.entry("POST /api/auth/logout", Set.of("204")),
          Map.entry("POST /api/auth/signup/email/verification", Set.of("202")),
          Map.entry("POST /api/auth/signup/email/verify", Set.of("200")),
          Map.entry("POST /api/auth/phone-verification", Set.of("202")),
          Map.entry("POST /api/auth/phone-verification/verify", Set.of("200")),
          Map.entry("POST /api/auth/signup/email", Set.of("201")),
          Map.entry("GET /api/auth/recovery-options", Set.of("200")),
          Map.entry("GET /api/terms", Set.of("200")),
          Map.entry("POST /api/me/terms-agreements", Set.of("204")),
          Map.entry("GET /api/me/terms-reconsent", Set.of("200")),
          Map.entry("POST /api/public/marketing-unsubscribe", Set.of("200")),
          Map.entry("GET /api/me", Set.of("200")),
          Map.entry("GET /api/me/session", Set.of("200")),
          Map.entry("GET /api/me/login-history", Set.of("200")),
          Map.entry("POST /api/auth/signup/kakao", Set.of("201")),
          Map.entry("POST /api/auth/password/reset-request", Set.of("200")),
          Map.entry("POST /api/auth/password/reset", Set.of("204")),
          Map.entry("POST /api/auth/step-up/password", Set.of("200")),
          Map.entry("GET /api/auth/step-up/kakao/start", Set.of("200")),
          Map.entry("GET /api/auth/step-up/kakao/callback", Set.of("200")),
          Map.entry("POST /api/me/login-methods/email/verification", Set.of("202")),
          Map.entry("POST /api/me/login-methods/email/verify", Set.of("200")),
          Map.entry("POST /api/me/login-methods/email", Set.of("200")),
          Map.entry("GET /api/me/login-methods/kakao/start", Set.of("200")),
          Map.entry("POST /api/me/login-methods/kakao", Set.of("200")),
          Map.entry("DELETE /api/me/login-methods/{method}", Set.of("204")),
          Map.entry("POST /api/webhooks/kakao/unlink", Set.of("200")),
          Map.entry("PATCH /api/me", Set.of("200")),
          Map.entry("PATCH /api/me/notification-preferences", Set.of("200")),
          Map.entry("POST /api/me/contact/email/verification", Set.of("202")),
          Map.entry("POST /api/me/contact/email/verify", Set.of("200")),
          Map.entry("PATCH /api/me/contact", Set.of("200")),
          Map.entry("GET /api/me/terms-agreements", Set.of("200")),
          Map.entry("GET /api/me/withdrawal-preview", Set.of("200")),
          Map.entry("DELETE /api/me", Set.of("204")),
          Map.entry("GET /api/offices/lookup", Set.of("200")),
          Map.entry("POST /api/office-registrations", Set.of("201")),
          Map.entry("POST /api/office-registrations/{registrationId}/evidence", Set.of("200")),
          Map.entry("GET /api/office-registrations/me", Set.of("200")),
          Map.entry("POST /api/office-registrations/{registrationId}/cancel", Set.of("200")),
          Map.entry(
              "POST /api/office-registrations/{registrationId}/reapplication-start", Set.of("200")),
          Map.entry("GET /api/offices/current", Set.of("200")),
          Map.entry("PATCH /api/offices/current", Set.of("200")),
          Map.entry("POST /api/office-change-requests", Set.of("201")),
          Map.entry("GET /api/office-change-requests", Set.of("200")),
          Map.entry("GET /api/ops/auth/kakao/start", Set.of("200")),
          Map.entry("GET /api/ops/auth/kakao/callback", Set.of("200")),
          Map.entry("POST /api/ops/auth/mfa/verify", Set.of("200")),
          Map.entry("POST /api/ops/auth/step-up", Set.of("200")),
          Map.entry("POST /api/ops/auth/logout", Set.of("204")),
          Map.entry("GET /api/ops/me", Set.of("200")),
          Map.entry("POST /api/ops/auth/invitations/preview", Set.of("200")),
          Map.entry("POST /api/ops/auth/invitations/kakao-start", Set.of("200")),
          Map.entry("GET /api/ops/auth/invitations/kakao/callback", Set.of("200")),
          Map.entry("POST /api/ops/auth/invitations/mfa-enrollment", Set.of("200")),
          Map.entry("POST /api/ops/auth/invitations/accept", Set.of("201")),
          Map.entry("GET /api/ops/office-registrations", Set.of("200")),
          Map.entry("GET /api/ops/office-registrations/{registrationId}", Set.of("200")),
          Map.entry("PUT /api/ops/office-registrations/{registrationId}/checklist", Set.of("200")),
          Map.entry("POST /api/ops/office-registrations/{registrationId}/approve", Set.of("200")),
          Map.entry(
              "POST /api/ops/office-registrations/{registrationId}/request-supplement",
              Set.of("200")),
          Map.entry(
              "POST /api/ops/office-registrations/{registrationId}/evidence/download-url",
              Set.of("200")),
          Map.entry("POST /api/ops/office-registrations/{registrationId}/reject", Set.of("200")),
          Map.entry("POST /api/ops/offices/{officeId}/revoke", Set.of("200")),
          Map.entry("GET /api/ops/office-change-requests", Set.of("200")),
          Map.entry("POST /api/ops/office-change-requests/{requestId}/approve", Set.of("200")),
          Map.entry("POST /api/ops/office-change-requests/{requestId}/reject", Set.of("200")),
          Map.entry("GET /api/public/pricing", Set.of("200")),
          Map.entry("GET /api/billing/plans", Set.of("200")),
          Map.entry("GET /api/subscriptions/current", Set.of("200")),
          Map.entry("POST /api/subscriptions/billing-key/session", Set.of("200")),
          Map.entry("POST /api/subscriptions/billing-key", Set.of("200")),
          Map.entry("DELETE /api/subscriptions/billing-key", Set.of("204")),
          Map.entry("GET /api/subscriptions/purchase-consent/preview", Set.of("200")),
          Map.entry("POST /api/subscriptions/purchase-consent", Set.of("200")),
          Map.entry("GET /api/billing/invoices", Set.of("200")),
          Map.entry("GET /api/billing/invoices/{invoiceId}", Set.of("200")),
          Map.entry("POST /api/refund-requests", Set.of("200", "201")),
          Map.entry("GET /api/refund-requests", Set.of("200")),
          Map.entry("GET /api/refund-requests/{refundId}", Set.of("200")),
          Map.entry("GET /api/subscriptions/start-customer-agreement", Set.of("200")),
          Map.entry("POST /api/subscriptions/start-customer-agreement/acknowledge", Set.of("200")),
          Map.entry("GET /api/subscriptions/price-change-notices", Set.of("200")),
          Map.entry(
              "POST /api/subscriptions/price-change-notices/{noticeId}/decline", Set.of("200")),
          Map.entry("GET /api/billing/tax-recipient", Set.of("200")),
          Map.entry("PUT /api/billing/tax-recipient", Set.of("200")),
          Map.entry("POST /api/subscriptions/first-payment", Set.of("200")),
          Map.entry("POST /api/subscriptions/retry-payment", Set.of("200")),
          Map.entry("POST /api/subscriptions/cancel", Set.of("200")),
          Map.entry("POST /api/subscriptions/cancel/withdraw", Set.of("200")));

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  void 인증_없이_api_docs에_접근할_수_있고_구현된_api가_전부_문서화돼_있다() throws Exception {
    String body = apiDocs();
    JsonNode root = objectMapper.readTree(body);

    assertThat(operationKeys(root))
        .containsExactlyInAnyOrderElementsOf(EXPECTED_RESPONSES.keySet());

    JsonNode servers = root.get("servers");
    assertThat(servers).hasSize(1);
    assertThat(servers.get(0).get("url").asString()).isEqualTo("https://api.sairo.agency");
    assertThat(body).doesNotContain("localhost");
    assertThat(
            root.get("components").get("securitySchemes").has(OpenApiConfig.SESSION_COOKIE_SCHEME))
        .isTrue();
  }

  @Test
  void 인증_없이_swagger_ui_페이지에_접근할_수_있다() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void 각_api의_문서화된_응답코드가_구현이_실제로_돌려주는_응답코드와_정확히_일치한다() throws Exception {
    JsonNode paths = objectMapper.readTree(apiDocs()).get("paths");

    int checkedOperations = 0;
    for (String path : paths.propertyNames()) {
      JsonNode pathItem = paths.get(path);
      for (String method : pathItem.propertyNames()) {
        if (!HTTP_METHODS.contains(method)) {
          continue;
        }
        String operationKey = method.toUpperCase() + " " + path;
        Set<String> expected = EXPECTED_RESPONSES.get(operationKey);
        assertThat(expected).as("정의되지 않은 오퍼레이션: " + operationKey).isNotNull();

        Set<String> actual = new HashSet<>();
        pathItem.get(method).get("responses").propertyNames().forEach(actual::add);
        assertThat(actual).as(operationKey).isEqualTo(expected);
        checkedOperations++;
      }
    }
    assertThat(checkedOperations).isEqualTo(EXPECTED_RESPONSES.size());
  }

  @Test
  void 공통_오류_응답_스키마가_문서에_정의돼_있다() throws Exception {
    JsonNode schemas = objectMapper.readTree(apiDocs()).get("components").get("schemas");

    JsonNode errorResponse = schemas.get("ErrorResponse");
    assertThat(errorResponse).isNotNull();
    Set<String> properties = new HashSet<>();
    errorResponse.get("properties").propertyNames().forEach(properties::add);
    assertThat(properties).containsExactlyInAnyOrder("code", "message", "traceId", "fieldErrors");

    JsonNode fieldErrorItem = schemas.get("FieldErrorItem");
    assertThat(fieldErrorItem).isNotNull();
    Set<String> fieldProperties = new HashSet<>();
    fieldErrorItem.get("properties").propertyNames().forEach(fieldProperties::add);
    assertThat(fieldProperties).containsExactlyInAnyOrder("field", "code", "message");
  }

  private String apiDocs() throws Exception {
    return mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private Set<String> operationKeys(JsonNode root) {
    Set<String> keys = new HashSet<>();
    JsonNode paths = root.get("paths");
    for (String path : paths.propertyNames()) {
      for (String method : paths.get(path).propertyNames()) {
        if (HTTP_METHODS.contains(method)) {
          keys.add(method.toUpperCase() + " " + path);
        }
      }
    }
    return keys;
  }
}
