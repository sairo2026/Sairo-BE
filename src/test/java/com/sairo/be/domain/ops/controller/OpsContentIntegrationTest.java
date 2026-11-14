package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.ops.service.OpsContentService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.PolicyFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class OpsContentIntegrationTest extends OpsConsoleTestBase {

  private static final String ANNOUNCEMENTS = "/api/ops/announcements";
  private static final String POLICIES = "/api/ops/policy-documents";
  private static final String INFO = "/api/ops/company-service-info";

  @Autowired private OpsContentService contentService;

  private Operator ops;
  private Cookie opsSession;
  private Operator reviewer;
  private Cookie reviewerSession;
  private Instant now;

  @BeforeEach
  void setUp() {
    ops = operator("최운영", OperatorRole.OPS);
    opsSession = sessionOf(ops);
    reviewer = operator("한운영", OperatorRole.OPS);
    reviewerSession = sessionOf(reviewer);
    now = Instant.now();
  }

  private static String at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)).toString();
  }

  private String announcementBody(String audience, boolean marketing) {
    return "{\"title\":\"정기점검 안내\",\"content\":\"9/30 02:00~04:00 점검 예정입니다.\","
        + audience
        + "\"startsAt\":\""
        + at(now)
        + "\",\"endsAt\":\""
        + at(now.plus(Duration.ofDays(3)))
        + "\",\"importance\":\"NORMAL\",\"marketing\":"
        + marketing
        + "}";
  }

  private static String all() {
    return "\"audienceType\":\"ALL\",";
  }

  private long createAnnouncement(String audience, boolean marketing) throws Exception {
    String body =
        post(ANNOUNCEMENTS, announcementBody(audience, marketing), opsSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"announcementId\":(\\d+).*", "$1"));
  }

  private long reviewedAnnouncement() throws Exception {
    long id = createAnnouncement(all(), false);
    post(ANNOUNCEMENTS + "/" + id + "/submit-review", "", reviewerSession)
        .andExpect(status().isOk());
    return id;
  }

  private String policyBody(String docType, String version, Instant effectiveAt, Instant noticeAt) {
    return "{\"docType\":\""
        + docType
        + "\",\"version\":\""
        + version
        + "\",\"body\":\"제1조 목적\\n이 약관은 서비스 이용 조건을 정한다.\",\"changeSummary\":\"환불 조항 보완\","
        + "\"noticeAt\":\""
        + at(noticeAt)
        + "\",\"requiresConsent\":false,\"effectiveAt\":\""
        + at(effectiveAt)
        + "\"}";
  }

  private long createPolicy(String docType, String version, Instant effectiveAt, Instant noticeAt)
      throws Exception {
    String body =
        post(POLICIES, policyBody(docType, version, effectiveAt, noticeAt), opsSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"documentId\":(\\d+).*", "$1"));
  }

  private static String infoBody(String businessName, String supportEmail) {
    return "{\"businessName\":\""
        + businessName
        + "\",\"representativeName\":\"대표자\",\"businessNumber\":\"000-00-00000\","
        + "\"address\":\"사업장 주소\",\"contactPhone\":\"02-0000-0000\",\"ecommerceNotice\":null,"
        + "\"supportEmail\":\""
        + supportEmail
        + "\",\"supportHours\":\"평일 09:00-18:00\",\"emergencyContact\":\"02-0000-0001\","
        + "\"privacyOfficer\":\"개인정보 담당\",\"privacyContact\":\"privacy@example.com\","
        + "\"privacyRequestPath\":\"설정 > 개인정보 요청\",\"cancellationPath\":\"설정 > 구독 > 해지 예약\"}";
  }

  private long createInfo(String businessName) throws Exception {
    String body =
        post(INFO, infoBody(businessName, "support@example.com"), opsSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"infoId\":(\\d+).*", "$1"));
  }

  private void marketingConsent(TestData.Staff owner, boolean enabled) {
    jdbcTemplate.update(
        "INSERT INTO app_user_notification_preference (user_id, channel_key, enabled) VALUES (?,"
            + " 'MARKETING', ?)",
        owner.user().getId(),
        enabled);
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_맞지_않으면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    Cookie billing = sessionOf(operator("박결제", OperatorRole.BILLING));
    for (String path : new String[] {ANNOUNCEMENTS, ANNOUNCEMENTS + "/1/preview", POLICIES, INFO}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(get(path).cookie(billing)), ErrorCode.FORBIDDEN);
    }
    String[] posts = {
      ANNOUNCEMENTS,
      ANNOUNCEMENTS + "/1/submit-review",
      ANNOUNCEMENTS + "/1/publish",
      ANNOUNCEMENTS + "/1/end",
      ANNOUNCEMENTS + "/1/revisions",
      POLICIES,
      POLICIES + "/1/submit-review",
      POLICIES + "/1/publish",
      INFO,
      INFO + "/1/inquiry-test"
    };
    for (String path : posts) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Cookie member = memberSession(owner);
    for (String path : new String[] {ANNOUNCEMENTS, POLICIES, INFO}) {
      assertError(mockMvc.perform(get(path).cookie(member)), ErrorCode.UNAUTHENTICATED);
    }
    for (String path :
        new String[] {
          ANNOUNCEMENTS + "/999999/submit-review",
          ANNOUNCEMENTS + "/999999/publish",
          ANNOUNCEMENTS + "/999999/end",
          POLICIES + "/999999/submit-review",
          POLICIES + "/999999/publish"
        }) {
      assertError(post(path, "", opsSession), ErrorCode.RESOURCE_NOT_FOUND);
    }
    assertError(
        post(ANNOUNCEMENTS + "/999999/revisions", announcementBody(all(), false), opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(get(ANNOUNCEMENTS + "/999999/preview").cookie(opsSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(INFO + "/999999/inquiry-test", "{\"result\":\"SUCCESS\"}", opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 공지_작성은_대상_조합과_기간과_링크를_검사한다() throws Exception {
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody("\"audienceType\":\"SUBSCRIPTION_STATUS\",", false),
            opsSession),
        "audienceStatus",
        "REQUIRED");
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody("\"audienceType\":\"ALL\",\"audienceOfficeId\":1,", false),
            opsSession),
        "audienceOfficeId",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody("\"audienceType\":\"OFFICE\",\"audienceStatus\":\"ACTIVE\",", false),
            opsSession),
        "audienceStatus",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody(
                "\"audienceType\":\"SUBSCRIPTION_STATUS\",\"audienceStatus\":\"PAID\",", false),
            opsSession),
        "audienceStatus",
        "NOT_ALLOWED");
    assertError(
        post(
            ANNOUNCEMENTS,
            announcementBody("\"audienceType\":\"OFFICE\",\"audienceOfficeId\":999999,", false),
            opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody(all(), false)
                .replace(
                    "\"marketing\":false",
                    "\"marketing\":false,\"detailUrl\":\"javascript:alert(1)\""),
            opsSession),
        "detailUrl",
        "INVALID_FORMAT");
    assertFieldError(
        post(
            ANNOUNCEMENTS,
            announcementBody(all(), false).replace(at(now.plus(Duration.ofDays(3))), at(now)),
            opsSession),
        "endsAt",
        "OUT_OF_RANGE");
    assertFieldError(
        post(ANNOUNCEMENTS, announcementBody(all(), false).replace("정기점검 안내", " "), opsSession),
        "title",
        "REQUIRED");
    assertFieldError(
        post(ANNOUNCEMENTS, announcementBody(all(), false).replace("NORMAL", "URGENT"), opsSession),
        "importance",
        "NOT_ALLOWED");

    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    post(
            ANNOUNCEMENTS,
            announcementBody(
                    "\"audienceType\":\"OFFICE\",\"audienceOfficeId\":"
                        + owner.office().getId()
                        + ",",
                    false)
                .replace("\"marketing\":false", "\"marketing\":false,\"detailUrl\":\"/notices/1\""),
            opsSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.publishedAt").value(Matchers.nullValue()));
    mockMvc
        .perform(get(ANNOUNCEMENTS).cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].audienceType").value("OFFICE"))
        .andExpect(jsonPath("$.items[0].audienceOfficeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].detailUrl").value("/notices/1"))
        .andExpect(jsonPath("$.items[0].createdByOperatorId").value(ops.id()))
        .andExpect(jsonPath("$.items[0].previousVersionId").value(Matchers.nullValue()));
    assertFieldError(
        mockMvc.perform(get(ANNOUNCEMENTS).param("status", "LIVE").cookie(opsSession)),
        "status",
        "NOT_ALLOWED");
    assertThat(auditCount("ANNOUNCEMENT_CREATED")).isEqualTo(1);
  }

  @Test
  void 공지는_검토_뒤에만_게시하고_게시본_수정은_새_버전으로_이전_게시본과_잇는다() throws Exception {
    long id = createAnnouncement(all(), false);
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/publish", "", opsSession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/end", "", opsSession), ErrorCode.INVALID_TRANSITION);
    post(ANNOUNCEMENTS + "/" + id + "/submit-review", "", reviewerSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEW"))
        .andExpect(jsonPath("$.reviewedByOperatorId").value(reviewer.id()));
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/submit-review", "", opsSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/revisions", announcementBody(all(), false), opsSession),
        ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        post(
            ANNOUNCEMENTS + "/" + id + "/publish",
            "{\"scheduledAt\":\"" + at(now.minus(Duration.ofMinutes(1))) + "\"}",
            opsSession),
        "scheduledAt",
        "OUT_OF_RANGE");

    post(ANNOUNCEMENTS + "/" + id + "/publish", "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.scheduledAt").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/publish", "", opsSession), ErrorCode.INVALID_TRANSITION);

    String revised = announcementBody(all(), false).replace("정기점검 안내", "정기점검 시간 변경 안내");
    String body =
        post(ANNOUNCEMENTS + "/" + id + "/revisions", revised, opsSession)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.versionNo").value(2))
            .andExpect(jsonPath("$.previousVersionId").value(id))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long revision = Long.parseLong(body.replaceAll("(?s).*\"announcementId\":(\\d+).*", "$1"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM platform_announcement WHERE id = ?", String.class, id))
        .isEqualTo("PUBLISHED");
    mockMvc
        .perform(get(ANNOUNCEMENTS).param("status", "DRAFT").cookie(opsSession))
        .andExpect(jsonPath("$.items[0].announcementId").value(revision))
        .andExpect(jsonPath("$.items[0].title").value("정기점검 시간 변경 안내"))
        .andExpect(jsonPath("$.items[0].previousVersionId").value(id));

    post(ANNOUNCEMENTS + "/" + id + "/end", "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.endedAt").isNotEmpty());
    assertError(
        post(ANNOUNCEMENTS + "/" + id + "/end", "", opsSession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("ANNOUNCEMENT_PUBLISHED")).isEqualTo(1);
    assertThat(auditCount("ANNOUNCEMENT_REVISED")).isEqualTo(1);
    assertThat(auditCount("ANNOUNCEMENT_ENDED")).isEqualTo(1);
  }

  @Test
  void 예약_공지는_종료하면_예약이_취소되고_배치가_예약_시각에_게시한다() throws Exception {
    long cancelled = reviewedAnnouncement();
    Instant scheduledAt = now.plus(Duration.ofHours(2));
    post(
            ANNOUNCEMENTS + "/" + cancelled + "/publish",
            "{\"scheduledAt\":\"" + at(scheduledAt) + "\"}",
            opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.publishedAt").value(Matchers.nullValue()));
    post(ANNOUNCEMENTS + "/" + cancelled + "/end", "", opsSession)
        .andExpect(jsonPath("$.status").value("ENDED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT published_at FROM platform_announcement WHERE id = ?",
                Timestamp.class,
                cancelled))
        .isNull();

    long scheduled = reviewedAnnouncement();
    post(
            ANNOUNCEMENTS + "/" + scheduled + "/publish",
            "{\"scheduledAt\":\"" + at(scheduledAt) + "\"}",
            opsSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    assertThat(contentService.publishDueAnnouncements(now)).isZero();
    assertThat(contentService.publishDueAnnouncements(scheduledAt.plusSeconds(1))).isEqualTo(1);
    mockMvc
        .perform(get(ANNOUNCEMENTS).param("status", "PUBLISHED").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].announcementId").value(scheduled));
    Timestamp published =
        jdbcTemplate.queryForObject(
            "SELECT published_at FROM platform_announcement WHERE id = ?",
            Timestamp.class,
            scheduled);
    Timestamp planned =
        jdbcTemplate.queryForObject(
            "SELECT scheduled_at FROM platform_announcement WHERE id = ?",
            Timestamp.class,
            scheduled);
    assertThat(published).isEqualTo(planned);
    assertThat(auditCount("ANNOUNCEMENT_SCHEDULED")).isEqualTo(2);
  }

  @Test
  void 공지_미리보기는_대상_사무소_수와_마케팅_미동의_제외_수를_보인다() throws Exception {
    TestData.Staff agreed = staff(OfficeMembershipRole.OWNER);
    TestData.Staff declined = staff(OfficeMembershipRole.OWNER);
    TestData.Staff silent = staff(OfficeMembershipRole.OWNER);
    marketingConsent(agreed, true);
    marketingConsent(declined, false);
    new BillingFixtures(jdbcTemplate)
        .subscription(agreed.office().getId())
        .status("ACTIVE")
        .period(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(25)))
        .insert();

    long allMarketing = createAnnouncement(all(), true);
    mockMvc
        .perform(get(ANNOUNCEMENTS + "/" + allMarketing + "/preview").cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetOfficeCount").value(3))
        .andExpect(jsonPath("$.marketingExcludedCount").value(2))
        .andExpect(jsonPath("$.renderedContent").value("정기점검 안내\n9/30 02:00~04:00 점검 예정입니다."));
    long allRequired = createAnnouncement(all(), false);
    mockMvc
        .perform(get(ANNOUNCEMENTS + "/" + allRequired + "/preview").cookie(opsSession))
        .andExpect(jsonPath("$.targetOfficeCount").value(3))
        .andExpect(jsonPath("$.marketingExcludedCount").value(0));
    long active =
        createAnnouncement(
            "\"audienceType\":\"SUBSCRIPTION_STATUS\",\"audienceStatus\":\"ACTIVE\",", true);
    mockMvc
        .perform(get(ANNOUNCEMENTS + "/" + active + "/preview").cookie(opsSession))
        .andExpect(jsonPath("$.targetOfficeCount").value(1))
        .andExpect(jsonPath("$.marketingExcludedCount").value(0));
    long single =
        createAnnouncement(
            "\"audienceType\":\"OFFICE\",\"audienceOfficeId\":" + silent.office().getId() + ",",
            true);
    mockMvc
        .perform(get(ANNOUNCEMENTS + "/" + single + "/preview").cookie(opsSession))
        .andExpect(jsonPath("$.targetOfficeCount").value(1))
        .andExpect(jsonPath("$.marketingExcludedCount").value(1));
  }

  @Test
  void 정책_문서는_검토_뒤_게시하고_요금_정책은_시행_30일_전까지만_게시한다() throws Exception {
    Instant effective = now.plus(Duration.ofDays(10));
    assertFieldError(
        post(
            POLICIES,
            policyBody("TERMS", "2026-12-01", effective, effective.plusSeconds(60)),
            opsSession),
        "noticeAt",
        "OUT_OF_RANGE");
    assertFieldError(
        post(POLICIES, policyBody("PRICING", "2026-12-01", effective, now), opsSession),
        "noticeAt",
        "OUT_OF_RANGE");
    assertFieldError(
        post(POLICIES, policyBody("COOKIE", "2026-12-01", effective, now), opsSession),
        "docType",
        "NOT_ALLOWED");
    assertFieldError(
        post(POLICIES, policyBody("TERMS", "v".repeat(21), effective, now), opsSession),
        "version",
        "TOO_LONG");
    assertFieldError(
        post(
            POLICIES,
            policyBody("TERMS", "2026-12-01", effective, now).replace("제1조 목적", "<b>제1조</b>"),
            opsSession),
        "body",
        "HTML_NOT_ALLOWED");

    long terms = createPolicy("TERMS", "2026-12-01", effective, now);
    assertError(
        post(POLICIES, policyBody("TERMS", "2026-12-01", effective, now), opsSession),
        ErrorCode.DOCUMENT_VERSION_CONFLICT);
    assertError(
        post(POLICIES + "/" + terms + "/publish", "", opsSession), ErrorCode.INVALID_TRANSITION);
    post(POLICIES + "/" + terms + "/submit-review", "", reviewerSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEW"))
        .andExpect(jsonPath("$.version").value("2026-12-01"))
        .andExpect(jsonPath("$.reviewedByOperatorId").value(reviewer.id()));
    assertError(
        post(POLICIES + "/" + terms + "/submit-review", "", opsSession),
        ErrorCode.INVALID_TRANSITION);
    Long recipient =
        jdbcTemplate.queryForObject(
            "INSERT INTO app_user (kakao_provider_key, name) VALUES ('terms-notice-user', '고지 수신자')"
                + " RETURNING id",
            Long.class);
    Long leaver =
        jdbcTemplate.queryForObject(
            "INSERT INTO app_user (kakao_provider_key, name, account_status, withdrawn_at) VALUES"
                + " ('terms-notice-leaver', '탈퇴 회원', 'WITHDRAWN', now()) RETURNING id",
            Long.class);
    post(POLICIES + "/" + terms + "/publish", "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    assertError(
        post(POLICIES + "/" + terms + "/publish", "", opsSession), ErrorCode.INVALID_TRANSITION);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE notification_type = 'TERMS_CHANGED' AND"
                    + " recipient_user_id = ? AND target_type = 'NONE'",
                Long.class,
                recipient))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE recipient_user_id = ?",
                Long.class,
                leaver))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id ="
                    + " d.notification_id WHERE n.recipient_user_id = ? AND d.channel = 'EMAIL'"
                    + " AND d.status = 'PENDING'",
                Long.class,
                recipient))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM system_job_run WHERE idempotency_key = ? AND status ="
                    + " 'SUCCESS'",
                Long.class,
                "terms-change-notice:" + terms))
        .isEqualTo(1L);

    long pricing =
        createPolicy(
            "PRICING", "2027-01-01", now.plus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)));
    post(POLICIES + "/" + pricing + "/submit-review", "", reviewerSession)
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE policy_document SET effective_at = now() + interval '29 days' WHERE id = ?",
        pricing);
    assertFieldError(
        post(POLICIES + "/" + pricing + "/publish", "", opsSession), "effectiveAt", "OUT_OF_RANGE");
    jdbcTemplate.update(
        "UPDATE policy_document SET effective_at = now() + interval '31 days' WHERE id = ?",
        pricing);
    post(POLICIES + "/" + pricing + "/publish", "", opsSession)
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    mockMvc
        .perform(get(POLICIES).param("docType", "TERMS").cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].body").value("제1조 목적\n이 약관은 서비스 이용 조건을 정한다."))
        .andExpect(jsonPath("$.items[0].reviewedByOperatorId").value(reviewer.id()))
        .andExpect(jsonPath("$.items[0].publishedByOperatorId").value(ops.id()))
        .andExpect(jsonPath("$.items[0].createdByOperatorId").value(ops.id()));
    mockMvc
        .perform(get(POLICIES).param("status", "PUBLISHED").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(2));
    assertThat(auditCount("POLICY_DOCUMENT_PUBLISHED")).isEqualTo(2);
  }

  @Test
  void 회사_정보는_버전으로_쌓고_수신_시험_성공_때_게시하며_출시_준비를_판정한다() throws Exception {
    PolicyFixtures.publishLaunchPolicies(jdbcTemplate, ops.id());
    new BillingFixtures(jdbcTemplate)
        .standardPrice("2026-LAUNCH-STD", now.minus(Duration.ofDays(1)));
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.launchReady").value(false))
        .andExpect(
            jsonPath("$.missingItems")
                .value(Matchers.contains("PUBLISHED_VERSION", "INQUIRY_DELIVERY_TEST", "REVIEW")));
    assertFieldError(
        post(INFO, infoBody("사이로", "not-an-email"), opsSession), "supportEmail", "INVALID_FORMAT");
    assertFieldError(
        post(INFO, infoBody(" ", "support@example.com"), opsSession), "businessName", "REQUIRED");

    long first = createInfo("사이로");
    post(INFO, infoBody("사이로", "support@example.com"), opsSession)
        .andExpect(jsonPath("$.versionNo").value(2));
    assertFieldError(
        post(INFO + "/" + first + "/inquiry-test", "{\"result\":\"MAYBE\"}", opsSession),
        "result",
        "NOT_ALLOWED");
    post(INFO + "/" + first + "/inquiry-test", "{\"result\":\"FAILED\"}", reviewerSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inquiryDeliveryTestedAt").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.reviewedByOperatorId").value(Matchers.nullValue()));
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].versionNo").value(2))
        .andExpect(jsonPath("$.launchReady").value(false));
    mockMvc
        .perform(get("/api/public/company-info"))
        .andExpect(jsonPath("$.versionNo").value(Matchers.nullValue()));

    post(INFO + "/" + first + "/inquiry-test", "{\"result\":\"SUCCESS\"}", reviewerSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inquiryDeliveryTestedAt").isNotEmpty())
        .andExpect(jsonPath("$.reviewedByOperatorId").value(reviewer.id()))
        .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.launchReady").value(true))
        .andExpect(jsonPath("$.missingItems").isEmpty())
        .andExpect(jsonPath("$.items[1].publishedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].publishedAt").value(Matchers.nullValue()));
    mockMvc
        .perform(get("/api/public/company-info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.supportEmail").value("support@example.com"));
    post(INFO + "/" + first + "/inquiry-test", "{\"result\":\"FAILED\"}", opsSession)
        .andExpect(jsonPath("$.reviewedByOperatorId").value(reviewer.id()));
    assertThat(auditCount("COMPANY_SERVICE_INFO_CREATED")).isEqualTo(2);
    assertThat(auditCount("COMPANY_INQUIRY_TEST_RECORDED")).isEqualTo(3);
  }

  @Test
  void 출시_준비는_필수_정책_문서_게시본과_승인된_표준_요금표가_모두_있어야_한다() throws Exception {
    long first = createInfo("사이로");
    post(INFO + "/" + first + "/inquiry-test", "{\"result\":\"SUCCESS\"}", reviewerSession)
        .andExpect(status().isOk());
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.launchReady").value(false))
        .andExpect(
            jsonPath("$.missingItems")
                .value(
                    Matchers.contains(
                        "POLICY_DOCUMENTS_NOT_PUBLISHED", "STANDARD_PRICE_SCHEDULE_NOT_APPROVED")));

    PolicyFixtures.publish(jdbcTemplate, "TERMS", ops.id());
    PolicyFixtures.publish(jdbcTemplate, "PRIVACY", ops.id());
    BillingFixtures fixtures = new BillingFixtures(jdbcTemplate);
    fixtures.price("2027-DRAFT-STD", "STANDARD", now.plus(Duration.ofDays(40)), "DRAFT", 39_900);
    fixtures.price("2026-START", "START_CUSTOMER", now.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.launchReady").value(false))
        .andExpect(
            jsonPath("$.missingItems")
                .value(
                    Matchers.contains(
                        "POLICY_DOCUMENTS_NOT_PUBLISHED", "STANDARD_PRICE_SCHEDULE_NOT_APPROVED")));

    PolicyFixtures.publish(jdbcTemplate, "REFUND", ops.id());
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.launchReady").value(false))
        .andExpect(
            jsonPath("$.missingItems")
                .value(Matchers.contains("STANDARD_PRICE_SCHEDULE_NOT_APPROVED")));

    fixtures.standardPrice("2026-LAUNCH-STD", now.minus(Duration.ofDays(1)));
    mockMvc
        .perform(get(INFO).cookie(opsSession))
        .andExpect(jsonPath("$.launchReady").value(true))
        .andExpect(jsonPath("$.missingItems").isEmpty());
  }
}
