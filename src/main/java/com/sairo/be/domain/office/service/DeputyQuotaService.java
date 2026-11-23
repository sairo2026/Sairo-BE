package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.MembershipRecordRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class DeputyQuotaService {

  public static final int MULTIPLIER = 5;

  public record Quota(long qualifiedCount, long deputyCount, long deputyLimit) {

    public boolean exceeded() {
      return deputyCount > deputyLimit;
    }
  }

  private final JdbcClient jdbcClient;
  private final MembershipRecordRepository membershipRepository;
  private final Clock clock;

  public DeputyQuotaService(
      JdbcClient jdbcClient, MembershipRecordRepository membershipRepository, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.membershipRepository = membershipRepository;
    this.clock = clock;
  }

  public Quota current(long officeId) {
    long agents =
        jdbcClient
            .sql(
                "SELECT count(*) FROM employment_roster WHERE office_id = :office"
                    + " AND legal_qualification = 'AGENT' AND (employment_end_date IS NULL"
                    + " OR employment_end_date >= :today)")
            .param("office", officeId)
            .param("today", java.sql.Date.valueOf(LocalDate.now(clock)))
            .query(Long.class)
            .single();
    agents += implicitOwnerCount(officeId);
    long deputies = membershipRepository.countApprovedByRole(officeId, OfficeMembershipRole.DEPUTY);
    return new Quota(agents, deputies, agents * MULTIPLIER);
  }

  /**
   * 개인 사무소의 대표는 개업공인중개사 본인이라 법정 한도의 분모(개업공인중개사와 소속공인중개사를 합한 수)에 들어간다. 대표가 명부에 직접 등록하지 않아도 1명으로
   * 세고, 명부에 이미 올라 있으면(구성원 연결 또는 같은 이름) 중복해 세지 않는다. 법인 사무소는 법인이 개업공인중개사라 명부의 자격 행만 센다.
   */
  private long implicitOwnerCount(long officeId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM office o JOIN office_membership m ON m.office_id = o.id"
                + " AND m.role = 'OWNER' AND m.status = 'APPROVED' JOIN app_user u ON u.id ="
                + " m.user_id WHERE o.id = :office AND o.entity_type = 'INDIVIDUAL' AND NOT EXISTS"
                + " (SELECT 1 FROM employment_roster r WHERE r.office_id = o.id AND"
                + " r.legal_qualification = 'AGENT' AND (r.employment_end_date IS NULL OR"
                + " r.employment_end_date >= :today) AND (r.membership_id = m.id OR r.person_name"
                + " = u.name))")
        .param("office", officeId)
        .param("today", java.sql.Date.valueOf(LocalDate.now(clock)))
        .query(Long.class)
        .single();
  }

  public boolean wouldExceedWithAdditionalDeputy(long officeId) {
    Quota quota = current(officeId);
    return quota.deputyCount() + 1 > quota.deputyLimit();
  }
}
