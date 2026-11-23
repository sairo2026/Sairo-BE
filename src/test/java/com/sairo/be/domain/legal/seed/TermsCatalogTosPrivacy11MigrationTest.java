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
class TermsCatalogTosPrivacy11MigrationTest {

  private static final String SEED =
      "db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql";
  private static final String MIGRATION = "db/migration/V30__add_terms_catalog_tos_privacy_1_1.sql";

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
  void 이용약관과_개인정보처리방침에_버전_1_1_행이_추가되고_1_0_행은_그대로다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, category, required, document_url,"
                + " reconsent_required, change_summary FROM terms_catalog"
                + " WHERE terms_code IN ('TOS', 'PRIVACY') ORDER BY terms_code, terms_version");

    assertThat(rows).hasSize(4);
    assertThat(rows)
        .extracting(r -> r.get("terms_code") + ":" + r.get("terms_version"))
        .containsExactly("PRIVACY:1.0", "PRIVACY:1.1", "TOS:1.0", "TOS:1.1");
    assertThat(rows).allSatisfy(row -> assertThat(row.get("reconsent_required")).isEqualTo(false));
    assertThat(rows.get(1))
        .containsEntry("category", "NOTICE")
        .containsEntry("required", false)
        .containsEntry("document_url", "/legal/privacy");
    assertThat(rows.get(3))
        .containsEntry("category", "TERMS")
        .containsEntry("required", true)
        .containsEntry("document_url", "/legal/terms");
    assertThat(rows.get(1).get("change_summary")).isNotNull();
    assertThat(rows.get(3).get("change_summary")).isNotNull();
  }

  @Test
  void 새_버전_본문_해시는_64자리_16진수이고_이전_버전_해시와_다르다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, content_hash FROM terms_catalog"
                + " WHERE terms_code IN ('TOS', 'PRIVACY')");

    assertThat(rows)
        .allSatisfy(row -> assertThat((String) row.get("content_hash")).matches("[0-9a-f]{64}"));
    assertThat(rows.stream().map(r -> (String) r.get("content_hash")).distinct().count())
        .isEqualTo(4);
  }

  @Test
  void 효력_시각_전에는_1_0이고_이후에는_1_1이_시행_중이다() {
    Instant before = Instant.parse("2026-12-15T14:59:59Z");
    Instant after = Instant.parse("2026-12-15T15:00:00Z");

    assertThat(
            termsCatalogRepository.findInForceByCode("TOS", before).orElseThrow().getTermsVersion())
        .isEqualTo("1.0");
    assertThat(
            termsCatalogRepository.findInForceByCode("TOS", after).orElseThrow().getTermsVersion())
        .isEqualTo("1.1");
    assertThat(
            termsCatalogRepository
                .findInForceByCode("PRIVACY", before)
                .orElseThrow()
                .getTermsVersion())
        .isEqualTo("1.0");
    assertThat(
            termsCatalogRepository
                .findInForceByCode("PRIVACY", after)
                .orElseThrow()
                .getTermsVersion())
        .isEqualTo("1.1");
  }

  @Test
  void 같은_마이그레이션을_두_번_적용해도_행이_늘지_않는다() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_catalog", Integer.class))
        .isEqualTo(9);
  }
}
