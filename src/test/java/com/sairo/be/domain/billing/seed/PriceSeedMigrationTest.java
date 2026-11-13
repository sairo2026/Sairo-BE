package com.sairo.be.domain.billing.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.DatabaseCleaner;
import com.sairo.be.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PriceSeedMigrationTest {

  private static final String SEED =
      "db/migration/V17__seed_standard_price_schedule_and_trial_events.sql";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private MockMvc mockMvc;

  private void applySeed() throws Exception {
    jdbcTemplate.execute(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8));
  }

  @BeforeEach
  void 시드를_빈_표에_다시_적용한다() throws Exception {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
    applySeed();
  }

  private static OffsetDateTime kst(String value) {
    return OffsetDateTime.parse(value + "+09:00");
  }

  @Test
  void 시스템_계정_두_개가_비활성_상태로_들어가고_작성자와_승인자가_다르다() {
    List<Map<String, Object>> accounts =
        jdbcTemplate.queryForList(
            "SELECT kakao_provider_key, status FROM operator_account ORDER BY kakao_provider_key");

    assertThat(accounts).hasSize(2);
    assertThat(accounts).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("DISABLED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_schedule WHERE approved_by_operator_id ="
                    + " created_by_operator_id",
                Long.class))
        .isZero();
  }

  @Test
  void 표준과_시작_고객_요금표가_같은_금액으로_시행_상태에_들어간다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT version, audience, status, solo_amount, team_first_extra,"
                + " team_after_four_extra, notice_at FROM price_schedule ORDER BY audience");

    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("status")).isEqualTo("ACTIVE");
              assertThat(row.get("solo_amount")).isEqualTo(39_900L);
              assertThat(row.get("team_first_extra")).isEqualTo(10_000L);
              assertThat(row.get("team_after_four_extra")).isEqualTo(8_000L);
              assertThat(row.get("notice_at")).isNull();
            });
    assertThat(rows.get(0)).containsEntry("audience", "STANDARD");
    assertThat(rows.get(1)).containsEntry("audience", "START_CUSTOMER");
  }

  @Test
  void 체험_이벤트는_시작_고객_하나와_일반_가입_네_개이고_일반_가입_창은_겹치지_않는다() {
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT name, audience, status, free_end_mode, fixed_free_ends_at, max_offices,"
                + " starts_at, ends_at, office_created_during, first_application_during"
                + " FROM trial_event ORDER BY starts_at, audience");

    assertThat(events).hasSize(5);
    assertThat(events).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("SCHEDULED"));
    Map<String, Object> start =
        events.stream()
            .filter(row -> row.get("audience").equals("START_CUSTOMER"))
            .findFirst()
            .get();
    assertThat(start).containsEntry("free_end_mode", "FIXED_END").containsEntry("max_offices", 15);
    assertThat(((Timestamp) start.get("fixed_free_ends_at")).toInstant())
        .isEqualTo(kst("2027-07-01T00:00:00").toInstant());
    assertThat(((Timestamp) start.get("starts_at")).toInstant())
        .isEqualTo(kst("2026-11-30T00:00:00").toInstant());
    assertThat(((Timestamp) start.get("ends_at")).toInstant())
        .isEqualTo(kst("2027-02-01T00:00:00").toInstant());
    List<Map<String, Object>> general =
        events.stream().filter(row -> row.get("audience").equals("GENERAL")).toList();
    assertThat(general).hasSize(4);
    for (int i = 1; i < general.size(); i++) {
      assertThat(((Timestamp) general.get(i).get("starts_at")).toInstant())
          .isEqualTo(((Timestamp) general.get(i - 1).get("ends_at")).toInstant());
    }
    assertThat(events)
        .allSatisfy(
            row -> {
              assertThat(row.get("office_created_during")).isEqualTo(false);
              assertThat(row.get("first_application_during")).isEqualTo(false);
            });
  }

  @Test
  void 시드를_다시_적용해도_행이_늘지_않는다() throws Exception {
    applySeed();

    assertThat(count("operator_account")).isEqualTo(2L);
    assertThat(count("price_schedule")).isEqualTo(2L);
    assertThat(count("trial_event")).isEqualTo(5L);
  }

  @Test
  void 공개_요금_조회는_시드한_표준_요금표로_200을_준다() throws Exception {
    mockMvc
        .perform(get("/api/public/pricing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("2026-STANDARD"))
        .andExpect(jsonPath("$.vatIncluded").value(true))
        .andExpect(jsonPath("$.billingCycle").value("MONTHLY_PREPAID"))
        .andExpect(jsonPath("$.plans[0].baseAmount").value(39_900))
        .andExpect(jsonPath("$.plans[1].perSeatAmount").value(10_000))
        .andExpect(jsonPath("$.plans[2].baseAmount").value(69_900))
        .andExpect(jsonPath("$.plans[2].perSeatAmount").value(8_000))
        .andExpect(jsonPath("$.examples[0].monthlyAmount").value(39_900))
        .andExpect(jsonPath("$.examples[1].monthlyAmount").value(49_900))
        .andExpect(jsonPath("$.examples[3].monthlyAmount").value(69_900))
        .andExpect(jsonPath("$.examples[4].monthlyAmount").value(77_900))
        .andExpect(jsonPath("$.examples[9].monthlyAmount").value(117_900))
        .andExpect(jsonPath("$.generalTrial").value(nullValue()));
  }

  private long count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }
}
