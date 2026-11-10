package com.sairo.be.support;

import com.sairo.be.domain.ops.service.OperatorInvitationAuthService;
import com.sairo.be.domain.ops.service.TotpService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TextCipher;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

public abstract class OpsIntegrationTestBase extends AuthIntegrationTestBase {

  public static final String OPS_COOKIE_NAME = "__Host-sairo_ops_session";

  @Autowired protected TotpService totpService;
  @Autowired protected TextCipher textCipher;

  public record Operator(
      long id, String kakaoKey, String name, String secret, OperatorRole[] roles) {

    public OperatorPrincipal principal() {
      return new OperatorPrincipal(id, name, Arrays.asList(roles));
    }
  }

  public record OpsStart(Cookie cookie, String state, String location) {}

  protected Operator createOperator(String kakaoKey, String name, OperatorRole... roles) {
    String secret = totpService.newSecret();
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, ?) RETURNING id",
            Long.class,
            kakaoKey,
            name,
            textCipher.encrypt(secret));
    for (OperatorRole role : roles) {
      jdbcTemplate.update(
          "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, ?)",
          id,
          role.name());
    }
    return new Operator(id, kakaoKey, name, secret, roles);
  }

  protected Cookie operatorSession(Operator operator) {
    return opsCookie(
        sessions.createOperatorSession(operator.principal(), Instant.now(), Instant.now()));
  }

  protected static Cookie opsCookie(String sessionId) {
    return new Cookie(
        OPS_COOKIE_NAME,
        java.util.Base64.getEncoder()
            .encodeToString(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  protected static Cookie opsCookieOf(MvcResult result) {
    return result.getResponse().getCookie(OPS_COOKIE_NAME);
  }

  protected String otp(Operator operator) {
    return otpFor(operator.secret(), 0);
  }

  protected String otpFor(String secret, int stepOffset) {
    return totpService.codeAt(secret, Instant.now().getEpochSecond() / 30 + stepOffset);
  }

  protected String wrongOtp(String secret) {
    String valid = otpFor(secret, 0);
    for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
      String code = String.format("%06d", candidate);
      boolean collides = false;
      for (int offset = -1; offset <= 1; offset++) {
        collides |= code.equals(otpFor(secret, offset));
      }
      if (!collides && !code.equals(valid)) {
        return code;
      }
    }
    throw new IllegalStateException("틀린 코드를 만들지 못했습니다.");
  }

  protected OpsStart startOps(String url) throws Exception {
    MvcResult result =
        mockMvc
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
            .andReturn();
    return opsStartOf(result);
  }

  protected OpsStart opsStartOf(MvcResult result) {
    String location = result.getResponse().getHeader("Location");
    Matcher matcher =
        Pattern.compile("state=([A-Za-z0-9_-]+)").matcher(location == null ? "" : location);
    return new OpsStart(opsCookieOf(result), matcher.find() ? matcher.group(1) : null, location);
  }

  protected long insertInvitation(
      String token, String name, String status, int hoursUntilExpiry, OperatorRole... roles) {
    Operator inviter =
        createOperator("inviter-" + System.nanoTime(), "초대자", OperatorRole.SECURITY_ADMIN);
    long createdHoursAgo = 72 - hoursUntilExpiry;
    String extra =
        switch (status) {
          case "CANCELLED" -> ", cancelled_by_operator_id, cancelled_at";
          default -> "";
        };
    String extraValues = status.equals("CANCELLED") ? ", " + inviter.id() + ", now()" : "";
    Long id;
    if (status.equals("ACCEPTED")) {
      Operator accepted = createOperator("accepted-" + System.nanoTime(), "수락자", OperatorRole.OPS);
      id =
          jdbcTemplate.queryForObject(
              "INSERT INTO operator_invitation (invitee_name, invitee_email, token_hash, status,"
                  + " invited_by_operator_id, expires_at, created_at, accepted_operator_id,"
                  + " accepted_at) VALUES (?, ?, ?, 'ACCEPTED', ?, now() + (? || ' hours')::interval,"
                  + " now() - (? || ' hours')::interval, ?, now()) RETURNING id",
              Long.class,
              name,
              token + "@example.com",
              OperatorInvitationAuthService.sha256(token),
              inviter.id(),
              String.valueOf(hoursUntilExpiry),
              String.valueOf(createdHoursAgo),
              accepted.id());
    } else {
      id =
          jdbcTemplate.queryForObject(
              "INSERT INTO operator_invitation (invitee_name, invitee_email, token_hash, status,"
                  + " invited_by_operator_id, expires_at, created_at"
                  + extra
                  + ") VALUES (?, ?, ?, ?, ?, now() + (? || ' hours')::interval,"
                  + " now() - (? || ' hours')::interval"
                  + extraValues
                  + ") RETURNING id",
              Long.class,
              name,
              token + "@example.com",
              OperatorInvitationAuthService.sha256(token),
              status,
              inviter.id(),
              String.valueOf(hoursUntilExpiry),
              String.valueOf(createdHoursAgo));
    }
    List<OperatorRole> roleList = Arrays.asList(roles);
    for (OperatorRole role : roleList) {
      jdbcTemplate.update(
          "INSERT INTO operator_invitation_role (invitation_id, role) VALUES (?, ?)",
          id,
          role.name());
    }
    return id;
  }
}
