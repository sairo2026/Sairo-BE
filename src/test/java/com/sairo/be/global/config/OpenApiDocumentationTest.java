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
          Map.entry("GET /api/offices/search", Set.of("200")),
          Map.entry("POST /api/offices/{officeId}/memberships", Set.of("201")),
          Map.entry("GET /api/memberships/me", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/cancel", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/reapplication-start", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/accept-invitation", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/approve", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/reject", Set.of("200")),
          Map.entry("POST /api/offices/current/memberships/invite", Set.of("201")),
          Map.entry("POST /api/memberships/{membershipId}/invitation/cancel", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/invitation/resend", Set.of("200")),
          Map.entry("GET /api/memberships/{membershipId}", Set.of("200")),
          Map.entry("PATCH /api/memberships/{membershipId}/role", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/suspend", Set.of("200")),
          Map.entry("POST /api/memberships/{membershipId}/reinstate", Set.of("200")),
          Map.entry("DELETE /api/memberships/{membershipId}", Set.of("204")),
          Map.entry("POST /api/memberships/me/leave", Set.of("204")),
          Map.entry("GET /api/public/pricing", Set.of("200")),
          Map.entry("GET /api/public/company-info", Set.of("200")),
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
          Map.entry("POST /api/subscriptions/cancel/withdraw", Set.of("200")),
          Map.entry("GET /api/properties/{propertyId}/offers", Set.of("200")),
          Map.entry("POST /api/properties/{propertyId}/offers", Set.of("201")),
          Map.entry("PATCH /api/properties/{propertyId}/offers/{offerId}", Set.of("200")),
          Map.entry("PATCH /api/properties/{propertyId}/category", Set.of("200")),
          Map.entry("DELETE /api/properties/{propertyId}", Set.of("204")),
          Map.entry("POST /api/properties/{propertyId}/restore", Set.of("200")),
          Map.entry("POST /api/customers/merge", Set.of("200")),
          Map.entry("GET /api/customers/{customerId}/consultations", Set.of("200")),
          Map.entry("POST /api/customers/{customerId}/consultations", Set.of("201")),
          Map.entry("PATCH /api/customer-consultations/{consultationId}", Set.of("200")),
          Map.entry("GET /api/customer-consultations/{consultationId}/revisions", Set.of("200")),
          Map.entry("GET /api/customers", Set.of("200")),
          Map.entry("POST /api/customers", Set.of("201")),
          Map.entry("GET /api/customers/duplicate-candidates", Set.of("200")),
          Map.entry("GET /api/customers/{customerId}", Set.of("200")),
          Map.entry("PATCH /api/customers/{customerId}", Set.of("200")),
          Map.entry("DELETE /api/customers/{customerId}", Set.of("204")),
          Map.entry("POST /api/customers/{customerId}/restore", Set.of("200")),
          Map.entry("GET /api/customers/{customerId}/assistant-view", Set.of("200")),
          Map.entry("POST /api/visit-coordinations", Set.of("201")),
          Map.entry("GET /api/visit-coordinations", Set.of("200")),
          Map.entry("GET /api/visit-coordinations/{coordinationId}", Set.of("200")),
          Map.entry(
              "POST /api/visit-coordinations/{coordinationId}/candidate-properties", Set.of("201")),
          Map.entry(
              "DELETE /api/visit-coordinations/{coordinationId}/candidate-properties/{candidateId}",
              Set.of("204")),
          Map.entry("POST /api/visit-coordinations/{coordinationId}/customer-link", Set.of("201")),
          Map.entry(
              "POST /api/visit-coordinations/{coordinationId}/customer-link/reissue",
              Set.of("200")),
          Map.entry("GET /api/public/customer-visit-links/{token}", Set.of("200")),
          Map.entry("POST /api/public/customer-visit-links/{token}/available-times", Set.of("200")),
          Map.entry("POST /api/properties/{propertyId}/access-approvals", Set.of("201")),
          Map.entry("GET /api/properties/{propertyId}/access-approvals", Set.of("200")),
          Map.entry(
              "POST /api/properties/{propertyId}/access-approvals/{approvalId}/approver-property-link",
              Set.of("201")),
          Map.entry(
              "GET /api/properties/{propertyId}/access-approvals/{approvalId}/approver-property-link",
              Set.of("200")),
          Map.entry(
              "DELETE /api/properties/{propertyId}/access-approvals/{approvalId}/approver-property-link",
              Set.of("204")),
          Map.entry(
              "GET /api/properties/{propertyId}/access-approvals/{approvalId}/approver-conditions",
              Set.of("200")),
          Map.entry(
              "PUT /api/properties/{propertyId}/access-approvals/{approvalId}/approver-conditions/proxy",
              Set.of("200")),
          Map.entry("GET /api/public/approver-property-links/{token}/conditions", Set.of("200")),
          Map.entry("PUT /api/public/approver-property-links/{token}/conditions", Set.of("200")),
          Map.entry(
              "POST /api/public/approver-property-links/{token}/conditions/impact-preview",
              Set.of("200")),
          Map.entry("POST /api/visit-coordinations/{coordinationId}/visit-requests", Set.of("201")),
          Map.entry("GET /api/visit-coordinations/{coordinationId}/visit-requests", Set.of("200")),
          Map.entry("GET /api/visit-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/visit-requests/{requestId}/withdraw", Set.of("200")),
          Map.entry("GET /api/visit-request-notices", Set.of("200")),
          Map.entry("POST /api/visit-request-notices/{noticeId}/read", Set.of("204")),
          Map.entry("POST /api/properties/{propertyId}/owner-occupants", Set.of("201")),
          Map.entry("PATCH /api/owner-occupants/{linkId}", Set.of("200")),
          Map.entry("POST /api/customers/{customerId}/property-interests", Set.of("201")),
          Map.entry("PATCH /api/customer-property-interests/{interestId}", Set.of("200")),
          Map.entry("POST /api/customers/{customerId}/demand-conditions", Set.of("201")),
          Map.entry("PATCH /api/demand-conditions/{conditionId}", Set.of("200")),
          Map.entry("GET /api/demand-conditions/{conditionId}/candidate-properties", Set.of("200")),
          Map.entry("GET /api/properties", Set.of("200")),
          Map.entry("POST /api/properties", Set.of("201")),
          Map.entry("GET /api/properties/duplicate-check", Set.of("200")),
          Map.entry("GET /api/properties/{propertyId}", Set.of("200")),
          Map.entry("PATCH /api/properties/{propertyId}", Set.of("200")),
          Map.entry("POST /api/properties/{propertyId}/photos", Set.of("201")),
          Map.entry("PATCH /api/properties/{propertyId}/photos/{photoId}", Set.of("200")),
          Map.entry("DELETE /api/properties/{propertyId}/photos/{photoId}", Set.of("204")),
          Map.entry("PUT /api/properties/{propertyId}/fields/{fieldDefinitionId}", Set.of("200")),
          Map.entry("GET /api/property-categories", Set.of("200")),
          Map.entry("POST /api/property-categories", Set.of("201")),
          Map.entry("PATCH /api/property-categories/{categoryId}", Set.of("200")),
          Map.entry("GET /api/property-categories/{categoryId}/fields", Set.of("200")),
          Map.entry("POST /api/property-categories/{categoryId}/fields", Set.of("201")),
          Map.entry(
              "PATCH /api/property-categories/{categoryId}/fields/{fieldDefinitionId}",
              Set.of("200")),
          Map.entry("GET /api/offices/current/auth-status", Set.of("200")),
          Map.entry("POST /api/grant-auth/return", Set.of("200")),
          Map.entry("POST /api/grant-auth/archive", Set.of("200")),
          Map.entry("GET /api/return-grants/current", Set.of("200")),
          Map.entry("POST /api/return-grants/{grantId}/export", Set.of("200")),
          Map.entry("GET /api/return-grants/{grantId}/exports/{exportId}", Set.of("200")),
          Map.entry("POST /api/archive-grants", Set.of("201")),
          Map.entry("GET /api/archive-grants/current", Set.of("200")),
          Map.entry("POST /api/import-batches", Set.of("200")),
          Map.entry("GET /api/import-batches", Set.of("200")),
          Map.entry("GET /api/import-batches/{batchId}", Set.of("200")),
          Map.entry("PUT /api/import-batches/{batchId}/sheet", Set.of("200")),
          Map.entry("GET /api/import-batches/{batchId}/mapping-suggestion", Set.of("200")),
          Map.entry("PUT /api/import-batches/{batchId}/mapping", Set.of("200")),
          Map.entry("GET /api/import-batches/{batchId}/preview", Set.of("200")),
          Map.entry("POST /api/import-batches/{batchId}/execute", Set.of("200")),
          Map.entry("POST /api/import-batches/{batchId}/cancel", Set.of("200")),
          Map.entry("POST /api/import-batches/{batchId}/retry-failed", Set.of("200")),
          Map.entry("POST /api/import-support-requests", Set.of("200")),
          Map.entry("GET /api/import-support-requests", Set.of("200")),
          Map.entry("GET /api/import-support-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/import-support-requests/{requestId}/accept-quote", Set.of("200")),
          Map.entry("POST /api/import-support-requests/{requestId}/payment/confirm", Set.of("200")),
          Map.entry("POST /api/import-support-requests/{requestId}/approve", Set.of("200")),
          Map.entry("POST /api/import-support-requests/{requestId}/request-changes", Set.of("200")),
          Map.entry("POST /api/import-support-requests/{requestId}/cancel", Set.of("200")),
          Map.entry("POST /api/exports", Set.of("201")),
          Map.entry("GET /api/exports", Set.of("200")),
          Map.entry("GET /api/exports/allowed-tables", Set.of("200")),
          Map.entry("GET /api/exports/{exportId}", Set.of("200")),
          Map.entry("PUT /api/offices/current/export-policy", Set.of("200")));

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
