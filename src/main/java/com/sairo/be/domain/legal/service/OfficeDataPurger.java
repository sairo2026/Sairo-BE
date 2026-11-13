package com.sairo.be.domain.legal.service;

import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.support.TimeRules;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 종료된 사무소의 반환 기간이 끝난 뒤 업무 데이터 파기 처리기다(파기 큐 대상 종류 OFFICE_DATA). 고객, 매물, 방문 조율, 가져오기, 내보내기, 업무, 알림과 그
 * 하위 행을 참조하는 쪽부터 지운다. 계약, 거래 기록, 구독·결제, 지원 문의, 권리 요청, 사무소와 구성원 소속은 이 처리기가 지우지 않는다. 계약 서류는 보존 기간
 * 종료일이 지난 것만 지운다. 지우려는 행을 아직 다른 행이 외래키로 참조하면(계약 당사자가 가리키는 고객, 계약이 가리키는 매물, 다른 사무소의 방문 신청 등) 그 행은
 * 지우지 않고 남긴다.
 */
@Profile("!migrate")
@Component
public class OfficeDataPurger {

  public static final String TARGET_TYPE = "OFFICE_DATA";
  static final int LOOP_LIMIT = 50;

  private static final String PROP = "(SELECT id FROM property WHERE office_id = :o)";
  private static final String CUST = "(SELECT id FROM customer WHERE office_id = :o)";
  private static final String COORD = "(SELECT id FROM visit_coordination WHERE office_id = :o)";
  private static final String ITEM = "(SELECT id FROM visit_schedule_item WHERE office_id = :o)";
  private static final String REQ = "(SELECT id FROM visit_request WHERE applicant_office_id = :o)";
  private static final String APPR =
      "(SELECT a.id FROM property_access_approval a WHERE a.property_id IN " + PROP + ")";
  private static final String COND =
      "(SELECT id FROM visit_approver_condition WHERE approval_id IN " + APPR + ")";
  private static final String ALINK =
      "(SELECT id FROM visit_approver_link WHERE coordination_id IN " + COORD + ")";
  private static final String CLINK =
      "(SELECT id FROM visit_customer_link WHERE coordination_id IN " + COORD + ")";
  private static final String SEL =
      "(SELECT id FROM visit_request_selection WHERE request_id IN " + REQ + ")";
  private static final String NOTIF = "(SELECT id FROM notification WHERE office_id = :o)";
  private static final String MAPV = "(SELECT id FROM import_mapping_version WHERE office_id = :o)";
  private static final String CONS =
      "(SELECT id FROM customer_consultation WHERE customer_id IN " + CUST + ")";
  private static final String DEM =
      "(SELECT id FROM customer_demand_condition WHERE customer_id IN " + CUST + ")";
  private static final String GRANT =
      "(SELECT id FROM visit_property_apply_grant WHERE owner_office_id = :o OR grantee_office_id"
          + " = :o)";

  /** 지울 표와 그 사무소 소유 조건이다. 파일 열이 있으면 지운 행의 파일 키를 돌려받아 저장소에서 지운다. */
  record Step(String table, String where, String fileColumn) {}

  /** 참조하는 쪽을 먼저 지우는 순서다. 참조 순서는 카탈로그 시험이 지킨다. */
  static final List<Step> STEPS =
      List.of(
          new Step(
              "contract_document",
              "contract_id IN (SELECT id FROM contract WHERE office_id = :o) AND"
                  + " retention_end_date IS NOT NULL AND retention_end_date < :today",
              "object_key"),
          new Step("approver_auth_event", "approval_id IN " + APPR, null),
          new Step("approver_condition_bulk_item", "approval_id IN " + APPR, null),
          new Step("consent_guest_notice_ack", "office_id = :o", null),
          new Step("customer_consultation_revision", "consultation_id IN " + CONS, null),
          new Step("customer_demand_region", "demand_condition_id IN " + DEM, null),
          new Step(
              "customer_merge_log",
              "primary_customer_id IN " + CUST + " OR merged_customer_id IN " + CUST,
              null),
          new Step("customer_property_interest", "office_id = :o", null),
          new Step("export_job", "office_id = :o", "file_object_key"),
          new Step("import_mapping_item", "mapping_version_id IN " + MAPV, null),
          new Step("import_record_link", "office_id = :o", null),
          new Step("import_row_result", "office_id = :o", null),
          new Step("import_support_request", "office_id = :o", null),
          new Step("notification_delivery", "notification_id IN " + NOTIF, null),
          new Step("office_export_table_policy", "office_id = :o", null),
          new Step("property_access_time_slot", "approval_id IN " + APPR, null),
          new Step("property_field_value", "property_id IN " + PROP, null),
          new Step("property_offer", "property_id IN " + PROP, null),
          new Step("property_owner_occupant", "office_id = :o", null),
          new Step("property_photo", "property_id IN " + PROP, "object_key"),
          new Step("task", "office_id = :o", null),
          new Step("visit_approver_condition_date_rule", "condition_id IN " + COND, null),
          new Step("visit_approver_condition_window", "condition_id IN " + COND, null),
          new Step("visit_approver_time_slot", "approver_link_id IN " + ALINK, null),
          new Step("visit_assistant_notice_log", "schedule_item_id IN " + ITEM, null),
          new Step("visit_confirmation_delivery", "coordination_id IN " + COORD, null),
          new Step("visit_coordination_status_history", "coordination_id IN " + COORD, null),
          new Step("visit_customer_time_slot", "customer_link_id IN " + CLINK, null),
          new Step("visit_preferred_broker", "approval_id IN " + APPR, null),
          new Step("visit_property_apply_grant_event", "grant_id IN " + GRANT, null),
          new Step("visit_request_notice", "request_id IN " + REQ, null),
          new Step("visit_request_revalidation", "request_id IN " + REQ, null),
          new Step("visit_request_status_history", "request_id IN " + REQ, null),
          new Step("visit_review", "request_id IN " + REQ, null),
          new Step("visit_schedule_item_result_history", "schedule_item_id IN " + ITEM, null),
          new Step(
              "visit_selection_trust_snapshot",
              "selection_id IN " + SEL + " OR compared_request_id IN " + REQ,
              null),
          new Step("approver_profile_approval", "approval_id IN " + APPR, null),
          new Step("visit_request_selection", "request_id IN " + REQ, null),
          new Step("visit_request", "applicant_office_id = :o", null),
          new Step("visit_schedule_item", "office_id = :o", null),
          new Step("visit_candidate_property", "office_id = :o", null),
          new Step("visit_approver_link", "coordination_id IN " + COORD, null),
          new Step("visit_customer_link", "coordination_id IN " + COORD, null),
          new Step("visit_property_apply_grant", "id IN " + GRANT, null),
          new Step("visit_coordination", "office_id = :o", null),
          new Step("visit_approver_condition", "approval_id IN " + APPR, null),
          new Step("visit_approver_property_link", "approval_id IN " + APPR, null),
          new Step("property_access_approval", "id IN " + APPR, null),
          new Step("customer_consultation", "customer_id IN " + CUST, null),
          new Step("customer_demand_condition", "customer_id IN " + CUST, null),
          new Step("import_mapping_version", "office_id = :o", null),
          new Step("import_batch", "office_id = :o", null),
          new Step("notification", "office_id = :o", null),
          new Step("property", "office_id = :o", null),
          new Step("customer", "office_id = :o", null));

  /** 순환 참조를 끊기 위해 지우기 전에 비우는 연결이다. */
  static final List<String> DETACH =
      List.of(
          "UPDATE visit_schedule_item SET confirmed_request_id = NULL WHERE office_id = :o AND"
              + " confirmed_request_id IS NOT NULL",
          "UPDATE visit_request SET replaces_schedule_item_id = NULL WHERE applicant_office_id ="
              + " :o AND replaces_schedule_item_id IS NOT NULL");

  private static final String DETACH_BEFORE = "visit_request";

  private record Reference(
      String childTable, List<String> childColumns, List<String> parentColumns) {}

  private final JdbcClient jdbcClient;
  private final ObjectStorage objectStorage;

  public OfficeDataPurger(JdbcClient jdbcClient, ObjectStorage objectStorage) {
    this.jdbcClient = jdbcClient;
    this.objectStorage = objectStorage;
  }

  public boolean supports(String targetType) {
    return TARGET_TYPE.equals(targetType);
  }

  /** 계획 건수다. 사무소가 가진 주요 업무 행 수이며 실제로 지우는 행 수는 실행 결과가 다르다. */
  public long dueCount(long officeId) {
    return jdbcClient
        .sql(
            "SELECT (SELECT count(*) FROM customer WHERE office_id = :o) + (SELECT count(*) FROM"
                + " property WHERE office_id = :o) + (SELECT count(*) FROM visit_coordination WHERE"
                + " office_id = :o) + (SELECT count(*) FROM task WHERE office_id = :o) + (SELECT"
                + " count(*) FROM import_batch WHERE office_id = :o) + (SELECT count(*) FROM"
                + " export_job WHERE office_id = :o) + (SELECT count(*) FROM notification WHERE"
                + " office_id = :o)")
        .param("o", officeId)
        .query(Long.class)
        .single();
  }

  /** 지금 실행하면 안 되는 이유다. 종료되지 않은 사무소와 반환권한이 살아 있는 사무소는 지우지 않는다. */
  public List<String> holdReasons(long officeId, Instant now) {
    List<String> reasons = new ArrayList<>();
    boolean closed =
        jdbcClient
                .sql("SELECT count(*) FROM office WHERE id = :o AND closed_at IS NOT NULL")
                .param("o", officeId)
                .query(Long.class)
                .single()
            > 0;
    if (!closed) {
      reasons.add("종료되지 않은 사무소");
    }
    boolean returnActive =
        jdbcClient
                .sql(
                    "SELECT count(*) FROM return_grant WHERE office_id = :o AND revoked_at IS NULL"
                        + " AND expires_at > :now")
                .param("o", officeId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single()
            > 0;
    if (returnActive) {
      reasons.add("반환권한 유효 기간 중");
    }
    return reasons;
  }

  /** 사무소 업무 데이터를 지우고 지운 행 수를 돌려준다. 호출한 쪽 트랜잭션에서 실행한다. */
  public long execute(long officeId, Instant now) {
    Date today = Date.valueOf(TimeRules.displayDate(now));
    Map<String, List<Reference>> references = new HashMap<>();
    List<String> files = new ArrayList<>();
    long deleted = 0;
    for (Step step : STEPS) {
      if (step.table().equals(DETACH_BEFORE)) {
        for (String detach : DETACH) {
          jdbcClient.sql(detach).param("o", officeId).update();
        }
      }
      List<Reference> refs = references.computeIfAbsent(step.table(), this::blockingReferences);
      boolean selfReferenced = refs.stream().anyMatch(r -> r.childTable().equals(step.table()));
      for (int round = 0; round < (selfReferenced ? LOOP_LIMIT : 1); round++) {
        long count = deleteStep(step, refs, officeId, today, files);
        deleted += count;
        if (count == 0) {
          break;
        }
      }
    }
    for (String key : files) {
      objectStorage.delete(key);
    }
    return deleted;
  }

  private long deleteStep(
      Step step, List<Reference> refs, long officeId, Date today, List<String> files) {
    String sql =
        "DELETE FROM " + step.table() + " WHERE (" + step.where() + ")" + guard(step.table(), refs);
    if (step.fileColumn() == null) {
      return jdbcClient.sql(sql).param("o", officeId).param("today", today).update();
    }
    List<String> keys =
        jdbcClient
            .sql(sql + " RETURNING " + step.fileColumn())
            .param("o", officeId)
            .param("today", today)
            .query(String.class)
            .list();
    for (String key : keys) {
      if (key != null) {
        files.add(key);
      }
    }
    return keys.size();
  }

  static String guard(String table, List<Reference> refs) {
    StringBuilder sb = new StringBuilder();
    for (Reference ref : refs) {
      sb.append(" AND NOT EXISTS (SELECT 1 FROM ").append(ref.childTable()).append(" ref WHERE ");
      for (int i = 0; i < ref.childColumns().size(); i++) {
        if (i > 0) {
          sb.append(" AND ");
        }
        sb.append("ref.")
            .append(ref.childColumns().get(i))
            .append(" = ")
            .append(table)
            .append('.')
            .append(ref.parentColumns().get(i));
      }
      sb.append(')');
    }
    return sb.toString();
  }

  /** 이 표를 외래키로 참조하고 삭제가 연쇄되지 않는 표와 열이다. */
  private List<Reference> blockingReferences(String table) {
    return jdbcClient
        .sql(
            """
            SELECT cl.relname AS child,
                   array_to_string(array_agg(a.attname::text ORDER BY k.ord), ',') AS child_cols,
                   array_to_string(array_agg(pa.attname::text ORDER BY k.ord), ',') AS parent_cols
              FROM pg_constraint c
              JOIN pg_class cl ON cl.oid = c.conrelid
              JOIN pg_class pcl ON pcl.oid = c.confrelid
              JOIN pg_namespace n ON n.oid = pcl.relnamespace
              CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(ccol, pcol, ord)
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.ccol
              JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = k.pcol
             WHERE c.contype = 'f' AND pcl.relname = :table AND n.nspname = current_schema()
               AND c.confdeltype IN ('a', 'r')
             GROUP BY c.oid, cl.relname
             ORDER BY cl.relname, c.oid
            """)
        .param("table", table)
        .query(
            (rs, i) ->
                new Reference(
                    rs.getString("child"),
                    List.of(rs.getString("child_cols").split(",")),
                    List.of(rs.getString("parent_cols").split(","))))
        .list();
  }
}
