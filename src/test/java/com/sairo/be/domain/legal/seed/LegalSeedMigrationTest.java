package com.sairo.be.domain.legal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.DatabaseCleaner;
import com.sairo.be.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class LegalSeedMigrationTest {

  private static final String SEED =
      "db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  void 시드를_빈_표에_다시_적용한다() throws Exception {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
    jdbcTemplate.execute(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void 약관_카탈로그에_일곱_항목이_버전_1_0으로_들어간다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, category, required, display_context,"
                + " reconsent_required FROM terms_catalog ORDER BY terms_code");

    assertThat(rows).hasSize(7);
    assertThat(rows).allSatisfy(row -> assertThat(row.get("terms_version")).isEqualTo("1.0"));
    assertThat(rows).allSatisfy(row -> assertThat(row.get("reconsent_required")).isEqualTo(false));
    assertThat(row(rows, "TOS"))
        .containsEntry("category", "TERMS")
        .containsEntry("required", true)
        .containsEntry("display_context", "SIGNUP");
    assertThat(row(rows, "PRIVACY"))
        .containsEntry("category", "NOTICE")
        .containsEntry("required", false)
        .containsEntry("display_context", "SIGNUP");
    assertThat(row(rows, "MARKETING"))
        .containsEntry("category", "MARKETING")
        .containsEntry("required", false);
    assertThat(row(rows, "CARD_REGISTRATION"))
        .containsEntry("category", "SEPARATE_CONSENT")
        .containsEntry("required", true)
        .containsEntry("display_context", "CARD_REGISTRATION");
    assertThat(row(rows, "SUBSCRIPTION_PURCHASE"))
        .containsEntry("category", "SEPARATE_CONSENT")
        .containsEntry("required", true)
        .containsEntry("display_context", "PURCHASE");
    assertThat(row(rows, "GUEST_LINK_NOTICE_APPROVER"))
        .containsEntry("category", "NOTICE")
        .containsEntry("display_context", "LINK_NOTICE");
    assertThat(row(rows, "GUEST_LINK_NOTICE_CUSTOMER"))
        .containsEntry("category", "NOTICE")
        .containsEntry("display_context", "LINK_NOTICE");
  }

  @Test
  void 약관_본문_해시는_서로_다른_64자리_16진수다() {
    List<String> hashes =
        jdbcTemplate.queryForList("SELECT content_hash FROM terms_catalog", String.class);

    assertThat(hashes).hasSize(7).doesNotHaveDuplicates();
    assertThat(hashes).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
  }

  @Test
  void 시행_중_약관_조회에서_가입_항목은_이용약관_방침_마케팅이다() {
    List<String> codes =
        jdbcTemplate.queryForList(
            "SELECT terms_code FROM terms_catalog WHERE display_context = 'SIGNUP'"
                + " AND effective_at <= now() ORDER BY terms_code",
            String.class);

    assertThat(codes).containsExactly("MARKETING", "PRIVACY", "TOS");
  }

  @Test
  void 보관_규칙_열아홉개가_가정_상태로_들어간다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT rule_code, retention_days, anchor_event, status FROM legal_retention_rule");

    assertThat(rows).hasSize(19);
    assertThat(rows).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("ASSUMPTION"));
    assertRule(rows, "LOGIN_HISTORY", 365, "RECORDED_AT");
    assertRule(rows, "TERMS_AGREEMENT_AFTER_WITHDRAWAL", 1095, "WITHDRAWN_AT");
    assertRule(rows, "MARKETING_NOTICE_DELIVERY", 1095, "RECORDED_AT");
    assertRule(rows, "BILLING_RECORD", 1825, "RECORDED_AT");
    assertRule(rows, "TAX_DOCUMENT", 1825, "ISSUED_AT");
    assertRule(rows, "PRIVACY_REQUEST_RECORD", 1095, "COMPLETED_AT");
    assertRule(rows, "SUPPORT_INQUIRY", 1095, "CLOSED_AT");
    assertRule(rows, "GUEST_INPUT_AFTER_APPROVAL_END", 90, "APPROVAL_END");
    assertRule(rows, "KAKAO_UNLINK_KEY_MAX", 7, "RECORDED_AT");
    assertRule(rows, "OFFICE_CONTRACT_DOCUMENT", 1825, "RECORDED_AT");
    assertRule(rows, "OFFICE_EXPLANATION_DOCUMENT", 1095, "RECORDED_AT");
  }

  @Test
  void 시드를_두_번_적용해도_행이_늘지_않는다() throws Exception {
    jdbcTemplate.execute(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8));

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_catalog", Integer.class))
        .isEqualTo(7);
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM legal_retention_rule", Integer.class))
        .isEqualTo(19);
  }

  private static Map<String, Object> row(List<Map<String, Object>> rows, String code) {
    return rows.stream().filter(r -> code.equals(r.get("terms_code"))).findFirst().orElseThrow();
  }

  private static void assertRule(
      List<Map<String, Object>> rows, String code, int days, String anchor) {
    Map<String, Object> rule =
        rows.stream().filter(r -> code.equals(r.get("rule_code"))).findFirst().orElseThrow();
    assertThat(rule).containsEntry("retention_days", days).containsEntry("anchor_event", anchor);
  }
}
