package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.IntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AdvisoryLockOrderingIntegrationTest {

  @Autowired private AdvisoryLock advisoryLock;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void 키_순서가_서로_반대여도_교착_없이_모두_끝난다() throws Exception {
    List<String> forward = List.of("visit:member-quota:1", "visit:approval:2");
    List<String> backward = List.of("visit:approval:2", "visit:member-quota:1");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new java.util.ArrayList<>();
    for (List<String> keys : List.of(forward, backward)) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                int done = 0;
                for (int i = 0; i < 40; i++) {
                  transactionTemplate.executeWithoutResult(
                      status -> advisoryLock.lockAllForTransaction(keys));
                  done++;
                }
                return done;
              }));
    }
    start.countDown();
    for (Future<Integer> result : results) {
      assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(40);
    }
    pool.shutdown();
  }

  @Test
  void 같은_키가_겹쳐도_한_번만_잡는다() {
    transactionTemplate.executeWithoutResult(
        status ->
            advisoryLock.lockAllForTransaction(
                List.of("visit:approval:9", "visit:approval:9", "visit:member-quota:3")));
  }
}
