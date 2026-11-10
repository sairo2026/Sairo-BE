package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

public abstract class PropertyIntegrationTestBase extends AuthIntegrationTestBase {

  @Autowired protected OfficeRepository officeRepository;
  @Autowired protected OfficeMembershipRepository officeMembershipRepository;
  @Autowired protected InMemoryObjectStorage objectStorage;

  protected TestData testData() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  protected TestData.Staff newStaff(OfficeMembershipRole role) {
    return testData().approvedStaff(role);
  }

  protected TestData.Staff staffIn(Office office, OfficeMembershipRole role) {
    AppUser user = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    return testData().approvedStaffIn(office, user, role);
  }

  protected Cookie cookieOf(TestData.Staff staff) {
    return sessions.cookie(
        sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
  }

  protected long categoryIdOf(String code) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM property_category WHERE code = ? AND office_id IS NULL",
            Long.class,
            code);
    return id == null ? -1 : id;
  }

  protected long fieldIdOf(String categoryCode, String fieldName) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT d.id FROM property_custom_field_definition d JOIN property_category c ON"
                + " c.id = d.category_id WHERE c.code = ? AND c.office_id IS NULL AND d.field_name"
                + " = ?",
            Long.class,
            categoryCode,
            fieldName);
    return id == null ? -1 : id;
  }

  protected static MockHttpServletRequestBuilder withSession(
      MockHttpServletRequestBuilder builder, Cookie cookie) {
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return AuthFlowSupport.withCsrf(builder);
  }

  protected static MockHttpServletRequestBuilder authedGet(String url, Cookie cookie) {
    MockHttpServletRequestBuilder builder = get(url);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return builder;
  }

  protected static MockHttpServletRequestBuilder jsonPostAs(
      String url, String body, Cookie cookie) {
    return withSession(post(url).contentType(MediaType.APPLICATION_JSON).content(body), cookie);
  }

  protected static MockHttpServletRequestBuilder jsonPatchAs(
      String url, String body, Cookie cookie, Object version) {
    MockHttpServletRequestBuilder builder =
        patch(url).contentType(MediaType.APPLICATION_JSON).content(body);
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    return withSession(builder, cookie);
  }

  protected static MockHttpServletRequestBuilder jsonPutAs(
      String url, String body, Cookie cookie, Object version) {
    MockHttpServletRequestBuilder builder =
        put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    return withSession(builder, cookie);
  }

  protected static MockHttpServletRequestBuilder deleteAs(String url, Cookie cookie) {
    return withSession(delete(url), cookie);
  }

  protected static org.springframework.test.web.servlet.RequestBuilder photoUpload(
      String url, Cookie cookie, byte[] content, String contentType, String description) {
    var builder = multipart(url).file(new MockMultipartFile("file", "photo", contentType, content));
    if (description != null) {
      builder.param("description", description);
    }
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return builder
        .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
        .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
  }

  protected long createProperty(Cookie cookie, String name, long categoryId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/properties",
                    "{\"name\":\"" + name + "\",\"categoryId\":" + categoryId + "}",
                    cookie))
            .andReturn();
    JsonNode node = AuthFlowSupport.json(result);
    return node.get("propertyId").longValue();
  }

  protected long propertyVersion(long propertyId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM property WHERE id = ?", Long.class, propertyId);
    return version == null ? -1 : version;
  }

  protected void archive(long propertyId) {
    jdbcTemplate.update("UPDATE property SET deleted_at = now() WHERE id = ?", propertyId);
  }

  protected long insertCustomer(long officeId, long membershipId, String name) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO customer (office_id, name, managing_membership_id, data_source,"
                + " data_purpose, retention_until) VALUES (?, ?, ?, '직접 입력', '상담', now() +"
                + " interval '1 year') RETURNING id",
            Long.class,
            officeId,
            name,
            membershipId);
    return id == null ? -1 : id;
  }

  protected long linkOwnerOccupant(
      long officeId, long propertyId, long customerId, String role, String startDate) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO property_owner_occupant (office_id, property_id, customer_id, role,"
                + " start_date) VALUES (?, ?, ?, ?, ?::date) RETURNING id",
            Long.class,
            officeId,
            propertyId,
            customerId,
            role,
            startDate);
    return id == null ? -1 : id;
  }

  protected long insertOffer(
      long propertyId, String dealType, String status, Long sale, Long deposit, Long rent) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO property_offer (property_id, deal_type, status, sale_price,"
                + " deposit_amount, monthly_rent_amount) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            propertyId,
            dealType,
            status,
            sale,
            deposit,
            rent);
    return id == null ? -1 : id;
  }
}
