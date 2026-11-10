package com.sairo.be.domain.office.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OfficeRegistrationIntegrationTest extends AuthIntegrationTestBase {

  private static final byte[] PDF = "%PDF-1.4 evidence".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
  };

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;
  @Autowired private InMemoryObjectStorage objectStorage;

  private AppUser user;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    user = saveEmailUser("applicant@example.com", "010-1111-2222");
    session = login("applicant@example.com", PASSWORD);
  }

  private TestData testData() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  private String createBody(String license, String business, String type) {
    return "{\"entityType\":\""
        + type
        + "\",\"registrationAuthority\":\"서울특별시 종로구\",\"realEstateLicenseNumber\":\""
        + license
        + "\",\"businessRegistrationNumber\":\""
        + business
        + "\",\"requestedOfficeName\":\"사이로공인중개사\",\"requestedRepresentativeName\":\"홍대표\","
        + "\"requestedRepresentativePhone\":\"02-123-4567\",\"requestedAddress\":\"서울 종로구 1\","
        + "\"applicantRegistrationRelation\":\"REPRESENTATIVE\"}";
  }

  private long createRegistration(Cookie cookie, String license, String business) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPost(
                    "/api/office-registrations",
                    createBody(license, business, "INDIVIDUAL"),
                    cookie))
            .andExpect(status().isCreated())
            .andReturn();
    return AuthFlowSupport.json(result).get("registrationId").asLong();
  }

  private MockMultipartHttpServletRequestBuilder evidenceRequest(
      long registrationId, Cookie cookie, Map<String, MockMultipartFile> parts) {
    MockMultipartHttpServletRequestBuilder builder =
        multipart("/api/office-registrations/{id}/evidence", registrationId);
    parts.forEach(
        (type, file) -> {
          builder.param("evidenceType", type);
          builder.file(file);
        });
    builder.cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE));
    builder.header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return builder;
  }

  private ResultActions submit(long registrationId, Cookie cookie, String... types)
      throws Exception {
    java.util.LinkedHashMap<String, MockMultipartFile> parts = new java.util.LinkedHashMap<>();
    for (String type : types) {
      parts.put(type, new MockMultipartFile("file", type + ".pdf", "application/pdf", PDF));
    }
    return mockMvc.perform(evidenceRequest(registrationId, cookie, parts));
  }

  private static final String[] REQUIRED = {
    "BROKERAGE_LICENSE", "BUSINESS_CERTIFICATE", "REPRESENTATIVE_QUALIFICATION"
  };

  private String statusOf(long registrationId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM office_registration WHERE id = ?", String.class, registrationId);
  }

  private void forceStatus(long registrationId, String status) {
    switch (status) {
      case "REJECTED" ->
          jdbcTemplate.update(
              "UPDATE office_registration SET status = 'REJECTED', reviewed_at = now(),"
                  + " rejection_reason = '사유' WHERE id = ?",
              registrationId);
      case "SUPPLEMENT_REQUESTED" ->
          jdbcTemplate.update(
              "UPDATE office_registration SET status = 'SUPPLEMENT_REQUESTED', reviewed_at = now(),"
                  + " supplement_reason = '서류 보완', supplement_due_at = now() + interval '3 days'"
                  + " WHERE id = ?",
              registrationId);
      default -> throw new IllegalArgumentException(status);
    }
  }

  @Test
  void 조회는_등록된_사무소가_있으면_ID를_없으면_null을_준다() throws Exception {
    var staff = testData().approvedStaff(OfficeMembershipRole.OWNER);
    var office = staff.office();
    String found =
        "/api/offices/lookup?entityType=INDIVIDUAL&registrationAuthority="
            + "서울특별시 종로구&realEstateLicenseNumber="
            + office.getRealEstateLicenseNumber();
    mockMvc
        .perform(get(found).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.existingOfficeId").value(office.getId()));
    mockMvc
        .perform(
            get("/api/offices/lookup?entityType=INDIVIDUAL&registrationAuthority=서울&realEstateLicenseNumber=없음")
                .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.existingOfficeId").doesNotExist());
  }

  @Test
  void 조회는_필수_파라미터가_없으면_400이고_로그인이_없으면_401이다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/offices/lookup?entityType=INDIVIDUAL").cookie(session)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            get(
                "/api/offices/lookup?entityType=INDIVIDUAL&registrationAuthority=a&realEstateLicenseNumber=b")),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 신청_작성은_작성중_상태로_만들고_이력을_남긴다() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/office-registrations",
                createBody("A-1", "123-45-67890", "INDIVIDUAL"),
                session))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.currentRevisionNo").value(1))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.warning").doesNotExist());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT business_registration_number FROM office_registration", String.class))
        .isEqualTo("1234567890");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_status_history WHERE to_status = 'DRAFT'"
                    + " AND changed_by_type = 'APPLICANT' AND from_status IS NULL",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 신청_작성은_사업자번호가_기존_사무소와_겹치면_경고를_함께_준다() throws Exception {
    var staff = testData().approvedStaff(OfficeMembershipRole.OWNER);
    mockMvc
        .perform(
            jsonPost(
                "/api/office-registrations",
                createBody("B-1", staff.office().getBusinessRegistrationNumber(), "INDIVIDUAL"),
                session))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.warning").value("DUPLICATE_BUSINESS_NUMBER"));
  }

  @Test
  void 신청_작성은_형식이_틀리면_400이고_로그인이_없으면_401이다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-registrations", createBody("C-1", "12345", "INDIVIDUAL"), session)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPost("/api/office-registrations", "{\"entityType\":\"INDIVIDUAL\"}", session)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPost("/api/office-registrations", createBody("C-2", "1234567890", "INDIVIDUAL"))),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 신청_작성은_진행_중_신청이_있으면_409이다() throws Exception {
    createRegistration(session, "D-1", "1111111111");
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-registrations",
                createBody("D-2", "2222222222", "INDIVIDUAL"),
                session)),
        ErrorCode.PENDING_REGISTRATION_EXISTS);

    AppUser other = saveEmailUser("other@example.com", "010-3333-4444");
    Cookie otherSession = login("other@example.com", PASSWORD);
    assertThat(other.getId()).isNotNull();
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-registrations",
                createBody("D-3", "1111111111", "INDIVIDUAL"),
                otherSession)),
        ErrorCode.PENDING_REGISTRATION_EXISTS);
  }

  @Test
  void 신청_작성은_승인된_소속이나_진행_중_참여_요청이_있어도_409이다() throws Exception {
    var staff = testData().approvedStaff(OfficeMembershipRole.AGENT);
    Cookie staffCookie =
        sessions.cookie(
            sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-registrations",
                createBody("E-1", "3333333333", "INDIVIDUAL"),
                staffCookie)),
        ErrorCode.PENDING_REGISTRATION_EXISTS);

    var other = testData().approvedStaff(OfficeMembershipRole.OWNER);
    jdbcTemplate.update(
        "INSERT INTO office_membership (office_id, user_id, role, status) VALUES (?, ?, 'AGENT', 'PENDING')",
        other.office().getId(),
        user.getId());
    assertError(
        mockMvc.perform(createRequest(session, "E-2", "4444444444")),
        ErrorCode.PENDING_REGISTRATION_EXISTS);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(
      Cookie cookie, String license, String business) {
    return jsonPost(
        "/api/office-registrations", createBody(license, business, "INDIVIDUAL"), cookie);
  }

  @Test
  void 신청_작성은_같은_등록관청과_개설등록번호의_사무소가_있으면_409이다() throws Exception {
    var staff = testData().approvedStaff(OfficeMembershipRole.OWNER);
    String body =
        createBody(staff.office().getRealEstateLicenseNumber(), "5555555555", "INDIVIDUAL");
    assertError(
        mockMvc.perform(jsonPost("/api/office-registrations", body, session)),
        ErrorCode.OFFICE_ALREADY_EXISTS);
  }

  @Test
  void 증빙_제출은_필수_세_종류가_있으면_심사_중으로_전이하고_제출_버전과_이력을_만든다() throws Exception {
    long id = createRegistration(session, "F-1", "6666666666");
    submit(id, session, REQUIRED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.currentRevisionNo").value(1))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_evidence", Long.class))
        .isEqualTo(3L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_status_history WHERE to_status ="
                    + " 'PENDING_REVIEW'",
                Long.class))
        .isEqualTo(1L);
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM office_registration_evidence LIMIT 1", String.class);
    assertThat(objectStorage.find(key)).isPresent();
  }

  @Test
  void 증빙_제출은_필수_종류가_빠지면_400이고_상태는_그대로다() throws Exception {
    long id = createRegistration(session, "G-1", "7777777777");
    ResultActions result = submit(id, session, "BROKERAGE_LICENSE", "BUSINESS_CERTIFICATE");
    assertError(result, ErrorCode.INVALID_REQUEST);
    result.andExpect(
        jsonPath("$.fieldErrors[0].field").value("evidenceType.REPRESENTATIVE_QUALIFICATION"));
    assertThat(statusOf(id)).isEqualTo("DRAFT");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_revision", Long.class))
        .isZero();
  }

  @Test
  void 법인_신청은_법인_대표권한과_등록관계_증빙도_필수다() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/office-registrations",
                createBody("H-1", "8888888888", "CORPORATION"),
                session))
        .andExpect(status().isCreated());
    long id = jdbcTemplate.queryForObject("SELECT id FROM office_registration", Long.class);
    assertError(submit(id, session, REQUIRED), ErrorCode.INVALID_REQUEST);
    submit(
            id,
            session,
            "BROKERAGE_LICENSE",
            "BUSINESS_CERTIFICATE",
            "REPRESENTATIVE_QUALIFICATION",
            "CORPORATE_AUTHORITY",
            "REGISTRATION_RELATION")
        .andExpect(status().isOk());
  }

  @Test
  void 증빙_제출은_확장자_시그니처_용량_주민번호를_검사한다() throws Exception {
    long id = createRegistration(session, "I-1", "9999999999");
    assertError(
        send(id, "BROKERAGE_LICENSE", "a.exe", "application/octet-stream", PDF),
        ErrorCode.INVALID_REQUEST);
    assertError(
        send(id, "BROKERAGE_LICENSE", "a.pdf", "application/pdf", PNG), ErrorCode.INVALID_REQUEST);
    assertError(
        send(id, "BROKERAGE_LICENSE", "a.pdf", "application/pdf", new byte[0]),
        ErrorCode.INVALID_REQUEST);
    assertError(
        send(
            id,
            "BROKERAGE_LICENSE",
            "a.pdf",
            "application/pdf",
            "%PDF-1.4 900101-1234567".getBytes(StandardCharsets.ISO_8859_1)),
        ErrorCode.INVALID_REQUEST);
    byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, tooLarge, 0, PDF.length);
    ResultActions large = send(id, "BROKERAGE_LICENSE", "a.pdf", "application/pdf", tooLarge);
    assertError(large, ErrorCode.INVALID_REQUEST);
    large.andExpect(jsonPath("$.fieldErrors[0].field").value("evidenceType.BROKERAGE_LICENSE"));
    assertError(send(id, "UNKNOWN", "a.pdf", "application/pdf", PDF), ErrorCode.INVALID_REQUEST);
    assertThat(statusOf(id)).isEqualTo("DRAFT");
  }

  private ResultActions send(
      long id, String type, String filename, String contentType, byte[] bytes) throws Exception {
    java.util.LinkedHashMap<String, MockMultipartFile> parts = new java.util.LinkedHashMap<>();
    parts.put(type, new MockMultipartFile("file", filename, contentType, bytes));
    for (String required : REQUIRED) {
      parts.putIfAbsent(
          required, new MockMultipartFile("file", required + ".pdf", "application/pdf", PDF));
    }
    return mockMvc.perform(evidenceRequest(id, session, parts));
  }

  @Test
  void 증빙_제출은_이미지_파일도_받는다() throws Exception {
    long id = createRegistration(session, "J-1", "1010101010");
    java.util.LinkedHashMap<String, MockMultipartFile> parts = new java.util.LinkedHashMap<>();
    parts.put("BROKERAGE_LICENSE", new MockMultipartFile("file", "a.png", "image/png", PNG));
    parts.put(
        "BUSINESS_CERTIFICATE", new MockMultipartFile("file", "b.pdf", "application/pdf", PDF));
    parts.put(
        "REPRESENTATIVE_QUALIFICATION",
        new MockMultipartFile("file", "c.pdf", "application/pdf", PDF));
    mockMvc.perform(evidenceRequest(id, session, parts)).andExpect(status().isOk());
  }

  @Test
  void 증빙_제출은_작성중이나_보완요청이_아니면_409이고_남의_신청이면_404이고_로그인이_없으면_401이다() throws Exception {
    long id = createRegistration(session, "K-1", "1212121212");
    submit(id, session, REQUIRED).andExpect(status().isOk());
    assertError(submit(id, session, REQUIRED), ErrorCode.INVALID_TRANSITION);

    AppUser other = saveEmailUser("o2@example.com", "010-5555-6666");
    Cookie otherSession = login("o2@example.com", PASSWORD);
    assertThat(other.getId()).isNotNull();
    assertError(submit(id, otherSession, REQUIRED), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(submit(id, null, REQUIRED), ErrorCode.UNAUTHENTICATED);
    assertError(submit(99999L, session, REQUIRED), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 보완_재제출은_버전을_올리고_바뀌지_않은_증빙을_이어_받는다() throws Exception {
    long id = createRegistration(session, "L-1", "1313131313");
    submit(id, session, REQUIRED).andExpect(status().isOk());
    forceStatus(id, "SUPPLEMENT_REQUESTED");
    submit(id, session, "BUSINESS_CERTIFICATE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.currentRevisionNo").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_evidence e JOIN"
                    + " office_registration_revision r ON r.id = e.revision_id"
                    + " WHERE r.revision_no = 2",
                Long.class))
        .isEqualTo(3L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT supplement_reason FROM office_registration WHERE id = ?", String.class, id))
        .isNull();
  }

  @Test
  void 본인_신청_상태는_최신_신청을_돌려주고_없으면_404이고_로그인이_없으면_401이다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/office-registrations/me").cookie(session)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(get("/api/office-registrations/me")), ErrorCode.UNAUTHENTICATED);
    long id = createRegistration(session, "M-1", "1414141414");
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationId").value(id))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.supplementReason").doesNotExist())
        .andExpect(jsonPath("$.resolved").doesNotExist());

    submit(id, session, REQUIRED).andExpect(status().isOk());
    forceStatus(id, "SUPPLEMENT_REQUESTED");
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(session))
        .andExpect(jsonPath("$.supplementReason").value("서류 보완"))
        .andExpect(jsonPath("$.supplementDueAt").exists());
  }

  @Test
  void 반려된_신청은_사유와_해결_여부를_보여주고_재신청_시작이_해결됨으로_바꾼다() throws Exception {
    long id = createRegistration(session, "N-1", "1515151515");
    submit(id, session, REQUIRED);
    forceStatus(id, "REJECTED");
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(session))
        .andExpect(jsonPath("$.rejectionReason").value("사유"))
        .andExpect(jsonPath("$.resolved").value(false));

    mockMvc
        .perform(jsonPost("/api/office-registrations/" + id + "/reapplication-start", "", session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationId").value(id))
        .andExpect(jsonPath("$.resolved").value(true));
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(session))
        .andExpect(jsonPath("$.resolved").value(true));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_status_history WHERE from_status ="
                    + " 'REJECTED' AND to_status = 'REJECTED' AND changed_by_type = 'APPLICANT'",
                Long.class))
        .isEqualTo(1L);
    createRegistration(session, "N-2", "1616161616");
  }

  @Test
  void 재신청_시작은_이미_해결됐거나_반려가_아니면_409이고_남의_신청은_404이고_로그인이_없으면_401이다() throws Exception {
    long id = createRegistration(session, "O-1", "1717171717");
    String url = "/api/office-registrations/" + id + "/reapplication-start";
    assertError(mockMvc.perform(jsonPost(url, "", session)), ErrorCode.INVALID_TRANSITION);
    forceStatus(id, "REJECTED");
    mockMvc.perform(jsonPost(url, "", session)).andExpect(status().isOk());
    assertError(mockMvc.perform(jsonPost(url, "", session)), ErrorCode.INVALID_TRANSITION);

    saveEmailUser("o3@example.com", "010-7777-8888");
    Cookie other = login("o3@example.com", PASSWORD);
    assertError(mockMvc.perform(jsonPost(url, "", other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(jsonPost(url, "")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 취소는_작성중_심사중_보완요청_신청을_취소하고_이력을_남긴다() throws Exception {
    long id = createRegistration(session, "P-1", "1818181818");
    mockMvc
        .perform(
            jsonPost(
                "/api/office-registrations/" + id + "/cancel", "{\"reason\":\"마음이 바뀜\"}", session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledAt").exists());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM office_registration_status_history WHERE to_status ="
                    + " 'CANCELLED'",
                String.class))
        .isEqualTo("마음이 바뀜");

    long second = createRegistration(session, "P-2", "1919191919");
    submit(second, session, REQUIRED);
    mockMvc
        .perform(jsonPost("/api/office-registrations/" + second + "/cancel", "", session))
        .andExpect(status().isOk());

    long third = createRegistration(session, "P-3", "2020202020");
    submit(third, session, REQUIRED);
    forceStatus(third, "SUPPLEMENT_REQUESTED");
    mockMvc
        .perform(jsonPost("/api/office-registrations/" + third + "/cancel", "{}", session))
        .andExpect(status().isOk());
  }

  @Test
  void 취소는_종료된_신청이면_409이고_남의_신청은_404이고_로그인이_없으면_401이고_사유가_길면_400이다() throws Exception {
    long id = createRegistration(session, "Q-1", "2121212121");
    String url = "/api/office-registrations/" + id + "/cancel";
    assertError(
        mockMvc.perform(jsonPost(url, "{\"reason\":\"" + "가".repeat(501) + "\"}", session)),
        ErrorCode.INVALID_REQUEST);
    mockMvc.perform(jsonPost(url, "", session)).andExpect(status().isOk());
    assertError(mockMvc.perform(jsonPost(url, "", session)), ErrorCode.INVALID_TRANSITION);

    saveEmailUser("o4@example.com", "010-9999-0000");
    Cookie other = login("o4@example.com", PASSWORD);
    assertError(mockMvc.perform(jsonPost(url, "", other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(jsonPost(url, "")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 동시에_같은_신청자가_두_번_작성하면_하나만_성공한다() throws Exception {
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first =
          pool.submit(
              () ->
                  mockMvc
                      .perform(
                          jsonPost(
                              "/api/office-registrations",
                              createBody("R-1", "2323232323", "INDIVIDUAL"),
                              session))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      var second =
          pool.submit(
              () ->
                  mockMvc
                      .perform(
                          jsonPost(
                              "/api/office-registrations",
                              createBody("R-2", "2424242424", "INDIVIDUAL"),
                              session))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM office_registration", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 응답_본문이_JSON으로_읽힌다() throws Exception {
    JsonNode body =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPost(
                        "/api/office-registrations",
                        createBody("S-1", "2525252525", "INDIVIDUAL"),
                        session))
                .andReturn());
    assertThat(body.get("registrationId").asLong()).isPositive();
  }
}
