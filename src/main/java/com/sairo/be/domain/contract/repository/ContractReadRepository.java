package com.sairo.be.domain.contract.repository;

import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 목록·상세 화면용 조회다. 서류 표(contract_document)는 읽기만 한다. */
@Profile("!migrate")
@Repository
public class ContractReadRepository {

  public record ListFilter(
      Long officeId,
      String contractStatus,
      String propertyAddress,
      String partyName,
      boolean includeExcluded,
      Integer leaseEndWithinDays,
      LocalDate today) {}

  public record ListRow(
      ContractRow contract,
      String propertyAddress,
      String sellerName,
      String buyerName,
      int sellerCount,
      int buyerCount,
      String managerName,
      boolean salesContractAttached,
      boolean disclosureAttached) {}

  public record Header(String propertyAddress, String managerName) {}

  public record DocumentRow(
      Long id,
      String documentType,
      int versionNo,
      Long contractRevisionId,
      LocalDate writtenDate,
      LocalDate deliveredDate,
      String datePendingReason,
      int retentionYears,
      LocalDate retentionEndDate,
      boolean piiMaskingConfirmed,
      Long supersedesDocumentId,
      Instant uploadedAt) {}

  private static final String MANAGER_JOIN =
      " JOIN office_membership m ON m.id = c.managing_membership_id JOIN app_user u ON u.id ="
          + " m.user_id JOIN property p ON p.id = c.property_id";

  private final JdbcClient jdbcClient;

  public ContractReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long count(ListFilter filter) {
    Map<String, Object> params = new HashMap<>();
    return jdbcClient
        .sql(
            "SELECT count(*) FROM contract c JOIN property p ON p.id = c.property_id"
                + where(filter, params))
        .params(params)
        .query(Long.class)
        .single();
  }

  public List<ListRow> list(ListFilter filter, int limit, long offset) {
    Map<String, Object> params = new HashMap<>();
    String where = where(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            "SELECT "
                + ContractRepository.COLUMNS
                + ", COALESCE(p.address, p.name) AS property_address, c.seller_landlord_name,"
                + " c.buyer_tenant_name, u.name AS manager_name, (u.account_status ="
                + " 'WITHDRAWN') AS withdrawn, m.withdrawn_alias_no, (SELECT count(*) FROM"
                + " contract_party cp WHERE cp.contract_id = c.id AND cp.removed_at IS NULL AND"
                + " cp.side = 'SELLER_LANDLORD') AS seller_count, (SELECT count(*) FROM"
                + " contract_party cp WHERE cp.contract_id = c.id AND cp.removed_at IS NULL AND"
                + " cp.side = 'BUYER_TENANT') AS buyer_count, EXISTS (SELECT 1 FROM"
                + " contract_document d WHERE d.contract_id = c.id AND d.document_type ="
                + " 'SALES_CONTRACT') AS sales_attached, EXISTS (SELECT 1 FROM contract_document"
                + " d WHERE d.contract_id = c.id AND d.document_type = 'DISCLOSURE_STATEMENT') AS"
                + " disclosure_attached FROM contract c"
                + MANAGER_JOIN
                + where
                + " ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(
            (rs, i) ->
                new ListRow(
                    ContractRepository.row(rs),
                    rs.getString("property_address"),
                    rs.getString("seller_landlord_name"),
                    rs.getString("buyer_tenant_name"),
                    rs.getInt("seller_count"),
                    rs.getInt("buyer_count"),
                    managerName(rs),
                    rs.getBoolean("sales_attached"),
                    rs.getBoolean("disclosure_attached")))
        .list();
  }

  public Header header(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT COALESCE(p.address, p.name) AS property_address, u.name AS manager_name,"
                + " (u.account_status = 'WITHDRAWN') AS withdrawn, m.withdrawn_alias_no FROM"
                + " contract c"
                + MANAGER_JOIN
                + " WHERE c.id = :id")
        .param("id", contractId)
        .query((rs, i) -> new Header(rs.getString("property_address"), managerName(rs)))
        .single();
  }

  public List<DocumentRow> documents(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT id, document_type, version_no, contract_revision_id, written_date,"
                + " delivered_date, date_pending_reason, retention_years, retention_end_date,"
                + " pii_masking_confirmed, supersedes_document_id, uploaded_at FROM"
                + " contract_document WHERE contract_id = :id ORDER BY document_type, version_no")
        .param("id", contractId)
        .query(
            (rs, i) ->
                new DocumentRow(
                    rs.getLong("id"),
                    rs.getString("document_type"),
                    rs.getInt("version_no"),
                    rs.getObject("contract_revision_id", Long.class),
                    rs.getObject("written_date", LocalDate.class),
                    rs.getObject("delivered_date", LocalDate.class),
                    rs.getString("date_pending_reason"),
                    rs.getInt("retention_years"),
                    rs.getObject("retention_end_date", LocalDate.class),
                    rs.getBoolean("pii_masking_confirmed"),
                    rs.getObject("supersedes_document_id", Long.class),
                    ContractRepository.instant(rs, "uploaded_at")))
        .list();
  }

  private static String managerName(ResultSet rs) throws SQLException {
    return WithdrawnMemberDisplay.name(
        rs.getBoolean("withdrawn"),
        rs.getString("manager_name"),
        rs.getObject("withdrawn_alias_no", Integer.class));
  }

  private static String where(ListFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE c.office_id = :office");
    params.put("office", filter.officeId());
    if (!filter.includeExcluded()) {
      where.append(" AND c.duplicate_excluded_at IS NULL");
    }
    if (filter.contractStatus() != null) {
      where.append(" AND c.contract_status = :status");
      params.put("status", filter.contractStatus());
    }
    if (filter.propertyAddress() != null) {
      where.append(
          " AND (strpos(lower(COALESCE(p.address, '')), lower(:address)) > 0 OR"
              + " strpos(lower(COALESCE(p.address_detail, '')), lower(:address)) > 0)");
      params.put("address", filter.propertyAddress());
    }
    if (filter.partyName() != null) {
      where.append(
          " AND EXISTS (SELECT 1 FROM contract_party cp WHERE cp.contract_id = c.id AND"
              + " cp.removed_at IS NULL AND strpos(lower(cp.name), lower(:partyName)) > 0)");
      params.put("partyName", filter.partyName());
    }
    if (filter.leaseEndWithinDays() != null) {
      where.append(
          " AND c.contract_status = 'SIGNED' AND c.deal_type IN ('JEONSE','MONTHLY') AND"
              + " c.lease_end_date >= :today AND c.lease_end_date <= :until");
      params.put("today", filter.today());
      params.put("until", filter.today().plusDays(filter.leaseEndWithinDays()));
    }
    return where.toString();
  }
}
