package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.billing.service.ReturnGrantService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

abstract class GrantTestBase extends BillingIntegrationTestBase {

  @Autowired protected ReturnGrantService returnGrantService;

  protected record Holder(AppUser user, TestData.Staff former) {}

  protected Holder emailHolder() {
    TestData.Staff owner = owner();
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser()
                .emailLogin("holder" + System.nanoTime() + "@example.com", PASSWORD)
                .build());
    TestData.Staff former =
        data().approvedStaffIn(owner.office(), user, OfficeMembershipRole.AGENT);
    return new Holder(user, former);
  }

  protected long returnGrant(Holder holder) {
    return returnGrantService
        .issue(holder.former().office().getId(), holder.user().getId(), Instant.now())
        .getId();
  }

  protected long archiveGrant(long officeId, long userId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO archive_grant (office_id, granted_to_user_id, contract_reference)"
            + " VALUES (?, ?, 'ARCHIVE-1') RETURNING id",
        Long.class,
        officeId,
        userId);
  }

  protected static String grantAuthBody(long grantId, String method, String credential) {
    return "{\"grantId\":"
        + grantId
        + ",\"authMethod\":\""
        + method
        + "\",\"credential\":\""
        + credential
        + "\"}";
  }

  protected Cookie grantSession(String path, long grantId, String method, String credential)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(postRequest(path, grantAuthBody(grantId, method, credential)).apply(null))
            .andReturn();
    return AuthFlowSupport.sessionCookie(result);
  }

  protected Cookie forgedGrantSession(GrantType type, long grantId, long userId, long officeId) {
    return sessions.cookie(
        sessions.createGrantSession(
            new GrantPrincipal(userId, grantId, type, officeId), Instant.now()));
  }

  protected void expireReturnGrant(long grantId) {
    Timestamp granted = Timestamp.from(Instant.now().minusSeconds(86_400L * 31));
    jdbcTemplate.update(
        "UPDATE return_grant SET granted_at = ?, expires_at = ? WHERE id = ?",
        granted,
        Timestamp.from(granted.toInstant().plusSeconds(86_400L * 30)),
        grantId);
  }
}
