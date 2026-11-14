package com.sairo.be.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class WithdrawalHandoverIntegrationTest extends VisitOutcomeTestBase {

  private record Office(TestData.Staff owner, TestData.Staff agent, SelectionFixture visit) {}

  /** 확정 방문 하나가 있는 사무소에 이메일 로그인 소속공인중개사를 더하고 그 조율의 담당자를 소속공인중개사로 바꾼다. */
  private Office officeWithAgent(String email, String phone) throws Exception {
    SelectionFixture s = confirmed();
    TestData.Staff owner = s.base().owner();
    AppUser user = saveEmailUser(email, phone);
    TestData.Staff agent =
        testData().approvedStaffIn(owner.office(), user, OfficeMembershipRole.AGENT);
    jdbcTemplate.update(
        "UPDATE visit_coordination SET manager_membership_id = ? WHERE id = ?",
        agent.membership().getId(),
        s.coordinationId());
    return new Office(owner, agent, s);
  }

  private long insertTask(long officeId, long assignee, String status) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO task (office_id, title, assignee_membership_id, due_date, status,"
            + " completed_at) VALUES (?, '전화', ?, current_date, ?, CASE WHEN ? = 'DONE' THEN"
            + " now() ELSE NULL END) RETURNING id",
        Long.class,
        officeId,
        assignee,
        status,
        status);
  }

  private long managerOf(String table, String column, long id) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", Long.class, id);
  }

  /** 대표가 같은 시각에 이미 다른 방문을 확정해 둔 상태를 만든다. */
  private void ownerBusyAtSameTime(Office office) {
    long ownerMembership = office.owner().membership().getId();
    long officeId = office.owner().office().getId();
    long customer = insertCustomer(officeId, ownerMembership, "다른고객");
    long candidate =
        insertCoordinationWithCandidate(
            new TestStaffRef(officeId, ownerMembership),
            customer,
            office.visit().base().propertyId(),
            "CONFIRMED");
    long coordination =
        jdbcTemplate.queryForObject(
            "SELECT coordination_id FROM visit_candidate_property WHERE id = ?",
            Long.class,
            candidate);
    jdbcTemplate.update(
        "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
            + " scheduled_start_at, scheduled_end_at, sort_order, result) SELECT ?, office_id, ?,"
            + " scheduled_start_at, scheduled_end_at, 1, 'SCHEDULED' FROM visit_schedule_item"
            + " WHERE id = ?",
        coordination,
        candidate,
        scheduledItem(office.visit().coordinationId()));
  }

  @Test
  void 탈퇴하면_진행_중_고객_매물_업무_방문_조율을_대표에게_넘기고_겹치는_확정_방문은_재조율_필요로_표시한다() throws Exception {
    Office office = officeWithAgent("leaver@example.com", "010-4444-0001");
    long officeId = office.owner().office().getId();
    long agentId = office.agent().membership().getId();
    long ownerId = office.owner().membership().getId();
    long customer = insertCustomer(officeId, agentId, "담당고객");
    long archivedCustomer = insertCustomer(officeId, agentId, "보관고객");
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archivedCustomer);
    long property = office.visit().base().propertyId();
    jdbcTemplate.update(
        "UPDATE property SET managing_membership_id = ? WHERE id = ?", agentId, property);
    long todo = insertTask(officeId, agentId, "TODO");
    long done = insertTask(officeId, agentId, "DONE");
    ownerBusyAtSameTime(office);
    long coordination = office.visit().coordinationId();
    assertThat(coordinationStatus(coordination)).isEqualTo("CONFIRMED");

    mockMvc
        .perform(authedGet("/api/memberships/" + agentId, cookieOf(office.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedCounts.customers").value(1))
        .andExpect(jsonPath("$.assignedCounts.properties").value(1))
        .andExpect(jsonPath("$.assignedCounts.tasks").value(1))
        .andExpect(jsonPath("$.assignedCounts.confirmedVisits").value(1));

    Cookie cookie = login("leaver@example.com", PASSWORD);
    grantStepUp(cookie);
    mockMvc
        .perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(cookie)))
        .andExpect(status().isNoContent());

    assertThat(managerOf("customer", "managing_membership_id", customer)).isEqualTo(ownerId);
    assertThat(managerOf("customer", "managing_membership_id", archivedCustomer))
        .isEqualTo(agentId);
    assertThat(managerOf("property", "managing_membership_id", property)).isEqualTo(ownerId);
    assertThat(managerOf("task", "assignee_membership_id", todo)).isEqualTo(ownerId);
    assertThat(managerOf("task", "assignee_membership_id", done)).isEqualTo(agentId);
    assertThat(managerOf("visit_coordination", "manager_membership_id", coordination))
        .isEqualTo(ownerId);
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, reschedule_trigger_type, reschedule_reason FROM visit_coordination"
                + " WHERE id = ?",
            coordination);
    assertThat(row.get("status")).isEqualTo("RESCHEDULE_REQUIRED");
    assertThat(row.get("reschedule_trigger_type")).isEqualTo("ASSIGNEE_HANDOVER");
    assertThat(
            count(
                "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ?"
                    + " AND to_status = 'RESCHEDULE_REQUIRED' AND source = 'SYSTEM'",
                coordination))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM visit_customer_link WHERE coordination_id = ? AND"
                    + " revoked_at IS NULL",
                coordination))
        .isZero();

    mockMvc
        .perform(authedGet("/api/memberships/" + agentId, cookieOf(office.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("탈퇴한 구성원 1"))
        .andExpect(jsonPath("$.assignedCounts.customers").value(0))
        .andExpect(jsonPath("$.assignedCounts.tasks").value(0))
        .andExpect(jsonPath("$.assignedCounts.confirmedVisits").value(0));
  }

  @Test
  void 나가기도_업무를_넘기고_대표_일정과_겹치지_않는_확정_방문은_확정_상태로_둔다() throws Exception {
    Office office = officeWithAgent("mover@example.com", "010-4444-0002");
    long agentId = office.agent().membership().getId();
    long ownerId = office.owner().membership().getId();
    long customer = insertCustomer(office.owner().office().getId(), agentId, "담당고객");
    long coordination = office.visit().coordinationId();

    mockMvc
        .perform(jsonPostAs("/api/memberships/me/leave", "", cookieOf(office.agent())))
        .andExpect(status().isNoContent());

    assertThat(managerOf("customer", "managing_membership_id", customer)).isEqualTo(ownerId);
    assertThat(managerOf("visit_coordination", "manager_membership_id", coordination))
        .isEqualTo(ownerId);
    assertThat(coordinationStatus(coordination)).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE recipient_user_id = ? AND"
                    + " notification_type = 'ASSIGNEE_CHANGED' AND target_id = ?",
                office.owner().user().getId(),
                coordination))
        .isEqualTo(1);
  }
}
