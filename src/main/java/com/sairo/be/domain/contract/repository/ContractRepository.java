package com.sairo.be.domain.contract.repository;

import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.DealType;
import com.sairo.be.domain.contract.entity.LeasePeriodStatus;
import com.sairo.be.domain.contract.entity.SettlementStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** contract 표와 계약 검증에 필요한 다른 표 조회를 JDBC로 다룬다. 사무소 범위는 호출자가 인증된 소속에서 넘긴다. */
@Profile("!migrate")
@Repository
public class ContractRepository {

  public record PropertyRef(Long id, String address, String name, boolean archived) {}

  /** 양측 첫 당사자의 이름·연락처다. 체결 상태로 바로 만들 때 표 제약을 맞추려고 함께 넣는다. */
  public record PartySnapshot(
      String sellerName, String sellerPhone, String buyerName, String buyerPhone) {}

  static final String COLUMNS =
      "c.id, c.office_id, c.property_id, c.previous_contract_id, c.deal_type,"
          + " c.managing_membership_id, c.signed_date, c.sale_price, c.deposit_amount,"
          + " c.monthly_rent_amount, c.balance_due_date, c.balance_due_date_pending_reason,"
          + " c.lease_start_date, c.lease_end_date, c.memo, c.contract_status,"
          + " c.lease_period_status, c.settlement_status, c.duplicate_of_contract_id,"
          + " c.duplicate_excluded_at, c.duplicate_exclusion_reason, c.created_at, c.updated_at,"
          + " c.version";

  private static final String SET_VALUES =
      " deal_type = :dealType, managing_membership_id = :manager, signed_date = :signedDate,"
          + " sale_price = :salePrice, deposit_amount = :deposit, monthly_rent_amount = :rent,"
          + " balance_due_date = :balanceDue, balance_due_date_pending_reason = :pendingReason,"
          + " lease_start_date = :leaseStart, lease_end_date = :leaseEnd, memo = :memo";

  private static final String BUMP = " updated_at = :now, version = version + 1";

  private final JdbcClient jdbcClient;

  public ContractRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insert(
      Long officeId,
      Long propertyId,
      Long previousContractId,
      ContractValues values,
      ContractStatus status,
      LeasePeriodStatus leaseStatus,
      PartySnapshot first,
      Instant now) {
    return bind(
            jdbcClient.sql(
                "INSERT INTO contract (office_id, property_id, previous_contract_id, deal_type,"
                    + " managing_membership_id, signed_date, sale_price, deposit_amount,"
                    + " monthly_rent_amount, balance_due_date, balance_due_date_pending_reason,"
                    + " lease_start_date, lease_end_date, memo, contract_status,"
                    + " lease_period_status, seller_landlord_name, seller_landlord_phone,"
                    + " buyer_tenant_name, buyer_tenant_phone, created_at, updated_at, version)"
                    + " VALUES (:office, :property, :previous, :dealType, :manager, :signedDate,"
                    + " :salePrice, :deposit, :rent, :balanceDue, :pendingReason, :leaseStart,"
                    + " :leaseEnd, :memo, :status, :lease, :sellerName, :sellerPhone, :buyerName,"
                    + " :buyerPhone, :now, :now, 1) RETURNING id"),
            values)
        .param("office", officeId)
        .param("property", propertyId)
        .param("previous", previousContractId)
        .param("status", status.name())
        .param("lease", leaseStatus == null ? null : leaseStatus.name())
        .param("sellerName", first.sellerName())
        .param("sellerPhone", first.sellerPhone())
        .param("buyerName", first.buyerName())
        .param("buyerPhone", first.buyerPhone())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<ContractRow> find(Long officeId, Long contractId) {
    return select(officeId, contractId, "");
  }

  public Optional<ContractRow> lock(Long officeId, Long contractId) {
    return select(officeId, contractId, " FOR UPDATE");
  }

  private Optional<ContractRow> select(Long officeId, Long contractId, String suffix) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM contract c WHERE c.id = :id AND c.office_id = :office"
                + suffix)
        .param("id", contractId)
        .param("office", officeId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  /** 핵심값과 임대기간 표시값을 한 문장으로 바꾼다. 거래유형과 임대기간 표시값의 표 제약을 함께 맞추려는 것이다. */
  public void updateValues(
      Long contractId, ContractValues values, LeasePeriodStatus leaseStatus, Instant now) {
    bind(
            jdbcClient.sql(
                "UPDATE contract SET"
                    + SET_VALUES
                    + ", lease_period_status = :lease,"
                    + BUMP
                    + " WHERE id = :id"),
            values)
        .param("lease", leaseStatus == null ? null : leaseStatus.name())
        .param("now", Timestamp.from(now))
        .param("id", contractId)
        .update();
  }

  public void updateStatus(
      Long contractId,
      ContractStatus status,
      LeasePeriodStatus leaseStatus,
      SettlementStatus settlementStatus,
      Instant now) {
    jdbcClient
        .sql(
            "UPDATE contract SET contract_status = :status, lease_period_status = :lease,"
                + " settlement_status = :settlement,"
                + BUMP
                + " WHERE id = :id")
        .param("status", status.name())
        .param("lease", leaseStatus == null ? null : leaseStatus.name())
        .param("settlement", settlementStatus.name())
        .param("now", Timestamp.from(now))
        .param("id", contractId)
        .update();
  }

  /** 양측 첫 당사자의 이름·연락처를 조회용 스냅샷 컬럼에 다시 옮긴다. 당사자가 없으면 NULL이다. */
  public void refreshPartySnapshot(Long contractId) {
    jdbcClient
        .sql(
            "UPDATE contract c SET seller_landlord_name = (SELECT name FROM contract_party WHERE"
                + " contract_id = c.id AND side = 'SELLER_LANDLORD' AND removed_at IS NULL ORDER"
                + " BY sequence_no LIMIT 1), seller_landlord_phone = (SELECT phone FROM"
                + " contract_party WHERE contract_id = c.id AND side = 'SELLER_LANDLORD' AND"
                + " removed_at IS NULL ORDER BY sequence_no LIMIT 1), buyer_tenant_name ="
                + " (SELECT name FROM contract_party WHERE contract_id = c.id AND side ="
                + " 'BUYER_TENANT' AND removed_at IS NULL ORDER BY sequence_no LIMIT 1),"
                + " buyer_tenant_phone = (SELECT phone FROM contract_party WHERE contract_id ="
                + " c.id AND side = 'BUYER_TENANT' AND removed_at IS NULL ORDER BY sequence_no"
                + " LIMIT 1) WHERE c.id = :id")
        .param("id", contractId)
        .update();
  }

  public void exclude(
      Long contractId, Long duplicateOf, String reason, Long membershipId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE contract SET duplicate_of_contract_id = :target, duplicate_excluded_at ="
                + " :now, duplicate_exclusion_reason = :reason,"
                + " duplicate_excluded_by_membership_id = :member,"
                + BUMP
                + " WHERE id = :id")
        .param("target", duplicateOf)
        .param("reason", reason)
        .param("member", membershipId)
        .param("now", Timestamp.from(now))
        .param("id", contractId)
        .update();
  }

  public void delete(Long contractId) {
    jdbcClient.sql("DELETE FROM contract WHERE id = :id").param("id", contractId).update();
  }

  public boolean exists(Long officeId, Long contractId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql("SELECT EXISTS (SELECT 1 FROM contract WHERE id = :id AND office_id = :office)")
            .param("id", contractId)
            .param("office", officeId)
            .query(Boolean.class)
            .single());
  }

  public Optional<PropertyRef> lockProperty(Long officeId, Long propertyId) {
    return jdbcClient
        .sql(
            "SELECT id, address, name, deleted_at IS NOT NULL AS archived FROM property WHERE id"
                + " = :id AND office_id = :office FOR UPDATE")
        .param("id", propertyId)
        .param("office", officeId)
        .query(
            (rs, i) ->
                new PropertyRef(
                    rs.getLong("id"),
                    rs.getString("address"),
                    rs.getString("name"),
                    rs.getBoolean("archived")))
        .optional();
  }

  /**
   * 같은 매물의 다른 체결 계약과 겹치는지 본다. 매매는 다른 매매 체결 계약이 있으면, 임대는 기간이 하루라도 겹치는 임대 체결 계약이 있으면 겹친다. 중복 제외한 계약은
   * 보지 않는다.
   */
  public boolean hasActiveConflict(
      Long propertyId, Long excludeContractId, DealType dealType, LocalDate start, LocalDate end) {
    String condition =
        dealType.isLease()
            ? " AND deal_type IN ('JEONSE','MONTHLY') AND lease_start_date <= :end AND"
                + " lease_end_date >= :start"
            : " AND deal_type = 'SALE'";
    JdbcClient.StatementSpec spec =
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM contract WHERE property_id = :property AND"
                    + " contract_status = 'SIGNED' AND duplicate_excluded_at IS NULL AND id <>"
                    + " :exclude"
                    + condition
                    + ")")
            .param("property", propertyId)
            .param("exclude", excludeContractId == null ? -1L : excludeContractId);
    if (dealType.isLease()) {
      spec = spec.param("start", start).param("end", end);
    }
    return Boolean.TRUE.equals(spec.query(Boolean.class).single());
  }

  /** 체결된 거래유형의 활성 거래제안을 완료로 바꾼다. 다른 거래유형의 제안은 그대로 둔다. */
  public void completeOffers(Long propertyId, DealType dealType, Instant now) {
    jdbcClient
        .sql(
            "UPDATE property_offer SET status = 'COMPLETED', updated_at = :now WHERE property_id"
                + " = :property AND deal_type = :dealType AND status IN"
                + " ('AVAILABLE','NEGOTIATING')")
        .param("now", Timestamp.from(now))
        .param("property", propertyId)
        .param("dealType", dealType.name())
        .update();
  }

  /** 계약 담당자는 같은 사무소의 승인된 대표 또는 소속공인중개사여야 한다. */
  public boolean isContractManager(Long officeId, Long membershipId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM office_membership WHERE id = :id AND office_id ="
                    + " :office AND status = 'APPROVED' AND role IN ('OWNER','AGENT'))")
            .param("id", membershipId)
            .param("office", officeId)
            .query(Boolean.class)
            .single());
  }

  /** 같은 사무소 고객이면 병합 이력을 따라간 최종 고객 ID를 돌려준다. */
  public Optional<Long> finalCustomerInOffice(Long officeId, Long customerId) {
    return jdbcClient
        .sql(
            "WITH RECURSIVE up(id, depth) AS (SELECT CAST(:id AS bigint), 0 UNION ALL SELECT"
                + " l.primary_customer_id, up.depth + 1 FROM customer_merge_log l JOIN up ON"
                + " l.merged_customer_id = up.id WHERE up.depth < 100) SELECT c.id FROM up JOIN"
                + " customer c ON c.id = up.id WHERE c.office_id = :office AND EXISTS (SELECT 1"
                + " FROM customer s WHERE s.id = :id AND s.office_id = :office) ORDER BY"
                + " up.depth DESC LIMIT 1")
        .param("id", customerId)
        .param("office", officeId)
        .query(Long.class)
        .optional();
  }

  /** 중개보수 지급의무자로 참조된 당사자 ID다. */
  public Set<Long> feePayerPartyIds(Long contractId) {
    return new HashSet<>(
        jdbcClient
            .sql("SELECT DISTINCT payer_party_id FROM contract_fee WHERE contract_id = :id")
            .param("id", contractId)
            .query(Long.class)
            .list());
  }

  /** 승인된 대표 소속 ID 가운데 가장 앞선 것이다. */
  public Optional<Long> ownerMembershipId(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT id FROM office_membership WHERE office_id = :office AND role = 'OWNER' AND"
                + " status = 'APPROVED' ORDER BY id LIMIT 1")
        .param("office", officeId)
        .query(Long.class)
        .optional();
  }

  public long countOpenManaged(Long officeId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM contract WHERE office_id = :office AND managing_membership_id ="
                + " :member AND contract_status IN ('DRAFT','SIGNED')")
        .param("office", officeId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  public int reassignOpen(Long officeId, Long fromMembershipId, Long toMembershipId, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE contract SET managing_membership_id = :to,"
                + BUMP
                + " WHERE office_id = :office AND managing_membership_id = :from AND"
                + " contract_status IN ('DRAFT','SIGNED')")
        .param("to", toMembershipId)
        .param("now", Timestamp.from(now))
        .param("office", officeId)
        .param("from", fromMembershipId)
        .update();
  }

  /** 체결된 전세·월세 가운데 임대기간 표시값이 오늘 기준 값과 다른 계약 ID다. */
  public List<Long> leaseStatusOutdated(LocalDate today) {
    return jdbcClient
        .sql(
            "SELECT id FROM contract WHERE contract_status = 'SIGNED' AND deal_type IN"
                + " ('JEONSE','MONTHLY') AND lease_period_status IS DISTINCT FROM (CASE WHEN"
                + " :today < lease_start_date THEN 'BEFORE_START' WHEN :today > lease_end_date"
                + " THEN 'MATURITY_CONFIRMATION_NEEDED' ELSE 'IN_PROGRESS' END) ORDER BY id")
        .param("today", today)
        .query(Long.class)
        .list();
  }

  public Optional<ContractRow> lockById(Long contractId) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM contract c WHERE c.id = :id FOR UPDATE SKIP LOCKED")
        .param("id", contractId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, ContractValues v) {
    return spec.param("dealType", v.dealType().name())
        .param("manager", v.managingMembershipId())
        .param("signedDate", v.signedDate())
        .param("salePrice", v.salePrice())
        .param("deposit", v.depositAmount())
        .param("rent", v.monthlyRentAmount())
        .param("balanceDue", v.balanceDueDate())
        .param("pendingReason", v.balanceDueDatePendingReason())
        .param("leaseStart", v.leaseStartDate())
        .param("leaseEnd", v.leaseEndDate())
        .param("memo", v.memo());
  }

  static ContractRow row(ResultSet rs) throws SQLException {
    String lease = rs.getString("lease_period_status");
    return new ContractRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("property_id"),
        rs.getObject("previous_contract_id", Long.class),
        new ContractValues(
            DealType.valueOf(rs.getString("deal_type")),
            rs.getLong("managing_membership_id"),
            rs.getObject("signed_date", LocalDate.class),
            rs.getObject("sale_price", Long.class),
            rs.getObject("deposit_amount", Long.class),
            rs.getObject("monthly_rent_amount", Long.class),
            rs.getObject("balance_due_date", LocalDate.class),
            rs.getString("balance_due_date_pending_reason"),
            rs.getObject("lease_start_date", LocalDate.class),
            rs.getObject("lease_end_date", LocalDate.class),
            rs.getString("memo")),
        ContractStatus.valueOf(rs.getString("contract_status")),
        lease == null ? null : LeasePeriodStatus.valueOf(lease),
        SettlementStatus.valueOf(rs.getString("settlement_status")),
        rs.getObject("duplicate_of_contract_id", Long.class),
        instant(rs, "duplicate_excluded_at"),
        rs.getString("duplicate_exclusion_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getLong("version"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
