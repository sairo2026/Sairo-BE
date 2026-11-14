package com.sairo.be.support;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** 운영 콘솔(BE-10a) 통합 시험 도구다. */
public abstract class OpsConsoleTestBase extends OpsIntegrationTestBase {

  @Autowired protected OfficeRepository officeRepository;
  @Autowired protected OfficeMembershipRepository officeMembershipRepository;

  private long sequence;

  protected Cookie sessionOf(Operator operator) {
    return operatorSession(operator);
  }

  protected Operator operator(String name, OperatorRole... roles) {
    sequence++;
    return createOperator("ops-" + System.nanoTime() + "-" + sequence, name, roles);
  }

  protected void stepUp(Cookie cookie) {
    setSessionAttribute(
        cookie, OperatorStepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().plusSeconds(300));
  }

  protected void expireStepUp(Cookie cookie) {
    setSessionAttribute(
        cookie, OperatorStepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().minusSeconds(1));
  }

  protected TestData.Staff staff(OfficeMembershipRole role) {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository)
        .approvedStaff(role);
  }

  protected Cookie memberSession(TestData.Staff staff) {
    return sessions.cookie(
        sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
  }

  protected ResultActions post(String path, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? jsonPost(path, body) : jsonPost(path, body, cookie));
  }

  protected long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  protected long auditCount(String actionType) {
    return count("SELECT count(*) FROM ops_audit_log WHERE action_type = ?", actionType);
  }

  protected long insertInquiry(long officeId, long requesterUserId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO support_inquiry (office_id, requester_user_id, category, status, content,"
            + " response_due_at) VALUES (?, ?, 'BUG', 'OPEN', '문의 본문', now() + interval '2"
            + " days') RETURNING id",
        Long.class,
        officeId,
        requesterUserId);
  }
}
