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
    long deputies = membershipRepository.countApprovedByRole(officeId, OfficeMembershipRole.DEPUTY);
    return new Quota(agents, deputies, agents * MULTIPLIER);
  }

  public boolean wouldExceedWithAdditionalDeputy(long officeId) {
    Quota quota = current(officeId);
    return quota.deputyCount() + 1 > quota.deputyLimit();
  }
}
