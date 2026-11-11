package com.sairo.be.domain.contract.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

abstract class ContractIntegrationTestBase extends CustomerIntegrationTestBase {

  protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

  protected TestData.Staff owner;
  protected Cookie ownerCookie;
  protected long officeId;
  protected long ownerMembership;
  protected long property;

  @BeforeEach
  void setUpContractOffice() throws Exception {
    owner = newStaff(OfficeMembershipRole.OWNER);
    ownerCookie = cookieOf(owner);
    officeId = owner.office().getId();
    ownerMembership = owner.membership().getId();
    property = addressedProperty(ownerCookie, "계약 매물", "서울시 마포구 합정동 1");
  }

  protected static LocalDate today() {
    return LocalDate.now(KST);
  }

  protected long addressedProperty(Cookie cookie, String name, String address) throws Exception {
    long id = createProperty(cookie, name, categoryIdOf("APARTMENT"));
    jdbcTemplate.update("UPDATE property SET address = ? WHERE id = ?", address, id);
    return id;
  }

  protected static String jeonse(
      long manager, LocalDate signed, LocalDate start, LocalDate end, String extra) {
    return "{\"dealType\":\"JEONSE\",\"managingMembershipId\":"
        + manager
        + ",\"signedDate\":\""
        + signed
        + "\",\"depositAmount\":300000000,\"leaseStartDate\":\""
        + start
        + "\",\"leaseEndDate\":\""
        + end
        + "\",\"balanceDueDate\":\""
        + start
        + "\",\"sellerLandlordParties\":[{\"name\":\"김임대\",\"phone\":\"01012345678\"}],"
        + "\"buyerTenantParties\":[{\"name\":\"이임차\"}]"
        + extra
        + "}";
  }

  protected static String sale(long manager, LocalDate signed, String extra) {
    return "{\"dealType\":\"SALE\",\"managingMembershipId\":"
        + manager
        + ",\"signedDate\":\""
        + signed
        + "\",\"salePrice\":500000000,\"balanceDueDatePendingReason\":\"잔금일 협의 중\","
        + "\"sellerLandlordParties\":[{\"name\":\"박매도\"},{\"name\":\"최매도\"}],"
        + "\"buyerTenantParties\":[{\"name\":\"정매수\"}]"
        + extra
        + "}";
  }

  protected JsonNode create(long propertyId, String body, Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(jsonPostAs("/api/properties/" + propertyId + "/contracts", body, cookie))
            .andExpect(status().isCreated())
            .andReturn());
  }

  protected long createJeonse(long propertyId, LocalDate start, LocalDate end) throws Exception {
    return create(propertyId, jeonse(ownerMembership, today(), start, end, ""), ownerCookie)
        .get("contractId")
        .longValue();
  }

  protected JsonNode getJson(String url, Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
        mockMvc.perform(authedGet(url, cookie)).andExpect(status().isOk()).andReturn());
  }

  protected long contractVersion(long contractId) {
    return longValue("SELECT version FROM contract WHERE id = ?", contractId);
  }

  protected long longValue(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? -1 : value;
  }

  protected String text(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }
}
