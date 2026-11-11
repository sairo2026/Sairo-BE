package com.sairo.be.domain.settlement.repository;

import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 월간 메모(monthly_memo)를 다룬다. 사무소·월당 한 행이고 version으로 낙관적 잠금을 건다. */
@Profile("!migrate")
@Repository
public class MonthlyMemoRepository {

  public record MemoRow(
      String content,
      long version,
      Long updatedByMembershipId,
      String updatedByMemberName,
      Instant updatedAt) {}

  private final JdbcClient jdbcClient;

  public MonthlyMemoRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<MemoRow> find(Long officeId, YearMonth month) {
    return jdbcClient
        .sql(
            "SELECT mm.content, mm.version, mm.updated_by_membership_id, mm.updated_at, u.name AS"
                + " member_name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no FROM monthly_memo mm LEFT JOIN office_membership m ON m.id"
                + " = mm.updated_by_membership_id LEFT JOIN app_user u ON u.id = m.user_id WHERE"
                + " mm.office_id = :office AND mm.year_month = :month")
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .query(
            (rs, rowNum) -> {
              Long membershipId = rs.getObject("updated_by_membership_id", Long.class);
              String name = rs.getString("member_name");
              String display =
                  name == null
                      ? null
                      : WithdrawnMemberDisplay.name(
                          rs.getBoolean("withdrawn"),
                          name,
                          rs.getObject("withdrawn_alias_no", Integer.class));
              Timestamp updated = rs.getTimestamp("updated_at");
              return new MemoRow(
                  rs.getString("content"),
                  rs.getLong("version"),
                  membershipId,
                  display,
                  updated == null ? null : updated.toInstant());
            })
        .optional();
  }

  /** 처음 작성한다. 같은 달을 먼저 만든 요청이 있으면 false다. */
  public boolean insert(
      Long officeId, YearMonth month, String content, Long membershipId, Instant now) {
    return !jdbcClient
        .sql(
            "INSERT INTO monthly_memo (office_id, year_month, content, version,"
                + " updated_by_membership_id, updated_at) VALUES (:office, :month, :content, 1,"
                + " :member, :now) ON CONFLICT (office_id, year_month) DO NOTHING RETURNING id")
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .param("content", content)
        .param("member", membershipId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list()
        .isEmpty();
  }

  /** 읽은 version과 같을 때만 고친다. 다르면 false다. */
  public boolean update(
      Long officeId,
      YearMonth month,
      long expectedVersion,
      String content,
      Long membershipId,
      Instant now) {
    return jdbcClient
            .sql(
                "UPDATE monthly_memo SET content = :content, version = version + 1,"
                    + " updated_by_membership_id = :member, updated_at = :now WHERE office_id ="
                    + " :office AND year_month = :month AND version = :expected")
            .param("content", content)
            .param("member", membershipId)
            .param("now", Timestamp.from(now))
            .param("office", officeId)
            .param("month", Date.valueOf(month.atDay(1)))
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  public Optional<Long> currentVersion(Long officeId, YearMonth month) {
    return jdbcClient
        .sql("SELECT version FROM monthly_memo WHERE office_id = :office AND year_month = :month")
        .param("office", officeId)
        .param("month", Date.valueOf(month.atDay(1)))
        .query(Long.class)
        .optional();
  }
}
