package com.sairo.be.domain.customer.repository;

import com.sairo.be.global.support.ImportedRecordBlocker;
import com.sairo.be.global.support.ImportedRecordState;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class CustomerImportRepository {

  public record MatchRow(
      long customerId, String normalizedName, String phoneDigits, String secondaryPhoneDigits) {}

  private final JdbcClient jdbcClient;

  public CustomerImportRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<MatchRow> findMatchCandidates(long officeId, Collection<String> normalizedNames) {
    if (normalizedNames.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            "SELECT c.id, lower(regexp_replace(c.name, '\\s+', '', 'g')),"
                + " regexp_replace(coalesce(c.phone, ''), '\\D', '', 'g'),"
                + " regexp_replace(coalesce(c.secondary_phone, ''), '\\D', '', 'g')"
                + " FROM customer c WHERE c.office_id = :officeId AND c.deleted_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM customer_merge_log l WHERE l.merged_customer_id"
                + " = c.id) AND lower(regexp_replace(c.name, '\\s+', '', 'g')) IN (:names)"
                + " ORDER BY c.id")
        .param("officeId", officeId)
        .param("names", normalizedNames)
        .query(
            (rs, rowNum) ->
                new MatchRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
        .list();
  }

  public ImportedRecordState inspect(long officeId, long customerId) {
    return jdbcClient
        .sql(
            "SELECT c.version, c.deleted_at IS NOT NULL,"
                + " EXISTS (SELECT 1 FROM contract_party WHERE customer_id = c.id),"
                + " EXISTS (SELECT 1 FROM visit_coordination WHERE customer_id = c.id),"
                + " EXISTS (SELECT 1 FROM property_owner_occupant WHERE customer_id = c.id)"
                + " OR EXISTS (SELECT 1 FROM customer_property_interest WHERE customer_id = c.id)"
                + " OR EXISTS (SELECT 1 FROM customer_demand_condition WHERE customer_id = c.id)"
                + " OR EXISTS (SELECT 1 FROM customer_consultation WHERE customer_id = c.id)"
                + " OR EXISTS (SELECT 1 FROM customer_merge_log WHERE primary_customer_id = c.id"
                + " OR merged_customer_id = c.id)"
                + " OR EXISTS (SELECT 1 FROM task WHERE related_customer_id = c.id)"
                + " FROM customer c WHERE c.id = :id AND c.office_id = :officeId")
        .param("id", customerId)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) -> {
              if (rs.getBoolean(3)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.LEGAL_RETENTION);
              }
              if (rs.getBoolean(4)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.EXTERNAL_LINK);
              }
              if (rs.getLong(1) > 1 || rs.getBoolean(2) || rs.getBoolean(5)) {
                return ImportedRecordState.blockedBy(ImportedRecordBlocker.FOLLOW_UP_EDIT);
              }
              return ImportedRecordState.removable();
            })
        .optional()
        .orElse(ImportedRecordState.missing());
  }
}
