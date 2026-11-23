package com.sairo.be.global.support.redis;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.repository.OAuthStateRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.PrincipalSessions;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.SecurityProbeController;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 운영과 같은 옵션(requirepass, AOF, 키스페이스 알림)의 비밀번호 보호 Redis에서 인증 관련 동작을 검증한다. 전체 앱을 올리고 합성 데이터만 쓴다. 로그인
 * 실패 잠금과 요청 제한은 PostgreSQL 기반이라 Redis 장애와 무관하게 동작해야 하고, 세션은 Redis에만 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityProbeController.class)
@TestPropertySource(
    properties = {
      "sairo.integrations.kakao-oauth=stub",
      "kakao.admin-key=test-admin-key",
      "kakao.app-id=1234567",
      "sairo.migration.import-executor=sync",
      "sairo.support.scan-dispatch=manual",
      "logging.level.io.lettuce=DEBUG",
      "logging.level.org.springframework.data.redis=DEBUG",
      "logging.level.org.springframework.session=DEBUG"
    })
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisAuthenticatedIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "redis.verify@example.com";
  private static final String SESSION_PREFIX = "sairo:session:";

  @Autowired private OAuthStateRepository oAuthStateRepository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ProtectedRedisEnvironment.register(registry, ProtectedRedisEnvironment.PASSWORD);
  }

  @AfterEach
  void ensureRedisRunning() {
    ProtectedRedisEnvironment.startRedisIfStopped();
  }

  private ResultActions loginRequest(String password) throws Exception {
    return mockMvc.perform(
        jsonPost(
            "/api/auth/login",
            "{\"email\":" + quote(EMAIL) + ",\"password\":" + quote(password) + "}"));
  }

  private Cookie loginOk() throws Exception {
    MvcResult result = loginRequest(PASSWORD).andExpect(status().isOk()).andReturn();
    Cookie cookie = AuthFlowSupport.sessionCookie(result);
    assertThat(cookie).isNotNull();
    return cookie;
  }

  private long ttl(String key) {
    Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
    return ttl == null ? -3 : ttl;
  }

  @Test
  @Order(1)
  void 인증된_Redis에서도_로그인_5회_실패는_계정을_잠그고_잠금은_PostgreSQL에_있다() throws Exception {
    saveEmailUser(EMAIL, null);

    for (int i = 1; i <= 5; i++) {
      assertError(loginRequest("wrong-password"), ErrorCode.INVALID_CREDENTIALS);
    }
    assertError(loginRequest(PASSWORD), ErrorCode.LOGIN_LOCKED);

    Integer rows =
        jdbcTemplate.queryForObject("SELECT count(*) FROM rate_limit_counter", Integer.class);
    assertThat(rows).isPositive();
    assertThat(redisTemplate.keys("*rate*")).isEmpty();
    assertThat(redisTemplate.keys("*login*")).isEmpty();
  }

  @Test
  @Order(2)
  void 로그인하면_세션이_Redis에_저장되고_요청마다_만료시간이_갱신된다() throws Exception {
    var user = saveEmailUser(EMAIL, null);
    Cookie cookie = loginOk();
    String id = sessionIdOf(cookie);

    assertThat(redisTemplate.hasKey(SESSION_PREFIX + "sessions:" + id)).isTrue();
    assertThat(redisTemplate.hasKey(SESSION_PREFIX + "sessions:expires:" + id)).isTrue();
    long idleMillis = Duration.ofHours(12).toMillis();
    assertThat(ttl(SESSION_PREFIX + "sessions:expires:" + id))
        .isBetween(idleMillis - 60_000, idleMillis + 1_000);
    assertThat(indexed(indexKey(PrincipalSessions.memberPrincipalName(user.getId())), id)).isTrue();

    redisTemplate.expire(SESSION_PREFIX + "sessions:expires:" + id, Duration.ofSeconds(100));
    assertThat(ttl(SESSION_PREFIX + "sessions:expires:" + id)).isLessThan(100_000);

    mockMvc.perform(get("/api/auth/context").cookie(cookie)).andExpect(status().isOk());

    long deadline = System.currentTimeMillis() + 5_000;
    while (ttl(SESSION_PREFIX + "sessions:expires:" + id) <= idleMillis - 60_000
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertThat(ttl(SESSION_PREFIX + "sessions:expires:" + id)).isGreaterThan(idleMillis - 60_000);
  }

  @Test
  @Order(3)
  void 만료된_세션은_401이고_키스페이스_알림으로_색인까지_정리된다() throws Exception {
    var user = saveEmailUser(EMAIL, null);
    Cookie cookie = loginOk();
    String id = sessionIdOf(cookie);
    String indexKey = indexKey(PrincipalSessions.memberPrincipalName(user.getId()));
    assertThat(indexed(indexKey, id)).isTrue();

    var stored = (org.springframework.session.Session) sessionRepository.findById(id);
    stored.setMaxInactiveInterval(Duration.ofSeconds(2));
    sessionRepository.save(stored);

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(indexed(indexKey, id)).isFalse());
    assertThat(sessions.exists(id)).isFalse();
    mockMvc.perform(get("/api/auth/context").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test
  @Order(4)
  void Redis를_정상_종료_후_재기동해도_세션과_상태값과_TTL이_AOF로_복구된다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie cookie = loginOk();
    String id = sessionIdOf(cookie);
    oAuthStateRepository.save("synthetic-state-graceful", "synthetic-sid", Duration.ofMinutes(10));
    long before = ttl(SESSION_PREFIX + "sessions:expires:" + id);

    ProtectedRedisEnvironment.stopRedis();
    ProtectedRedisEnvironment.startRedis();

    awaitRedisUsable();
    assertThat(sessions.exists(id)).isTrue();
    assertThat(ttl(SESSION_PREFIX + "sessions:expires:" + id))
        .isBetween(before - 120_000, before + 1_000);
    mockMvc.perform(get("/api/auth/context").cookie(cookie)).andExpect(status().isOk());
    assertThat(oAuthStateRepository.consume("synthetic-state-graceful")).contains("synthetic-sid");
    assertThat(oAuthStateRepository.consume("synthetic-state-graceful")).isEmpty();
  }

  @Test
  @Order(5)
  void Redis가_강제_종료돼도_1초_뒤에는_AOF로_세션이_복구된다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie cookie = loginOk();
    String id = sessionIdOf(cookie);
    Thread.sleep(2_500);

    ProtectedRedisEnvironment.killRedis();
    ProtectedRedisEnvironment.startRedis();

    awaitRedisUsable();
    assertThat(sessions.exists(id)).isTrue();
    mockMvc.perform(get("/api/auth/context").cookie(cookie)).andExpect(status().isOk());
  }

  @Test
  @Order(6)
  void Redis_장애중에는_로그인과_세션요청이_실패로_닫히고_복구되면_재기동없이_회복된다(CapturedOutput output) throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie before = loginOk();

    ProtectedRedisEnvironment.stopRedis();

    long start = System.nanoTime();
    Outcome loginDuringOutage = outcome(() -> loginRequest(PASSWORD).andReturn());
    long loginMillis = (System.nanoTime() - start) / 1_000_000;
    start = System.nanoTime();
    Outcome sessionDuringOutage =
        outcome(() -> mockMvc.perform(get("/api/auth/context").cookie(before)).andReturn());
    long sessionMillis = (System.nanoTime() - start) / 1_000_000;
    Outcome healthDuringOutage =
        outcome(() -> mockMvc.perform(get("/actuator/health")).andReturn());
    Outcome liveness = outcome(() -> mockMvc.perform(get("/actuator/health/liveness")).andReturn());
    Outcome anonymous = outcome(() -> mockMvc.perform(get("/api/probe/staff")).andReturn());

    System.out.println(
        "[REDIS-VERIFY] outage login="
            + loginDuringOutage.status()
            + " in "
            + loginMillis
            + "ms, session="
            + sessionDuringOutage.status()
            + " in "
            + sessionMillis
            + "ms, health="
            + healthDuringOutage.status()
            + " "
            + healthDuringOutage.body()
            + ", liveness="
            + liveness.status()
            + ", anonymous="
            + anonymous.status());

    assertThat(loginDuringOutage.status()).isGreaterThanOrEqualTo(500);
    assertThat(loginDuringOutage.sessionCookie()).isNull();
    assertThat(sessionDuringOutage.status()).isNotEqualTo(200);
    assertThat(healthDuringOutage.status()).isEqualTo(503);
    assertThat(liveness.status()).isEqualTo(200);
    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(loginDuringOutage.body()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
    assertThat(loginMillis).isLessThan(5_000);
    assertThat(sessionMillis).isLessThan(5_000);

    ProtectedRedisEnvironment.startRedis();

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get("/api/auth/context").cookie(before))
                    .andExpect(status().isOk()));
    Cookie after = loginOk();
    assertThat(after).isNotNull();
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    assertThat(output.getAll()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
  }

  @Test
  @Order(8)
  void 복구후에도_키스페이스_구독이_되살아나_만료_색인이_정리된다() throws Exception {
    var user = saveEmailUser(EMAIL, null);
    ProtectedRedisEnvironment.stopRedis();
    ProtectedRedisEnvironment.startRedis();
    awaitRedisUsable();
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .until(
            () -> {
              try {
                loginOk();
                return true;
              } catch (AssertionError | Exception e) {
                return false;
              }
            });
    Cookie cookie = loginOk();
    String id = sessionIdOf(cookie);
    String indexKey = indexKey(PrincipalSessions.memberPrincipalName(user.getId()));
    var stored = (org.springframework.session.Session) sessionRepository.findById(id);
    stored.setMaxInactiveInterval(Duration.ofSeconds(2));
    sessionRepository.save(stored);

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(indexed(indexKey, id)).isFalse());
  }

  @Test
  @Order(7)
  void 운영중_Redis_비밀번호가_바뀌어_재연결이_거절되면_닫힌_채_유지되고_되돌리면_회복된다(CapturedOutput output) throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie before = loginOk();
    String rotated = "rotated-" + ProtectedRedisEnvironment.PASSWORD;

    ProtectedRedisEnvironment.cli("config", "set", "requirepass", rotated);
    ProtectedRedisEnvironment.cliAs(rotated, "client", "kill", "type", "normal");
    ProtectedRedisEnvironment.cliAs(rotated, "client", "kill", "type", "pubsub");

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(
                        outcome(
                                () ->
                                    mockMvc
                                        .perform(get("/api/auth/context").cookie(before))
                                        .andReturn())
                            .status())
                    .isNotEqualTo(200));
    Outcome login = outcome(() -> loginRequest(PASSWORD).andReturn());
    Outcome health = outcome(() -> mockMvc.perform(get("/actuator/health")).andReturn());
    System.out.println(
        "[REDIS-VERIFY] rotated-password login="
            + login.status()
            + " health="
            + health.status()
            + " "
            + health.body());
    assertThat(login.status()).isGreaterThanOrEqualTo(500);
    assertThat(login.sessionCookie()).isNull();
    assertThat(health.status()).isEqualTo(503);

    ProtectedRedisEnvironment.cliAs(
        rotated, "config", "set", "requirepass", ProtectedRedisEnvironment.PASSWORD);

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get("/api/auth/context").cookie(before))
                    .andExpect(status().isOk()));
    loginOk();
    assertThat(output.getAll()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
    assertThat(output.getAll()).doesNotContain(rotated);
  }

  @Test
  @Order(9)
  void 캡처한_로그에는_Redis_비밀번호가_나타나지_않는다(CapturedOutput output) {
    assertThat(output.getAll()).isNotBlank();
    assertThat(output.getAll()).doesNotContain(ProtectedRedisEnvironment.PASSWORD);
    assertThat(Instant.now()).isNotNull();
  }

  /** 서블릿 컨테이너였다면 500으로 바뀔 필터 단계 예외도 599로 받아 관찰한다. */
  private record Outcome(int status, String body, Cookie sessionCookie, Exception error) {}

  private interface Call {
    MvcResult run() throws Exception;
  }

  private static Outcome outcome(Call call) {
    try {
      MvcResult result = call.run();
      return new Outcome(
          result.getResponse().getStatus(),
          result.getResponse().getContentAsString(),
          AuthFlowSupport.sessionCookie(result),
          null);
    } catch (Exception e) {
      return new Outcome(599, String.valueOf(e), null, e);
    }
  }

  private boolean indexed(String key, String id) {
    Set<String> members = redisTemplate.opsForSet().members(key);
    return members != null && members.stream().anyMatch(member -> member.endsWith(id));
  }

  private void awaitRedisUsable() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(300))
        .until(
            () -> {
              try {
                redisTemplate.opsForValue().get("probe-ready");
                return true;
              } catch (RuntimeException e) {
                return false;
              }
            });
  }

  private static String indexKey(String principalName) {
    return SESSION_PREFIX
        + "index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:"
        + principalName;
  }
}
