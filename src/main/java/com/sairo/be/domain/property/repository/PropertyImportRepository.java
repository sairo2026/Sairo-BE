package com.sairo.be.domain.property.repository;

import com.sairo.be.global.support.ImportedRecordBlocker;
import com.sairo.be.global.support.ImportedRecordState;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class PropertyImportRepository {

  public record MatchRow(
      long propertyId, String normalizedAddress, String normalizedAddressDetail) {}

  private final JdbcClient jdbcClient;

  public PropertyImportRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<MatchRow> findMatchCandidates(long officeId, Collection<String> normalizedAddresses) {
    if (normalizedAddresses.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            "SELECT p.id, lower(regexp_replace(coalesce(p.address, ''), '\\s+', '', 'g')),"
                + " lower(regexp_replace(coalesce(p.address_detail, ''), '\\s+', '', 'g'))"
                + " FROM property p WHERE p.office_id = :officeId AND p.deleted_at IS NULL"
                + " AND lower(regexp_replace(coalesce(p.address, ''), '\\s+', '', 'g'))"
                + " IN (:addresses) ORDER BY p.id")
        .param("officeId", officeId)
        .param("addresses", normalizedAddresses)
        .query((rs, rowNum) -> new MatchRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  public Long findDefaultCategoryId(long officeId) {
    return jdbcClient
        .sql(
            "SELECT c.id FROM property_category c WHERE (c.office_id IS NULL OR c.office_id ="
                + " :officeId) AND c.active ORDER BY c.is_system DESC, c.id LIMIT 1")
        .param("officeId", officeId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  public ImportedRecordState inspect(long officeId, long propertyId) {
    return jdbcClient
        .sql(
            "SELECT p.version, p.deleted_at IS NOT NULL, p.registration_stage <> 'DRAFT',"
                + " EXISTS (SELECT 1 FROM contract WHERE property_id = p.id),"
                + " EXISTS (SELECT 1 FROM visit_candidate_property WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM property_access_approval WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM visit_property_apply_grant WHERE property_id = p.id),"
                + " EXISTS (SELECT 1 FROM property_owner_occupant WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM customer_property_interest WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM property_offer WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM property_field_value WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM property_photo WHERE property_id = p.id)"
                + " OR EXISTS (SELECT 1 FROM task WHERE related_property_id = p.id)"
                + " FROM property p WHERE p.id = :id AND p.office_id = :officeId")
        .param("id", propertyId)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) -> {
              if (rs.getBoolean(4)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.LEGAL_RETENTION);
              }
              if (rs.getBoolean(5)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.EXTERNAL_LINK);
              }
              if (rs.getLong(1) > 1 || rs.getBoolean(2) || rs.getBoolean(3) || rs.getBoolean(6)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.FOLLOW_UP_EDIT);
              }
              return ImportedRecordState.removable();
            })
        .optional()
        .orElse(ImportedRecordState.missing());
  }
}
