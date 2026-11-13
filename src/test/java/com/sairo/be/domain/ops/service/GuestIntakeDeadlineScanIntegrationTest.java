package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.OfficeBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class GuestIntakeDeadlineScanIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private GuestIntakeDeadlineScanService scanService;
  @Autowired private OpsConsoleQueryRepository queries;
  @Autowired private com.sairo.be.domain.office.repository.OfficeRepository officeRepository;

  private long officeId() {
    return officeRepository.saveAndFlush(OfficeBuilder.anOffice().build()).getId();
  }

  private void intake(long officeId, String status, String dueFromNow) {
    boolean closed = status.equals("CLOSED");
    jdbcTemplate.update(
        "INSERT INTO legal_guest_privacy_intake (office_id, link_kind, link_id, request_type,"
            + " requester_role, contact_channel, contact_encrypted, received_at, internal_due_at,"
            + " status, closed_at, closed_reason) VALUES (?, 'CUSTOMER', 1, 'ACCESS', 'SELF',"
            + " 'PHONE', 'x', now() - interval '10 days', now() + ?::interval, ?, ?, ?)",
        officeId,
        dueFromNow,
        status,
        closed ? java.sql.Timestamp.from(Instant.now()) : null,
        closed ? "DUPLICATE" : null);
  }

  @Test
  void 기한_임박과_기한_초과_건수를_세고_초과가_있으면_실패_행으로_남는다() {
    long office = officeId();
    intake(office, "RECEIVED", "2 days");
    intake(office, "RECEIVED", "-1 day");
    intake(office, "RECEIVED", "10 days");
    intake(office, "CLOSED", "-5 days");

    var counts =
        queries.guestIntakeDeadlineCounts(Instant.now(), Instant.now().plus(Duration.ofDays(3)));
    assertThat(counts.approaching()).isEqualTo(2);
    assertThat(counts.overdue()).isEqualTo(1);

    assertThat(scanService.runScheduled(Instant.now())).isFalse();
    List<Map<String, Object>> runs =
        jdbcTemplate.queryForList(
            "SELECT status, error_code FROM system_job_run WHERE job_name ="
                + " 'guest-intake-deadline-scan'");
    assertThat(runs).hasSize(1);
    assertThat(runs.get(0))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "GUEST_INTAKE_OVERDUE");
  }

  @Test
  void 기한을_넘긴_접수가_없으면_성공으로_끝난다() {
    long office = officeId();
    intake(office, "RECEIVED", "2 days");

    assertThat(scanService.runScheduled(Instant.now())).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'guest-intake-deadline-scan'",
                String.class))
        .isEqualTo("SUCCESS");
  }
}
