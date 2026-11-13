package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OpsOwnerTransferListIntegrationTest extends OpsConsoleTestBase {

  private static final String TRANSFERS = "/api/ops/owner-transfers";

  private Cookie reviewSession;

  private record Transfer(long id, TestData.Staff owner) {}

  @BeforeEach
  void setUp() {
    reviewSession = sessionOf(operator("김심사", OperatorRole.REVIEW));
  }

  private Transfer transfer(String status, int requestedDaysAgo, Integer acceptedDaysAgo) {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    AppUser candidateUser =
        appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().name("새대표").build());
    TestData.Staff candidate =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaffIn(owner.office(), candidateUser, OfficeMembershipRole.AGENT);
    long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO office_owner_transfer (office_id, from_membership_id, to_membership_id,"
                + " status, requested_at, accept_deadline_at) VALUES (?, ?, ?, ?, now() - (? *"
                + " interval '1 day'), now() - (? * interval '1 day') + interval '7 days')"
                + " RETURNING id",
            Long.class,
            owner.office().getId(),
            owner.membership().getId(),
            candidate.membership().getId(),
            status,
            requestedDaysAgo,
            requestedDaysAgo);
    if (acceptedDaysAgo != null) {
      jdbcTemplate.update(
          "UPDATE office_owner_transfer SET accepted_at = now() - (? * interval '1 day') WHERE id"
              + " = ?",
          acceptedDaysAgo,
          id);
    }
    if (status.equals("COMPLETED")) {
      jdbcTemplate.update("UPDATE office_owner_transfer SET completed_at = now() WHERE id = ?", id);
    }
    return new Transfer(id, owner);
  }

  @Test
  void 대표_이전_목록은_세션이_없으면_401이고_심사_역할이_없으면_403이다() throws Exception {
    assertError(mockMvc.perform(get(TRANSFERS)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(get(TRANSFERS).cookie(sessionOf(operator("최운영", OperatorRole.OPS)))),
        ErrorCode.FORBIDDEN);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(get(TRANSFERS).cookie(memberSession(owner))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 기본_목록은_운영자_승인_대기_건만_수락_순으로_주고_승인_화면에_필요한_값을_담는다() throws Exception {
    Transfer later = transfer("UNDER_REVIEW", 5, 1);
    Transfer earlier = transfer("UNDER_REVIEW", 4, 3);
    transfer("PENDING", 2, null);
    transfer("COMPLETED", 9, 8);

    mockMvc
        .perform(get(TRANSFERS).cookie(reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].transferId").value(earlier.id()))
        .andExpect(jsonPath("$.items[0].officeId").value(earlier.owner().office().getId()))
        .andExpect(jsonPath("$.items[0].officeName").value(earlier.owner().office().getName()))
        .andExpect(jsonPath("$.items[0].fromDisplayName").value(earlier.owner().user().getName()))
        .andExpect(jsonPath("$.items[0].toDisplayName").value("새대표"))
        .andExpect(jsonPath("$.items[0].status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.items[0].requestedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].acceptDeadlineAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].acceptedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].completedAt").value(nullValue()))
        .andExpect(jsonPath("$.items[1].transferId").value(later.id()));
  }

  @Test
  void 상태와_사무소로_거르고_허용하지_않는_값은_400이다() throws Exception {
    transfer("UNDER_REVIEW", 5, 1);
    Transfer pending = transfer("PENDING", 2, null);
    Transfer completed = transfer("COMPLETED", 9, 8);

    mockMvc
        .perform(get(TRANSFERS).param("status", "PENDING,COMPLETED").cookie(reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].transferId").value(completed.id()))
        .andExpect(jsonPath("$.items[0].completedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[1].transferId").value(pending.id()))
        .andExpect(jsonPath("$.items[1].acceptedAt").value(nullValue()));
    mockMvc
        .perform(
            get(TRANSFERS)
                .param("status", "PENDING,UNDER_REVIEW,COMPLETED")
                .param("officeId", String.valueOf(pending.owner().office().getId()))
                .cookie(reviewSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].transferId").value(pending.id()));
    mockMvc
        .perform(get(TRANSFERS).param("status", "REJECTED").cookie(reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    assertFieldError(
        mockMvc.perform(get(TRANSFERS).param("status", "PENDING,DONE").cookie(reviewSession)),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(get(TRANSFERS).param("officeId", "0").cookie(reviewSession)),
        "officeId",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(get(TRANSFERS).param("size", "10").cookie(reviewSession)),
        "size",
        "NOT_ALLOWED");
  }
}
