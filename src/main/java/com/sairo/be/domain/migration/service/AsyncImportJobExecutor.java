package com.sairo.be.domain.migration.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("!migrate")
@Component
@ConditionalOnProperty(
    name = "sairo.migration.import-executor",
    havingValue = "async",
    matchIfMissing = true)
public class AsyncImportJobExecutor implements ImportJobExecutor, DisposableBean {

  private static final int WORKERS = 2;

  private final ExecutorService pool;

  public AsyncImportJobExecutor() {
    AtomicInteger sequence = new AtomicInteger();
    this.pool =
        Executors.newFixedThreadPool(
            WORKERS,
            runnable -> {
              Thread thread = new Thread(runnable, "import-job-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public void submit(Runnable job) {
    pool.execute(
        () -> {
          try {
            job.run();
          } catch (RuntimeException e) {
            log.error("데이터 이전 작업이 예기치 않게 중단됐습니다.", e);
          }
        });
  }

  @Override
  public void destroy() {
    pool.shutdown();
  }
}
