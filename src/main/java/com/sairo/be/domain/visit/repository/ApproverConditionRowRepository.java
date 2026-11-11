package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.service.ConditionSpec.DateRule;
import com.sairo.be.domain.visit.service.ConditionSpec.Limit;
import com.sairo.be.domain.visit.service.ConditionSpec.LimitKind;
import com.sairo.be.domain.visit.service.ConditionSpec.RuleItem;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.domain.visit.service.ConditionSpec.WindowType;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ApproverConditionRowRepository {

  private final JdbcClient jdbcClient;

  public ApproverConditionRowRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void saveWindows(Long conditionId, List<Window> windows) {
    for (Window window : windows) {
      jdbcClient
          .sql(
              "INSERT INTO visit_approver_condition_window (condition_id, window_type, weekday,"
                  + " window_date, starts_time, ends_time, is_exception) VALUES (:condition,"
                  + " :type, :weekday, :date, CAST(:starts AS time), CAST(:ends AS time),"
                  + " :exception)")
          .param("condition", conditionId)
          .param("type", window.type().name())
          .param("weekday", window.weekday(), java.sql.Types.SMALLINT)
          .param(
              "date",
              window.date() == null ? null : Date.valueOf(window.date()),
              java.sql.Types.DATE)
          .param("starts", clock(window.startMinute()))
          .param("ends", clock(window.endMinute()))
          .param("exception", window.exception())
          .update();
    }
  }

  public void saveDateRules(Long conditionId, List<DateRule> rules) {
    for (DateRule rule : rules) {
      for (RuleItem item : RuleItem.values()) {
        Limit limit = rule.item(item);
        if (limit == null) {
          continue;
        }
        jdbcClient
            .sql(
                "INSERT INTO visit_approver_condition_date_rule (condition_id, rule_date,"
                    + " rule_item, limit_kind, limit_value) VALUES (:condition, :date, :item,"
                    + " :kind, :value)")
            .param("condition", conditionId)
            .param("date", Date.valueOf(rule.ruleDate()))
            .param("item", item.name())
            .param("kind", limit.kind().name())
            .param("value", limit.value(), java.sql.Types.INTEGER)
            .update();
      }
    }
  }

  public List<Window> windowsOf(Long conditionId) {
    return jdbcClient
        .sql(
            "SELECT window_type, weekday, window_date,"
                + " (EXTRACT(EPOCH FROM starts_time) / 60)::int AS start_min,"
                + " (EXTRACT(EPOCH FROM ends_time) / 60)::int AS end_min, is_exception"
                + " FROM visit_approver_condition_window WHERE condition_id = :condition"
                + " ORDER BY window_type DESC, weekday, window_date, starts_time, id")
        .param("condition", conditionId)
        .query(
            (rs, rowNum) -> {
              int weekday = rs.getInt("weekday");
              Integer weekdayValue = rs.wasNull() ? null : weekday;
              Date date = rs.getDate("window_date");
              return new Window(
                  WindowType.valueOf(rs.getString("window_type")),
                  weekdayValue,
                  date == null ? null : date.toLocalDate(),
                  rs.getInt("start_min"),
                  rs.getInt("end_min"),
                  rs.getBoolean("is_exception"));
            })
        .list();
  }

  public List<DateRule> dateRulesOf(Long conditionId) {
    Map<LocalDate, Map<RuleItem, Limit>> byDate = new TreeMap<>();
    jdbcClient
        .sql(
            "SELECT rule_date, rule_item, limit_kind, limit_value FROM"
                + " visit_approver_condition_date_rule WHERE condition_id = :condition")
        .param("condition", conditionId)
        .query(
            (rs, rowNum) -> {
              int value = rs.getInt("limit_value");
              Integer limitValue = rs.wasNull() ? null : value;
              byDate
                  .computeIfAbsent(
                      rs.getDate("rule_date").toLocalDate(), d -> new EnumMap<>(RuleItem.class))
                  .put(
                      RuleItem.valueOf(rs.getString("rule_item")),
                      new Limit(LimitKind.valueOf(rs.getString("limit_kind")), limitValue));
              return null;
            })
        .list();
    List<DateRule> rules = new ArrayList<>();
    byDate.forEach(
        (date, items) ->
            rules.add(
                new DateRule(
                    date,
                    items.get(RuleItem.MAX_VISITS_PER_DAY),
                    items.get(RuleItem.MIN_GAP),
                    items.get(RuleItem.MIN_LEAD),
                    items.get(RuleItem.SINGLE_VISIT_MAX))));
    return rules;
  }

  private static String clock(int minutes) {
    return String.format("%02d:%02d", minutes / 60, minutes % 60);
  }
}
