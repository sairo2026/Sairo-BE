package com.sairo.be.perf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.PublicLinkTokenGenerator;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.SessionTestSupport;
import com.sairo.be.support.SpreadsheetFixtures;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@PerfTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class ScalePerformanceTest {

  private static final int ITERATIONS = intEnv("PERF_ITERATIONS", 30);
  private static final int BUDGET_SECONDS = intEnv("PERF_BUDGET_SECONDS", 45);
  private static final int IMPORT_ROWS = intEnv("PERF_IMPORT_ROWS", 10_000);
  private static final double LIST_P95_LIMIT_MS = 300;
  private static final double AGGREGATE_P95_LIMIT_MS = 1000;
  private static final long IMPORT_EXPORT_LIMIT_SECONDS = 300;
  private static final long HEAP_LIMIT_BYTES = 1024L * 1024 * 1024;

  private record Api(
      String name,
      String category,
      int expectedStatus,
      Function<Integer, MockHttpServletRequestBuilder> request) {}

  private record Fixture(
      long largeOffice,
      long midOffice,
      long largeFirstMember,
      long midFirstMember,
      long largeOwnerUser,
      long midAgentUser,
      long hotCoordination,
      int approverLinks) {}

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired SessionRepository sessionRepository;
  @Autowired PublicLinkTokenGenerator tokenGenerator;
  @Autowired InMemoryObjectStorage objectStorage;

  private static Fixture fixture;
  private static Cookie ownerLarge;
  private static Cookie deputyLarge;
  private static Cookie ownerMid;
  private static Cookie agentMid;
  private static int failures;

  private static int intEnv(String name, int fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private void guard(String phase, ThrowingRunnable body) {
    if (!phase.equals("seed") && fixture == null) {
      PerfReport.line("구간 `" + phase + "`은 시드 실패로 건너뜀");
      return;
    }
    try {
      body.run();
    } catch (Throwable e) {
      failures++;
      PerfReport.line(
          "\n구간 `" + phase + "` 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
      e.printStackTrace();
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  @Order(1)
  void seed() {
    guard(
        "seed",
        () -> {
          PerfReport.section("시험 환경");
          PerfReport.line(
              "- 적용된 마이그레이션 최대 버전: "
                  + jdbc.queryForObject(
                      "SELECT max(version::int) FROM flyway_schema_history WHERE success",
                      String.class));
          PerfReport.line("- JVM 힙 한도: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "MB");
          PerfReport.line("- 반복 횟수 상한 " + ITERATIONS + "회, 시나리오당 시간 상한 " + BUDGET_SECONDS + "초");
          boolean seeded =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT to_regclass('public.perf_seed_office') IS NOT NULL", Boolean.class));
          long start = System.nanoTime();
          if (!seeded) {
            String sql =
                new String(
                        new org.springframework.core.io.ClassPathResource("perf/seed.sql")
                            .getInputStream()
                            .readAllBytes(),
                        StandardCharsets.UTF_8)
                    .replace("@@LARGE_PROPERTIES@@", env("PERF_LARGE_PROPERTIES", "100000"))
                    .replace("@@LARGE_CUSTOMERS@@", env("PERF_LARGE_CUSTOMERS", "100000"))
                    .replace("@@MID_OFFICES@@", env("PERF_MID_OFFICES", "10"))
                    .replace("@@MID_PROPERTIES@@", env("PERF_MID_PROPERTIES", "10000"))
                    .replace("@@MID_CUSTOMERS@@", env("PERF_MID_CUSTOMERS", "10000"))
                    .replace("@@APPROVER_LINKS@@", env("PERF_APPROVER_LINKS", "60"))
                    .replace("@@REQUESTS_PER_LINK@@", env("PERF_REQUESTS_PER_LINK", "30"));
            try (var connection = dataSource.getConnection()) {
              connection.setAutoCommit(false);
              try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL statement_timeout = 0");
              }
              try {
                ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                        new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), "UTF-8"));
                connection.commit();
              } catch (RuntimeException e) {
                connection.rollback();
                throw e;
              }
            }
          }
          long seedMillis = (System.nanoTime() - start) / 1_000_000;
          createApproverLinks();
          try (var connection = dataSource.getConnection();
              var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("SET statement_timeout = 0");
            statement.execute("VACUUM ANALYZE");
            statement.execute("RESET statement_timeout");
          }
          PerfReport.line(
              seeded ? "- 시드는 이미 적용되어 있어 건너뜀" : "- 시드 생성 시간: " + seedMillis / 1000 + "초");
          PerfReport.line("\n| 표 | 행 수 | 크기 |\n|---|---|---|");
          for (String table :
              List.of(
                  "property",
                  "property_offer",
                  "property_owner_occupant",
                  "customer",
                  "customer_merge_log",
                  "customer_consultation",
                  "entity_lifecycle_event",
                  "visit_coordination",
                  "visit_schedule_item",
                  "visit_request",
                  "contract",
                  "contract_fee_transaction",
                  "task")) {
            PerfReport.line(
                "| "
                    + table
                    + " | "
                    + jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)
                    + " | "
                    + jdbc.queryForObject(
                        "SELECT pg_size_pretty(pg_total_relation_size('" + table + "'))",
                        String.class)
                    + " |");
          }
          loadFixture();
        });
  }

  private void createApproverLinks() {
    List<Long> approvals =
        jdbc.queryForList(
            "SELECT a.id FROM property_access_approval a JOIN perf_seed_hot h ON h.property_id ="
                + " a.property_id ORDER BY h.idx",
            Long.class);
    long member =
        jdbc.queryForObject(
            "SELECT first_member_id FROM perf_seed_office WHERE kind = 'LARGE'", Long.class);
    int index = 0;
    for (Long approval : approvals) {
      index++;
      Integer existing =
          jdbc.queryForObject(
              "SELECT count(*) FROM visit_approver_property_link WHERE approval_id = ?",
              Integer.class,
              approval);
      if (existing != null && existing > 0) {
        continue;
      }
      jdbc.update(
          "INSERT INTO visit_approver_property_link (approval_id, token_hash,"
              + " issued_by_membership_id, expires_at, retention_until) VALUES (?, ?, ?, now() +"
              + " interval '30 days', current_date + 365)",
          approval,
          tokenGenerator.hash("perf-approver-token-" + index),
          member);
    }
  }

  private void loadFixture() {
    Map<String, Object> large =
        jdbc.queryForMap(
            "SELECT office_id, first_member_id FROM perf_seed_office WHERE kind = 'LARGE'");
    Map<String, Object> mid =
        jdbc.queryForMap(
            "SELECT office_id, first_member_id FROM perf_seed_office WHERE kind = 'MID' ORDER BY"
                + " office_id LIMIT 1");
    long largeOffice = ((Number) large.get("office_id")).longValue();
    long midOffice = ((Number) mid.get("office_id")).longValue();
    long largeFirst = ((Number) large.get("first_member_id")).longValue();
    long midFirst = ((Number) mid.get("first_member_id")).longValue();
    long ownerUser =
        jdbc.queryForObject(
            "SELECT user_id FROM office_membership WHERE id = ?", Long.class, largeFirst);
    long midAgentUser =
        jdbc.queryForObject(
            "SELECT user_id FROM office_membership WHERE id = ?", Long.class, midFirst + 1);
    long deputyUser =
        jdbc.queryForObject(
            "SELECT user_id FROM office_membership WHERE id = ?", Long.class, largeFirst + 4);
    long midOwnerUser =
        jdbc.queryForObject(
            "SELECT user_id FROM office_membership WHERE id = ?", Long.class, midFirst);
    Long hot =
        jdbc.queryForObject(
            "SELECT coordination_id FROM perf_seed_hot_coordination LIMIT 1", Long.class);
    int links =
        jdbc.queryForObject("SELECT count(*) FROM visit_approver_property_link", Integer.class);
    fixture =
        new Fixture(
            largeOffice,
            midOffice,
            largeFirst,
            midFirst,
            ownerUser,
            midAgentUser,
            hot == null ? -1 : hot,
            links);
    SessionTestSupport sessions = new SessionTestSupport(sessionRepository);
    ownerLarge = cookie(sessions, ownerUser, largeFirst, largeOffice, StaffRole.OWNER);
    deputyLarge = cookie(sessions, deputyUser, largeFirst + 4, largeOffice, StaffRole.DEPUTY);
    ownerMid = cookie(sessions, midOwnerUser, midFirst, midOffice, StaffRole.OWNER);
    agentMid = cookie(sessions, midAgentUser, midFirst + 1, midOffice, StaffRole.AGENT);
  }

  private static Cookie cookie(
      SessionTestSupport sessions, long userId, long membershipId, long officeId, StaffRole role) {
    StaffPrincipal principal = new StaffPrincipal(userId, membershipId, officeId, "시험회원", role);
    return sessions.cookie(sessions.createMemberSession(principal, Instant.now(), Instant.now()));
  }

  private static MockHttpServletRequestBuilder as(String url, Cookie cookie) {
    return get(url).cookie(cookie);
  }

  private static RequestPostProcessor remote(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  private List<Api> apis() {
    YearMonth now = YearMonth.now(ZoneId.of("Asia/Seoul"));
    List<Api> list = new ArrayList<>();
    String L = "LIST";
    String A = "AGGREGATE";
    list.add(new Api("property.list.default", L, 200, i -> as("/api/properties", ownerLarge)));
    list.add(
        new Api("property.list.size100", L, 200, i -> as("/api/properties?size=100", ownerLarge)));
    list.add(
        new Api("property.list.page40", L, 200, i -> as("/api/properties?page=40", ownerLarge)));
    list.add(
        new Api(
            "property.list.page3000", L, 200, i -> as("/api/properties?page=3000", ownerLarge)));
    list.add(
        new Api(
            "property.list.keyword-name",
            L,
            200,
            i ->
                as(
                    "/api/properties?keyword="
                        + enc("매물 " + fixture.largeOffice + "-" + (4000 + i)),
                    ownerLarge)));
    list.add(
        new Api(
            "property.list.keyword-address",
            L,
            200,
            i -> as("/api/properties?keyword=" + enc("로 " + (100 + i) + "번지"), ownerLarge)));
    list.add(
        new Api(
            "property.list.keyword-owner",
            L,
            200,
            i -> as("/api/properties?keyword=" + enc("가상고객 " + (100 + i)), ownerLarge)));
    list.add(
        new Api(
            "property.list.deal-price",
            L,
            200,
            i ->
                as(
                    "/api/properties?dealType=JEONSE&priceMin=100000000&priceMax=1000000000",
                    ownerLarge)));
    list.add(
        new Api(
            "property.list.area",
            L,
            200,
            i -> as("/api/properties?areaType=EXCLUSIVE&areaMin=30&areaMax=60", ownerLarge)));
    list.add(
        new Api(
            "property.list.manager",
            L,
            200,
            i ->
                as(
                    "/api/properties?managingMembershipId=" + (fixture.largeFirstMember + 1),
                    ownerLarge)));
    list.add(
        new Api(
            "property.list.draft",
            L,
            200,
            i -> as("/api/properties?registrationStage=DRAFT", ownerLarge)));
    list.add(
        new Api(
            "property.list.archived",
            L,
            200,
            i -> as("/api/properties?archived=true", ownerLarge)));
    list.add(
        new Api(
            "property.duplicate-check",
            L,
            200,
            i ->
                as(
                    "/api/properties/duplicate-check?address="
                        + enc("가상시 가상구 가상로 " + (500 + i) + "번지"),
                    ownerLarge)));
    list.add(new Api("customer.list.default", L, 200, i -> as("/api/customers", ownerLarge)));
    list.add(
        new Api("customer.list.page3000", L, 200, i -> as("/api/customers?page=3000", ownerLarge)));
    list.add(
        new Api(
            "customer.list.keyword-name",
            L,
            200,
            i -> as("/api/customers?keyword=" + enc("가상고객 " + (100 + i)), ownerLarge)));
    list.add(
        new Api(
            "customer.list.keyword-phone",
            L,
            200,
            i ->
                as(
                    "/api/customers?keyword=" + enc("000-0001-" + String.format("%04d", 100 + i)),
                    ownerLarge)));
    list.add(
        new Api(
            "customer.list.role-owner", L, 200, i -> as("/api/customers?role=OWNER", ownerLarge)));
    list.add(
        new Api(
            "customer.list.role-demand",
            L,
            200,
            i -> as("/api/customers?role=DEMAND", ownerLarge)));
    list.add(
        new Api(
            "customer.list.role-other", L, 200, i -> as("/api/customers?role=OTHER", ownerLarge)));
    list.add(
        new Api(
            "customer.list.archived", L, 200, i -> as("/api/customers?archived=true", ownerLarge)));
    list.add(
        new Api(
            "customer.duplicate-candidates",
            L,
            200,
            i ->
                as(
                    "/api/customers/duplicate-candidates?name="
                        + enc("가상고객 " + (200 + i))
                        + "&phone="
                        + enc("000-0000-" + String.format("%04d", 200 + i)),
                    ownerLarge)));
    list.add(new Api("home.owner", L, 200, i -> as("/api/home", ownerLarge)));
    list.add(new Api("home.deputy", L, 200, i -> as("/api/home", deputyLarge)));
    list.add(new Api("settlement.list", A, 200, i -> as("/api/settlements", ownerLarge)));
    list.add(new Api("settlement.current", A, 200, i -> as("/api/settlements/" + now, ownerLarge)));
    list.add(
        new Api(
            "settlement.prev1",
            A,
            200,
            i -> as("/api/settlements/" + now.minusMonths(1), ownerLarge)));
    list.add(
        new Api(
            "settlement.prev6",
            A,
            200,
            i -> as("/api/settlements/" + now.minusMonths(6), ownerLarge)));
    list.add(
        new Api(
            "settlement.details.contract",
            A,
            200,
            i ->
                as(
                    "/api/settlements/"
                        + now.minusMonths(2)
                        + "/details?metric=contractSignedCount",
                    ownerLarge)));
    list.add(
        new Api(
            "settlement.details.property",
            A,
            200,
            i -> as("/api/settlements/" + now + "/details?metric=propertyCountTotal", ownerLarge)));
    list.add(new Api("visit.list", L, 200, i -> as("/api/visit-coordinations", ownerLarge)));
    list.add(
        new Api(
            "visit.list.confirmed",
            L,
            200,
            i -> as("/api/visit-coordinations?status=CONFIRMED", ownerLarge)));
    list.add(
        new Api(
            "visit.conflicts",
            L,
            200,
            i ->
                as(
                    "/api/visit-coordinations/" + fixture.hotCoordination + "/schedule/conflicts",
                    ownerLarge)));
    list.add(
        new Api(
            "approver.requests",
            L,
            200,
            i ->
                get("/api/public/approver-property-links/perf-approver-token-"
                        + (1 + i % Math.max(1, fixture.approverLinks))
                        + "/visit-requests")
                    .with(remote("10.20." + (i / 250) + "." + (i % 250)))));
    list.add(new Api("mid.property.list", L, 200, i -> as("/api/properties", ownerMid)));
    list.add(
        new Api(
            "mid.property.keyword",
            L,
            200,
            i ->
                as(
                    "/api/properties?keyword=" + enc("매물 " + fixture.midOffice + "-" + (400 + i)),
                    ownerMid)));
    list.add(new Api("mid.customer.list", L, 200, i -> as("/api/customers", agentMid)));
    list.add(
        new Api(
            "mid.customer.keyword",
            L,
            200,
            i -> as("/api/customers?keyword=" + enc("가상고객 " + (100 + i)), agentMid)));
    list.add(new Api("mid.home", L, 200, i -> as("/api/home", ownerMid)));
    list.add(
        new Api("mid.customer.role-owner", L, 200, i -> as("/api/customers?role=OWNER", agentMid)));
    list.add(new Api("mid.settlement.list", A, 200, i -> as("/api/settlements", ownerMid)));
    list.add(
        new Api("mid.settlement.current", A, 200, i -> as("/api/settlements/" + now, ownerMid)));
    list.add(
        new Api(
            "mid.settlement.prev6",
            A,
            200,
            i -> as("/api/settlements/" + now.minusMonths(6), ownerMid)));
    return list;
  }

  private static String enc(String value) {
    return value;
  }

  @Test
  @Order(2)
  void apiLatency() {
    guard(
        "apiLatency",
        () -> {
          PerfReport.section("API 응답시간");
          PerfReport.line(
              "| 시나리오 | 표본 | p50(ms) | p95(ms) | 최대(ms) | 요청당 SQL | 오류 | 기준 | 판정 |\n"
                  + "|---|---|---|---|---|---|---|---|---|");
          for (Api api : apis()) {
            guard(api.name(), () -> measure(api));
          }
        });
  }

  private void measure(Api api) throws Exception {
    for (int i = 0; i < 2; i++) {
      mockMvc.perform(api.request().apply(-1 - i)).andReturn();
    }
    SqlRecorder.Scenario scenario = SqlRecorder.begin(api.name());
    List<Double> samples = new ArrayList<>();
    int errors = 0;
    long budgetEnd = System.nanoTime() + BUDGET_SECONDS * 1_000_000_000L;
    for (int i = 0; i < ITERATIONS; i++) {
      long start = System.nanoTime();
      MvcResult result = mockMvc.perform(api.request().apply(i)).andReturn();
      samples.add((System.nanoTime() - start) / 1_000_000.0);
      if (result.getResponse().getStatus() != api.expectedStatus()) {
        errors++;
        if (errors == 1) {
          PerfReport.line(
              "\n> `"
                  + api.name()
                  + "` 응답 상태 "
                  + result.getResponse().getStatus()
                  + ": "
                  + abbreviate(result.getResponse().getContentAsString())
                  + "\n");
        }
      }
      if (i >= 2 && System.nanoTime() > budgetEnd) {
        break;
      }
    }
    SqlRecorder.end();
    Collections.sort(samples);
    double p50 = percentile(samples, 0.50);
    double p95 = percentile(samples, 0.95);
    double limit = api.category().equals("AGGREGATE") ? AGGREGATE_P95_LIMIT_MS : LIST_P95_LIMIT_MS;
    String verdict = errors > 0 ? "오류" : p95 <= limit ? "통과" : "초과";
    double sqlPerRequest = (double) scenario.statementCount() / samples.size();
    PerfReport.line(
        String.format(
            "| %s | %d | %.1f | %.1f | %.1f | %.1f | %d | %.0f | %s |",
            api.name(),
            samples.size(),
            p50,
            p95,
            samples.get(samples.size() - 1),
            sqlPerRequest,
            errors,
            limit,
            verdict));
    PerfReport.csv(
        String.format(
            "%s,%d,%.1f,%.1f,%.1f,%.1f,%d",
            api.name(),
            samples.size(),
            p50,
            p95,
            samples.get(samples.size() - 1),
            sqlPerRequest,
            errors));
    explainTop(scenario, samples.size());
  }

  private static String abbreviate(String text) {
    String value = text == null ? "" : text.replaceAll("\\s+", " ");
    return value.length() > 300 ? value.substring(0, 300) : value;
  }

  private static double percentile(List<Double> sorted, double fraction) {
    int index = (int) Math.ceil(fraction * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private void explainTop(SqlRecorder.Scenario scenario, int requests) {
    int rank = 0;
    for (SqlRecorder.Stat stat : scenario.byTotalTime()) {
      if (rank >= 3) {
        break;
      }
      double perRequest = stat.totalMillis() / Math.max(1, requests);
      if (perRequest < 8 || !SqlRecorder.explainable(stat.sql)) {
        continue;
      }
      rank++;
      List<String> plan = SqlRecorder.explain(dataSource, stat);
      String file = scenario.name + "-" + rank + ".txt";
      PerfReport.plan(
          file,
          "-- 요청당 평균 "
              + String.format("%.1f", perRequest)
              + "ms, 호출 "
              + stat.count
              + "회\n"
              + stat.sql
              + "\n\n"
              + String.join("\n", plan));
      PerfReport.line(
          String.format(
              "> `%s` SQL %d: 요청당 %.1fms, 실행계획 실행 %sms, 순차 스캔 %s (plans/%s)",
              scenario.name,
              rank,
              perRequest,
              PerfReport.executionMillis(plan),
              PerfReport.seqScans(plan),
              file));
    }
  }

  @Test
  @Order(3)
  void concurrentMix() {
    guard(
        "concurrentMix",
        () -> {
          PerfReport.section("여러 사무소 동시 요청");
          List<Cookie> cookies = new ArrayList<>();
          List<Long> offices =
              jdbc.queryForList(
                  "SELECT office_id FROM perf_seed_office ORDER BY office_id LIMIT 8", Long.class);
          SessionTestSupport sessions = new SessionTestSupport(sessionRepository);
          for (Long office : offices) {
            long first =
                jdbc.queryForObject(
                    "SELECT first_member_id FROM perf_seed_office WHERE office_id = ?",
                    Long.class,
                    office);
            long user =
                jdbc.queryForObject(
                    "SELECT user_id FROM office_membership WHERE id = ?", Long.class, first);
            cookies.add(cookie(sessions, user, first, office, StaffRole.OWNER));
          }
          List<String> urls =
              List.of(
                  "/api/properties",
                  "/api/customers",
                  "/api/home",
                  "/api/properties?keyword=" + enc("가상매물 1"),
                  "/api/customers?keyword=" + enc("가상고객 12"));
          int perThread = Math.max(10, ITERATIONS);
          ExecutorService pool = Executors.newFixedThreadPool(cookies.size());
          List<Future<List<Double>>> futures = new ArrayList<>();
          long wall = System.nanoTime();
          for (Cookie cookie : cookies) {
            futures.add(
                pool.submit(
                    () -> {
                      List<Double> own = new ArrayList<>();
                      for (int i = 0; i < perThread; i++) {
                        long start = System.nanoTime();
                        MvcResult result =
                            mockMvc.perform(as(urls.get(i % urls.size()), cookie)).andReturn();
                        if (result.getResponse().getStatus() != 200) {
                          own.add(-1.0);
                        } else {
                          own.add((System.nanoTime() - start) / 1_000_000.0);
                        }
                      }
                      return own;
                    }));
          }
          List<Double> all = new ArrayList<>();
          int errors = 0;
          for (Future<List<Double>> future : futures) {
            for (Double value : future.get()) {
              if (value < 0) {
                errors++;
              } else {
                all.add(value);
              }
            }
          }
          pool.shutdown();
          Collections.sort(all);
          PerfReport.line(
              String.format(
                  "- 사무소 %d개가 각 %d회 혼합 요청, 전체 %.1f초, p50 %.1fms, p95 %.1fms, 최대 %.1fms, 오류 %d",
                  cookies.size(),
                  perThread,
                  (System.nanoTime() - wall) / 1e9,
                  percentile(all, 0.5),
                  percentile(all, 0.95),
                  all.get(all.size() - 1),
                  errors));
        });
  }

  private static Cookie xsrf() {
    return new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
  }

  private MvcResult upload(Cookie cookie, String fileName, byte[] content, String targetType)
      throws Exception {
    return mockMvc
        .perform(
            multipart("/api/import-batches")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                .param("targetType", targetType)
                .cookie(cookie, xsrf())
                .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
        .andReturn();
  }

  private long version(long batchId) {
    return jdbc.queryForObject(
        "SELECT version FROM import_batch WHERE id = ?", Long.class, batchId);
  }

  private static String customerMapping() {
    return "{\"sourceSystem\":\"시험이전\",\"mappings\":["
        + "{\"sourceColumn\":\"고객번호\",\"targetField\":\"customerSourceId\"},"
        + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"},"
        + "{\"sourceColumn\":\"연락처\",\"targetField\":\"customerPhone\"},"
        + "{\"sourceColumn\":\"이메일\",\"targetField\":\"customerEmail\"},"
        + "{\"sourceColumn\":\"메모\",\"targetField\":\"customerMemo\"}]}";
  }

  private static String propertyMapping() {
    return "{\"sourceSystem\":\"시험이전\",\"mappings\":["
        + "{\"sourceColumn\":\"매물번호\",\"targetField\":\"propertySourceId\"},"
        + "{\"sourceColumn\":\"매물명\",\"targetField\":\"propertyName\"},"
        + "{\"sourceColumn\":\"주소\",\"targetField\":\"propertyAddress\"},"
        + "{\"sourceColumn\":\"상세주소\",\"targetField\":\"propertyAddressDetail\"},"
        + "{\"sourceColumn\":\"전용면적(평)\",\"targetField\":\"exclusiveAreaSqm\"}]}";
  }

  private List<List<Object>> customerRows(String prefix, int from, int count) {
    List<List<Object>> rows = new ArrayList<>();
    rows.add(SpreadsheetFixtures.row("고객번호", "성명", "연락처", "이메일", "메모"));
    for (int i = from; i < from + count; i++) {
      rows.add(
          SpreadsheetFixtures.row(
              prefix + i,
              "가상고객 " + (i % 20000),
              "000-"
                  + String.format("%04d", 5000 + i / 10000)
                  + "-"
                  + String.format("%04d", i % 10000),
              "",
              ""));
    }
    return rows;
  }

  private List<List<Object>> propertyRows(String prefix, int count) {
    List<List<Object>> rows = new ArrayList<>();
    rows.add(SpreadsheetFixtures.row("매물번호", "매물명", "주소", "상세주소", "전용면적(평)"));
    for (int i = 0; i < count; i++) {
      rows.add(
          SpreadsheetFixtures.row(
              prefix + i,
              "이전매물 " + i,
              "가상시 가상구 가상로 " + (1 + i * 3 % 60000) + "번지",
              (i % 9 + 1) + "0" + (i % 5 + 1) + "호",
              20 + i % 40));
    }
    return rows;
  }

  private byte[] csv(List<List<Object>> rows) {
    StringBuilder builder = new StringBuilder();
    for (List<Object> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        builder.append(i == 0 ? "" : ",").append(row.get(i));
      }
      builder.append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @Order(4)
  void importMigration() {
    guard(
        "importMigration",
        () -> {
          PerfReport.section("데이터 이전");
          PerfReport.line(
              "한 시트의 행 수 상한은 10,000행이고 월 실행 한도는 5회이므로 한 번의 이전은 최대 10,000행이다."
                  + " 아래 시험 행 수는 "
                  + IMPORT_ROWS
                  + "행이다. 실행은 요청 스레드에서 동기로 처리한다.\n");
          PerfReport.line("| 단계 | 시간(ms) | 힙 | 비고 |\n|---|---|---|---|");
          jdbc.update(
              "UPDATE import_batch SET executed_at = executed_at - interval '40 days',"
                  + " cancellable_until = cancellable_until - interval '40 days' WHERE office_id ="
                  + " ? AND executed_at IS NOT NULL",
              fixture.largeOffice);
          Cookie owner = ownerLarge;
          int half = IMPORT_ROWS / 2;
          runImport(
              "customer-csv",
              owner,
              "고객A.csv",
              csv(customerRows("IMP-A-", 0, IMPORT_ROWS)),
              "CUSTOMER",
              customerMapping(),
              IMPORT_ROWS);
          runImport(
              "customer-xlsx",
              owner,
              "고객B.xlsx",
              SpreadsheetFixtures.xlsx(
                  SpreadsheetFixtures.sheets(
                      "Sheet1",
                      concat(
                          customerRows("IMP-A-", 0, half),
                          customerRows("IMP-B-", IMPORT_ROWS, IMPORT_ROWS - half)))),
              "CUSTOMER",
              customerMapping(),
              IMPORT_ROWS - half);
          runImport(
              "property-xls",
              owner,
              "매물C.xls",
              SpreadsheetFixtures.xls(
                  SpreadsheetFixtures.sheets("Sheet1", propertyRows("IMP-P-", IMPORT_ROWS))),
              "PROPERTY",
              propertyMapping(),
              IMPORT_ROWS);
        });
  }

  private static List<List<Object>> concat(List<List<Object>> first, List<List<Object>> second) {
    List<List<Object>> rows = new ArrayList<>(first);
    rows.addAll(second.subList(1, second.size()));
    return rows;
  }

  private void runImport(
      String name,
      Cookie cookie,
      String fileName,
      byte[] content,
      String type,
      String mapping,
      int expectedSucceeded)
      throws Exception {
    long[] batch = new long[1];
    timedStage(
        name + ".upload",
        "파일 " + content.length / 1024 + "KB",
        () -> {
          MvcResult result = upload(cookie, fileName, content, type);
          require(result, 200);
          batch[0] = AuthFlowSupport.json(result).get("batchId").longValue();
        });
    timedStage(
        name + ".mapping-suggestion",
        "",
        () ->
            require(
                mockMvc
                    .perform(as("/api/import-batches/" + batch[0] + "/mapping-suggestion", cookie))
                    .andReturn(),
                200));
    timedStage(
        name + ".confirm-mapping",
        "",
        () ->
            require(
                mockMvc
                    .perform(
                        AuthFlowSupport.withCsrf(
                            put("/api/import-batches/" + batch[0] + "/mapping")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapping)
                                .header("If-Match", String.valueOf(version(batch[0])))
                                .cookie(cookie)))
                    .andReturn(),
                200));
    timedStage(
        name + ".preview-page0",
        "",
        () ->
            require(
                mockMvc
                    .perform(
                        as("/api/import-batches/" + batch[0] + "/preview?page=0&size=100", cookie))
                    .andReturn(),
                200));
    timedStage(
        name + ".preview-last",
        "",
        () ->
            require(
                mockMvc
                    .perform(
                        as(
                            "/api/import-batches/"
                                + batch[0]
                                + "/preview?page="
                                + (IMPORT_ROWS / 100 - 1)
                                + "&size=100",
                            cookie))
                    .andReturn(),
                200));
    timedStage(
        name + ".execute",
        "",
        () -> {
          require(
              mockMvc
                  .perform(
                      AuthFlowSupport.withCsrf(
                          post("/api/import-batches/" + batch[0] + "/execute")
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("")
                              .cookie(cookie)))
                  .andReturn(),
              200);
          Map<String, Object> row =
              jdbc.queryForMap(
                  "SELECT status, succeeded_count, failed_count FROM import_batch WHERE id = ?",
                  batch[0]);
          long skipped =
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM import_row_result WHERE batch_id = ? AND result ="
                      + " 'SKIPPED'",
                  Long.class,
                  batch[0]);
          PerfReport.line(
              "> `"
                  + name
                  + "` 실행 결과: 상태 "
                  + row.get("status")
                  + ", 성공 "
                  + row.get("succeeded_count")
                  + ", 실패 "
                  + row.get("failed_count")
                  + ", 제외 "
                  + skipped);
          if (!"COMPLETED".equals(row.get("status"))
              || !Integer.valueOf(expectedSucceeded).equals(row.get("succeeded_count"))
              || !Integer.valueOf(0).equals(row.get("failed_count"))
              || skipped != IMPORT_ROWS - expectedSucceeded) {
            throw new IllegalStateException("이전 결과가 준비한 신규·중복 행 구성과 다릅니다.");
          }
        });
  }

  private static void require(MvcResult result, int status) throws Exception {
    if (result.getResponse().getStatus() != status) {
      throw new IllegalStateException(
          "응답 상태 "
              + result.getResponse().getStatus()
              + ": "
              + abbreviate(result.getResponse().getContentAsString()));
    }
  }

  private void timedStage(String name, String note, ThrowingRunnable body) throws Exception {
    SqlRecorder.Scenario scenario = SqlRecorder.begin(name);
    long start = System.nanoTime();
    HeapSampler.Peak peak;
    try (HeapSampler sampler = new HeapSampler()) {
      try {
        body.run();
      } finally {
        peak = sampler.stop();
        SqlRecorder.end();
      }
    }
    long millis = (System.nanoTime() - start) / 1_000_000;
    PerfReport.line("| " + name + " | " + millis + " | " + peak.describe() + " | " + note + " |");
    if (millis > IMPORT_EXPORT_LIMIT_SECONDS * 1000 || peak.sampledUsedBytes() > HEAP_LIMIT_BYTES) {
      PerfReport.line("> `" + name + "` 기준 초과");
    }
    List<SqlRecorder.Stat> stats = scenario.byTotalTime();
    int rank = 0;
    for (SqlRecorder.Stat stat : stats) {
      if (rank >= 3 || stat.totalMillis() < 200) {
        break;
      }
      rank++;
      String file = name + "-" + rank + ".txt";
      String plan = "";
      if (SqlRecorder.explainable(stat.sql)) {
        List<String> lines = SqlRecorder.explain(dataSource, stat);
        plan = "\n\n" + String.join("\n", lines);
        PerfReport.plan(
            file,
            "-- 호출 "
                + stat.count
                + "회, 합계 "
                + String.format("%.0f", stat.totalMillis())
                + "ms\n"
                + stat.sql
                + plan);
        PerfReport.line(
            "> `"
                + name
                + "` SQL "
                + rank
                + ": "
                + stat.count
                + "회 합계 "
                + String.format("%.0f", stat.totalMillis())
                + "ms, 호출당 최대 "
                + String.format("%.1f", stat.maxMillis())
                + "ms, 실행계획 실행 "
                + PerfReport.executionMillis(lines)
                + "ms, 순차 스캔 "
                + PerfReport.seqScans(lines));
      } else {
        PerfReport.line(
            "> `"
                + name
                + "` SQL "
                + rank
                + ": "
                + stat.count
                + "회 합계 "
                + String.format("%.0f", stat.totalMillis())
                + "ms (쓰기 문장) "
                + abbreviateSql(stat.sql));
      }
    }
  }

  private static String abbreviateSql(String sql) {
    String text = sql.replaceAll("\\s+", " ");
    return "`" + (text.length() > 110 ? text.substring(0, 110) : text) + "`";
  }

  @Test
  @Order(5)
  void exportMigration() {
    guard(
        "exportMigration",
        () -> {
          PerfReport.section("데이터 내보내기");
          PerfReport.line("| 단계 | 시간(ms) | 힙 | 비고 |\n|---|---|---|---|");
          String body =
              "{\"scope\":\"TABLES\",\"tables\":[\"PROPERTY\",\"CUSTOMER\",\"CONTRACT\",\"FEE\",\"PAYMENT\",\"TASK\",\"VISIT\"]}";
          exportOnce("export-large-office", ownerLarge, body, fixture.largeOffice);
          exportOnce("export-mid-office", ownerMid, body, fixture.midOffice);
        });
  }

  private void exportOnce(String name, Cookie cookie, String body, long officeId) throws Exception {
    long[] id = new long[1];
    timedStage(
        name,
        "",
        () -> {
          MvcResult result =
              mockMvc
                  .perform(
                      AuthFlowSupport.withCsrf(
                          post("/api/exports")
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body)
                              .cookie(cookie)))
                  .andReturn();
          require(result, 201);
          id[0] = AuthFlowSupport.json(result).get("exportId").longValue();
        });
    Map<String, Object> job =
        jdbc.queryForMap("SELECT status, file_object_key FROM export_job WHERE id = ?", id[0]);
    String key = (String) job.get("file_object_key");
    long size =
        key == null ? -1 : objectStorage.find(key).map(bytes -> (long) bytes.length).orElse(-1L);
    PerfReport.line(
        "> `"
            + name
            + "` 사무소 "
            + officeId
            + " 결과: 상태 "
            + job.get("status")
            + ", 압축 파일 "
            + size / 1024
            + "KB");
  }

  @Test
  @Order(6)
  void finish() {
    PerfReport.section("실패한 구간");
    PerfReport.line(failures == 0 ? "- 없음" : "- 실패 구간 " + failures + "개, 위 본문 참고");
    if ("true".equalsIgnoreCase(System.getenv("PERF_ENFORCE")) && failures > 0) {
      throw new AssertionError("성능 시험에 실패한 구간이 있다.");
    }
  }
}
