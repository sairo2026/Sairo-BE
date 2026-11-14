package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.SairoBeApplication;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationStartedEvent;

/** 운영 마이그레이션 컨테이너(migrate 프로필)는 앱 DataSource 없이 기동해야 한다. */
class MigrateProfileContextTest {

  private static final class StopAfterStart extends RuntimeException {
    StopAfterStart() {
      super("context refreshed, stop before runners");
    }
  }

  @Test
  void migrate_프로필은_앱_DataSource_없이_컨텍스트가_기동된다() {
    AtomicBoolean started = new AtomicBoolean();
    try {
      new SpringApplicationBuilder(SairoBeApplication.class)
          .web(WebApplicationType.NONE)
          .profiles("migrate")
          .properties(
              "DB_HOST=localhost",
              "DB_NAME=none",
              "DB_MIGRATOR_USERNAME=none",
              "DB_MIGRATOR_PASSWORD=none")
          .listeners(
              event -> {
                if (event instanceof ApplicationStartedEvent) {
                  started.set(true);
                  throw new StopAfterStart();
                }
              })
          .run("--spring.flyway.enabled=false");
    } catch (RuntimeException expected) {
      // 시작 직후 러너 실행 전에 일부러 중단한다.
    }
    assertThat(started).isTrue();
  }
}
