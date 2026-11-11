package com.sairo.be.domain.settlement.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 결산이 읽는 다른 도메인 표의 조회와 수명 사건 정정 행 추가를 JDBC로 처리한다. */
@Profile("!migrate")
@Repository
public class SettlementSourceRepository {

  public record LifecycleEvent(
      Long id,
      Long officeId,
      String entityType,
      Long propertyId,
      Long customerId,
      String eventType,
      String creationSource,
      Long previousCategoryId,
      Long newCategoryId,
      Instant effectiveAt) {}

  public record EntityState(boolean archived, Long categoryId) {}

  private static final String EVENT_COLUMNS =
      "id, office_id, entity_type, property_id, customer_id, event_type, creation_source,"
          + " previous_category_id, new_category_id, effective_at";

  private final JdbcClient jdbcClient;

  public SettlementSourceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Instant> officeCreatedAt(Long officeId) {
    return jdbcClient
        .sql("SELECT created_at FROM office WHERE id = :office")
        .param("office", officeId)
        .query((rs, rowNum) -> rs.getTimestamp("created_at").toInstant())
        .optional();
  }

  /** 조율의 대체되지 않은 최초 최종결과(방문 완료·미방문) 효력시각이다. */
  public Optional<Instant> firstFinalAt(Long officeId, Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT min(h.effective_at) AS ended_at FROM visit_coordination_status_history h JOIN"
                + " visit_coordination vc ON vc.id = h.coordination_id WHERE vc.office_id ="
                + " :office AND h.coordination_id = :coordination AND h.to_status IN"
                + " ('VISIT_COMPLETED','VISIT_INCOMPLETE') AND NOT EXISTS (SELECT 1 FROM"
                + " visit_coordination_status_history hx WHERE hx.supersedes_history_id = h.id)")
        .param("office", officeId)
        .param("coordination", coordinationId)
        .query(
            (rs, rowNum) -> {
              Timestamp value = rs.getTimestamp("ended_at");
              return value == null ? null : value.toInstant();
            })
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  public Optional<LifecycleEvent> findEvent(Long eventId, Long officeId) {
    return jdbcClient
        .sql(
            "SELECT "
                + EVENT_COLUMNS
                + " FROM entity_lifecycle_event WHERE id = :id AND office_id = :office")
        .param("id", eventId)
        .param("office", officeId)
        .query((rs, rowNum) -> event(rs))
        .optional();
  }

  /** 대상 매물·고객 행을 잠그고 현재 보관 여부와 카테고리를 돌려준다. 없거나 다른 사무소면 빈 값이다. */
  public Optional<EntityState> lockEntity(LifecycleEvent event) {
    boolean property = "PROPERTY".equals(event.entityType());
    String sql =
        property
            ? "SELECT deleted_at IS NOT NULL AS archived, category_id FROM property WHERE id = :id"
                + " AND office_id = :office FOR UPDATE"
            : "SELECT deleted_at IS NOT NULL AS archived, NULL::bigint AS category_id FROM customer"
                + " WHERE id = :id AND office_id = :office FOR UPDATE";
    return jdbcClient
        .sql(sql)
        .param("id", property ? event.propertyId() : event.customerId())
        .param("office", event.officeId())
        .query(
            (rs, rowNum) ->
                new EntityState(rs.getBoolean("archived"), rs.getObject("category_id", Long.class)))
        .optional();
  }

  public boolean isSuperseded(Long eventId) {
    return jdbcClient
        .sql("SELECT EXISTS (SELECT 1 FROM entity_lifecycle_event WHERE supersedes_event_id = :id)")
        .param("id", eventId)
        .query(Boolean.class)
        .single();
  }

  /** 같은 대상의 대체되지 않은 사건을 효력시각 순서로 돌려준다. */
  public List<LifecycleEvent> validEvents(LifecycleEvent target) {
    boolean property = "PROPERTY".equals(target.entityType());
    return jdbcClient
        .sql(
            "SELECT "
                + EVENT_COLUMNS
                + " FROM entity_lifecycle_event e WHERE e.office_id = :office AND "
                + (property ? "e.property_id" : "e.customer_id")
                + " = :entity AND NOT EXISTS (SELECT 1 FROM entity_lifecycle_event sx WHERE"
                + " sx.supersedes_event_id = e.id) ORDER BY e.effective_at, e.id")
        .param("office", target.officeId())
        .param("entity", property ? target.propertyId() : target.customerId())
        .query((rs, rowNum) -> event(rs))
        .list();
  }

  public Long insertCorrection(
      LifecycleEvent original,
      Instant effectiveAt,
      String reason,
      Long membershipId,
      Instant recordedAt) {
    return jdbcClient
        .sql(
            "INSERT INTO entity_lifecycle_event (office_id, entity_type, property_id, customer_id,"
                + " event_type, creation_source, previous_category_id, new_category_id,"
                + " effective_at, recorded_at, recorded_by_membership_id, correction_reason,"
                + " supersedes_event_id) VALUES (:office, :entityType, :property, :customer,"
                + " :eventType, :source, :previous, :next, :effective, :recorded, :member,"
                + " :reason, :supersedes) RETURNING id")
        .param("office", original.officeId())
        .param("entityType", original.entityType())
        .param("property", original.propertyId())
        .param("customer", original.customerId())
        .param("eventType", original.eventType())
        .param("source", original.creationSource())
        .param("previous", original.previousCategoryId())
        .param("next", original.newCategoryId())
        .param("effective", Timestamp.from(effectiveAt))
        .param("recorded", Timestamp.from(recordedAt))
        .param("member", membershipId)
        .param("reason", reason)
        .param("supersedes", original.id())
        .query(Long.class)
        .single();
  }

  private static LifecycleEvent event(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new LifecycleEvent(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("entity_type"),
        rs.getObject("property_id", Long.class),
        rs.getObject("customer_id", Long.class),
        rs.getString("event_type"),
        rs.getString("creation_source"),
        rs.getObject("previous_category_id", Long.class),
        rs.getObject("new_category_id", Long.class),
        rs.getTimestamp("effective_at").toInstant());
  }
}
