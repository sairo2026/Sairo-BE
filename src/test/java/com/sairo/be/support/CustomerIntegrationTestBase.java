package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

public abstract class CustomerIntegrationTestBase extends PropertyIntegrationTestBase {

  protected String customerBody(String name, String phone, long managerId, String extra) {
    return "{\"isCorporation\":false,\"name\":\""
        + name
        + "\",\"managingMembershipId\":"
        + managerId
        + ",\"dataSource\":\"방문상담\",\"processingBasis\":\"CONSENT\","
        + "\"dataPurpose\":\"매물 상담\",\"retentionUntil\":\"2030-01-01\""
        + (phone == null ? "" : ",\"phone\":\"" + phone + "\"")
        + extra
        + "}";
  }

  protected long createCustomer(Cookie cookie, String name, String phone, long managerId)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs("/api/customers", customerBody(name, phone, managerId, ""), cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("customerId")
        .longValue();
  }

  protected long customerVersion(long customerId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM customer WHERE id = ?", Long.class, customerId);
    return version == null ? -1 : version;
  }

  protected long createConsultationRow(long customerId, long membershipId, String type) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO customer_consultation (customer_id, occurred_at, author_membership_id,"
                + " content, consultation_type) VALUES (?, now(), ?, '문의', ?) RETURNING id",
            Long.class,
            customerId,
            membershipId,
            type);
    return id == null ? -1 : id;
  }

  protected void mergeRow(long primary, long merged, long membershipId) {
    jdbcTemplate.update(
        "INSERT INTO customer_merge_log (primary_customer_id, merged_customer_id,"
            + " executed_by_membership_id) VALUES (?, ?, ?)",
        primary,
        merged,
        membershipId);
  }
}
