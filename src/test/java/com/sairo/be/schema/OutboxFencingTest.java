package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OutboxFencingTest extends AbstractSchemaTest {

    private Long officeRegistrationId;
    private Long officeId;
    private Long memberId;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            if (officeRegistrationId != null) {
                exec(conn, "DELETE FROM document_move_outbox WHERE office_registration_id = ?", officeRegistrationId);
                exec(conn, "DELETE FROM office_registration WHERE id = ?", officeRegistrationId);
            }
            if (officeId != null) {
                exec(conn, "DELETE FROM office WHERE id = ?", officeId);
            }
            if (memberId != null) {
                exec(conn, "DELETE FROM member WHERE id = ?", memberId);
            }
        }
    }

    @Test
    void 정상_흐름_PENDING에서_IN_PROGRESS를_거쳐_DONE까지() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long outboxId = setupRejectedRegistrationWithPendingOutbox(conn);

            UUID lockToken = acquireLease(conn, outboxId, "worker-1");
            assertThat(status(conn, outboxId)).isEqualTo("IN_PROGRESS");

            int completed = completeWithToken(conn, outboxId, lockToken);
            assertThat(completed).isEqualTo(1);
            assertThat(status(conn, outboxId)).isEqualTo("DONE");
        }
    }

    @Test
    void 실패하면_PENDING으로_돌아가고_재시도_횟수가_오른다() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long outboxId = setupRejectedRegistrationWithPendingOutbox(conn);
            UUID lockToken = acquireLease(conn, outboxId, "worker-1");

            int failed = failWithToken(conn, outboxId, lockToken, "원본 삭제 실패: 403");
            assertThat(failed).isEqualTo(1);
            assertThat(status(conn, outboxId)).isEqualTo("PENDING");
            assertThat(attemptCount(conn, outboxId)).isEqualTo(1);
        }
    }

    @Test
    void lease가_만료되면_다른_워커가_dispatch_쿼리로_회수한다() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long outboxId = setupRejectedRegistrationWithPendingOutbox(conn);
            acquireLease(conn, outboxId, "worker-1");

            exec(conn, "UPDATE document_move_outbox SET lock_expires_at = now() - interval '1 minute' WHERE id = ?", outboxId);

            assertThat(dispatchableIds(conn)).contains(outboxId);

            UUID newToken = acquireLease(conn, outboxId, "worker-2");
            assertThat(status(conn, outboxId)).isEqualTo("IN_PROGRESS");
            assertThat(lockToken(conn, outboxId)).isEqualTo(newToken);
        }
    }

    @Test
    void 늦게_돌아온_이전_워커는_옛_lock_token으로_완료_처리를_덮어쓰지_못한다() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            long outboxId = setupRejectedRegistrationWithPendingOutbox(conn);

            UUID staleToken = acquireLease(conn, outboxId, "worker-1");
            exec(conn, "UPDATE document_move_outbox SET lock_expires_at = now() - interval '1 minute' WHERE id = ?", outboxId);

            UUID freshToken = acquireLease(conn, outboxId, "worker-2");
            int worker2Completed = completeWithToken(conn, outboxId, freshToken);
            assertThat(worker2Completed).isEqualTo(1);
            assertThat(status(conn, outboxId)).isEqualTo("DONE");

            int worker1LateCompleted = completeWithToken(conn, outboxId, staleToken);
            assertThat(worker1LateCompleted).as("영향받은 행이 0개여야 함 — DB 상태를 절대 덮어쓰지 않는다").isEqualTo(0);
            assertThat(status(conn, outboxId)).as("worker-2가 만든 DONE 상태가 그대로 유지돼야 함").isEqualTo("DONE");
        }
    }

    @Test
    void outbox_상태_CHECK_IN_PROGRESS인데_lock_token이_없으면_실패한다() {
        assertConstraintViolation(null, conn -> {
            long outboxId = setupRejectedRegistrationWithPendingOutbox(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE document_move_outbox SET status = 'IN_PROGRESS', locked_at = now(), locked_by = 'x', lock_expires_at = now() + interval '5 minutes' "
                            + "WHERE id = ?")) {
                ps.setLong(1, outboxId);
                ps.executeUpdate();
            }
        });
    }

    private UUID acquireLease(Connection conn, long outboxId, String workerId) throws java.sql.SQLException {
        UUID token = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE document_move_outbox SET status='IN_PROGRESS', locked_at=now(), locked_by=?, lock_token=?, "
                        + "lock_expires_at = now() + interval '5 minutes' WHERE id = ?")) {
            ps.setString(1, workerId);
            ps.setObject(2, token);
            ps.setLong(3, outboxId);
            ps.executeUpdate();
        }
        return token;
    }

    private int completeWithToken(Connection conn, long outboxId, UUID token) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE document_move_outbox SET status='DONE', processed_at=now(), "
                        + "locked_at=NULL, locked_by=NULL, lock_token=NULL, lock_expires_at=NULL "
                        + "WHERE id = ? AND status = 'IN_PROGRESS' AND lock_token = ?")) {
            ps.setLong(1, outboxId);
            ps.setObject(2, token);
            return ps.executeUpdate();
        }
    }

    private int failWithToken(Connection conn, long outboxId, UUID token, String error) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE document_move_outbox SET status='PENDING', attempt_count = attempt_count + 1, "
                        + "next_attempt_at = now() + interval '1 minute', last_error = ?, "
                        + "locked_at=NULL, locked_by=NULL, lock_token=NULL, lock_expires_at=NULL "
                        + "WHERE id = ? AND status = 'IN_PROGRESS' AND lock_token = ?")) {
            ps.setString(1, error);
            ps.setLong(2, outboxId);
            ps.setObject(3, token);
            return ps.executeUpdate();
        }
    }

    private java.util.List<Long> dispatchableIds(Connection conn) throws java.sql.SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM document_move_outbox WHERE "
                        + "(status = 'PENDING' AND next_attempt_at <= now()) "
                        + "OR (status = 'IN_PROGRESS' AND lock_expires_at < now())")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private long setupRejectedRegistrationWithPendingOutbox(Connection conn) throws java.sql.SQLException {
        memberId = insertMember(conn, "신청자", uniqueEmail("outbox"));
        officeId = insertOffice(conn, "반려사무소", uniqueBizNo());

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO office_registration (applicant_member_id, business_registration_number, status, rejection_reason, reviewed_at) "
                        + "VALUES (?, ?, 'REJECTED', '증빙 불충분', now()) RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setString(2, uniqueBizNo());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                officeRegistrationId = rs.getLong(1);
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO document_move_outbox (office_registration_id, task_type) VALUES (?, 'MOVE_TO_PENDING_DELETION') RETURNING id")) {
            ps.setLong(1, officeRegistrationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void exec(Connection conn, String sql, long id) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private String status(Connection conn, long outboxId) throws java.sql.SQLException {
        return queryString(conn, "SELECT status FROM document_move_outbox WHERE id = ?", outboxId);
    }

    private int attemptCount(Connection conn, long outboxId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT attempt_count FROM document_move_outbox WHERE id = ?")) {
            ps.setLong(1, outboxId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private UUID lockToken(Connection conn, long outboxId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT lock_token FROM document_move_outbox WHERE id = ?")) {
            ps.setLong(1, outboxId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String queryString(Connection conn, String sql, long id) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
