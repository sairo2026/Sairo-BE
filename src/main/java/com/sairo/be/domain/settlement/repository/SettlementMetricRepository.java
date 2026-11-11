package com.sairo.be.domain.settlement.repository;

import com.sairo.be.domain.settlement.entity.SettlementMetric;
import com.sairo.be.global.support.TimeRules;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 월말결산 지표를 원본 사건 표에서 직접 계산한다. 지표마다 상세 내역 행을 만드는 질의 하나를 두고 집계 값은 그 행을 세거나 더해 얻는다. 그래서 집계와 상세 내역은 항상
 * 같은 조건이다. 대체된 사건(다른 행의 supersedes 대상)은 모두 뺀다.
 */
@Profile("!migrate")
@Repository
public class SettlementMetricRepository {

  private record Definition(String sql, String order, String aggregate) {}

  private static final String VALID_LIFECYCLE =
      " AND NOT EXISTS (SELECT 1 FROM entity_lifecycle_event sx WHERE sx.supersedes_event_id ="
          + " e.id)";

  private static final String NOT_MERGED =
      " AND NOT EXISTS (SELECT 1 FROM customer_merge_log m WHERE m.merged_customer_id = cu.id"
          + " AND m.executed_at < :to)";

  private static final String PROPERTY_HOLDING =
      "WITH last_event AS (SELECT DISTINCT ON (e.property_id) e.property_id, e.event_type FROM"
          + " entity_lifecycle_event e WHERE e.office_id = :office AND e.entity_type = 'PROPERTY'"
          + " AND e.event_type IN ('CREATED','ARCHIVED','RESTORED') AND e.effective_at < :to"
          + VALID_LIFECYCLE
          + " ORDER BY e.property_id, e.effective_at DESC, e.id DESC), created AS (SELECT"
          + " DISTINCT ON (e.property_id) e.property_id, e.effective_at, e.creation_source FROM"
          + " entity_lifecycle_event e WHERE e.office_id = :office AND e.entity_type = 'PROPERTY'"
          + " AND e.event_type = 'CREATED'"
          + VALID_LIFECYCLE
          + " ORDER BY e.property_id, e.effective_at, e.id) SELECT p.id AS property_id, p.name AS"
          + " property_name, p.address, c.effective_at, COALESCE(c.creation_source = 'IMPORT',"
          + " false) AS is_migrated FROM last_event l JOIN property p ON p.id = l.property_id LEFT"
          + " JOIN created c ON c.property_id = l.property_id WHERE l.event_type <> 'ARCHIVED'";

  private static final String CUSTOMER_HOLDING =
      "WITH last_event AS (SELECT DISTINCT ON (e.customer_id) e.customer_id, e.event_type FROM"
          + " entity_lifecycle_event e WHERE e.office_id = :office AND e.entity_type = 'CUSTOMER'"
          + " AND e.event_type IN ('CREATED','ARCHIVED','RESTORED') AND e.effective_at < :to"
          + VALID_LIFECYCLE
          + " ORDER BY e.customer_id, e.effective_at DESC, e.id DESC), created AS (SELECT"
          + " DISTINCT ON (e.customer_id) e.customer_id, e.effective_at, e.creation_source FROM"
          + " entity_lifecycle_event e WHERE e.office_id = :office AND e.entity_type = 'CUSTOMER'"
          + " AND e.event_type = 'CREATED'"
          + VALID_LIFECYCLE
          + " ORDER BY e.customer_id, e.effective_at, e.id) SELECT cu.id AS customer_id, cu.name"
          + " AS customer_name, c.effective_at, COALESCE(c.creation_source = 'IMPORT', false) AS"
          + " is_migrated FROM last_event l JOIN customer cu ON cu.id = l.customer_id LEFT JOIN"
          + " created c ON c.customer_id = l.customer_id WHERE l.event_type <> 'ARCHIVED'"
          + NOT_MERGED;

  private static final String PROPERTY_NEW =
      "SELECT p.id AS property_id, p.name AS property_name, p.address, e.effective_at,"
          + " e.creation_source = 'IMPORT' AS is_migrated FROM entity_lifecycle_event e JOIN"
          + " property p ON p.id = e.property_id WHERE e.office_id = :office AND e.entity_type ="
          + " 'PROPERTY' AND e.event_type = 'CREATED' AND e.effective_at >= :from AND"
          + " e.effective_at < :to"
          + VALID_LIFECYCLE;

  private static final String CUSTOMER_NEW =
      "SELECT cu.id AS customer_id, cu.name AS customer_name, e.effective_at,"
          + " e.creation_source = 'IMPORT' AS is_migrated FROM entity_lifecycle_event e JOIN"
          + " customer cu ON cu.id = e.customer_id WHERE e.office_id = :office AND e.entity_type ="
          + " 'CUSTOMER' AND e.event_type = 'CREATED' AND e.effective_at >= :from AND"
          + " e.effective_at < :to"
          + VALID_LIFECYCLE
          + NOT_MERGED;

  private static final String FIRST_INQUIRY =
      "SELECT cc.id AS consultation_id, cu.id AS customer_id, cu.name AS customer_name,"
          + " cc.occurred_at FROM customer_consultation cc JOIN customer cu ON cu.id ="
          + " cc.customer_id WHERE cu.office_id = :office AND cc.consultation_type ="
          + " 'FIRST_INQUIRY' AND cc.occurred_at >= :from AND cc.occurred_at < :to";

  private static final String VISIT_COMPLETED =
      "SELECT si.coordination_id, si.id AS schedule_item_id, vcp.property_id, p.address AS"
          + " property_address, cu.name AS customer_name, h.actual_visited_at FROM"
          + " visit_schedule_item_result_history h JOIN visit_schedule_item si ON si.id ="
          + " h.schedule_item_id JOIN visit_candidate_property vcp ON vcp.id ="
          + " si.candidate_property_id JOIN property p ON p.id = vcp.property_id JOIN"
          + " visit_coordination vc ON vc.id = si.coordination_id JOIN customer cu ON cu.id ="
          + " vc.customer_id WHERE si.office_id = :office AND h.result = 'COMPLETED' AND"
          + " h.actual_visited_at >= :from AND h.actual_visited_at < :to AND NOT EXISTS (SELECT 1"
          + " FROM visit_schedule_item_result_history hx WHERE hx.supersedes_history_id = h.id)";

  private static final String COORDINATION_ENDED =
      "WITH valid AS (SELECT h.id, h.coordination_id, h.to_status, h.effective_at FROM"
          + " visit_coordination_status_history h JOIN visit_coordination vc ON vc.id ="
          + " h.coordination_id WHERE vc.office_id = :office AND NOT EXISTS (SELECT 1 FROM"
          + " visit_coordination_status_history hx WHERE hx.supersedes_history_id = h.id)), latest"
          + " AS (SELECT DISTINCT ON (coordination_id) coordination_id, to_status FROM valid ORDER"
          + " BY coordination_id, effective_at DESC, id DESC), first_final AS (SELECT"
          + " coordination_id, min(effective_at) AS ended_at FROM valid WHERE to_status IN"
          + " ('VISIT_COMPLETED','VISIT_INCOMPLETE') GROUP BY coordination_id) SELECT"
          + " f.coordination_id, cu.name AS customer_name, f.ended_at, l.to_status AS end_type"
          + " FROM first_final f JOIN latest l ON l.coordination_id = f.coordination_id JOIN"
          + " visit_coordination vc ON vc.id = f.coordination_id JOIN customer cu ON cu.id ="
          + " vc.customer_id WHERE l.to_status IN ('VISIT_COMPLETED','VISIT_INCOMPLETE') AND"
          + " f.ended_at >= :from AND f.ended_at < :to";

  private static final String CONTRACT_HISTORY =
      "WITH ch AS (SELECT h.id, h.contract_id, h.to_status, h.effective_at FROM"
          + " contract_status_history h JOIN contract c ON c.id = h.contract_id WHERE c.office_id"
          + " = :office AND h.track = 'CONTRACT' AND NOT EXISTS (SELECT 1 FROM"
          + " contract_status_history hx WHERE hx.supersedes_history_id = h.id)), signed AS"
          + " (SELECT contract_id, min(effective_at) AS signed_at FROM ch WHERE to_status ="
          + " 'SIGNED' GROUP BY contract_id), state AS (SELECT DISTINCT ON (contract_id)"
          + " contract_id, to_status FROM ch WHERE effective_at < :to ORDER BY contract_id,"
          + " effective_at DESC, id DESC)";

  private static final String VALID_STATE =
      " AND st.to_status IN ('SIGNED','ENDED') AND c.duplicate_excluded_at IS NULL";

  private static final String CONTRACT_SIGNED_ROWS =
      CONTRACT_HISTORY
          + " SELECT c.id AS contract_id, c.property_id, p.address AS property_address,"
          + " c.deal_type, c.signed_date, c.balance_due_date, c.balance_due_date_pending_reason"
          + " FROM signed s JOIN state st ON st.contract_id = s.contract_id JOIN contract c ON"
          + " c.id = s.contract_id JOIN property p ON p.id = c.property_id WHERE s.signed_at >="
          + " :from AND s.signed_at < :to"
          + VALID_STATE;

  private static final String LEASE_MATURITY_ROWS =
      CONTRACT_HISTORY
          + " SELECT c.id AS contract_id, c.property_id, p.address AS property_address,"
          + " c.lease_end_date FROM contract c JOIN state st ON st.contract_id = c.id JOIN"
          + " property p ON p.id = c.property_id WHERE c.office_id = :office AND c.deal_type IN"
          + " ('JEONSE','MONTHLY') AND c.lease_end_date BETWEEN :firstDay AND :lastDay"
          + VALID_STATE;

  private static final String FEE_REVISION =
      " rev AS (SELECT DISTINCT ON (r.fee_id) r.fee_id, r.agreed_fee_amount, r.total_amount"
          + " FROM contract_fee_revision r JOIN contract_fee f ON f.id = r.fee_id JOIN contract c"
          + " ON c.id = f.contract_id WHERE c.office_id = :office AND r.effective_date <="
          + " :cutoffDay AND NOT EXISTS (SELECT 1 FROM contract_fee_revision rx WHERE"
          + " rx.supersedes_revision_id = r.id) ORDER BY r.fee_id, r.effective_date DESC, r.id"
          + " DESC)";

  private static final String AGREED_FEE_ROWS =
      CONTRACT_HISTORY
          + ","
          + FEE_REVISION
          + " SELECT f.id AS fee_id, c.id AS contract_id, p.address AS property_address, cp.name"
          + " AS payer_name, c.balance_due_date, rev.agreed_fee_amount FROM contract_fee f JOIN"
          + " rev ON rev.fee_id = f.id JOIN contract c ON c.id = f.contract_id JOIN state st ON"
          + " st.contract_id = c.id JOIN property p ON p.id = c.property_id JOIN contract_party cp"
          + " ON cp.id = f.payer_party_id WHERE c.balance_due_date BETWEEN :firstDay AND"
          + " :lastDay"
          + VALID_STATE;

  private static final String VALID_TRANSACTION =
      " AND t.transaction_type IN ('RECEIPT','REFUND') AND NOT EXISTS (SELECT 1 FROM"
          + " contract_fee_transaction tx WHERE tx.supersedes_transaction_id = t.id)";

  private static final String RECEIVED_ROWS =
      "SELECT t.id AS transaction_id, f.id AS fee_id, c.id AS contract_id, p.address AS"
          + " property_address, cp.name AS payer_name, t.transaction_type, t.amount,"
          + " t.actual_date, CASE WHEN t.transaction_type = 'RECEIPT' THEN t.amount ELSE"
          + " -t.amount END AS net_amount FROM contract_fee_transaction t JOIN contract_fee f ON"
          + " f.id = t.fee_id JOIN contract c ON c.id = f.contract_id JOIN property p ON p.id ="
          + " c.property_id JOIN contract_party cp ON cp.id = f.payer_party_id WHERE c.office_id"
          + " = :office AND t.actual_date BETWEEN :firstDay AND :cutoffDay"
          + VALID_TRANSACTION;

  private static final String PAYER_BALANCE =
      "WITH"
          + FEE_REVISION
          + ", billed AS (SELECT f.payer_party_id, sum(rev.total_amount) AS billed FROM"
          + " contract_fee f JOIN rev ON rev.fee_id = f.id GROUP BY f.payer_party_id), paid AS"
          + " (SELECT f.payer_party_id, sum(CASE WHEN t.transaction_type = 'RECEIPT' THEN"
          + " t.amount ELSE -t.amount END) AS net FROM contract_fee_transaction t JOIN"
          + " contract_fee f ON f.id = t.fee_id JOIN contract c ON c.id = f.contract_id WHERE"
          + " c.office_id = :office AND t.actual_date <= :cutoffDay"
          + VALID_TRANSACTION
          + " GROUP BY f.payer_party_id), balance AS (SELECT COALESCE(b.payer_party_id,"
          + " pd.payer_party_id) AS payer_party_id, COALESCE(b.billed, 0)::bigint AS"
          + " billed_total, COALESCE(pd.net, 0)::bigint AS net_received_total FROM billed b FULL"
          + " JOIN paid pd ON pd.payer_party_id = b.payer_party_id) SELECT bl.payer_party_id,"
          + " cp.name AS payer_name, cp.side AS payer_type, cp.contract_id, p.address AS"
          + " property_address, bl.billed_total, bl.net_received_total, abs(bl.billed_total -"
          + " bl.net_received_total) AS balance_amount FROM balance bl JOIN contract_party cp ON"
          + " cp.id = bl.payer_party_id JOIN contract c ON c.id = cp.contract_id JOIN property p"
          + " ON p.id = c.property_id WHERE ";

  private static final Map<SettlementMetric, Definition> DEFINITIONS = definitions();

  private final JdbcClient jdbcClient;

  public SettlementMetricRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  private static Map<SettlementMetric, Definition> definitions() {
    Map<SettlementMetric, Definition> map = new EnumMap<>(SettlementMetric.class);
    String count = "count(*)";
    map.put(
        SettlementMetric.propertyCountTotal,
        new Definition(PROPERTY_HOLDING, "property_id", count));
    map.put(
        SettlementMetric.customerCountTotal,
        new Definition(CUSTOMER_HOLDING, "customer_id", count));
    map.put(
        SettlementMetric.propertyCountNew,
        new Definition(PROPERTY_NEW, "effective_at, property_id", count));
    map.put(
        SettlementMetric.propertyCountNewMigrated,
        new Definition(
            "SELECT * FROM (" + PROPERTY_NEW + ") n WHERE n.is_migrated",
            "effective_at, property_id",
            count));
    map.put(
        SettlementMetric.customerCountNew,
        new Definition(CUSTOMER_NEW, "effective_at, customer_id", count));
    map.put(
        SettlementMetric.customerCountNewMigrated,
        new Definition(
            "SELECT * FROM (" + CUSTOMER_NEW + ") n WHERE n.is_migrated",
            "effective_at, customer_id",
            count));
    map.put(
        SettlementMetric.firstInquiryCount,
        new Definition(FIRST_INQUIRY, "occurred_at, consultation_id", count));
    map.put(
        SettlementMetric.visitCompletedCount,
        new Definition(VISIT_COMPLETED, "actual_visited_at, schedule_item_id", count));
    map.put(
        SettlementMetric.visitCompletedPropertyCount,
        new Definition(
            "SELECT property_id, property_address, count(*) AS visit_completed_count FROM ("
                + VISIT_COMPLETED
                + ") v GROUP BY property_id, property_address",
            "property_id",
            count));
    map.put(
        SettlementMetric.coordinationEndedCount,
        new Definition(COORDINATION_ENDED, "ended_at, coordination_id", count));
    map.put(
        SettlementMetric.coordinationVisitCompletedCount,
        new Definition(
            "SELECT * FROM (" + COORDINATION_ENDED + ") e WHERE e.end_type = 'VISIT_COMPLETED'",
            "ended_at, coordination_id",
            count));
    map.put(
        SettlementMetric.coordinationVisitIncompleteCount,
        new Definition(
            "SELECT * FROM (" + COORDINATION_ENDED + ") e WHERE e.end_type = 'VISIT_INCOMPLETE'",
            "ended_at, coordination_id",
            count));
    map.put(
        SettlementMetric.contractSignedCount,
        new Definition(
            "SELECT contract_id, property_id, property_address, deal_type, signed_date FROM ("
                + CONTRACT_SIGNED_ROWS
                + ") s",
            "signed_date NULLS LAST, contract_id",
            count));
    map.put(
        SettlementMetric.contractSignedPropertyCount,
        new Definition(
            "SELECT property_id, property_address, count(*) AS contract_count FROM ("
                + CONTRACT_SIGNED_ROWS
                + ") s GROUP BY property_id, property_address",
            "property_id",
            count));
    map.put(
        SettlementMetric.leaseMaturityCount,
        new Definition(LEASE_MATURITY_ROWS, "lease_end_date, contract_id", count));
    map.put(
        SettlementMetric.leaseMaturityPropertyCount,
        new Definition(
            "SELECT property_id, property_address, count(*) AS contract_count FROM ("
                + LEASE_MATURITY_ROWS
                + ") l GROUP BY property_id, property_address",
            "property_id",
            count));
    map.put(
        SettlementMetric.agreedFeeTotal,
        new Definition(
            AGREED_FEE_ROWS,
            "balance_due_date, fee_id",
            "COALESCE(sum(agreed_fee_amount), 0)::bigint"));
    map.put(
        SettlementMetric.agreedFeeUndatedCount,
        new Definition(
            "SELECT contract_id, property_address, balance_due_date_pending_reason FROM ("
                + CONTRACT_SIGNED_ROWS
                + ") s WHERE s.balance_due_date IS NULL",
            "contract_id",
            count));
    map.put(
        SettlementMetric.actualFeeReceivedTotal,
        new Definition(
            RECEIVED_ROWS, "actual_date, transaction_id", "COALESCE(sum(net_amount), 0)::bigint"));
    map.put(
        SettlementMetric.outstandingAmount,
        new Definition(
            PAYER_BALANCE + "bl.billed_total > bl.net_received_total",
            "payer_party_id",
            "COALESCE(sum(balance_amount), 0)::bigint"));
    map.put(
        SettlementMetric.overcollectedAmount,
        new Definition(
            PAYER_BALANCE + "bl.billed_total < bl.net_received_total",
            "payer_party_id",
            "COALESCE(sum(balance_amount), 0)::bigint"));
    return map;
  }

  /** 지표 하나의 집계 값(건수 또는 금액 합계)이다. */
  public long aggregate(SettlementMetric metric, SettlementWindow window) {
    Definition definition = DEFINITIONS.get(metric);
    Long value =
        bind(
                jdbcClient.sql(
                    "SELECT " + definition.aggregate() + " FROM (" + definition.sql() + ") x"),
                window)
            .query(Long.class)
            .single();
    return value == null ? 0L : value;
  }

  public long countRows(SettlementMetric metric, SettlementWindow window) {
    Definition definition = DEFINITIONS.get(metric);
    Long value =
        bind(jdbcClient.sql("SELECT count(*) FROM (" + definition.sql() + ") x"), window)
            .query(Long.class)
            .single();
    return value == null ? 0L : value;
  }

  /** 상세 내역 한 쪽이다. 열 이름을 낮은 낙타 표기로 바꾸고 시각은 Asia/Seoul 오프셋으로 돌려준다. */
  public List<Map<String, Object>> rows(
      SettlementMetric metric, SettlementWindow window, int limit, long offset) {
    Definition definition = DEFINITIONS.get(metric);
    return bind(
            jdbcClient.sql(
                "SELECT x.* FROM ("
                    + definition.sql()
                    + ") x ORDER BY "
                    + definition.order()
                    + " LIMIT :limit OFFSET :offset"),
            window)
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, rowNum) -> toItem(rs))
        .list();
  }

  private static JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec spec, SettlementWindow window) {
    return spec.param("office", window.officeId())
        .param("from", Timestamp.from(window.from()))
        .param("to", Timestamp.from(window.to()))
        .param("firstDay", Date.valueOf(window.firstDay()))
        .param("lastDay", Date.valueOf(window.lastDay()))
        .param("cutoffDay", Date.valueOf(window.cutoffDay()));
  }

  private static Map<String, Object> toItem(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> item = new LinkedHashMap<>();
    for (int index = 1; index <= meta.getColumnCount(); index++) {
      String column = meta.getColumnLabel(index);
      if (column.equals("net_amount")) {
        continue;
      }
      item.put(camel(column), value(rs.getObject(index)));
    }
    return item;
  }

  private static Object value(Object raw) {
    if (raw instanceof Timestamp timestamp) {
      return TimeRules.toDisplayOffset(timestamp.toInstant());
    }
    if (raw instanceof Date date) {
      return date.toLocalDate();
    }
    if (raw instanceof java.math.BigDecimal decimal) {
      return decimal.longValueExact();
    }
    return raw;
  }

  static String camel(String column) {
    StringBuilder result = new StringBuilder();
    boolean upper = false;
    for (char ch : column.toCharArray()) {
      if (ch == '_') {
        upper = true;
      } else {
        result.append(upper ? Character.toUpperCase(ch) : ch);
        upper = false;
      }
    }
    return result.toString();
  }
}
