package com.sairo.be.domain.customer.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class CustomerReadRepository {

  private static final String CLOSURE =
      "WITH RECURSIVE closure(root_id, member_id) AS (SELECT c.id, c.id FROM customer c WHERE"
          + " c.office_id = :officeId AND NOT EXISTS (SELECT 1 FROM customer_merge_log l WHERE"
          + " l.merged_customer_id = c.id) UNION ALL SELECT cl.root_id, l.merged_customer_id FROM"
          + " closure cl JOIN customer_merge_log l ON l.primary_customer_id = cl.member_id) ";

  private final JdbcClient jdbcClient;

  public CustomerReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public record ListFilter(String keyword, String role, boolean archived) {}

  public record ListRow(
      long customerId,
      long officeId,
      String name,
      boolean corporation,
      String phone,
      Instant deletedAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record RoleFlags(boolean demand, boolean owner, boolean occupant) {}

  public record RelatedProperty(
      long propertyId,
      String propertyName,
      String role,
      LocalDate startDate,
      LocalDate endDate,
      Long linkId,
      Long interestId,
      String note,
      boolean confirmed) {}

  public record ConditionRow(
      long conditionId,
      long customerId,
      String dealType,
      Long categoryId,
      Long priceMin,
      Long priceMax,
      Long depositMin,
      Long depositMax,
      Long rentMin,
      Long rentMax,
      BigDecimal areaMin,
      BigDecimal areaMax,
      LocalDate moveInDate,
      Instant createdAt) {}

  public record RegionRow(long regionId, long conditionId, String regionName) {}

  public record ContractRow(
      long contractId,
      long propertyId,
      String propertyName,
      String dealType,
      String contractStatus,
      LocalDate signedDate,
      LocalDate leaseStartDate,
      LocalDate leaseEndDate) {}

  public record ConsultationRow(
      long consultationId,
      long customerId,
      String consultationType,
      Instant occurredAt,
      String content,
      long authorMembershipId,
      String authorName,
      boolean authorWithdrawn,
      Integer authorAliasNo,
      int revisionCount,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record VisitRow(
      long coordinationId,
      String status,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      int candidatePropertyCount) {}

  public record TaskRow(long taskId, String title, LocalDate dueDate, String status) {}

  public record DuplicateRow(
      long customerId,
      String name,
      boolean corporation,
      String phone,
      Instant deletedAt,
      long managingMembershipId) {}

  public record CandidateRow(
      long propertyId,
      String name,
      String dealType,
      Long depositAmount,
      Long salePrice,
      Long monthlyRentAmount) {}

  public long finalCustomerId(long customerId) {
    long current = customerId;
    for (int depth = 0; depth < 1000; depth++) {
      Long next =
          jdbcClient
              .sql(
                  "SELECT primary_customer_id FROM customer_merge_log WHERE merged_customer_id ="
                      + " :id")
              .param("id", current)
              .query(Long.class)
              .optional()
              .orElse(null);
      if (next == null) {
        return current;
      }
      current = next;
    }
    throw new IllegalStateException("병합 이력이 순환합니다.");
  }

  public boolean isMerged(long customerId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql("SELECT EXISTS (SELECT 1 FROM customer_merge_log WHERE merged_customer_id = :id)")
            .param("id", customerId)
            .query(Boolean.class)
            .single());
  }

  public List<Long> mergedSet(long finalCustomerId) {
    return jdbcClient
        .sql(
            "WITH RECURSIVE s(id) AS (SELECT CAST(:id AS bigint) UNION ALL SELECT"
                + " l.merged_customer_id FROM customer_merge_log l JOIN s ON l.primary_customer_id"
                + " = s.id) SELECT id FROM s ORDER BY id")
        .param("id", finalCustomerId)
        .query(Long.class)
        .list();
  }

  public List<ListRow> findListRows(
      long officeId, ListFilter filter, LocalDate today, int limit, long offset) {
    Map<String, Object> params = new HashMap<>();
    String where = listWhere(officeId, filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            CLOSURE
                + "SELECT c.id, c.office_id, c.name, c.is_corporation, c.phone, c.deleted_at,"
                + " c.created_at, c.updated_at, c.version FROM customer c"
                + where
                + " ORDER BY c.updated_at DESC, c.id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(
            (rs, rowNum) ->
                new ListRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    rs.getString(5),
                    instant(rs, 6),
                    instant(rs, 7),
                    instant(rs, 8),
                    rs.getLong(9)))
        .list();
  }

  public long countList(long officeId, ListFilter filter) {
    Map<String, Object> params = new HashMap<>();
    String where = listWhere(officeId, filter, params);
    Long count =
        jdbcClient
            .sql(CLOSURE + "SELECT count(*) FROM customer c" + where)
            .params(params)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  private String listWhere(long officeId, ListFilter filter, Map<String, Object> params) {
    params.put("officeId", officeId);
    StringBuilder where =
        new StringBuilder(
            " WHERE c.office_id = :officeId AND NOT EXISTS (SELECT 1 FROM customer_merge_log l"
                + " WHERE l.merged_customer_id = c.id)");
    where.append(filter.archived() ? " AND c.deleted_at IS NOT NULL" : " AND c.deleted_at IS NULL");
    if (filter.keyword() != null) {
      params.put("kw", "%" + escapeLike(filter.keyword().toLowerCase()) + "%");
      String digits = filter.keyword().replaceAll("\\D", "");
      String phoneClause = "";
      if (!digits.isEmpty()) {
        params.put("kwDigits", "%" + digits + "%");
        phoneClause =
            " OR regexp_replace(coalesce(%s.phone, ''), '\\D', '', 'g') LIKE :kwDigits"
                + " OR regexp_replace(coalesce(%s.secondary_phone, ''), '\\D', '', 'g') LIKE"
                + " :kwDigits";
      }
      String direct =
          "lower(%s.name) LIKE :kw ESCAPE '\\' OR lower(coalesce(%s.phone, '')) LIKE :kw ESCAPE"
              + " '\\'"
              + phoneClause;
      where.append(" AND (");
      where.append(direct.replace("%s", "c"));
      where.append(
          " OR EXISTS (SELECT 1 FROM closure cl JOIN customer m ON m.id = cl.member_id WHERE"
              + " cl.root_id = c.id AND m.id <> c.id AND (");
      where.append(direct.replace("%s", "m"));
      where.append(")))");
    }
    if (filter.role() != null) {
      where.append(roleClause(filter.role()));
    }
    return where.toString();
  }

  private static String roleClause(String role) {
    String demand =
        "EXISTS (SELECT 1 FROM closure cl JOIN customer_demand_condition d ON d.customer_id ="
            + " cl.member_id WHERE cl.root_id = c.id)";
    String owner =
        "EXISTS (SELECT 1 FROM closure cl JOIN property_owner_occupant oo ON oo.customer_id ="
            + " cl.member_id WHERE cl.root_id = c.id AND oo.role = 'OWNER')";
    String occupant =
        "EXISTS (SELECT 1 FROM closure cl JOIN property_owner_occupant oo ON oo.customer_id ="
            + " cl.member_id WHERE cl.root_id = c.id AND oo.role = 'OCCUPANT')";
    return switch (role) {
      case "DEMAND" -> " AND " + demand;
      case "OWNER" -> " AND " + owner;
      case "OCCUPANT" -> " AND " + occupant;
      default -> " AND NOT " + demand + " AND NOT " + owner + " AND NOT " + occupant;
    };
  }

  public Map<Long, RoleFlags> findRoles(long officeId, Collection<Long> customerIds) {
    Map<Long, RoleFlags> roles = new LinkedHashMap<>();
    if (customerIds.isEmpty()) {
      return roles;
    }
    jdbcClient
        .sql(
            CLOSURE
                + "SELECT c.id, EXISTS (SELECT 1 FROM closure cl JOIN customer_demand_condition d"
                + " ON d.customer_id = cl.member_id WHERE cl.root_id = c.id), EXISTS (SELECT 1"
                + " FROM closure cl JOIN property_owner_occupant oo ON oo.customer_id ="
                + " cl.member_id WHERE cl.root_id = c.id AND oo.role = 'OWNER'), EXISTS (SELECT 1"
                + " FROM closure cl JOIN property_owner_occupant oo ON oo.customer_id ="
                + " cl.member_id WHERE cl.root_id = c.id AND oo.role = 'OCCUPANT') FROM customer c"
                + " WHERE c.id IN (:ids)")
        .param("officeId", officeId)
        .param("ids", customerIds)
        .query(
            (rs, rowNum) ->
                Map.entry(
                    rs.getLong(1),
                    new RoleFlags(rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4))))
        .list()
        .forEach(entry -> roles.put(entry.getKey(), entry.getValue()));
    return roles;
  }

  public List<RelatedProperty> findRelatedProperties(Collection<Long> customerIds) {
    List<RelatedProperty> rows = new ArrayList<>();
    rows.addAll(
        jdbcClient
            .sql(
                "SELECT oo.property_id, p.name, oo.role, oo.start_date, oo.end_date, oo.id,"
                    + " (oo.confirmed_at IS NOT NULL) FROM property_owner_occupant oo JOIN"
                    + " property p ON p.id = oo.property_id WHERE oo.customer_id IN (:ids)")
            .param("ids", customerIds)
            .query(
                (rs, rowNum) ->
                    new RelatedProperty(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class),
                        rs.getLong(6),
                        null,
                        null,
                        rs.getBoolean(7)))
            .list());
    rows.addAll(
        jdbcClient
            .sql(
                "SELECT i.property_id, p.name, i.start_date, i.end_date, i.id, i.note FROM"
                    + " customer_property_interest i JOIN property p ON p.id = i.property_id WHERE"
                    + " i.customer_id IN (:ids)")
            .param("ids", customerIds)
            .query(
                (rs, rowNum) ->
                    new RelatedProperty(
                        rs.getLong(1),
                        rs.getString(2),
                        "INTEREST",
                        rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class),
                        null,
                        rs.getLong(5),
                        rs.getString(6),
                        false))
            .list());
    rows.sort(
        (a, b) -> {
          int byStart = b.startDate().compareTo(a.startDate());
          if (byStart != 0) {
            return byStart;
          }
          long aId = a.linkId() != null ? a.linkId() : a.interestId();
          long bId = b.linkId() != null ? b.linkId() : b.interestId();
          return Long.compare(bId, aId);
        });
    return rows;
  }

  public List<ConditionRow> findConditions(Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT id, customer_id, deal_type, category_id, price_min, price_max, deposit_min,"
                + " deposit_max, rent_min, rent_max, area_min, area_max, move_in_date, created_at"
                + " FROM customer_demand_condition WHERE customer_id IN (:ids) ORDER BY id")
        .param("ids", customerIds)
        .query(this::mapCondition)
        .list();
  }

  public java.util.Optional<ConditionRow> findCondition(long conditionId) {
    return jdbcClient
        .sql(
            "SELECT id, customer_id, deal_type, category_id, price_min, price_max,"
                + " deposit_min, deposit_max, rent_min, rent_max, area_min, area_max,"
                + " move_in_date, created_at FROM customer_demand_condition WHERE id = :id")
        .param("id", conditionId)
        .query(this::mapCondition)
        .optional();
  }

  private ConditionRow mapCondition(ResultSet rs, int rowNum) throws SQLException {
    return new ConditionRow(
        rs.getLong(1),
        rs.getLong(2),
        rs.getString(3),
        (Long) rs.getObject(4),
        (Long) rs.getObject(5),
        (Long) rs.getObject(6),
        (Long) rs.getObject(7),
        (Long) rs.getObject(8),
        (Long) rs.getObject(9),
        (Long) rs.getObject(10),
        rs.getBigDecimal(11),
        rs.getBigDecimal(12),
        rs.getObject(13, LocalDate.class),
        instant(rs, 14));
  }

  public List<RegionRow> findRegions(Collection<Long> conditionIds) {
    if (conditionIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            "SELECT id, demand_condition_id, region_name FROM customer_demand_region WHERE"
                + " demand_condition_id IN (:ids) ORDER BY id")
        .param("ids", conditionIds)
        .query((rs, rowNum) -> new RegionRow(rs.getLong(1), rs.getLong(2), rs.getString(3)))
        .list();
  }

  public void replaceRegions(long conditionId, List<String> regions) {
    jdbcClient
        .sql("DELETE FROM customer_demand_region WHERE demand_condition_id = :id")
        .param("id", conditionId)
        .update();
    for (String region : regions) {
      jdbcClient
          .sql(
              "INSERT INTO customer_demand_region (demand_condition_id, region_name) VALUES (:id,"
                  + " :name)")
          .param("id", conditionId)
          .param("name", region)
          .update();
    }
  }

  public List<ContractRow> findContracts(long officeId, Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT DISTINCT ON (c.id) c.id, c.property_id, p.name, c.deal_type,"
                + " c.contract_status, c.signed_date, c.lease_start_date, c.lease_end_date FROM"
                + " contract_party cp JOIN contract c ON c.id = cp.contract_id JOIN property p ON"
                + " p.id = c.property_id WHERE cp.customer_id IN (:ids) AND cp.removed_at IS NULL"
                + " AND c.office_id = :officeId ORDER BY c.id")
        .param("ids", customerIds)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new ContractRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getObject(6, LocalDate.class),
                    rs.getObject(7, LocalDate.class),
                    rs.getObject(8, LocalDate.class)))
        .list();
  }

  public List<ConsultationRow> findConsultations(Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT cc.id, cc.customer_id, cc.consultation_type, cc.occurred_at, cc.content,"
                + " cc.author_membership_id, u.name, (u.account_status = 'WITHDRAWN'),"
                + " m.withdrawn_alias_no, (SELECT count(*) FROM customer_consultation_revision r"
                + " WHERE r.consultation_id = cc.id), cc.created_at, cc.updated_at, cc.version"
                + " FROM customer_consultation cc JOIN office_membership m ON m.id ="
                + " cc.author_membership_id JOIN app_user u ON u.id = m.user_id WHERE"
                + " cc.customer_id IN (:ids) ORDER BY cc.occurred_at DESC, cc.id DESC")
        .param("ids", customerIds)
        .query(this::mapConsultation)
        .list();
  }

  public ConsultationRow mapConsultation(ResultSet rs, int rowNum) throws SQLException {
    return new ConsultationRow(
        rs.getLong(1),
        rs.getLong(2),
        rs.getString(3),
        instant(rs, 4),
        rs.getString(5),
        rs.getLong(6),
        rs.getString(7),
        rs.getBoolean(8),
        (Integer) rs.getObject(9),
        rs.getInt(10),
        instant(rs, 11),
        instant(rs, 12),
        rs.getLong(13));
  }

  public List<VisitRow> findVisits(long officeId, Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT vc.id, vc.status, vc.period_start_date, vc.period_end_date, (SELECT count(*)"
                + " FROM visit_candidate_property cp WHERE cp.coordination_id = vc.id) FROM"
                + " visit_coordination vc WHERE vc.customer_id IN (:ids) AND vc.office_id ="
                + " :officeId ORDER BY vc.id DESC")
        .param("ids", customerIds)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new VisitRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getObject(3, LocalDate.class),
                    rs.getObject(4, LocalDate.class),
                    rs.getInt(5)))
        .list();
  }

  public List<TaskRow> findTasks(long officeId, Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT id, title, due_date, status FROM task WHERE related_customer_id IN (:ids) AND"
                + " office_id = :officeId AND status <> 'CANCELLED' ORDER BY due_date, id")
        .param("ids", customerIds)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new TaskRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getObject(3, LocalDate.class),
                    rs.getString(4)))
        .list();
  }

  public boolean isDeletable(Collection<Long> customerIds) {
    List<String> checks =
        List.of(
            "property_owner_occupant",
            "customer_property_interest",
            "customer_demand_condition",
            "customer_consultation",
            "visit_coordination",
            "contract_party",
            "task");
    for (String table : checks) {
      String column = table.equals("task") ? "related_customer_id" : "customer_id";
      Boolean found =
          jdbcClient
              .sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE " + column + " IN (:ids))")
              .param("ids", customerIds)
              .query(Boolean.class)
              .single();
      if (Boolean.TRUE.equals(found)) {
        return false;
      }
    }
    return !Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM customer_merge_log WHERE primary_customer_id IN"
                    + " (:ids) OR merged_customer_id IN (:ids))")
            .param("ids", customerIds)
            .query(Boolean.class)
            .single());
  }

  public void deleteCustomerRows(long customerId) {
    jdbcClient
        .sql("DELETE FROM entity_lifecycle_event WHERE customer_id = :id")
        .param("id", customerId)
        .update();
    jdbcClient.sql("DELETE FROM customer WHERE id = :id").param("id", customerId).update();
  }

  public List<DuplicateRow> findDuplicates(long officeId, String name, String phone) {
    String digits = phone.replaceAll("\\D", "");
    return jdbcClient
        .sql(
            CLOSURE
                + "SELECT DISTINCT c.id, c.name, c.is_corporation, c.phone, c.deleted_at,"
                + " c.managing_membership_id FROM customer c WHERE c.office_id = :officeId AND NOT"
                + " EXISTS (SELECT 1 FROM customer_merge_log l WHERE l.merged_customer_id = c.id)"
                + " AND EXISTS (SELECT 1 FROM closure cl JOIN customer m ON m.id = cl.member_id"
                + " WHERE cl.root_id = c.id AND lower(regexp_replace(m.name, '\\s+', '', 'g')) ="
                + " :name AND (regexp_replace(coalesce(m.phone, ''), '\\D', '', 'g') = :digits OR"
                + " regexp_replace(coalesce(m.secondary_phone, ''), '\\D', '', 'g') = :digits))"
                + " ORDER BY c.id")
        .param("officeId", officeId)
        .param("name", name.replaceAll("\\s+", "").toLowerCase())
        .param("digits", digits)
        .query(
            (rs, rowNum) ->
                new DuplicateRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getBoolean(3),
                    rs.getString(4),
                    instant(rs, 5),
                    rs.getLong(6)))
        .list();
  }

  public List<CandidateRow> findCandidateProperties(
      long officeId, ConditionRow condition, List<String> regions) {
    Map<String, Object> params = new HashMap<>();
    params.put("officeId", officeId);
    params.put("dealType", condition.dealType());
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.id, p.name, o.deal_type, o.deposit_amount, o.sale_price,"
                + " o.monthly_rent_amount FROM property p JOIN property_offer o ON o.property_id"
                + " = p.id WHERE p.office_id = :officeId AND p.deleted_at IS NULL AND o.status IN"
                + " ('AVAILABLE','NEGOTIATING') AND o.deal_type = :dealType");
    if (condition.categoryId() != null) {
      params.put("categoryId", condition.categoryId());
      sql.append(" AND p.category_id = :categoryId");
    }
    rangeClause(sql, params, "o.sale_price", "price", condition.priceMin(), condition.priceMax());
    rangeClause(
        sql, params, "o.deposit_amount", "deposit", condition.depositMin(), condition.depositMax());
    rangeClause(
        sql, params, "o.monthly_rent_amount", "rent", condition.rentMin(), condition.rentMax());
    if (condition.areaMin() != null) {
      params.put("areaMin", condition.areaMin());
      sql.append(" AND p.exclusive_area_sqm >= :areaMin");
    }
    if (condition.areaMax() != null) {
      params.put("areaMax", condition.areaMax());
      sql.append(" AND p.exclusive_area_sqm <= :areaMax");
    }
    if (!regions.isEmpty()) {
      sql.append(" AND (");
      for (int i = 0; i < regions.size(); i++) {
        params.put("region" + i, "%" + escapeLike(regions.get(i).toLowerCase()) + "%");
        sql.append(i == 0 ? "" : " OR ")
            .append("lower(coalesce(p.address, '')) LIKE :region")
            .append(i)
            .append(" ESCAPE '\\'");
      }
      sql.append(")");
    }
    sql.append(" ORDER BY p.updated_at DESC, p.id DESC LIMIT 200");
    return jdbcClient
        .sql(sql.toString())
        .params(params)
        .query(
            (rs, rowNum) ->
                new CandidateRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    (Long) rs.getObject(4),
                    (Long) rs.getObject(5),
                    (Long) rs.getObject(6)))
        .list();
  }

  private static void rangeClause(
      StringBuilder sql,
      Map<String, Object> params,
      String column,
      String key,
      Long min,
      Long max) {
    if (min != null) {
      params.put(key + "Min", min);
      sql.append(" AND ").append(column).append(" >= :").append(key).append("Min");
    }
    if (max != null) {
      params.put(key + "Max", max);
      sql.append(" AND ").append(column).append(" <= :").append(key).append("Max");
    }
  }

  public record RevisionRow(
      Instant previousOccurredAt,
      String previousContent,
      String previousConsultationType,
      String correctedBy,
      boolean correctorWithdrawn,
      Integer correctorAliasNo,
      Instant correctedAt,
      String correctionReason) {}

  public List<Long> firstInquiryIds(Collection<Long> customerIds) {
    return jdbcClient
        .sql(
            "SELECT id FROM customer_consultation WHERE customer_id IN (:ids) AND"
                + " consultation_type = 'FIRST_INQUIRY' ORDER BY id")
        .param("ids", customerIds)
        .query(Long.class)
        .list();
  }

  public java.util.Optional<Long> findConsultationCustomerId(long consultationId, long officeId) {
    return jdbcClient
        .sql(
            "SELECT cc.customer_id FROM customer_consultation cc JOIN customer c ON c.id ="
                + " cc.customer_id WHERE cc.id = :id AND c.office_id = :officeId")
        .param("id", consultationId)
        .param("officeId", officeId)
        .query(Long.class)
        .optional();
  }

  public boolean hasScheduleOverlap(Collection<Long> first, Collection<Long> second) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM visit_schedule_item x JOIN visit_coordination cx ON"
                    + " cx.id = x.coordination_id JOIN visit_schedule_item y ON y.id <> x.id JOIN"
                    + " visit_coordination cy ON cy.id = y.coordination_id WHERE cx.customer_id IN"
                    + " (:first) AND cy.customer_id IN (:second) AND x.result = 'SCHEDULED' AND"
                    + " y.result = 'SCHEDULED' AND x.scheduled_start_at IS NOT NULL AND"
                    + " y.scheduled_start_at IS NOT NULL AND x.scheduled_end_at IS NOT NULL AND"
                    + " y.scheduled_end_at IS NOT NULL AND x.scheduled_start_at <"
                    + " y.scheduled_end_at AND y.scheduled_start_at < x.scheduled_end_at)")
            .param("first", first)
            .param("second", second)
            .query(Boolean.class)
            .single());
  }

  public List<RevisionRow> findRevisions(long consultationId) {
    return jdbcClient
        .sql(
            "SELECT r.previous_occurred_at, r.previous_content, r.previous_consultation_type,"
                + " u.name, (u.account_status = 'WITHDRAWN'), m.withdrawn_alias_no,"
                + " r.corrected_at, r.correction_reason FROM customer_consultation_revision r"
                + " JOIN office_membership m ON m.id = r.corrected_by_membership_id JOIN app_user u"
                + " ON u.id = m.user_id WHERE r.consultation_id = :id ORDER BY r.id")
        .param("id", consultationId)
        .query(
            (rs, rowNum) ->
                new RevisionRow(
                    instant(rs, 1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    (Integer) rs.getObject(6),
                    instant(rs, 7),
                    rs.getString(8)))
        .list();
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
