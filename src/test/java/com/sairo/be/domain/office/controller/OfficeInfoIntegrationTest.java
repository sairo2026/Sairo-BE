package com.sairo.be.domain.office.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class OfficeInfoIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private TestData.Staff staff(OfficeMembershipRole role) {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository)
        .approvedStaff(role);
  }

  private Cookie cookieOf(TestData.Staff staff) {
    return sessions.cookie(
        sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
  }

  private MockHttpServletRequestBuilder patchPhone(Cookie cookie, String ifMatch, String body) {
    MockHttpServletRequestBuilder builder =
        cookie == null
            ? jsonPatch("/api/offices/current", body)
            : jsonPatch("/api/offices/current", body, cookie);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return builder;
  }

  @Test
  void 사무소_조회는_대표와_중개사에게_연락처를_주고_중개보조원에게는_주지_않는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    var deputy =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaffIn(
                owner.office(),
                appUserRepository.saveAndFlush(
                    com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build()),
                OfficeMembershipRole.DEPUTY);
    mockMvc
        .perform(get("/api/offices/current").cookie(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.representativePhone").value("010-0000-0000"))
        .andExpect(jsonPath("$.version").value(1));
    mockMvc
        .perform(get("/api/offices/current").cookie(cookieOf(deputy)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.representativePhone").doesNotExist())
        .andExpect(jsonPath("$.name").value("테스트공인중개사사무소"));
  }

  @Test
  void 사무소_조회는_소속이_없으면_403이고_로그인이_없으면_401이다() throws Exception {
    saveEmailUser("nomember@example.com", "010-1212-3434");
    Cookie plain = login("nomember@example.com", PASSWORD);
    assertError(mockMvc.perform(get("/api/offices/current").cookie(plain)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get("/api/offices/current")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 연락처_수정은_버전을_올리고_바뀐_값을_돌려준다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    mockMvc
        .perform(patchPhone(cookie, "1", "{\"representativePhone\":\"010-9876-5432\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.representativePhone").value("010-9876-5432"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT representative_phone FROM office WHERE id = ?",
                String.class,
                owner.office().getId()))
        .isEqualTo("010-9876-5432");
  }

  @Test
  void 연락처_수정은_버전이_다르면_409이고_현재_버전을_알려준다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    mockMvc
        .perform(patchPhone(cookie, "1", "{\"representativePhone\":\"010-9876-5432\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(patchPhone(cookie, "1", "{\"representativePhone\":\"010-1111-2222\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(2));
  }

  @Test
  void 연락처_수정은_다른_필드나_빈_값이나_If_Match_누락이면_400이다() throws Exception {
    Cookie cookie = cookieOf(staff(OfficeMembershipRole.OWNER));
    assertError(
        mockMvc.perform(
            patchPhone(cookie, "1", "{\"representativePhone\":\"010-1111-2222\",\"name\":\"변경\"}")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(patchPhone(cookie, "1", "{\"representativePhone\":\"\"}")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(patchPhone(cookie, "1", "{\"representativePhone\":\"abc\"}")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(patchPhone(cookie, null, "{\"representativePhone\":\"010-1111-2222\"}")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(patchPhone(cookie, "x", "{\"representativePhone\":\"010-1111-2222\"}")),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 연락처_수정은_대표만_할_수_있고_로그인이_없으면_401이다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    var agent =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaffIn(
                owner.office(),
                appUserRepository.saveAndFlush(
                    com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build()),
                OfficeMembershipRole.AGENT);
    assertError(
        mockMvc.perform(
            patchPhone(cookieOf(agent), "1", "{\"representativePhone\":\"010-1111-2222\"}")),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(patchPhone(null, "1", "{\"representativePhone\":\"010-1111-2222\"}")),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 변경_신청은_재인증_뒤_만들어지고_사무소_정보는_바뀌지_않는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    grantStepUp(cookie);
    mockMvc
        .perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"서울 새 주소 1\"}}",
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.requestedValues.address").value("서울 새 주소 1"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT address FROM office WHERE id = ?", String.class, owner.office().getId()))
        .isEqualTo("서울특별시 종로구 테스트로 1");
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"다시\"}}",
                cookie)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 변경_신청은_재인증이_없으면_401이고_종류에_맞지_않는_키는_400이다() throws Exception {
    Cookie cookie = cookieOf(staff(OfficeMembershipRole.OWNER));
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"새 주소\"}}",
                cookie)),
        ErrorCode.STEP_UP_REQUIRED);
    grantStepUp(cookie);
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"representativeName\":\"김\"}}",
                cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REGISTRATION_INFO\",\"requestedValues\":{\"entityType\":\"X\"}}",
                cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REGISTRATION_INFO\",\"requestedValues\":{\"businessRegistrationNumber\":\"1\"}}",
                cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REPRESENTATIVE\",\"requestedValues\":{}}",
                cookie)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 변경_신청은_대표만_할_수_있고_로그인이_없으면_401이다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    var agent =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaffIn(
                owner.office(),
                appUserRepository.saveAndFlush(
                    com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build()),
                OfficeMembershipRole.AGENT);
    String body = "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"새 주소\"}}";
    assertError(
        mockMvc.perform(jsonPost("/api/office-change-requests", body, cookieOf(agent))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPost("/api/office-change-requests", body)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 변경_신청_목록은_본인_사무소의_신청만_최신순으로_상태_필터와_함께_준다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    var other = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    for (String address : new String[] {"첫째 주소", "둘째 주소"}) {
      grantStepUp(cookie);
      mockMvc.perform(
          jsonPost(
              "/api/office-change-requests",
              "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"" + address + "\"}}",
              cookie));
      jdbcTemplate.update(
          "UPDATE office_change_request SET status = 'REJECTED', reviewed_at = now(),"
              + " rejection_reason = '반려' WHERE requested_values LIKE '%첫째%'");
    }
    Cookie otherCookie = cookieOf(other);
    grantStepUp(otherCookie);
    mockMvc.perform(
        jsonPost(
            "/api/office-change-requests",
            "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"남의 주소\"}}",
            otherCookie));
    jdbcTemplate.update(
        "UPDATE office_change_request SET status = 'REJECTED', reviewed_at = now(),"
            + " rejection_reason = '반려' WHERE requested_values LIKE '%첫째%'");

    mockMvc
        .perform(get("/api/office-change-requests").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].requestedValues.address").value("둘째 주소"));
    mockMvc
        .perform(get("/api/office-change-requests?status=REJECTED").cookie(cookie))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].rejectionReason").value("반려"));
  }

  @Test
  void 변경_신청_목록은_잘못된_필터나_크기는_400이고_대표가_아니면_403이고_로그인이_없으면_401이다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    assertError(
        mockMvc.perform(get("/api/office-change-requests?status=NOPE").cookie(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/office-change-requests?size=10").cookie(cookie)),
        ErrorCode.INVALID_REQUEST);
    var agent =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaffIn(
                owner.office(),
                appUserRepository.saveAndFlush(
                    com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build()),
                OfficeMembershipRole.AGENT);
    assertError(
        mockMvc.perform(get("/api/office-change-requests").cookie(cookieOf(agent))),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get("/api/office-change-requests")), ErrorCode.UNAUTHENTICATED);
    assertThat(AuthFlowSupport.CSRF_VALUE).isNotBlank();
  }

  @Test
  void 연락처_수정은_숫자가_충분하지_않은_값을_INVALID_FORMAT으로_거절한다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    for (String invalid :
        new String[] {
          "+++++++", "()- ()- ", "-------", "012345", "+82+10-0000-0002", "0101234567890123456"
        }) {
      assertFieldError(
          mockMvc.perform(patchPhone(cookie, "1", "{\"representativePhone\":\"" + invalid + "\"}")),
          "representativePhone",
          "INVALID_FORMAT");
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT representative_phone FROM office WHERE id = ?",
                String.class,
                owner.office().getId()))
        .isEqualTo("010-0000-0000");
  }

  @Test
  void 연락처_수정은_국내_번호와_국제_번호_형식을_받는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long version = 1;
    for (String valid : new String[] {"02-123-4567", "+82 10-0000-0002", "(02) 1234-5678"}) {
      mockMvc
          .perform(
              patchPhone(
                  cookie, String.valueOf(version), "{\"representativePhone\":\"" + valid + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.representativePhone").value(valid));
      version++;
    }
  }

  @Test
  void 변경_신청은_진행_중인_신청이_있으면_409로_거절하고_처리된_뒤에는_다시_받는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    String body = "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"서울 새 주소\"}}";
    grantStepUp(cookie);
    mockMvc
        .perform(jsonPost("/api/office-change-requests", body, cookie))
        .andExpect(status().isCreated());
    grantStepUp(cookie);
    assertError(
        mockMvc.perform(jsonPost("/api/office-change-requests", body, cookie)),
        ErrorCode.PENDING_REGISTRATION_EXISTS);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_change_request WHERE office_id = ?",
                Integer.class,
                owner.office().getId()))
        .isEqualTo(1);
    jdbcTemplate.update(
        "UPDATE office_change_request SET status = 'REJECTED', reviewed_at = now(),"
            + " rejection_reason = '반려' WHERE office_id = ?",
        owner.office().getId());
    mockMvc
        .perform(jsonPost("/api/office-change-requests", body, cookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 변경_신청은_다른_사무소의_진행_중_신청에_막히지_않는다() throws Exception {
    var first = staff(OfficeMembershipRole.OWNER);
    var second = staff(OfficeMembershipRole.OWNER);
    String body = "{\"changeType\":\"ADDRESS\",\"requestedValues\":{\"address\":\"서울 새 주소\"}}";
    for (var owner : new TestData.Staff[] {first, second}) {
      Cookie cookie = cookieOf(owner);
      grantStepUp(cookie);
      mockMvc
          .perform(jsonPost("/api/office-change-requests", body, cookie))
          .andExpect(status().isCreated());
    }
  }

  @Test
  void 변경_신청은_사업자등록번호_검증번호가_틀리면_400이고_하이픈_표기는_정규화해_받는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    grantStepUp(cookie);
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REGISTRATION_INFO\",\"requestedValues\":{\"businessRegistrationNumber\":\"1234567890\"}}",
                cookie)),
        "requestedValues.businessRegistrationNumber",
        "INVALID_VALUE");
    mockMvc
        .perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REGISTRATION_INFO\",\"requestedValues\":{\"businessRegistrationNumber\":\"123-45-67891\"}}",
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestedValues.businessRegistrationNumber").value("1234567891"));
  }

  @Test
  void 대표자_변경_신청의_연락처도_숫자가_없는_값은_400이다() throws Exception {
    Cookie cookie = cookieOf(staff(OfficeMembershipRole.OWNER));
    grantStepUp(cookie);
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/office-change-requests",
                "{\"changeType\":\"REPRESENTATIVE\",\"requestedValues\":{\"representativePhone\":\"+++++++\"}}",
                cookie)),
        "requestedValues.representativePhone",
        "INVALID_FORMAT");
  }
}
