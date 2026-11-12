package com.sairo.be.domain.property.repository;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class PropertyArchiveRepository {

  private static final String[] REFERENCE_TABLES = {
    "property_owner_occupant",
    "customer_property_interest",
    "visit_candidate_property",
    "contract",
    "property_access_approval",
    "visit_property_apply_grant"
  };

  private final JdbcClient jdbcClient;

  public PropertyArchiveRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean hasActiveCoordination(long officeId, long propertyId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM visit_candidate_property cp JOIN visit_coordination vc"
            + " ON vc.id = cp.coordination_id WHERE cp.property_id = :propertyId AND vc.office_id"
            + " = :officeId AND vc.status NOT IN ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED'))"
            + " OR EXISTS (SELECT 1 FROM visit_schedule_item s JOIN visit_candidate_property cp"
            + " ON cp.id = s.candidate_property_id WHERE cp.property_id = :propertyId AND"
            + " s.result = 'SCHEDULED')",
        officeId,
        propertyId);
  }

  public boolean hasSignedContract(long officeId, long propertyId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM contract WHERE property_id = :propertyId AND office_id ="
            + " :officeId AND contract_status = 'SIGNED')",
        officeId,
        propertyId);
  }

  public boolean hasReferences(long propertyId) {
    for (String table : REFERENCE_TABLES) {
      Boolean found =
          jdbcClient
              .sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE property_id = :propertyId)")
              .param("propertyId", propertyId)
              .query(Boolean.class)
              .single();
      if (Boolean.TRUE.equals(found)) {
        return true;
      }
    }
    return false;
  }

  public void deleteDraftRows(long propertyId) {
    for (String table :
        List.of(
            "property_offer", "property_field_value", "property_photo", "entity_lifecycle_event")) {
      jdbcClient
          .sql("DELETE FROM " + table + " WHERE property_id = :propertyId")
          .param("propertyId", propertyId)
          .update();
    }
    jdbcClient
        .sql("DELETE FROM property WHERE id = :propertyId")
        .param("propertyId", propertyId)
        .update();
  }

  private boolean exists(String sql, long officeId, long propertyId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(sql)
            .param("propertyId", propertyId)
            .param("officeId", officeId)
            .query(Boolean.class)
            .single());
  }
}
