package com.sairo.be.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.ops.service.TotpService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TextCipher;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

public abstract class VisitReviewTestBase extends VisitOutcomeTestBase {

  private static final Set<String> FORBIDDEN_WORDS =
      Set.of("score", "scores", "rating", "ratings", "rank", "ranking", "star", "stars");

  @Autowired protected TotpService totpService;
  @Autowired protected TextCipher textCipher;

  public record CompletedVisit(SelectionFixture s, long itemId) {

    public String token() {
      return s.token();
    }

    public Cookie cookie() {
      return s.cookie();
    }
  }

  protected CompletedVisit completedVisit() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    recordResult(s, item, completedBody(30)).andExpect(status().isOk());
    return new CompletedVisit(s, item);
  }

  protected static String linkUrl(String token) {
    return "/api/public/approver-property-links/" + token;
  }

  protected static String reviewBody(String punctuality, String contact, String asRequested) {
    return reviewBody(punctuality, contact, asRequested, NOTICE_VERSION);
  }

  protected static String reviewBody(
      String punctuality, String contact, String asRequested, String noticeVersion) {
    return "{\"punctuality\":\""
        + punctuality
        + "\",\"advanceContact\":\""
        + contact
        + "\",\"asRequested\":\""
        + asRequested
        + "\",\"acknowledgedNoticeVersion\":\""
        + noticeVersion
        + "\"}";
  }

  protected ResultActions putReview(String token, long itemId, String body, Object version)
      throws Exception {
    return putReview(token, itemId, body, version, null);
  }

  protected ResultActions putReview(
      String token, long itemId, String body, Object version, String idempotencyKey)
      throws Exception {
    MockHttpServletRequestBuilder builder =
        jsonPutAs(linkUrl(token) + "/visit-reviews/" + itemId, body, null, version);
    if (idempotencyKey != null) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    return mockMvc.perform(builder);
  }

  protected ResultActions withdrawReview(String token, long itemId, Object version)
      throws Exception {
    return postVersioned(
        linkUrl(token) + "/visit-reviews/" + itemId + "/withdraw", "", null, version);
  }

  protected ResultActions reviewList(String token) throws Exception {
    return mockMvc.perform(get(linkUrl(token) + "/visit-reviews"));
  }

  protected long reviewIdOf(long itemId) {
    return count("SELECT id FROM visit_review WHERE schedule_item_id = ?", itemId);
  }

  protected long submitReview(CompletedVisit v) throws Exception {
    putReview(v.token(), v.itemId(), reviewBody("YES", "NO", "SKIP"), null)
        .andExpect(status().isCreated());
    return reviewIdOf(v.itemId());
  }

  protected ResultActions fileDispute(Cookie cookie, long reviewId, String body) throws Exception {
    return mockMvc.perform(
        jsonPostAs("/api/visit-reviews/" + reviewId + "/disputes", body, cookie));
  }

  protected static String disputeBody() {
    return "{\"reasonCode\":\"FACT_INCORRECT\",\"reasonText\":\"방문 전에 확인 연락을 드렸습니다.\"}";
  }

  protected Cookie operatorCookie(OperatorRole... roles) {
    String secret = totpService.newSecret();
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, ?) RETURNING id",
            Long.class,
            "review-op-" + System.nanoTime(),
            "운영자",
            textCipher.encrypt(secret));
    for (OperatorRole role : roles) {
      jdbcTemplate.update(
          "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, ?)",
          id,
          role.name());
    }
    OperatorPrincipal principal = new OperatorPrincipal(id, "운영자", Arrays.asList(roles));
    return OpsIntegrationTestBase.opsCookie(
        sessions.createOperatorSession(principal, Instant.now(), Instant.now()));
  }

  protected static void assertNoScoreFields(JsonNode node) {
    List<String> names = new ArrayList<>();
    collectNames(node, names);
    for (String name : names) {
      for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])")) {
        assertThat(FORBIDDEN_WORDS).as("응답 필드 " + name).doesNotContain(word.toLowerCase());
      }
    }
  }

  private static void collectNames(JsonNode node, List<String> names) {
    if (node.isObject()) {
      for (String name : node.propertyNames()) {
        names.add(name);
        collectNames(node.get(name), names);
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectNames(child, names);
      }
    }
  }
}
