package com.sairo.be.support.fixture;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PolicyFixtures {

  public static final List<String> LAUNCH_POLICY_TYPES = List.of("TERMS", "PRIVACY", "REFUND");

  private PolicyFixtures() {}

  public static void publish(JdbcTemplate jdbcTemplate, String docType, long operatorId) {
    jdbcTemplate.update(
        "INSERT INTO policy_document (doc_type, version, status, body, change_summary,"
            + " requires_consent, effective_at, published_at, created_by_operator_id,"
            + " reviewed_by_operator_id, published_by_operator_id) VALUES (?, 'LAUNCH-FIXTURE',"
            + " 'PUBLISHED', '본문', '요약', false, now(), now(), ?, ?, ?)",
        docType,
        operatorId,
        operatorId,
        operatorId);
  }

  public static void publishLaunchPolicies(JdbcTemplate jdbcTemplate, long operatorId) {
    LAUNCH_POLICY_TYPES.forEach(docType -> publish(jdbcTemplate, docType, operatorId));
  }
}
