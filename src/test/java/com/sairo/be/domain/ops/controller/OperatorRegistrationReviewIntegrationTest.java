package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class OperatorRegistrationReviewIntegrationTest extends OpsIntegrationTestBase {

  private static final String[] ITEMS = {
    "REVISION_MATCH",
    "SOURCE_VERIFIED",
    "REGISTRATION_RELATION",
    "QUALIFICATION_EDUCATION",
    "EMPLOYMENT_ROSTER"
  };

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;
  @Autowired private InMemoryObjectStorage objectStorage;

  private Operator reviewer;
  private Cookie reviewSession;
  private int sequence;

  @BeforeEach
  void createReviewer() {
    reviewer = createOperator("8001001", "김심사", OperatorRole.REVIEW);
    reviewSession = operatorSession(reviewer);
  }

  private long submittedRegistration(long applicantId, String status) {
    sequence++;
    String extra =
        status.equals("SUPPLEMENT_REQUESTED")
            ? ", supplement_reason, supplement_due_at, reviewed_at"
            : "";
    String extraValues =
        status.equals("SUPPLEMENT_REQUESTED") ? ", '보완', now() + interval '2 days', now()" : "";
    long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
                + " registration_authority, real_estate_license_number,"
                + " business_registration_number, requested_office_name,"
                + " requested_representative_name, requested_representative_phone,"
                + " requested_address, applicant_registration_relation"
                + extra
                + ") VALUES (?, ?, 'INDIVIDUAL', '서울특별시 종로구', ?, ?, '사이로공인중개사', '홍대표',"
                + " '02-123-4567', '서울 종로구 1', 'REPRESENTATIVE'"
                + extraValues
                + ") RETURNING id",
            Long.class,
            applicantId,
            status,
            "LIC-" + sequence,
            String.format("%010d", 3_000_000_000L + sequence));
    if (!status.equals("DRAFT")) {
      long revisionId =
          jdbcTemplate.queryForObject(
              "INSERT INTO office_registration_revision (registration_id, revision_no,"
                  + " requested_office_name, requested_address) VALUES (?, 1, '사이로공인중개사',"
                  + " '서울 종로구 1') RETURNING id",
              Long.class,
              id);
      for (String type :
          new String[] {
            "BROKERAGE_LICENSE", "BUSINESS_CERTIFICATE", "REPRESENTATIVE_QUALIFICATION"
          }) {
        String key = "test/" + id + "/" + type + ".pdf";
        objectStorage.put(key, "%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1), "application/pdf");
        jdbcTemplate.update(
            "INSERT INTO office_registration_evidence (revision_id, evidence_type, object_key)"
                + " VALUES (?, ?, ?)",
            revisionId,
            type,
            key);
      }
    }
    return id;
  }

  private AppUser applicant() {
    sequence++;
    return appUserRepository.saveAndFlush(
        com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build());
  }

  private Cookie memberCookie(AppUser user) {
    return sessions.cookie(
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                user.getId(), null, null, user.getName(), null),
            Instant.now(),
            Instant.now()));
  }

  private String checklistBody(boolean checked, String... codes) {
    StringBuilder items = new StringBuilder();
    for (String code : codes) {
      if (items.length() > 0) {
        items.append(',');
      }
      items
          .append("{\"itemCode\":\"")
          .append(code)
          .append("\",\"checked\":")
          .append(checked)
          .append('}');
    }
    return "{\"items\":[" + items + "]}";
  }

  private ResultActions checkAll(long registrationId) throws Exception {
    return mockMvc.perform(
        putJson(
            "/api/ops/office-registrations/" + registrationId + "/checklist",
            checklistBody(true, ITEMS),
            reviewSession));
  }

  private static MockHttpServletRequestBuilder putJson(String url, String body, Cookie cookie) {
    MockHttpServletRequestBuilder builder =
        put(url).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return AuthFlowSupport.withCsrf(builder);
  }

  private ResultActions post(String path, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? jsonPost(path, body) : jsonPost(path, body, cookie));
  }

  @Test
  void 목록은_기본으로_심사_중_신청만_오래된_순으로_주고_상태_필터를_받는다() throws Exception {
    long first = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    long second = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    submittedRegistration(applicant().getId(), "SUPPLEMENT_REQUESTED");
    mockMvc
        .perform(get("/api/ops/office-registrations").cookie(reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].registrationId").value(first))
        .andExpect(jsonPath("$.items[1].registrationId").value(second))
        .andExpect(jsonPath("$.items[0].requestedOfficeName").value("사이로공인중개사"))
        .andExpect(jsonPath("$.items[0].status").value("PENDING_REVIEW"));
    mockMvc
        .perform(
            get("/api/ops/office-registrations?status=SUPPLEMENT_REQUESTED,PENDING_REVIEW")
                .cookie(reviewSession))
        .andExpect(jsonPath("$.totalElements").value(3));
  }

  @Test
  void 목록은_잘못된_필터나_크기는_400이다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/ops/office-registrations?status=NOPE").cookie(reviewSession)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/ops/office-registrations?size=7").cookie(reviewSession)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 심사_엔드포인트는_로그인이_없으면_401이고_REVIEW_역할이_없으면_403이다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    Cookie support = operatorSession(createOperator("8001002", "지원", OperatorRole.SUPPORT));
    String base = "/api/ops/office-registrations/" + id;
    for (String url :
        new String[] {"/api/ops/office-registrations", base, "/api/ops/office-change-requests"}) {
      assertError(mockMvc.perform(get(url)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(url).cookie(support)), ErrorCode.FORBIDDEN);
    }
    for (String url :
        new String[] {
          base + "/approve",
          base + "/request-supplement",
          base + "/reject",
          base + "/evidence/download-url",
          "/api/ops/offices/1/revoke",
          "/api/ops/office-change-requests/1/approve",
          "/api/ops/office-change-requests/1/reject"
        }) {
      assertError(post(url, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(url, "{}", support), ErrorCode.FORBIDDEN);
    }
    assertError(
        mockMvc.perform(putJson(base + "/checklist", checklistBody(true, ITEMS[0]), null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(putJson(base + "/checklist", checklistBody(true, ITEMS[0]), support)),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 상세는_제출_버전과_증빙_종류와_확인_항목과_이력을_주고_원문은_주지_않는다() throws Exception {
    AppUser user = applicant();
    long id = submittedRegistration(user.getId(), "PENDING_REVIEW");
    String body =
        mockMvc
            .perform(get("/api/ops/office-registrations/" + id).cookie(reviewSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registrationId").value(id))
            .andExpect(jsonPath("$.applicantUserId").value(user.getId()))
            .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.hasEvidence").value(true))
            .andExpect(jsonPath("$.currentRevisionNo").value(1))
            .andExpect(jsonPath("$.revisions[0].revisionNo").value(1))
            .andExpect(jsonPath("$.revisions[0].evidenceTypes.length()").value(3))
            .andExpect(jsonPath("$.reviewChecklist.length()").value(5))
            .andExpect(jsonPath("$.reviewChecklist[0].checked").value(false))
            .andExpect(jsonPath("$.canApprove").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("object_key").doesNotContain("test/" + id);
  }

  @Test
  void 상세는_없는_신청이면_404다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/ops/office-registrations/99999").cookie(reviewSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 확인_항목은_보낸_항목만_바꾸고_확인자와_시각을_남기며_모두_확인되면_승인_가능이다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    mockMvc
        .perform(
            putJson(
                "/api/ops/office-registrations/" + id + "/checklist",
                checklistBody(true, ITEMS[0], ITEMS[1]),
                reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionNo").value(1))
        .andExpect(jsonPath("$.reviewChecklist[0].checked").value(true))
        .andExpect(jsonPath("$.reviewChecklist[0].checkedByOperatorName").value("김심사"))
        .andExpect(jsonPath("$.reviewChecklist[0].checkedAt").exists())
        .andExpect(jsonPath("$.reviewChecklist[2].checked").value(false))
        .andExpect(jsonPath("$.canApprove").value(false));
    checkAll(id).andExpect(jsonPath("$.canApprove").value(true));
    mockMvc
        .perform(
            putJson(
                "/api/ops/office-registrations/" + id + "/checklist",
                checklistBody(false, ITEMS[4]),
                reviewSession))
        .andExpect(jsonPath("$.canApprove").value(false))
        .andExpect(jsonPath("$.reviewChecklist[4].checkedByOperatorName").doesNotExist());
  }

  @Test
  void 확인_항목은_중복_빈_목록_알_수_없는_코드면_400이고_심사_중이_아니면_409이고_없으면_404다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    String url = "/api/ops/office-registrations/" + id + "/checklist";
    assertError(
        mockMvc.perform(putJson(url, checklistBody(true, ITEMS[0], ITEMS[0]), reviewSession)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(putJson(url, "{\"items\":[]}", reviewSession)), ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(putJson(url, checklistBody(true, "UNKNOWN_ITEM"), reviewSession)),
        ErrorCode.INVALID_REQUEST);
    long draft = submittedRegistration(applicant().getId(), "SUPPLEMENT_REQUESTED");
    assertError(
        mockMvc.perform(
            putJson(
                "/api/ops/office-registrations/" + draft + "/checklist",
                checklistBody(true, ITEMS[0]),
                reviewSession)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(
            putJson(
                "/api/ops/office-registrations/99999/checklist",
                checklistBody(true, ITEMS[0]),
                reviewSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 승인은_확인_항목이_끝나지_않았으면_403이고_아무것도_만들지_않는다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REVIEW_CHECKLIST_INCOMPLETE"));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM office", Long.class)).isZero();
  }

  @Test
  void 승인은_사무소와_대표_소속을_만들고_신청자_세션에_바로_반영한다() throws Exception {
    AppUser user = applicant();
    Cookie applicantSession = memberCookie(user);
    long id = submittedRegistration(user.getId(), "PENDING_REVIEW");
    checkAll(id);
    mockMvc
        .perform(get("/api/offices/current").cookie(applicantSession))
        .andExpect(status().isForbidden());

    post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.officeId").isNumber())
        .andExpect(jsonPath("$.currentRevisionNo").value(1));

    var office = jdbcTemplate.queryForMap("SELECT * FROM office");
    assertThat(office)
        .containsEntry("name", "사이로공인중개사")
        .containsEntry("representative_name", "홍대표")
        .containsEntry("address", "서울 종로구 1");
    var membership =
        jdbcTemplate.queryForMap(
            "SELECT role, status, request_source, user_id FROM office_membership");
    assertThat(membership)
        .containsEntry("role", "OWNER")
        .containsEntry("status", "APPROVED")
        .containsEntry("request_source", "REGISTRATION")
        .containsEntry("user_id", user.getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT resulting_office_id FROM office_registration WHERE id = ?", Long.class, id))
        .isEqualTo(((Number) office.get("id")).longValue());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_registration_status_history WHERE registration_id = ?"
                    + " AND to_status = 'APPROVED' AND changed_by_type = 'OPERATOR'"
                    + " AND changed_by_operator_id = ?",
                Long.class,
                id,
                reviewer.id()))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status ="
                    + " 'APPROVED' AND changed_by_type = 'SYSTEM'",
                Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE action_type ="
                    + " 'OFFICE_REGISTRATION_APPROVED' AND operator_account_id = ?",
                Long.class,
                reviewer.id()))
        .isEqualTo(1L);
    mockMvc
        .perform(get("/api/offices/current").cookie(applicantSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("사이로공인중개사"));
  }

  @Test
  void 승인은_심사_중이_아니면_409이고_없으면_404이고_같은_개설등록번호_사무소가_있으면_409다() throws Exception {
    long approved = submittedRegistration(applicant().getId(), "SUPPLEMENT_REQUESTED");
    assertError(
        post("/api/ops/office-registrations/" + approved + "/approve", "", reviewSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/office-registrations/99999/approve", "", reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);

    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    checkAll(id);
    String license =
        jdbcTemplate.queryForObject(
            "SELECT real_estate_license_number FROM office_registration WHERE id = ?",
            String.class,
            id);
    jdbcTemplate.update(
        "INSERT INTO office (name, entity_type, registration_authority,"
            + " real_estate_license_number, business_registration_number, representative_name,"
            + " representative_phone, address) VALUES ('기존', 'INDIVIDUAL', '서울특별시 종로구', ?,"
            + " '9999999999', '김', '02', '서울')",
        license);
    assertError(
        post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession),
        ErrorCode.OFFICE_ALREADY_EXISTS);
  }

  @Test
  void 승인은_신청자에게_이미_승인된_소속이_있으면_409이고_사무소도_남기지_않는다() throws Exception {
    var staff =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.AGENT);
    long id = submittedRegistration(staff.user().getId(), "PENDING_REVIEW");
    checkAll(id);
    assertError(
        post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession),
        ErrorCode.INVALID_TRANSITION);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM office", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 동시에_두_번_승인하면_하나만_성공한다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    checkAll(id);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks =
          List.of(
              pool.submit(
                  () ->
                      post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession)
                          .andReturn()
                          .getResponse()
                          .getStatus()),
              pool.submit(
                  () ->
                      post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession)
                          .andReturn()
                          .getResponse()
                          .getStatus()));
      assertThat(List.of(tasks.get(0).get(), tasks.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM office", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 보완_요청은_고객에게_보이는_사유와_기한을_남기고_상태_이력을_기록한다() throws Exception {
    AppUser user = applicant();
    Cookie applicantSession = memberCookie(user);
    long id = submittedRegistration(user.getId(), "PENDING_REVIEW");
    String due = Instant.now().plusSeconds(3 * 86400).toString();
    post(
            "/api/ops/office-registrations/" + id + "/request-supplement",
            "{\"reason\":\"사업자등록증을 다시 올려 주세요\",\"supplementDueAt\":\"" + due + "\"}",
            reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUPPLEMENT_REQUESTED"))
        .andExpect(jsonPath("$.officeId").doesNotExist());
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(applicantSession))
        .andExpect(jsonPath("$.status").value("SUPPLEMENT_REQUESTED"))
        .andExpect(jsonPath("$.supplementReason").value("사업자등록증을 다시 올려 주세요"))
        .andExpect(jsonPath("$.supplementDueAt").exists());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM office_registration_status_history WHERE to_status ="
                    + " 'SUPPLEMENT_REQUESTED' AND changed_by_operator_id = ?",
                String.class,
                reviewer.id()))
        .isEqualTo("사업자등록증을 다시 올려 주세요");
  }

  @Test
  void 보완_요청은_기한이_과거이거나_사유가_비면_400이고_심사_중이_아니면_409이고_없으면_404다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    String url = "/api/ops/office-registrations/" + id + "/request-supplement";
    String past = Instant.now().minusSeconds(60).toString();
    String future = Instant.now().plusSeconds(86400).toString();
    assertError(
        post(url, "{\"reason\":\"보완\",\"supplementDueAt\":\"" + past + "\"}", reviewSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(url, "{\"reason\":\" \",\"supplementDueAt\":\"" + future + "\"}", reviewSession),
        ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{\"reason\":\"보완\"}", reviewSession), ErrorCode.INVALID_REQUEST);
    long other = submittedRegistration(applicant().getId(), "SUPPLEMENT_REQUESTED");
    assertError(
        post(
            "/api/ops/office-registrations/" + other + "/request-supplement",
            "{\"reason\":\"보완\",\"supplementDueAt\":\"" + future + "\"}",
            reviewSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(
            "/api/ops/office-registrations/99999/request-supplement",
            "{\"reason\":\"보완\",\"supplementDueAt\":\"" + future + "\"}",
            reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 보완_뒤_신청자가_다시_제출하면_심사_중으로_돌아가고_확인_항목이_새_버전에서_시작한다() throws Exception {
    AppUser user = applicant();
    Cookie applicantSession = memberCookie(user);
    long id = submittedRegistration(user.getId(), "PENDING_REVIEW");
    checkAll(id);
    String due = Instant.now().plusSeconds(86400).toString();
    post(
        "/api/ops/office-registrations/" + id + "/request-supplement",
        "{\"reason\":\"보완\",\"supplementDueAt\":\"" + due + "\"}",
        reviewSession);
    var resubmit =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                "/api/office-registrations/{id}/evidence", id)
            .file(
                new org.springframework.mock.web.MockMultipartFile(
                    "file",
                    "a.pdf",
                    "application/pdf",
                    "%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1)))
            .param("evidenceType", "BUSINESS_CERTIFICATE")
            .cookie(applicantSession, new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
            .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
    mockMvc
        .perform(resubmit)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentRevisionNo").value(2));
    mockMvc
        .perform(get("/api/ops/office-registrations/" + id).cookie(reviewSession))
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.currentRevisionNo").value(2))
        .andExpect(jsonPath("$.reviewChecklist[0].checked").value(false))
        .andExpect(jsonPath("$.canApprove").value(false));
  }

  @Test
  void 반려는_심사_중과_보완_요청_신청을_사유와_함께_종료한다() throws Exception {
    AppUser user = applicant();
    Cookie applicantSession = memberCookie(user);
    long pending = submittedRegistration(user.getId(), "PENDING_REVIEW");
    post(
            "/api/ops/office-registrations/" + pending + "/reject",
            "{\"reason\":\"개설등록증이 확인되지 않습니다\"}",
            reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    mockMvc
        .perform(get("/api/office-registrations/me").cookie(applicantSession))
        .andExpect(jsonPath("$.rejectionReason").value("개설등록증이 확인되지 않습니다"))
        .andExpect(jsonPath("$.resolved").value(false));

    long supplement = submittedRegistration(applicant().getId(), "SUPPLEMENT_REQUESTED");
    post(
            "/api/ops/office-registrations/" + supplement + "/reject",
            "{\"reason\":\"기한이 지났습니다\"}",
            reviewSession)
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE action_type ="
                    + " 'OFFICE_REGISTRATION_REJECTED'",
                Long.class))
        .isEqualTo(2L);
  }

  @Test
  void 반려는_사유가_비면_400이고_이미_끝난_신청이면_409이고_없으면_404다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    String url = "/api/ops/office-registrations/" + id + "/reject";
    assertError(post(url, "{\"reason\":\"\"}", reviewSession), ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{}", reviewSession), ErrorCode.INVALID_REQUEST);
    post(url, "{\"reason\":\"사유\"}", reviewSession).andExpect(status().isOk());
    assertError(post(url, "{\"reason\":\"사유\"}", reviewSession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/office-registrations/99999/reject", "{\"reason\":\"사유\"}", reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 증빙_다운로드_URL은_단기_URL을_발급하고_감사_로그를_남긴다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    String body =
        post(
                "/api/ops/office-registrations/" + id + "/evidence/download-url",
                "{\"evidenceType\":\"BROKERAGE_LICENSE\"}",
                reviewSession)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("storage.stub.invalid").contains("ttl=300");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT detail FROM ops_audit_log WHERE action_type ="
                    + " 'OFFICE_EVIDENCE_DOWNLOAD_URL_ISSUED'",
                String.class))
        .isEqualTo("BROKERAGE_LICENSE");
  }

  @Test
  void 증빙_다운로드_URL은_증빙이_없으면_404_상태가_맞지_않으면_409_본문이_틀리면_400이다() throws Exception {
    long id = submittedRegistration(applicant().getId(), "PENDING_REVIEW");
    String url = "/api/ops/office-registrations/" + id + "/evidence/download-url";
    assertError(
        post(url, "{\"evidenceType\":\"CORPORATE_AUTHORITY\"}", reviewSession),
        ErrorCode.EVIDENCE_NOT_FOUND);
    assertError(post(url, "{}", reviewSession), ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{\"evidenceType\":\"X\"}", reviewSession), ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            "/api/ops/office-registrations/99999/evidence/download-url",
            "{\"evidenceType\":\"BROKERAGE_LICENSE\"}",
            reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    post("/api/ops/office-registrations/" + id + "/reject", "{\"reason\":\"사유\"}", reviewSession);
    assertError(
        post(url, "{\"evidenceType\":\"BROKERAGE_LICENSE\"}", reviewSession),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 회수는_신청을_회수_상태로_바꾸고_구성원_세션을_모두_종료한다() throws Exception {
    AppUser owner = applicant();
    long id = submittedRegistration(owner.getId(), "PENDING_REVIEW");
    checkAll(id);
    post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession)
        .andExpect(status().isOk());
    long officeId = jdbcTemplate.queryForObject("SELECT id FROM office", Long.class);
    long membershipId = jdbcTemplate.queryForObject("SELECT id FROM office_membership", Long.class);
    Cookie ownerSession =
        sessions.cookie(
            sessions.createMemberSession(
                new com.sairo.be.global.security.StaffPrincipal(
                    owner.getId(),
                    membershipId,
                    officeId,
                    owner.getName(),
                    com.sairo.be.global.security.StaffRole.OWNER),
                Instant.now(),
                Instant.now()));
    mockMvc.perform(get("/api/offices/current").cookie(ownerSession)).andExpect(status().isOk());

    post(
            "/api/ops/offices/" + officeId + "/revoke",
            "{\"reason\":\"허위 서류가 확인되었습니다\"}",
            reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeId").value(officeId))
        .andExpect(jsonPath("$.registrationId").value(id))
        .andExpect(jsonPath("$.registrationStatus").value("REVOKED"));
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, revocation_reason FROM office_registration WHERE id = ?", id))
        .containsEntry("status", "REVOKED")
        .containsEntry("revocation_reason", "허위 서류가 확인되었습니다");
    assertError(
        mockMvc.perform(get("/api/offices/current").cookie(ownerSession)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 회수는_사유가_비면_400이고_회수된_사무소는_409이고_없으면_404다() throws Exception {
    AppUser owner = applicant();
    long id = submittedRegistration(owner.getId(), "PENDING_REVIEW");
    checkAll(id);
    post("/api/ops/office-registrations/" + id + "/approve", "", reviewSession);
    long officeId = jdbcTemplate.queryForObject("SELECT id FROM office", Long.class);
    String url = "/api/ops/offices/" + officeId + "/revoke";
    assertError(post(url, "{\"reason\":\"\"}", reviewSession), ErrorCode.INVALID_REQUEST);
    post(url, "{\"reason\":\"사유\"}", reviewSession).andExpect(status().isOk());
    assertError(post(url, "{\"reason\":\"사유\"}", reviewSession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/offices/99999/revoke", "{\"reason\":\"사유\"}", reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
