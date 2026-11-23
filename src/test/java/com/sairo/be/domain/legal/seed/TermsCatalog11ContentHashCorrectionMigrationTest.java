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
class TermsCatalog11ContentHashCorrectionMigrationTest {

  private static final String SEED =
      "db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql";
  private static final String TOS_PRIVACY =
      "db/migration/V30__add_terms_catalog_tos_privacy_1_1.sql";
  private static final String SUBSCRIPTION =
      "db/migration/V31__add_terms_catalog_subscription_purchase_1_1.sql";
  private static final String CORRECTION =
      "db/migration/V32__correct_terms_catalog_1_1_content_hash.sql";

  private static final String TOS_OLD =
      "97ddc8d8ae69212f858eaea8c47ffa803c9d52754404117a31c6fb2ddbad3176";
  private static final String PRIVACY_OLD =
      "0a5e22ca4a5b4c2c75e8f187788589ea22605d93596d5edd8dbe6005c6379307";
  private static final String SUBSCRIPTION_OLD =
      "eca742d6baf0299cc04ca3d8e95bf10f8974e25689527479c4423a219f581059";
  private static final String TOS_NEW =
      "2a9fae7a75eef01efdce8b33cc7a195fa74c92bc762c683472152ff480c5ef94";
  private static final String PRIVACY_NEW =
      "371004f23c65f23e841feb1e20ca89251ad32b226e2bae7decf12aa2e6461cd4";
  private static final String SUBSCRIPTION_NEW =
      "011d39a2d4c8a1cf4ca253d0299a0a07ca00f9e72d8820404f3baedd118b7d86";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  void 시드와_앞선_1_1_행을_적용한다() throws Exception {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
    run(SEED);
    run(TOS_PRIVACY);
    run(SUBSCRIPTION);
  }

  private void run(String path) throws Exception {
    jdbcTemplate.execute(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
  }

  private Map<String, String> hashes() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, content_hash FROM terms_catalog");
    return rows.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                r -> r.get("terms_code") + ":" + r.get("terms_version"),
                r -> ((String) r.get("content_hash")).trim()));
  }

  @Test
  void 시행_전_1_1_행의_해시가_정정_본문의_값으로_바뀌고_버전과_효력_시각은_그대로다() throws Exception {
    Map<String, String> before = hashes();
    assertThat(before)
        .containsEntry("TOS:1.1", TOS_OLD)
        .containsEntry("PRIVACY:1.1", PRIVACY_OLD)
        .containsEntry("SUBSCRIPTION_PURCHASE:1.1", SUBSCRIPTION_OLD);

    run(CORRECTION);

    Map<String, String> after = hashes();
    assertThat(after)
        .containsEntry("TOS:1.1", TOS_NEW)
        .containsEntry("PRIVACY:1.1", PRIVACY_NEW)
        .containsEntry("SUBSCRIPTION_PURCHASE:1.1", SUBSCRIPTION_NEW);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_catalog", Integer.class))
        .isEqualTo(before.size());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM terms_catalog WHERE terms_version = '1.1'"
                    + " AND effective_at = TIMESTAMPTZ '2026-12-16 00:00:00+09'",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void 시행_중인_1_0_행은_바뀌지_않는다() throws Exception {
    Map<String, String> before = hashes();

    run(CORRECTION);

    Map<String, String> after = hashes();
    for (String key : List.of("TOS:1.0", "PRIVACY:1.0", "SUBSCRIPTION_PURCHASE:1.0")) {
      assertThat(after.get(key)).isEqualTo(before.get(key));
    }
  }

  @Test
  void 변경_요약도_정정된_본문을_설명한다() throws Exception {
    run(CORRECTION);

    String tos =
        jdbcTemplate.queryForObject(
            "SELECT change_summary FROM terms_catalog WHERE terms_code = 'TOS'"
                + " AND terms_version = '1.1'",
            String.class);
    String subscription =
        jdbcTemplate.queryForObject(
            "SELECT change_summary FROM terms_catalog WHERE terms_code = 'SUBSCRIPTION_PURCHASE'"
                + " AND terms_version = '1.1'",
            String.class);
    assertThat(tos).contains("제18조 제3항").contains("제22조 제1항");
    assertThat(subscription).contains("무료 이용 뒤 첫 결제 실패");
  }

  @Test
  void 효력_시각이_지난_행은_해시가_같아도_고치지_않는다() throws Exception {
    jdbcTemplate.update(
        "UPDATE terms_catalog SET effective_at = TIMESTAMPTZ '2026-01-01 00:00:00+09'"
            + " WHERE terms_code = 'TOS' AND terms_version = '1.1'");

    run(CORRECTION);

    assertThat(hashes()).containsEntry("TOS:1.1", TOS_OLD);
    assertThat(hashes()).containsEntry("PRIVACY:1.1", PRIVACY_NEW);
  }

  @Test
  void 이미_다른_값으로_바뀐_행은_고치지_않는다() throws Exception {
    String other = "f".repeat(64);
    jdbcTemplate.update(
        "UPDATE terms_catalog SET content_hash = ? WHERE terms_code = 'PRIVACY'"
            + " AND terms_version = '1.1'",
        other);

    run(CORRECTION);

    assertThat(hashes()).containsEntry("PRIVACY:1.1", other);
  }

  @Test
  void 같은_마이그레이션을_두_번_적용해도_결과가_같다() throws Exception {
    run(CORRECTION);
    Map<String, String> once = hashes();

    run(CORRECTION);

    assertThat(hashes()).isEqualTo(once);
  }
}
