package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.scheduling.annotation.Scheduled;

@IntegrationTest
class SchedulerLockIntegrationTest {

  private static final String LOCK_NAME = "scheduler-lock-probe";

  public static class ProbeJob {

    private final AtomicInteger runs = new AtomicInteger();

    @Scheduled(fixedDelayString = "PT0.1S")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
    public void run() {
      runs.incrementAndGet();
    }

    public int runs() {
      return runs.get();
    }
  }

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void clearLock() {
    jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", LOCK_NAME);
  }

  private AnnotationConfigApplicationContext batchContext(String batchEnabled) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("batch", Map.of("sairo.batch.enabled", batchEnabled)));
    context.registerBean(DataSource.class, () -> new DelegatingDataSource(dataSource));
    context.register(BatchConfig.class, ProbeJob.class);
    return context;
  }

  private static void waitUntilRun(ProbeJob job) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (job.runs() == 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
  }

  private long lockRows() {
    Long value =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM shedlock WHERE name = ? AND locked_by IS NOT NULL",
            Long.class,
            LOCK_NAME);
    return value == null ? 0 : value;
  }

  @Test
  void 다른_인스턴스가_잠금을_쥐고_있으면_예약_배치를_건너뛰고_풀리면_실행한다() throws Exception {
    LockProvider otherInstance = new BatchConfig().lockProvider(dataSource);
    SimpleLock held =
        otherInstance
            .lock(
                new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO))
            .orElseThrow();
    assertThat(lockRows()).isEqualTo(1);

    try (AnnotationConfigApplicationContext context = batchContext("true")) {
      context.refresh();
      ProbeJob job = context.getBean(ProbeJob.class);

      Thread.sleep(800);
      assertThat(job.runs()).isZero();

      held.unlock();
      waitUntilRun(job);
      assertThat(job.runs()).isPositive();
      assertThat(lockRows()).isEqualTo(1);
    }
  }

  @Test
  void 같은_이름의_잠금은_한_번에_하나만_잡힌다() throws InterruptedException {
    LockProvider first = new BatchConfig().lockProvider(dataSource);
    LockProvider second = new BatchConfig().lockProvider(dataSource);
    LockConfiguration configuration =
        new LockConfiguration(Instant.now(), LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO);

    SimpleLock held = first.lock(configuration).orElseThrow();
    assertThat(second.lock(configuration)).isEmpty();
    held.unlock();
    SimpleLock reacquired = null;
    Instant deadline = Instant.now().plusSeconds(5);
    while (reacquired == null && Instant.now().isBefore(deadline)) {
      reacquired =
          second
              .lock(
                  new LockConfiguration(
                      Instant.now(), LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO))
              .orElse(null);
      if (reacquired == null) {
        Thread.sleep(25);
      }
    }
    assertThat(reacquired).isNotNull();
    reacquired.unlock();
  }

  @Test
  void 배치를_켜지_않으면_잠금_설정과_예약_실행을_만들지_않는다() throws Exception {
    try (AnnotationConfigApplicationContext context = batchContext("false")) {
      context.refresh();

      assertThat(context.getBeanNamesForType(LockProvider.class)).isEmpty();
      Thread.sleep(300);
      assertThat(context.getBean(ProbeJob.class).runs()).isZero();
      assertThat(lockRows()).isZero();
    }
  }
}
