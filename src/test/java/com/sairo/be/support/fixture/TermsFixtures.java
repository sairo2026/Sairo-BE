package com.sairo.be.support.fixture;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TermsFixtures {

  public static final String VERSION = "2026-09-01";
  private static final String HASH = "a".repeat(64);

  private TermsFixtures() {}

  public static void seedSignupTerms(JdbcTemplate jdbcTemplate) {
    insert(
        jdbcTemplate,
        "TOS",
        VERSION,
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-01-01T00:00:00Z",
        false,
        null);
    insert(
        jdbcTemplate,
        "PRIVACY",
        VERSION,
        "개인정보처리방침",
        "NOTICE",
        false,
        "2026-01-01T00:00:00Z",
        false,
        null);
    insert(
        jdbcTemplate,
        "MARKETING",
        VERSION,
        "마케팅 정보 수신 동의",
        "MARKETING",
        false,
        "2026-01-01T00:00:00Z",
        false,
        null);
  }

  public static void insert(
      JdbcTemplate jdbcTemplate,
      String code,
      String version,
      String title,
      String category,
      boolean required,
      String effectiveAt,
      boolean reconsentRequired,
      String changeSummary) {
    jdbcTemplate.update(
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at, display_context, reconsent_required,"
            + " change_summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SIGNUP', ?, ?)",
        code,
        version,
        title,
        category,
        required,
        "/legal/terms/" + code + "/" + version,
        HASH,
        java.sql.Timestamp.from(Instant.parse(effectiveAt)),
        reconsentRequired,
        changeSummary);
  }
}
