package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConcurrencyTest extends AbstractSchemaTest {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private Long createdUserId;
  private Long createdOfficeId;

  @AfterEach
  void cleanup() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      if (createdOfficeId != null) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM office WHERE id = ?")) {
          ps.setLong(1, createdOfficeId);
          ps.executeUpdate();
        }
      }
      if (createdUserId != null) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM app_user WHERE id = ?")) {
          ps.setLong(1, createdUserId);
          ps.executeUpdate();
        }
      }
    }
    executor.shutdownNow();
  }

  @Test
  void 사용자_행을_두_트랜잭션이_동시에_FOR_UPDATE로_잡으면_직렬화된다() throws Exception {
    try (Connection setup = dataSource.getConnection()) {
      createdUserId = insertUser(setup, "사용자", uniqueKakaoKey("concurrency"));
    }

    assertSecondBlocksUntilFirstCommits(
        "SELECT id FROM app_user WHERE id = ? FOR UPDATE", createdUserId);
  }

  @Test
  void 사무소_행을_두_트랜잭션이_동시에_FOR_UPDATE로_잡으면_직렬화된다() throws Exception {
    try (Connection setup = dataSource.getConnection()) {
      createdOfficeId = insertOffice(setup, "동시성테스트사무소", uniqueBizNo());
    }

    assertSecondBlocksUntilFirstCommits(
        "SELECT id FROM office WHERE id = ? FOR UPDATE", createdOfficeId);
  }

  private void assertSecondBlocksUntilFirstCommits(String forUpdateSql, long id) throws Exception {
    Connection first = dataSource.getConnection();
    first.setAutoCommit(false);
    try (PreparedStatement ps = first.prepareStatement(forUpdateSql)) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
    }

    Callable<Boolean> secondAttempt =
        () -> {
          try (Connection second = dataSource.getConnection()) {
            second.setAutoCommit(false);
            try (PreparedStatement ps = second.prepareStatement(forUpdateSql)) {
              ps.setLong(1, id);
              try (ResultSet rs = ps.executeQuery()) {
                rs.next();
              }
            }
            second.commit();
            return true;
          }
        };
    Future<Boolean> future = executor.submit(secondAttempt);

    assertThat(isDoneWithin(future, 400)).isFalse();

    first.commit();
    first.close();

    assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
  }

  private boolean isDoneWithin(Future<?> future, long millis) throws Exception {
    try {
      future.get(millis, TimeUnit.MILLISECONDS);
      return true;
    } catch (java.util.concurrent.TimeoutException e) {
      return false;
    }
  }
}
