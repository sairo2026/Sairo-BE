package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RateLimitCounterTest extends AbstractSchemaTest {

    @Test
    void 같은_bucket_key로_UPSERT를_순차_반복하면_요청수가_1씩_증가하고_행은_하나만_유지된다() throws Exception {
        withRollback(conn -> {
            String bucketKey = uniqueBucketKey();

            assertThat(upsertAndGetCount(conn, bucketKey)).isEqualTo(1);
            assertThat(upsertAndGetCount(conn, bucketKey)).isEqualTo(2);
            assertThat(upsertAndGetCount(conn, bucketKey)).isEqualTo(3);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM rate_limit_counter WHERE bucket_key = ?")) {
                ps.setString(1, bucketKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("bucket_key는 PK이므로 행이 하나만 있어야 함").isEqualTo(1);
                }
            }
        });
    }

    @Test
    void 같은_bucket_key를_여러_커넥션이_동시에_UPSERT해도_요청수를_잃어버리지_않는다() throws Exception {
        String bucketKey = uniqueBucketKey();
        int concurrentCalls = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCalls);
        CountDownLatch startBarrier = new CountDownLatch(concurrentCalls);
        CountDownLatch releaseGate = new CountDownLatch(1);

        try {
            java.util.List<Future<Integer>> futures = IntStream.range(0, concurrentCalls)
                    .mapToObj(i -> executor.submit(() -> {
                        startBarrier.countDown();
                        releaseGate.await();
                        try (Connection conn = dataSource.getConnection()) {
                            return upsertAndGetCount(conn, bucketKey);
                        }
                    }))
                    .toList();

            assertThat(startBarrier.await(5, TimeUnit.SECONDS))
                    .as("모든 스레드가 커넥션을 얻고 대기 상태까지 도달해야 함").isTrue();
            releaseGate.countDown();

            java.util.List<Integer> observedCounts = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) {
                observedCounts.add(f.get(10, TimeUnit.SECONDS));
            }

            assertThat(observedCounts).as("동시에 들어온 %d개 UPSERT가 반환한 request_count는 서로 달라야 함(직렬화 증거)", concurrentCalls)
                    .hasSize(concurrentCalls)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, concurrentCalls).boxed().toList());

            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT request_count FROM rate_limit_counter WHERE bucket_key = ?")) {
                    ps.setString(1, bucketKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as("최종 request_count는 성공한 동시 호출 수와 정확히 같아야 함")
                                .isEqualTo(concurrentCalls);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM rate_limit_counter WHERE bucket_key = ?")) {
                    ps.setString(1, bucketKey);
                    ps.executeUpdate();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 윈도우가_지나면_요청수가_1로_리셋된다() throws Exception {
        withRollback(conn -> {
            String bucketKey = uniqueBucketKey();
            upsertAndGetCount(conn, bucketKey);
            upsertAndGetCount(conn, bucketKey);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE rate_limit_counter SET window_started_at = now() - interval '1 hour' WHERE bucket_key = ?")) {
                ps.setString(1, bucketKey);
                ps.executeUpdate();
            }

            int afterWindowReset = upsertResetOnNewWindowAndGetCount(conn, bucketKey);
            assertThat(afterWindowReset).as("윈도우가 지나면 1로 리셋되어야 함").isEqualTo(1);
        });
    }

    @Test
    void request_count가_0이하이면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rate_limit_counter (bucket_key, window_started_at, request_count) VALUES (?, now(), 0)")) {
                ps.setString(1, uniqueBucketKey());
                ps.executeUpdate();
            }
        });
    }

    private int upsertAndGetCount(Connection conn, String bucketKey) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rate_limit_counter (bucket_key, window_started_at, request_count) VALUES (?, now(), 1) "
                        + "ON CONFLICT (bucket_key) DO UPDATE SET request_count = rate_limit_counter.request_count + 1 "
                        + "RETURNING request_count")) {
            ps.setString(1, bucketKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int upsertResetOnNewWindowAndGetCount(Connection conn, String bucketKey) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rate_limit_counter (bucket_key, window_started_at, request_count) VALUES (?, now(), 1) "
                        + "ON CONFLICT (bucket_key) DO UPDATE SET "
                        + "request_count = CASE WHEN rate_limit_counter.window_started_at <= now() - interval '1 hour' "
                        + "THEN 1 ELSE rate_limit_counter.request_count + 1 END, "
                        + "window_started_at = CASE WHEN rate_limit_counter.window_started_at <= now() - interval '1 hour' "
                        + "THEN now() ELSE rate_limit_counter.window_started_at END "
                        + "RETURNING request_count")) {
            ps.setString(1, bucketKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String uniqueBucketKey() {
        return "test:" + UUID.randomUUID();
    }
}
