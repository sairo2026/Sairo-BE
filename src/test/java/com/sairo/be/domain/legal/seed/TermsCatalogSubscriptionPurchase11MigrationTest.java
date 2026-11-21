package com.sairo.be.domain.legal.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.legal.repository.TermsCatalogRepository;
import com.sairo.be.support.DatabaseCleaner;
import com.sairo.be.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TermsCatalogSubscriptionPurchase11MigrationTest {

  private static final String SEED =
      "db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql";
  private static final String MIGRATION =
      "db/migration/V31__add_terms_catalog_subscription_purchase_1_1.sql";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private TermsCatalogRepository termsCatalogRepository;

  @BeforeEach
  void 시드와_새_버전_마이그레이션을_빈_표에_적용한다() throws Exception {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
    jdbcTemplate.execute(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8));
    jdbcTemplate.execute(
        new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void 구독_구매_약관에_버전_1_1_행이_추가되고_1_0_행은_그대로다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_version, category, required, display_context, document_url,"
                + " reconsent_required, content_hash, change_summary FROM terms_catalog"
                + " WHERE terms_code = 'SUBSCRIPTION_PURCHASE' ORDER BY terms_version");

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(r -> r.get("terms_version")).containsExactly("1.0", "1.1");
    assertThat(rows.get(1))
        .containsEntry("category", "SEPARATE_CONSENT")
        .containsEntry("required", true)
        .containsEntry("display_context", "PURCHASE")
        .containsEntry("document_url", "/legal/subscription-terms")
        .containsEntry("reconsent_required", false);
    assertThat((String) rows.get(1).get("content_hash")).matches("[0-9a-f]{64}");
    assertThat(rows.get(1).get("content_hash")).isNotEqualTo(rows.get(0).get("content_hash"));
    assertThat(rows.get(1).get("change_summary")).isNotNull();
  }

  @Test
  void 새_버전은_갱신_결제일_이후부터_시행된다() {
    Instant renewalDay = Instant.parse("2026-12-15T14:59:59Z");
    Instant effective = Instant.parse("2026-12-15T15:00:00Z");

    assertThat(
            termsCatalogRepository
                .findInForceByCode("SUBSCRIPTION_PURCHASE", renewalDay)
                .orElseThrow()
                .getTermsVersion())
        .isEqualTo("1.0");
    assertThat(
            termsCatalogRepository
                .findInForceByCode("SUBSCRIPTION_PURCHASE", effective)
                .orElseThrow()
                .getTermsVersion())
        .isEqualTo("1.1");
  }

  @Test
  void 같은_마이그레이션을_두_번_적용해도_행이_늘지_않는다() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_catalog", Integer.class))
        .isEqualTo(8);
  }
}
