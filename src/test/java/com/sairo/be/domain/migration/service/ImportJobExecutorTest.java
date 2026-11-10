package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ImportJobExecutorTest {

  @Test
  void 비동기_실행기는_요청_스레드와_다른_스레드에서_작업을_처리한다() throws Exception {
    AsyncImportJobExecutor executor = new AsyncImportJobExecutor();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> worker = new AtomicReference<>();
    try {
      executor.submit(
          () -> {
            worker.set(Thread.currentThread().getName());
            done.countDown();
          });

      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(worker.get())
          .startsWith("import-job-")
          .isNotEqualTo(Thread.currentThread().getName());
    } finally {
      executor.destroy();
    }
  }

  @Test
  void 비동기_실행기는_작업이_예외로_끝나도_다음_작업을_계속_처리한다() throws Exception {
    AsyncImportJobExecutor executor = new AsyncImportJobExecutor();
    CountDownLatch done = new CountDownLatch(1);
    try {
      executor.submit(
          () -> {
            throw new IllegalStateException("의도한 실패");
          });
      executor.submit(done::countDown);

      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.destroy();
    }
  }

  @Test
  void 동기_실행기는_작업이_끝난_뒤에_돌아온다() {
    SynchronousImportJobExecutor executor = new SynchronousImportJobExecutor();
    AtomicReference<String> state = new AtomicReference<>("대기");

    executor.submit(() -> state.set("완료"));

    assertThat(state.get()).isEqualTo("완료");
  }
}
