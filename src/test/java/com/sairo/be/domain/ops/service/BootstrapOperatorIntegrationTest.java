package com.sairo.be.domain.ops.service;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.service.BootstrapOperatorService.Outcome;
import com.sairo.be.global.config.BootstrapOperatorProperties;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TokenGenerator;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class BootstrapOperatorIntegrationTest extends OpsIntegrationTestBase {

  private static final String EMAIL = "first.operator@example.com";
  private static final String KAKAO_ID = "7202001";
  private static final Path DIRECTORY = createDirectory();
  private static final Path FILE = DIRECTORY.resolve("invitation.txt");
  private static final Pattern TOKEN_IN_FILE = Pattern.compile("token=([A-Za-z0-9_%-]+)");

  @Autowired private BootstrapOperatorService service;
  @Autowired private OperatorAccountRepository accounts;
  @Autowired private OperatorInvitationRepository invitations;
  @Autowired private OpsAuditLogRepository auditLog;
  @Autowired private AdvisoryLock advisoryLock;
  @Autowired private TokenGenerator tokenGenerator;
  @Autowired private Clock clock;
  @Autowired private BootstrapOperatorProperties properties;

  private static Path createDirectory() {
    try {
      return Files.createTempDirectory("sairo-bootstrap-test");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("sairo.bootstrap.operator.email", () -> EMAIL);
    registry.add("sairo.bootstrap.operator.invitation-file", FILE::toString);
  }

  @BeforeEach
  void clearFile() throws IOException {
    Files.deleteIfExists(FILE);
  }

  private String tokenFromFile() throws IOException {
    Matcher matcher = TOKEN_IN_FILE.matcher(Files.readString(FILE, StandardCharsets.UTF_8));
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private long invitationCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM operator_invitation", Long.class);
  }

  @Test
  void 활성_운영자가_없으면_보안관리자_초대를_하나_만들고_링크를_파일에만_쓴다() throws Exception {
    assertThat(service.bootstrap()).isEqualTo(Outcome.CREATED);

    assertThat(invitationCount()).isEqualTo(1L);
    var row =
        jdbcTemplate.queryForMap(
            "SELECT invitee_email, status, token_hash, expires_at - created_at AS validity"
                + " FROM operator_invitation");
    assertThat(row).containsEntry("invitee_email", EMAIL).containsEntry("status", "PENDING");
    assertThat(jdbcTemplate.queryForList("SELECT role FROM operator_invitation_role", String.class))
        .containsExactly(OperatorRole.SECURITY_ADMIN.name());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE action_type ="
                    + " 'OPERATOR_BOOTSTRAP_INVITATION_CREATED'",
                Long.class))
        .isEqualTo(1L);
    String content = Files.readString(FILE, StandardCharsets.UTF_8);
    assertThat(content).contains("/ops/invitations/accept?token=");
  }

  @Test
  void 토큰은_해시로만_저장되고_원문은_어디에도_남지_않는다() throws Exception {
    service.bootstrap();

    String token = tokenFromFile();
    String stored =
        jdbcTemplate.queryForObject("SELECT token_hash FROM operator_invitation", String.class);
    assertThat(stored)
        .hasSize(64)
        .isEqualTo(OperatorInvitationAuthService.sha256(token))
        .isNotEqualTo(token);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_invitation WHERE token_hash = ? OR invitee_name = ?",
                Long.class,
                token,
                token))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE detail LIKE ?",
                Long.class,
                "%" + token + "%"))
        .isZero();
  }

  @Test
  void 초대_파일은_소유자만_읽고_쓸_수_있다() throws Exception {
    assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");

    service.bootstrap();

    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(FILE);
    assertThat(PosixFilePermissions.toString(permissions)).isEqualTo("rw-------");
  }

  @Test
  void 만료되지_않은_초대가_있으면_다시_만들지_않고_파일도_바꾸지_않는다() throws Exception {
    service.bootstrap();
    String first = Files.readString(FILE, StandardCharsets.UTF_8);

    assertThat(service.bootstrap()).isEqualTo(Outcome.INVITATION_PENDING);

    assertThat(invitationCount()).isEqualTo(1L);
    assertThat(Files.readString(FILE, StandardCharsets.UTF_8)).isEqualTo(first);
  }

  @Test
  void 활성_운영자가_이미_있으면_초대도_파일도_만들지_않는다() throws Exception {
    createOperator("existing-operator", "기존 운영자", OperatorRole.SECURITY_ADMIN);

    assertThat(service.bootstrap()).isEqualTo(Outcome.OPERATOR_EXISTS);

    assertThat(invitationCount()).isZero();
    assertThat(Files.exists(FILE)).isFalse();
  }

  @Test
  void 설정을_끄면_실행기는_아무것도_만들지_않는다() throws Exception {
    BootstrapOperatorProperties disabled =
        new BootstrapOperatorProperties(
            false, properties.email(), properties.name(), properties.invitationFile());

    new BootstrapOperatorRunner(disabled, service).run(null);

    assertThat(invitationCount()).isZero();
    assertThat(Files.exists(FILE)).isFalse();
  }

  @Test
  void 설정을_켜면_실행기가_초대를_만든다() throws Exception {
    BootstrapOperatorProperties enabled =
        new BootstrapOperatorProperties(
            true, properties.email(), properties.name(), properties.invitationFile());

    new BootstrapOperatorRunner(enabled, service).run(null);

    assertThat(invitationCount()).isEqualTo(1L);
    assertThat(Files.exists(FILE)).isTrue();
  }

  @Test
  void 만료된_초대만_남았고_운영자가_없으면_새_초대로_대체한다() throws Exception {
    service.bootstrap();
    String firstToken = tokenFromFile();
    jdbcTemplate.update(
        "UPDATE operator_invitation SET created_at = created_at - interval '73 hours',"
            + " expires_at = expires_at - interval '73 hours'");

    assertThat(service.bootstrap()).isEqualTo(Outcome.CREATED);

    assertThat(invitationCount()).isEqualTo(2L);
    assertThat(tokenFromFile()).isNotEqualTo(firstToken);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_invitation WHERE status = 'PENDING'", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 설정이_올바르지_않으면_초대를_만들지_않는다() throws Exception {
    for (BootstrapOperatorProperties invalid :
        new BootstrapOperatorProperties[] {
          new BootstrapOperatorProperties(true, "not-an-email", null, FILE.toString()),
          new BootstrapOperatorProperties(true, "", null, FILE.toString()),
          new BootstrapOperatorProperties(true, EMAIL, null, " ")
        }) {
      BootstrapOperatorService broken =
          new BootstrapOperatorService(
              accounts,
              invitations,
              auditLog,
              advisoryLock,
              tokenGenerator,
              invalid,
              clock,
              "http://localhost:3000");

      assertThat(broken.bootstrap()).isEqualTo(Outcome.INVALID_CONFIGURATION);
    }

    assertThat(invitationCount()).isZero();
    assertThat(Files.exists(FILE)).isFalse();
  }

  @Test
  void 만들어진_초대는_기존_카카오_로그인과_2차_인증_흐름으로_수락되어_보안관리자_계정이_생긴다() throws Exception {
    service.bootstrap();
    String token = java.net.URLDecoder.decode(tokenFromFile(), StandardCharsets.UTF_8);

    MvcResult started =
        mockMvc
            .perform(
                jsonPost("/api/ops/auth/invitations/kakao-start", "{\"token\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String url = json(started).get("authorizationUrl").asString();
    Matcher state = Pattern.compile("state=([A-Za-z0-9_-]+)").matcher(url);
    assertThat(state.find()).isTrue();
    MvcResult callback =
        mockMvc
            .perform(
                get("/api/ops/auth/invitations/kakao/callback")
                    .cookie(opsCookieOf(started))
                    .param("code", KAKAO_ID)
                    .param("state", state.group(1)))
            .andReturn();
    assertThat(callback.getResponse().getStatus()).isEqualTo(302);
    Cookie enrollment = opsCookieOf(callback);
    MvcResult enrolled =
        mockMvc
            .perform(jsonPost("/api/ops/auth/invitations/mfa-enrollment", "", enrollment))
            .andExpect(status().isOk())
            .andReturn();
    String secret = json(enrolled).get("secret").asString();

    mockMvc
        .perform(
            jsonPost(
                "/api/ops/auth/invitations/accept",
                "{\"otpCode\":\"" + otpFor(secret, 0) + "\"}",
                enrollment))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roles[0]").value("SECURITY_ADMIN"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_account WHERE status = 'ACTIVE'", Long.class))
        .isEqualTo(1L);
    assertThat(service.bootstrap()).isEqualTo(Outcome.OPERATOR_EXISTS);
  }
}
