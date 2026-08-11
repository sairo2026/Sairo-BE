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
    private Long createdMemberId;
    private Long createdOfficeId;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            if (createdOfficeId != null) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM office_membership WHERE office_id = ?")) {
                    ps.setLong(1, createdOfficeId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM office_registration WHERE resulting_office_id = ?")) {
                    ps.setLong(1, createdOfficeId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM office WHERE id = ?")) {
                    ps.setLong(1, createdOfficeId);
                    ps.executeUpdate();
                }
            }
            if (createdMemberId != null) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM email_verification_code WHERE member_id = ?")) {
                    ps.setLong(1, createdMemberId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM member WHERE id = ?")) {
                    ps.setLong(1, createdMemberId);
                    ps.executeUpdate();
                }
            }
        }
        executor.shutdownNow();
    }

    @Test
    void 이메일_인증코드_행을_두_트랜잭션이_동시에_FOR_UPDATE로_잡으면_직렬화된다() throws Exception {
        long codeId;
        try (Connection setup = dataSource.getConnection()) {
            createdMemberId = insertMember(setup, "회원", uniqueEmail("concurrency"));
            codeId = insertActiveCode(setup, createdMemberId);
        }

        assertSecondBlocksUntilFirstCommits(
                "SELECT id FROM email_verification_code WHERE id = ? FOR UPDATE", codeId);
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

        Callable<Boolean> secondAttempt = () -> {
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

        assertThat(isDoneWithin(future, 400)).as("두 번째 트랜잭션이 첫 번째보다 먼저 끝나면 안 된다").isFalse();

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

    @Test
    void 인증코드는_틀릴때만_시도횟수가_올라가고_5회째에_정확히_잠긴다() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            createdMemberId = insertMember(conn, "회원", uniqueEmail("lockout"));
            long codeId = insertActiveCode(conn, createdMemberId);

            for (int i = 1; i <= 4; i++) {
                int updated = tryWrongGuess(conn, codeId);
                assertThat(updated).as("오답 %d회차는 반영돼야 함", i).isEqualTo(1);
            }
            assertThat(status(conn, codeId)).isEqualTo("ACTIVE");
            assertThat(attemptCount(conn, codeId)).isEqualTo(4);

            int fifthWrong = tryWrongGuess(conn, codeId);
            assertThat(fifthWrong).isEqualTo(1);
            assertThat(status(conn, codeId)).isEqualTo("LOCKED");
            assertThat(attemptCount(conn, codeId)).isEqualTo(5);

            int sixthAttempt = tryWrongGuess(conn, codeId);
            assertThat(sixthAttempt).isEqualTo(0);
        }
    }

    @Test
    void 인증코드는_이전_오답이_있어도_정답이면_시도횟수를_건드리지_않고_성공한다() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            createdMemberId = insertMember(conn, "회원", uniqueEmail("late-success"));
            long codeId = insertActiveCode(conn, createdMemberId);

            for (int i = 1; i <= 4; i++) {
                tryWrongGuess(conn, codeId);
            }
            assertThat(attemptCount(conn, codeId)).isEqualTo(4);
            assertThat(status(conn, codeId)).isEqualTo("ACTIVE");

            int verified = tryCorrectGuess(conn, codeId);
            assertThat(verified).isEqualTo(1);
            assertThat(status(conn, codeId)).isEqualTo("VERIFIED");
            assertThat(attemptCount(conn, codeId)).as("정답 처리는 시도횟수를 올리지 않아야 함").isEqualTo(4);
        }
    }

    private long insertActiveCode(Connection conn, long memberId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO email_verification_code (member_id, purpose, target_email, challenge_id, code_hash, expires_at) "
                        + "VALUES (?, 'LOGIN', ?, gen_random_uuid(), 'hash', now() + interval '5 minutes') RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setString(2, uniqueEmail("code-target"));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int tryWrongGuess(Connection conn, long codeId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE email_verification_code SET "
                        + "attempt_count = attempt_count + 1, "
                        + "status = CASE WHEN attempt_count + 1 >= 5 THEN 'LOCKED' ELSE status END, "
                        + "invalidated_at = CASE WHEN attempt_count + 1 >= 5 THEN now() ELSE invalidated_at END "
                        + "WHERE id = ? AND status = 'ACTIVE' AND expires_at > now()")) {
            ps.setLong(1, codeId);
            return ps.executeUpdate();
        }
    }

    private int tryCorrectGuess(Connection conn, long codeId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE email_verification_code SET status = 'VERIFIED', verified_at = now() "
                        + "WHERE id = ? AND status = 'ACTIVE' AND expires_at > now()")) {
            ps.setLong(1, codeId);
            return ps.executeUpdate();
        }
    }

    private String status(Connection conn, long codeId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM email_verification_code WHERE id = ?")) {
            ps.setLong(1, codeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int attemptCount(Connection conn, long codeId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT attempt_count FROM email_verification_code WHERE id = ?")) {
            ps.setLong(1, codeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
