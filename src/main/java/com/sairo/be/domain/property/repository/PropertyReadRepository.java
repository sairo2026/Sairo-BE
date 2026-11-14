package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.AreaType;
import com.sairo.be.domain.property.entity.DealType;
import com.sairo.be.domain.property.entity.RegistrationStage;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
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
public class PropertyReadRepository {

  private static final String ACTIVE_OFFER_STATUSES = "('AVAILABLE','NEGOTIATING')";

  private final JdbcClient jdbcClient;

  public PropertyReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public record ListFilter(
      String keyword,
      Long categoryId,
      DealType dealType,
      Long priceMin,
      Long priceMax,
      AreaType areaType,
      BigDecimal areaMin,
      BigDecimal areaMax,
      Long managingMembershipId,
      RegistrationStage registrationStage,
      boolean archived) {}

  public record ListRow(
      long propertyId,
      long officeId,
      String name,
      String address,
      String addressDetail,
      long categoryId,
      String categoryName,
      String registrationStage,
      Long managingMembershipId,
      String managerName,
      boolean managerWithdrawn,
      Integer managerAliasNo,
      String occupancyStatus,
      LocalDate referenceLeaseEndDate,
      String referenceLeaseEndSource,
      String memo,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record OfferRow(
      long propertyId,
      long offerId,
      String dealType,
      String status,
      Long salePrice,
      Long depositAmount,
      Long monthlyRentAmount,
      Long premiumAmount) {}

  public record OwnerOccupantRow(
      long propertyId,
      long linkId,
      long customerId,
      String customerName,
      String role,
      LocalDate startDate,
      LocalDate endDate) {}

  public record VisitRow(
      long scheduleItemId,
      long coordinationId,
      long customerId,
      String customerName,
      Instant scheduledStartAt,
      String result,
      Instant actualVisitedAt) {}

  public record ContractRow(
      long contractId,
      String dealType,
      String contractStatus,
      LocalDate signedDate,
      LocalDate leaseStartDate,
      LocalDate leaseEndDate) {}

  public record CoordinationRow(long coordinationId, String status, String customerName) {}

  public record DuplicateRow(
      long propertyId,
      String address,
      String addressDetail,
      String categoryName,
      String registrationStage) {}

  public List<ListRow> findListRows(long officeId, ListFilter filter, int limit, long offset) {
    Map<String, Object> params = new HashMap<>();
    String where = whereClause(officeId, filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    String sql =
        "SELECT p.id, p.office_id, p.name, p.address, p.address_detail, p.category_id,"
            + " c.name AS category_name, p.registration_stage, p.managing_membership_id,"
            + " u.name AS manager_name, (u.account_status = 'WITHDRAWN') AS manager_withdrawn,"
            + " m.withdrawn_alias_no, p.occupancy_status, p.reference_lease_end_date,"
            + " p.reference_lease_end_source, p.memo, p.created_at, p.updated_at, p.version"
            + " FROM property p JOIN property_category c ON c.id = p.category_id"
            + " LEFT JOIN office_membership m ON m.id = p.managing_membership_id"
            + " LEFT JOIN app_user u ON u.id = m.user_id"
            + where
            + " ORDER BY p.updated_at DESC, p.id DESC LIMIT :limit OFFSET :offset";
    return jdbcClient.sql(sql).params(params).query(this::mapListRow).list();
  }

  public long countList(long officeId, ListFilter filter) {
    Map<String, Object> params = new HashMap<>();
    String where = whereClause(officeId, filter, params);
    Long count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM property p JOIN property_category c ON c.id = p.category_id"
                    + where)
            .params(params)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  private String whereClause(long officeId, ListFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE p.office_id = :officeId");
    params.put("officeId", officeId);
    where.append(filter.archived() ? " AND p.deleted_at IS NOT NULL" : " AND p.deleted_at IS NULL");
    if (filter.keyword() != null) {
      params.put("keyword", "%" + escapeLike(filter.keyword().toLowerCase()) + "%");
      where.append(
          " AND (lower(p.name) LIKE :keyword ESCAPE '\\' OR lower(coalesce(p.address, '')) LIKE"
              + " :keyword ESCAPE '\\' OR EXISTS (SELECT 1 FROM property_owner_occupant oo"
              + " JOIN customer cu ON cu.id = oo.customer_id WHERE oo.property_id = p.id AND"
              + " (lower(cu.name) LIKE :keyword ESCAPE '\\' OR EXISTS (SELECT 1 FROM"
              + " customer_merge_log ml JOIN customer mc ON mc.id = ml.merged_customer_id WHERE"
              + " ml.primary_customer_id = cu.id AND lower(mc.name) LIKE :keyword ESCAPE"
              + " '\\'))))");
    }
    if (filter.categoryId() != null) {
      params.put("categoryId", filter.categoryId());
      where.append(" AND p.category_id = :categoryId");
    }
    if (filter.managingMembershipId() != null) {
      params.put("managingMembershipId", filter.managingMembershipId());
      where.append(" AND p.managing_membership_id = :managingMembershipId");
    }
    if (filter.registrationStage() != null) {
      params.put("registrationStage", filter.registrationStage().name());
      where.append(" AND p.registration_stage = :registrationStage");
    }
    if (filter.areaType() != null) {
      String column = areaColumn(filter.areaType());
      if (filter.areaMin() != null) {
        params.put("areaMin", filter.areaMin());
        where.append(" AND p.").append(column).append(" >= :areaMin");
      }
      if (filter.areaMax() != null) {
        params.put("areaMax", filter.areaMax());
        where.append(" AND p.").append(column).append(" <= :areaMax");
      }
    }
    if (filter.dealType() != null) {
      params.put("dealType", filter.dealType().name());
      StringBuilder offer =
          new StringBuilder(
              " AND EXISTS (SELECT 1 FROM property_offer o WHERE o.property_id = p.id AND"
                  + " o.status IN "
                  + ACTIVE_OFFER_STATUSES
                  + " AND o.deal_type = :dealType");
      String priceColumn = priceColumn(filter.dealType());
      if (filter.priceMin() != null) {
        params.put("priceMin", filter.priceMin());
        offer.append(" AND o.").append(priceColumn).append(" >= :priceMin");
      }
      if (filter.priceMax() != null) {
        params.put("priceMax", filter.priceMax());
        offer.append(" AND o.").append(priceColumn).append(" <= :priceMax");
      }
      where.append(offer).append(")");
    }
    return where.toString();
  }

  private static String areaColumn(AreaType areaType) {
    return switch (areaType) {
      case EXCLUSIVE -> "exclusive_area_sqm";
      case SUPPLY -> "supply_area_sqm";
      case LAND -> "land_area_sqm";
      case GROSS_FLOOR -> "gross_floor_area_sqm";
    };
  }

  private static String priceColumn(DealType dealType) {
    return switch (dealType) {
      case SALE -> "sale_price";
      case JEONSE -> "deposit_amount";
      case MONTHLY -> "monthly_rent_amount";
    };
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private ListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    return new ListRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("name"),
        rs.getString("address"),
        rs.getString("address_detail"),
        rs.getLong("category_id"),
        rs.getString("category_name"),
        rs.getString("registration_stage"),
        (Long) rs.getObject("managing_membership_id"),
        rs.getString("manager_name"),
        rs.getBoolean("manager_withdrawn"),
        (Integer) rs.getObject("withdrawn_alias_no"),
        rs.getString("occupancy_status"),
        rs.getObject("reference_lease_end_date", LocalDate.class),
        rs.getString("reference_lease_end_source"),
        rs.getString("memo"),
        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getLong("version"));
  }

  public Map<Long, List<OfferRow>> findActiveOffers(Collection<Long> propertyIds) {
    return groupOffers(propertyIds, " AND status IN " + ACTIVE_OFFER_STATUSES);
  }

  public Map<Long, List<OfferRow>> findOffers(Collection<Long> propertyIds) {
    return groupOffers(propertyIds, "");
  }

  private Map<Long, List<OfferRow>> groupOffers(Collection<Long> propertyIds, String extra) {
    Map<Long, List<OfferRow>> grouped = new LinkedHashMap<>();
    if (propertyIds.isEmpty()) {
      return grouped;
    }
    jdbcClient
        .sql(
            "SELECT property_id, id, deal_type, status, sale_price, deposit_amount,"
                + " monthly_rent_amount, premium_amount FROM property_offer WHERE property_id IN"
                + " (:ids)"
                + extra
                + " ORDER BY id")
        .param("ids", propertyIds)
        .query(
            (rs, rowNum) ->
                new OfferRow(
                    rs.getLong("property_id"),
                    rs.getLong("id"),
                    rs.getString("deal_type"),
                    rs.getString("status"),
                    (Long) rs.getObject("sale_price"),
                    (Long) rs.getObject("deposit_amount"),
                    (Long) rs.getObject("monthly_rent_amount"),
                    (Long) rs.getObject("premium_amount")))
        .list()
        .forEach(
            row -> grouped.computeIfAbsent(row.propertyId(), key -> new ArrayList<>()).add(row));
    return grouped;
  }

  public Map<Long, List<String>> findCurrentPartyNames(
      Collection<Long> propertyIds, String role, LocalDate today) {
    Map<Long, List<String>> grouped = new HashMap<>();
    if (propertyIds.isEmpty()) {
      return grouped;
    }
    jdbcClient
        .sql(
            "SELECT oo.property_id, cu.name FROM property_owner_occupant oo"
                + " JOIN customer cu ON cu.id = oo.customer_id"
                + " WHERE oo.property_id IN (:ids) AND oo.role = :role AND oo.start_date <= :today"
                + " AND (oo.end_date IS NULL OR oo.end_date >= :today)"
                + " ORDER BY oo.start_date DESC, oo.id")
        .param("ids", propertyIds)
        .param("role", role)
        .param("today", today)
        .query((rs, rowNum) -> Map.entry(rs.getLong(1), rs.getString(2)))
        .list()
        .forEach(
            entry ->
                grouped
                    .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                    .add(entry.getValue()));
    return grouped;
  }

  public Map<Long, LocalDate> findContractLeaseEnds(Collection<Long> propertyIds) {
    Map<Long, LocalDate> ends = new HashMap<>();
    if (propertyIds.isEmpty()) {
      return ends;
    }
    jdbcClient
        .sql(
            "SELECT property_id, max(lease_end_date) AS lease_end_date FROM contract"
                + " WHERE property_id IN (:ids) AND contract_status = 'SIGNED'"
                + " AND deal_type <> 'SALE' AND lease_end_date IS NOT NULL GROUP BY property_id")
        .param("ids", propertyIds)
        .query((rs, rowNum) -> Map.entry(rs.getLong(1), rs.getObject(2, LocalDate.class)))
        .list()
        .forEach(entry -> ends.put(entry.getKey(), entry.getValue()));
    return ends;
  }

  public List<OwnerOccupantRow> findOwnerOccupants(long propertyId) {
    return jdbcClient
        .sql(
            "SELECT oo.property_id, oo.id, oo.customer_id, cu.name, oo.role, oo.start_date,"
                + " oo.end_date FROM property_owner_occupant oo"
                + " JOIN customer cu ON cu.id = oo.customer_id WHERE oo.property_id = :propertyId"
                + " ORDER BY oo.start_date DESC, oo.id DESC")
        .param("propertyId", propertyId)
        .query(
            (rs, rowNum) ->
                new OwnerOccupantRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getObject(6, LocalDate.class),
                    rs.getObject(7, LocalDate.class)))
        .list();
  }

  public List<VisitRow> findVisits(long officeId, long propertyId) {
    return jdbcClient
        .sql(
            "SELECT s.id, s.coordination_id, vc.customer_id, cu.name, s.scheduled_start_at,"
                + " s.result, s.actual_visited_at FROM visit_schedule_item s"
                + " JOIN visit_candidate_property cp ON cp.id = s.candidate_property_id"
                + " JOIN visit_coordination vc ON vc.id = s.coordination_id"
                + " JOIN customer cu ON cu.id = vc.customer_id"
                + " WHERE cp.property_id = :propertyId AND vc.office_id = :officeId"
                + " AND s.result <> 'SCHEDULED'"
                + " ORDER BY s.scheduled_start_at DESC NULLS LAST, s.id DESC")
        .param("propertyId", propertyId)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new VisitRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getString(4),
                    instant(rs, 5),
                    rs.getString(6),
                    instant(rs, 7)))
        .list();
  }

  public List<ContractRow> findContracts(long officeId, long propertyId) {
    return jdbcClient
        .sql(
            "SELECT id, deal_type, contract_status, signed_date, lease_start_date,"
                + " lease_end_date FROM contract WHERE property_id = :propertyId"
                + " AND office_id = :officeId ORDER BY updated_at DESC, id DESC")
        .param("propertyId", propertyId)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new ContractRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4, LocalDate.class),
                    rs.getObject(5, LocalDate.class),
                    rs.getObject(6, LocalDate.class)))
        .list();
  }

  public List<CoordinationRow> findActiveCoordinations(long officeId, long propertyId) {
    return jdbcClient
        .sql(
            "SELECT vc.id, vc.status, cu.name FROM visit_candidate_property cp"
                + " JOIN visit_coordination vc ON vc.id = cp.coordination_id"
                + " JOIN customer cu ON cu.id = vc.customer_id"
                + " WHERE cp.property_id = :propertyId AND vc.office_id = :officeId"
                + " AND vc.status NOT IN ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED')"
                + " ORDER BY vc.id")
        .param("propertyId", propertyId)
        .param("officeId", officeId)
        .query((rs, rowNum) -> new CoordinationRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  public List<DuplicateRow> findDuplicates(
      long officeId,
      String address,
      String addressDetail,
      Long excludePropertyId,
      boolean exactDetail) {
    Map<String, Object> params = new HashMap<>();
    params.put("officeId", officeId);
    params.put("address", normalize(address));
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.id, p.address, p.address_detail, c.name, p.registration_stage"
                + " FROM property p JOIN property_category c ON c.id = p.category_id"
                + " WHERE p.office_id = :officeId AND p.deleted_at IS NULL"
                + " AND lower(regexp_replace(coalesce(p.address, ''), '\\s+', '', 'g'))"
                + " = :address");
    if (exactDetail || (addressDetail != null && !addressDetail.isBlank())) {
      params.put("detail", normalize(addressDetail));
      sql.append(
          " AND lower(regexp_replace(coalesce(p.address_detail, ''), '\\s+', '', 'g'))"
              + " = :detail");
    }
    if (excludePropertyId != null) {
      params.put("excludeId", excludePropertyId);
      sql.append(" AND p.id <> :excludeId");
    }
    sql.append(" ORDER BY p.id");
    return jdbcClient
        .sql(sql.toString())
        .params(params)
        .query(
            (rs, rowNum) ->
                new DuplicateRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)))
        .list();
  }

  public static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
