package com.sairo.be.domain.settlement.repository;

import com.sairo.be.domain.settlement.entity.SettlementMetric;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 지난 달 결산 저장값(monthly_settlement)을 다룬다. 현재 달은 저장하지 않는다. */
@Profile("!migrate")
@Repository
public class MonthlySettlementRepository {

  public record StoredSettlement(Map<SettlementMetric, Long> values, Instant generatedAt) {}

  private static final String COLUMNS =
      Stream.of(SettlementMetric.values())
          .map(MonthlySettlementRepository::column)
          .collect(Collectors.joining(", "));

  private final JdbcClient jdbcClient;

  public MonthlySettlementRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  static String column(SettlementMetric metric) {
    StringBuilder result = new StringBuilder();
    for (char ch : metric.name().toCharArray()) {
      if (Character.isUpperCase(ch)) {
        result.append('_').append(Character.toLowerCase(ch));
      } else {
        result.append(ch);
      }
    }
    return result.toString();
  }

  public Optional<StoredSettlement> lock(Long officeId, YearMonth month) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + ", generated_at FROM monthly_settlement WHERE office_id = :office AND"
                + " year_month = :month FOR UPDATE")
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .query(
            (rs, rowNum) -> {
              Map<SettlementMetric, Long> values = new EnumMap<>(SettlementMetric.class);
              for (SettlementMetric metric : SettlementMetric.values()) {
                values.put(metric, rs.getLong(column(metric)));
              }
              Timestamp generated = rs.getTimestamp("generated_at");
              return new StoredSettlement(values, generated == null ? null : generated.toInstant());
            })
        .optional();
  }

  public boolean exists(Long officeId, YearMonth month) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM monthly_settlement WHERE office_id = :office AND"
                + " year_month = :month)")
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .query(Boolean.class)
        .single();
  }

  public void insert(
      Long officeId, YearMonth month, Map<SettlementMetric, Long> values, Instant now) {
    StatementBuilder builder = new StatementBuilder(values);
    jdbcClient
        .sql(
            "INSERT INTO monthly_settlement (office_id, year_month, "
                + COLUMNS
                + ", is_finalized, generated_at, created_at, updated_at) VALUES (:office, :month, "
                + builder.placeholders()
                + ", true, :now, :now, :now) ON CONFLICT (office_id, year_month) DO NOTHING")
        .params(builder.params())
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .param("now", Timestamp.from(now))
        .update();
  }

  public void update(
      Long officeId, YearMonth month, Map<SettlementMetric, Long> values, Instant now) {
    StatementBuilder builder = new StatementBuilder(values);
    jdbcClient
        .sql(
            "UPDATE monthly_settlement SET "
                + builder.assignments()
                + ", is_finalized = true, generated_at = :now, updated_at = :now, version = version"
                + " + 1 WHERE office_id = :office AND year_month = :month")
        .params(builder.params())
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .param("now", Timestamp.from(now))
        .update();
  }

  private static final class StatementBuilder {

    private final Map<SettlementMetric, Long> values;

    StatementBuilder(Map<SettlementMetric, Long> values) {
      this.values = values;
    }

    String placeholders() {
      return Stream.of(SettlementMetric.values())
          .map(metric -> ":" + metric.name())
          .collect(Collectors.joining(", "));
    }

    String assignments() {
      return Stream.of(SettlementMetric.values())
          .map(metric -> column(metric) + " = :" + metric.name())
          .collect(Collectors.joining(", "));
    }

    Map<String, Object> params() {
      Map<String, Object> params = new java.util.HashMap<>();
      for (SettlementMetric metric : SettlementMetric.values()) {
        params.put(metric.name(), values.getOrDefault(metric, 0L));
      }
      return params;
    }
  }
}
