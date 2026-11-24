package com.sairo.be.e2e;

import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.support.fixture.BillingFixtures;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Test-classpath-only fixture for a loopback local-e2e database. */
@Profile("local-e2e")
@RestController
@RequestMapping("/api/__e2e/failed-refunds")
@RequiresAccess(AccessPermission.PUBLIC)
public class E2EFailedRefundController {
  private final JdbcTemplate jdbc;
  private final byte[] expectedToken;

  E2EFailedRefundController(
      JdbcTemplate jdbc,
      @Value("${E2E_FIXTURE_TOKEN}") String fixtureToken,
      @Value("${spring.datasource.url:}") String jdbcUrl) {
    if (fixtureToken.length() < 32) {
      throw new IllegalStateException("E2E fixture token must have at least 32 characters");
    }
    URI database;
    try {
      database = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException invalidUrl) {
      throw new IllegalStateException("E2E fixture requires a loopback PostgreSQL URL");
    }
    if (!jdbcUrl.startsWith("jdbc:")
        || !"postgresql".equals(database.getScheme())
        || database.getHost() == null
        || !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(database.getHost())) {
      throw new IllegalStateException("E2E fixture requires a loopback PostgreSQL URL");
    }
    this.jdbc = jdbc;
    this.expectedToken = fixtureToken.getBytes(StandardCharsets.UTF_8);
  }

  record CreateRequest(String ownerEmail) {}

  @PostMapping
  ResponseEntity<Map<String, Long>> create(
      @RequestHeader("X-E2E-Token") String token, @RequestBody CreateRequest request) {
    authenticate(token);
    if (request.ownerEmail() == null || !request.ownerEmail().endsWith("@example.com")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    Map<String, Object> paid =
        jdbc.queryForMap(
            "SELECT i.office_id, i.subscription_id, i.purchase_consent_id, i.amount"
                + " FROM billing_invoice i JOIN office_membership m ON m.office_id = i.office_id"
                + " JOIN app_user u ON u.id = m.user_id"
                + " WHERE u.email = ? AND i.status = 'PAID' ORDER BY i.id DESC LIMIT 1",
            request.ownerEmail());
    long officeId = ((Number) paid.get("office_id")).longValue();
    long subscriptionId = ((Number) paid.get("subscription_id")).longValue();
    long consentId = ((Number) paid.get("purchase_consent_id")).longValue();
    long amount = ((Number) paid.get("amount")).longValue();
    Instant start =
        Instant.now().minus(100, ChronoUnit.DAYS).plusMillis(System.nanoTime() % 100_000);
    long invoiceId =
        new BillingFixtures(jdbc)
            .invoice(officeId, subscriptionId, consentId)
            .amount(amount)
            .period(start, start.plus(30, ChronoUnit.DAYS))
            .insert();
    String orderId = "E2E_REFUND_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "INSERT INTO billing_charge_attempt (invoice_id, attempt_no, result, pg_transaction_id,"
            + " pg_order_id, idempotency_key, resolved_at) VALUES (?, 1, 'SUCCESS', ?, ?, ?, now())",
        invoiceId,
        "paykey-" + orderId,
        orderId,
        UUID.randomUUID().toString());
    long refundId =
        jdbc.queryForObject(
            "INSERT INTO refund_request (office_id, invoice_id, reason_code, reason_event_type,"
                + " reason_event_id, status, refund_amount, entitlement_amount,"
                + " original_approved_amount, pg_idempotency_key, failure_reason)"
                + " VALUES (?, ?, 'INCORRECT_CHARGE', 'INVOICE', ?, 'FAILED', ?, ?, ?, ?,"
                + " 'STUB_CANCEL_DECLINED') RETURNING id",
            Long.class,
            officeId,
            invoiceId,
            invoiceId,
            amount,
            amount,
            amount,
            UUID.randomUUID().toString());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("invoiceId", invoiceId, "refundId", refundId));
  }

  @GetMapping("/{refundId}/audit-count")
  ResponseEntity<Map<String, Integer>> auditCount(
      @RequestHeader("X-E2E-Token") String token, @PathVariable long refundId) {
    authenticate(token);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM ops_audit_log WHERE target_type = 'REFUND_REQUEST' AND target_id = ?"
                + " AND action_type IN ('REFUND_REOPENED', 'REFUND_RESOLVED_MANUALLY')",
            Integer.class,
            refundId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("count", count));
  }

  private void authenticate(String token) {
    if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
