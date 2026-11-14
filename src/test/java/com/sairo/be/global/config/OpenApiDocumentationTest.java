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
          Map.entry("GET /api/offices/current/memberships", Set.of("200")),
          Map.entry("POST /api/offices/current/employment-roster", Set.of("201")),
          Map.entry("GET /api/offices/current/employment-roster", Set.of("200")),
          Map.entry("PATCH /api/offices/current/employment-roster/{rosterId}", Set.of("200")),
          Map.entry("POST /api/offices/current/owner-transfer", Set.of("201")),
          Map.entry("GET /api/offices/current/owner-transfer", Set.of("200")),
          Map.entry("GET /api/owner-transfers/{transferId}", Set.of("200")),
          Map.entry("POST /api/owner-transfers/{transferId}/accept", Set.of("200")),
          Map.entry("POST /api/owner-transfers/{transferId}/reject", Set.of("200")),
          Map.entry("POST /api/owner-transfers/{transferId}/withdraw", Set.of("200")),
          Map.entry("POST /api/ops/owner-transfers/{transferId}/approve", Set.of("200")),
          Map.entry("GET /api/ops/owner-transfers", Set.of("200")),
          Map.entry("GET /api/ops/home", Set.of("200")),
          Map.entry("GET /api/ops/offices", Set.of("200")),
          Map.entry("GET /api/ops/offices/{officeId}", Set.of("200")),
          Map.entry("GET /api/ops/operators", Set.of("200")),
          Map.entry("GET /api/ops/operators/options", Set.of("200")),
          Map.entry("POST /api/ops/operators/{operatorId}/roles", Set.of("200")),
          Map.entry("POST /api/ops/operators/{operatorId}/disable", Set.of("200")),
          Map.entry("POST /api/ops/operators/{operatorId}/enable", Set.of("200")),
          Map.entry("GET /api/ops/audit-log", Set.of("200")),
          Map.entry("POST /api/ops/operator-invitations", Set.of("201")),
          Map.entry("GET /api/ops/operator-invitations", Set.of("200")),
          Map.entry("POST /api/ops/operator-invitations/{invitationId}/cancel", Set.of("200")),
          Map.entry("GET /api/ops/members", Set.of("200")),
          Map.entry("GET /api/ops/members/{userId}", Set.of("200")),
          Map.entry("POST /api/ops/members/{userId}/emergency-suspend", Set.of("200")),
          Map.entry("POST /api/ops/raw-access-requests", Set.of("201")),
          Map.entry("GET /api/ops/raw-access-requests", Set.of("200")),
          Map.entry("GET /api/ops/raw-access-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/ops/raw-access-requests/{requestId}/approve", Set.of("200")),
          Map.entry("POST /api/ops/raw-access-requests/{requestId}/reject", Set.of("200")),
          Map.entry("POST /api/ops/raw-access-requests/{requestId}/execute", Set.of("200")),
          Map.entry("POST /api/ops/raw-access-grants/{grantId}/revoke", Set.of("200")),
          Map.entry("GET /api/ops/raw-access-grants/{grantId}/file", Set.of("200")),
          Map.entry("GET /api/ops/raw-access-grants/{grantId}/content", Set.of("200")),
          Map.entry("GET /api/ops/support-inquiries", Set.of("200")),
          Map.entry("GET /api/ops/support-inquiries/{inquiryId}", Set.of("200")),
          Map.entry("POST /api/ops/support-inquiries/{inquiryId}/assign", Set.of("200")),
          Map.entry("POST /api/ops/support-inquiries/{inquiryId}/answer", Set.of("200")),
          Map.entry("POST /api/ops/support-inquiries/{inquiryId}/notes", Set.of("201")),
          Map.entry("POST /api/ops/support-inquiries/{inquiryId}/attachments", Set.of("201")),
          Map.entry(
              "GET /api/ops/support-inquiries/{inquiryId}/attachments/{attachmentId}",
              Set.of("200")),
          Map.entry("PATCH /api/ops/support-inquiries/{inquiryId}/priority", Set.of("200")),
          Map.entry("GET /api/ops/enterprise-leads", Set.of("200")),
          Map.entry("GET /api/ops/enterprise-leads/{leadId}", Set.of("200")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/assign", Set.of("200")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/consultation-start", Set.of("200")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/quote", Set.of("201")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/contract", Set.of("200")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/close", Set.of("200")),
          Map.entry("POST /api/ops/enterprise-leads/{leadId}/reopen", Set.of("200")),
          Map.entry("GET /api/ops/guest-privacy-intakes", Set.of("200")),
          Map.entry("GET /api/ops/guest-privacy-intakes/{intakeId}", Set.of("200")),
          Map.entry("POST /api/ops/guest-privacy-intakes/{intakeId}/resolve", Set.of("200")),
          Map.entry("GET /api/ops/authority-requests", Set.of("200")),
          Map.entry("POST /api/ops/authority-requests", Set.of("201")),
          Map.entry("POST /api/ops/authority-requests/{requestId}/close", Set.of("200")),
          Map.entry("GET /api/ops/integrations/{integrationId}/contract-copies", Set.of("200")),
          Map.entry("POST /api/ops/integrations/{integrationId}/contract-copies", Set.of("201")),
          Map.entry("GET /api/ops/billing/invoices", Set.of("200")),
          Map.entry("GET /api/ops/billing/invoices/{invoiceId}", Set.of("200")),
          Map.entry("GET /api/ops/refund-requests", Set.of("200")),
          Map.entry("GET /api/ops/refund-requests/{refundId}", Set.of("200")),
          Map.entry("POST /api/ops/refund-requests/{refundId}/review", Set.of("200")),
          Map.entry("POST /api/ops/refund-requests/{refundId}/execute", Set.of("200")),
          Map.entry("GET /api/ops/billing/reconciliation", Set.of("200")),
          Map.entry("POST /api/ops/billing/reconciliation/{entryId}/resolve", Set.of("200")),
          Map.entry("GET /api/ops/billing/tax-documents", Set.of("200")),
          Map.entry("POST /api/ops/billing/invoices/{invoiceId}/tax-documents", Set.of("201")),
          Map.entry("POST /api/ops/billing/tax-documents/{documentId}/amend", Set.of("201")),
          Map.entry("GET /api/ops/start-customer-agreements", Set.of("200")),
          Map.entry("POST /api/ops/start-customer-agreements", Set.of("201")),
          Map.entry("POST /api/ops/start-customer-agreements/{agreementId}/approve", Set.of("200")),
          Map.entry("POST /api/ops/start-customer-agreements/{agreementId}/revoke", Set.of("200")),
          Map.entry("GET /api/ops/price-change-notices", Set.of("200")),
          Map.entry("POST /api/ops/price-schedules/{scheduleId}/notices", Set.of("201")),
          Map.entry("GET /api/ops/import-support-requests", Set.of("200")),
          Map.entry("GET /api/ops/import-support-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/ops/import-support-requests/{requestId}/quote", Set.of("201")),
          Map.entry("POST /api/ops/import-support-requests/{requestId}/reject", Set.of("200")),
          Map.entry("POST /api/ops/import-support-requests/{requestId}/start", Set.of("200")),
          Map.entry("PUT /api/ops/import-support-requests/{requestId}/mapping", Set.of("200")),
          Map.entry(
              "POST /api/ops/import-support-requests/{requestId}/submit-review", Set.of("200")),
          Map.entry("POST /api/ops/import-support-requests/{requestId}/execute", Set.of("202")),
          Map.entry("POST /api/ops/import-support-requests/{requestId}/deliver", Set.of("200")),
          Map.entry("GET /api/ops/privacy-requests", Set.of("200")),
          Map.entry("GET /api/ops/privacy-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/ops/privacy-requests/{requestId}/process", Set.of("200")),
          Map.entry("GET /api/ops/return-grants", Set.of("200")),
          Map.entry("GET /api/ops/archive-grants", Set.of("200")),
          Map.entry("POST /api/ops/return-grants/{grantId}/transfer", Set.of("200")),
          Map.entry("POST /api/ops/archive-grants/{grantId}/transfer", Set.of("200")),
          Map.entry("GET /api/ops/deletion-queue", Set.of("200")),
          Map.entry("GET /api/ops/deletion-queue/{queueId}", Set.of("200")),
          Map.entry("GET /api/ops/deletion-queue/{queueId}/preview", Set.of("200")),
          Map.entry("POST /api/ops/deletion-queue/{queueId}/exclude", Set.of("200")),
          Map.entry("POST /api/ops/deletion-queue/{queueId}/execute", Set.of("202")),
          Map.entry("POST /api/ops/deletion-queue/{queueId}/verify", Set.of("200")),
          Map.entry("POST /api/ops/deletion-queue/{queueId}/cancel", Set.of("200")),
          Map.entry("GET /api/ops/price-schedules", Set.of("200")),
          Map.entry("POST /api/ops/price-schedules", Set.of("201")),
          Map.entry("POST /api/ops/price-schedules/{scheduleId}/approve", Set.of("200")),
          Map.entry("GET /api/ops/trial-events", Set.of("200")),
          Map.entry("POST /api/ops/trial-events", Set.of("201")),
          Map.entry("GET /api/ops/trial-events/{eventId}/preview", Set.of("200")),
          Map.entry("POST /api/ops/trial-events/{eventId}/approve", Set.of("200")),
          Map.entry("POST /api/ops/trial-events/{eventId}/stop", Set.of("200")),
          Map.entry("GET /api/ops/business-calendar", Set.of("200")),
          Map.entry("PUT /api/ops/business-calendar/{date}", Set.of("200")),
          Map.entry("GET /api/ops/announcements", Set.of("200")),
          Map.entry("POST /api/ops/announcements", Set.of("201")),
          Map.entry("POST /api/ops/announcements/{announcementId}/submit-review", Set.of("200")),
          Map.entry("GET /api/ops/announcements/{announcementId}/preview", Set.of("200")),
          Map.entry("POST /api/ops/announcements/{announcementId}/publish", Set.of("200")),
          Map.entry("POST /api/ops/announcements/{announcementId}/end", Set.of("200")),
          Map.entry("POST /api/ops/announcements/{announcementId}/revisions", Set.of("201")),
          Map.entry("GET /api/ops/policy-documents", Set.of("200")),
          Map.entry("POST /api/ops/policy-documents", Set.of("201")),
          Map.entry("POST /api/ops/policy-documents/{documentId}/submit-review", Set.of("200")),
          Map.entry("POST /api/ops/policy-documents/{documentId}/publish", Set.of("200")),
          Map.entry("GET /api/ops/company-service-info", Set.of("200")),
          Map.entry("POST /api/ops/company-service-info", Set.of("201")),
          Map.entry("POST /api/ops/company-service-info/{infoId}/inquiry-test", Set.of("200")),
          Map.entry("GET /api/ops/system-jobs", Set.of("200")),
          Map.entry("POST /api/ops/system-jobs/{jobName}/rerun", Set.of("202")),
          Map.entry("GET /api/ops/incidents", Set.of("200")),
          Map.entry("POST /api/ops/incidents", Set.of("201")),
          Map.entry("PATCH /api/ops/incidents/{incidentId}", Set.of("200")),
          Map.entry("GET /api/ops/integrations", Set.of("200")),
          Map.entry("PATCH /api/ops/integrations/{integrationId}", Set.of("200")),
          Map.entry("POST /api/ops/integrations/{integrationId}/check", Set.of("200")),
          Map.entry("GET /api/ops/privacy-incidents", Set.of("200")),
          Map.entry("POST /api/ops/privacy-incidents", Set.of("201")),
          Map.entry("PATCH /api/ops/privacy-incidents/{privacyIncidentId}", Set.of("200")),
          Map.entry("GET /api/ops/terms-hash-checks", Set.of("200")),
          Map.entry("POST /api/ops/terms-hash-checks", Set.of("200")),
          Map.entry("GET /api/ops/retention-rules", Set.of("200")),
          Map.entry("PUT /api/ops/retention-rules/{ruleCode}", Set.of("200")),
          Map.entry("GET /api/ops/kakao-unlink-events", Set.of("200")),
          Map.entry("POST /api/ops/kakao-unlink-events/{eventId}/retry", Set.of("200")),
          Map.entry("POST /api/offices/current/close", Set.of("200")),
          Map.entry(
              "POST /api/return-grants/{grantId}/visit-coordinations/{coordinationId}/cancel",
              Set.of("200")),
          Map.entry(
              "POST"
                  + " /api/return-grants/{grantId}/visit-coordinations/{coordinationId}/access-revocations",
              Set.of("200")),
          Map.entry(
              "PATCH /api/return-grants/{grantId}/visit-schedule-items/{itemId}/result",
              Set.of("200")),
          Map.entry("POST /api/approver-portal/privacy-intakes", Set.of("201")),
          Map.entry("POST /api/memberships/me/leave", Set.of("204")),
          Map.entry("GET /api/public/pricing", Set.of("200")),
          Map.entry("GET /api/public/company-info", Set.of("200")),
          Map.entry("GET /api/policy-documents", Set.of("200")),
          Map.entry("POST /api/support-inquiries", Set.of("201")),
          Map.entry("GET /api/support-inquiries/me", Set.of("200")),
          Map.entry("GET /api/support-inquiries/{inquiryId}", Set.of("200")),
          Map.entry("POST /api/support-inquiries/{inquiryId}/replies", Set.of("201")),
          Map.entry("POST /api/support-inquiries/{inquiryId}/attachments", Set.of("201")),
          Map.entry(
              "GET /api/support-inquiries/{inquiryId}/attachments/{attachmentId}", Set.of("200")),
          Map.entry("POST /api/enterprise-leads", Set.of("201")),
          Map.entry("GET /api/enterprise-leads/{leadId}", Set.of("200")),
          Map.entry("POST /api/enterprise-leads/{leadId}/quotes/{quoteId}/accept", Set.of("200")),
          Map.entry("POST /api/enterprise-leads/{leadId}/quotes/{quoteId}/reject", Set.of("200")),
          Map.entry("POST /api/privacy-requests", Set.of("201")),
          Map.entry("GET /api/privacy-requests/me", Set.of("200")),
          Map.entry("GET /api/privacy-requests/{requestId}", Set.of("200")),
          Map.entry("POST /api/public/privacy-intakes", Set.of("201")),
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
          Map.entry("PUT /api/visit-coordinations/{coordinationId}/schedule", Set.of("200")),
          Map.entry(
              "GET /api/visit-coordinations/{coordinationId}/schedule/conflicts", Set.of("200")),
          Map.entry("PATCH /api/visit-coordinations/{coordinationId}/manager", Set.of("200")),
          Map.entry(
              "GET /api/visit-coordinations/{coordinationId}/confirmation-messages", Set.of("200")),
          Map.entry(
              "POST /api/visit-coordinations/{coordinationId}/confirmation-deliveries",
              Set.of("201")),
          Map.entry(
              "GET /api/visit-coordinations/{coordinationId}/confirmation-deliveries",
              Set.of("200")),
          Map.entry(
              "GET /api/public/approver-property-links/{token}/visit-requests", Set.of("200")),
          Map.entry(
              "POST /api/public/approver-property-links/{token}/visit-requests/{requestId}/select",
              Set.of("200")),
          Map.entry("GET /api/visit-request-notices", Set.of("200")),
          Map.entry("POST /api/visit-request-notices/{noticeId}/read", Set.of("204")),
          Map.entry("PATCH /api/visit-schedule-items/{itemId}/result", Set.of("200")),
          Map.entry("GET /api/visit-schedule-items/{itemId}/result-history", Set.of("200")),
          Map.entry("POST /api/visit-schedule-items/{itemId}/assistant-notice", Set.of("201")),
          Map.entry("GET /api/visit-schedule-items/{itemId}/assistant-notices", Set.of("200")),
          Map.entry("POST /api/visit-schedule-items/{itemId}/change-requests", Set.of("201")),
          Map.entry("GET /api/visit-assistant-notices", Set.of("200")),
          Map.entry("POST /api/visit-assistant-notices/{noticeId}/confirm", Set.of("200")),
          Map.entry("POST /api/visit-coordinations/{coordinationId}/reschedule", Set.of("200")),
          Map.entry(
              "POST /api/visit-coordinations/{coordinationId}/access-revocations", Set.of("200")),
          Map.entry("POST /api/visit-coordinations/{coordinationId}/cancel", Set.of("200")),
          Map.entry("POST /api/visit-coordinations/{coordinationId}/clone", Set.of("200")),
          Map.entry("GET /api/public/customer-visit-links/{token}/confirmed", Set.of("200")),
          Map.entry(
              "GET /api/public/approver-property-links/{token}/confirmed-schedules", Set.of("200")),
          Map.entry("GET /api/public/approver-property-links/{token}/visit-reviews", Set.of("200")),
          Map.entry(
              "PUT /api/public/approver-property-links/{token}/visit-reviews/{scheduleItemId}",
              Set.of("200", "201")),
          Map.entry(
              "POST /api/public/approver-property-links/{token}/visit-reviews/{scheduleItemId}/withdraw",
              Set.of("200")),
          Map.entry(
              "GET /api/public/approver-property-links/{token}/preferred-brokers", Set.of("200")),
          Map.entry(
              "POST /api/public/approver-property-links/{token}/preferred-brokers", Set.of("201")),
          Map.entry(
              "POST /api/public/approver-property-links/{token}/preferred-brokers/{preferredBrokerId}/release",
              Set.of("200")),
          Map.entry("GET /api/visit-trust/me", Set.of("200")),
          Map.entry("GET /api/visit-reviews/received", Set.of("200")),
          Map.entry("POST /api/visit-reviews/{reviewId}/disputes", Set.of("201")),
          Map.entry("GET /api/ops/visit-review-disputes", Set.of("200")),
          Map.entry("POST /api/ops/visit-review-disputes/{disputeId}/decision", Set.of("200")),
          Map.entry("POST /api/ops/visit-reviews/{reviewId}/remove", Set.of("200")),
          Map.entry(
              "POST /api/properties/{propertyId}/access-approvals/{approvalId}/apply-grants",
              Set.of("201")),
          Map.entry(
              "GET /api/properties/{propertyId}/access-approvals/{approvalId}/apply-grants",
              Set.of("200")),
          Map.entry(
              "POST /api/properties/{propertyId}/access-approvals/{approvalId}/apply-grants/{grantId}/revoke",
              Set.of("200")),
          Map.entry("GET /api/apply-grants/received", Set.of("200")),
          Map.entry("GET /api/properties/{propertyId}/cross-office-requests", Set.of("200")),
          Map.entry("GET /api/properties/{propertyId}/cross-office-schedules", Set.of("200")),
          Map.entry("POST /api/public/approver-auth/otp", Set.of("200")),
          Map.entry("POST /api/public/approver-auth/verify", Set.of("200")),
          Map.entry("DELETE /api/approver-portal/session", Set.of("204")),
          Map.entry("GET /api/approver-portal/profile", Set.of("200")),
          Map.entry("POST /api/approver-portal/profile/close", Set.of("200")),
          Map.entry("GET /api/approver-portal/approvals", Set.of("200")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/link-decision", Set.of("200")),
          Map.entry("DELETE /api/approver-portal/approvals/{approvalId}/link", Set.of("204")),
          Map.entry("POST /api/approver-portal/condition-bulk/preview", Set.of("200")),
          Map.entry("POST /api/approver-portal/condition-bulk", Set.of("200")),
          Map.entry("GET /api/approver-portal/approvals/{approvalId}/conditions", Set.of("200")),
          Map.entry("PUT /api/approver-portal/approvals/{approvalId}/conditions", Set.of("200")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/conditions/impact-preview",
              Set.of("200")),
          Map.entry(
              "GET /api/approver-portal/approvals/{approvalId}/visit-requests", Set.of("200")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/visit-requests/{requestId}/select",
              Set.of("200")),
          Map.entry(
              "GET /api/approver-portal/approvals/{approvalId}/confirmed-schedules", Set.of("200")),
          Map.entry("GET /api/approver-portal/approvals/{approvalId}/visit-reviews", Set.of("200")),
          Map.entry(
              "PUT /api/approver-portal/approvals/{approvalId}/visit-reviews/{scheduleItemId}",
              Set.of("200", "201")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/visit-reviews/{scheduleItemId}/withdraw",
              Set.of("200")),
          Map.entry(
              "GET /api/approver-portal/approvals/{approvalId}/preferred-brokers", Set.of("200")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/preferred-brokers", Set.of("201")),
          Map.entry(
              "POST /api/approver-portal/approvals/{approvalId}/preferred-brokers/{preferredBrokerId}/release",
              Set.of("200")),
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
          Map.entry("POST /api/archive-grants/{grantId}/documents/export", Set.of("201")),
          Map.entry(
              "GET /api/archive-grants/{grantId}/documents/exports/{exportId}", Set.of("200")),
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
          Map.entry("PUT /api/offices/current/export-policy", Set.of("200")),
          Map.entry("GET /api/home", Set.of("200")),
          Map.entry("GET /api/tasks", Set.of("200")),
          Map.entry("POST /api/tasks", Set.of("201")),
          Map.entry("PATCH /api/tasks/{taskId}", Set.of("200")),
          Map.entry("DELETE /api/tasks/{taskId}", Set.of("204")),
          Map.entry("GET /api/notifications", Set.of("200")),
          Map.entry("POST /api/notifications/{notificationId}/read", Set.of("204")),
          Map.entry("GET /api/settlements", Set.of("200")),
          Map.entry("GET /api/settlements/{yearMonth}", Set.of("200")),
          Map.entry("GET /api/settlements/{yearMonth}/details", Set.of("200")),
          Map.entry("GET /api/settlements/{yearMonth}/memo", Set.of("200")),
          Map.entry("PUT /api/settlements/{yearMonth}/memo", Set.of("200")),
          Map.entry("GET /api/settlements/{yearMonth}/export", Set.of("200")),
          Map.entry("POST /api/entity-lifecycle-events/{eventId}/corrections", Set.of("201")),
          Map.entry("GET /api/contracts", Set.of("200")),
          Map.entry("POST /api/properties/{propertyId}/contracts/drafts", Set.of("201")),
          Map.entry("PATCH /api/contracts/{contractId}/draft", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/sign", Set.of("200")),
          Map.entry("DELETE /api/contracts/{contractId}/draft", Set.of("204")),
          Map.entry("POST /api/properties/{propertyId}/contracts", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/corrections", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/revisions", Set.of("200")),
          Map.entry("PATCH /api/contracts/{contractId}/status", Set.of("200")),
          Map.entry("PATCH /api/contracts/{contractId}/settlement-status", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/renew", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/status-history", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/duplicate-exclusion", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/documents", Set.of("201")),
          Map.entry(
              "POST /api/contracts/{contractId}/documents/{documentId}/revision", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/documents/{documentId}", Set.of("200")),
          Map.entry("POST /api/contracts/{contractId}/fees", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/fees", Set.of("200")),
          Map.entry("POST /api/contract-fees/{feeId}/revisions", Set.of("201")),
          Map.entry("POST /api/contract-fees/{feeId}/transactions", Set.of("201")),
          Map.entry("POST /api/contracts/{contractId}/payment-schedules", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/payment-schedules", Set.of("200")),
          Map.entry("POST /api/payment-schedules/{scheduleId}/transactions", Set.of("201")),
          Map.entry("GET /api/contracts/{contractId}/followup-tasks", Set.of("200")),
          Map.entry("PATCH /api/contract-followup-tasks/{taskId}", Set.of("200")));

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

  @Test
  void 명세에_맞춘_응답_필드가_스키마에_있다() throws Exception {
    JsonNode schemas = objectMapper.readTree(apiDocs()).get("components").get("schemas");

    assertThat(properties(schemas, "OwnerTransferResponse")).contains("updatedAt", "version");
    assertThat(properties(schemas, "RefundRequestStatusResponse")).contains("createdAt");
    assertThat(properties(schemas, "GrantStatusResponse")).contains("officeId");
    assertThat(properties(schemas, "ArchiveGrantCreateResponse")).contains("officeId");
    assertThat(properties(schemas, "ExportResponse")).contains("failureReason");
  }

  private Set<String> properties(JsonNode schemas, String name) {
    JsonNode schema = schemas.get(name);
    assertThat(schema).as(name).isNotNull();
    Set<String> names = new HashSet<>();
    schema.get("properties").propertyNames().forEach(names::add);
    return names;
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
