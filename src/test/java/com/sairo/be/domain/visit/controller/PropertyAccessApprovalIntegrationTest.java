package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverIntegrationTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PropertyAccessApprovalIntegrationTest extends ApproverIntegrationTestBase {

  private record Fixture(TestData.Staff owner, Cookie cookie, long propertyId) {}

  private Fixture fixture() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    return new Fixture(owner, cookie, newProperty(cookie));
  }

  @Test
  void 등록하면_번호는_정규화해_저장하고_해시는_응답에_없다() throws Exception {
    Fixture f = fixture();
    String body =
        approvalBody(null, today().plusDays(1), today().plusDays(30))
            .replace("010-1111-2222", "01011112222");

    JsonNode created =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(approvalsUrl(f.propertyId()), body, f.cookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalRole").value("PRIMARY"))
                .andExpect(jsonPath("$.approverPhone").value("010-1111-2222"))
                .andExpect(jsonPath("$.timeSlots.length()").value(0))
                .andExpect(jsonPath("$.source").value("DIRECT"))
                .andExpect(jsonPath("$.officeId").value(f.owner().office().getId()))
                .andExpect(jsonPath("$.approverPhoneHash").doesNotExist())
                .andReturn());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT approver_phone, approver_phone_hash, retention_until, valid_until FROM"
                + " property_access_approval WHERE id = ?",
            created.get("approvalId").longValue());
    assertThat(row.get("approver_phone")).isEqualTo("010-1111-2222");
    assertThat(row.get("approver_phone_hash").toString()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(row.get("approver_phone_hash")).isNotEqualTo("010-1111-2222");
    Long retentionDays =
        jdbcTemplate.queryForObject(
            "SELECT retention_until - (valid_until AT TIME ZONE 'Asia/Seoul')::date FROM"
                + " property_access_approval WHERE id = ?",
            Long.class,
            created.get("approvalId").longValue());
    assertThat(retentionDays).isEqualTo(90L);
  }

  @Test
  void 같은_번호는_표기가_달라도_같은_해시이고_다른_번호는_다른_해시다() throws Exception {
    Fixture f = fixture();
    long first = registerApproval(f.cookie(), f.propertyId(), "CO_APPROVER", 1, 10);
    mockMvc
        .perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("CO_APPROVER", today().plusDays(1), today().plusDays(10))
                    .replace("010-1111-2222", "01011112222"),
                f.cookie()))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("CO_APPROVER", today().plusDays(1), today().plusDays(10))
                    .replace("010-1111-2222", "010-9999-8888"),
                f.cookie()))
        .andExpect(status().isCreated());

    List<String> hashes =
        jdbcTemplate.queryForList(
            "SELECT approver_phone_hash FROM property_access_approval WHERE property_id = ?"
                + " ORDER BY id",
            String.class,
            f.propertyId());
    assertThat(first).isPositive();
    assertThat(hashes.get(0)).isEqualTo(hashes.get(1)).isNotEqualTo(hashes.get(2));
  }

  @Test
  void 같은_기간_대표_승인자가_있으면_409이고_공동_승인자와_다른_기간은_허용한다() throws Exception {
    Fixture f = fixture();
    registerApproval(f.cookie(), f.propertyId(), null, 1, 30);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("PRIMARY", today().plusDays(20), today().plusDays(40)),
                f.cookie())),
        ErrorCode.PRIMARY_APPROVER_EXISTS);
    registerApproval(f.cookie(), f.propertyId(), "CO_APPROVER", 1, 30);
    registerApproval(f.cookie(), f.propertyId(), "PRIMARY", 31, 60);
  }

  @Test
  void 철회된_대표_승인자는_중복으로_보지_않는다() throws Exception {
    Fixture f = fixture();
    long first = registerApproval(f.cookie(), f.propertyId(), null, 1, 30);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        f.owner().user().getId(),
        first);

    registerApproval(f.cookie(), f.propertyId(), "PRIMARY", 1, 30);
  }

  @Test
  void 동시에_대표_승인자를_등록하면_한_건만_성공한다() throws Exception {
    Fixture f = fixture();
    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<Integer> task =
          () -> {
            start.await();
            return mockMvc
                .perform(
                    jsonPostAs(
                        approvalsUrl(f.propertyId()),
                        approvalBody("PRIMARY", today().plusDays(1), today().plusDays(30)),
                        f.cookie()))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      results.add(pool.submit(task));
    }
    start.countDown();
    int created = 0;
    int conflicts = 0;
    for (Future<Integer> result : results) {
      int code = result.get();
      if (code == 201) {
        created++;
      } else if (code == 409) {
        conflicts++;
      }
    }
    pool.shutdown();

    assertThat(created).isEqualTo(1);
    assertThat(conflicts).isEqualTo(threads - 1);
  }

  @Test
  void 대리_입력은_확인_시각이_필수이고_연락처는_선택이며_입력자_이름이_나온다() throws Exception {
    Fixture f = fixture();
    String base =
        "{\"approverType\":\"OWNER\",\"approverName\":\"박소유\",\"validFrom\":\""
            + today().plusDays(1)
            + "T09:00:00+09:00\",\"validUntil\":\""
            + today().plusDays(9)
            + "T18:00:00+09:00\",\"source\":\"PROXY\"";

    assertFieldError(
        mockMvc.perform(jsonPostAs(approvalsUrl(f.propertyId()), base + "}", f.cookie())),
        "confirmedAt",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                base + ",\"confirmedAt\":\"" + Instant.now().plusSeconds(3600) + "\"}",
                f.cookie())),
        "confirmedAt",
        "OUT_OF_RANGE");

    mockMvc
        .perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                base + ",\"confirmedAt\":\"" + Instant.now().minusSeconds(60) + "\"}",
                f.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source").value("PROXY"))
        .andExpect(jsonPath("$.approverPhone").doesNotExist())
        .andExpect(jsonPath("$.recordedByName").value(f.owner().user().getName()))
        .andExpect(jsonPath("$.recordedByMembershipId").value(f.owner().membership().getId()));
    Object hash =
        jdbcTemplate.queryForObject(
            "SELECT approver_phone_hash FROM property_access_approval WHERE property_id = ?",
            Object.class,
            f.propertyId());
    assertThat(hash).isNull();
  }

  @Test
  void 입력_검증과_정합화_전_가능시간은_무시한다() throws Exception {
    Fixture f = fixture();
    String from = today().plusDays(1) + "T09:00:00+09:00";
    String until = today().plusDays(5) + "T09:00:00+09:00";

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                "{\"approverType\":\"OWNER\",\"approverName\":\"박소유\",\"validFrom\":\""
                    + from
                    + "\",\"validUntil\":\""
                    + until
                    + "\",\"source\":\"DIRECT\"}",
                f.cookie())),
        "approverPhone",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("PRIMARY", today().plusDays(5), today().plusDays(4)),
                f.cookie())),
        "validUntil",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("PRIMARY", today().plusDays(1), today().plusDays(5))
                    .replace("}", ",\"approverPhoneHash\":\"x\"}"),
                f.cookie())),
        "approverPhoneHash",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody("BOSS", today().plusDays(1), today().plusDays(5)),
                f.cookie())),
        "approvalRole",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody(null, today().plusDays(1), today().plusDays(5))
                    .replace("010-1111-2222", "12345"),
                f.cookie())),
        "approverPhone",
        "INVALID_FORMAT");

    mockMvc
        .perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody(null, today().plusDays(1), today().plusDays(5))
                    .replace(
                        "}",
                        ",\"timeSlots\":[{\"startsAt\":\""
                            + from
                            + "\",\"endsAt\":\""
                            + until
                            + "\"}]}"),
                f.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.timeSlots.length()").value(0));
    Integer slots =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM property_access_time_slot", Integer.class);
    assertThat(slots).isZero();
  }

  @Test
  void 보관된_매물에는_등록할_수_없다() throws Exception {
    Fixture f = fixture();
    archive(f.propertyId());

    assertError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(f.propertyId()),
                approvalBody(null, today().plusDays(1), today().plusDays(5)),
                f.cookie())),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 이력은_최근_등록이_앞이고_해시와_다른_사무소_데이터가_없다() throws Exception {
    Fixture f = fixture();
    long first = registerApproval(f.cookie(), f.propertyId(), "PRIMARY", 1, 10);
    long second = registerApproval(f.cookie(), f.propertyId(), "CO_APPROVER", 1, 10);

    mockMvc
        .perform(authedGet(approvalsUrl(f.propertyId()), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].approvalId").value(second))
        .andExpect(jsonPath("$.items[0].approvalRole").value("CO_APPROVER"))
        .andExpect(jsonPath("$.items[1].approvalId").value(first))
        .andExpect(jsonPath("$.items[0].approverPhoneHash").doesNotExist())
        .andExpect(jsonPath("$.items[0].timeSlots.length()").value(0))
        .andExpect(jsonPath("$.items[0].revokedAt").doesNotExist());
    assertError(
        mockMvc.perform(authedGet(approvalsUrl(f.propertyId()) + "?size=10", f.cookie())),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 출입승인_엔드포인트는_401_403_다른_사무소_404를_지키고_중개보조원도_등록할_수_있다() throws Exception {
    Fixture f = fixture();
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember2@example.com", "010-1212-3434"));
    String body = approvalBody(null, today().plusDays(1), today().plusDays(5));

    assertError(
        mockMvc.perform(jsonPostAs(approvalsUrl(f.propertyId()), body, null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(approvalsUrl(f.propertyId()), null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(approvalsUrl(f.propertyId()), body, plain)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet(approvalsUrl(f.propertyId()), plain)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(approvalsUrl(f.propertyId()), body, cookieOf(foreign))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(approvalsUrl(f.propertyId()), cookieOf(foreign))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(approvalsUrl(987_654L), body, f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);

    mockMvc
        .perform(jsonPostAs(approvalsUrl(f.propertyId()), body, cookieOf(deputy)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(authedGet(approvalsUrl(f.propertyId()), cookieOf(deputy)))
        .andExpect(status().isOk());
  }
}
