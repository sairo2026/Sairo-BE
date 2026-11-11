package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.service.TrustSummary;
import com.sairo.be.domain.visit.service.TrustSummary.Indicator;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class TrustSummaryRepository {

  private record Row(
      Long approvalId, String status, String punctuality, String contact, String asRequested) {}

  private final JdbcClient jdbcClient;

  public TrustSummaryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public int excludedCount(Long membershipId, Instant now) {
    Instant since = now.atZone(ZoneOffset.UTC).minusMonths(TrustSummary.WINDOW_MONTHS).toInstant();
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_schedule_item si JOIN visit_request vr ON vr.id ="
                + " si.confirmed_request_id JOIN visit_review r ON r.schedule_item_id = si.id"
                + " WHERE vr.applicant_membership_id = :member AND si.result = 'COMPLETED' AND"
                + " si.actual_visited_at >= :since AND r.status = 'DISPUTED'")
        .param("member", membershipId)
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  public TrustSummary summarize(Long membershipId, Instant now) {
    Instant since = now.atZone(ZoneOffset.UTC).minusMonths(TrustSummary.WINDOW_MONTHS).toInstant();
    List<Row> rows =
        jdbcClient
            .sql(
                "SELECT vr.approval_id, r.status, r.punctuality, r.advance_contact,"
                    + " r.as_requested FROM visit_schedule_item si JOIN visit_request vr ON"
                    + " vr.id = si.confirmed_request_id LEFT JOIN visit_review r ON"
                    + " r.schedule_item_id = si.id WHERE vr.applicant_membership_id = :member"
                    + " AND si.result = 'COMPLETED' AND si.actual_visited_at >= :since")
            .param("member", membershipId)
            .param("since", Timestamp.from(since))
            .query(
                (rs, rowNum) ->
                    new Row(
                        rs.getLong("approval_id"),
                        rs.getString("status"),
                        rs.getString("punctuality"),
                        rs.getString("advance_contact"),
                        rs.getString("as_requested")))
            .list();
    int completed = rows.size();
    int evaluated = 0;
    Set<Long> approvals = new HashSet<>();
    int[] answered = new int[3];
    int[] yes = new int[3];
    for (Row row : rows) {
      if (!"ACTIVE".equals(row.status())) {
        continue;
      }
      evaluated++;
      approvals.add(row.approvalId());
      String[] values = {row.punctuality(), row.contact(), row.asRequested()};
      for (int i = 0; i < 3; i++) {
        if ("YES".equals(values[i])) {
          answered[i]++;
          yes[i]++;
        } else if ("NO".equals(values[i])) {
          answered[i]++;
        }
      }
    }
    String[] names = {"PUNCTUALITY", "ADVANCE_CONTACT", "AS_REQUESTED"};
    List<Indicator> indicators = new ArrayList<>();
    int bandedAnswered = 0;
    int bandedYes = 0;
    for (int i = 0; i < 3; i++) {
      String band = null;
      if (answered[i] >= TrustSummary.MIN_ITEM_ANSWERS) {
        band = TrustSummary.bandOf(yes[i], answered[i]);
        bandedAnswered += answered[i];
        bandedYes += yes[i];
      }
      indicators.add(new Indicator(names[i], answered[i], yes[i], band));
    }
    String state;
    if (evaluated == 0) {
      state = TrustSummary.NO_DATA;
    } else if (evaluated < TrustSummary.MIN_EVALUATED_VISITS
        || approvals.size() < TrustSummary.MIN_DISTINCT_APPROVALS
        || bandedAnswered == 0) {
      state = TrustSummary.INSUFFICIENT;
    } else {
      state = TrustSummary.AVAILABLE;
    }
    String overall =
        TrustSummary.AVAILABLE.equals(state)
            ? TrustSummary.bandOf(bandedYes, bandedAnswered)
            : null;
    return new TrustSummary(state, overall, completed, evaluated, approvals.size(), indicators);
  }
}
